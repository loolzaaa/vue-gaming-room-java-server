# Vue Gaming Room Java Server

A basic platform for creating web-based games with a room concept.

The server allows you to create/join rooms. Each room can have 1 game. 
Each room contains a set of room members, who can become players or observers. 
After all players are ready and the game starts, observers are removed from the room.

## Usage

### Requirements

- JDK 21
- Maven 3.8.5+ (or the bundled Maven Wrapper)

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

#### Configuration

All settings are optional and can be overridden in `application.yml`/`application.properties`:

| Property | Default | Description |
|---|---|---|
| `vgr.room.max-members` | `200` | Maximum number of room members |
| `vgr.room.inactive-timeout` | `60m` | Inactive room cleanup timeout |
| `vgr.room.clean-initial-delay-ms` | `10000` | Delay before the first cleanup run |
| `vgr.room.clean-interval-ms` | `60000` | Interval between cleanup runs |
| `vgr.room.max-nickname-length` | `32` | Maximum nickname length in code points |
| `vgr.websocket.endpoint-path` | `/game-ws` | WebSocket endpoint path |
| `vgr.websocket.allowed-origins` | `*` | Allowed WebSocket origin patterns |
| `vgr.websocket.max-message-size` | `64KB` | Maximum incoming message size |
| `vgr.websocket.idle-timeout` | `120s` | Idle timeout for WebSocket sessions (`0` disables) |
| `vgr.websocket.send-time-limit` | `10s` | Maximum time to send a message to one client |
| `vgr.websocket.send-buffer-size` | `512KB` | Per-session send buffer limit |
| `vgr.websocket.max-sessions-per-user` | `3` | Maximum active WebSocket sessions per user (oldest are closed) |
| `vgr.websocket.rate-limit-per-second` | `30` | WebSocket messages per second per session |
| `vgr.websocket.rate-limit-burst` | `60` | Maximum WebSocket message burst per session |
| `vgr.websocket.ticket-ttl` | `30s` | Lifetime of a one-time WebSocket ticket |

The WebSocket endpoint path is also returned by `GET /game/info` as `wsEndpoint`,
so clients can discover a custom path automatically.

#### Security

- Every member gets an individual secret token. The token is returned in `RoomDTO.wsToken` and is not exposed in `UPDATE_MEMBERS` or other member payloads.
- All mutating REST endpoints require the `X-Member-Token` header. `POST /room/create` does not require it; `POST /room/join/{code}` requires it only when an existing member with the same `userId` is reconnecting.
- The recommended WebSocket flow:
  1. `POST /room/{code}/ws-ticket?userId={userId}` with the `X-Member-Token` header.
  2. Connect to `ws(s)://.../game-ws?ticket={ticket}`. Tickets are single-use and expire after `vgr.websocket.ticket-ttl`.
- The legacy `?token={userId}:{memberToken}` handshake is still accepted for compatibility.
- The server validates that the user is a member of the room and limits concurrent sessions per user.
- Per-session WebSocket rate limiting closes abusive connections.
- Use `vgr.websocket.allowed-origins` to restrict allowed origins in production and serve the app over TLS (`wss`).

#### Validation rules

- Nicknames are trimmed, normalized to Unicode NFC, limited by `vgr.room.max-nickname-length`,
  cannot contain control characters, and must be unique case-insensitively within a room.
- Member colors must use the `#RRGGBB` format; the server normalizes them to lowercase.
- Room codes consist of 4 uppercase Latin letters.
- User ids must be non-blank, at most 64 characters, and must not contain `:` or control characters.

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
