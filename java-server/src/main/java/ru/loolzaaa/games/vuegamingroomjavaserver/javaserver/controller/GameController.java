package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.controller;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Game;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.GameService;

@RequiredArgsConstructor
@RestController
@RequestMapping("/game")
public class GameController {

    private final GameService<? extends Game> gameService;

    @GetMapping(path = "/info", produces = "application/json")
    public GameInfo getGameInfo() {
        return new GameInfo(gameService.getGameName());
    }

    @Getter
    @AllArgsConstructor
    public static class GameInfo {
        String gameName;
    }
}
