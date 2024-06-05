package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.exception.RoomException;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Game;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Member;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Room;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.RoomService;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.WebSocketEventProcessor.DATA_PROPERTY_NAME;
import static ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.WebSocketEventProcessor.EVENT_PROPERTY_NAME;


@RequiredArgsConstructor
public class GameWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LogManager.getLogger(GameWebSocketHandler.class);

    public static final String UPDATE_MEMBERS = "UPDATE_MEMBERS";
    public static final String GAME_STATE = "GAME_STATE";
    public static final String UPDATE_SETTINGS = "UPDATE_SETTINGS";
    public static final String START_GAME = "START_GAME";
    public static final String RESTART_GAME = "RESTART_GAME";

    private static final String ERROR = "ERROR";
    private static final String PING = "PING";
    private static final String PONG = "PONG";

    private final Map<String, String> webSocketTokenRoomCodeMap = new ConcurrentHashMap<>();
    private final Map<String, RoomWebSocketSessionsHolder> roomSessionsMap = new ConcurrentHashMap<>();
    private final Lock roomSessionsLock = new ReentrantLock();

    private final Map<String, Lock> sessionLockMap = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;

    private final RoomService roomService;

    private final WebSocketEventProcessor webSocketEventProcessor;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = (String) session.getAttributes().get("token");
        String[] tokenParts = token.split(":");
        String userId = tokenParts[0];
        String webSocketToken = tokenParts[1];

        String code = webSocketTokenRoomCodeMap.get(webSocketToken);
        if (code == null) {
            session.close(CloseStatus.BAD_DATA);
            log.warn("Incorrect websocket token {} for user with id {}", webSocketToken, userId);
            return;
        }

        session.getAttributes().put("code", code);
        List<PlayerWebSocketSession> playerWebSocketSessions = roomSessionsMap.get(code).playerWebSocketSessions;
        PlayerWebSocketSession playerWebSocketSession = new PlayerWebSocketSession(userId, session);
        playerWebSocketSessions.add(playerWebSocketSession);
        sessionLockMap.put(session.getId(), playerWebSocketSession.lock);
        log.info("Websocket connection established for user {} in room {}", userId, code);

        // Обновить состояние всех членов комнаты из-за вновь подключившегося
        sendEvent(code, UPDATE_MEMBERS);

        // Отправка текущего состояния игры (если начата) для вновь подключившегося
        Room<? extends Game> room = roomService.getRooms().get(code);
        if (room.isGameStarted()) {
            ObjectNode gameState = mapper.createObjectNode();
            gameState.put(EVENT_PROPERTY_NAME, GAME_STATE);
            JsonNode gameStateData = webSocketEventProcessor.createGameState(room.getGame(), userId);
            if (gameStateData != null) {
                gameState.set(DATA_PROPERTY_NAME, gameStateData);
            }
            sendTextMessage(session, playerWebSocketSession.lock, gameState);
            log.debug("Send game state for user id {} in room {}: {}", userId, code, gameState);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.debug("Incoming message: {}", message);

        Lock sessionLock = sessionLockMap.get(session.getId());

        ObjectNode messageNode = (ObjectNode) mapper.readTree(message.getPayload());
        String event = messageNode.get(EVENT_PROPERTY_NAME).asText();

        if (event.equals(PING)) {
            messageNode.put(EVENT_PROPERTY_NAME, PONG);
            sendTextMessage(session, sessionLock, messageNode);
            return;
        }

        String code = messageNode.get(DATA_PROPERTY_NAME).get("code").asText();
        Room<?> room = roomService.getRooms().get(code);
        Game game = room.getGame();

        RoomWebSocketSessionsHolder roomWebSocketSessionsHolder = roomSessionsMap.get(code);
        List<PlayerWebSocketSession> playerWebSocketSessions = roomWebSocketSessionsHolder.playerWebSocketSessions;
        String userId = playerWebSocketSessions.stream()
                .filter(playerWebSocketSession -> playerWebSocketSession.webSocketSession.getId().equals(session.getId()))
                .map(playerWebSocketSession -> playerWebSocketSession.userId)
                .findFirst()
                .orElseThrow();

        Consumer<JsonNode> sendMessage = payload -> sendTextMessage(session, sessionLock, payload);
        Consumer<String> callbackEvent = e -> sendEvent(code, e);

        try {
            if (event.equals(UPDATE_SETTINGS)) {
                Member member = room.getMemberByUserId(userId);
                if (member == null || !member.isAdmin()) {
                    throw new RoomException("The member doesn't exist or it is not an admin");
                }
                webSocketEventProcessor.updateGameSettings(messageNode.get(DATA_PROPERTY_NAME), game, sendMessage, callbackEvent);
            } else {
                webSocketEventProcessor.incomingEvent(messageNode, game, userId, sendMessage, callbackEvent);
            }
        } catch (Exception e) {
            ObjectNode errorNode = mapper.createObjectNode();
            errorNode.put(EVENT_PROPERTY_NAME, ERROR);
            errorNode.set(DATA_PROPERTY_NAME, webSocketEventProcessor.processEventError(e));
            sendMessage.accept(errorNode);
        }

        room.setLastActivity(LocalDateTime.now());
    }

    public void sendEvent(String code, String event) {
        RoomWebSocketSessionsHolder roomWebSocketSessionsHolder = roomSessionsMap.get(code);
        if (roomWebSocketSessionsHolder == null) {
            return;
        }

        List<PlayerWebSocketSession> playerWebSocketSessions = roomWebSocketSessionsHolder.playerWebSocketSessions;
        Room<?> room = roomService.getRooms().get(code);
        Game game = room.getGame();

        switch (event) {
            case UPDATE_MEMBERS -> {
                ObjectNode eventNode = mapper.createObjectNode();
                eventNode.put(EVENT_PROPERTY_NAME, UPDATE_MEMBERS);
                List<String> onlinePlayers = playerWebSocketSessions.stream()
                        .map(playerSession -> playerSession.userId)
                        .toList();
                long spectatorsCount = room.getMembers().stream()
                        .filter(m -> m.isSpectator() && onlinePlayers.contains(m.getUserId()))
                        .count();
                List<Member> members = room.getMembers().stream()
                        .filter(m -> m.isPlayer() && onlinePlayers.contains(m.getUserId()))
                        .toList();
                ObjectNode dataNode = eventNode.putObject(DATA_PROPERTY_NAME);
                dataNode.put("spectatorsCount", spectatorsCount);
                dataNode.set("members", mapper.valueToTree(members));
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    sendTextMessage(playerWebSocketSession, eventNode);
                }
            }
            case GAME_STATE -> {
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    ObjectNode eventNode = mapper.createObjectNode();
                    eventNode.put(EVENT_PROPERTY_NAME, GAME_STATE);
                    JsonNode gameStateData = webSocketEventProcessor.createGameState(room.getGame(), playerWebSocketSession.userId);
                    if (gameStateData != null) {
                        eventNode.set(DATA_PROPERTY_NAME, gameStateData);
                    }
                    sendTextMessage(playerWebSocketSession, eventNode);
                }
            }
            case START_GAME -> {
                room.setGameStarted(true);

                // Выгнать всех наблюдателей из комнаты после старта игры
                List<String> spectators = new ArrayList<>();
                room.getMembers().stream()
                        .filter(Member::isSpectator)
                        .forEach(member -> spectators.add(member.getUserId()));
                room.getMembers().removeIf(member -> spectators.contains(member.getUserId()));
                // А также закрыть их сессии
                playerWebSocketSessions.stream()
                        .filter(playerSession -> spectators.contains(playerSession.userId))
                        .forEach(this::closePlayerSession);
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    ObjectNode eventNode = mapper.createObjectNode();
                    eventNode.put(EVENT_PROPERTY_NAME, START_GAME);
                    JsonNode startGameData = webSocketEventProcessor.startGame(game, playerWebSocketSession.userId);
                    if (startGameData != null) {
                        eventNode.set(DATA_PROPERTY_NAME, startGameData);
                    }
                    sendTextMessage(playerWebSocketSession, eventNode);
                }
            }
            case RESTART_GAME -> {
                room.setGameStarted(false);
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    ObjectNode eventNode = mapper.createObjectNode();
                    eventNode.put(EVENT_PROPERTY_NAME, RESTART_GAME);
                    JsonNode restartGameData = webSocketEventProcessor.restartGame(game, playerWebSocketSession.userId);
                    if (restartGameData != null) {
                        eventNode.set(DATA_PROPERTY_NAME, restartGameData);
                    }
                    sendTextMessage(playerWebSocketSession, eventNode);
                }
            }
            default -> {
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    ObjectNode eventNode = webSocketEventProcessor.outgoingEvent(event, game, playerWebSocketSession.userId);
                    if (eventNode != null) {
                        sendTextMessage(playerWebSocketSession, eventNode);
                    }
                }
            }
        }
        room.setLastActivity(LocalDateTime.now());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Websocket session error: {}", exception.getLocalizedMessage());
        log.debug(exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String token = (String) session.getAttributes().get("token");
        String userId = token.split(":")[0];
        String code = (String) session.getAttributes().get("code");
        cleanSessionResources(session, userId);
        log.debug("Websocket connection for user id {} in room {} closed with status {}", userId, code, status);
    }

    public void createNewSessionsListForRoom(String webSocketToken, String code) {
        roomSessionsLock.lock();
        try {
            webSocketTokenRoomCodeMap.put(webSocketToken, code);
            roomSessionsMap.put(code, new RoomWebSocketSessionsHolder());
        } finally {
            roomSessionsLock.unlock();
        }
        log.debug("New sessions list for room {} created", code);
    }

    public void removeSessionsListForRoom(String code) {
        RoomWebSocketSessionsHolder roomWebSocketSessionsHolder;
        roomSessionsLock.lock();
        try {
            roomWebSocketSessionsHolder = roomSessionsMap.get(code);
            roomSessionsMap.remove(code);
            log.trace("WebSocket sessions holder removed for room {}", code);

            String wsToken = null;
            for (Map.Entry<String, String> pair : webSocketTokenRoomCodeMap.entrySet()) {
                if (pair.getValue().equals(code)) {
                    wsToken = pair.getKey();
                    break;
                }
            }
            if (wsToken != null) {
                webSocketTokenRoomCodeMap.remove(wsToken);
                log.trace("WebSocket token removed for room {}", code);
            }
        } finally {
            roomSessionsLock.unlock();
        }
        if (roomWebSocketSessionsHolder == null) {
            return;
        }
        for (PlayerWebSocketSession playerWebSocketSession : roomWebSocketSessionsHolder.playerWebSocketSessions) {
            closePlayerSession(playerWebSocketSession);
        }
        log.debug("Sessions list for room {} removed", code);
    }

    private void sendTextMessage(PlayerWebSocketSession playerWebSocketSession, JsonNode payload) {
        sendTextMessage(playerWebSocketSession.webSocketSession, playerWebSocketSession.lock, payload);
    }

    private void sendTextMessage(WebSocketSession webSocketSession, Lock lock, JsonNode payload) {
        lock.lock();
        try {
            String eventJson = mapper.writeValueAsString(payload);
            TextMessage playersMessage = new TextMessage(eventJson);
            webSocketSession.sendMessage(playersMessage);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    private void cleanSessionResources(WebSocketSession session, String userId) {
        String code = (String) session.getAttributes().get("code");
        log.trace("Cleaning websocket session resources for user {} in room {}", userId, code);

        sessionLockMap.remove(session.getId());

        RoomWebSocketSessionsHolder roomWebSocketSessionsHolder = roomSessionsMap.get(code);
        if (roomWebSocketSessionsHolder == null) {
            log.trace("WebSocket sessions holder already removed for room {}", code);
            return;
        }
        roomWebSocketSessionsHolder.lock.lock();
        try {
            roomWebSocketSessionsHolder.playerWebSocketSessions
                    .removeIf(e -> e.webSocketSession.getId().equals(session.getId()));
            log.trace("Remove websocket session from holder for user {} in room {}", userId, code);
            Room<?> room = roomService.getRooms().get(code);
            Member member = room.getMemberByUserId(userId);
            // Если игра не начата, то выгнать вылетевшего из комнаты
            if (!room.isGameStarted()) {
                log.trace("Kick user {} from room {} because game not started", userId, code);
                room.getMembers().removeIf(m -> m.getUserId().equals(userId));
            }
            // Если вылетевший был админом, то...
            if (member != null && member.isAdmin()) {
                member.setAdmin(false);
                Member firstPlayer = room.getMembers().stream()
                        .filter(m -> m.isPlayer() && !m.getUserId().equals(userId))
                        .findFirst()
                        .orElse(null);

                if (firstPlayer != null) {
                    log.trace("User {} was admin in room {}, transfer permissions to user {}",
                            userId, code, firstPlayer.getUserId());
                    // ... передать статус админа первому попавшемуся игроку
                    firstPlayer.setAdmin(true);
                } else {
                    log.trace("User {} was admin in room {}, there is no another players, destroy room",
                            userId, code);
                    // ... или грохнуть всю комнату
                    roomService.getRooms().remove(code);
                    removeSessionsListForRoom(code);
                }
            }
        } finally {
            roomWebSocketSessionsHolder.lock.unlock();
        }
        log.debug("WebSocket session resources for user {} cleared", userId);
        // Обновить состояние всех членов комнаты из-за отключившегося
        sendEvent(code, UPDATE_MEMBERS);
    }

    private void closePlayerSession(PlayerWebSocketSession playerSession) {
        try {
            if (playerSession.webSocketSession.isOpen()) {
                playerSession.webSocketSession.close(CloseStatus.NORMAL);
            }
        } catch (IOException e) {
            log.error("Websocket session close error: {}", e.getLocalizedMessage());
        }
    }

    @RequiredArgsConstructor
    private static class RoomWebSocketSessionsHolder {
        ReentrantLock lock = new ReentrantLock();
        List<PlayerWebSocketSession> playerWebSocketSessions = new CopyOnWriteArrayList<>();
    }

    @RequiredArgsConstructor
    private static class PlayerWebSocketSession {
        ReentrantLock lock = new ReentrantLock();
        final String userId;
        final WebSocketSession webSocketSession;
    }
}
