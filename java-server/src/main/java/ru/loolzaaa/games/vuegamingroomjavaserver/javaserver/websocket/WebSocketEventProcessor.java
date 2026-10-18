package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Game;

import java.util.function.Consumer;

public interface WebSocketEventProcessor {

    String EVENT_PROPERTY_NAME = "event";
    String DATA_PROPERTY_NAME = "data";

    /**
     * Приватное состояние игрока. Вызывается только для участников с ролью игрока.
     */
    JsonNode createPlayerState(Game g, String userId);

    /**
     * Публичное состояние для наблюдателей/экранов. Не должно содержать приватных данных игроков.
     */
    JsonNode createDisplayState(Game g);

    void updateGameSettings(JsonNode settingsNode,
                            Game g,
                            Consumer<JsonNode> sendMessage,
                            Consumer<String> callbackEvent);

    /**
     * Данные события START_GAME для игрока.
     */
    JsonNode startPlayerGame(Game g, String userId);

    /**
     * Данные события START_GAME для наблюдателя/экрана.
     */
    JsonNode startDisplayGame(Game g);

    /**
     * Данные события RESTART_GAME для игрока.
     */
    JsonNode restartPlayerGame(Game g, String userId);

    /**
     * Данные события RESTART_GAME для наблюдателя/экрана.
     */
    JsonNode restartDisplayGame(Game g);

    /**
     * Входящее игровое событие. Сервер вызывает метод только для игроков.
     */
    void incomingEvent(
            ObjectNode eventNode,
            Game g,
            String userId,
            Consumer<JsonNode> sendMessage,
            Consumer<String> callbackEvent);

    /**
     * Payload кастомного события для игрока.
     */
    ObjectNode outgoingPlayerEvent(String event, Game g, String userId);

    /**
     * Payload кастомного события для наблюдателя/экрана.
     */
    ObjectNode outgoingDisplayEvent(String event, Game g);

    JsonNode processEventError(Exception e);
}
