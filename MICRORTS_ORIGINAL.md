# Original MicroRTS Documentation

This document preserves the original MicroRTS documentation from the upstream repository.

---

## About MicroRTS

[![Build Status](https://travis-ci.org/douglasrizzo/microrts.svg?branch=master)](https://travis-ci.org/douglasrizzo/microrts)

microRTS is a small implementation of an RTS game, designed to perform AI research. The advantage of using microRTS with respect to using a full-fledged game like Wargus or StarCraft (using BWAPI) is that microRTS is much simpler, and can be used to quickly test theoretical ideas, before moving on to full-fledged RTS games.

By default, microRTS is deterministic and real-time (i.e. players can issue actions simultaneously, and actions are durative). However, it is possible to experiment both with fully-observable and partially-observable games, as well as with deterministic and non-deterministic settings via configuration flags. As part of the implementation, I include a collection of hard-coded, and game-tree search techniques (such as variants of minimax, Monte Carlo search, and Monte Carlo Tree Search).

microRTS was developed by [Santiago Ontanon](https://sites.google.com/site/santiagoontanonvillar/Home).

MicroRTS-Py will eventually be updated, maintained, and made compliant with the standards of the Farama Foundation (https://farama.org/project_standards). However, this is currently a lower priority than other projects we're working to maintain. If you'd like to contribute to development, you can join our discord server here- https://discord.gg/jfERDCSw.

For a video of how microRTS looks like when a human plays, see a [YouTube video](https://www.youtube.com/watch?v=ZsKKAoiD7B0)

If you are interested in testing your algorithms against other people's, **there is an annual microRTS competition**. For more information on the competition see the [competition website](https://sites.google.com/site/micrortsaicompetition/home). The previous competitions have been organized at IEEE-CIG2017 and IEEE-CIG2018, and this year it's organized at IEEE-COG2019 (notice the change of name of the conference).

---

## Citation

To cite microRTS, please cite this paper:

> Santiago Ontanon (2013) The Combinatorial Multi-Armed Bandit Problem and its Application to Real-Time Strategy Games, In AIIDE 2013. pp. 58 - 64.

---

## Setting up microRTS in an IDE

Watch [this YouTube video](https://www.youtube.com/watch?v=_jVOMNqw3Qs) to learn how to acquire microRTS and setup a project using Netbeans.

---

## Reinforcement Learning in microRTS

If you'd like to use reinforcement learning in microRTS please check this project: https://github.com/Farama-Foundation/MicroRTS-Py

---

## Executing microRTS through the terminal

If you want to build and run microRTS from source using the command line, clone or download this repository and run the following commands in the root folder of the project to compile the source code:

Linux or Mac OS:

```shell
javac -cp "lib/*:src" -d bin src/rts/MicroRTS.java # to build
```

Windows:

```shell
javac -cp "lib/*;src" -d bin src/rts/MicroRTS.java # to build
```

### Generating a JAR file

You can join all compiled source files and dependencies into a single JAR file, which can be executed on its own. In order to create a JAR file for microRTS:

```shell
javac -cp "lib/*:src" -d bin $(find . -name "*.java") # compile source files
cd bin
find ../lib -name "*.jar" | xargs -n 1 jar xvf # extract the contents of the JAR dependencies
jar cvf microrts.jar $(find . -name '*.class' -type f) # create a single JAR file with sources and dependencies
```

### Executing microRTS

To execute microRTS from compiled class files:

```shell
java -cp "lib/*:bin" rts.MicroRTS # on Linux/Mac
java -cp "lib/*;bin" rts.MicroRTS # on Windows
```

To execute microRTS from the JAR file:

```shell
java -cp microrts.jar rts.MicroRTS
```

#### Which class to execute

microRTS has multiple entry points, and for experimentation purposes you might eventually want to create your own class if none of the base ones suit your needs (see the "tests" folder for examples), but a default one is the `gui.frontend.FrontEnd` class, which opens the default GUI. To execute microRTS in this way, use the following command:

```shell
java -cp microrts.jar gui.frontend.FrontEnd
```

Another, more expansive entry point is the `rts.MicroRTS` class. It is capable of starting microRTS in multiple modes, such as in client mode (attempts to connect to a server which will provide commands to a bot), server mode (tries to connect to a client in order to control a bot), run a standalone game and exit or open the default GUI.

The `rts.MicroRTS` class accepts multiple initialization parameters, either from the command line or from a properties file. A list of all the acceptable command-line arguments can be accessed through the following command:

```shell
java -cp microrts.jar rts.MicroRTS -h
```

An example of a properties file is provided in the `resources` directory. microRTS can be started using a properties file with the following command:

```shell
java -cp microrts.jar rts.MicroRTS -f my_file.properties
```

---

## Instructions

![instructions image](https://raw.githubusercontent.com/santiontanon/microrts/master/help.png)

---

## Running Multiple Games (Tournament Mode)

<img width="935" height="798" alt="Tournament Screenshot" src="https://github.com/user-attachments/assets/4ef13d14-9d92-4f6d-8260-39182e40432c" />

0. Switch to the "Tournaments" tab on the top of the window.

1. **Opponent Mode**
   - Set the Opponent dropdown **Round Robin** to **Fixed Opponents**.

2. **Select AIs**
   - From **Available AIs**:
     - Add your AI to **Selected AIs**.
     - Add the baselines/opponents to **Opponent AIs**.

3. **Maps**
   - Go to **Maps** -> click **+** -> browse to the project's `maps` folder.
   - Add a map (e.g. `8x8` or `24x24`).

4. **Iterations**
   - Set **Iterations** = number of games per matchup
     (e.g. `2` -> 2 games, `3` -> 3 games).

5. **Match Config**
   - **Max Game Length** = `3000`
   - **Time Budget** = `1000`
   - Leave the other timing fields as default.

6. **Unit Type Table / Rules**
   - Set **Unit Type Table** = `Original - Both`
   - do not Check:
     - `Self-play matches`
     - `Game over if AI times out`
     - (Keep the other default checkboxes enabled.)

7. **Run Tournament**

<img width="814" height="480" alt="Tournament Results" src="https://github.com/user-attachments/assets/ac1a69f1-eff9-4a01-a9da-7374895badde" />

Tournament results appear in the "tournament_nn" folder created in the project folder.

---

## Running Automatically with RunLoop.sh

Run the game multiple times automatically with `RunLoop.sh`.
The script recompiles, launches a match, waits N seconds, kills it, and repeats.

**Prerequisites:**
- JDK 17+ on your PATH (`javac -version`, `java -version`).
- Project layout includes `src/`, `lib/` (jars), and `bin/` (compiled out).
- Run from the project root.

```bash
# First time only - make it executable
chmod +x RunLoop.sh

# Run with defaults (e.g., 5 runs x 10s each)
./RunLoop.sh
```

**Customization (lines 4 and 5 in RunLoop.sh):**

```bash
TOTAL_RUNS=10                         # Number of matches to run
RUN_TIME_PER_GAME_SEC="${RUN_TIME_PER_GAME_SEC:-400}"  # Seconds per match
```

**Stopping early:** Press Ctrl+C in the terminal; the script cleans up and exits.

---

## Configuration File

Edit `resources/config.properties` to configure AI players:

```properties
AI1=ai.abstraction.ollama    # Player 0
AI2=ai.RandomBiasedAI        # Player 1
```

---

## Determining the Winner

After the game ends, open the `ResponseTimestamp(YYYY_MM_DD*).csv` file and check the "Score_in_every_run" column:
- If AI1 is Player zero (P0) and AI2 is Player one (P1)
- If P0 > P1, then Player zero is the winner
- Otherwise, Player one is the winner

---

## Related Links

- [Original MicroRTS Repository](https://github.com/santiontanon/microrts)
- [MicroRTS-Py (Python/RL Interface)](https://github.com/Farama-Foundation/MicroRTS-Py)
- [microRTS AI Competition](https://sites.google.com/site/micrortsaicompetition/home)
