package ru.loolzaaa.games.vuegamingroomjavaserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
            public JsonNode createPlayerState(Game g, String userId) {
                return JsonNodeFactory.instance.objectNode()
                        .put("view", "player")
                        .put("userId", userId);
            }

            @Override
            public JsonNode createDisplayState(Game g) {
                return JsonNodeFactory.instance.objectNode().put("view", "display");
            }

            @Override
            public void updateGameSettings(JsonNode settingsNode, Game g, Consumer<JsonNode> sendMessage, Consumer<String> callbackEvent) {

            }

            @Override
            public JsonNode startPlayerGame(Game g, String userId) {
                return JsonNodeFactory.instance.objectNode()
                        .put("view", "player")
                        .put("started", true)
                        .put("userId", userId);
            }

            @Override
            public JsonNode startDisplayGame(Game g) {
                return JsonNodeFactory.instance.objectNode()
                        .put("view", "display")
                        .put("started", true);
            }

            @Override
            public JsonNode restartPlayerGame(Game g, String userId) {
                return JsonNodeFactory.instance.objectNode()
                        .put("view", "player")
                        .put("restarted", true)
                        .put("userId", userId);
            }

            @Override
            public JsonNode restartDisplayGame(Game g) {
                return JsonNodeFactory.instance.objectNode()
                        .put("view", "display")
                        .put("restarted", true);
            }

            @Override
            public void incomingEvent(ObjectNode eventNode, Game g, String userId, Consumer<JsonNode> sendMessage, Consumer<String> callbackEvent) {

            }

            @Override
            public ObjectNode outgoingPlayerEvent(String event, Game g, String userId) {
                return JsonNodeFactory.instance.objectNode()
                        .put("view", "player")
                        .put("event", event)
                        .put("userId", userId);
            }

            @Override
            public ObjectNode outgoingDisplayEvent(String event, Game g) {
                return JsonNodeFactory.instance.objectNode()
                        .put("view", "display")
                        .put("event", event);
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
