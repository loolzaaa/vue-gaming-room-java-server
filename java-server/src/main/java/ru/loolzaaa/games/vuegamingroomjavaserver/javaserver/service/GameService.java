package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service;


import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Game;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Member;

import java.util.List;

public interface GameService<G extends Game> {
    String getGameName();
    G createGameInstance();
    void startNewGame(Game g, List<Member> members);
}
