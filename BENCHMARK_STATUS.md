# Benchmark Status

**Last Updated:** 2026-02-01

This file tracks the current status of LLM benchmark experiments.

---

## Latest Benchmark Run

**Date:** 2026-02-01
**Status:** CPU run - all games timed out. GPU required.

| Model | Game | Status |
|-------|------|--------|
| llama3.1:8b | 1 | timeout |
| llama3.1:8b | 2 | timeout |
| qwen3:14b | 1 | timeout |
| qwen3:14b | 2 | timeout |

**Cause:** CPU-only inference too slow. LLM calls blocked at first turn.

---

## Configuration Used

```
Map: maps/8x8/basesWorkers8x8.xml
Opponent: RandomBiasedAI
Max Cycles: 5000
Timeout: 300 seconds per game
Hardware: CPU only (no GPU)
```

---

## Next Steps

1. Request GPU session: `srun --gres=gpu:1 --pty bash`
2. Verify GPU access: `nvidia-smi`
3. Start Ollama: `ollama serve &`
4. Verify GPU in Ollama: `ollama ps` (should show "100% GPU")
5. Run benchmark: `./benchmark.sh`

See [GPU_SETUP.md](GPU_SETUP.md) for detailed instructions.

---

## Related Documentation

- [BENCHMARKING.md](BENCHMARKING.md) - How to run benchmarks
- [GPU_SETUP.md](GPU_SETUP.md) - HPC/GPU setup
- [CLAUDE.md](CLAUDE.md) - AI assistant instructions
