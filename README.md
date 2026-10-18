# Vue Gaming Room Java Server

A basic platform for creating web-based games with a room concept.

The server allows you to create/join rooms. Each room can have 1 game. 
Each room contains a set of room members, who can become players or observers. 
Players control the game, while observers stay in the room after the start and receive a public display state.

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
  <version>1.0.0</version>
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
| `vgr.websocket.send-queue-capacity` | `64` | Per-session async outbound queue capacity |
| `vgr.websocket.max-sessions-per-user` | `3` | Maximum active WebSocket sessions per user (oldest are closed) |
| `vgr.websocket.rate-limit-per-second` | `30` | WebSocket messages per second per session |
| `vgr.websocket.rate-limit-burst` | `60` | Maximum WebSocket message burst per session |
| `vgr.websocket.ticket-ttl` | `30s` | Lifetime of a one-time WebSocket ticket |
| `vgr.http.rate-limit-per-second` | `20` | HTTP requests per second per client address for `/room/**` |
| `vgr.http.rate-limit-burst` | `40` | Maximum HTTP request burst per client address |

The WebSocket endpoint path is also returned by `GET /game/info` as `wsEndpoint`,
so clients can discover a custom path automatically.

#### Security

- Every member gets an individual secret token. The token is returned in `RoomDTO.wsToken` and is not exposed in `UPDATE_MEMBERS` or other member payloads.
- All mutating REST endpoints require the `X-Member-Token` header. `POST /room/create` does not require it; `POST /room/join/{code}` requires it only when an existing member with the same `userId` is reconnecting. The spectator list endpoint requires both `userId` and `X-Member-Token`.
- The recommended WebSocket flow:
  1. `POST /room/{code}/ws-ticket?userId={userId}` with the `X-Member-Token` header.
  2. Connect to `ws(s)://.../game-ws?ticket={ticket}`. Tickets are single-use and expire after `vgr.websocket.ticket-ttl`.
- Only tickets are accepted during the handshake. The old `?token={userId}:{memberToken}` form is rejected.
- The server validates that the user is a member of the room and limits concurrent sessions per user.
- Per-session WebSocket rate limiting closes abusive connections.
- REST requests to `/room/**` are rate-limited per client address (`vgr.http.*`).
- Use `vgr.websocket.allowed-origins` to restrict allowed origins in production and serve the app over TLS (`wss`). The default `*` is intended for local development only.

#### Container requirements

- The server applies the idle timeout through the native JSR-356 API:
  `jakarta.websocket.Session#setMaxIdleTimeout`.
- The servlet container **must support native WebSocket idle timeouts** (Tomcat does).
  There is intentionally no fallback sweeper: if a container ignores this setting, dead sessions
  remain until the container/TCP closes them.
- Per-session text/binary message size limits are also applied through `WebSocketSession`.

#### Validation rules

- Nicknames are trimmed, normalized to Unicode NFC, limited by `vgr.room.max-nickname-length`,
  cannot contain control characters, and must be unique case-insensitively within a room.
- Member colors must use the `#RRGGBB` format; the server normalizes them to lowercase.
- Room codes consist of 4 uppercase Latin letters.
- User ids must be non-blank, at most 64 characters, and must not contain `:` or control characters.

#### Known limitations / TODO

- **Slow clients / outbound sending**: each session has a bounded asynchronous outbound queue
  (`vgr.websocket.send-queue-capacity`, default 64), served by a virtual thread.
  `GAME_STATE` and `UPDATE_MEMBERS` are coalesced — only the latest pending state is sent.
  A custom state-like event can be coalesced with `sendEvent(code, event, true)`;
  custom events default to non-coalescable. If the queue overflows, the session is closed.
  The legacy `ConcurrentWebSocketSessionDecorator` limits (`send-time-limit`, `send-buffer-size`)
  remain as a second safety layer. Sending is independent of broadcast/event threads, but a blocked
  `sendMessage` occupies only that session's sender; queue overflow/close is the practical timeout.
- **Metrics (TODO)**: only logs are emitted for queue overflow, send errors and slow clients.
  Planned metrics: active sessions, outbound queue size, coalesced messages, queue overflows,
  send latency/errors, handshake rejects. This will be implemented as a later step.
- **Token rotation / revocation (low priority)**: member tokens are stable for the room lifetime and are
  removed together with the member/room. Explicit rotate/revoke/logout-all endpoints are not implemented.
  For short-lived, anonymous board-game rooms this is not critical; revisit if rooms become long-lived
  or user accounts/valuables appear.
- **Token storage / XSS**: the reference Vue client stores the member token in `localStorage`.
  For simple board games this is an acceptable trade-off. If you add payments, accounts, valuable
  inventory or user-generated HTML, treat XSS as a real threat: use HttpOnly cookies or SSO,
  a strict CSP, sanitization and token rotation.
- **Game state concurrency**: game implementations are currently responsible for synchronizing
  access to their own state; per-room serialization is planned separately.

#### Rate limiting at the reverse proxy

The application limits `/room/**` requests per client address, but an edge limit is still recommended.
Example for nginx:

```nginx
limit_req_zone $binary_remote_addr zone=vgr_room:10m rate=20r/s;
limit_conn_zone $binary_remote_addr zone=vgr_conn:10m;
limit_req_status 429;

server {
    listen 443 ssl;
    server_name game.example.com;

    location = /room {
        limit_req zone=vgr_room burst=40 nodelay;
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /room/ {
        limit_req zone=vgr_room burst=40 nodelay;
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /game-ws {
        limit_conn vgr_conn 10;
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 120s;
    }
}
```

If both nginx and the application limiter are used, enable real client IP resolution in the
container (`server.forward-headers-strategy=native` for Tomcat), otherwise the application sees
the nginx address and limits all clients as one.

Notes:

- `limit_req_status 429` makes nginx return the same status as the application limiter
  (the default nginx status is `503`).
- `limit_req_zone $binary_remote_addr ... rate=20r/s` and `burst=40 nodelay` mirror the
  default application limits (`vgr.http.rate-limit-per-second=20`,
  `vgr.http.rate-limit-burst=40`); tune both layers together.
- `limit_conn` limits concurrent connections per IP, including WebSocket connections;
  adjust `limit_conn vgr_conn 10` to the expected number of tabs/devices per client.
- `proxy_read_timeout 120s` must be greater than the WebSocket idle timeout/PING interval
  (`vgr.websocket.idle-timeout=120s`), otherwise nginx can drop a healthy connection.
- The application's HTTP limiter uses `request.getRemoteAddr()` and skips `OPTIONS`
  preflight requests; nginx limits all methods, so keep CORS preflight in mind.
- The application returns `429` with `{"message":"Too many requests"}`; if nginx rejects first,
  configure its error page/body separately if the client needs a JSON error.

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

All interaction between players and the game happens via WebSocket.
To handle incoming/outgoing events, implement the `WebSocketEventProcessor` interface.

The state is split into two views:

- `PlayerStateDTO` — private state of one player (hand, private resources, available actions);
- `DisplayStateDTO` — public state for observers/screens (board, visible cards, counts).

Event names stay the same (`GAME_STATE`, `START_GAME`, `RESTART_GAME`), but the payload is chosen by the
recipient's role: players receive player data, observers receive display data.

`Consumer<JsonNode> sendMessage` — sends a message to the player from whom the incoming event came.

`Consumer<String> callbackEvent` — sends an outgoing event to ALL room members after the incoming event
has been processed.

Both callback paths send non-coalescable events by default. `GAME_STATE` and `UPDATE_MEMBERS` are coalesced
automatically. If a custom event represents only the latest state, inject `GameWebSocketHandler` and use
`sendEvent(code, event, true)`.

If you need to indicate an error to the player during incoming event data validation, throw any exception;
it will be handled by `WebSocketEventProcessor#processEventError(Exception e)`.

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
    public JsonNode createPlayerState(Game g, String userId) {
        GameImpl game = (GameImpl) g;
        PlayerStateDTO state = PlayerStateDTO.create(userId, game);
        return mapper.valueToTree(state);
    }

    @Override
    public JsonNode createDisplayState(Game g) {
        GameImpl game = (GameImpl) g;
        DisplayStateDTO state = DisplayStateDTO.create(game);
        return mapper.valueToTree(state);
    }

    @Override
    public void updateGameSettings(JsonNode settingsNode, Game g, Consumer<JsonNode> sendMessage, Consumer<String> callbackEvent) {
        GameImpl game = (GameImpl) g;
        GameSettings gameSettings = mapper.convertValue(settingsNode, GameSettings.class);
        game.setSettings(gameSettings);
    }

    @Override
    public JsonNode startPlayerGame(Game g, String userId) {
        return createPlayerState(g, userId);
    }

    @Override
    public JsonNode startDisplayGame(Game g) {
        return createDisplayState(g);
    }

    @Override
    public JsonNode restartPlayerGame(Game g, String userId) {
        return null;
    }

    @Override
    public JsonNode restartDisplayGame(Game g) {
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
    public ObjectNode outgoingPlayerEvent(String event, Game g, String userId) {
        GameImpl game = (GameImpl) g;

        return switch (event) {
            case NEXT_MOVE -> nextMoveForPlayer(game, userId);
            default -> null;
        };
    }

    @Override
    public ObjectNode outgoingDisplayEvent(String event, Game g) {
        GameImpl game = (GameImpl) g;

        return switch (event) {
            case NEXT_MOVE -> nextMoveForDisplay(game);
            default -> null;
        };
    }

    @Override
    public JsonNode processEventError(Exception e) {
        return mapper.getNodeFactory().textNode(e.getLocalizedMessage());
    }

    private ObjectNode nextMoveForPlayer(GameImpl game, String userId) {
        ObjectNode messageNode = mapper.createObjectNode();
        messageNode.put(EVENT_PROPERTY_NAME, GameWebSocketHandler.GAME_STATE);
        messageNode.set(DATA_PROPERTY_NAME, createPlayerState(game, userId));
        return messageNode;
    }

    private ObjectNode nextMoveForDisplay(GameImpl game) {
        ObjectNode messageNode = mapper.createObjectNode();
        messageNode.put(EVENT_PROPERTY_NAME, GameWebSocketHandler.GAME_STATE);
        messageNode.set(DATA_PROPERTY_NAME, createDisplayState(game));
        return messageNode;
    }

    private void passMoveChosen(GameImpl game, Consumer<JsonNode> sendMessage, Consumer<String> callbackEvent) {
        GameCard gameCard = gameService.passMoveChosen(game);
        callbackEvent.accept(NEXT_MOVE);
    }
}
```

#### Observers and public screens

- Observers are not removed when the game starts; they keep receiving public display state.
- If a game has no public screen, `createDisplayState` may return `null`; observers receive `GAME_STATE` without data and the client renders the configured `DisplayPlaceholder` or `displayComp` fallback.
- The room admin may stay an observer and keep admin controls (settings/start/restart).
- `incomingEvent` is called only for players; observers cannot control the game.
- `UPDATE_MEMBERS` contains all online members: players and observers. The client filters players for the lobby table.
- New members may join after the game has started; they always join as observers (`changeMemberPlayerStatus` is blocked after start).
- Multiple TVs should use separate observer accounts (each with its own token). The WebSocket session limit is a safety limit, not a replacement for separate accounts.

## Game client

You can find description of Vue Gaming Room Client [here](https://github.com/loolzaaa/vue-gaming-room).
