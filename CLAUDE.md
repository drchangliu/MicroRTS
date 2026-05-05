# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Purpose

Fork of [MicroRTS](https://github.com/santiontanon/microrts) extended with LLM-powered game AI agents. Serves as:
1. **MicroRTS Game Engine** - Java-based RTS for AI research
2. **LLM Competition Platform** - 2026 IEEE WCCI MicroRTS LLM Game AI Competition
3. **LLM Benchmark Suite** - Measuring LLM game-playing performance

## Build & Run Commands

```bash
# Compile (Ant)
ant build

# Compile (manual)
find src -name '*.java' > sources.list
javac -cp "lib/*:bin" -d bin @sources.list

# Run headless game
java -cp "lib/*:bin" rts.MicroRTS -f resources/config.properties

# Run with GUI
java -cp "lib/*:bin" gui.frontend.FrontEnd

# Run tests (JUnit 4, requires ant build first)
ant test

# Run benchmark suite (requires Ollama running)
ollama serve &
./benchmark.sh

# Multi-game runner with timeout
./RunLoop.sh

# Build fat JAR
ant export_jar   # outputs build/microrts.jar
```

**Java target version:** 1.8. **Dependencies** are in `lib/` (Gson, JDom, Weka, JUnit 4).

## Behavioral Rules

1. **Do not change game rules or engine behavior** - The `rts/` package must remain unchanged
2. **Do not modify baseline AIs** - `RandomBiasedAI`, `RandomAI`, `PassiveAI` are reference implementations
3. **Prefer non-invasive changes** - Wrapper scripts, config files, new AI classes
4. **Avoid refactors** unless required for a specific task
5. **Leave TODO comments** rather than guessing at unclear requirements

## Architecture

### AI Class Hierarchy

All AI agents implement the `AI` abstract class. The key inheritance chain:

```
AI (abstract)
├── AIWithComputationBudget (enforces TIME_BUDGET & ITERATIONS_BUDGET)
│   ├── AbstractionLayerAI (high-level action abstraction layer)
│   │   ├── LLM agents: ollama, LLM_Gemini, LLM_DeepseekR1, mistral
│   │   ├── HybridLLMRush (rule-based strategies + periodic LLM consultation)
│   │   ├── StrategicLLMAgent (8 strategies + tactical parameters from LLM)
│   │   └── Rush strategies: WorkerRush, LightRush, HeavyRush, RangedRush
│   ├── InterruptibleAI
│   │   └── LLMInformedMCTS (AlphaGo-style MCTS with LLM policy priors)
│   └── MCTS variants, minimax, portfolio AIs
├── RandomBiasedAI, RandomAI, PassiveAI (baselines)
```

**AbstractionLayerAI** is the critical base class for LLM agents. It provides high-level actions (`move`, `attack`, `harvest`, `build`, `train`, `idle`) and converts them to low-level `UnitAction` objects via `translateActions()`. LLM agents override `getAction(int player, GameState gs)`.

### Game Loop

```
MicroRTS.main() → GameSettings.loadFromConfig() → Game.start()
  → Each frame: ai1.getAction() → PlayerAction, ai2.getAction() → PlayerAction
  → gs.executePlayerAction() → check win conditions → increment cycle
```

AI selection is **reflection-based**: `config.properties` specifies fully-qualified class names (e.g., `AI1=ai.abstraction.ollama`), which are instantiated via `Class.forName()` with a `UnitTypeTable` constructor parameter.

### LLM Agent Integration Pattern

All LLM agents follow the same pattern:
1. Compose game state description as text (map, units, resources, turn)
2. Query LLM (Ollama HTTP API, Gemini API, etc.) with JSON schema
3. Parse JSON response containing `thinking` and `moves` array
4. Convert moves to `AbstractAction` objects → `translateActions()` → `PlayerAction`

**Environment variables:** `OLLAMA_HOST` (default `http://localhost:11434`), `OLLAMA_MODEL` (e.g., `llama3.1:8b`), `OLLAMA_MODEL_P2` (for LLM-vs-LLM with `ollama2`).

### LLM-Guided Search (Advanced Agents)

Three tiers of LLM integration exist:
- **Pure LLM** (`ollama.java`): Every decision from LLM, `LLM_INTERVAL=100` cycles between calls
- **Hybrid** (`HybridLLMRush`, `StrategicLLMAgent`): Rule-based execution with periodic LLM strategy consultation (every 200-500 ticks)
- **Search + LLM** (`LLMInformedMCTS`): MCTS tree search biased by LLM policy priors (cached 300 ticks) and strategic goal evaluation (cached 500 ticks). ~2-3 LLM calls per 500 ticks

Supporting classes:
- `src/ai/evaluation/LLMStrategicEvaluation.java` - Goal-aligned MCTS evaluation
- `src/ai/stochastic/LLMPolicyProbabilityDistribution.java` - Cached action probability priors
- `src/ai/mcts/llmguided/LLMInformedMCTS.java` - Policy prior + goal evaluation MCTS

### Core Game Engine (read-only)

- `rts/GameState.java` - Mutable game state with unit tracking
- `rts/PhysicalGameState.java` - Map and unit/building positions (loaded from XML)
- `rts/PlayerAction.java` - Collection of Unit → UnitAction pairs
- `rts/UnitAction.java` - Low-level unit commands
- `rts/Game.java` - Main game loop

### Adding a New LLM Agent

1. Create `src/ai/abstraction/NewAgent.java` extending `AbstractionLayerAI`
2. Implement `getAction(int player, GameState gs)` - query LLM, parse response, return actions
3. Must have constructor taking `UnitTypeTable` parameter (for reflection-based instantiation)
4. Set `AI1=ai.abstraction.NewAgent` in `resources/config.properties`

## Configuration

Edit `resources/config.properties` to change game settings:
- `AI1` / `AI2` - Fully-qualified AI class names
- `map_location` - Map XML path (maps in `maps/` directory, 8x8 through 24x24)
- `max_cycles` - Game length limit (default 5000)
- `headless` - `true` for no GUI
- `launch_mode` - `STANDALONE`, `GUI`, `SERVER`, `CLIENT`

## Key Documentation Files

| File | Purpose |
|------|---------|
| `LLM_PROMPTS.md` | LLM prompt format spec and JSON response schema |
| `COMPETITION.md` | IEEE WCCI 2026 competition rules |
| `BENCHMARKING.md` | How to benchmark LLMs |
| `PROJECT_STRUCTURE.md` | Full codebase navigation reference |

## Experiment Discipline

- Use fixed seeds where possible
- Log: model name, map, opponent, max_cycles
- One model at a time, one clear output file per experiment
- Output format: `model=llama3.1:8b opponent=RandomBiasedAI map=8x8/basesWorkers8x8 game=1 winner=LLM ticks=4321`
