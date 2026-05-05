# LLM Benchmarking Guide

This guide explains how to use MicroRTS as a benchmark suite for evaluating LLM game-playing capabilities.

---

## Purpose

MicroRTS provides a controlled environment to measure:
- **Strategic reasoning** - Can the LLM develop and execute winning strategies?
- **State understanding** - Can it correctly parse game state descriptions?
- **Action generation** - Can it produce valid, well-formed action commands?
- **Real-time performance** - Can it respond quickly enough for gameplay?

---

## Benchmark Results (2026-02-05)

### Leaderboard

18 entries tested across 7 models, 3 agent architectures, local and cloud inference.

| Rank | Model | Size | Agent Type | Score | Grade | Cleared | Eliminated at |
|------|-------|------|------------|-------|-------|---------|---------------|
| 1 | **qwen3:14b** | 14B local | Search+LLM | **119.0** | **A+** | 6/6 | cleared all |
| 2 | **deepseek-chat (V3)** | 671B cloud | Search+LLM | **96.0** | **A+** | 5/6 | CoacAI |
| 3 | gemma3 | 4B local | Hybrid | 69.0 | C | 4/6 | Tiamat |
| 3 | llama3.1:8b | 8B local | Search+LLM | 69.0 | C | 4/6 | Tiamat |
| 3 | qwen3:14b | 14B local | Hybrid | 69.0 | C | 4/6 | Tiamat |
| 6 | deepseek-chat (V3) | 671B cloud | Hybrid | 54.0 | D | 3/6 | WorkerRush |
| 6 | deepseek-r1:8b | 8B local | Hybrid | 54.0 | D | 3/6 | WorkerRush |
| 6 | deepseek-r1:8b | 8B local | Search+LLM | 54.0 | D | 3/6 | WorkerRush |
| 6 | gemma3 | 4B local | Search+LLM | 54.0 | D | 3/6 | WorkerRush |
| 6 | llama3.1:8b | 8B local | Hybrid | 54.0 | D | 3/6 | WorkerRush |
| 6 | llama3.2 | 3B local | Hybrid | 54.0 | D | 3/6 | WorkerRush |
| 12 | llama3.2 | 3B local | Search+LLM | 36.0 | F | 2/6 | LightRush |
| 13 | gemini-2.5-flash | cloud | PureLLM | 5.0 | F | 0/6 | RandomBiasedAI |
| 14 | llama3.1:8b | 8B local | PureLLM | 0.0 | F | 0/6 | RandomBiasedAI |
| 14 | qwen3:4b | 4B local | PureLLM | 0.0 | F | 0/6 | RandomBiasedAI |

Score can exceed 100 due to efficiency bonuses (+0.2 for wins under 50% max_cycles, +0.1 for under 75%).

### Per-Model Summary

| Model | Parameters | Hybrid | Search+LLM | Best | Notes |
|-------|-----------|--------|------------|------|-------|
| qwen3:14b | 14B | 69 (C) | **119 (A+)** | A+ | Only model to clear all 6 opponents |
| deepseek-chat (V3) | 671B MoE | 54 (D) | **96 (A+)** | A+ | Cloud API; beat Tiamat, fell to CoacAI |
| gemma3 | 4B | **69 (C)** | 54 (D) | C | Hybrid outperformed Search+LLM |
| llama3.1:8b | 8B | 54 (D) | **69 (C)** | C | Search+LLM beat WorkerRush |
| deepseek-r1:8b | 8B | 54 (D) | 54 (D) | D | Reasoning mode didn't help |
| llama3.2 | 3B | **54 (D)** | 36 (F) | D | Smallest model; Search+LLM failed at LightRush |
| qwen3:4b | 4B | - | - | F | Only tested PureLLM (0 pts) |

### Key Findings

1. **Agent architecture matters more than model size.** A 14B local model with MCTS search (qwen3:14b Search+LLM, 119 pts) outperformed a 671B cloud model (DeepSeek-V3 Search+LLM, 96 pts). Fast local inference gives the search algorithm more iterations per time budget.

2. **WorkerRush is the gatekeeper.** Every model under 14B parameters gets eliminated here (54 pts ceiling). Only qwen3:14b and DeepSeek-V3 with Search+LLM broke through.

3. **PureLLM is unusable without a fast GPU.** Calling an LLM every game tick is orders of magnitude too slow on CPU. All PureLLM agents scored 0-5 pts.

4. **Hybrid can outperform Search+LLM for smaller models.** gemma3 (4B) Hybrid scored 69 while its Search+LLM scored 54. When the model can't provide good policy priors, MCTS overhead hurts more than it helps.

5. **Reasoning models don't help.** deepseek-r1:8b's chain-of-thought reasoning just slowed inference with no strategic benefit (54 pts in both architectures, same as llama3.1:8b Hybrid).

6. **Parameter count isn't everything.** gemma3 at 4B matched qwen3:14b at 14B in Hybrid mode (both 69 pts, both eliminated at Tiamat).

7. **Cloud API benchmarking is cheap.** A full DeepSeek-V3 benchmark run (~30 API calls) cost approximately $0.05.

### Agent Architecture Comparison

```
Score by Architecture (best model in each tier):

Search+LLM:  ████████████████████████████████████████████████  119 (qwen3:14b)
Hybrid:      ██████████████████████████████                     69 (gemma3/qwen3:14b)
PureLLM:     ██                                                  5 (gemini-2.5-flash)
```

The three agent architectures represent fundamentally different approaches:

| Architecture | Strengths | Weaknesses | Best For |
|-------------|-----------|------------|----------|
| **Search+LLM** | Deep tactical play via MCTS; LLM provides strategic direction | Slow with weak models; needs fast inference | Large models (14B+) or fast cloud APIs |
| **Hybrid** | Fast execution; robust even with small models | No lookahead; relies on predefined strategies | Small models (4-8B); CPU-only setups |
| **PureLLM** | Most direct use of LLM intelligence | Far too slow on CPU; inference bottleneck | GPU-only; research/demonstration |

---

## Quick Start

```bash
# 1. Start Ollama
ollama serve &

# 2. Run the benchmark arena
python3 benchmark_arena.py

# 3. Generate consolidated leaderboard
python3 generate_leaderboard.py
```

---

## Benchmark Configuration

### Models to Test

Set environment variables before running:

```bash
export OLLAMA_MODEL="qwen3:14b"   # Model under test
```

Tested local models (via Ollama):
- `qwen3:14b` - Alibaba Qwen 3 14B (best local performer)
- `llama3.1:8b` - Meta Llama 3.1 8B
- `gemma3` - Google Gemma 3 4B
- `deepseek-r1:8b` - DeepSeek R1 8B (reasoning)
- `llama3.2` - Meta Llama 3.2 3B
- `qwen3:4b` - Alibaba Qwen 3 4B

Cloud models (via `openai_proxy.py`):
- `deepseek-chat` - DeepSeek V3 671B MoE

### Opponents

The benchmark arena (v2.0) uses **single-elimination** against 6 reference AIs. LLMs must **win** to advance to the next opponent. A draw, loss, or timeout eliminates.

| # | Opponent | Class | Tier | Weight | Description |
|---|----------|-------|------|--------|-------------|
| 1 | RandomBiasedAI | `ai.RandomBiasedAI` | Easy | 10 pts | Prefers useful actions |
| 2 | HeavyRush | `ai.abstraction.HeavyRush` | Medium-Hard | 20 pts | Heavy unit pressure |
| 3 | LightRush | `ai.abstraction.LightRush` | Medium | 15 pts | Aggressive light units |
| 4 | WorkerRush | `ai.abstraction.WorkerRush` | Medium | 15 pts | Aggressive workers |
| 5 | Tiamat | `ai.competition.tiamat.Tiamat` | Hard | 20 pts | Competition-winning bot |
| 6 | CoacAI | `ai.coac.CoacAI` | Hard | 20 pts | Competition-winning bot |

Total: **100 points**. If an LLM can't beat an easier opponent, there's no point playing harder ones.

**Note:** CoacAI and Tiamat require the bot JARs in `lib/bots/`. The arena uses classpath `lib/*:lib/bots/*:bin`.

### Game Settings

In `resources/config.properties`:

```properties
map_location=maps/8x8/basesWorkers8x8.xml  # Map
max_cycles=5000                             # Max game length
headless=true                               # No GUI
```

---

## Running Benchmarks

### Benchmark Arena (Recommended)

```bash
# Run full benchmark (all configured LLMs vs all 6 reference AIs)
python3 benchmark_arena.py

# Run with multiple games per matchup for more reliable results
python3 benchmark_arena.py --games 3
```

This produces:
- `benchmark_results/benchmark_YYYY-MM-DD_HH-MM.json` (detailed results)
- `benchmark_results/RESULTS.md` (formatted report)
- `benchmark_results/leaderboard.json` (appended entries)

### Benchmarking a Specific Local Model

```bash
# Set model and run only Hybrid + Search+LLM (skip PureLLM on CPU)
OLLAMA_MODEL=qwen3:14b python3 -c "
import os, sys
os.environ['OLLAMA_MODEL'] = 'qwen3:14b'
import benchmark_arena as ba
model = os.environ['OLLAMA_MODEL']
ba.LLMS = {
    'ai.abstraction.HybridLLMRush': {
        'name': 'hybrid', 'display': f'{model} (Hybrid)',
        'agent_type': 'Hybrid', 'env': {'OLLAMA_MODEL': model}
    },
    'ai.mcts.llmguided.LLMInformedMCTS': {
        'name': 'mcts', 'display': f'{model} (Search+LLM)',
        'agent_type': 'Search+LLM', 'env': {'OLLAMA_MODEL': model}
    },
}
ba.run_tournament(games_per_pair=1)
"
```

### Batch Benchmarking Multiple Local Models

```bash
python3 run_phase1_benchmarks.py
```

Edit `MODELS_TO_TEST` in the script to change which models are tested.

### Benchmarking Cloud Models

Use `openai_proxy.py` to translate the Ollama protocol to OpenAI-compatible cloud APIs:

```bash
# Terminal 1: Start the proxy
DEEPSEEK_API_KEY=sk-... python3 openai_proxy.py --provider deepseek --port 11435

# Terminal 2: Run the benchmark pointing at the proxy
OLLAMA_HOST=http://localhost:11435 OLLAMA_MODEL=deepseek-chat python3 -c "
import os, sys
os.environ['OLLAMA_HOST'] = 'http://localhost:11435'
os.environ['OLLAMA_MODEL'] = 'deepseek-chat'
import benchmark_arena as ba
model = os.environ['OLLAMA_MODEL']
ba.LLMS = {
    'ai.abstraction.HybridLLMRush': {
        'name': 'hybrid', 'display': f'{model} (Hybrid)',
        'agent_type': 'Hybrid',
        'env': {'OLLAMA_MODEL': model, 'OLLAMA_HOST': 'http://localhost:11435'}
    },
    'ai.mcts.llmguided.LLMInformedMCTS': {
        'name': 'mcts', 'display': f'{model} (Search+LLM)',
        'agent_type': 'Search+LLM',
        'env': {'OLLAMA_MODEL': model, 'OLLAMA_HOST': 'http://localhost:11435'}
    },
}
ba.run_tournament(games_per_pair=1)
"
```

Supported cloud providers:

| Provider | Env Variable | Models | API Cost (approx.) |
|----------|-------------|--------|-------------------|
| DeepSeek | `DEEPSEEK_API_KEY` | `deepseek-chat`, `deepseek-reasoner` | ~$0.05/run |
| OpenAI | `OPENAI_API_KEY` | `gpt-4o`, `gpt-4o-mini` | ~$0.50/run |
| OpenRouter | `OPENROUTER_API_KEY` | Any model | Varies |

### Generating the Consolidated Leaderboard

After running one or more benchmarks:

```bash
python3 generate_leaderboard.py
```

This reads all `benchmark_results/benchmark_*.json` files, finds the best score per model, and generates:
- `benchmark_results/leaderboard.json` (consolidated best-score-per-model)
- `benchmark_results/LEADERBOARD.md` (rich per-opponent breakdown table)

Both v1.0 (2 opponents) and v2.0 (6 opponents) result files are handled. Scores from different versions are not directly comparable.

---

## Scoring System

### Single-Elimination Format

LLMs face opponents in order of difficulty. They must **win** to advance. A draw, loss, or timeout eliminates them immediately.

### Per-Game Scoring

| Result | Base Score | Efficiency Bonus | Max Score |
|--------|-----------|-----------------|-----------|
| Win | 1.0 | +0.2 if < 50% max_cycles; +0.1 if < 75% | 1.2 |
| Draw | 0.5 | None | 0.5 |
| Loss/Timeout | 0.0 | None | 0.0 |

**Final Score** = Sum of (game_score x opponent_weight) across all opponents played.

### Grade Scale

| Grade | Score Range | Description |
|-------|-------------|-------------|
| A+ | 90-100+ | Excellent - beats hard AIs consistently |
| A | 80-89 | Very Good - competes with hard AIs |
| B | 70-79 | Good - beats medium, challenges hard |
| C | 60-69 | Average - beats easy and some medium |
| D | 40-59 | Below Average - draws common |
| F | 0-39 | Failing - losses/timeouts |

---

## LLM Agent Inventory

There are **8 LLM-based Game AI agents** in the system (plus 2 supporting classes), organized into 3 architectural tiers based on how frequently they call the LLM.

```
LLM Call Frequency:

PureLLM (5 agents):    ██████████████████████████████████████  Every 1-50 ticks
Hybrid (2 agents):     ████████                                Every 100-200 ticks
Search+LLM (1 agent):  ████                                    Every 300-500 ticks
```

### Tier 1: PureLLM Agents (5) -- LLM every tick

These call the LLM on every game tick to directly decide all unit actions. All send full game state (map, units, resources, turn) and expect JSON with `{"thinking": "...", "moves": [...]}`. Each move specifies unit position, type, and one of 6 actions: move, harvest, train, build, attack, idle.

| Agent | File | Backend | Frequency | Notes |
|-------|------|---------|-----------|-------|
| **ollama** | `src/ai/abstraction/ollama.java` | Ollama | Every tick | Primary agent; uses `OLLAMA_MODEL` env var |
| **ollama2** | `src/ai/abstraction/ollama2.java` | Ollama | Every tick | For Player 2 in LLM-vs-LLM; uses `OLLAMA_MODEL_P2` |
| **LLM_Gemini** | `src/ai/abstraction/LLM_Gemini.java` | Google Gemini API | Every tick | Hardcoded `gemini-2.5-flash`; logs token metrics to CSV |
| **mistral** | `src/ai/abstraction/mistral.java` | Ollama | Every tick | Aggressive prompt style ("attack ASAP, all in") |
| **LLM_DeepseekR1** | `src/ai/abstraction/LLM_DeepseekR1.java` | Ollama via sbatch | Every 50 ticks | HPC-oriented; runs LLM calls via job scheduler |

PureLLM agents require sub-second inference to be competitive, which is only feasible with a fast GPU or low-latency cloud API. On CPU, all PureLLM agents scored 0-5 pts in benchmarks.

### Tier 2: Hybrid Agents (2) -- LLM every 100-200 ticks

Rule-based strategy execution with periodic LLM consultation. The LLM picks the high-level strategy; proven rule-based AIs execute it.

| Agent | File | Frequency | Strategies |
|-------|------|-----------|------------|
| **HybridLLMRush** | `src/ai/abstraction/HybridLLMRush.java` | Every 200 ticks | 4: WORKER_RUSH, LIGHT_RUSH, HEAVY_RUSH, RANGED_RUSH |
| **StrategicLLMAgent** | `src/ai/abstraction/StrategicLLMAgent.java` | Every 200 ticks (100 in combat) | 8: above 4 + TURTLE, BOOM, COUNTER_ATTACK, HARASS |

**HybridLLMRush** asks the LLM "which rush strategy?" given unit/building counts for both players, then delegates execution to the corresponding hard-coded rush AI (WorkerRush, LightRush, HeavyRush, or RangedRush).

**StrategicLLMAgent** extends this with 4 additional strategies (defensive/economic) and also asks the LLM for tactical parameters:
- `aggression` (0.0-1.0): passive to all-in
- `economy_priority` (0.0-1.0): military focus to economy focus
- `retreat_threshold` (0.0-1.0): when to retreat based on relative strength
- `primary_target` (BASE, WORKERS, or ARMY): what to prioritize attacking

Best for small models (4-8B) where fast execution outweighs strategic depth. Scored up to 69 pts (C) in benchmarks.

### Tier 3: Search+LLM Agent (1) -- LLM every 300-500 ticks

| Agent | File | Frequency | Role |
|-------|------|-----------|------|
| **LLMInformedMCTS** | `src/ai/mcts/llmguided/LLMInformedMCTS.java` | ~2-3 calls per 500 ticks | AlphaGo-style MCTS with LLM policy priors and strategic goals |

Uses Monte Carlo Tree Search biased by LLM guidance at two levels:

1. **Policy priors** (via `LLMPolicyProbabilityDistribution`, cached ~300 ticks): The LLM provides action probability distributions for 8 unit situation types:
   - WORKER_NEAR_RESOURCE, WORKER_NO_RESOURCE, WORKER_CARRYING
   - MILITARY_COMBAT, MILITARY_NO_COMBAT
   - BASE_ECONOMY, BASE_LOW_RESOURCES, BARRACKS

2. **Strategic goals** (via `LLMStrategicEvaluation`, cached ~500 ticks): The LLM selects primary/secondary goals from: BUILD_ARMY, EXPAND_ECONOMY, ATTACK_BASE, ATTACK_WORKERS, DEFEND, CONTROL_RESOURCES. These bias the MCTS evaluation function.

Best for capable models (14B+) that can provide good strategic priors. Scored up to 119 pts (A+) with qwen3:14b -- the highest score in all benchmarks.

### Summary Table

| Agent | Tier | LLM Backend | Call Frequency | Strategies | Benchmark Best |
|-------|------|-------------|----------------|------------|---------------|
| ollama | PureLLM | Ollama | Every tick | Direct actions | 0 pts (F) |
| ollama2 | PureLLM | Ollama | Every tick | Direct actions | 0 pts (F) |
| LLM_Gemini | PureLLM | Gemini API | Every tick | Direct actions | 5 pts (F) |
| mistral | PureLLM | Ollama | Every tick | Direct actions (aggressive) | Not benchmarked |
| LLM_DeepseekR1 | PureLLM | Ollama/sbatch | Every 50 ticks | Direct actions | Not benchmarked |
| HybridLLMRush | Hybrid | Ollama | Every 200 ticks | 4 rush strategies | 69 pts (C) |
| StrategicLLMAgent | Hybrid | Ollama | Every 100-200 ticks | 8 strategies + tactics | Not benchmarked |
| LLMInformedMCTS | Search+LLM | Ollama | ~2-3 per 500 ticks | MCTS + policy priors + goals | 119 pts (A+) |

---

## LLM vs LLM Games

You can run two LLMs against each other:

```bash
# Set different models for each player
export OLLAMA_MODEL="llama3.1:8b"      # Player 0
export OLLAMA_MODEL_P2="qwen3:4b"      # Player 1

# Update config.properties:
# AI1=ai.abstraction.ollama
# AI2=ai.abstraction.ollama2
```

### Available LLM AI Classes

| Class | Environment Variables | Description |
|-------|----------------------|-------------|
| `ai.abstraction.ollama` | `OLLAMA_MODEL`, `OLLAMA_HOST` | Primary Ollama agent (PureLLM) |
| `ai.abstraction.ollama2` | `OLLAMA_MODEL_P2`, `OLLAMA_HOST` | Second Ollama agent (PureLLM) |
| `ai.abstraction.LLM_Gemini` | `GEMINI_API_KEY` | Google Gemini API (PureLLM) |
| `ai.abstraction.mistral` | `OLLAMA_MODEL`, `OLLAMA_HOST` | Mistral via Ollama (PureLLM) |
| `ai.abstraction.LLM_DeepseekR1` | `OLLAMA_HOST` | DeepSeek-R1 via sbatch (PureLLM) |
| `ai.abstraction.HybridLLMRush` | `OLLAMA_MODEL`, `OLLAMA_HOST` | Hybrid 4-strategy agent |
| `ai.abstraction.StrategicLLMAgent` | `OLLAMA_MODEL`, `OLLAMA_HOST` | Hybrid 8-strategy + tactics agent |
| `ai.mcts.llmguided.LLMInformedMCTS` | `OLLAMA_MODEL`, `OLLAMA_HOST` | MCTS with LLM priors (Search+LLM) |

---

## Hardware Requirements

### Minimum (CPU only)
- Any modern CPU
- 16GB RAM
- Works for Hybrid and Search+LLM agents
- PureLLM agents will timeout

### Recommended (GPU)
- NVIDIA GPU with 8GB+ VRAM
- 16GB+ system RAM
- Required for PureLLM agents
- Improves Search+LLM performance

### Model Memory Requirements

| Model | RAM/VRAM Required |
|-------|-------------------|
| llama3.2 (3B) | ~2 GB |
| qwen3:4b | ~2.5 GB |
| gemma3 (4B) | ~3.3 GB |
| llama3.1:8b | ~5 GB |
| deepseek-r1:8b | ~5.2 GB |
| qwen3:14b | ~9.3 GB |

---

## Adding New LLM Backends

To benchmark a new LLM provider:

1. Create `src/ai/abstraction/NewLLM.java` extending `AbstractionLayerAI`
2. Implement `getAction(int player, GameState gs)`
3. Handle API calls and JSON parsing
4. Reference in config: `AI1=ai.abstraction.NewLLM`

Or use `openai_proxy.py` to proxy any OpenAI-compatible API through the existing Ollama agent classes without writing Java code.

---

## Reproducibility

For reproducible benchmarks:

1. **Document configuration** - Record model, agent type, map, opponent, max_cycles
2. **Multiple runs** - Use `--games 3` or higher for more reliable results
3. **Hardware notes** - Document GPU model or note CPU-only
4. **Version tracking** - Results include arena version (v1.0/v2.0) for comparability

All benchmark results are stored in `benchmark_results/` as JSON with full configuration metadata.

---

## Files Reference

| File | Purpose |
|------|---------|
| `benchmark_arena.py` | Main benchmark runner (v2.0, single-elimination) |
| `generate_leaderboard.py` | Consolidates results across runs, best-score-per-model |
| `openai_proxy.py` | Ollama-to-cloud API proxy (DeepSeek, OpenAI, OpenRouter) |
| `run_phase1_benchmarks.py` | Batch runner for local models (Hybrid + Search+LLM) |
| `benchmark_results/` | JSON results, RESULTS.md, LEADERBOARD.md, leaderboard.json |
