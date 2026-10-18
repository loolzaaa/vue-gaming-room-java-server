# Vue Gaming Room Java Server

Spring Boot server library for room-based web games: lobby, players, observers/public screens,
REST API, WebSocket state delivery and game lifecycle.

The server is the backend part of the [Vue Gaming Room](https://github.com/loolzaaa/vue-gaming-room)
ecosystem. A room hosts one game, players control it, and observers can stay connected as public
screens (for example, TVs) after the game starts.

## Features

- Room creation, joining and reconnecting.
- One game instance per room.
- Separate player and observer roles without extra role types.
- Private player state and public display state routed by role.
- Observers are not removed on game start; the room admin may stay an observer.
- Late observer joins while a game is running.
- One-time short-lived WebSocket tickets and per-member secrets.
- Authenticated REST mutations via `X-Member-Token`.
- Per-session async WebSocket outbound queues with state coalescing.
- WebSocket and HTTP rate limiting.
- Configurable room, cleanup, WebSocket and rate-limit settings.
- Spring Boot auto-configuration.

## Modules

| Module | Purpose |
|---|---|
| `java-server` | Core rooms, game contracts, REST controllers, WebSocket handler, security, settings |
| `java-server-spring-boot-starter` | Spring Boot auto-configuration and integration tests |

## Requirements

- JDK 21
- Maven 3.8.5+ (the bundled Maven Wrapper is recommended)

## Get the server

Clone the repository and install the modules locally:

```shell
git clone https://github.com/loolzaaa/vue-gaming-room-java-server.git
cd vue-gaming-room-java-server
./mvnw clean install
```

On Windows use `mvnw.cmd` instead of `./mvnw`.

Add the starter to the game server project:

```xml
<dependency>
  <groupId>ru.loolzaaa.games.vue-gaming-room-java-server</groupId>
  <artifactId>java-server-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Quick start

1. Create a Spring Boot application (JDK 21).
2. Add the starter dependency above.
3. Implement three contracts:
   - `Game` — game state and player limits;
   - `GameService<G extends Game>` — create and start the game;
   - `WebSocketEventProcessor` — player/display state and game events.

Minimal application configuration:

```yaml
vgr:
  websocket:
    allowed-origins:
      - https://game.example.com
```

The server registers REST endpoints under `/room`, game info at `/game/info`, and the WebSocket
endpoint at `/game-ws` by default. All settings are optional; see the guide for the full list.

## Documentation

- [Server usage guide](docs/GUIDE.md) — complete integration, REST/WS protocol, game implementation,
  configuration, security and deployment.
- [TODO and known limitations](docs/TODO.md).
- [Vue client](https://github.com/loolzaaa/vue-gaming-room) — matching client library.

## License

[MIT](LICENSE)
