package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.controller;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Game;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.GameService;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.settings.ServerSettings;

@RestController
@RequestMapping("/game")
public class GameController {

    private final GameService<? extends Game> gameService;
    private final String wsEndpoint;

    public GameController(GameService<? extends Game> gameService) {
        this(gameService, ServerSettings.defaults());
    }

    public GameController(GameService<? extends Game> gameService, ServerSettings settings) {
        this.gameService = gameService;
        this.wsEndpoint = settings.webSocket().endpointPath();
    }

    @GetMapping(path = "/info", produces = "application/json")
    public GameInfo getGameInfo() {
        return new GameInfo(gameService.getGameName(), wsEndpoint);
    }

    @Getter
    @AllArgsConstructor
    public static class GameInfo {
        String gameName;
        String wsEndpoint;
    }
}
