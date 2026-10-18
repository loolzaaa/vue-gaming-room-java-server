# Server Usage Guide

This guide covers the full integration of `vue-gaming-room-java-server` into a Spring Boot game
project: game contracts, player/display state, REST and WebSocket protocols, configuration,
security and deployment.

## 1. Architecture

```text
Browser / TV
   │  REST (rooms, members, tickets)
   │  WebSocket (tickets, game events, state)
   ▼
java-server-spring-boot-starter
   │
   ├── RoomController / GameController
   ├── GameWebSocketHandler
   ├── RoomService<G>
   ├── Room<G> + gameLock
   └── ServerSettings / VgrProperties
          │
          ▼
   Your GameService<G> and WebSocketEventProcessor
```

### Roles

There are three independent flags on a room member:

- `isPlayer` — controls the game;
- `isSpectator` — receives public display state (observer/screen);
- `isAdmin` — can update settings, start and restart the game.

There are no separate role types. A spectator can also be an admin. Players and spectators are both
members of the room and both keep their place after the game starts.

### Room lifecycle

1. A member creates a room and becomes its admin and spectator.
2. Other members join as spectators.
3. Spectators can become players before the game starts.
4. The admin starts the game when players are ready.
5. Players receive private state; spectators receive public display state.
6. Spectators are not removed. New members may join as spectators during the game.
7. The admin may restart the game. Players and spectators receive role-specific restart data.

## 2. Installation

Requirements: JDK 21, Maven 3.8.5+.

```shell
git clone https://github.com/loolzaaa/vue-gaming-room-java-server.git
cd vue-gaming-room-java-server
./mvnw clean install
```

Add the starter to the game project:

```xml
<dependency>
  <groupId>ru.loolzaaa.games.vue-gaming-room-java-server</groupId>
  <artifactId>java-server-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

The starter brings the web and WebSocket stack, registers the controllers, the WebSocket handler,
the cleanup task and the HTTP rate-limit filter.

## 3. Implement the game model

### Game

```java
@Getter
@Setter
public class GameImpl implements Game {

    public static final String GAME_NAME = "test-game";
    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 4;

    private GameSettings settings = new GameSettings();
    private List<GameCard> mainDeck = new ArrayList<>();
    private List<GameCard> discardDeck = new ArrayList<>();
    private List<Player> players = new ArrayList<>();

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

### Player

Extend `AbstractPlayer` to keep the associated room member:

```java
@Getter
@Setter
public class Player extends AbstractPlayer {

    private final int number;
    private List<GameCard> cardsInHand = new ArrayList<>();

    public Player(int number, Member member) {
        super(member);
        this.number = number;
    }
}
```

### Settings (optional)

```java
@Getter
@Setter
public class GameSettings {
    private boolean allowGameOption = false;
    private int answerTimeMs = 10_000;
}
```

## 4. Implement `GameService<G extends Game>`

The game service creates game instances and starts a new game. The type parameter is the concrete
game type, so implementations do not need casts.

```java
@Service
@RequiredArgsConstructor
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
    public void startNewGame(GameImpl game, List<Member> members) {
        game.setPlayers(members.stream()
                .map(member -> new Player(members.indexOf(member), member))
                .toList());
        game.setMainDeck(createDeck());
    }

    public GameCard passMoveChosen(GameImpl game) {
        GameCard card = game.getMainDeck().removeFirst();
        game.getDiscardDeck().add(card);
        return card;
    }
}
```

`startNewGame` is called both for the initial start and for restart. Initialize or reset the game
state there.

### Concurrency

All calls to `GameService` and `WebSocketEventProcessor` are serialized per room by a dedicated
`gameLock`. Game implementations do not need to synchronize their own state. Avoid long blocking
operations inside callbacks because they hold the per-room game lock.

## 5. Implement `WebSocketEventProcessor`

The processor provides two views:

- `PlayerStateDTO` — private state of one player (own hand, private resources, available actions);
- `DisplayStateDTO` — public state for spectators/screens (board, visible cards, counts).

Event names stay the same for both roles (`GAME_STATE`, `START_GAME`, `RESTART_GAME`); the server
chooses the payload by the recipient's role.

```java
@Component
@RequiredArgsConstructor
public class GameEventProcessorImpl implements WebSocketEventProcessor {

    public static final String NEXT_MOVE = "NEXT_MOVE";
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
    public void updateGameSettings(JsonNode settingsNode,
                                   Game g,
                                   Consumer<JsonNode> sendMessage,
                                   Consumer<String> callbackEvent) {
        GameImpl game = (GameImpl) g;
        game.setSettings(mapper.convertValue(settingsNode, GameSettings.class));
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
        return createPlayerState(g, userId);
    }

    @Override
    public JsonNode restartDisplayGame(Game g) {
        return createDisplayState(g);
    }

    @Override
    public void incomingEvent(ObjectNode eventNode,
                              Game g,
                              String userId,
                              Consumer<JsonNode> sendMessage,
                              Consumer<String> callbackEvent) {
        GameImpl game = (GameImpl) g;
        String event = eventNode.get(EVENT_PROPERTY_NAME).asText();
        switch (event) {
            case PASS_MOVE_CHOSEN -> {
                gameService.passMoveChosen(game);
                callbackEvent.accept(NEXT_MOVE);
            }
        }
    }

    @Override
    public ObjectNode outgoingPlayerEvent(String event, Game g, String userId) {
        GameImpl game = (GameImpl) g;
        return switch (event) {
            case NEXT_MOVE -> message(GameWebSocketHandler.GAME_STATE, createPlayerState(game, userId));
            default -> null;
        };
    }

    @Override
    public ObjectNode outgoingDisplayEvent(String event, Game g) {
        GameImpl game = (GameImpl) g;
        return switch (event) {
            case NEXT_MOVE -> message(GameWebSocketHandler.GAME_STATE, createDisplayState(game));
            default -> null;
        };
    }

    @Override
    public JsonNode processEventError(Exception e) {
        return mapper.getNodeFactory().textNode(e.getLocalizedMessage());
    }

    private ObjectNode message(String event, JsonNode data) {
        ObjectNode node = mapper.createObjectNode();
        node.put(EVENT_PROPERTY_NAME, event);
        if (data != null) {
            node.set(DATA_PROPERTY_NAME, data);
        }
        return node;
    }
}
```

### Optional public screen

If the game has no public screen, omit `createDisplayState` (the default returns `null`). The client
renders its configured `DisplayPlaceholder` or `displayComp` fallback.

### Callback semantics

- `sendMessage` — sends a message only to the player whose incoming event is being processed.
- `callbackEvent` — broadcasts an outgoing event to all room members after the incoming event.
- Both callbacks are non-coalescable by default.
- `GAME_STATE` and `UPDATE_MEMBERS` are coalesced automatically.
- A custom state-like event can be coalesced by injecting `GameWebSocketHandler` and calling
  `sendEvent(code, event, true)`.
- Exceptions thrown by the processor are passed to `processEventError(Exception)` and returned to
  the player as an `ERROR` event.

### Incoming events

`incomingEvent` is called only for members with `isPlayer == true`. Spectators cannot control the
game; their game events are rejected with an `ERROR`.

## 6. REST API

All mutating requests require the `X-Member-Token` header, except `POST /room/create`.
`POST /room/join/{code}` requires it only when an existing member with the same `userId` reconnects.

| Method | Path | Description |
|---|---|---|
| `GET` | `/game/info` | `{gameName, wsEndpoint}` |
| `POST` | `/room/create?nickname=...&userId=...` | Create a room; returns `RoomDTO` |
| `POST` | `/room/join/{code}?nickname=...&userId=...` | Join or reconnect; returns `RoomDTO` |
| `POST` | `/room/{code}/{userId}/nickname?newNickname=...` | Change nickname |
| `POST` | `/room/{code}/{userId}/color?newColor=...` | Change color (`#RRGGBB`) |
| `POST` | `/room/{code}/{userId}/player?newStatus=...` | Become player/spectator (lobby only) |
| `POST` | `/room/{code}/{userId}/ready?newStatus=...` | Change ready status |
| `POST` | `/room/{code}/start?userId=...&forceStart=...` | Start game (admin) |
| `POST` | `/room/{code}/restart?userId=...` | Restart game (admin) |
| `POST` | `/room/{code}/ws-ticket?userId=...` | Issue a one-time WebSocket ticket |
| `GET` | `/room/{code}/spectators?userId=...` | List spectators |

`RoomDTO`:

```json
{
  "code": "ABCD",
  "wsToken": "per-member-secret",
  "gameStarted": false,
  "userId": "member-user-id"
}
```

`wsToken` is the member secret used for `X-Member-Token` and for issuing WebSocket tickets.

Errors use JSON:

```json
{ "message": "The room doesn't exist" }
```

HTTP rate limiting returns `429` with the same envelope. Validation errors are `400`.

## 7. WebSocket protocol

### Handshake

1. Obtain a ticket:

```http
POST /room/ABCD/ws-ticket?userId=member-id
X-Member-Token: member-secret
```

```json
{ "ticket": "one-time-ticket", "expiresInMs": 30000 }
```

2. Connect:

```text
ws(s)://host/game-ws?ticket=one-time-ticket
```

Tickets are single-use and expire after `vgr.websocket.ticket-ttl`. The server checks that the user
is a member of the room. A session that is not bound to a member is rejected.

### Envelope

```json
{ "event": "EVENT_NAME", "data": { } }
```

### Server events

| Event | Recipient | Description |
|---|---|---|
| `UPDATE_MEMBERS` | all | All online members, roles, readiness, spectator count |
| `GAME_STATE` | role-specific | `PlayerStateDTO` for players, `DisplayStateDTO` for spectators |
| `START_GAME` | role-specific | Start data for players or display screens |
| `RESTART_GAME` | role-specific | Restart data for players or display screens |
| `ERROR` | sender | Validation/processing error |
| `PONG` | sender | Reply to `PING` |

`UPDATE_MEMBERS.data`:

```json
{
  "spectatorsCount": 2,
  "members": [
    {
      "nickname": "player",
      "color": "#aabbcc",
      "admin": false,
      "player": true,
      "spectator": false,
      "ready": true
    }
  ]
}
```

The client should filter `player == true` for the lobby player table and use the full list to find
the current member and their role.

### Client events

| Event | Description |
|---|---|
| `PING` | Keep-alive; server replies `PONG` |
| `UPDATE_SETTINGS` | Update game settings (admin) |
| custom | Passed to `incomingEvent` for players |

Custom event example:

```json
{
  "event": "PASS_MOVE_CHOSEN",
  "data": {
    "code": "ABCD",
    "card": "hearts-7"
  }
}
```

`data.code` must match the room of the session. Missing or malformed data produces `ERROR` and does
not close the connection. Excessive messages or queue overflow close the session.

## 8. Observers, screens and TVs

- Spectators stay in the room after the game starts.
- The admin may remain a spectator and keep admin controls.
- New members may join after the start; they always join as spectators and cannot become players.
- A game without a public screen can return `null` display data; the client shows a configurable
  placeholder.
- For multiple TVs use separate spectator accounts, each with its own token. The per-user session
  limit is a safety limit, not a replacement for separate accounts.

## 9. Configuration

All properties have defaults and can be set in `application.yml` or `application.properties`.

| Property | Default | Description |
|---|---|---|
| `vgr.room.max-members` | `200` | Maximum room members |
| `vgr.room.inactive-timeout` | `60m` | Inactive room cleanup timeout |
| `vgr.room.clean-initial-delay-ms` | `10000` | Delay before the first cleanup run |
| `vgr.room.clean-interval-ms` | `60000` | Cleanup interval |
| `vgr.room.max-nickname-length` | `32` | Maximum nickname length in code points |
| `vgr.websocket.endpoint-path` | `/game-ws` | WebSocket endpoint path |
| `vgr.websocket.allowed-origins` | `*` | Allowed origin patterns |
| `vgr.websocket.max-message-size` | `64KB` | Maximum incoming message size |
| `vgr.websocket.idle-timeout` | `120s` | Native WebSocket idle timeout; `0` disables |
| `vgr.websocket.send-time-limit` | `10s` | Maximum send time to one client |
| `vgr.websocket.send-buffer-size` | `512KB` | Per-session send buffer limit |
| `vgr.websocket.send-queue-capacity` | `64` | Async outbound queue capacity |
| `vgr.websocket.max-sessions-per-user` | `3` | Max active sessions per user |
| `vgr.websocket.rate-limit-per-second` | `30` | WebSocket messages/second per session |
| `vgr.websocket.rate-limit-burst` | `60` | WebSocket message burst |
| `vgr.websocket.ticket-ttl` | `30s` | One-time ticket lifetime |
| `vgr.http.rate-limit-per-second` | `20` | HTTP requests/second per address for `/room/**` |
| `vgr.http.rate-limit-burst` | `40` | HTTP request burst |

`GET /game/info` returns the configured endpoint path as `wsEndpoint`, so the client can discover a
custom path.

## 10. Security

- Every member has a unique secret (`RoomDTO.wsToken`), never exposed in member payloads.
- REST mutations require `X-Member-Token`.
- WebSocket handshake accepts only one-time tickets; legacy `?token=...` is rejected.
- Membership is checked during the handshake.
- Active sessions per user are limited; the oldest are closed.
- WebSocket messages are rate-limited per session; HTTP requests are rate-limited per address.
- Set explicit `vgr.websocket.allowed-origins` and use `wss` in production.
- The token is stored client-side. For games with payments, accounts or valuable inventory, use
  SSO/HttpOnly cookies, a strict CSP and consider token rotation (see TODO).

### Container requirement

The idle timeout is applied through the native JSR-356 API
(`jakarta.websocket.Session#setMaxIdleTimeout`). The servlet container must support native WebSocket
idle timeouts (Tomcat does). There is intentionally no fallback sweeper.

## 11. Deployment behind nginx

The application limits `/room/**` per client address; an edge limit is still recommended. Example:

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

- `limit_req_status 429` matches the application response.
- Keep nginx `rate`/`burst` aligned with `vgr.http.*`.
- `proxy_read_timeout` must exceed the WebSocket idle timeout.
- If both limiters are active, set `server.forward-headers-strategy=native` for Tomcat so the
  application sees real client addresses.

## 12. Validation rules

- Nicknames are trimmed, NFC-normalized, limited by `vgr.room.max-nickname-length`, cannot contain
  control characters, and are unique case-insensitively within a room.
- Colors must match `#RRGGBB`; the server normalizes them to lowercase.
- Room codes are 4 uppercase Latin letters.
- User ids are non-blank, up to 64 characters, without `:` or control characters.

## 13. Testing your game

The server core is covered by unit and integration tests. When adding a game:

1. Unit-test `GameService` state transitions.
2. Test `WebSocketEventProcessor` methods with a real `ObjectMapper`.
3. Test player and display payloads separately.
4. Use `GameWebSocketHandler` or the REST endpoints for an integration test.
5. Keep game callbacks fast: they hold the per-room game lock.

See [TODO](TODO.md) for planned improvements and known limitations.
