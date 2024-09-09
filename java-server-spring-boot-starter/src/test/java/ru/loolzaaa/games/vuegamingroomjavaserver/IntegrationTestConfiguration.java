package ru.loolzaaa.games.vuegamingroomjavaserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Game;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Member;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.GameService;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.WebSocketEventProcessor;

import java.util.List;
import java.util.function.Consumer;

@Configuration
public class IntegrationTestConfiguration {
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    GameService<GameImpl> gameService() {
        return new GameService<>() {
            @Override
            public String getGameName() {
                return new GameImpl().getName();
            }

            @Override
            public GameImpl createGameInstance() {
                return new GameImpl();
            }

            @Override
            public void startNewGame(Game g, List<Member> members) {

            }
        };
    }

    @Bean
    WebSocketEventProcessor webSocketEventProcessor() {
        return new WebSocketEventProcessor() {
            @Override
            public JsonNode createGameState(Game g, String userId) {
                return null;
            }

            @Override
            public void updateGameSettings(JsonNode settingsNode, Game g, Consumer<JsonNode> sendMessage, Consumer<String> callbackEvent) {

            }

            @Override
            public JsonNode startGame(Game g, String userId) {
                return null;
            }

            @Override
            public JsonNode restartGame(Game g, String userId) {
                return null;
            }

            @Override
            public void incomingEvent(ObjectNode eventNode, Game g, String userId, Consumer<JsonNode> sendMessage, Consumer<String> callbackEvent) {

            }

            @Override
            public ObjectNode outgoingEvent(String event, Game g, String userId) {
                return null;
            }

            @Override
            public JsonNode processEventError(Exception e) {
                return null;
            }
        };
    }

    public static class GameImpl implements Game {
        @Override
        public String getName() {
            return "TEST";
        }

        @Override
        public int getMinPlayers() {
            return 1;
        }

        @Override
        public int getMaxPlayers() {
            return 2;
        }
    }
}
