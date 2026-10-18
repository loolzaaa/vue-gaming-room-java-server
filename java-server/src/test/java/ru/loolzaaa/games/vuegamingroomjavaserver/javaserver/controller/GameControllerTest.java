package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.controller;

import org.junit.jupiter.api.Test;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.settings.ServerSettings;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.support.TestFixtures.TestGameService;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GameControllerTest {

    private final TestGameService gameService = new TestGameService("test-game", 1, 2);

    @Test
    void shouldReturnGameNameFromGameService() {
        GameController gameController = new GameController(gameService);

        GameController.GameInfo gameInfo = gameController.getGameInfo();

        assertThat(gameInfo.getGameName()).isEqualTo("test-game");
        assertThat(gameInfo.getWsEndpoint()).isEqualTo("/game-ws");
    }

    @Test
    void shouldReturnConfiguredWebSocketEndpoint() {
        ServerSettings settings = new ServerSettings(
                ServerSettings.RoomSettings.defaults(),
                new ServerSettings.WebSocketSettings(
                        "/custom-ws", List.of("*"), 1024,
                        Duration.ofSeconds(30), Duration.ofSeconds(5), 2048,
                        3, 30, 60, Duration.ofSeconds(30)));
        GameController gameController = new GameController(gameService, settings);

        assertThat(gameController.getGameInfo().getWsEndpoint()).isEqualTo("/custom-ws");
    }
}
