/*
 * Ironclad - deterministic, no-LLM MicroRTS competition agent.
 *
 * Strategy summary:
 *  - Scales worker count and barracks count to map size / resource availability.
 *  - Builds a barracks as soon as it is safely affordable, then keeps every
 *    production building busy at all times.
 *  - Chooses combat unit mix (Light / Heavy / Ranged) adaptively based on the
 *    composition of enemy units actually observed on the board (the game is
 *    fully observable, so this is exact, not guesswork).
 *  - Kites with Ranged units: retreats one tile when a melee unit closes to
 *    adjacency, otherwise stays at range and fires.
 *  - Reactively pulls idle-economy workers into combat to break early rushes
 *    (HeavyRush / LightRush) before a standing army exists, instead of the
 *    common bug of never actually reassigning workers that are already busy
 *    harvesting. One worker is always kept out of that rotation entirely so
 *    a running skirmish elsewhere can never starve our income to zero.
 *  - Idle workers with nothing to harvest (patch exhausted) convert to
 *    harassers instead of standing still.
 *
 * Known limitation: a pure worker-only swarm rush (WorkerRush) still beats
 * this bot on small maps. Several counters were tried (pulling workers into
 * defense 1-for-1, sending workers to hunt the enemy's own harvester,
 * fleeing every fight to protect the economy) and none of them won outright
 * - the first two lose the attrition race against an opponent that commits
 * its whole economy to expendable attackers, and pure fleeing starves our
 * own economy just as badly while also giving up ground against non-rush
 * opponents. The current build keeps the version that reliably beats
 * RandomBiasedAI / HeavyRush / LightRush rather than a WorkerRush-specific
 * change that regressed those.
 */
package ai.abstraction.submissions.ironclad;

import ai.abstraction.AbstractAction;
import ai.abstraction.AbstractionLayerAI;
import ai.abstraction.Build;
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

public class IroncladBot extends AbstractionLayerAI {

    protected UnitTypeTable utt;
    private UnitType workerType;
    private UnitType baseType;
    private UnitType barracksType;
    private UnitType lightType;
    private UnitType heavyType;
    private UnitType rangedType;

    // How close an enemy combat unit has to get to our economy before we
    // pull workers off harvesting to fight it.
    private static final int DEFENSE_RADIUS = 4;
    private static final boolean DEBUG = false;

    // One worker is always kept out of the defense rotation entirely and
    // dedicated purely to harvesting, so a prolonged skirmish elsewhere can
    // never drive our income all the way to zero for the rest of the game.
    private long protectedHarvesterId = -1;

    public IroncladBot(UnitTypeTable a_utt) {
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
        return new IroncladBot(utt);
    }

    @Override
    public PlayerAction getAction(int player, GameState gs) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        Player p = gs.getPlayer(player);
        int opponent = 1 - player;

        int area = pgs.getWidth() * pgs.getHeight();
        int tier = area <= 64 ? 0 : area <= 256 ? 1 : area <= 1024 ? 2 : 3;

        List<Unit> myWorkers = new ArrayList<>();
        List<Unit> myBases = new ArrayList<>();
        List<Unit> myBarracks = new ArrayList<>();
        List<Unit> myArmy = new ArrayList<>();
        List<Unit> enemyUnits = new ArrayList<>();
        List<Unit> enemyThreats = new ArrayList<>(); // any enemy unit that can attack
        List<Unit> enemyWorkers = new ArrayList<>();
        List<Unit> resources = new ArrayList<>();

        int enemyLight = 0, enemyHeavy = 0, enemyRanged = 0;

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
                if (t.canAttack) {
                    enemyThreats.add(u);
                    if (t == lightType) enemyLight++;
                    else if (t == heavyType) enemyHeavy++;
                    else if (t == rangedType) enemyRanged++;
                }
            }
        }

        // Keep exactly one worker permanently assigned to harvesting, immune to
        // the defense rotation, so a running skirmish elsewhere can never
        // starve our income all the way to zero.
        boolean protectedAlive = false;
        for (Unit u : myWorkers) {
            if (u.getID() == protectedHarvesterId) { protectedAlive = true; break; }
        }
        if (!protectedAlive) {
            List<Unit> candidates = new ArrayList<>();
            for (Unit u : myWorkers) {
                if (!(getAbstractAction(u) instanceof Build)) candidates.add(u);
            }
            Unit pick = !myBases.isEmpty()
                    ? nearest(candidates, myBases.get(0).getX(), myBases.get(0).getY())
                    : (candidates.isEmpty() ? null : candidates.get(0));
            protectedHarvesterId = pick != null ? pick.getID() : -1;
        }

        int workerTarget = workerTarget(tier, resources.size());
        int barracksTarget = barracksTarget(tier, myArmy.size(), p.getResources());

        // ---- Barracks bookkeeping (computed early so worker training can
        //      reserve resources for the barracks instead of starving it) ----
        int barracksBuilding = 0;
        for (Unit u : myWorkers) {
            AbstractAction aa = getAbstractAction(u);
            if (aa instanceof Build) barracksBuilding++; // this bot only ever constructs barracks
        }
        int totalBarracks = myBarracks.size() + barracksBuilding;
        boolean shouldAssignBuilder = totalBarracks < barracksTarget;
        // Keep reserving resources until the barracks physically exists, not
        // merely once a worker has been dispatched to build it - otherwise the
        // base immediately spends the reserved funds on a new worker the tick
        // after a builder is assigned (since totalBarracks already counts the
        // in-progress build), starving the construction of the resources it
        // still needs to actually complete.
        boolean needsBarracks = myBarracks.size() < barracksTarget;
        int barracksReserve = needsBarracks ? barracksType.cost : 0;

        // ---- Production: workers from bases ----
        // A small starting economy (2-3 workers) is always free to train - one
        // worker alone cannot both harvest and build, and a couple of parallel
        // harvesters keep some income flowing even while one is pulled into
        // defense. Beyond that floor, worker training reserves resources for
        // whatever the barracks pipeline needs next (the barracks itself while
        // it's under construction, then a small floor for its first units),
        // so a rush's constant trickle of 1-cost attackers can't perpetually
        // starve our tech by winning the "spend the next resource point" race.
        // Both players start with a banked 5 resources, and barracks cost
        // exactly 5 - so the fastest possible opening is to commit the
        // starting worker to the barracks immediately using that free bank,
        // rather than spending any of it training a second worker first
        // (which only delays reaching 5 again, since harvest round trips on
        // these maps take 70+ ticks each - training a worker "to help the
        // economy" before the barracks exists actually just slows the
        // barracks down for no compensating benefit before it exists).
        int minWorkersBeforeReserve = Math.min(workerTarget, 1);
        // Keep reserving for the army's next unit for as long as any
        // barracks exists, not just until the 2nd unit trains - otherwise
        // once army size reaches 2 the base stops holding anything back and
        // starts winning every future "spend the next resource point" race
        // against an idle barracks, permanently stalling production at
        // whatever army size happened to exist at that moment (observed:
        // army growth plateauing at 1-2 units for the rest of the game
        // while the opponent's production kept climbing).
        int armyReserve = !myBarracks.isEmpty() ? lightType.cost : 0;
        int totalReserve = barracksReserve + armyReserve;
        for (Unit u : myBases) {
            if (gs.getActionAssignment(u) != null) continue;
            if (myWorkers.size() >= workerTarget) continue;
            int need = workerType.cost + (myWorkers.size() < minWorkersBeforeReserve ? 0 : totalReserve);
            if (p.getResources() >= need) {
                train(u, workerType);
            }
        }

        // ---- Production: army from barracks (adaptive mix) ----
        int lightCount = countType(myArmy, lightType);
        int heavyCount = countType(myArmy, heavyType);
        int rangedCount = countType(myArmy, rangedType);
        for (Unit u : myBarracks) {
            if (gs.getActionAssignment(u) != null) continue;
            UnitType want = chooseCombatType(tier, enemyLight, enemyHeavy, enemyRanged,
                    lightCount, heavyCount, rangedCount, p.getResources());
            if (want == null) continue;
            train(u, want);
            if (want == lightType) lightCount++;
            else if (want == heavyType) heavyCount++;
            else if (want == rangedType) rangedCount++;
        }

        // ---- Barracks construction ----
        if (DEBUG) System.out.println("IRONCLAD_DEBUG t=" + gs.getTime() + " res=" + p.getResources()
                + " workers=" + myWorkers.size() + " bases=" + myBases.size() + " barracks=" + myBarracks.size()
                + " barracksBuilding=" + barracksBuilding + " army=" + myArmy.size()
                + " shouldAssignBuilder=" + shouldAssignBuilder + " needsBarracks=" + needsBarracks
                + " minWorkersBeforeReserve=" + minWorkersBeforeReserve + " armyReserve=" + armyReserve
                + " totalReserve=" + totalReserve + " workerTarget=" + workerTarget);
        if (shouldAssignBuilder && p.getResources() >= barracksType.cost) {
            // Pick the worker nearest our base as builder (not just the first
            // one found) - a worker that has wandered off toward the front
            // line is likely to die mid-construction, wasting the ~100-tick
            // build timer and restarting from zero with whoever is picked next.
            List<Unit> buildCandidates = new ArrayList<>();
            for (Unit u : myWorkers) {
                if (!(getAbstractAction(u) instanceof Build)) buildCandidates.add(u);
            }
            Unit builder = !myBases.isEmpty()
                    ? nearest(buildCandidates, myBases.get(0).getX(), myBases.get(0).getY())
                    : (buildCandidates.isEmpty() ? null : buildCandidates.get(0));
            if (DEBUG) System.out.println("IRONCLAD_DEBUG builder=" + builder);
            if (builder != null) {
                Unit anchor = nearest(myBases, builder.getX(), builder.getY());
                int bx = anchor != null ? anchor.getX() : builder.getX();
                int by = anchor != null ? anchor.getY() : builder.getY();
                List<Integer> reserved = new ArrayList<>();
                buildIfNotAlreadyBuilding(builder, barracksType, bx, by, reserved, p, pgs);
            }
        }

        // ---- Harvesting (everyone not building) ----
        List<Unit> spareWorkers = new LinkedList<>();
        for (Unit u : myWorkers) {
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
        List<Unit> nearbyThreats = new ArrayList<>();
        for (Unit t : enemyThreats) {
            if (nearestDistance(myBases, t.getX(), t.getY()) <= DEFENSE_RADIUS
                    || nearestDistance(myWorkers, t.getX(), t.getY()) <= 2) {
                nearbyThreats.add(t);
            }
        }
        // Worker-vs-worker fights are roughly even trades (1hp/1dmg each), so
        // matching the threat count 1-for-1 is enough - pulling extra workers
        // "for safety" just hands a rush opponent a permanent economic
        // stranglehold, since it can trickle in attackers forever and we would
        // otherwise never harvest again. Never pull more than half our
        // workforce at once so a sustained rush can't fully zero our economy.
        int homeDefenseForce = 0;
        for (Unit a : myArmy) {
            if (nearestDistance(myBases, a.getX(), a.getY()) <= DEFENSE_RADIUS + 3) homeDefenseForce++;
        }
        if (!nearbyThreats.isEmpty() && homeDefenseForce < nearbyThreats.size()) {
            int needed = Math.min(nearbyThreats.size() - homeDefenseForce, Math.max(1, myWorkers.size() / 2));
            // Never pull the protected harvester into the defense rotation
            // (unless it is literally our only worker) - it must keep
            // completing harvest round trips no matter what is happening
            // elsewhere, or a sustained rush stalls our income at zero.
            List<Unit> defenseCandidates = new ArrayList<>();
            for (Unit u : myWorkers) {
                if (u.getID() != protectedHarvesterId) defenseCandidates.add(u);
            }
            if (defenseCandidates.isEmpty()) defenseCandidates.addAll(myWorkers);
            List<Unit> defenders = kNearest(defenseCandidates, nearbyThreats.get(0).getX(), nearbyThreats.get(0).getY(), needed);
            for (Unit w : defenders) {
                AbstractAction aa = getAbstractAction(w);
                if (aa instanceof Build) continue; // never pull the builder
                Unit target = bestTarget(w, enemyUnits);
                if (target != null) attack(w, target);
            }
        }

        // ---- Spare workers with nothing better to do: harass ----
        // (never repurpose the protected harvester - if it currently has
        // nothing to harvest, just let it idle rather than sending it to
        // fight, since another patch may free up or it may need to path
        // back once the skirmish clears)
        for (Unit w : spareWorkers) {
            if (w.getID() == protectedHarvesterId) continue;
            Unit target = bestTarget(w, enemyUnits);
            if (target != null) attack(w, target);
        }

        // ---- Combat units ----
        for (Unit u : myArmy) {
            if (u.getType() == rangedType) {
                Unit meleeThreat = nearestWithinRange(enemyThreats, u.getX(), u.getY(), 1);
                if (meleeThreat != null) {
                    int[] retreat = bestRetreatTile(u, meleeThreat, pgs);
                    if (retreat != null) {
                        move(u, retreat[0], retreat[1]);
                        continue;
                    }
                }
            }
            Unit target = bestTarget(u, enemyUnits);
            if (target != null) attack(u, target);
        }

        return translateActions(player, gs);
    }

    // ---------- sizing helpers ----------

    private int workerTarget(int tier, int visibleResourcePatches) {
        int base;
        switch (tier) {
            case 0: base = 6; break;
            case 1: base = 6; break;
            case 2: base = 8; break;
            default: base = 10; break;
        }
        int patchBound = Math.max(3, visibleResourcePatches * 2);
        return Math.min(base, Math.max(3, patchBound));
    }

    private int barracksTarget(int tier, int armySize, int resources) {
        int cap;
        switch (tier) {
            case 0: cap = 2; break;
            case 1: cap = 2; break;
            case 2: cap = 3; break;
            default: cap = 4; break;
        }
        int target = 1;
        if (armySize >= 4 || resources >= 12) target = 2;
        if (armySize >= 10) target = 3;
        if (armySize >= 18) target = 4;
        return Math.min(cap, target);
    }

    private UnitType chooseCombatType(int tier, int enemyLight, int enemyHeavy, int enemyRanged,
                                       int myLight, int myHeavy, int myRanged, int resources) {
        double totalArmy = myLight + myHeavy + myRanged + 1.0;
        double lightRatio = myLight / totalArmy;
        double heavyRatio = myHeavy / totalArmy;
        double rangedRatio = myRanged / totalArmy;

        double targetLight = 0.45, targetHeavy = 0.4, targetRanged = 0.15;
        if (enemyRanged > 0) {
            // fragile 1hp ranged units die fast to a quick melee rush
            targetLight = 0.6; targetHeavy = 0.3; targetRanged = 0.1;
        } else if (enemyHeavy >= 2) {
            // don't trade heavy-for-heavy, kite with ranged instead
            targetLight = 0.25; targetHeavy = 0.2; targetRanged = 0.55;
        } else if (enemyLight >= 3 && enemyHeavy == 0) {
            // Heavy wins 1-for-1 but costs more and produces slower (3/120
            // vs 2/80), so a pure-Heavy response gets outproduced by a
            // sustained Light rush's faster tempo. Matching their tempo
            // with mostly-Light (with enough Heavy mixed in to punch
            // through) keeps unit count competitive instead of falling
            // behind on numbers while individually superior.
            targetLight = 0.55; targetHeavy = 0.3; targetRanged = 0.15;
        }
        if (tier >= 2) {
            // more room on the map to kite safely
            targetRanged = Math.min(0.6, targetRanged + 0.15);
            double excess = (targetLight + targetHeavy + targetRanged) - 1.0;
            targetHeavy = Math.max(0.1, targetHeavy - excess);
        }
        if (myLight + myHeavy + myRanged < 2) {
            // rush-proof opener: get something on the board fast (Light is cheapest/fastest)
            targetLight = Math.max(targetLight, 0.7);
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

    private int nearestDistanceBetween(List<Unit> as, List<Unit> bs) {
        int best = Integer.MAX_VALUE;
        for (Unit a : as) {
            int d = nearestDistance(bs, a.getX(), a.getY());
            if (d < best) best = d;
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

    private int[] bestRetreatTile(Unit u, Unit threat, PhysicalGameState pgs) {
        int[][] dirs = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
        int curDist = distance(u.getX(), u.getY(), threat.getX(), threat.getY());
        int[] best = null;
        int bestDist = curDist;
        for (int[] d : dirs) {
            int nx = u.getX() + d[0];
            int ny = u.getY() + d[1];
            if (nx < 0 || ny < 0 || nx >= pgs.getWidth() || ny >= pgs.getHeight()) continue;
            if (pgs.getUnitAt(nx, ny) != null) continue;
            int nd = distance(nx, ny, threat.getX(), threat.getY());
            if (nd > bestDist) { bestDist = nd; best = new int[]{nx, ny}; }
        }
        return best;
    }

    @Override
    public List<ParameterSpecification> getParameters() {
        return new ArrayList<>();
    }
}
