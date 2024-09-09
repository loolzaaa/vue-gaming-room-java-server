# Vue Gaming Room Java Server

A basic platform for creating web-based games with a room concept.

The server allows you to create/join rooms. Each room can have 1 game. 
Each room contains a set of room members, who can become players or observers. 
After all players are ready and the game starts, observers are removed from the room.

## Usage

### Install server dependency locally

- Clone this repo
- cd path/to/cloned/repo
- Run `mvn clean install`

### Setup game server

#### Create new project

- Create new Spring Boot project in [Spring Initializer](https://start.spring.io/) with Lombok (optionally) dependency
- Add early installed server dependency to `pom.xml`
```xml
<dependency>
  <groupId>ru.loolzaaa.games.vue-gaming-room-java-server</groupId>
  <artifactId>java-server-spring-boot-starter</artifactId>
  <version>0.2.5</version>
</dependency>
```

#### Create game entities

First, you need to create a player entity. The best way to do this is by inheriting from an `AbstractPlayer`.
Each player contains fields that apply only to him in game (the cards in his hand, the received resources, etc.)

```java
@ToString
@Getter
@Setter
public class Player extends AbstractPlayer {

    private final int number;

    private List<GameCard> cardsInHand = new ArrayList<>();

    public Player(int number, Member member) {
        super(member);
        this.number = number;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Player player = (Player) o;
        return number == player.number;
    }

    @Override
    public int hashCode() {
        return Objects.hash(number);
    }
}
```

**NOTE:** If players are supposed to take turns, it makes sense to add a player's ordinal number field, which, 
among other things, will allow you to easily override the `equals` and `hashCode` methods.

Next, you need to create an implementation of the Game entity. 
For this, you should implement the `Game` interface. 
Each game should specify its name, as well as the minimum/maximum number of players.

```java
@Getter
@Setter
public class GameImpl implements Game {

    public static final String GAME_NAME = "test-game";
    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 4;

    // Game settings implementation. See below.
    private GameSettings settings = new GameSettings();

    List<GameCard> mainDeck;
    List<GameCard> discardDeck;
    private List<Player> players;
    // ... more game properties

    @Override
    public String getName() {
        return GAME_NAME;
    }

    @Override
    public int getMinPlayers() {
        return MIN_PLAYERS;
    }

    @Override
    public int getMaxPlayers() {
        return MAX_PLAYERS;
    }
}
```

Optinally, game can contain settings. If so, add its to game.

```java
@Getter
@Setter
public class GameSettings {
    private boolean allowGameOption = false;
    private int answerTimeMs = 10000;
}
```

#### Create game service

All actions that will change the state of the game must be performed in the corresponding service, 
which must implement the `GameService<G extends Game>` interface.

```java
@Service
public class GameServiceImpl implements GameService<GameImpl> {
    @Override
    public String getGameName() {
        return GameImpl.GAME_NAME;
    }
    
    @Override
    public GameImpl createGameInstance() {
        return new GameImpl();
    }

    @Override
    public void startNewGame(Game g, List<Member> members) {
        GameImpl game = (GameImpl) g;

        // Start game routine
    }

    public void nextMove(GameImpl game) {
        // Some method changes game state
    }
}
```

#### Create game event processor

Any interaction between the player and the game occurs via a websocket. 
To handle incoming/outgoing events, you need to implement the `WebSocketEventProcessor` interface.

In this exmaple `GameStateDTO` - data transfer object that will contain the complete state of the game. 
The state must be complete enough to allow reconnection to the game in case of a crash.

`Consumer<JsonNode> sendMessage` - this consumer allows you to send a message to the player from whom the incoming event came.

`Consumer<String> callbackEvent` - this consumer allows you to send an outgoing event to ALL players after the incoming event has been processed.

If you need to indicate an error to the player during incoming event data validation, 
you can throw any exception, it will be handled by the implemented `WebSocketEventProcessor#processEventError(Exception e)` method.

```java
@Component
@RequiredArgsConstructor
public class GameEventProcessorImpl implements WebSocketEventProcessor {

    // Outgoing events
    public static final String NEXT_MOVE = "NEXT_MOVE";

    // Incoming events
    public static final String PASS_MOVE_CHOSEN = "PASS_MOVE_CHOSEN";

    private final GameServiceImpl gameService;

    private final ObjectMapper mapper;

    @Override
    public JsonNode createGameState(Game g, String userId) {
        GameImpl game = (GameImpl) g;

        GameStateDTO gameStateDTO = GameStateDTO.create(userId, game);
        return mapper.valueToTree(gameStateDTO);
    }

    @Override
    public void updateGameSettings(JsonNode settingsNode, Game g, Consumer<JsonNode> sendMessage, Consumer<String> callbackEvent) {
        GameImpl game = (GameImpl) g;
        GameSettings gameSettings = mapper.convertValue(settingsNode, GameSettings.class);
        game.setSettings(gameSettings);
    }

    @Override
    public JsonNode startGame(Game g, String userId) {
        return createGameState(g, userId);
    }

    @Override
    public JsonNode restartGame(Game g, String s) {
        return null;
    }

    @Override
    public void incomingEvent(ObjectNode eventNode, Game g, String userId, Consumer<JsonNode> sendMessage, Consumer<String> callbackEvent) {
        GameImpl game = (GameImpl) g;

        String event = eventNode.get(EVENT_PROPERTY_NAME).asText();
        switch (event) {
            case PASS_MOVE_CHOSEN -> passMoveChosen(game, sendMessage, callbackEvent);
        }
    }

    @Override
    public ObjectNode outgoingEvent(String event, Game g, String userId) {
        GameImpl game = (GameImpl) g;

        return switch (event) {
            case NEXT_MOVE -> nextMove(game, userId);
            default -> null;
        };
    }

    @Override
    public JsonNode processEventError(Exception e) {
        return mapper.getNodeFactory().textNode(e.getLocalizedMessage());
    }

    private ObjectNode nextMove(GameImpl game, String userId) {
        ObjectNode messageNode = mapper.createObjectNode();
        messageNode.put(EVENT_PROPERTY_NAME, GameWebSocketHandler.GAME_STATE);
        messageNode.set(DATA_PROPERTY_NAME, createGameState(game, userId));
        return messageNode;
    }

    private void passMoveChosen(GameImpl game, Consumer<JsonNode> sendMessage, Consumer<String> callbackEvent) {
        GameCard gameCard = gameService.passMoveChosen(game);
        callbackEvent.accept(NEXT_MOVE);
    }
}
```

## Game client

You can find description of Vue Gaming Room Client [here](https://github.com/loolzaaa/vue-gaming-room).
