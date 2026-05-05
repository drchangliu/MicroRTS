# Game Fairness and Synchronized Tick Analysis

This document explains how MicroRTS handles AI timing and ensures fair play between fast AIs (like RandomBiasedAI) and slow AIs (like LLM agents).

## How the Game Loop Works

The MicroRTS game loop in `src/rts/Game.java` is **synchronous and blocking**:

```java
while (!gameover && gs.getTime() < maxCycles) {
    // Game WAITS for AI1 to respond (even if it takes 5 seconds)
    PlayerAction pa1 = ai1.getAction(0, playerOneGameState);

    // Game WAITS for AI2 to respond
    PlayerAction pa2 = ai2.getAction(1, playerTwoGameState);

    // Both actions are executed
    gs.issueSafe(pa1);
    gs.issueSafe(pa2);

    // Game advances exactly 1 tick
    gameover = gs.cycle();
}
```

## Key Insight: The Game is Naturally Fair

**Both AIs get exactly 1 decision per game tick.** The game waits for each AI to respond before advancing.

### Example Timeline

```
Tick 0:
  → Game asks AI1 (LLM) for action      [waits 5 seconds for API response]
  → Game asks AI2 (RandomBiasedAI)      [returns in microseconds]
  → Both actions executed simultaneously
  → Game advances 1 tick

Tick 1:
  → Game asks AI1 (LLM) for action      [waits 5 seconds]
  → Game asks AI2 (RandomBiasedAI)      [returns instantly]
  → Both actions executed simultaneously
  → Game advances 1 tick
```

If an LLM takes 5 seconds to respond, the game simply runs slower (5 seconds per tick instead of milliseconds). **Neither player gets more turns than the other.**

## The LLM_INTERVAL Trap

### The Problem

Early LLM implementations used `LLM_INTERVAL` to reduce API calls:

```java
// In LLM_Gemini.java (PROBLEMATIC when > 1)
static final Integer LLM_INTERVAL = 100;

public PlayerAction getAction(int player, GameState gs) {
    if (gs.getTime() % LLM_INTERVAL != 0) {
        // Skip API call, return cached/continuation action
        return translateActions(player, gs);
    }
    // Only call API every 100 ticks
    String response = prompt(finalPrompt);
    ...
}
```

This created **unfairness**:
- LLM: Makes fresh decisions every 100 ticks
- RandomBiasedAI: Makes fresh decisions every tick
- Result: Opponent gets 100x more decision opportunities

### The Solution

Set `LLM_INTERVAL = 1` so the LLM makes fresh decisions every tick:

```java
// In LLM_Gemini.java and ollama.java
static final Integer LLM_INTERVAL = 1;
```

Now:
- LLM: Makes fresh decisions every tick (calls API each time)
- RandomBiasedAI: Makes fresh decisions every tick
- Result: Fair - both get equal decision opportunities

## Trade-offs

| Setting | Fairness | Speed | API Usage |
|---------|----------|-------|-----------|
| `LLM_INTERVAL=1` | Fair | Slow (waits for API) | High |
| `LLM_INTERVAL=100` | Unfair | Fast | Low |

## Recommendations

### For Fair Benchmarking
- Use `LLM_INTERVAL=1` (default now)
- Accept slower game speed as the cost of fairness
- Use local Ollama to avoid API rate limits

### For Quick Testing
- Use `LLM_INTERVAL > 1` only for debugging/testing
- Understand results are not representative of fair play

### For API Rate Limits
If using cloud APIs (Gemini, OpenAI) with rate limits:
- Use local Ollama instead (no rate limits)
- Or use paid API tiers with higher limits
- Do NOT increase `LLM_INTERVAL` as a workaround - it creates unfair games

## Configuration

### LLM Agent Settings
Located in the respective Java files:
- `src/ai/abstraction/LLM_Gemini.java`: `LLM_INTERVAL = 1`
- `src/ai/abstraction/ollama.java`: `LLM_INTERVAL = 1`

### Game Settings
In `resources/config.properties`:
```properties
# These do NOT affect fairness, only GUI refresh rate
update_interval=50

# AI classes
AI1=ai.abstraction.LLM_Gemini
AI2=ai.RandomBiasedAI
```

## Summary

1. **The game loop is synchronous** - it waits for both AIs before advancing
2. **Both AIs get exactly 1 decision per tick** - inherently fair
3. **LLM_INTERVAL must be 1 for fair play** - anything higher gives opponent more decisions
4. **Slow response = slow game, not unfair game** - the LLM taking 5 seconds just means each tick takes 5 seconds
