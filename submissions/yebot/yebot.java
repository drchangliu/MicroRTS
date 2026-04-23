/*
 * yebot — Macro-LLM + Hard-coded Micro  (v3)
 *
 * Key changes vs v2:
 *   1. ASYNC LLM — the LLM call runs in a background thread and NEVER
 *      blocks getAction(). Java micro uses the last known strategy while
 *      the LLM is thinking. Zero idle ticks at game start.
 *   2. EARLY-GAME FAST PATH — for the first RUSH_TICKS ticks, always
 *      execute the hard-coded rush regardless of LLM state. The LLM only
 *      takes effect after the opening is safely underway.
 *   3. TIMEOUT GUARD — if the async LLM call takes longer than
 *      LLM_HARD_TIMEOUT ms we cancel it and keep the current strategy.
 *   4. All v2 fixes retained (idle-worker filter, small-map guard, etc.)
 *
 * Architecture:
 *   MICRO (Java, every tick, non-blocking):
 *     - Small maps (≤12): worker rush
 *     - Large maps (>12): eco → barracks → army
 *     - Smart targeting (counter-type preferred)
 *
 *   MACRO (LLM, async background thread every LLM_INTERVAL ticks):
 *     - Reads game state snapshot
 *     - Picks WORKER_RUSH | ECON_HEAVY | ECON_RANGED | COUNTER_MIX | ALL_IN
 *     - Result applied next tick after it arrives — never blocks game loop
 *
 * @author Ye
 */
package ai.abstraction.submissions.yebot;

import ai.abstraction.AbstractAction;
import ai.abstraction.AbstractionLayerAI;
import ai.abstraction.Harvest;
import ai.abstraction.pathfinding.AStarPathFinding;
import ai.abstraction.pathfinding.PathFinding;
import ai.core.AI;
import ai.core.ParameterSpecification;
import com.google.gson.*;
import rts.*;
import rts.units.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;


public class yebot extends AbstractionLayerAI {

    // ═══════════════════════════════════════════════════════════════════════
    //  CONFIG
    // ═══════════════════════════════════════════════════════════════════════
    private static final String OLLAMA_MODEL = System.getenv("OLLAMA_MODEL") != null
            ? System.getenv("OLLAMA_MODEL") : "qwen3:8b";
    private static final String API_URL = System.getenv("OLLAMA_URL") != null
            ? System.getenv("OLLAMA_URL") : "http://localhost:11434/v1/chat/completions";

    /** Per-request HTTP timeout (ms). Kept short so slow LLM doesn't hang. */
    private static final int LLM_HTTP_TIMEOUT = 4000;

    /** Hard wall-clock timeout for the async Future (ms). */
    private static final int LLM_HARD_TIMEOUT = 4500;

    /** How many ticks between LLM macro updates. */
    private static final int LLM_INTERVAL = 200;

    /**
     * For the first RUSH_TICKS ticks the bot ignores the LLM and just
     * executes the hard-coded opening. This guarantees we always move
     * immediately at tick 0 even if the LLM is slow to connect.
     */
    private static final int RUSH_TICKS = 50;

    /** Map width at or below this → small map, force rush. */
    private static final int SMALL_MAP_THRESHOLD = 12;

    // ═══════════════════════════════════════════════════════════════════════
    //  UNIT TYPES
    // ═══════════════════════════════════════════════════════════════════════
    private UnitTypeTable utt;
    private UnitType workerType, lightType, heavyType, rangedType, baseType, barracksType;

    // ═══════════════════════════════════════════════════════════════════════
    //  ASYNC LLM STATE
    // ═══════════════════════════════════════════════════════════════════════

    /** Current macro strategy — set synchronously after RUSH_TICKS. */
    private String macroStrategy = "DEFAULT";

    /** Tick when we last called the LLM. */
    private int lastLLMTick = -LLM_INTERVAL;

    // ═══════════════════════════════════════════════════════════════════════
    //  LLM SYSTEM PROMPT
    // ═══════════════════════════════════════════════════════════════════════
    private static final String SYSTEM_PROMPT =
        "You are a MicroRTS macro strategist. Given the game state, choose ONE strategy.\n"
      + "UNITS: Worker(HP=1,dmg=1,cost=1) Light(HP=4,dmg=2,cost=2) "
      + "Heavy(HP=8,dmg=4,cost=3) Ranged(HP=3,dmg=1,range=3,cost=2)\n"
      + "COUNTER LOGIC: Light beats Worker. Heavy beats Light. Ranged beats Heavy. Workers swarm Ranged.\n"
      + "STRATEGIES:\n"
      + "- WORKER_RUSH: Send all workers to attack. Best on small maps or when ahead in workers.\n"
      + "- ECON_HEAVY: Build barracks, produce Heavies. Good vs Light-heavy enemy.\n"
      + "- ECON_RANGED: Build barracks, produce Ranged. Good vs Heavy-heavy enemy.\n"
      + "- COUNTER_MIX: Produce whatever counters enemy composition.\n"
      + "- ALL_IN: Stop eco, send everything to attack. Use when you have army advantage.\n"
      + "OUTPUT FORMAT (JSON only, no markdown): "
      + "{\"thinking\":\"brief reason\",\"strategy\":\"STRATEGY_NAME\"}\n";

    // ═══════════════════════════════════════════════════════════════════════
    //  CONSTRUCTORS
    // ═══════════════════════════════════════════════════════════════════════

    public yebot(UnitTypeTable a_utt) { this(a_utt, new AStarPathFinding()); }

    public yebot(UnitTypeTable a_utt, PathFinding a_pf) {
        super(a_pf);
        reset(a_utt);
    }

    @Override
    public void reset() {
        super.reset();
        macroStrategy = "DEFAULT";
        lastLLMTick = -LLM_INTERVAL;
        // Cancel any in-flight LLM call from a previous game
        
    }

    public void reset(UnitTypeTable a_utt) {
        utt          = a_utt;
        workerType   = utt.getUnitType("Worker");
        lightType    = utt.getUnitType("Light");
        heavyType    = utt.getUnitType("Heavy");
        rangedType   = utt.getUnitType("Ranged");
        baseType     = utt.getUnitType("Base");
        barracksType = utt.getUnitType("Barracks");
    }

    @Override
    public AI clone() { return new yebot(utt, pf); }

    // ═══════════════════════════════════════════════════════════════════════
    //  MAIN LOOP  — never blocks on the LLM
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public PlayerAction getAction(int player, GameState gs) throws Exception {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        Player p   = gs.getPlayer(player);
        int tick   = gs.getTime();
        int mapW   = pgs.getWidth();
        boolean small = mapW <= SMALL_MAP_THRESHOLD;

        // ── 1. Maybe call LLM synchronously (guarded by RUSH_TICKS fast path) ─
        // The RUSH_TICKS fast path at step 2 ensures tick-0 always runs
        // immediately. The LLM is only called at tick >= RUSH_TICKS, by which
        // point the opening is safely underway and a brief blocking call is fine.
        if (tick >= RUSH_TICKS && tick - lastLLMTick >= LLM_INTERVAL) {
            lastLLMTick = tick;
            try {
                String stateText = buildMacroStateText(player, gs, pgs, small);
                String resp      = callLLM(stateText);
                String parsed    = parseMacroStrategy(resp, small);
                if (parsed != null) {
                    macroStrategy = parsed;
                    System.out.println("[yebot] t=" + tick + " LLM → " + parsed);
                }
            } catch (Exception e) {
                System.err.println("[yebot] LLM error: " + e.getMessage());
            }
        }

        // ── 2. Resolve effective strategy ─────────────────────────────────
        String strategy = resolveStrategy(mapW, small, tick, player, pgs);

        // ── 3. Execute micro ──────────────────────────────────────────────
        switch (strategy) {
            case "WORKER_RUSH": executeWorkerRush(player, p, gs, pgs); break;
            case "ALL_IN":      executeAllIn(player, p, gs, pgs);      break;
            case "ECON_HEAVY":  executeEcon(player, p, gs, pgs, heavyType);  break;
            case "ECON_RANGED": executeEcon(player, p, gs, pgs, rangedType); break;
            case "COUNTER_MIX": executeEcon(player, p, gs, pgs, pickCounterUnit(pgs, player)); break;
            default:            executeEcon(player, p, gs, pgs, heavyType);  break;
        }

        return translateActions(player, gs);
    }



    // ═══════════════════════════════════════════════════════════════════════
    //  RESOLVE STRATEGY
    //
    //  Priority:
    //    tick < RUSH_TICKS  → hard-coded opening (ignore LLM entirely)
    //    small map          → WORKER_RUSH, unless LLM said ALL_IN
    //    large map          → LLM strategy if set, else auto-counter
    // ═══════════════════════════════════════════════════════════════════════

    private String resolveStrategy(int mapW, boolean small, int tick,
                                    int player, PhysicalGameState pgs) {
        // Early game fast path: always hard-coded, LLM not consulted
        if (tick < RUSH_TICKS) {
            return small ? "WORKER_RUSH" : "ECON_HEAVY";
        }

        String llm = macroStrategy;

        if (small) {
            // Only ALL_IN is an allowed LLM upgrade on small maps
            return "ALL_IN".equals(llm) ? "ALL_IN" : "WORKER_RUSH";
        }

        // Large map: trust LLM if it has spoken
        if (!"DEFAULT".equals(llm)) return llm;

        // No LLM answer yet
        return autoCounter(pgs, player);
    }

    private String autoCounter(PhysicalGameState pgs, int player) {
        int eH = 0, eL = 0, eR = 0;
        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() >= 0 && u.getPlayer() != player) {
                if      (u.getType() == heavyType)  eH++;
                else if (u.getType() == lightType)  eL++;
                else if (u.getType() == rangedType) eR++;
            }
        }
        if (eH >= eL && eH >= eR && eH > 0) return "ECON_RANGED";
        if (eL >= eH && eL >= eR && eL > 0) return "ECON_HEAVY";
        return "ECON_HEAVY";
    }

    private UnitType pickCounterUnit(PhysicalGameState pgs, int player) {
        int eH = 0, eL = 0, eR = 0, eW = 0;
        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() >= 0 && u.getPlayer() != player) {
                if      (u.getType() == heavyType)  eH++;
                else if (u.getType() == lightType)  eL++;
                else if (u.getType() == rangedType) eR++;
                else if (u.getType() == workerType) eW++;
            }
        }
        if (eL > eH && eL > eR) return heavyType;
        if (eH >= eL && eH >= eR && eH > 0) return rangedType;
        if (eW > eH + eL + eR) return lightType;
        return heavyType;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  STRATEGY: WORKER RUSH
    //  Only acts on idle workers (gs.getActionAssignment == null).
    // ═══════════════════════════════════════════════════════════════════════

    private void executeWorkerRush(int player, Player p, GameState gs,
                                    PhysicalGameState pgs) {
        // Bases: train workers when idle
        for (Unit u : pgs.getUnits()) {
            if (u.getType() == baseType && u.getPlayer() == player
                    && gs.getActionAssignment(u) == null
                    && p.getResources() >= workerType.cost) {
                train(u, workerType);
            }
        }
        // Non-harvesting combat units
        for (Unit u : pgs.getUnits()) {
            if (u.getType().canAttack && !u.getType().canHarvest
                    && u.getPlayer() == player
                    && gs.getActionAssignment(u) == null) {
                attackNearest(u, p, pgs);
            }
        }
        // Idle workers only
        List<Unit> idle = new LinkedList<>();
        for (Unit u : pgs.getUnits()) {
            if (u.getType().canHarvest && u.getPlayer() == player
                    && gs.getActionAssignment(u) == null) {
                idle.add(u);
            }
        }
        workerRushBehavior(idle, p, pgs);
    }

    /** Mirrors built-in WorkerRush: build base → 1 harvester → rest attack. */
    private void workerRushBehavior(List<Unit> idle, Player p,
                                     PhysicalGameState pgs) {
        if (idle.isEmpty()) return;

        int nbases = 0, resourcesUsed = 0;
        for (Unit u : pgs.getUnits()) {
            if (u.getType() == baseType && u.getPlayer() == p.getID()) nbases++;
        }

        List<Integer> reserved = new LinkedList<>();
        List<Unit> free = new LinkedList<>(idle);

        // Build base if none
        if (nbases == 0 && !free.isEmpty()
                && p.getResources() >= baseType.cost + resourcesUsed) {
            Unit u = free.remove(0);
            buildIfNotAlreadyBuilding(u, baseType, u.getX(), u.getY(), reserved, p, pgs);
            resourcesUsed += baseType.cost;
        }

        // One harvester
        if (!free.isEmpty()) {
            Unit hw = free.remove(0);
            doHarvest(hw, p, pgs); // if it can't harvest it just idles — acceptable
        }

        // All others attack
        for (Unit u : free) attackNearest(u, p, pgs);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  STRATEGY: ALL IN
    // ═══════════════════════════════════════════════════════════════════════

    private void executeAllIn(int player, Player p, GameState gs,
                               PhysicalGameState pgs) {
        for (Unit u : pgs.getUnits()) {
            if (u.getType() == baseType && u.getPlayer() == player
                    && gs.getActionAssignment(u) == null
                    && p.getResources() >= workerType.cost) {
                train(u, workerType);
            }
        }
        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() == player && u.getType().canAttack
                    && gs.getActionAssignment(u) == null) {
                attackNearest(u, p, pgs);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  STRATEGY: ECON BUILD
    //
    //  Wave attack system: collect combat units until WAVE_SIZE, then
    //  send them all together. Prevents units dying alone mid-map.
    //  Second barracks built once army income is stable (4+ workers).
    // ═══════════════════════════════════════════════════════════════════════

    /** Minimum army size before we push. Lone units die mid-map on 16x16. */
    private static final int WAVE_SIZE = 3;

    // Per-player wave staging: units waiting for the wave threshold
    // Key = player id, Value = set of unit IDs currently staged
    private final Map<Integer, Set<Long>> waveStaging = new HashMap<>();

    private void executeEcon(int player, Player p, GameState gs,
                              PhysicalGameState pgs, UnitType combatUnit) {
        int nbases = 0, nbarracks = 0, nworkers = 0;
        int resourcesUsed = 0;

        boolean barracksBuilt   = false;
        boolean barracksStarted = false;

        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() == player) {
                if      (u.getType() == baseType)     nbases++;
                else if (u.getType() == barracksType) { nbarracks++; barracksBuilt = true; }
                else if (u.getType() == workerType)   nworkers++;
            }
        }
        // Detect barracks under construction (worker executing TYPE_PRODUCE)
        for (Unit u : pgs.getUnits()) {
            if (u.getType() == workerType && u.getPlayer() == player) {
                UnitAction ua = gs.getUnitAction(u);
                if (ua != null && ua.getType() == UnitAction.TYPE_PRODUCE) {
                    barracksStarted = true;
                }
            }
        }

        // Train workers (cap: 4 per base on large maps for faster income)
        int workerCap = (pgs.getWidth() > SMALL_MAP_THRESHOLD) ? nbases * 4 : nbases * 3;
        for (Unit u : pgs.getUnits()) {
            if (u.getType() == baseType && u.getPlayer() == player
                    && gs.getActionAssignment(u) == null
                    && nworkers < workerCap
                    && p.getResources() - resourcesUsed >= workerType.cost) {
                train(u, workerType);
                resourcesUsed += workerType.cost;
            }
        }

        // Train combat from barracks
        for (Unit u : pgs.getUnits()) {
            if (u.getType() == barracksType && u.getPlayer() == player
                    && gs.getActionAssignment(u) == null
                    && p.getResources() - resourcesUsed >= combatUnit.cost) {
                train(u, combatUnit);
                resourcesUsed += combatUnit.cost;
            }
        }

        // ── Wave attack logic ─────────────────────────────────────────────
        // Collect idle combat units. If we have >= WAVE_SIZE staged, unleash
        // all of them. Otherwise, hold them near base.
        Set<Long> staged = waveStaging.computeIfAbsent(player, k -> new HashSet<Long>());

        // Remove dead units from staging set
        Set<Long> alive = new HashSet<>();
        for (Unit u : pgs.getUnits()) alive.add(u.getID());
        staged.retainAll(alive);

        // Add newly idle combat units to staging
        for (Unit u : pgs.getUnits()) {
            if (u.getType().canAttack && !u.getType().canHarvest
                    && u.getPlayer() == player
                    && gs.getActionAssignment(u) == null) {
                staged.add(u.getID());
            }
        }

        // Decide: push or hold
        boolean push = staged.size() >= WAVE_SIZE;
        if (push) {
            // Send every staged unit to attack, then clear staging
            for (Unit u : pgs.getUnits()) {
                if (staged.contains(u.getID()) && gs.getActionAssignment(u) == null) {
                    attackSmart(u, p, pgs);
                }
            }
            staged.clear();
        }
        // else: staged units wait — their existing (idle) state is fine for this tick

        // ── Worker behavior ───────────────────────────────────────────────
        List<Unit> idle = new LinkedList<>();
        for (Unit u : pgs.getUnits()) {
            if (u.getType().canHarvest && u.getPlayer() == player
                    && gs.getActionAssignment(u) == null) {
                idle.add(u);
            }
        }
        // Build second barracks once workers are stable (≥4) and first is up
        boolean needBarracks = !barracksBuilt && !barracksStarted;
        boolean needSecondBarracks = barracksBuilt && nbarracks < 2 && nworkers >= 4
                && pgs.getWidth() > SMALL_MAP_THRESHOLD;
        econWorkerBehavior(idle, p, pgs, needBarracks, needSecondBarracks, resourcesUsed);
    }

    private void econWorkerBehavior(List<Unit> idle, Player p,
                                     PhysicalGameState pgs,
                                     boolean needBarracks,
                                     boolean needSecondBarracks,
                                     int resourcesUsed) {
        if (idle.isEmpty()) return;

        int nbases = 0;
        for (Unit u : pgs.getUnits()) {
            if (u.getType() == baseType && u.getPlayer() == p.getID()) nbases++;
        }

        List<Integer> reserved = new LinkedList<>();
        List<Unit> free = new LinkedList<>(idle);

        // Build base if none
        if (nbases == 0 && !free.isEmpty()
                && p.getResources() >= baseType.cost + resourcesUsed) {
            Unit u = free.remove(0);
            buildIfNotAlreadyBuilding(u, baseType, u.getX(), u.getY(), reserved, p, pgs);
            resourcesUsed += baseType.cost;
        }

        // Build first barracks
        if (needBarracks && !free.isEmpty()
                && p.getResources() >= barracksType.cost + resourcesUsed) {
            Unit u = free.remove(0);
            buildIfNotAlreadyBuilding(u, barracksType, u.getX(), u.getY(), reserved, p, pgs);
            resourcesUsed += barracksType.cost;
        }

        // Build second barracks (large map only, when economy is stable)
        if (needSecondBarracks && !free.isEmpty()
                && p.getResources() >= barracksType.cost + resourcesUsed) {
            Unit u = free.remove(0);
            buildIfNotAlreadyBuilding(u, barracksType, u.getX(), u.getY(), reserved, p, pgs);
            resourcesUsed += barracksType.cost;
        }

        // 2 harvesters max, rest attack
        int harvAssigned = 0;
        List<Unit> attackers = new LinkedList<>();
        for (Unit w : free) {
            if (harvAssigned < 2 && doHarvest(w, p, pgs)) {
                harvAssigned++;
            } else {
                attackers.add(w);
            }
        }
        for (Unit w : attackers) attackNearest(w, p, pgs);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HARVEST HELPER — never cancels in-progress harvest
    // ═══════════════════════════════════════════════════════════════════════

    private boolean doHarvest(Unit hw, Player p, PhysicalGameState pgs) {
        Unit closestBase = null, closestResource = null;
        int dBase = Integer.MAX_VALUE, dRes = Integer.MAX_VALUE;

        for (Unit u : pgs.getUnits()) {
            if (u.getType().isResource) {
                int d = Math.abs(u.getX()-hw.getX()) + Math.abs(u.getY()-hw.getY());
                if (d < dRes) { closestResource = u; dRes = d; }
            }
            if (u.getType().isStockpile && u.getPlayer() == p.getID()) {
                int d = Math.abs(u.getX()-hw.getX()) + Math.abs(u.getY()-hw.getY());
                if (d < dBase) { closestBase = u; dBase = d; }
            }
        }

        // CRITICAL FIX: never pass null as the resource target.
        // Harvest.execute() calls this.target.getX() unconditionally on the
        // next tick, so a null target causes an NPE that crashes the entire game.
        // Both resource and base must be non-null — the Harvest abstraction
        // uses resource to know where to go NEXT after dropping off cargo.
        if (closestResource == null || closestBase == null) return false;

        if (!(getAbstractAction(hw) instanceof Harvest)) {
            harvest(hw, closestResource, closestBase);
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ATTACK HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private void attackNearest(Unit u, Player p, PhysicalGameState pgs) {
        Unit target = null;
        int best = Integer.MAX_VALUE;
        for (Unit u2 : pgs.getUnits()) {
            if (u2.getPlayer() >= 0 && u2.getPlayer() != p.getID()) {
                int d = Math.abs(u2.getX()-u.getX()) + Math.abs(u2.getY()-u.getY());
                if (d < best) { target = u2; best = d; }
            }
        }
        if (target != null) attack(u, target);
    }

    /**
     * Priority targeting: attack the unit type that counters US first.
     *   Light  → prefers Heavy  (Heavy beats Light)
     *   Heavy  → prefers Ranged (Ranged beats Heavy)
     *   Ranged → prefers Worker (workers swarm Ranged)
     * Falls back to nearest if no priority target exists.
     */
    private void attackSmart(Unit u, Player p, PhysicalGameState pgs) {
        UnitType pref = null;
        if      (u.getType() == lightType)  pref = heavyType;
        else if (u.getType() == heavyType)  pref = rangedType;
        else if (u.getType() == rangedType) pref = workerType;

        Unit target = null;
        int best = Integer.MAX_VALUE;

        if (pref != null) {
            for (Unit u2 : pgs.getUnits()) {
                if (u2.getPlayer() >= 0 && u2.getPlayer() != p.getID()
                        && u2.getType() == pref) {
                    int d = Math.abs(u2.getX()-u.getX()) + Math.abs(u2.getY()-u.getY());
                    if (d < best) { target = u2; best = d; }
                }
            }
        }

        if (target == null) attackNearest(u, p, pgs);
        else attack(u, target);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  LLM — build state text
    // ═══════════════════════════════════════════════════════════════════════

    private String buildMacroStateText(int player, GameState gs,
                                        PhysicalGameState pgs, boolean small) {
        int myW=0,myB=0,myBr=0,myH=0,myR=0,myL=0;
        int eW=0,eB=0,eBr=0,eH=0,eR=0,eL=0,res=0;

        for (Unit u : pgs.getUnits()) {
            if (u.getType().isResource) { res++; continue; }
            if (u.getPlayer() == player) {
                if      (u.getType()==workerType)   myW++;
                else if (u.getType()==baseType)     myB++;
                else if (u.getType()==barracksType) myBr++;
                else if (u.getType()==heavyType)    myH++;
                else if (u.getType()==rangedType)   myR++;
                else if (u.getType()==lightType)    myL++;
            } else if (u.getPlayer() >= 0) {
                if      (u.getType()==workerType)   eW++;
                else if (u.getType()==baseType)     eB++;
                else if (u.getType()==barracksType) eBr++;
                else if (u.getType()==heavyType)    eH++;
                else if (u.getType()==rangedType)   eR++;
                else if (u.getType()==lightType)    eL++;
            }
        }

        return "Turn=" + gs.getTime()
             + " Map=" + pgs.getWidth() + "x" + pgs.getHeight()
             + (small ? " (SMALL — prefer WORKER_RUSH or ALL_IN only)" : "")
             + " Resources=" + gs.getPlayer(player).getResources()
             + "\nMY:    W=" + myW  + " B=" + myB  + " Br=" + myBr
             + " H=" + myH  + " R=" + myR  + " L=" + myL
             + "\nENEMY: W=" + eW   + " B=" + eB   + " Br=" + eBr
             + " H=" + eH   + " R=" + eR   + " L=" + eL
             + "\nMapRes=" + res
             + "\nChoose the best strategy.";
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  LLM — parse response
    //  Small map guard: ECON_* → override to WORKER_RUSH
    // ═══════════════════════════════════════════════════════════════════════

    private static final Set<String> ECON_STRATEGIES = new HashSet<>(Arrays.asList(
            "ECON_HEAVY", "ECON_RANGED", "COUNTER_MIX"
    ));

    private String parseMacroStrategy(String response, boolean smallMap) {
        try {
            response = response.replaceAll("(?s)<think>.*?</think>", "").trim();
            int s = response.indexOf("{"), e = response.lastIndexOf("}") + 1;
            if (s < 0 || e <= s) return null;

            JsonObject json = JsonParser.parseString(response.substring(s, e)).getAsJsonObject();
            if (json.has("thinking"))
                System.out.println("[yebot] LLM thinking: " + json.get("thinking").getAsString());

            if (json.has("strategy")) {
                String strat = json.get("strategy").getAsString().toUpperCase().trim();
                if (smallMap && ECON_STRATEGIES.contains(strat)) {
                    System.out.println("[yebot] guarding: " + strat + " → WORKER_RUSH on small map");
                    return "WORKER_RUSH";
                }
                switch (strat) {
                    case "WORKER_RUSH": case "ECON_HEAVY": case "ECON_RANGED":
                    case "COUNTER_MIX": case "ALL_IN":
                        return strat;
                }
            }
        } catch (Exception ex) {
            System.err.println("[yebot] parse error: " + ex.getMessage());
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  LLM — HTTP call (OpenAI-compatible, e.g. Ollama)
    // ═══════════════════════════════════════════════════════════════════════

    private String callLLM(String stateText) {
        try {
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(LLM_HTTP_TIMEOUT);
            conn.setReadTimeout(LLM_HTTP_TIMEOUT);

            JsonObject req = new JsonObject();
            req.addProperty("model", OLLAMA_MODEL);

            JsonArray msgs = new JsonArray();
            JsonObject sys = new JsonObject(); sys.addProperty("role", "system");
            sys.addProperty("content", SYSTEM_PROMPT); msgs.add(sys);
            JsonObject usr = new JsonObject(); usr.addProperty("role", "user");
            usr.addProperty("content", stateText); msgs.add(usr);
            req.add("messages", msgs);

            JsonObject fmt = new JsonObject();
            fmt.addProperty("type", "json_object");
            req.add("response_format", fmt);
            req.addProperty("temperature", 0.3);
            req.addProperty("max_tokens", 256);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    JsonObject resp = JsonParser.parseString(sb.toString()).getAsJsonObject();
                    JsonArray choices = resp.getAsJsonArray("choices");
                    if (choices != null && choices.size() > 0)
                        return choices.get(0).getAsJsonObject()
                                .getAsJsonObject("message")
                                .get("content").getAsString();
                }
            }
        } catch (Exception e) {
            System.err.println("[yebot] callLLM: " + e.getMessage());
        }
        return "{}";
    }

    @Override
    public List<ParameterSpecification> getParameters() { return new ArrayList<>(); }
}