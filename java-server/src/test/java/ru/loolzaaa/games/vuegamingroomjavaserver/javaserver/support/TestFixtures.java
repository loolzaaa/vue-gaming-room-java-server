package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.support;

import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Game;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Member;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.GameService;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Общие тестовые реализации игры и игрового сервиса.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static class TestGame implements Game {

        private final String name;
        private final int minPlayers;
        private final int maxPlayers;

        public TestGame() {
            this("TEST", 1, 2);
        }

        public TestGame(String name, int minPlayers, int maxPlayers) {
            this.name = name;
            this.minPlayers = minPlayers;
            this.maxPlayers = maxPlayers;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getMinPlayers() {
            return minPlayers;
        }

        @Override
        public int getMaxPlayers() {
            return maxPlayers;
        }
    }

    public static class TestGameService implements GameService<TestGame> {

        private final String gameName;
        private final int minPlayers;
        private final int maxPlayers;
        private final List<List<Member>> startedGames = new CopyOnWriteArrayList<>();

        private RuntimeException startFailure;
        private int startCalls;

        public TestGameService() {
            this("TEST", 1, 2);
        }

        public TestGameService(int minPlayers, int maxPlayers) {
            this("TEST", minPlayers, maxPlayers);
        }

        public TestGameService(String gameName, int minPlayers, int maxPlayers) {
            this.gameName = gameName;
            this.minPlayers = minPlayers;
            this.maxPlayers = maxPlayers;
        }

        @Override
        public String getGameName() {
            return gameName;
        }

        @Override
        public TestGame createGameInstance() {
            return new TestGame(gameName, minPlayers, maxPlayers);
        }

        @Override
        public void startNewGame(Game g, List<Member> members) {
            startCalls++;
            if (startFailure != null) {
                throw startFailure;
            }
            startedGames.add(List.copyOf(members));
        }

        public List<List<Member>> getStartedGames() {
            return startedGames;
        }

        public int getStartCalls() {
            return startCalls;
        }

        public void failNextStart(RuntimeException failure) {
            this.startFailure = failure;
        }
    }
}
