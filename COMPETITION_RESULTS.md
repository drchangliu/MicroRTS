# 2026 IEEE WCCI MicroRTS LLM Game AI Competition — Final Results

**Compiled:** 2026-05-31
**Tournament dataset:** `docs/data/tournament_results.json` (generated 2026-05-09)
**Submission portal:** https://drchangliu.github.io/MicroRTS/submit.html
**Competition page:** https://github.com/drchangliu/MicroRTS/blob/master/COMPETITION.md

---

## 1. Overview

The 2026 WCCI MicroRTS LLM Competition challenged participants to build agents
that play MicroRTS — a real-time strategy benchmark — by reasoning about game
state through LLM prompts, with no training or fine-tuning permitted. Thirteen
teams submitted bots via the GitHub PR portal; together these submissions
produced 15 distinct agent binaries (two teams contributed both LLM-only and
LLM+search variants).

All agents were evaluated on the organizers' tournament server in a
**single-elimination, multi-map format** against six anchor opponents drawn
from MicroRTS' canonical ladder.

### Format

| Item | Value |
|---|---|
| Maps | 8x8, 16x16, 32x32, 64x64 (basesWorkers / GardenOfWar) |
| Max cycles | 1500 / 3000 / 5000 / 8000 |
| Games per matchup | 1 per map |
| Anchor opponents (weight) | RandomBiasedAI (10), WorkerRush (15), LightRush (15), HeavyRush (20), Tiamat (20), CoacAI (20) |
| Scoring | Weighted points per win (1.0) / draw (0.5), averaged across maps; 0–100+ |
| Grading | A+ ≥ 90 · A ≥ 80 · B ≥ 70 · C ≥ 60 · D ≥ 40 · F < 40 |
| Default model on server | `llama3.1:8b` (Ollama) |

Single-elimination means a loss against an anchor stops that map; the higher
the anchor an agent reaches before elimination, the more weighted points it
banks. CoacAI and Tiamat are the strongest ladder anchors.

---

## 2. Final Leaderboard

| Rank | Team | Best Score | Grade | Model | Approach |
|---:|---|---:|:---:|---|---|
| 1 | **AlliBot** | **100.5** | **A+** | qwen3:14b | Mayari-derived hybrid + LLM advisor/search |
| 2 | Mayari-LLM | 87.0 | A | llama3.1:8b | MayariBot hardcoded with LLM consultation |
| 3 | HOPE | 79.0 | B | llama3.1:8b | Hybrid Ollama + MCTS with predictive lookahead |
| 4 | Chase | 69.0 | C | llama3.1:8b (gpt-5 dev) | Deterministic rush engine + advisor every ~500 ticks |
| 5 | yebot | 69.0 | C | qwen3:8b | Macro-LLM + hard-coded micro |
| 6 | Fortress Bot | 57.5 | D | llama3.1:8b | Ollama strategy planner + scripted opening + rush mirrors |
| 7 | PenguinBot | 54.0 | D | llama3.1:8b | NaiveMCTS + LLM stance controller (ATTACK/DEFEND) |
| 8 | xiebot | 54.0 | D | llama3.1:8b | LLM planner for mid/large maps + selective tactical MCTS |
| 9 | jmurr | 35.0 | F | none (heuristic) | Symmetric self/opponent state evaluation |
| 10 | AI4PC | 33.8 | F | llama3.1:8b | LLM picks rush every 100 ticks via counter-triangle |
| 11 | Parker's Bot | 33.0 | F | llama3.1:8b | LLM-guided adaptive rush + counter-triangle fallback |
| 12 | jmurrllm | 24.0 | F | llama3.1:8b | Light-rush opener + LLM opponent-classifier + counter table |
| 13 | EAGLE | 3.8 | F | llama3.1:8b | Structured prompt policy + role-based worker assignment |
| 13 | Adil Bot | 3.8 | F | none (heuristic) | Rule-based: harvest/build/train + early worker rush |

Notes:
- AlliBot was also submitted in a no-threading variant ("PenguinBot no-thread", 25.0/F); only the better result is shown above. The competition counts 13 teams.
- jmurr submitted both a no-LLM and an LLM variant; both are listed because they were registered as separate PRs.
- "Best Score" is the team's highest tournament score across all dated runs.

---

## 3. Per-Map Breakdown (Top 6)

| Team | 8x8 | 16x16 | 32x32 | 64x64 |
|---|:---:|:---:|:---:|:---:|
| AlliBot | 69 (C, eliminated by Tiamat) | **117 (A+)** | 96 (A+, eliminated by CoacAI) | **120 (A+)** |
| Mayari-LLM | 54 (D, eliminated by WorkerRush) | **120 (A+)** | — | — |
| HOPE | 79 (B) | — | — | — |
| Chase | 69 (C) | — | — | — |
| yebot | 69 (C) | — | — | — |
| Fortress Bot | 79 (B, eliminated by Tiamat) | 36 (F, eliminated by LightRush) | — | — |

Only AlliBot ran the full four-map gauntlet to completion. The bots' ability to
generalize from 8x8 to 64x64 separated A-grade entries from the rest:
small-map tactics rarely transferred without an LLM-aware strategic layer.

---

## 4. Submission Archetypes

The 13 teams converged on five recognizable design patterns:

1. **MCTS + LLM stance (search-led, LLM-advised)**
   PenguinBot, HOPE, xiebot. Tree search drives micro; LLM is consulted every
   few hundred ticks to bias exploration toward ATTACK/DEFEND or to set
   macro priors.
2. **LLM strategy controller over scripted rushes (script-led, LLM-routed)**
   AI4PC, Parker's Bot, Chase, Fortress Bot, jmurrllm. Each tick is executed
   by a canonical rush bot (Worker/Light/Heavy/Ranged); LLM is queried
   periodically to pick which rush, often with a "counter-triangle" fallback.
3. **Hybrid macro/micro with LLM macro layer (LLM-led, scripted micro)**
   AlliBot, Mayari-LLM, yebot. The LLM proposes goals; deterministic code
   handles harvesting, building, and combat micro. AlliBot's win is largely
   attributable to qwen3:14b producing higher-quality macro guidance.
4. **Pure-prompt LLM policy (LLM-only)**
   EAGLE. Every action is generated from a structured prompt with rule
   constraints. Low scores reflect the difficulty of low-level micro under
   per-tick LLM latency and validity constraints.
5. **No-LLM baselines (heuristic only)**
   jmurr (state evaluation), Adil Bot (scripted rule set). Included by
   participants to measure how much the LLM layer is worth.

---

## 5. What Worked, What Didn't

### What worked
- **Hybrid architectures dominated.** Every A/B-grade entry kept the LLM out
  of the per-tick loop and used it as a strategic advisor (every 100–500
  ticks) over a deterministic execution layer.
- **Stronger models translated to wins** when the rest of the stack was
  sound: AlliBot (qwen3:14b) is the only A+ submission, and yebot (qwen3:8b)
  edges out several llama3.1:8b peers despite a smaller code base.
- **Counter-triangle reasoning** (Light beats Ranged, Heavy beats Light,
  Ranged beats Heavy) recurs in five entries and reliably beats the rush
  anchors at 8x8.

### What didn't
- **Pure-LLM policies (every-tick prompting)** struggled with action validity
  and latency. EAGLE could not consistently beat RandomBiasedAI.
- **Small-map tuning didn't transfer.** Mayari-LLM scored A+ on 16x16 but
  D on 8x8 (eliminated by WorkerRush), illustrating how brittle map-specific
  heuristics are.
- **Stickiness without re-evaluation** hurt the per-100-tick strategy
  pickers (AI4PC) on larger maps where the opponent's composition changed
  faster than the LLM polling interval.

---

## 6. Headline Numbers

- **13 teams**, **15 agent binaries** evaluated
- **1 A+ submission** (AlliBot, 100.5)
- **5 entries graded C or higher** (38% of teams)
- **8 entries used `llama3.1:8b`**, **2 used qwen3 variants**, **2 used no LLM**, **1 dev-tuned on gpt-5**
- **AlliBot is the only entry that beat CoacAI on a map** (32x32, 2W-1L vs CoacAI)
- **Most-feared anchor:** Tiamat eliminated 3 top-half entries; only AlliBot beat it on any map

---

## 7. Acknowledgments

Thanks to all 13 teams for their submissions, to the MicroRTS authors
(Santi Ontañón, Levi Lelis, Rubens O. Moraes, et al.) for the open game
engine, and to the IEEE WCCI 2026 competition committee. Submissions,
source code, and per-game logs are archived under `submissions/` and
`tournament_results/` in the repository.
