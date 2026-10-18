package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.exception.RoomException;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Game;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Member;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Room;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.security.TokenBucket;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.RoomService;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.settings.ServerSettings;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.WebSocketEventProcessor.DATA_PROPERTY_NAME;
import static ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.WebSocketEventProcessor.EVENT_PROPERTY_NAME;


public class GameWebSocketHandler extends TextWebSocketHandler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);

    public static final String UPDATE_MEMBERS = "UPDATE_MEMBERS";
    public static final String GAME_STATE = "GAME_STATE";
    public static final String UPDATE_SETTINGS = "UPDATE_SETTINGS";
    public static final String START_GAME = "START_GAME";
    public static final String RESTART_GAME = "RESTART_GAME";

    private static final String ERROR = "ERROR";
    private static final String PING = "PING";
    private static final String PONG = "PONG";

    private static final String CODE_ATTRIBUTE = "code";
    private static final String USER_ID_ATTRIBUTE = "userId";
    private static final String CODE_PROPERTY_NAME = "code";
    private static final String SPECTATORS_COUNT_PROPERTY_NAME = "spectatorsCount";
    private static final String MEMBERS_PROPERTY_NAME = "members";

    private final Map<String, RoomWebSocketSessionsHolder> roomSessionsMap = new ConcurrentHashMap<>();
    private final Lock roomSessionsLock = new ReentrantLock();

    private final Map<String, PlayerWebSocketSession> playerSessionsById = new ConcurrentHashMap<>();
    private final Map<String, PendingTicket> wsTickets = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;
    private final RoomService roomService;
    private final WebSocketEventProcessor webSocketEventProcessor;

    private final int maxMessageSize;
    private final long idleTimeoutMs;
    private final int sendTimeLimitMs;
    private final int sendBufferSize;
    private final int maxSessionsPerUser;
    private final int rateLimitPerSecond;
    private final int rateLimitBurst;
    private final long ticketTtlMs;
    private final long ticketTtlNanos;
    private final int sendQueueCapacity;

    public record WsIdentity(String code, String userId) {
    }

    public record WsTicket(String ticket, long expiresInMs) {
    }

    private record PendingTicket(String code, String userId, long expiresAtNanos) {
    }

    public GameWebSocketHandler(ObjectMapper mapper,
                                RoomService roomService,
                                WebSocketEventProcessor webSocketEventProcessor) {
        this(mapper, roomService, webSocketEventProcessor, ServerSettings.defaults());
    }

    public GameWebSocketHandler(ObjectMapper mapper,
                                RoomService roomService,
                                WebSocketEventProcessor webSocketEventProcessor,
                                ServerSettings settings) {
        this.mapper = mapper;
        this.roomService = roomService;
        this.webSocketEventProcessor = webSocketEventProcessor;
        this.maxMessageSize = settings.webSocket().maxMessageSize();
        this.idleTimeoutMs = settings.webSocket().idleTimeout().toMillis();
        this.sendTimeLimitMs = (int) settings.webSocket().sendTimeLimit().toMillis();
        this.sendBufferSize = settings.webSocket().sendBufferSize();
        this.maxSessionsPerUser = settings.webSocket().maxSessionsPerUser();
        this.rateLimitPerSecond = settings.webSocket().rateLimitPerSecond();
        this.rateLimitBurst = settings.webSocket().rateLimitBurst();
        this.ticketTtlMs = settings.webSocket().ticketTtl().toMillis();
        this.ticketTtlNanos = TimeUnit.MILLISECONDS.toNanos(ticketTtlMs);
        this.sendQueueCapacity = settings.webSocket().sendQueueCapacity();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.setTextMessageSizeLimit(maxMessageSize);
        session.setBinaryMessageSizeLimit(maxMessageSize);
        if (idleTimeoutMs > 0 && session instanceof NativeWebSocketSession nativeSession) {
            Session nativeWsSession = nativeSession.getNativeSession(Session.class);
            if (nativeWsSession != null) {
                nativeWsSession.setMaxIdleTimeout(idleTimeoutMs);
            }
        }

        String code = (String) session.getAttributes().get(CODE_ATTRIBUTE);
        String userId = (String) session.getAttributes().get(USER_ID_ATTRIBUTE);
        if (code == null || userId == null) {
            session.close(CloseStatus.BAD_DATA);
            log.warn("Websocket session {} is not bound to a room", session.getId());
            return;
        }

        Room<? extends Game> room = roomService.getRooms().get(code);
        if (room == null) {
            session.close(CloseStatus.BAD_DATA);
            log.warn("Room {} is absent for user with id {}", code, userId);
            return;
        }
        boolean player;
        synchronized (room) {
            Member member = room.getMemberByUserId(userId);
            if (member == null) {
                session.close(CloseStatus.POLICY_VIOLATION);
                log.warn("User {} is not a member of room {}", userId, code);
                return;
            }
            player = member.isPlayer();
        }

        RoomWebSocketSessionsHolder roomWebSocketSessionsHolder = roomSessionsMap.get(code);
        if (roomWebSocketSessionsHolder == null) {
            session.close(CloseStatus.BAD_DATA);
            log.warn("Sessions holder for room {} is absent for user with id {}", code, userId);
            return;
        }
        List<PlayerWebSocketSession> playerWebSocketSessions = roomWebSocketSessionsHolder.playerWebSocketSessions;
        WebSocketSession concurrentSession = new ConcurrentWebSocketSessionDecorator(
                session, sendTimeLimitMs, sendBufferSize);
        PlayerWebSocketSession playerWebSocketSession = new PlayerWebSocketSession(
                userId, concurrentSession, new TokenBucket(rateLimitPerSecond, rateLimitBurst),
                mapper, sendQueueCapacity);
        playerWebSocketSessions.add(playerWebSocketSession);
        playerSessionsById.put(session.getId(), playerWebSocketSession);
        closeExcessSessions(playerWebSocketSessions, playerWebSocketSession);
        log.info("Websocket connection established for user {} in room {}", userId, code);

        // Обновить состояние всех членов комнаты из-за вновь подключившегося
        sendEvent(code, UPDATE_MEMBERS);

        // Отправка текущего состояния игры (если начата) для вновь подключившегося
        if (room.isGameStarted()) {
            ObjectNode gameState = mapper.createObjectNode();
            gameState.put(EVENT_PROPERTY_NAME, GAME_STATE);
            try {
                JsonNode gameStateData = player
                        ? webSocketEventProcessor.createPlayerState(room.getGame(), userId)
                        : webSocketEventProcessor.createDisplayState(room.getGame());
                if (gameStateData != null) {
                    gameState.set(DATA_PROPERTY_NAME, gameStateData);
                }
                sendTextMessage(playerWebSocketSession, GAME_STATE, gameState, true);
                log.debug("Send game state for user id {} in room {}: {}", userId, code, gameState);
            } catch (Exception e) {
                log.error("Unable to create game state for user {} in room {}", userId, code, e);
                sendErrorMessage(playerWebSocketSession, "Unable to create game state");
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Incoming message: {}", message);

        PlayerWebSocketSession playerWebSocketSession = playerSessionsById.get(session.getId());
        if (playerWebSocketSession == null) {
            log.warn("No registered websocket session found for {}", session.getId());
            return;
        }
        if (!playerWebSocketSession.rateLimiter.tryAcquire()) {
            log.warn("Rate limit exceeded for user {} in session {}; closing session",
                    playerWebSocketSession.userId, session.getId());
            closePlayerSession(playerWebSocketSession);
            return;
        }

        ObjectNode messageNode;
        String event;
        try {
            JsonNode parsedMessage = mapper.readTree(message.getPayload());
            if (!parsedMessage.isObject()) {
                sendErrorMessage(playerWebSocketSession, "Message must be a JSON object");
                return;
            }
            messageNode = (ObjectNode) parsedMessage;
            JsonNode eventNode = messageNode.get(EVENT_PROPERTY_NAME);
            if (eventNode == null || !eventNode.isTextual() || eventNode.asText().isBlank()) {
                sendErrorMessage(playerWebSocketSession, "Event name is missing");
                return;
            }
            event = eventNode.asText();
        } catch (JsonProcessingException e) {
            log.debug("Unable to parse incoming message from session {}: {}",
                    session.getId(), e.getLocalizedMessage());
            sendErrorMessage(playerWebSocketSession, "Malformed JSON message");
            return;
        }

        if (PING.equals(event)) {
            messageNode.put(EVENT_PROPERTY_NAME, PONG);
            sendTextMessage(playerWebSocketSession, PONG, messageNode, false);
            return;
        }

        // Комната и пользователь берутся из атрибутов сессии, а не из тела сообщения
        String code = (String) session.getAttributes().get(CODE_ATTRIBUTE);
        String userId = (String) session.getAttributes().get(USER_ID_ATTRIBUTE);
        if (code == null || userId == null) {
            sendErrorMessage(playerWebSocketSession, "Session is not bound to a room");
            return;
        }

        JsonNode dataNode = messageNode.get(DATA_PROPERTY_NAME);
        if (dataNode == null || !dataNode.isObject()) {
            sendErrorMessage(playerWebSocketSession, "Message data is missing");
            return;
        }
        // Если клиент передал код комнаты, он должен совпадать с комнатой сессии
        JsonNode codeNode = dataNode.get(CODE_PROPERTY_NAME);
        if (codeNode != null && (!codeNode.isTextual() || !code.equals(codeNode.asText()))) {
            sendErrorMessage(playerWebSocketSession, "Room code does not match the session");
            return;
        }

        Room<?> room = roomService.getRooms().get(code);
        RoomWebSocketSessionsHolder roomWebSocketSessionsHolder = roomSessionsMap.get(code);
        if (room == null || roomWebSocketSessionsHolder == null) {
            sendErrorMessage(playerWebSocketSession, "The room doesn't exist");
            return;
        }
        Game game = room.getGame();
        Member member;
        synchronized (room) {
            member = room.getMemberByUserId(userId);
        }

        Consumer<JsonNode> sendMessage = payload -> sendTextMessage(playerWebSocketSession, event, payload, false);
        Consumer<String> callbackEvent = e -> sendEvent(code, e);

        try {
            if (UPDATE_SETTINGS.equals(event)) {
                if (member == null || !member.isAdmin()) {
                    throw new RoomException("The member doesn't exist or it is not an admin");
                }
                JsonNode settingsNode = dataNode.get("settings");
                if (settingsNode == null || settingsNode.isNull()) {
                    throw new RoomException("Game settings are missing");
                }
                webSocketEventProcessor.updateGameSettings(settingsNode, game, sendMessage, callbackEvent);
            } else {
                if (member == null || !member.isPlayer()) {
                    throw new RoomException("Only players can send game events");
                }
                webSocketEventProcessor.incomingEvent(messageNode, game, userId, sendMessage, callbackEvent);
            }
        } catch (Exception e) {
            log.debug("Error processing event {} from user {} in room {}: {}",
                    event, userId, code, e.getLocalizedMessage());
            log.trace("Event processing error", e);
            sendErrorMessage(playerWebSocketSession, processEventError(e));
        }

        synchronized (room) {
            room.setLastActivity(LocalDateTime.now());
        }
    }

    public void sendEvent(String code, String event) {
        sendEvent(code, event, false);
    }

    /**
     * Отправляет событие в комнату.
     * <p>
     * Кастомные события по умолчанию не коалесцируются. Если событие содержит
     * только актуальное состояние (аналог {@code GAME_STATE}), можно передать
     * {@code coalescable = true}: новое сообщение заменит ещё не отправленное
     * сообщение с тем же именем.
     */
    public void sendEvent(String code, String event, boolean coalescable) {
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
                List<String> onlineUserIds = playerWebSocketSessions.stream()
                        .map(playerSession -> playerSession.userId)
                        .toList();
                ObjectNode dataNode = eventNode.putObject(DATA_PROPERTY_NAME);
                // Снимок участников строится под блокировкой комнаты,
                // чтобы не сериализовать частично изменённое состояние
                try {
                    synchronized (room) {
                        long spectatorsCount = room.getMembers().stream()
                                .filter(m -> m.isSpectator() && onlineUserIds.contains(m.getUserId()))
                                .count();
                        List<Member> members = room.getMembers().stream()
                                .filter(m -> onlineUserIds.contains(m.getUserId()))
                                .toList();
                        dataNode.put(SPECTATORS_COUNT_PROPERTY_NAME, spectatorsCount);
                        dataNode.set(MEMBERS_PROPERTY_NAME, mapper.valueToTree(members));
                    }
                } catch (Exception e) {
                    log.error("Unable to create members snapshot for room {}", code, e);
                    return;
                }
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    sendTextMessage(playerWebSocketSession, UPDATE_MEMBERS, eventNode, true);
                }
            }
            case GAME_STATE -> {
                JsonNode displayState = null;
                boolean displayStateCreated = false;
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    Member member;
                    synchronized (room) {
                        member = room.getMemberByUserId(playerWebSocketSession.userId);
                    }
                    if (member == null) {
                        continue;
                    }
                    ObjectNode eventNode = mapper.createObjectNode();
                    eventNode.put(EVENT_PROPERTY_NAME, GAME_STATE);
                    try {
                        JsonNode gameStateData;
                        if (member.isPlayer()) {
                            gameStateData = webSocketEventProcessor.createPlayerState(
                                    room.getGame(), playerWebSocketSession.userId);
                        } else {
                            if (!displayStateCreated) {
                                displayState = webSocketEventProcessor.createDisplayState(room.getGame());
                                displayStateCreated = true;
                            }
                            gameStateData = displayState;
                        }
                        if (gameStateData != null) {
                            eventNode.set(DATA_PROPERTY_NAME, gameStateData);
                        }
                        sendTextMessage(playerWebSocketSession, GAME_STATE, eventNode, true);
                    } catch (Exception e) {
                        log.error("Unable to create game state for user {} in room {}",
                                playerWebSocketSession.userId, code, e);
                        sendErrorMessage(playerWebSocketSession, "Unable to create game state");
                    }
                }
            }
            case START_GAME -> {
                JsonNode displayStartData = null;
                boolean displayStartDataCreated = false;
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    Member member;
                    synchronized (room) {
                        member = room.getMemberByUserId(playerWebSocketSession.userId);
                    }
                    if (member == null) {
                        continue;
                    }
                    ObjectNode eventNode = mapper.createObjectNode();
                    eventNode.put(EVENT_PROPERTY_NAME, START_GAME);
                    try {
                        JsonNode startGameData;
                        if (member.isPlayer()) {
                            startGameData = webSocketEventProcessor.startPlayerGame(
                                    game, playerWebSocketSession.userId);
                        } else {
                            if (!displayStartDataCreated) {
                                displayStartData = webSocketEventProcessor.startDisplayGame(game);
                                displayStartDataCreated = true;
                            }
                            startGameData = displayStartData;
                        }
                        if (startGameData != null) {
                            eventNode.set(DATA_PROPERTY_NAME, startGameData);
                        }
                    } catch (Exception e) {
                        log.error("Unable to create start game data for user {} in room {}",
                                playerWebSocketSession.userId, code, e);
                    }
                    sendTextMessage(playerWebSocketSession, START_GAME, eventNode, false);
                }
            }
            case RESTART_GAME -> {
                JsonNode displayRestartData = null;
                boolean displayRestartDataCreated = false;
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    Member member;
                    synchronized (room) {
                        member = room.getMemberByUserId(playerWebSocketSession.userId);
                    }
                    if (member == null) {
                        continue;
                    }
                    ObjectNode eventNode = mapper.createObjectNode();
                    eventNode.put(EVENT_PROPERTY_NAME, RESTART_GAME);
                    try {
                        JsonNode restartGameData;
                        if (member.isPlayer()) {
                            restartGameData = webSocketEventProcessor.restartPlayerGame(
                                    game, playerWebSocketSession.userId);
                        } else {
                            if (!displayRestartDataCreated) {
                                displayRestartData = webSocketEventProcessor.restartDisplayGame(game);
                                displayRestartDataCreated = true;
                            }
                            restartGameData = displayRestartData;
                        }
                        if (restartGameData != null) {
                            eventNode.set(DATA_PROPERTY_NAME, restartGameData);
                        }
                    } catch (Exception e) {
                        log.error("Unable to create restart game data for user {} in room {}",
                                playerWebSocketSession.userId, code, e);
                    }
                    sendTextMessage(playerWebSocketSession, RESTART_GAME, eventNode, false);
                }
            }
            default -> {
                for (PlayerWebSocketSession playerWebSocketSession : playerWebSocketSessions) {
                    Member member;
                    synchronized (room) {
                        member = room.getMemberByUserId(playerWebSocketSession.userId);
                    }
                    if (member == null) {
                        continue;
                    }
                    try {
                        ObjectNode eventNode = member.isPlayer()
                                ? webSocketEventProcessor.outgoingPlayerEvent(
                                        event, game, playerWebSocketSession.userId)
                                : webSocketEventProcessor.outgoingDisplayEvent(event, game);
                        if (eventNode != null) {
                            sendTextMessage(playerWebSocketSession, event, eventNode, coalescable);
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
        log.debug("Websocket session error", exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = (String) session.getAttributes().get(USER_ID_ATTRIBUTE);
        String code = (String) session.getAttributes().get(CODE_ATTRIBUTE);
        if (userId == null) {
            log.debug("Websocket session {} closed without user id", session.getId());
            return;
        }
        cleanSessionResources(session, userId);
        log.debug("Websocket connection for user id {} in room {} closed with status {}", userId, code, status);
    }

    public void createNewSessionsListForRoom(String code) {
        roomSessionsLock.lock();
        try {
            roomSessionsMap.put(code, new RoomWebSocketSessionsHolder());
        } finally {
            roomSessionsLock.unlock();
        }
        log.debug("New sessions list for room {} created", code);
    }

    public WsTicket createWsTicket(String code, String userId) {
        long now = System.nanoTime();
        wsTickets.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() < now);
        String ticket = UUID.randomUUID().toString();
        wsTickets.put(ticket, new PendingTicket(code, userId, now + ticketTtlNanos));
        return new WsTicket(ticket, ticketTtlMs);
    }

    public WsIdentity consumeWsTicket(String ticket) {
        PendingTicket pendingTicket = wsTickets.remove(ticket);
        if (pendingTicket == null || pendingTicket.expiresAtNanos() < System.nanoTime()) {
            return null;
        }
        return new WsIdentity(pendingTicket.code(), pendingTicket.userId());
    }

    public void removeSessionsListForRoom(String code) {
        RoomWebSocketSessionsHolder roomWebSocketSessionsHolder;
        roomSessionsLock.lock();
        try {
            roomWebSocketSessionsHolder = roomSessionsMap.remove(code);
            wsTickets.entrySet().removeIf(entry -> entry.getValue().code().equals(code));
            log.trace("WebSocket sessions holder removed for room {}", code);
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

    private void sendTextMessage(PlayerWebSocketSession playerWebSocketSession,
                                 String event,
                                 JsonNode payload,
                                 boolean coalescable) {
        playerWebSocketSession.send(event, payload, coalescable);
    }

    private void sendErrorMessage(PlayerWebSocketSession playerWebSocketSession, String message) {
        sendErrorMessage(playerWebSocketSession, mapper.getNodeFactory().textNode(message));
    }

    private void sendErrorMessage(PlayerWebSocketSession playerWebSocketSession, JsonNode data) {
        ObjectNode errorNode = mapper.createObjectNode();
        errorNode.put(EVENT_PROPERTY_NAME, ERROR);
        errorNode.set(DATA_PROPERTY_NAME, data);
        sendTextMessage(playerWebSocketSession, ERROR, errorNode, false);
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

    private void closeExcessSessions(List<PlayerWebSocketSession> sessions,
                                     PlayerWebSocketSession newSession) {
        List<PlayerWebSocketSession> userSessions = sessions.stream()
                .filter(session -> !session.webSocketSession.getId().equals(newSession.webSocketSession.getId()))
                .filter(session -> session.userId.equals(newSession.userId))
                .toList();
        int excess = userSessions.size() + 1 - maxSessionsPerUser;
        for (int i = 0; i < excess; i++) {
            PlayerWebSocketSession oldSession = userSessions.get(i);
            sessions.remove(oldSession);
            playerSessionsById.remove(oldSession.webSocketSession.getId());
            log.info("Closing excess websocket session {} for user {}",
                    oldSession.webSocketSession.getId(), oldSession.userId);
            closePlayerSession(oldSession);
        }
    }

    private void cleanSessionResources(WebSocketSession session, String userId) {
        String code = (String) session.getAttributes().get(CODE_ATTRIBUTE);
        if (code == null) {
            log.trace("Skip cleaning websocket session resources for user {} without room code", userId);
            return;
        }
        log.trace("Cleaning websocket session resources for user {} in room {}", userId, code);

        PlayerWebSocketSession playerWebSocketSession = playerSessionsById.remove(session.getId());
        if (playerWebSocketSession != null) {
            playerWebSocketSession.close(CloseStatus.NORMAL);
        }

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
        playerSession.close(CloseStatus.NORMAL);
    }

    @Override
    public void destroy() {
        List<PlayerWebSocketSession> sessions = new ArrayList<>(playerSessionsById.values());
        sessions.forEach(session -> session.close(CloseStatus.GOING_AWAY));
        playerSessionsById.clear();
    }

    private static class RoomWebSocketSessionsHolder {
        final ReentrantLock lock = new ReentrantLock();
        final List<PlayerWebSocketSession> playerWebSocketSessions = new CopyOnWriteArrayList<>();
    }

    private static class PlayerWebSocketSession {
        final String userId;
        final WebSocketSession webSocketSession;
        final TokenBucket rateLimiter;
        final SessionOutboundSender sender;

        PlayerWebSocketSession(String userId,
                               WebSocketSession webSocketSession,
                               TokenBucket rateLimiter,
                               ObjectMapper mapper,
                               int sendQueueCapacity) {
            this.userId = userId;
            this.webSocketSession = webSocketSession;
            this.rateLimiter = rateLimiter;
            this.sender = new SessionOutboundSender(webSocketSession, mapper, sendQueueCapacity);
        }

        void send(String event, JsonNode payload, boolean coalescable) {
            sender.send(event, payload, coalescable);
        }

        void close(CloseStatus status) {
            sender.close(status);
        }
    }
}
