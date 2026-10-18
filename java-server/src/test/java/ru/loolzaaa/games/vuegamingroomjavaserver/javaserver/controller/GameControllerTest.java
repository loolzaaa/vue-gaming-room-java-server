package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.controller;

import org.junit.jupiter.api.Test;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.support.TestFixtures.TestGameService;

import static org.assertj.core.api.Assertions.assertThat;

class GameControllerTest {

    private final TestGameService gameService = new TestGameService("test-game", 1, 2);
    private final GameController gameController = new GameController(gameService);

    @Test
    void shouldReturnGameNameFromGameService() {
        GameController.GameInfo gameInfo = gameController.getGameInfo();

        assertThat(gameInfo.getGameName()).isEqualTo("test-game");
    }
}
