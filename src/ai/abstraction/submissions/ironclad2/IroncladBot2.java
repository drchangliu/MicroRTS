/*
 * Ironclad 2 - deterministic, no-LLM MicroRTS competition agent.
 *
 * This is a from-scratch redesign of the original Ironclad bot, aimed
 * specifically at fixing two measured weaknesses: losses to LightRush on
 * small maps and to HeavyRush on larger maps, plus the documented
 * vulnerability to a pure WorkerRush swarm.
 *
 * Root causes identified by reading the actual reference opponents
 * (ai.abstraction.HeavyRush / LightRush / WorkerRush):
 *
 *  1. HeavyRush and LightRush both cap their own worker count at 1 and
 *     send every combat unit to attack the instant it becomes idle, one
 *     at a time ("trickle" attacks) rather than massing an army. The
 *     first Ironclad fed its own units into fights the same way - each
 *     new unit attacked alone as soon as it spawned - so it kept losing
 *     1-for-1 trades against a technically weaker but never-idle
 *     opponent instead of using its own (much larger) economy advantage.
 *  2. The old emergency-defense code capped how many workers could be
 *     pulled into defense at "half the workforce", which silently
 *     under-reacted whenever an incoming swarm (a WorkerRush) was bigger
 *     than half our own worker count - exactly the case that matters.
 *
 * Ironclad 2's fix is a rally-and-mass posture: newly trained units hold
 * near home (intercepting anything that wanders into range - which
 * quietly neutralizes HeavyRush/LightRush's trickle attacks for free)
 * until either a real striking force has formed or the game clock says
 * it is time to stop waiting, and only then commits to an attack-move
 * against the enemy. The emergency worker-defense fallback (for the
 * brief window before any army exists) no longer artificially caps how
 * many workers can respond to a large incoming swarm.
 */
package ai.abstraction.submissions.ironclad2;

import ai.abstraction.AbstractAction;
import ai.abstraction.AbstractionLayerAI;
import ai.abstraction.Build;
import ai.abstraction.Harvest;
import ai.abstraction.pathfinding.AStarPathFinding;
import ai.core.AI;
import ai.core.ParameterSpecification;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import rts.GameState;
import rts.PhysicalGameState;
import rts.Player;
import rts.PlayerAction;
import rts.units.Unit;
import rts.units.UnitType;
import rts.units.UnitTypeTable;

public class IroncladBot2 extends AbstractionLayerAI {

    protected UnitTypeTable utt;
    private UnitType workerType;
    private UnitType baseType;
    private UnitType barracksType;
    private UnitType lightType;
    private UnitType heavyType;
    private UnitType rangedType;

    // How close an enemy combat unit has to get to our economy/army before
    // we react to it at all (either by pulling workers, in the very early
    // game, or by having our standing army intercept it).
    private static final int DEFENSE_RADIUS = 4;
    // Army units guarding the rally point will engage anything that comes
    // within this range without waiting for a full mass - this is what
    // quietly kills HeavyRush/LightRush's one-at-a-time attackers before
    // they ever reach the base.
    private static final int GARRISON_ENGAGE_RADIUS = DEFENSE_RADIUS + 4;
    private static final boolean DEBUG = false;

    // Roughly matches tournament/run_tournament.py's per-map cycle limits
    // (1500 / 3000 / 5000 / 8000 for 8x8 / 16x16 / 32x32 / 64x64). Used only
    // as a pacing heuristic so a long game doesn't stall into a timeout draw
    // while we wait for a "big enough" army that never quite arrives.
    private static final int[] TIER_MAX_CYCLES = {1500, 3000, 5000, 8000};

    // One worker is always kept out of the defense rotation entirely and
    // dedicated purely to harvesting, so a prolonged skirmish elsewhere can
    // never drive our income all the way to zero for the rest of the game.
    private long protectedHarvesterId = -1;

    public IroncladBot2(UnitTypeTable a_utt) {
        super(new AStarPathFinding());
        reset(a_utt);
    }

    @Override
    public void reset() {
        super.reset();
    }

    public void reset(UnitTypeTable a_utt) {
        utt = a_utt;
        workerType = utt.getUnitType("Worker");
        baseType = utt.getUnitType("Base");
        barracksType = utt.getUnitType("Barracks");
        lightType = utt.getUnitType("Light");
        heavyType = utt.getUnitType("Heavy");
        rangedType = utt.getUnitType("Ranged");
    }

    @Override
    public AI clone() {
        return new IroncladBot2(utt);
    }

    @Override
    public PlayerAction getAction(int player, GameState gs) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        Player p = gs.getPlayer(player);
        int opponent = 1 - player;

        int area = pgs.getWidth() * pgs.getHeight();
        int tier = area <= 64 ? 0 : area <= 256 ? 1 : area <= 1024 ? 2 : 3;
        int maxCycles = TIER_MAX_CYCLES[tier];

        List<Unit> myWorkers = new ArrayList<>();
        List<Unit> myBases = new ArrayList<>();
        List<Unit> myBarracks = new ArrayList<>();
        List<Unit> myArmy = new ArrayList<>();
        List<Unit> enemyUnits = new ArrayList<>();
        List<Unit> enemyThreats = new ArrayList<>(); // any enemy unit that can attack
        List<Unit> enemyWorkers = new ArrayList<>();
        List<Unit> resources = new ArrayList<>();

        int enemyLight = 0, enemyHeavy = 0, enemyRanged = 0, enemyBarracks = 0;

        for (Unit u : pgs.getUnits()) {
            UnitType t = u.getType();
            if (t.isResource) {
                resources.add(u);
                continue;
            }
            if (u.getPlayer() == player) {
                if (t == workerType) myWorkers.add(u);
                else if (t == baseType) myBases.add(u);
                else if (t == barracksType) myBarracks.add(u);
                else if (t.canAttack) myArmy.add(u);
            } else if (u.getPlayer() == opponent) {
                enemyUnits.add(u);
                if (t == workerType) enemyWorkers.add(u);
                if (t == barracksType) enemyBarracks++;
                if (t.canAttack) {
                    enemyThreats.add(u);
                    if (t == lightType) enemyLight++;
                    else if (t == heavyType) enemyHeavy++;
                    else if (t == rangedType) enemyRanged++;
                }
            }
        }

        // Recognize a pure economy-only rush signature (HeavyRush, LightRush
        // and WorkerRush all cap themselves - deliberately or as a side
        // effect - to a tiny worker economy and dump everything else into
        // attackers) so we know we can safely out-tech them once we survive
        // the opening, rather than mirroring their all-in aggression.
        boolean enemyIsWorkerSwarm = enemyWorkers.size() >= 3 && enemyThreats.size() == enemyWorkers.size()
                && enemyBarracks == 0;

        // Keep one worker permanently assigned to harvesting, immune to the
        // proactive defense rotation, so a running skirmish elsewhere can
        // never starve our income all the way to zero. This is re-evaluated
        // every tick rather than fixed at the start: the worker picked
        // first (before any other exists) is very often the one that ends
        // up building the barracks, and if we kept protecting that same ID
        // forever we would end up "protecting" the builder while leaving
        // the worker actually doing the harvesting exposed - exactly
        // backwards from the intent.
        Unit currentProtected = null;
        for (Unit u : myWorkers) {
            if (u.getID() == protectedHarvesterId) { currentProtected = u; break; }
        }
        boolean protectedNeedsReassignment = currentProtected == null
                || getAbstractAction(currentProtected) instanceof Build;
        if (protectedNeedsReassignment) {
            Unit pick = null;
            for (Unit u : myWorkers) {
                if (u.getID() == protectedHarvesterId) continue;
                if (getAbstractAction(u) instanceof Harvest) {
                    pick = u;
                    break;
                }
            }
            if (pick == null) {
                for (Unit u : myWorkers) {
                    if (u.getID() == protectedHarvesterId) continue;
                    if (!(getAbstractAction(u) instanceof Build)) { pick = u; break; }
                }
            }
            if (pick != null) {
                protectedHarvesterId = pick.getID();
            } else if (currentProtected == null) {
                protectedHarvesterId = -1;
            }
        }

        // Self-defense: a worker with only 1 hp dies to a single hit whether
        // or not it fights back, so if an enemy is already adjacent there is
        // no economic reason to hold fire - fight back (an even trade)
        // instead of dying for nothing. This applies even to the protected
        // harvester or the active builder; it is only the *proactive*
        // redirect-to-go-fight-something-distant behavior below that
        // spares them.
        List<Unit> selfDefending = new ArrayList<>();
        for (Unit u : myWorkers) {
            Unit adjacentThreat = nearestWithinRange(enemyThreats, u.getX(), u.getY(), 1);
            if (adjacentThreat != null) {
                Unit target = bestTarget(u, enemyUnits);
                if (target != null) {
                    attack(u, target);
                    selfDefending.add(u);
                }
            }
        }

        int workerTarget = workerTarget(tier, resources.size());
        int barracksTarget = barracksTarget(tier, myArmy.size(), p.getResources(), gs.getTime(), maxCycles);

        // ---- Barracks bookkeeping (computed early so worker training can
        //      reserve resources for the barracks instead of starving it) ----
        int barracksBuilding = 0;
        for (Unit u : myWorkers) {
            AbstractAction aa = getAbstractAction(u);
            if (aa instanceof Build) barracksBuilding++; // this bot only ever constructs barracks
        }
        int totalBarracks = myBarracks.size() + barracksBuilding;

        // 1 (a 2nd worker) + 5 (the barracks) = 6, more than the starting
        // bank of 5 - so training a 2nd worker and funding the barracks can
        // never both happen at the very start; only one can go first.
        //
        // Committing the single starting worker to the barracks immediately
        // (the original Ironclad's approach - fastest possible barracks,
        // ready ~t=100) was measured to do WORSE against WorkerRush than
        // this: with nobody left to harvest OR defend for that whole
        // window, both the lone worker and the base itself got run down by
        // the swarm before the barracks ever produced anything (a loss by
        // ~t=220). Training a 2nd worker first delays the barracks to
        // ~t=150, but means the original worker never stops harvesting and
        // there are two bodies instead of one to react to the rush - which
        // measured out to surviving meaningfully longer (~t=315) even
        // though the barracks was still finished too late to save the
        // game outright on this particular (tiny, equal-resource) map.
        // Bootstrapping first is kept as the better of the two known
        // options; don't even attempt to fund/build the barracks until it
        // is complete.
        int minWorkersBeforeReserve = Math.min(workerTarget, 2);
        boolean bootstrapped = myWorkers.size() >= minWorkersBeforeReserve;
        boolean shouldAssignBuilder = bootstrapped && totalBarracks < barracksTarget;
        if (DEBUG) {
            System.out.println("IRONCLAD2_DEBUG_SAB t=" + gs.getTime() + " barracksBuilding=" + barracksBuilding
                    + " myBarracks=" + myBarracks.size() + " barracksTarget=" + barracksTarget
                    + " bootstrapped=" + bootstrapped + " shouldAssignBuilder=" + shouldAssignBuilder);
        }
        // Keep reserving resources until the barracks physically exists, not
        // merely once a worker has been dispatched to build it - otherwise the
        // base immediately spends the reserved funds on a new worker the tick
        // after a builder is assigned, starving the construction of the
        // resources it still needs to actually complete. This reserve only
        // switches on once we're past the worker bootstrap above, so it
        // never competes with training worker #2.
        boolean needsBarracks = myBarracks.size() < barracksTarget;
        int barracksReserve = (bootstrapped && needsBarracks) ? barracksType.cost : 0;
        // Keep reserving for the army's next unit for as long as any barracks
        // exists, not just until the 2nd unit trains - otherwise once army
        // size reaches 2 the base stops holding anything back and starts
        // winning every future "spend the next resource point" race against
        // an idle barracks, permanently stalling production.
        int armyReserve = !myBarracks.isEmpty() ? lightType.cost : 0;
        int totalReserve = barracksReserve + armyReserve;
        // Money spent on a low-level action isn't actually deducted from
        // p.getResources() until that action completes - a produce() the
        // base issued at t=0 keeps its cost "reserved" against the bank for
        // its entire production time, but the raw bank figure doesn't drop
        // until it finishes. Checking affordability against the raw bank
        // alone (as an earlier version of this code did) lets us commit to
        // something we can't actually pay for - the engine silently rejects
        // the resulting action every tick from then on, freezing whichever
        // unit was assigned it. Subtracting gs.getResourceUsage() gives the
        // truly-uncommitted balance; tracking a running projected balance on
        // top of that as we decide each purchase below keeps those
        // decisions from then double-booking each other within this tick.
        int projectedResources = p.getResources() - gs.getResourceUsage().getResourcesUsed(player);
        for (Unit u : myBases) {
            if (gs.getActionAssignment(u) != null) continue;
            if (myWorkers.size() >= workerTarget) continue;
            int need = workerType.cost + (myWorkers.size() < minWorkersBeforeReserve ? 0 : totalReserve);
            if (projectedResources >= need) {
                train(u, workerType);
                projectedResources -= workerType.cost;
            }
        }

        // ---- Production: army from barracks (adaptive mix) ----
        int lightCount = countType(myArmy, lightType);
        int heavyCount = countType(myArmy, heavyType);
        int rangedCount = countType(myArmy, rangedType);
        for (Unit u : myBarracks) {
            if (gs.getActionAssignment(u) != null) continue;
            UnitType want = chooseCombatType(tier, enemyLight, enemyHeavy, enemyRanged, enemyIsWorkerSwarm,
                    lightCount, heavyCount, rangedCount, projectedResources);
            if (want == null) continue;
            train(u, want);
            projectedResources -= want.cost;
            if (want == lightType) lightCount++;
            else if (want == heavyType) heavyCount++;
            else if (want == rangedType) rangedCount++;
        }

        // ---- Barracks construction ----
        if (DEBUG) {
            System.out.println("IRONCLAD2_DEBUG t=" + gs.getTime() + " res=" + p.getResources()
                    + " workers=" + myWorkers.size() + " bases=" + myBases.size() + " barracks=" + myBarracks.size()
                    + " army=" + myArmy.size() + " enemyWorkerSwarm=" + enemyIsWorkerSwarm
                    + " enemyThreats=" + enemyThreats.size() + " protectedId=" + protectedHarvesterId
                    + " selfDefending=" + selfDefending.size());
        }
        if (shouldAssignBuilder && projectedResources >= barracksType.cost) {
            // Pick the worker nearest our base as builder (not just the first
            // one found) - a worker that has wandered off toward the front
            // line is likely to die mid-construction, wasting the build timer
            // and restarting from zero with whoever is picked next. Never
            // pick the protected harvester if any other worker is available -
            // it must keep generating income while the barracks goes up, or
            // we're back to the zero-income paralysis window this design
            // specifically avoids.
            List<Unit> buildCandidates = new ArrayList<>();
            for (Unit u : myWorkers) {
                if (u.getID() == protectedHarvesterId || selfDefending.contains(u)) continue;
                if (!(getAbstractAction(u) instanceof Build)) buildCandidates.add(u);
            }
            if (buildCandidates.isEmpty()) {
                for (Unit u : myWorkers) {
                    if (selfDefending.contains(u)) continue;
                    if (!(getAbstractAction(u) instanceof Build)) buildCandidates.add(u);
                }
            }
            Unit builder = !myBases.isEmpty()
                    ? nearest(buildCandidates, myBases.get(0).getX(), myBases.get(0).getY())
                    : (buildCandidates.isEmpty() ? null : buildCandidates.get(0));
            if (builder != null) {
                // Anchor the search for a free building tile on the builder's
                // OWN current position rather than the base's. By the time a
                // worker is picked here it's already the one nearest the
                // base, so this is still "near home" - but anchoring on the
                // base itself made the pathfinder route around the base
                // building to reach far-side tiles in some cases, which
                // could stall out into a left/right oscillation instead of
                // ever completing.
                int bx = builder.getX();
                int by = builder.getY();
                if (!(getAbstractAction(builder) instanceof Build)) {
                    // Deliberately not using buildIfNotAlreadyBuilding() here:
                    // it hands the Build action the AI's single SHARED
                    // AStarPathFinding instance, which every other unit's
                    // move/harvest/attack this same tick is also calling
                    // into. Interleaved use of that instance's internal
                    // search arrays (observed directly: a builder stuck
                    // alternating move-left/move-right forever, never
                    // reaching the point of actually placing the building)
                    // corrupts the path it computes. Giving the builder its
                    // own dedicated pathfinder avoids sharing that mutable
                    // state with whatever else is pathing this tick.
                    //
                    // Even with a dedicated pathfinder, targeting a tile
                    // found by the wider ring search (findBuildingPosition)
                    // can require the builder to move first, and when two
                    // of its immediate neighbors are equally good approach
                    // tiles the A* search can flip between them every tick
                    // and never settle - the builder oscillates left/right
                    // forever and never reaches "already adjacent, produce
                    // now". So check the builder's own 4 immediate
                    // orthogonal tiles directly first (same free-tile check
                    // Train.execute() uses for spawning) and build there
                    // when one is free: that target is already adjacent by
                    // construction, so Build.execute() can produce
                    // immediately with zero moves and no pathfinding
                    // ambiguity. Only fall back to the ring search - which
                    // may require actual travel - when all 4 are blocked.
                    int targetX = -1, targetY = -1;
                    if (by > 0 && gs.free(bx, by - 1)) { targetX = bx; targetY = by - 1; }
                    else if (bx < pgs.getWidth() - 1 && gs.free(bx + 1, by)) { targetX = bx + 1; targetY = by; }
                    else if (by < pgs.getHeight() - 1 && gs.free(bx, by + 1)) { targetX = bx; targetY = by + 1; }
                    else if (bx > 0 && gs.free(bx - 1, by)) { targetX = bx - 1; targetY = by; }
                    if (targetX >= 0) {
                        actions.put(builder, new Build(builder, barracksType,
                                targetX, targetY, new AStarPathFinding()));
                    } else {
                        int pos = findBuildingPosition(new ArrayList<>(), bx, by, p, pgs);
                        if (pos >= 0) {
                            actions.put(builder, new Build(builder, barracksType,
                                    pos % pgs.getWidth(), pos / pgs.getWidth(), new AStarPathFinding()));
                        }
                    }
                }
                projectedResources -= barracksType.cost;
                if (DEBUG) {
                    System.out.println("IRONCLAD2_DEBUG_BUILD t=" + gs.getTime() + " builder=" + builder.getID()
                            + " pos=(" + bx + "," + by + ") action=" + getAbstractAction(builder));
                }
            }
        }

        // ---- Harvesting (everyone not building or already fighting back) ----
        List<Unit> spareWorkers = new LinkedList<>();
        for (Unit u : myWorkers) {
            if (selfDefending.contains(u)) continue; // already acted this tick
            AbstractAction aa = getAbstractAction(u);
            if (aa instanceof Build) continue; // busy constructing
            Unit resource = nearest(resources, u.getX(), u.getY());
            Unit base = nearest(myBases, u.getX(), u.getY());
            if (resource != null && base != null) {
                harvest(u, resource, base);
            } else {
                // nothing to harvest (patches exhausted / no base) - repurpose
                spareWorkers.add(u);
            }
        }

        // ---- Emergency defense: pull workers off harvesting to fight rushes ----
        // This only matters in the brief window before any army unit exists
        // yet (roughly the first ~150-250 ticks); once we have a standing
        // garrison (below) it takes over home defense duty instead.
        List<Unit> nearbyThreats = new ArrayList<>();
        for (Unit t : enemyThreats) {
            if (nearestDistance(myBases, t.getX(), t.getY()) <= DEFENSE_RADIUS
                    || nearestDistance(myBarracks, t.getX(), t.getY()) <= DEFENSE_RADIUS
                    || nearestDistance(myWorkers, t.getX(), t.getY()) <= 2) {
                nearbyThreats.add(t);
            }
        }
        int homeDefenseForce = 0;
        for (Unit a : myArmy) {
            if (nearestDistance(myBases, a.getX(), a.getY()) <= GARRISON_ENGAGE_RADIUS) homeDefenseForce++;
        }
        if (!nearbyThreats.isEmpty() && homeDefenseForce < nearbyThreats.size()) {
            // Unlike the first Ironclad, this does NOT cap the response at
            // "half the workforce": under-reacting to a swarm bigger than
            // half our workers is exactly how a WorkerRush used to win. We
            // still always keep the one protected harvester out of it
            // (unless it is literally our only worker), so income never
            // drops to absolute zero even during an all-in defense.
            int needed = nearbyThreats.size() - homeDefenseForce;
            List<Unit> defenseCandidates = new ArrayList<>();
            for (Unit u : myWorkers) {
                if (u.getID() != protectedHarvesterId && !selfDefending.contains(u)) defenseCandidates.add(u);
            }
            if (defenseCandidates.isEmpty() && nearbyThreats.size() <= 1) {
                // Only risk the last remaining worker (our sole income
                // source) on a fair fight. Throwing it at a swarm bigger
                // than 1 just trades a certain loss now (no worker, no
                // barracks, no game) for a maybe-later one - keep it
                // harvesting/building instead and accept the building might
                // take some hits; a base has 10 hp and workers do 1 dmg
                // each, so that's rarely instantly fatal.
                defenseCandidates.addAll(myWorkers);
            }
            needed = Math.min(needed, defenseCandidates.size());
            List<Unit> defenders = kNearest(defenseCandidates, nearbyThreats.get(0).getX(), nearbyThreats.get(0).getY(), needed);
            for (Unit w : defenders) {
                AbstractAction aa = getAbstractAction(w);
                if (aa instanceof Build) continue; // never pull the builder
                Unit target = bestTarget(w, enemyUnits);
                if (target != null) attack(w, target);
            }
        }

        // ---- Spare workers with nothing better to do: harass ----
        // (never repurpose the protected harvester, and skip this entirely
        // while we're actively fending off a nearby threat - every idle hand
        // is better spent staying alive than picking a second fight)
        if (nearbyThreats.isEmpty()) {
            for (Unit w : spareWorkers) {
                if (w.getID() == protectedHarvesterId) continue;
                Unit target = bestTarget(w, enemyUnits);
                if (target != null) attack(w, target);
            }
        }

        // ---- Combat units: rally-and-mass instead of trickling out alone ----
        // Newly trained units hold near our base (killing anything that
        // wanders within GARRISON_ENGAGE_RADIUS along the way - this is what
        // neutralizes HeavyRush/LightRush's one-at-a-time attacks for free)
        // until either a real strike force has formed or the game clock says
        // it's time to stop waiting for one.
        int massThreshold = enemyIsWorkerSwarm ? 2 : (3 + tier);
        boolean pastPatienceWindow = gs.getTime() > (long) (maxCycles * 0.55);
        boolean readyToPush = myArmy.size() >= massThreshold || pastPatienceWindow;

        int rallyX, rallyY;
        if (!myBases.isEmpty()) {
            rallyX = myBases.get(0).getX();
            rallyY = myBases.get(0).getY();
        } else if (!myBarracks.isEmpty()) {
            rallyX = myBarracks.get(0).getX();
            rallyY = myBarracks.get(0).getY();
        } else if (!myArmy.isEmpty()) {
            rallyX = myArmy.get(0).getX();
            rallyY = myArmy.get(0).getY();
        } else {
            rallyX = -1;
            rallyY = -1;
        }

        for (Unit u : myArmy) {
            if (gs.getActionAssignment(u) != null) continue;

            if (u.getType() == rangedType) {
                Unit meleeThreat = nearestWithinRange(enemyThreats, u.getX(), u.getY(), 1);
                if (meleeThreat != null) {
                    int[] retreat = bestRetreatTile(u, enemyThreats, pgs);
                    if (retreat != null) {
                        move(u, retreat[0], retreat[1]);
                        continue;
                    }
                }
            }

            Unit nearThreat = nearestWithinRange(enemyThreats, u.getX(), u.getY(), GARRISON_ENGAGE_RADIUS);
            if (nearThreat != null) {
                // Something is close enough to matter (either it's attacking
                // us, or we're pushing and ran into resistance) - fight it,
                // preferring the overall priority target if one is in reach.
                Unit target = bestTarget(u, enemyUnits);
                if (target != null) attack(u, target);
            } else if (readyToPush) {
                // Mass (or the clock) says it's time: attack-move toward
                // whatever matters most on the board.
                Unit target = bestTarget(u, enemyUnits);
                if (target != null) attack(u, target);
            } else if (rallyX >= 0) {
                // Not enough force yet and nothing nearby to fight - hold at
                // the rally point instead of wandering out alone.
                if (distance(u.getX(), u.getY(), rallyX, rallyY) > 2) {
                    move(u, rallyX, rallyY);
                }
            }
        }

        if (DEBUG) {
            StringBuilder sb = new StringBuilder("IRONCLAD2_DEBUG_WORKERS t=" + gs.getTime());
            for (Unit u : myWorkers) {
                sb.append(" | id=").append(u.getID())
                        .append(u.getID() == protectedHarvesterId ? "*" : "")
                        .append(" hp=").append(u.getHitPoints())
                        .append(" cargo=").append(u.getResources())
                        .append(" pos=(").append(u.getX()).append(",").append(u.getY()).append(")")
                        .append(" action=").append(getAbstractAction(u));
            }
            System.out.println(sb.toString());
        }

        return translateActions(player, gs);
    }

    // ---------- sizing helpers ----------

    private int workerTarget(int tier, int visibleResourcePatches) {
        int base;
        switch (tier) {
            case 0: base = 4; break;
            case 1: base = 5; break;
            case 2: base = 6; break;
            default: base = 7; break;
        }
        int patchBound = Math.max(3, visibleResourcePatches * 2);
        return Math.min(base, Math.max(3, patchBound));
    }

    private int barracksTarget(int tier, int armySize, int resources, int time, int maxCycles) {
        int cap;
        switch (tier) {
            case 0: cap = 2; break;
            case 1: cap = 2; break;
            case 2: cap = 3; break;
            default: cap = 4; break;
        }
        int target = 1;
        // Get a 2nd production line earlier than the original Ironclad did -
        // a single barracks cannot out-produce an opponent that never stops
        // training, and both HeavyRush and LightRush never stop training.
        if (armySize >= 3 || resources >= 10 || time > maxCycles * 0.2) target = 2;
        if (armySize >= 9) target = 3;
        if (armySize >= 16) target = 4;
        return Math.min(cap, target);
    }

    private UnitType chooseCombatType(int tier, int enemyLight, int enemyHeavy, int enemyRanged,
                                       boolean enemyIsWorkerSwarm,
                                       int myLight, int myHeavy, int myRanged, int resources) {
        double totalArmy = myLight + myHeavy + myRanged + 1.0;
        double lightRatio = myLight / totalArmy;
        double heavyRatio = myHeavy / totalArmy;
        double rangedRatio = myRanged / totalArmy;

        // Default mix: mostly Light (cheap, fast, and it 1-shots workers and
        // trades evenly with Light) with Heavy for staying power and a
        // little Ranged for chip damage.
        double targetLight = 0.5, targetHeavy = 0.35, targetRanged = 0.15;

        if (enemyIsWorkerSwarm) {
            // A worker swarm dies in one hit to Light (2 dmg vs 1 hp) and
            // Light is the fastest/cheapest thing we can put on the board -
            // production speed matters far more here than unit quality.
            targetLight = 0.85; targetHeavy = 0.1; targetRanged = 0.05;
        } else if (enemyRanged > 0) {
            // fragile 1hp ranged units die fast to a quick melee rush
            targetLight = 0.6; targetHeavy = 0.3; targetRanged = 0.1;
        } else if (enemyHeavy >= 2) {
            // Mirror their Heavy investment for even trades instead of
            // going almost all-Ranged (fragile, and a single micro mistake
            // trades a 2-cost unit for nothing) - keep meaningful Ranged
            // support for kiting/chip damage without depending on it alone.
            targetLight = 0.25; targetHeavy = 0.45; targetRanged = 0.3;
        } else if (enemyLight >= 3 && enemyHeavy == 0) {
            // Heavy wins 1-for-1 but costs more and produces slower (3/120
            // vs 2/80), so a pure-Heavy response gets outproduced by a
            // sustained Light rush's faster tempo. Matching their tempo
            // with mostly-Light (with enough Heavy mixed in to punch
            // through) keeps unit count competitive.
            targetLight = 0.55; targetHeavy = 0.3; targetRanged = 0.15;
        }
        if (tier >= 2) {
            // more room on the map to kite safely
            targetRanged = Math.min(0.5, targetRanged + 0.1);
            double excess = (targetLight + targetHeavy + targetRanged) - 1.0;
            if (excess > 0) targetHeavy = Math.max(0.1, targetHeavy - excess);
        }
        if (myLight + myHeavy + myRanged < 2) {
            // rush-proof opener: get something on the board fast
            targetLight = Math.max(targetLight, 0.8);
        }

        double deficitLight = targetLight - lightRatio;
        double deficitHeavy = targetHeavy - heavyRatio;
        double deficitRanged = targetRanged - rangedRatio;

        UnitType[] byPreference;
        if (deficitLight >= deficitHeavy && deficitLight >= deficitRanged) {
            byPreference = new UnitType[]{lightType, heavyType, rangedType};
        } else if (deficitHeavy >= deficitRanged) {
            byPreference = new UnitType[]{heavyType, lightType, rangedType};
        } else {
            byPreference = new UnitType[]{rangedType, lightType, heavyType};
        }
        for (UnitType t : byPreference) {
            if (resources >= t.cost) return t;
        }
        return null;
    }

    // ---------- targeting / geometry helpers ----------

    private int countType(List<Unit> units, UnitType t) {
        int c = 0;
        for (Unit u : units) if (u.getType() == t) c++;
        return c;
    }

    private int distance(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    private Unit nearest(List<Unit> units, int x, int y) {
        Unit best = null;
        int bestD = Integer.MAX_VALUE;
        for (Unit u : units) {
            int d = distance(u.getX(), u.getY(), x, y);
            if (d < bestD) { bestD = d; best = u; }
        }
        return best;
    }

    private int nearestDistance(List<Unit> units, int x, int y) {
        int bestD = Integer.MAX_VALUE;
        for (Unit u : units) {
            int d = distance(u.getX(), u.getY(), x, y);
            if (d < bestD) bestD = d;
        }
        return bestD;
    }

    private Unit nearestWithinRange(List<Unit> units, int x, int y, int range) {
        Unit best = null;
        int bestD = Integer.MAX_VALUE;
        for (Unit u : units) {
            int d = distance(u.getX(), u.getY(), x, y);
            if (d <= range && d < bestD) { bestD = d; best = u; }
        }
        return best;
    }

    private List<Unit> kNearest(List<Unit> units, int x, int y, int k) {
        List<Unit> pool = new ArrayList<>(units);
        List<Unit> result = new ArrayList<>();
        while (!pool.isEmpty() && result.size() < k) {
            Unit best = nearest(pool, x, y);
            if (best == null) break;
            result.add(best);
            pool.remove(best);
        }
        return result;
    }

    /**
     * Target priority: live combat units first (neutralize threats), then
     * workers (economic damage), then buildings (finish the game). Within a
     * bucket, prefer the nearest, then the weakest (secure kills).
     */
    private Unit bestTarget(Unit from, List<Unit> enemies) {
        Unit best = null;
        int bestBucket = Integer.MAX_VALUE;
        int bestDist = Integer.MAX_VALUE;
        int bestHp = Integer.MAX_VALUE;
        for (Unit e : enemies) {
            UnitType t = e.getType();
            int bucket = t.isStockpile ? 2 : (t.canHarvest ? 1 : 0);
            int d = distance(from.getX(), from.getY(), e.getX(), e.getY());
            int hp = e.getHitPoints();
            if (bucket < bestBucket
                    || (bucket == bestBucket && d < bestDist)
                    || (bucket == bestBucket && d == bestDist && hp < bestHp)) {
                best = e; bestBucket = bucket; bestDist = d; bestHp = hp;
            }
        }
        return best;
    }

    /**
     * Picks the empty adjacent tile that maximizes the minimum distance to
     * every nearby threat (not just the single closest one), so a kiting
     * unit doesn't back itself into a second attacker or a corner.
     */
    private int[] bestRetreatTile(Unit u, List<Unit> threats, PhysicalGameState pgs) {
        List<Unit> nearby = new ArrayList<>();
        for (Unit t : threats) {
            if (distance(u.getX(), u.getY(), t.getX(), t.getY()) <= 3) nearby.add(t);
        }
        if (nearby.isEmpty()) return null;

        int[][] dirs = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
        int curMinDist = nearestDistance(nearby, u.getX(), u.getY());
        int[] best = null;
        int bestMinDist = curMinDist;
        for (int[] d : dirs) {
            int nx = u.getX() + d[0];
            int ny = u.getY() + d[1];
            if (nx < 0 || ny < 0 || nx >= pgs.getWidth() || ny >= pgs.getHeight()) continue;
            if (pgs.getUnitAt(nx, ny) != null) continue;
            int nd = nearestDistance(nearby, nx, ny);
            if (nd > bestMinDist) { bestMinDist = nd; best = new int[]{nx, ny}; }
        }
        return best;
    }

    @Override
    public List<ParameterSpecification> getParameters() {
        return new ArrayList<>();
    }
}
