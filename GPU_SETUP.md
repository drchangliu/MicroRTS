# GPU Setup for Ollama on node001 (HPC Cluster)

**Last Updated:** 2026-02-01

---

## Hardware

- **Hostname:** node001
- **GPUs:** 8x NVIDIA RTX A6000 (48GB VRAM each)
- **OS:** Rocky Linux 9.5 (kernel 5.14.0-503.15.1.el9_5.x86_64)
- **NVIDIA Driver:** 550.144.03
- **Cluster:** Warewulf/SLURM managed

---

## Key Issue: SLURM GPU Allocation

This cluster uses **SLURM** for job scheduling. GPUs are controlled via cgroups.

**If you see this error:**
```
nvidia-smi: No devices were found
```

**Or Ollama shows:**
```
inference compute: id=cpu library=cpu
entering low vram mode: total vram=0 B
```

**The cause is:** Your SLURM job was not allocated GPU resources.

---

## Solution: Request GPUs via SLURM

### Option 1: Interactive Session with GPU
```bash
srun --gres=gpu:1 --pty bash
# Then run your commands with GPU access
ollama serve &
# ... run benchmark
```

### Option 2: Batch Script with GPU
```bash
#!/bin/bash
#SBATCH --job-name=llm-benchmark
#SBATCH --gres=gpu:1
#SBATCH --time=02:00:00
#SBATCH --output=benchmark_%j.log

# Start Ollama
ollama serve &
sleep 10

# Run benchmark
cd /home/liuc/gitwork/MicroRTS
./benchmark.sh
```

Submit with: `sbatch benchmark_gpu.sh`

### Option 3: Request Multiple GPUs (for parallel inference)
```bash
srun --gres=gpu:8 --pty bash
# or
#SBATCH --gres=gpu:8
```

---

## Verify GPU Access

After getting a GPU-enabled session:

```bash
# Should show GPU info, not "No devices found"
nvidia-smi

# Check Ollama sees GPU
ollama serve &
sleep 5
cat /tmp/ollama.log | grep -i gpu
# Should show: "inference compute id=GPU-xxx"

# Check model uses GPU
ollama ps
# Should show "100% GPU" not "100% CPU"
```

---

## Ollama Configuration

**Ollama binary location:** `/home/liuc/.local/bin/ollama`
**Ollama models directory:** `/home/liuc/.ollama/models`
**Default host:** `http://localhost:11434`

**Environment variables:**
```bash
export OLLAMA_HOST="http://localhost:11434"
export OLLAMA_MODEL="llama3.1:8b"   # or qwen3:14b
export CUDA_VISIBLE_DEVICES=0       # limit to specific GPU if needed
```

---

## Available Models

```
llama3.1:8b   (8.0B params)  - ~5GB VRAM
qwen3:14b    (14.8B params) - ~9GB VRAM
llama3.3:70b (70.6B params) - ~42GB VRAM
```

---

## Troubleshooting

### nvidia-smi shows "No devices found"
- Not in a SLURM job with GPU allocation
- Fix: `srun --gres=gpu:1 --pty bash`

### Ollama shows 100% CPU
- Same as above - no GPU allocation
- Restart Ollama after getting GPU access: `killall ollama && ollama serve &`

### Permission denied on /dev/nvidia*
- SLURM cgroup is blocking access
- The job_id in `/proc/self/cgroup` shows which job you're in
- Need to request new job with `--gres=gpu:N`

### User ID warning: "cannot find name for user ID 1014"
- Cosmetic issue - user not in /etc/passwd on compute node
- Does not affect functionality
