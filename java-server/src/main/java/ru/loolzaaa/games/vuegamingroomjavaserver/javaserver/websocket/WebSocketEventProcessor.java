package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Game;

import java.util.function.Consumer;

public interface WebSocketEventProcessor {

    String EVENT_PROPERTY_NAME = "event";
    String DATA_PROPERTY_NAME = "data";

    JsonNode createGameState(Game g, String userId);

    void updateGameSettings(JsonNode settingsNode,
                            Game g,
                            Consumer<JsonNode> sendMessage,
                            Consumer<String> callbackEvent);

    JsonNode startGame(Game g, String userId);

    JsonNode restartGame(Game g, String userId);

    void incomingEvent(
            ObjectNode eventNode,
            Game g,
            String userId,
            Consumer<JsonNode> sendMessage,
            Consumer<String> callbackEvent);

    ObjectNode outgoingEvent(String event, Game g, String userId);

    JsonNode processEventError(Exception e);
}
