package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
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

    private static final int MAX_MESSAGE_SIZE = 64 * 1024;

    private final Map<String, String> webSocketTokenRoomCodeMap = new ConcurrentHashMap<>();
    private final Map<String, RoomWebSocketSessionsHolder> roomSessionsMap = new ConcurrentHashMap<>();
    private final Lock roomSessionsLock = new ReentrantLock();

    private final Map<String, Lock> sessionLockMap = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;

    private final RoomService roomService;

    private final WebSocketEventProcessor webSocketEventProcessor;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.setTextMessageSizeLimit(MAX_MESSAGE_SIZE);
        session.setBinaryMessageSizeLimit(MAX_MESSAGE_SIZE);

        String token = (String) session.getAttributes().get("token");
        String[] tokenParts = token == null ? new String[0] : token.split(":");
        if (tokenParts.length != 2) {
            session.close(CloseStatus.BAD_DATA);
            log.warn("Incorrect websocket token format for session {}", session.getId());
            return;
        }
        String userId = tokenParts[0];
        String webSocketToken = tokenParts[1];

        String code = webSocketTokenRoomCodeMap.get(webSocketToken);
        if (code == null) {
            session.close(CloseStatus.BAD_DATA);
            log.warn("Incorrect websocket token for user with id {}", userId);
            return;
        }

        session.getAttributes().put("code", code);
        session.getAttributes().put("userId", userId);
        RoomWebSocketSessionsHolder roomWebSocketSessionsHolder = roomSessionsMap.get(code);
        if (roomWebSocketSessionsHolder == null) {
            session.close(CloseStatus.BAD_DATA);
            log.warn("Sessions holder for room {} is absent for user with id {}", code, userId);
            return;
        }
        List<PlayerWebSocketSession> playerWebSocketSessions = roomWebSocketSessionsHolder.playerWebSocketSessions;
        PlayerWebSocketSession playerWebSocketSession = new PlayerWebSocketSession(userId, session);
        playerWebSocketSessions.add(playerWebSocketSession);
        sessionLockMap.put(session.getId(), playerWebSocketSession.lock);
        log.info("Websocket connection established for user {} in room {}", userId, code);

        // Обновить состояние всех членов комнаты из-за вновь подключившегося
        sendEvent(code, UPDATE_MEMBERS);

        // Отправка текущего состояния игры (если начата) для вновь подключившегося
        Room<? extends Game> room = roomService.getRooms().get(code);
        if (room != null && room.isGameStarted()) {
            ObjectNode gameState = mapper.createObjectNode();
            gameState.put(EVENT_PROPERTY_NAME, GAME_STATE);
            try {
                JsonNode gameStateData = webSocketEventProcessor.createGameState(room.getGame(), userId);
                if (gameStateData != null) {
                    gameState.set(DATA_PROPERTY_NAME, gameStateData);
                }
                sendTextMessage(session, playerWebSocketSession.lock, gameState);
                log.debug("Send game state for user id {} in room {}: {}", userId, code, gameState);
            } catch (Exception e) {
                log.error("Unable to create game state for user {} in room {}", userId, code, e);
                sendErrorMessage(session, playerWebSocketSession.lock, "Unable to create game state");
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Incoming message: {}", message);

        Lock sessionLock = sessionLockMap.get(session.getId());
        if (sessionLock == null) {
            log.warn("No lock found for websocket session {}", session.getId());
            return;
        }

        ObjectNode messageNode;
        String event;
        try {
            JsonNode parsedMessage = mapper.readTree(message.getPayload());
            if (!parsedMessage.isObject()) {
                sendErrorMessage(session, sessionLock, "Message must be a JSON object");
                return;
            }
            messageNode = (ObjectNode) parsedMessage;
            JsonNode eventNode = messageNode.get(EVENT_PROPERTY_NAME);
            if (eventNode == null || !eventNode.isTextual() || eventNode.asText().isBlank()) {
                sendErrorMessage(session, sessionLock, "Event name is missing");
                return;
            }
            event = eventNode.asText();
        } catch (JsonProcessingException e) {
            log.debug("Unable to parse incoming message from session {}: {}",
                    session.getId(), e.getLocalizedMessage());
            sendErrorMessage(session, sessionLock, "Malformed JSON message");
            return;
        }

        if (PING.equals(event)) {
            messageNode.put(EVENT_PROPERTY_NAME, PONG);
            sendTextMessage(session, sessionLock, messageNode);
            return;
        }

        // Комната и пользователь берутся из атрибутов сессии, а не из тела сообщения
        String code = (String) session.getAttributes().get("code");
        String userId = (String) session.getAttributes().get("userId");
        if (code == null || userId == null) {
            sendErrorMessage(session, sessionLock, "Session is not bound to a room");
            return;
        }

        JsonNode dataNode = messageNode.get(DATA_PROPERTY_NAME);
        if (dataNode == null || !dataNode.isObject()) {
            sendErrorMessage(session, sessionLock, "Message data is missing");
            return;
        }
        // Если клиент передал код комнаты, он должен совпадать с комнатой сессии
        JsonNode codeNode = dataNode.get("code");
        if (codeNode != null && (!codeNode.isTextual() || !code.equals(codeNode.asText()))) {
            sendErrorMessage(session, sessionLock, "Room code does not match the session");
            return;
        }

        Room<?> room = roomService.getRooms().get(code);
        RoomWebSocketSessionsHolder roomWebSocketSessionsHolder = roomSessionsMap.get(code);
        if (room == null || roomWebSocketSessionsHolder == null) {
            sendErrorMessage(session, sessionLock, "The room doesn't exist");
            return;
        }
        Game game = room.getGame();

        Consumer<JsonNode> sendMessage = payload -> sendTextMessage(session, sessionLock, payload);
        Consumer<String> callbackEvent = e -> sendEvent(code, e);

        try {
            if (UPDATE_SETTINGS.equals(event)) {
                Member member;
                synchronized (room) {
                    member = room.getMemberByUserId(userId);
                }
                if (member == null || !member.isAdmin()) {
                    throw new RoomException("The member doesn't exist or it is not an admin");
                }
                JsonNode settingsNode = dataNode.get("settings");
                if (settingsNode == null || settingsNode.isNull()) {
                    throw new RoomException("Game settings are missing");
                }
                webSocketEventProcessor.updateGameSettings(settingsNode, game, sendMessage, callbackEvent);
            } else {
                webSocketEventProcessor.incomingEvent(messageNode, game, userId, sendMessage, callbackEvent);
            }
        } catch (Exception e) {
            log.debug("Error processing event {} from user {} in room {}: {}",
                    event, userId, code, e.getLocalizedMessage());
            log.trace("Event processing error", e);
            sendErrorMessage(session, sessionLock, processEventError(e));
        }

        synchronized (room) {
            room.setLastActivity(LocalDateTime.now());
        }
    }

    public void sendEvent(String code, String event) {
        RoomWebSocketSessionsHolder roomWebSocketSessionsHolder = roomSessionsMap.get(code);
        if (roomWebSocketSessionsHolder == null) {
            return;
        }

        List<PlayerWebSocketSession> playerWebSocketSessions = roomWebSocketSessionsHolder.playerWebSocketSessions;
        Room<?> room = roomService.getRooms().get(code);
        if (room == null) {
            log.debug("Skip sending event {} to room {} because it no longer exists", event, code);
            return;
        }
        Game game = room.getGame();

        switch (event) {
            case UPDATE_MEMBERS -> {
                ObjectNode eventNode = mapper.createObjectNode();
                eventNode.put(EVENT_PROPERTY_NAME, UPDATE_MEMBERS);
                List<String> onlinePlayers = playerWebSocketSessions.stream()
                        .map(playerSession -> playerSession.userId)
                        .toList();
                ObjectNode dataNode = eventNode.putObject(DATA_PROPERTY_NAME);
                // Снимок участников строится под блокировкой комнаты,
                // чтобы не сериализовать частично изменённое состояние
                try {
                    synchronized (room) {
                        long spectatorsCount = room.getMembers().stream()
                                .filter(m -> m.isSpectator() && onlinePlayers.contains(m.getUserId()))
                                .count();
                        List<Member> members = room.getMembers().stream()
                                .filter(m -> m.isPlayer() && onlinePlayers.contains(m.getUserId()))
                                .toList();
                        dataNode.put("spectatorsCount", spectatorsCount);
                        dataNode.set("members", mapper.valueToTree(members));
                    }
                } catch (Exception e) {
                    log.error("Unable to create members snapshot for room {}", code, e);
                    return;
                }
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    sendTextMessage(playerWebSocketSession, eventNode);
                }
            }
            case GAME_STATE -> {
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    ObjectNode eventNode = mapper.createObjectNode();
                    eventNode.put(EVENT_PROPERTY_NAME, GAME_STATE);
                    try {
                        JsonNode gameStateData = webSocketEventProcessor.createGameState(
                                room.getGame(), playerWebSocketSession.userId);
                        if (gameStateData != null) {
                            eventNode.set(DATA_PROPERTY_NAME, gameStateData);
                        }
                        sendTextMessage(playerWebSocketSession, eventNode);
                    } catch (Exception e) {
                        log.error("Unable to create game state for user {} in room {}",
                                playerWebSocketSession.userId, code, e);
                        sendErrorMessage(playerWebSocketSession, "Unable to create game state");
                    }
                }
            }
            case START_GAME -> {
                // Игра уже помечена запущенной в RoomService под блокировкой комнаты.
                // Здесь только выгоняем наблюдателей, делая это атомарно с чтением списка
                List<String> spectators;
                synchronized (room) {
                    spectators = room.getMembers().stream()
                            .filter(Member::isSpectator)
                            .map(Member::getUserId)
                            .toList();
                    room.getMembers().removeIf(member -> spectators.contains(member.getUserId()));
                }
                // А также закрыть их сессии, предварительно убрав из списка рассылки,
                // чтобы не пытаться отправить им событие старта игры
                List<PlayerWebSocketSession> spectatorSessions = playerWebSocketSessions.stream()
                        .filter(playerSession -> spectators.contains(playerSession.userId))
                        .toList();
                playerWebSocketSessions.removeAll(spectatorSessions);
                spectatorSessions.forEach(this::closePlayerSession);
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    ObjectNode eventNode = mapper.createObjectNode();
                    eventNode.put(EVENT_PROPERTY_NAME, START_GAME);
                    try {
                        JsonNode startGameData = webSocketEventProcessor.startGame(
                                game, playerWebSocketSession.userId);
                        if (startGameData != null) {
                            eventNode.set(DATA_PROPERTY_NAME, startGameData);
                        }
                    } catch (Exception e) {
                        log.error("Unable to create start game data for user {} in room {}",
                                playerWebSocketSession.userId, code, e);
                    }
                    sendTextMessage(playerWebSocketSession, eventNode);
                }
            }
            case RESTART_GAME -> {
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    ObjectNode eventNode = mapper.createObjectNode();
                    eventNode.put(EVENT_PROPERTY_NAME, RESTART_GAME);
                    try {
                        JsonNode restartGameData = webSocketEventProcessor.restartGame(
                                game, playerWebSocketSession.userId);
                        if (restartGameData != null) {
                            eventNode.set(DATA_PROPERTY_NAME, restartGameData);
                        }
                    } catch (Exception e) {
                        log.error("Unable to create restart game data for user {} in room {}",
                                playerWebSocketSession.userId, code, e);
                    }
                    sendTextMessage(playerWebSocketSession, eventNode);
                }
            }
            default -> {
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    try {
                        ObjectNode eventNode = webSocketEventProcessor.outgoingEvent(
                                event, game, playerWebSocketSession.userId);
                        if (eventNode != null) {
                            sendTextMessage(playerWebSocketSession, eventNode);
                        }
                    } catch (Exception e) {
                        log.error("Unable to process outgoing event {} for user {} in room {}",
                                event, playerWebSocketSession.userId, code, e);
                    }
                }
            }
        }
        synchronized (room) {
            room.setLastActivity(LocalDateTime.now());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Websocket session error: {}", exception.getLocalizedMessage());
        log.debug(exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String token = (String) session.getAttributes().get("token");
        if (token == null) {
            log.debug("Websocket session {} closed without token", session.getId());
            return;
        }
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

    private void sendErrorMessage(PlayerWebSocketSession playerWebSocketSession, String message) {
        sendErrorMessage(playerWebSocketSession.webSocketSession, playerWebSocketSession.lock, message);
    }

    private void sendErrorMessage(WebSocketSession webSocketSession, Lock lock, String message) {
        sendErrorMessage(webSocketSession, lock, mapper.getNodeFactory().textNode(message));
    }

    private void sendErrorMessage(WebSocketSession webSocketSession, Lock lock, JsonNode data) {
        ObjectNode errorNode = mapper.createObjectNode();
        errorNode.put(EVENT_PROPERTY_NAME, ERROR);
        errorNode.set(DATA_PROPERTY_NAME, data);
        sendTextMessage(webSocketSession, lock, errorNode);
    }

    private JsonNode processEventError(Exception e) {
        try {
            JsonNode errorData = webSocketEventProcessor.processEventError(e);
            if (errorData != null) {
                return errorData;
            }
        } catch (Exception formattingError) {
            log.warn("Unable to format event error: {}", formattingError.getLocalizedMessage());
        }
        String message = e.getLocalizedMessage() != null ? e.getLocalizedMessage() : "Event processing error";
        return mapper.getNodeFactory().textNode(message);
    }

    private void sendTextMessage(WebSocketSession webSocketSession, Lock lock, JsonNode payload) {
        if (!webSocketSession.isOpen()) {
            log.debug("Skip sending message to closed websocket session {}", webSocketSession.getId());
            return;
        }
        lock.lock();
        try {
            String eventJson = mapper.writeValueAsString(payload);
            TextMessage playersMessage = new TextMessage(eventJson);
            webSocketSession.sendMessage(playersMessage);
        } catch (IOException | IllegalStateException e) {
            // Ошибка отправки одному клиенту не должна прерывать рассылку остальным
            log.warn("Unable to send message to websocket session {}: {}",
                    webSocketSession.getId(), e.getLocalizedMessage());
        } finally {
            lock.unlock();
        }
    }

    private void cleanSessionResources(WebSocketSession session, String userId) {
        String code = (String) session.getAttributes().get("code");
        if (code == null) {
            log.trace("Skip cleaning websocket session resources for user {} without room code", userId);
            return;
        }
        log.trace("Cleaning websocket session resources for user {} in room {}", userId, code);

        sessionLockMap.remove(session.getId());

        RoomWebSocketSessionsHolder roomWebSocketSessionsHolder = roomSessionsMap.get(code);
        if (roomWebSocketSessionsHolder == null) {
            log.trace("WebSocket sessions holder already removed for room {}", code);
            return;
        }
        roomWebSocketSessionsHolder.lock.lock();
        try {
            List<PlayerWebSocketSession> playerWebSocketSessions = roomWebSocketSessionsHolder.playerWebSocketSessions;
            playerWebSocketSessions.removeIf(e -> e.webSocketSession.getId().equals(session.getId()));
            log.trace("Remove websocket session from holder for user {} in room {}", userId, code);

            // Участник считается отключившимся, только если у него не осталось других активных сессий
            boolean userLeftRoom = playerWebSocketSessions.stream()
                    .noneMatch(playerSession -> playerSession.userId.equals(userId));
            if (!userLeftRoom) {
                log.trace("User {} still has active websocket sessions in room {}", userId, code);
                return;
            }

            Room<?> room = roomService.getRooms().get(code);
            if (room == null) {
                log.trace("Room {} already removed", code);
                return;
            }
            // Мутации комнаты выполняются под её блокировкой, чтобы не пересекаться
            // с RoomService и планировщиком очистки
            synchronized (room) {
                if (roomService.getRooms().get(code) != room) {
                    log.trace("Room {} already removed", code);
                    return;
                }
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

    private static class RoomWebSocketSessionsHolder {
        final ReentrantLock lock = new ReentrantLock();
        final List<PlayerWebSocketSession> playerWebSocketSessions = new CopyOnWriteArrayList<>();
    }

    @RequiredArgsConstructor
    private static class PlayerWebSocketSession {
        final ReentrantLock lock = new ReentrantLock();
        final String userId;
        final WebSocketSession webSocketSession;
    }
}
