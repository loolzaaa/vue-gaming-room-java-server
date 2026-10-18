# AGENTS.md — Server Repository

Instructions for AI agents working on `vue-gaming-room-java-server`.

## Project purpose

Spring Boot server library for room-based web games. The repository contains two Maven modules:

| Module | Responsibility |
|---|---|
| `java-server` | Core rooms, game contracts, REST controllers, WebSocket handler, security, settings |
| `java-server-spring-boot-starter` | Spring Boot auto-configuration, `VgrProperties`, integration tests |

Current version: `1.0.0`. Do not bump the version unless explicitly requested.

## Build and test

Requirements: JDK 21, Maven 3.8.5+.

Windows:

```powershell
.\mvnw.cmd -B clean test
```

Linux/macOS:

```shell
./mvnw clean test
```

The build also enforces Java/Maven versions via `maven-enforcer-plugin`. Do not disable it.

Test suites:

- `java-server/src/test/java` — core unit tests.
- `java-server-spring-boot-starter/src/test/java` — REST/WS integration tests and auto-config
  properties tests.

Approximate suite size: 133 tests. Keep the full suite green.

## Project layout

```text
java-server/src/main/java/.../javaserver/
  controller/      RoomController, GameController
  dto/             RoomDTO
  exception/       RoomException
  pojo/            Game, AbstractPlayer, Member, Room
  security/        TokenBucket
  service/         GameService, RoomService
  settings/        ServerSettings
  task/            RoomCleanTask
  websocket/       GameWebSocketHandler, WebSocketEventProcessor,
                   TokenHandshakeInterceptor, outbound queue/sender

java-server-spring-boot-starter/src/main/java/.../
  autoconfigure/   JavaServerAutoConfiguration, VgrProperties
  security/        HttpRateLimitFilter
```

## Architectural invariants

Do not break these rules without an explicit design discussion.

### Room and game locking

- Membership/room state is protected by `synchronized (room)`.
- Game state is protected by `room.getGameLock()`.
- Lock order is **`gameLock → room monitor`**. Never acquire the room monitor first and then the
  game lock.
- `RoomService.startGame`/`restartGame` acquire `gameLock` before `room`.
- `GameWebSocketHandler` serializes `incomingEvent`, `updateGameSettings`, state generation and
  start/restart/outgoing payload generation under `gameLock`.
- Game callbacks must remain fast. Do not perform blocking I/O while holding the game lock.

### WebSocket sending

- Outbound messages are asynchronous: `OutboundQueue` + `SessionOutboundSender` (virtual thread).
- Never add blocking network sends to broadcast/game threads.
- `GAME_STATE` and `UPDATE_MEMBERS` are coalescable. Custom state-like events can use
  `sendEvent(code, event, true)`; custom events default to non-coalescable.
- Queue overflow closes the session. Do not silently drop critical events.
- `GameWebSocketHandler` implements `DisposableBean`; keep sender shutdown working.

### Roles and state

- Roles are flags only: `isPlayer`, `isSpectator`, `isAdmin`. Do not introduce new role types.
- Players receive player state; spectators receive public display state.
- `WebSocketEventProcessor` has separate player/display methods. Display methods are optional
  (default `null`); the client has a configurable fallback.
- Observers are not removed on game start and may remain admin.
- New members may join after start only as spectators.
- `incomingEvent` is called only for players; spectators must not control the game.
- `UPDATE_MEMBERS` contains all online members; clients filter players themselves.

### Security

- Never reintroduce the shared room token or the legacy `?token=...` handshake.
- REST mutations require `X-Member-Token`; WebSocket handshakes use one-time tickets from
  `POST /room/{code}/ws-ticket`.
- Membership is validated during the handshake.
- Keep cookie/token values out of logs.
- Rate limits: WS per session, HTTP per address for `/room/**`.
- Default allowed origins `*` is for development only; keep it configurable.

### Configuration

- Core code uses plain `ServerSettings`; the starter binds `VgrProperties` (`vgr.*`).
- Add new settings to both, with validation and tests.
- Do not hardcode values that are already configurable.
- `RoomCleanTask` scheduling uses `vgr.room.clean-*-ms` placeholders.

### Dependencies and logging

- Use SLF4J (`LoggerFactory`), not Log4j directly.
- Versions are managed by the Spring Boot BOM in the parent POM.
- `lombok` is `provided`.
- Do not add dependencies without updating the BOM/plugin configuration and tests.

## How to extend

### Add a game-facing API

1. Change `GameService`/`WebSocketEventProcessor` in `java-server`.
2. Update `IntegrationTestConfiguration` and `TestFixtures`.
3. Update the README examples and `docs/GUIDE.md`.
4. Add unit tests for the new contract and integration tests for the WebSocket flow.
5. Bump the version only if requested; the project currently targets a clean `1.0.0`.

### Add a setting

1. Add a field to `ServerSettings` and validate it in the record constructor.
2. Add a field/getter/setter to `VgrProperties` and map it in `toServerSettings()`.
3. Pass it to the consuming component/constructor.
4. Add tests in `ServerSettingsTest` and `VgrPropertiesTest`.
5. Document it in `docs/GUIDE.md` and README if user-facing.

### Add a REST endpoint

- Put auth/member-token validation in `RoomController` before calling `RoomService`.
- Return `RoomException` for user-facing errors; it is converted to JSON `400`.
- Add an integration test using MockMvc with `X-Member-Token`.
- Do not leak member tokens or user ids in payloads unnecessarily.

### Add a WebSocket event

- Add outgoing payload methods to both player and display sides if the event is role-sensitive.
- Use coalescing only for state-like events.
- Add handler tests with `Awaitility` because sending is asynchronous.
- Update `docs/GUIDE.md` event tables.

## Testing conventions

- JUnit 5, Mockito, AssertJ, Awaitility (from `spring-boot-starter-test`).
- Async WebSocket assertions must wait for delivery; never assert immediately after enqueueing.
- Core tests should be plain JUnit (no Spring context) unless Spring wiring is the subject.
- Integration tests use `@SpringJUnitWebConfig` with `JavaServerAutoConfiguration` and
  `IntegrationTestConfiguration`.
- Keep tests deterministic; do not use `Thread.sleep` for synchronization where a latch/Awaitility
  works.

## Documentation

- `README.md` — short project overview and quick start.
- `docs/GUIDE.md` — full server integration/protocol/configuration/security guide.
- `docs/TODO.md` — known limitations and roadmap.
- Update docs together with behavior changes.

## Common pitfalls

- Inverting the lock order (`room → gameLock`) can deadlock.
- Blocking inside `incomingEvent`/state generation blocks all game actions in that room.
- Sending directly from `sendEvent` bypasses the async queue and can block broadcasts.
- Forgetting the `X-Member-Token` header in new REST endpoints breaks authentication.
- Adding a display method with no fallback: return `null` and let the client render its placeholder.
- Reintroducing the old `?token=` handshake or the shared room token.

## Git conventions

- Keep commits focused by area; include tests with behavior changes.
- Do not rewrite published history.
- Do not commit generated build output (`target/`).
