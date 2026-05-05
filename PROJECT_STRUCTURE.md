# MicroRTS Project Structure

**Last Updated:** 2026-02-01
**Purpose:** Technical reference for navigating the codebase.

---

## 1. PROJECT OVERVIEW

This repository serves **three purposes**:
1. **MicroRTS Game Engine** - Complete fork of original MicroRTS for AI research
2. **LLM Competition Platform** - Template for 2026 IEEE WCCI MicroRTS LLM Game AI Competition
3. **LLM Benchmark Suite** - Tools to measure LLM game-playing performance

**Core Technology:** Java-based RTS game engine with LLM agent integrations via Ollama, Gemini, and other APIs.

**Repository Location:** `/home/liuc/gitwork/MicroRTS`
**Total Java Source Files:** 361
**Git History:** 20+ commits, recent focus on LLM agent integration

---

## 2. PROJECT STRUCTURE

```
MicroRTS/
├── src/                          # Main source code
│   ├── ai/                       # AI implementations (18 subdirectories)
│   │   ├── abstraction/          # High-level AI strategies
│   │   │   ├── ollama.java       # Ollama/local LLM agent implementation (84KB)
│   │   │   ├── LLM_Gemini.java   # Gemini API LLM agent (58KB)
│   │   │   ├── LLM_DeepseekR1.java # Deepseek R1 implementation (28KB)
│   │   │   ├── mistral.java      # Mistral LLM implementation (66KB)
│   │   │   ├── HeavyRush.java
│   │   │   ├── LightRush.java
│   │   │   ├── EconomyRush.java
│   │   │   ├── pathfinding/      # Pathfinding algorithms (A*, BFS, Greedy)
│   │   │   └── partialobservability/
│   │   ├── core/                 # Core AI interfaces
│   │   ├── RandomBiasedAI.java   # Baseline opponent
│   │   ├── RandomAI.java
│   │   ├── PassiveAI.java
│   │   ├── mcts/                 # Monte Carlo Tree Search
│   │   ├── minimax/              # Minimax search algorithms
│   │   └── ahtn/                 # Hierarchical Task Network planner
│   ├── rts/                      # Game engine core (19 files)
│   │   ├── Game.java             # Main game loop
│   │   ├── GameState.java        # Game state management (34KB)
│   │   ├── GameSettings.java     # Configuration management
│   │   ├── PhysicalGameState.java # Map & unit state (22KB)
│   │   ├── PlayerAction.java     # Action definition & validation
│   │   ├── UnitAction.java       # Low-level unit actions
│   │   └── MicroRTS.java         # Main entry point
│   ├── gui/                      # GUI components
│   │   ├── frontend/
│   │   │   ├── FrontEnd.java     # Main GUI entry point
│   │   │   ├── FEStatePane.java  # Game state UI panel
│   │   │   └── FETournamentPane.java # Tournament management
│   ├── tests/                    # Test classes (29+ files)
│   ├── tournaments/              # Tournament management
│   └── util/                     # Utility classes
├── bin/                          # Compiled Java classes
├── lib/                          # External dependencies
│   ├── bots/                     # Pre-compiled bot JARs (7 files)
│   ├── gson-2.10.1.jar           # JSON parsing
│   ├── jdom.jar                  # XML parsing
│   └── weka.jar                  # Machine learning library
├── maps/                         # Game maps (140+ XML files)
│   ├── 8x8/                      # Default benchmark map location
│   └── [4x4, 10x10, 12x12, 16x16, 24x24, BroodWar]
├── resources/
│   └── config.properties         # Game configuration file
├── logs/                         # Game execution logs
├── build.xml                     # Ant build configuration
│
├── # Documentation
├── README.md                     # Main entry point (3 purposes)
├── COMPETITION.md                # IEEE WCCI 2026 competition guide
├── BENCHMARKING.md               # LLM benchmarking instructions
├── MICRORTS_ORIGINAL.md          # Original MicroRTS documentation
├── LLM_PROMPTS.md                # LLM prompt format specification
├── PROJECT_STRUCTURE.md          # This file - codebase reference
├── GPU_SETUP.md                  # HPC/GPU setup instructions
├── CLAUDE.md                     # AI coding assistant instructions
├── CONTRIBUTING.md               # Contribution guidelines
│
├── # Scripts
├── benchmark.sh                  # Automated benchmark script
└── RunLoop.sh                    # Game execution loop script
```

---

## 3. BUILD SYSTEM

**Build Tool:** Apache Ant
**Java Version:** 1.8

**Build Commands:**
```bash
# Using Ant
ant build           # Compile to /bin
ant clean           # Remove compiled files
ant export_jar      # Create build/microrts.jar

# Manual compilation
find src -name '*.java' > sources.list
javac -cp "lib/*:bin" -d bin @sources.list
```

---

## 4. HOW TO RUN

### Standalone Game (Headless)
```bash
java -cp "lib/*:bin" rts.MicroRTS
java -cp "lib/*:bin" rts.MicroRTS -f resources/config.properties
```

### GUI Frontend
```bash
java -cp "lib/*:bin" gui.frontend.FrontEnd
```

### Benchmark Execution
```bash
./benchmark.sh      # Tests LLM models vs RandomBiasedAI
./RunLoop.sh        # Multiple game execution loop
```

### Server/Client Modes
```bash
java -cp "lib/*:bin" rts.MicroRTS -s 9898           # Server
java -cp "lib/*:bin" rts.MicroRTS -c 127.0.0.1 9898 # Client
```

---

## 5. CONFIGURATION

### Primary Config: `resources/config.properties`
```properties
launch_mode=STANDALONE          # STANDALONE, GUI, SERVER, CLIENT
map_location=maps/8x8/basesWorkers8x8.xml
max_cycles=5000                 # Max game length
headless=true                   # No GUI rendering
partially_observable=false      # Full information visibility
AI1=ai.abstraction.ollama       # Player 1 (LLM agent)
AI2=ai.RandomBiasedAI           # Player 2 (Opponent)
UTT_version=2                   # Unit type table version
```

### Environment Variables for LLM
```bash
export OLLAMA_HOST="http://localhost:11434"
export OLLAMA_MODEL="llama3.1:8b"   # or qwen3:14b
```

---

## 6. LLM AGENT IMPLEMENTATION

### Ollama Agent (`src/ai/abstraction/ollama.java`)

**Key Features:**
- Queries local Ollama models via HTTP
- Reads `OLLAMA_HOST` and `OLLAMA_MODEL` from environment
- Supported models: llama3.1:8b, mistral, qwen variants, deepseek-r1
- `LLM_INTERVAL=100` cycles between decisions

**Response Format Expected:**
```json
{
  "thinking": "strategic reasoning...",
  "moves": [
    {
      "raw_move": "(x, y): unit_type action(...)",
      "unit_position": [x, y],
      "unit_type": "worker|light|heavy|ranged|base|barracks",
      "action_type": "move|attack|harvest|build|train|idle"
    }
  ]
}
```

### Other LLM Implementations
- `LLM_Gemini.java` - Google Gemini API
- `LLM_DeepseekR1.java` - Deepseek R1
- `mistral.java` - Mistral API

---

## 7. KEY CLASSES

### Game Engine (rts package)
| Class | Purpose |
|-------|---------|
| `Game.java` | Main game loop orchestrator |
| `GameState.java` | Game state, unit management, action execution |
| `GameSettings.java` | Configuration loading |
| `PhysicalGameState.java` | Map representation, unit/building storage |
| `PlayerAction.java` | Player's turn action set |
| `UnitAction.java` | Individual unit action types |
| `MicroRTS.java` | Entry point, launch mode handling |

### AI Classes
| Class | Purpose |
|-------|---------|
| `ollama.java` | Ollama LLM integration |
| `RandomBiasedAI.java` | Benchmark opponent |
| `AbstractionLayerAI.java` | Base class for strategy AIs |
| `HeavyRush.java` | Heavy unit rush strategy |
| `LightRush.java` | Light unit rush strategy |

---

## 8. GAME MECHANICS

**Win Condition:** Eliminate all opposing units and buildings

**Unit Types:**
| Type | HP | Cost | Damage | Range | Speed |
|------|----|----|--------|-------|-------|
| Worker | 1 | 1 | 1 | 1 | 1 |
| Light | 4 | 2 | 2 | 1 | 2 |
| Heavy | 8 | 3 | 4 | 1 | 1 |
| Ranged | 3 | 2 | 1 | 3 | 1 |
| Base | 10 | 10 | - | - | - |
| Barracks | 5 | 5 | - | - | - |

**Actions:**
- `move((x, y))` - Navigate to location
- `attack((ex, ey))` - Attack enemy
- `harvest((rx, ry), (bx, by))` - Gather resources
- `build((x, y), type)` - Construct building
- `train(unit_type)` - Produce unit
- `idle()` - Do nothing

---

## 9. BENCHMARK SETUP

**Script:** `benchmark.sh`
**Tests:** llama3.1:8b and qwen3:14b vs RandomBiasedAI
**Output:** `benchmark_results_YYYY-MM-DD_HH-MM-SS.txt`

**Output Format:**
```
model=llama3.1:8b opponent=RandomBiasedAI map=8x8/basesWorkers8x8 game=1 winner=LLM ticks=4321 crashed=no
```

---

## 10. EXTERNAL DEPENDENCIES

**Libraries (lib/):**
- `gson-2.10.1.jar` - JSON parsing for LLM responses
- `jdom.jar` - XML parsing for map files
- `weka.jar` - Machine learning (11 MB)
- `junit-4.12.jar` - Unit testing

**Pre-compiled Bots (lib/bots/):**
- Coac.jar, Droplet.jar, GRojoA3N.jar, Izanagi.jar, mayariBot.jar, MixedBot.jar, TiamatBot.jar

**External Services:**
- Ollama Server (http://localhost:11434)
- Google Gemini API (optional)

---

## 11. EXTENDING THE PROJECT

### Add a New LLM Provider
1. Create `src/ai/abstraction/[NewLLM].java` extending `AbstractionLayerAI`
2. Implement `getAction(int player, GameState gs)`
3. Handle API calls and JSON parsing
4. Reference in `config.properties` as `AI1=ai.abstraction.[NewLLM]`

### Add a New Strategy AI
1. Extend `AbstractionLayerAI`
2. Implement action generation in `getAction()`
3. Add to tournament selections

### Add a New Map
1. Create XML file in `maps/` hierarchy
2. Define unit/building starting positions
3. Reference in `config.properties`
