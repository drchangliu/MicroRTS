package ai.abstraction.submissions.eagle;

import ai.abstraction.*;

import ai.abstraction.pathfinding.AStarPathFinding;
import ai.core.AI;
import ai.abstraction.pathfinding.PathFinding;
import ai.core.ParameterSpecification;

import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import java.io.*;
import java.net.*;
import com.google.gson.*;
import rts.GameState;
import rts.PhysicalGameState;
import rts.UnitAction;
import rts.Player;
import rts.PlayerAction;
import rts.units.*;


/**
 *
 * @author Mukesh
 */
public class EAGLE extends AbstractionLayerAI {

    /**
     * Static & non-static variables
     * connected to 2 classes  unitTypeTable & unitType
     */
    static final JsonObject MOVE_RESPONSE_SCHEMA;
    // How often the LLM should act on the game state
    // NOTE: Fairness is now handled at the game level via ai_decision_interval in config.properties
    // This should be set to 1 so the LLM responds whenever the game asks
    static final Integer LLM_INTERVAL = 50;
    Random r = new Random();
    protected UnitTypeTable utt; // different class
    UnitType resourceType;
    UnitType workerType;
    UnitType lightType;
    UnitType heavyType;
    UnitType rangedType;
    UnitType baseType;
    UnitType barracksType;
    Instant promptTime;
    Instant responseTime;
    long Latency =0;
    String num_shot ="One-Shot";
    String aiName1= "";
    String aiName2="";
    private boolean logsInitializedone = false;

    // ==== OLLAMA CONFIG ====
  //  static final String OLLAMA_HOST =
          //  System.getenv().getOrDefault("OLLAMA_HOST", "http://localhost:11434");
   // static String MODEL = "llama3.1:8b"; // gpt-oss  "llama3.1:8b" gpt-oss:20b
   // static final boolean OLLAMA_STREAM = false;
    static final String OLLAMA_FORMAT = "json";

    // Keep your file names/logs using MODEL:
    // String fileName = "Response_format.csv";

    /// --

    // ==== OLLAMA CONFIG ====
// Env takes precedence: export OLLAMA_MODEL=llama3.1:8b (or gpt-oss:20b, mistral:latest, ...)
    static final String OLLAMA_HOST =
            System.getenv().getOrDefault("OLLAMA_HOST", "http://localhost:11434");

    static String MODEL =
            System.getenv().getOrDefault("OLLAMA_MODEL", "llama3.1:8b"); // smollm2:135m is bad real bad
    // deepseek-r1:14b
     //

    // Models that usually honor "format":"json" (add more as you verify)
    static final Set<String> JSON_FRIENDLY = Set.of(
            "llama3.1:8b", "mistral:latest", "mistral:7b","llama3-gradient:8b","deepseek-r1:14b",
            "qwen2.5:7b", "qwen2.5:14b", "qwen3:latest", "qwen3-coder:30b", "deepseek-r1:8b"
    );

    // Optional switch to use chat endpoint (recommended)
    static final boolean USE_CHAT = true;

    // Keep stream false until you implement incremental parsing
    static final boolean OLLAMA_STREAM = false;


    /// ---




    // is there any other way to give prompt in a better way to give Free to it ?

    /**
     * prompt that needs to change based on they model
     *
     * V1: Game Rules:
     Two players, Player 1 (Ally) and Player 2 (Enemy) are competing to eliminate all opposing enemy units in a Real Time Strategy (RTS) game.
     Each step, each player can assign actions to their units if they are not already doing an action. Each unit can only be assigned ONE action.
     Players can only assign actions to their ally units.
     There are 6 available actions:
     - move((Target_x, Target_y)): Unit will move to target location.
     - train(Unit_Type): Unit will train the provided unit type (only bases and barracks can use this action).
     - build((Target_x, Target_y), Building_Type): Unit will build the provided building type at the target location, consuming the resource cost from the ally base (only workers can use this action).
     - harvest((Resource_x, Resource_y), (Ally_Base_x, Ally_Base_y)): Unit will navigate to the target resource, collect a resource and bring it back to the target ally base.
     - attack((Enemy_x, Enemy_y)): Unit will navigate to, and attack the target enemy.
     - idle(): The target unit will do nothing for a round. This is the default for all available units that are not assigned an action.
     The game is over once all units and buildings from either team are killed or destroyed, the remaining team is the winner. BUILD A BARRACKS!
     *
     Unit types:
     | Unit Type | HP | Cost | Attack Damage | Attack Range | Speed | Abilities                                                       |
     |-----------|----|------|---------------|--------------|-------|-----------------------------------------------------------------|
     | worker    | 1  | 1    | 1             | 1            | 1     | Trained from base, Gathers resources, builds base and barracks  |
     | light     | 4  | 2    | 2             | 1            | 2     | Trained from barracks, High Speed                               |
     | heavy     | 8  | 3    | 4             | 1            | 1     | Trained from barracks, High HP, High Damage                     |
     | ranged    | 3  | 2    | 1             | 3            | 1     | Trained from barracks, High Range                               |
     *
     Building types:
     | Building Type | HP  | Cost | Abilities                               |
     |---------------|-----|------|-----------------------------------------|
     | base          | 10  | 10   | Produces workers, Stores resources      |
     | barracks      | 5   | 5    | Produces Light, Heavy, and Ranged units |
     *
     Suggested strategy:
     1. Early Game - Economy Focus
     - Harvest nonstop with workers.
     - Build barracks once you have 5 resources.
     2. Mid Game - Army Development
     - Train heavies, ranged, and lights using the barracks.
     - Hunt enemy workers to slow their economy.
     - Keep barracks safe at all costs.
     3. Late Game - Closing Out
     - Group units and attack key targets together.
     - Destroy enemy production buildings first.
     - Maintain resource control to prevent comebacks.
     *
     Game state format:
     The game state consists the map size and a list of feature locations (zero-indexed) within the the map bounds. Units and buildings have different properties associated with their current state. All units and buildings (except resources) have an 'available' property. If a unit or building is available an action issued to it will be accepted.
     *
     Move format:
     Return a list of actions to take for each available unit or building in the following format:
     (<X>, <Y>): <Unit Type> <Action>(<Action Arguments>)
     (<X>, <Y>): <Unit Type> <Action>(<Action Arguments>)
     etc ..."""
     *
     */
    // Improved prompt with clear JSON format to reduce parsing errors
    // ===== PROMPT LOADING =====
    // Override with -Dmicrorts.prompt=path or MICRORTS_PROMPT=path.
    static final String PROMPT_PATH =
            System.getProperty("microrts.prompt",
                    System.getenv().getOrDefault("MICRORTS_PROMPT", "prompt.txt"));

    protected static final String DEFAULT_PROMPT = """
CRITICAL RULES:
1. You can ONLY command units marked as Ally.
2. NEVER command Enemy or Neutral units.
3. Each move must use an available Ally unit from Feature locations.
4. unit_position must exactly match the acting Ally unit position.
5. unit_type must match the acting Ally unit type.
6. action_type must match the action in raw_move.
7. Output valid JSON only.
8. Do not explain outside JSON.
9. Each Ally unit may receive at most one action.
10. Count only current game state lines, not examples.
11. Do not trust current_action="idling" as proof that the unit is free.
12. Use Feature locations order to assign stable roles.
13. If Ally Worker Unit count is 0 or 1, Ally Base must train(worker) this turn if possible.
14. If Ally Worker Unit count is 2 or more, do NOT choose train_worker because of worker count.
15. If no line exactly contains "Ally Barracks Unit", the SECOND Ally Worker Unit in Feature locations must attempt barracks construction every turn.
16. "Enemy Barracks Unit" does NOT count as our barracks.
17. Never train from Enemy Barracks.

BUILDINGS:
base:
- Cost: 10
- HP: 10
- Can train workers
- Stores resources
barracks:
- Cost: 5
- HP: 5
- Can train light, heavy, ranged units
UNITS:
worker:
- Cost: 1
- HP: 1
- Can harvest resources
- Can build base and barracks
- Can attack, but should usually focus on economy/building
light:
- Cost: 2
- HP: 4
- Fast melee attacker
heavy:
- Cost: 3
- HP: 8
- Strong melee attacker
ranged:
- Cost: 2
- HP: 3
- Long-range attacker

ACTIONS:
move((x,y))
train(unit_type)
build((x,y), building_type)
harvest((resource_x,resource_y),(base_x,base_y))
attack((enemy_x,enemy_y))
idle()

RAW MOVE FORMAT:
(x,y): unit_type action(arguments)
Examples:
(1,1): worker harvest((0,0),(2,1))
(2,1): base train(worker)
(1,1): worker build((3,2), barracks)
(3,2): barracks train(light)
(4,4): light attack((5,6))

EXAMPLE SAFETY RULE:
Examples are training examples only.
Do NOT treat units, buildings, resources, or coordinates in examples as current game state.
Only the final INPUT block after "CURRENT GAME STATE:" is the real current state.
When checking worker_count or Ally Barracks Unit, check ONLY the current game state, never examples.

STATE INTERPRETATION STEP:
Before making any decision, extract these values ONLY from CURRENT GAME STATE:
- worker_count = number of current-state lines containing exactly "Ally Worker Unit"
- worker_status = "less_than_2" only if worker_count is 0 or 1
- worker_status = "enough_workers" if worker_count is 2, 3, 4, 5, or more
- has_ally_barracks = True if and only if a current-state line contains exactly "Ally Barracks Unit"
- has_enemy_barracks = True if and only if a current-state line contains exactly "Enemy Barracks Unit"
- ally_base_position = position of the line containing exactly "Ally Base Unit"
- worker_1 = first Ally Worker Unit in Feature locations order
- worker_2 = second Ally Worker Unit in Feature locations order
- worker_3 = third Ally Worker Unit in Feature locations order
- worker_4 = fourth Ally Worker Unit in Feature locations order
Important:
Do not use mathematical comparison symbols in reasoning.
Do not say worker_count<2.
Use worker_status instead.
Feature locations order is the stable role assignment order.
Do not choose builder by current_action.
Do not choose builder by distance.
Do not change builder because another worker appears idle.
If worker_2 exists and has_ally_barracks == False, worker_2 is always the barracks builder.

DECISION LOGIC:
1. Count Ally Worker Unit lines.
2. If worker_count is 0 or 1:
   - worker_status=less_than_2
   - decision=train_worker
3. If worker_count is 2, 3, 4, 5, or more:
   - worker_status=enough_workers
   - decision must NOT be train_worker because of worker count
   - If has_ally_barracks=False, decision=build_barracks_with_worker_2
   - If has_ally_barracks=True, decision=train_army_or_attack

CONSISTENCY RULE:
If worker_count is 2, 3, 4, 5, or more, your decision must NOT be train_worker because of worker count.
If worker_count=5 and decision=train_worker, the answer is incorrect.
If worker_status=enough_workers and decision=train_worker because of worker count, the answer is incorrect.
If has_ally_barracks=False and worker_status=enough_workers, decision must be build_barracks_with_worker_2.
If has_ally_barracks=True, do not build another barracks.
Your thinking must include:
worker_count=<number>; worker_status=<less_than_2/enough_workers>; has_ally_barracks=<True/False>; builder=<worker_2_position or none>; decision=<train_worker/build_barracks_with_worker_2/train_army_or_attack>; reason=...

START GAME RULE:
At the beginning of the game, prioritize creating enough workers.
Condition:
- Count the number of Ally Worker Unit lines in CURRENT GAME STATE.
Rule:
- If worker_count is 0 or 1:
  - worker_status=less_than_2.
  - Ally Base must train(worker) this turn if possible.
  - This rule has higher priority than barracks construction.
  - Existing workers should harvest.
  - Do not attempt barracks construction until worker_status=enough_workers.
- If worker_count is 2, 3, 4, 5, or more:
  - worker_status=enough_workers.
  - Do not choose train_worker because of worker count.
  - Proceed to barracks or army decision.
Priority order:
1. If worker_status=less_than_2 -> base train(worker)
2. Else if no Ally Barracks Unit -> worker_2 build((target_x,target_y), barracks)
3. Else -> normal strategy

STABLE WORKER ROLE RULE:
Use Feature locations order to assign worker roles.
When worker_status=enough_workers:
- worker_1: harvest from nearest Neutral Resource Node to Ally Base.
- worker_2: build barracks until Ally Barracks Unit exists.
- worker_3 and later: harvest unless needed for defense.
Do not swap worker_1 and worker_2.
Do not choose a different barracks builder if worker_2 is listed.
Do not use current_action="idling" to reassign worker roles.
Even if the game says another worker is idling, worker_2 remains the barracks builder.

MEMORYLESS BARRACKS RULE FOR ANY MAP:
At every turn, use only the current Feature locations.
If no current-state line exactly contains "Ally Barracks Unit", barracks construction is unfinished.
When no Ally Barracks Unit exists and worker_status=enough_workers:
1. Always assign worker_2 to attempt barracks construction this turn.
2. Do this even if Ally Base resources are currently below 5.
3. Do not require resources >= 5 for repeated build attempts.
4. Absence of Ally Barracks Unit means the barracks task is not complete.
5. Repeated build commands are correct.
6. Continue issuing a barracks build command from worker_2 every turn until a current-state line exactly contains "Ally Barracks Unit".
7. Never replace worker_2 with worker_1, worker_3, or another worker.
Build target selection:
Choose the first valid target near the Ally Base using this priority order:
1. (base_x + 1, base_y)
2. (base_x, base_y + 1)
3. (base_x - 1, base_y)
4. (base_x, base_y - 1)
5. (base_x + 1, base_y + 1)
6. (base_x - 1, base_y + 1)
7. (base_x + 1, base_y - 1)
8. (base_x - 1, base_y - 1)
9. (base_x + 2, base_y)
10. (base_x, base_y + 2)
11. (base_x - 2, base_y)
12. (base_x, base_y - 2)
A valid build target must be:
- inside the map
- not occupied by any listed unit
- not occupied by any listed building
- not occupied by any listed resource
Required barracks action:
(worker_2_x,worker_2_y): worker build((target_x,target_y), barracks)
Other units:
- worker_1 should harvest.
- worker_3 and later should harvest.
- Base may train workers only if this is not caused by worker count when worker_status=enough_workers.
- Do not send worker_2 to harvest, move, or attack while no Ally Barracks Unit exists.

EARLY GAME:
- If worker_status=less_than_2, Ally Base trains worker first.
- Else if no current-state line exactly contains "Ally Barracks Unit", assign worker_2 to build barracks.
- worker_1 harvests.
- worker_3 and later harvest.
- Do not attack early unless Enemy units are threatening the Ally Base.
- Enemy Barracks Unit does not change this rule.

MID GAME:
- Enter mid game only if a current-state line exactly contains "Ally Barracks Unit".
- Train combat units only from Ally Barracks Unit.
- Keep workers harvesting.
- Move army toward Enemy Base.

LATE GAME:
- Attack Enemy Base and Enemy units.
- Continue producing army.

STRATEGY:
1. Train at least 2 workers.
2. Build economy.
3. Use worker_2 to build one Ally Barracks as early as possible.
4. Train army from Ally Barracks only.
5. Attack enemy base.

STRATEGY TO ACTION:
economy -> harvest
workers -> base train(worker)
barracks -> worker_2 build((target_x,target_y), barracks)
army -> Ally barracks train(light), Ally barracks train(heavy), Ally barracks train(ranged)
attack -> attack((enemy_x,enemy_y))

DECISION PROCESS:
1. Read CURRENT GAME STATE only.
2. Compute worker_count from current-state lines containing "Ally Worker Unit".
3. Set worker_status:
   - worker_count is 0 or 1 -> worker_status=less_than_2
   - worker_count is 2, 3, 4, 5, or more -> worker_status=enough_workers
4. Compute has_ally_barracks from current-state lines containing "Ally Barracks Unit".
5. Assign worker roles by Feature locations order.
6. If worker_status=less_than_2:
   - Assign Ally Base to train(worker) if possible.
   - Assign existing workers to harvest.
7. Else if worker_status=enough_workers and has_ally_barracks == False:
   - We do not have a barracks.
   - Assign worker_2 to build barracks.
   - Assign worker_1 and other workers to harvest.
   - Ignore Enemy Barracks Unit when deciding whether we have barracks.
8. Else if has_ally_barracks == True:
   - Use only Ally Barracks Unit position to train combat units.
   - Assign workers to harvest.
9. If combat units exist, attack or move toward Enemy Base.
10. Output up to Max actions.
11. Output valid JSON only.

Each move must include:
- raw_move
- unit_position
- unit_type
- action_type

INPUT:
Map size: 8x8
Turn: 10
Max actions: 4
Feature locations:
(2, 1) Ally Base Unit {resources=3, current_action="idling", HP=10}
(0, 0) Neutral Resource Node {resources=20}
(5, 6) Enemy Base Unit {resources=4, current_action="idling", HP=10}
(1, 1) Ally Worker Unit {current_action="idling", HP=1}
OUPUT:
{
  "thinking": "worker_count=1; worker_status=less_than_2; has_ally_barracks=False; builder=none; decision=train_worker; reason=only one Ally Worker Unit exists in this example input, so Ally Base must train worker before barracks construction",
  "moves": [
    {
      "raw_move": "(2,1): base train(worker)",
      "unit_position": [2,1],
      "unit_type": "base",
      "action_type": "train"
    },
    {
      "raw_move": "(1,1): worker harvest((0,0),(2,1))",
      "unit_position": [1,1],
      "unit_type": "worker",
      "action_type": "harvest"
    }
  ]
}

INPUT:
Map size: 8x8
Turn: 70
Max actions: 4
Feature locations:
(0, 0) Neutral Resource Node {resources=17}
(7, 7) Neutral Resource Node {resources=20}
(2, 1) Ally Base Unit {resources=6, current_action="idling", HP=10}
(5, 6) Enemy Base Unit {resources=4, current_action="idling", HP=10}
(1, 1) Ally Worker Unit {current_action="idling", HP=1}
(2, 3) Ally Worker Unit {current_action="idling", HP=1}
(2, 0) Ally Worker Unit {current_action="idling", HP=1}
OUTPUT:
{
  "thinking": "worker_count=3; worker_status=enough_workers; has_ally_barracks=False; builder=(2,3); decision=build_barracks_with_worker_2; reason=there are enough workers and no Ally Barracks Unit exists, so worker_2 must build barracks while other workers harvest",
  "moves": [
    {
      "raw_move": "(2,3): worker build((3,1), barracks)",
      "unit_position": [2,3],
      "unit_type": "worker",
      "action_type": "build"
    },
    {
      "raw_move": "(1,1): worker harvest((0,0),(2,1))",
      "unit_position": [1,1],
      "unit_type": "worker",
      "action_type": "harvest"
    },
    {
      "raw_move": "(2,0): worker harvest((0,0),(2,1))",
      "unit_position": [2,0],
      "unit_type": "worker",
      "action_type": "harvest"
    }
  ]
}

Here are the rewritten rules for the current game state:
* If there are enough workers (`worker_count >= 3`) and no Ally Barracks Unit exists (`has_ally_barracks == False`), then worker\\_2 must build a barracks while other workers harvest.
* Set the builder to the position of worker\\_2.
* The decision is `build_barracks_with_worker_2`.
* Worker at position (2,0) builds a barracks at position (3,1).
* Worker at position (1,1) harvests resources from the neutral resource node at position (0,0) and delivers them to the Ally Base Unit at position (2,1).

INPUT:
Map size: 8x8
Turn: 150
Max actions: 4
Feature locations:
(0, 0) Neutral Resource Node {resources=9}
(7, 7) Neutral Resource Node {resources=20}
(2, 1) Ally Base Unit {resources=6, current_action="idling", HP=10}
(5, 6) Enemy Base Unit {resources=4, current_action="idling", HP=10}
(1, 1) Ally Worker Unit {current_action="idling", HP=1}
(2, 3) Ally Worker Unit {current_action="idling", HP=1}
(2, 0) Ally Worker Unit {current_action="idling", HP=1}
(3, 0) Ally Worker Unit {current_action="idling", HP=1}
(3, 2) Ally Worker Unit {current_action="idling", HP=1}
OUTPUT:
{
  "thinking": "worker_count=5; worker_status=enough_workers; has_ally_barracks=False; builder=(2,3); decision=build_barracks_with_worker_2; reason=worker_count is 5, so do not choose train_worker because of worker count; no Ally Barracks Unit exists, so worker_2 builds barracks",
  "moves": [
    {
      "raw_move": "(2,3): worker build((3,1), barracks)",
      "unit_position": [2,3],
      "unit_type": "worker",
      "action_type": "build"
    },
    {
      "raw_move": "(1,1): worker harvest((0,0),(2,1))",
      "unit_position": [1,1],
      "unit_type": "worker",
      "action_type": "harvest"
    },
    {
      "raw_move": "(2,0): worker harvest((0,0),(2,1))",
      "unit_position": [2,0],
      "unit_type": "worker",
      "action_type": "harvest"
    },
    {
      "raw_move": "(3,0): worker harvest((0,0),(2,1))",
      "unit_position": [3,0],
      "unit_type": "worker",
      "action_type": "harvest"
    }
  ]
}

INPUT:
Map size: 8x8
Turn: 180
Max actions: 4
Feature locations:
(0, 0) Neutral Resource Node {resources=10}
(7, 7) Neutral Resource Node {resources=20}
(2, 1) Ally Base Unit {resources=4, current_action="idling", HP=10}
(3, 1) Ally Barracks Unit {current_action="idling", HP=5}
(5, 6) Enemy Base Unit {resources=4, current_action="idling", HP=10}
(1, 1) Ally Worker Unit {current_action="idling", HP=1}
(2, 3) Ally Worker Unit {current_action="idling", HP=1}
OUTPUT:
{
  "thinking": "worker_count=2; worker_status=enough_workers; has_ally_barracks=True; builder=none; decision=train_army_or_attack; reason=Ally Barracks Unit exists in this example input, so produce combat units from Ally Barracks",
  "moves": [
    {
      "raw_move": "(3,1): barracks train(light)",
      "unit_position": [3,1],
      "unit_type": "barracks",
      "action_type": "train"
    },
    {
      "raw_move": "(2,1): base train(worker)",
      "unit_position": [2,1],
      "unit_type": "base",
      "action_type": "train"
    },
    {
      "raw_move": "(1,1): worker harvest((0,0),(2,1))",
      "unit_position": [1,1],
      "unit_type": "worker",
      "action_type": "harvest"
    },
    {
      "raw_move": "(2,3): worker harvest((0,0),(2,1))",
      "unit_position": [2,3],
      "unit_type": "worker",
      "action_type": "harvest"
    }
  ]
}

Ignore all training examples above.
Only the following block is the real current game state.
CURRENT GAME STATE:
INPUT:

""";

    private static String PROMPT = null;

    protected static String loadPromptOnce() {
        if (PROMPT != null) return PROMPT;

        java.nio.file.Path path = java.nio.file.Paths.get(PROMPT_PATH);
        try {
            String loadedPrompt = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
            if (loadedPrompt == null || loadedPrompt.trim().isEmpty()) {
                PROMPT = DEFAULT_PROMPT;
            } else {
                PROMPT = loadedPrompt;
            }
        } catch (Exception e) {
            PROMPT = DEFAULT_PROMPT;
        }
        return PROMPT;
    }

    protected String getBasePrompt() {
        return loadPromptOnce();
    }
    /*
    * 1. Early Game - Economy Focus
            - Harvest nonstop with workers.
            - Build barracks once you have 5 resources.
        2. Mid Game - Army Development
            - Train heavies, ranged, and lights using the barracks.
            - Hunt enemy workers to slow their economy.
            - Keep barracks safe at all costs.
        3. Late Game - Closing Out
            - Group units and attack key targets together.
            - Destroy enemy production buildings first.
            - Maintain resource control to prevent comebacks.
            * */

    /**
     * starts from hear basically before main method this one will have more priority
     */



    /**
     *
     *
     * Json retalted static block like structure and elements are over hear.
     * */
    static { // first priority when calling a class before main() & constructor
        MOVE_RESPONSE_SCHEMA = new JsonObject();


        String schemaJson = """
                {
                  "type": "object",
                  "properties": {
                    "thinking": {
                      "type": "string",
                      "description": "Plan out what moves you should take you can do multiple moves at a times"
                    },
                    "moves": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "raw_move": {
                            "type": "string"
                          },
                          "unit_position": {
                            "type": "array",
                            "items": {
                              "type": "integer"
                            },
                            "minItems": 2,
                            "maxItems": 2
                          },
                          "unit_type": {
                            "type": "string",
                            "enum": [
                              "worker",
                              "light",
                              "heavy",
                              "ranged",
                              "base",
                              "barracks"
                            ]
                          },
                          "action_type": {
                            "type": "string",
                            "enum": [
                              "move",
                              "train",
                              "build",
                              "harvest",
                              "attack"
                            ]
                          }
                        },
                        "required": [
                          "raw_move",
                          "unit_position",
                          "unit_type",
                          "action_type"
                        ]
                      }
                    }
                  },
                  "required": [
                    "moves",
                    "thinking"
                  ],
                  "propertyOrdering": [
                    "thinking",
                    "moves"
                  ]
                }
      """; // "thinking",

        JsonParser parser = new JsonParser();   /// if any format of json issue take a look and any modifications have a look
        JsonObject responseSchema = parser.parse(schemaJson).getAsJsonObject();
        MOVE_RESPONSE_SCHEMA.add("response_schema", responseSchema);

    }

    // is there any other way to give prompt in a better way to give Free to it ?


    /**
     * constructors
     */

    /**
     *
     * @param a_utt
     *
     */
    public EAGLE(UnitTypeTable a_utt) {
        this(a_utt, new AStarPathFinding());
    }

    public EAGLE(UnitTypeTable a_utt,String aiName1, String aiName2){
        this(a_utt, new AStarPathFinding());
        if((aiName1 != null && aiName2 != null) && (!(aiName1.isEmpty())  || !(aiName2.isEmpty())) && logsInitializedone != true) {
            this.aiName1 = aiName1;
            this.aiName2 = aiName2;
            logsInitializedone = true;
        }
    }

    /**
     *
     * @param a_utt = ?
     * @param a_pf = ?
     */
    public EAGLE(UnitTypeTable a_utt, PathFinding a_pf) {
        super(a_pf); //
        reset(a_utt); // method call
    }


    /**
     *
     * reset function is reseting they Time budget & Iteration budget
     * going to they reset of abstract layer ai to reset are clearing they data from
     * the hashMap   HashMap<Unit, AbstractAction>
     *
     *
     * TIME_BUDGET  in  aiwithComputationbudget
     * ITERATIONS_BUDGET  in  aiwithComputationbudget
     */
    public void reset() {
        super.reset();
        TIME_BUDGET = -1;
        ITERATIONS_BUDGET = -1;
    }

    /**
     *
     * @param a_utt
     */
    public void reset(UnitTypeTable a_utt)
    {
        utt = a_utt;
        resourceType = utt.getUnitType("Resource");
        workerType = utt.getUnitType("Worker");
        lightType = utt.getUnitType("Light");
        heavyType = utt.getUnitType("Heavy");
        rangedType = utt.getUnitType("Ranged");
        baseType = utt.getUnitType("Base");
        barracksType = utt.getUnitType("Barracks");
    }

    /**
     * utt passing with a_utt
     * pf from abstract layer ai
     *
     *
     * @return
     */

    @Override
    public AI clone() {
        return new EAGLE(utt, pf);
    }


    /**
     *
     * @param player ID of the player to move. Use it to check whether units are yours or enemy's
     * @param gs the game state where the action should be performed
     * @return
     */



    @Override
    public PlayerAction getAction(int player, GameState gs) throws Exception {

        String finalPrompt;

        // If we're NOT on an LLM turn, just keep executing the abstract actions already assigned
        if (gs.getTime() % LLM_INTERVAL != 0) {

            PlayerAction pa = translateActions(player, gs);
            return pa;
        }

        // ===== Gather game context =====
        PhysicalGameState pgs = gs.getPhysicalGameState();
        int width = pgs.getWidth();
        int height = pgs.getHeight();
        Player p = gs.getPlayer(player);

        ArrayList<String> features = new ArrayList<>();
        int maxActions = 0;

        // Build feature list for the prompt and help us count how many units we can legally command
        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() == player) {
                maxActions++;
            }

            String unitStats;
            UnitAction unitAction = gs.getUnitAction(u);
            String unitActionString = unitActionToString(unitAction);

            String unitType;
            if (u.getType() == resourceType) {
                unitType = "Resource Node";
                unitStats = "{resources=" + u.getResources() + "}";
            } else if (u.getType() == baseType) {
                unitType = "Base Unit";
                unitStats = "{resources=" + p.getResources() +
                        ", current_action=\"" + unitActionString +
                        "\", HP=" + u.getHitPoints() + "}";
            } else if (u.getType() == barracksType) {
                unitType = "Barracks Unit";
                unitStats = "{current_action=\"" + unitActionString +
                        "\", HP=" + u.getHitPoints() + "}";
            } else if (u.getType() == workerType) {
                unitType = "Worker Unit";
                unitStats = "{current_action=\"" + unitActionString +
                        "\", HP=" + u.getHitPoints() + "}";
            } else if (u.getType() == lightType) {
                unitType = "Light Unit";
                unitStats = "{current_action=\"" + unitActionString +
                        "\", HP=" + u.getHitPoints() + "}";
            } else if (u.getType() == heavyType) {
                unitType = "Heavy Unit";
                unitStats = "{current_action=\"" + unitActionString +
                        "\", HP=" + u.getHitPoints() + "}";
            } else if (u.getType() == rangedType) {
                unitType = "Ranged Unit";
                unitStats = "{current_action=\"" + unitActionString +
                        "\", HP=" + u.getHitPoints() + "}";
            } else {
                unitType = "Unknown";
                unitStats = "{}";
            }

            String unitPos = "(" + u.getX() + ", " + u.getY() + ")";
            String team = (u.getPlayer() == player) ? "Ally" :
                    (u.getType() == resourceType ? "Neutral" : "Enemy");

            features.add(unitPos + " " + team + " " + unitType + " " + unitStats);
        }

        // Map summary for the LLM
        String mapPrompt         = "Map size: " + width + "x" + height;
        String turnPrompt        = "Turn: " + gs.getTime();
        String maxActionsPrompt  = "Max actions: " + maxActions;

        String featuresPrompt = "Feature locations:\n" + String.join("\n", features);

        // Final LLM prompt
        finalPrompt = getBasePrompt() + "\n\n" +
                mapPrompt + "\n" +
                turnPrompt + "\n" +
                maxActionsPrompt + "\n\n" +
                featuresPrompt + "\n";

        String response = prompt(finalPrompt);
        // ===== Parse model JSON safely & log pretty copy =====
        JsonObject jsonResponse = parseJsonStrictThenLenient(response);

        // ===== Extract "moves" array from model output =====
        JsonArray moveElements = jsonResponse.getAsJsonArray("moves");

        if (moveElements == null || moveElements.size() == 0) {
            PlayerAction fallbackPA = translateActions(player, gs);
            return fallbackPA;
        }

        // ===== Try to apply each move from the LLM safely =====
        for (JsonElement moveElement : moveElements) {
            try {
                if (!moveElement.isJsonObject()) {
                    continue;
                }

                JsonObject move = moveElement.getAsJsonObject();

                // --- Validate unit_position ---
                if (!move.has("unit_position") || !move.get("unit_position").isJsonArray()) {
                    continue;
                }

                JsonArray unitPosition = move.getAsJsonArray("unit_position");
                if (unitPosition == null ||
                        unitPosition.size() < 2 ||
                        unitPosition.get(0).isJsonNull() ||
                        unitPosition.get(1).isJsonNull()) {
                    continue;
                }

                int unitX = unitPosition.get(0).getAsInt();
                int unitY = unitPosition.get(1).getAsInt();

                // --- Look up the unit in the game ---
                Unit unit = pgs.getUnitAt(unitX, unitY);
                if (unit == null) {
                    continue;
                }

                // cannot command enemy / neutral units
                if (unit.getPlayer() != player) {
                    continue;
                }

                // --- Required action fields ---
                if (!move.has("action_type") || !move.has("raw_move")) {
                    continue;
                }

                String actionType = move.get("action_type").getAsString();
                String rawMove    = move.get("raw_move").getAsString();
                String unitType   = move.has("unit_type")
                        ? move.get("unit_type").getAsString()
                        : "unknown";

                // We'll parse text like "(2,1): worker move((3,1))"
                // using regex per action type, then call the abstraction-layer helpers
                switch (actionType) {
                    case "move": {
                        // structures can't move
                        if (unit.getType() == baseType || unit.getType() == barracksType) {
                            break;
                        }

                        Pattern pattern = Pattern.compile(
                                "\\(\\s*\\d+,\\s*\\d+\\):.*?move\\(\\(\\s*(\\d+),\\s*(\\d+)\\s*\\)\\)"
                        );
                        Matcher matcher = pattern.matcher(rawMove);

                        if (matcher.find()) {
                            int targetX = Integer.parseInt(matcher.group(1));
                            int targetY = Integer.parseInt(matcher.group(2));
                            move(unit, targetX, targetY);
                        } else {
                        }
                        break;
                    }

                    case "harvest": {
                        // workers only
                        if (unit.getType() != workerType) {
                            break;
                        }

                        Pattern pattern = Pattern.compile(
                                "\\(\\s*\\d+,\\s*\\d+\\):.*?harvest\\(\\((\\d+),\\s*(\\d+)\\),\\s*\\((\\d+),\\s*(\\d+)\\)\\)"
                        );
                        Matcher matcher = pattern.matcher(rawMove);

                        if (matcher.find()) {
                            int resourceX = Integer.parseInt(matcher.group(1));
                            int resourceY = Integer.parseInt(matcher.group(2));
                            int baseX     = Integer.parseInt(matcher.group(3));
                            int baseY     = Integer.parseInt(matcher.group(4));

                            Unit resourceUnit = pgs.getUnitAt(resourceX, resourceY);
                            Unit baseUnit     = pgs.getUnitAt(baseX, baseY);

                            if (resourceUnit != null && baseUnit != null) {
                                harvest(unit, resourceUnit, baseUnit);
                            } else {
                            }
                        } else {
                        }
                        break;
                    }

                    case "train": {
                        // only base or barracks can train
                        if ((unit.getType() != baseType) && (unit.getType() != barracksType)) {
                            break;
                        }

                        Pattern pattern = Pattern.compile(
                                "\\(\\s*\\d+,\\s*\\d+\\):.*?train\\(\\s*['\"]?(\\w+)['\"]?\\s*\\)"
                        );
                        Matcher matcher = pattern.matcher(rawMove);

                        if (matcher.find()) {
                            String stringTrainUnitType = matcher.group(1);
                            UnitType trainUnitType = stringToUnitType(stringTrainUnitType);
                            train(unit, trainUnitType);
                        } else {
                        }
                        break;
                    }

                    case "build": {
                        // only workers can build
                        if (unit.getType() != workerType) {
                            break;
                        }

                        Pattern pattern = Pattern.compile(
                                "\\(\\s*\\d+,\\s*\\d+\\):.*?build\\(\\s*\\(\\s*(\\d+),\\s*(\\d+)\\s*\\),\\s*['\"]?(\\w+)['\"]?\\s*\\)"
                        );
                        Matcher matcher = pattern.matcher(rawMove);

                        if (matcher.find()) {
                            int buildX = Integer.parseInt(matcher.group(1));
                            int buildY = Integer.parseInt(matcher.group(2));
                            String stringBuildUnitType = matcher.group(3);
                            UnitType unitBuildType = stringToUnitType(stringBuildUnitType);
                            build(unit, unitBuildType, buildX, buildY);
                        } else {
                        }
                        break;
                    }

                    case "attack": {
                        Pattern pattern = Pattern.compile(
                                "\\(\\s*\\d+,\\s*\\d+\\):.*?attack\\(\\s*\\(\\s*(\\d+),\\s*(\\d+)\\s*\\)\\s*\\)"
                        );
                        Matcher matcher = pattern.matcher(rawMove);

                        if (matcher.find()) {
                            int enemyX = Integer.parseInt(matcher.group(1));
                            int enemyY = Integer.parseInt(matcher.group(2));
                            Unit enemyUnit = pgs.getUnitAt(enemyX, enemyY);

                            if (enemyUnit != null) {
                                attack(unit, enemyUnit);
                            } else {
                            }
                        } else {
                        }
                        break;
                    }

                    case "idle": {
                        idle(unit);
                        break;
                    }

                    default: {
                        break;
                    }
                }

            } catch (Exception ex) {
                // CRITICAL: swallow bad move so AI doesn't crash the whole game
                // continue to next move
            }
        }

        // ===== Auto-defense override (only if unit has no current abstract action) =====
        // If an allied combat unit is standing next to an enemy, let it attack,
        // but DON'T override if LLM already gave that unit an action.
        for (Unit u1 : pgs.getUnits()) {
            // only consider our units that can attack
            if (u1.getPlayer() != player || !u1.getType().canAttack) {
                continue;
            }

            Unit closestEnemy = null;
            int closestDistance = 0;

            for (Unit u2 : pgs.getUnits()) {
                if (u2.getPlayer() == player) continue; // skip allies

                int d = Math.abs(u2.getX() - u1.getX()) + Math.abs(u2.getY() - u1.getY());
                if (closestEnemy == null || d < closestDistance) {
                    closestEnemy = u2;
                    closestDistance = d;
                }
            }

            if (closestEnemy != null && closestDistance == 1) {
                if (getAbstractAction(u1) == null) {
                    attack(u1, closestEnemy);
                } else {
                }
            }
        }
        // ===== Return the final PlayerAction for this frame =====
        PlayerAction pa = translateActions(player, gs);
        return pa;
    }


    static String sanitizeModelJson(String s) {
        if (s == null) return "";
        s = s.trim();

        // Strip Markdown code fences if model adds them
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            if (first >= 0) s = s.substring(first + 1);
            int close = s.lastIndexOf("```");
            if (close > 0) s = s.substring(0, close);
            s = s.trim();
        }

        // If the model prepended text, jump to first JSON object/array
        int obj = s.indexOf('{');
        int arr = s.indexOf('[');
        int start = (obj == -1) ? arr : (arr == -1 ? obj : Math.min(obj, arr));
        if (start > 0) s = s.substring(start).trim();

        return s;
    }

    static JsonObject parseJsonStrictThenLenient(String raw) {
        String cleaned = sanitizeModelJson(raw);
        try {
            return JsonParser.parseString(cleaned).getAsJsonObject();
        } catch (JsonSyntaxException e) {
            try {
                com.google.gson.stream.JsonReader r =
                        new com.google.gson.stream.JsonReader(new java.io.StringReader(cleaned));
                r.setLenient(true);
                return JsonParser.parseReader(r).getAsJsonObject();
            } catch (Exception e2) {
                throw e; // bubble up the original strict error
            }
        }
    }












    // Abstraction functions:
    // - move(Unit ally, int x, int y)
    // - train(Unit ally, UnitType type)
    // - build(Unit ally, UnitType building, int x, int y)
    // - harvest(Unit ally, Unit resource, Unit base)
    // - attack(Unit ally, Unit enemy)
    // - idle(Unit u)
    // - buildIfNotAlreadyBuilding(Unit ally, UnitType building, Int x, Int y, Player p, PhysicalGameState pgs) (This function has been omitted from the LLM)
    public String prompt(String finalPrompt) {
        try {
            // Build Ollama request body
            JsonObject body = new JsonObject();
            body.addProperty("model", MODEL);
            // Prepend /no_think to disable qwen3 thinking mode for faster responses
            body.addProperty("prompt", "/no_think " + finalPrompt);
            body.addProperty("stream", OLLAMA_STREAM);   // false -> single JSON
            body.addProperty("format", OLLAMA_FORMAT);   // "json" -> enforce JSON output

            // Optional generation knobs (tweak as needed):
            // body.addProperty("temperature", 0.4);
            // body.addProperty("num_ctx", 8192);

            URL url = new URL(OLLAMA_HOST + "/api/generate");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            // record request time for latency
            promptTime = Instant.now();

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                os.write(input);
            }

            int code = conn.getResponseCode();
            InputStream is = (code == HttpURLConnection.HTTP_OK)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                for (String line; (line = br.readLine()) != null; ) sb.append(line);
            }

            responseTime = Instant.now();
            Latency = responseTime.toEpochMilli() - promptTime.toEpochMilli();

            if (code != HttpURLConnection.HTTP_OK) {
                return "{\"thinking\":\"error\",\"moves\":[]}";
            }

            // Ollama /api/generate returns JSON like:
            // {"model":"...","created_at":"...","response":"...TEXT...","done":true,...}
            // Note: qwen3 "thinking" models put output in "thinking" field instead of "response"
            JsonObject top = JsonParser.parseString(sb.toString()).getAsJsonObject();

            String modelText = "";

            // First try "response" field (standard models like llama3.1)
            if (top.has("response") && !top.get("response").getAsString().isEmpty()) {
                modelText = top.get("response").getAsString();
            }
            // Fall back to "thinking" field (qwen3 thinking models)
            else if (top.has("thinking") && !top.get("thinking").isJsonNull()) {
                modelText = top.get("thinking").getAsString();
            }
            else {
                return "{\"thinking\":\"invalid_response\",\"moves\":[]}";
            }

            // Return the text **as-is** ??your caller will parse to JSON later
            return modelText;

        } catch (Exception e) {
            return "{\"thinking\":\"exception\",\"moves\":[]}";
        }
    }



    @Override
    public List<ParameterSpecification> getParameters()
    {
        List<ParameterSpecification> parameters = new ArrayList<>();

        parameters.add(new ParameterSpecification("PathFinding", PathFinding.class, new AStarPathFinding()));

        return parameters;
    }


    private UnitType stringToUnitType(String string) {
        string = string.toLowerCase();
        switch (string) {
            case "worker":
                return workerType;
            case "light":
                return lightType;
            case "heavy":
                return heavyType;
            case "ranged":
                return rangedType;
            case "base":
                return baseType;
            case "barracks":
                return barracksType;
            default:
                return workerType;
        }
    }

    private String unitActionToString(UnitAction action) {
        if (action == null) { return "idling"; }

        String description;
        switch (action.getType()) {
            case UnitAction.TYPE_MOVE:
                description = String.format("moving to (%d,%d)", action.getLocationX(), action.getLocationY());
                break;
            case UnitAction.TYPE_HARVEST:
                description = String.format("harvesting from (%d,%d)", action.getLocationX(), action.getLocationY());
                break;
            case UnitAction.TYPE_RETURN:
                description = String.format("returning resources to (%d,%d)", action.getLocationX(), action.getLocationY());
                break;
            case UnitAction.TYPE_PRODUCE:
                description = String.format("producing unit at (%d,%d)", action.getLocationX(), action.getLocationY());
                break;
            case UnitAction.TYPE_ATTACK_LOCATION:
                description = String.format("attacking location (%d,%d)", action.getLocationX(), action.getLocationY());
                break;
            case UnitAction.TYPE_NONE:
                description = "idling";
                break;
            default:
                description = "unknown action";
                break;
        }
        return description;
    }
}

