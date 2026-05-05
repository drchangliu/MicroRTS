<p align="center">
    <img src="microrts-text.png" width="500px"/>
</p>

# MicroRTS with LLM Game AI Agents

This repository serves **three purposes**:

1. **MicroRTS Game Engine** - A complete fork of the original [MicroRTS](https://github.com/santiontanon/microrts) real-time strategy game, designed for AI research
2. **LLM Game AI Competition Platform** - A template and framework for the [2026 IEEE WCCI MicroRTS LLM Game AI Competition](https://attend.ieee.org/wcci-2026/competitions/)
3. **LLM Benchmark Suite** - Tools to measure and compare how well different LLMs perform as game-playing agents

---

## Quick Navigation

| Purpose | Documentation |
|---------|---------------|
| Run the competition | [COMPETITION.md](COMPETITION.md) |
| Benchmark LLMs | [BENCHMARKING.md](BENCHMARKING.md) |
| Original MicroRTS | [MICRORTS_ORIGINAL.md](MICRORTS_ORIGINAL.md) |
| Technical prompt format | [LLM_PROMPTS.md](LLM_PROMPTS.md) |
| Codebase structure | [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) |

---

## 1. MicroRTS Game Engine

MicroRTS is a lightweight RTS game designed for AI research by [Santiago Ontanon](https://sites.google.com/site/santiagoontanonvillar/Home). It provides:

- Deterministic, real-time gameplay with simultaneous actions
- Configurable partial observability and non-determinism
- Multiple built-in AI implementations (minimax, MCTS, rule-based strategies)
- Tournament framework for AI competitions

**Why MicroRTS for LLM research?**
- Simpler than StarCraft/Warcraft, enabling faster experimentation
- Well-defined action space and game state representation
- Established research community and competition history

For full original documentation, see [MICRORTS_ORIGINAL.md](MICRORTS_ORIGINAL.md).

---

## 2. LLM Game AI Competition

This repository is the official platform for the **2026 IEEE WCCI MicroRTS LLM Game AI Competition**.

**Competition Goal:** Build an LLM-powered agent that can play MicroRTS competitively by:
- Understanding game state descriptions
- Generating valid action commands
- Developing winning strategies through prompts

### Quick Start for Competitors

```bash
# 1. Clone the repository
git clone https://github.com/drchangliu/MicroRTS
cd MicroRTS

# 2. Install Ollama and download a model
ollama run llama3.1:8b   # Leave running in terminal

# 3. Compile and run
chmod +x RunLoop.sh
./RunLoop.sh
```

**Key files for competition:**
- `src/ai/abstraction/ollama.java` - Main LLM agent (modify the PROMPT here)
- `resources/config.properties` - Game configuration
- `RunLoop.sh` - Run multiple games automatically

See [COMPETITION.md](COMPETITION.md) for complete competition rules and setup instructions.

---

## 3. LLM Benchmark Suite

Use this repository to measure how well different LLMs perform as game-playing agents.

### Supported LLM Backends

| Backend | Implementation | Status |
|---------|---------------|--------|
| Ollama (local) | `ollama.java` | Recommended |
| Google Gemini | `LLM_Gemini.java` | Working |
| Mistral | `mistral.java` | Working |
| Deepseek R1 | `LLM_DeepseekR1.java` | Working |

### Running Benchmarks

```bash
# Start Ollama (on GPU for reasonable speed)
srun --gres=gpu:1 --pty bash  # If on HPC cluster
ollama serve &

# Run benchmark suite
./benchmark.sh
```

### Benchmark Metrics

Each game records:
- **Winner** - Which AI won (LLM or opponent)
- **Game length** - Number of ticks to completion
- **Model name** - Which LLM was tested
- **Crashes/errors** - Any invalid actions or failures

See [BENCHMARKING.md](BENCHMARKING.md) for detailed benchmarking instructions.

---

## Repository Structure

```
MicroRTS/
├── src/                          # Java source code
│   ├── ai/                       # AI implementations
│   │   ├── abstraction/
│   │   │   ├── ollama.java       # Ollama LLM agent
│   │   │   ├── LLM_Gemini.java   # Gemini API agent
│   │   │   └── ...               # Other LLM backends
│   │   ├── RandomBiasedAI.java   # Baseline opponent
│   │   └── mcts/, minimax/       # Traditional game AI
│   ├── rts/                      # Game engine core
│   └── gui/                      # GUI components
├── maps/                         # Game maps (8x8 to 24x24)
├── resources/config.properties   # Game configuration
├── benchmark.sh                  # Benchmark runner
├── RunLoop.sh                    # Multi-game runner
└── lib/                          # Dependencies
```

---

## Building and Running

### Prerequisites

- JDK 17+ (`javac -version`, `java -version`)
- Ollama (for local LLM inference)
- GPU recommended for real-time gameplay

### Compile

```bash
# Using the build script
find src -name '*.java' > sources.list
javac -cp "lib/*:bin" -d bin @sources.list

# Or using Ant
ant build
```

### Run

```bash
# GUI mode
java -cp "lib/*:bin" gui.frontend.FrontEnd

# Headless mode with config
java -cp "lib/*:bin" rts.MicroRTS -f resources/config.properties

# Tournament mode
java -cp "lib/*:bin" gui.frontend.FrontEnd  # Use Tournaments tab
```

---

## Configuration

Edit `resources/config.properties`:

```properties
# Game settings
map_location=maps/8x8/basesWorkers8x8.xml
max_cycles=5000
headless=true

# AI players
AI1=ai.abstraction.ollama        # Your LLM agent
AI2=ai.RandomBiasedAI            # Opponent

# Available opponents:
# ai.RandomBiasedAI, ai.RandomAI, ai.PassiveAI
# ai.abstraction.HeavyRush, ai.abstraction.LightRush
```

---

## Citation

If you use MicroRTS in your research, please cite:

> Santiago Ontanon (2013) *The Combinatorial Multi-Armed Bandit Problem and its Application to Real-Time Strategy Games*, AIIDE 2013. pp. 58-64.

For LLM agent work using this repository, please also cite this repository.

---

## Related Resources

- [Original MicroRTS](https://github.com/santiontanon/microrts) - Upstream repository
- [MicroRTS-Py](https://github.com/Farama-Foundation/MicroRTS-Py) - Python/RL interface
- [microRTS Competition](https://sites.google.com/site/micrortsaicompetition/home) - Annual AI competition
- [2026 IEEE WCCI](https://attend.ieee.org/wcci-2026/competitions/) - LLM competition venue

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on submitting pull requests.

---

## License

MicroRTS is open source. See the original repository for license details.
