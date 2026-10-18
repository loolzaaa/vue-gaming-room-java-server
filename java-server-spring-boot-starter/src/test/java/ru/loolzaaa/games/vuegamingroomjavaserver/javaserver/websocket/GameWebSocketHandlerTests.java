package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint;
import org.apache.tomcat.websocket.WsSession;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import ru.loolzaaa.games.vuegamingroomjavaserver.IntegrationTestConfiguration;
import ru.loolzaaa.games.vuegamingroomjavaserver.autoconfigure.JavaServerAutoConfiguration;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.dto.RoomDTO;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.exception.RoomException;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.RoomService;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.settings.ServerSettings;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitWebConfig({JavaServerAutoConfiguration.class, IntegrationTestConfiguration.class})
class GameWebSocketHandlerTests {

    @Autowired
    ObjectMapper mapper;
    @Autowired
    GameWebSocketHandler gameWebSocketHandler;
    @Autowired
    RoomService roomService;

    @Test
    void shouldRespondWithErrorForMalformedJson() throws Exception {
        TestSession session = connect(createRoom("ws-malformed", gameWebSocketHandler), gameWebSocketHandler);

        gameWebSocketHandler.handleTextMessage(session.webSocketSession, new TextMessage("{not-json"));

        assertEquals("ERROR", lastMessage(session).get("event").asText());
    }

    @Test
    void shouldRespondWithErrorWhenEventIsMissing() throws Exception {
        TestSession session = connect(createRoom("ws-no-event", gameWebSocketHandler), gameWebSocketHandler);

        gameWebSocketHandler.handleTextMessage(session.webSocketSession,
                new TextMessage("{\"data\":{}}"));

        assertEquals("ERROR", lastMessage(session).get("event").asText());
    }

    @Test
    void shouldRespondWithErrorWhenDataIsMissing() throws Exception {
        TestSession session = connect(createRoom("ws-no-data", gameWebSocketHandler), gameWebSocketHandler);

        gameWebSocketHandler.handleTextMessage(session.webSocketSession,
                new TextMessage("{\"event\":\"SOME_EVENT\"}"));

        assertEquals("ERROR", lastMessage(session).get("event").asText());
    }

    @Test
    void shouldRespondWithErrorWhenRoomCodeDiffers() throws Exception {
        TestSession session = connect(createRoom("ws-wrong-code", gameWebSocketHandler), gameWebSocketHandler);

        gameWebSocketHandler.handleTextMessage(session.webSocketSession,
                new TextMessage("{\"event\":\"SOME_EVENT\",\"data\":{\"code\":\"WRONG\"}}"));

        assertEquals("ERROR", lastMessage(session).get("event").asText());
    }

    @Test
    void shouldRespondWithErrorWhenSettingsAreMissing() throws Exception {
        RoomDTO roomDTO = createRoom("ws-no-settings", gameWebSocketHandler);
        TestSession session = connect(roomDTO, gameWebSocketHandler);

        gameWebSocketHandler.handleTextMessage(session.webSocketSession, new TextMessage(
                "{\"event\":\"UPDATE_SETTINGS\",\"data\":{\"code\":\"" + roomDTO.getCode() + "\"}}"));

        assertEquals("ERROR", lastMessage(session).get("event").asText());
    }

    @Test
    void shouldReplyWithPong() throws Exception {
        TestSession session = connect(createRoom("ws-ping", gameWebSocketHandler), gameWebSocketHandler);

        gameWebSocketHandler.handleTextMessage(session.webSocketSession,
                new TextMessage("{\"event\":\"PING\"}"));

        assertEquals("PONG", lastMessage(session).get("event").asText());
    }

    @Test
    void shouldRespondWithErrorWhenProcessorThrows() throws Exception {
        WebSocketEventProcessor failingProcessor = mock(WebSocketEventProcessor.class);
        when(failingProcessor.processEventError(any())).thenReturn(null);
        doThrow(new RoomException("boom")).when(failingProcessor)
                .incomingEvent(any(), any(), any(), any(), any());

        GameWebSocketHandler handler = new GameWebSocketHandler(mapper, roomService, failingProcessor);
        RoomDTO roomDTO = createRoom("ws-failing-processor", handler);
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), roomDTO.getUserId(), true);
        TestSession session = connect(roomDTO, handler);

        handler.handleTextMessage(session.webSocketSession, new TextMessage(
                "{\"event\":\"FAIL\",\"data\":{\"code\":\"" + roomDTO.getCode() + "\"}}"));

        JsonNode response = lastMessage(session);
        assertEquals("ERROR", response.get("event").asText());
        assertEquals("boom", response.get("data").asText());
    }

    @Test
    void shouldConfigureDefaultIdleTimeout() throws Exception {
        TestSession session = connect(createRoom("ws-idle-default", gameWebSocketHandler), gameWebSocketHandler);

        verify(session.nativeSession()).setMaxIdleTimeout(120_000L);
    }

    @Test
    void shouldConfigureCustomIdleTimeout() throws Exception {
        ServerSettings settings = new ServerSettings(
                ServerSettings.RoomSettings.defaults(),
                new ServerSettings.WebSocketSettings(
                        "/game-ws", List.of("*"), 64 * 1024,
                        Duration.ofSeconds(30), Duration.ofSeconds(5), 128 * 1024,
                        3, 30, 60, Duration.ofSeconds(30)));
        GameWebSocketHandler handler = new GameWebSocketHandler(
                mapper, roomService, mock(WebSocketEventProcessor.class), settings);

        TestSession session = connect(createRoom("ws-idle-custom", handler), handler);

        verify(session.nativeSession()).setMaxIdleTimeout(30_000L);
    }

    @Test
    void shouldCloseSessionWhenRateLimitExceeded() throws Exception {
        ServerSettings settings = settingsWith(3, 1, 1);
        GameWebSocketHandler handler = new GameWebSocketHandler(
                mapper, roomService, mock(WebSocketEventProcessor.class), settings);
        RoomDTO roomDTO = createRoom("ws-rate-limit", handler);
        TestSession session = connect(roomDTO, handler);

        handler.handleTextMessage(session.webSocketSession, new TextMessage("{\"event\":\"PING\"}"));
        handler.handleTextMessage(session.webSocketSession, new TextMessage("{\"event\":\"PING\"}"));

        verify(session.nativeSession(), atLeastOnce()).close(any(CloseReason.class));
    }

    @Test
    void shouldCloseOldestSessionWhenPerUserLimitExceeded() throws Exception {
        ServerSettings settings = settingsWith(1, 30, 60);
        GameWebSocketHandler handler = new GameWebSocketHandler(
                mapper, roomService, mock(WebSocketEventProcessor.class), settings);
        RoomDTO roomDTO = createRoom("ws-session-limit", handler);

        TestSession first = connect(roomDTO, handler);
        connect(roomDTO, handler);

        verify(first.nativeSession(), atLeastOnce()).close(any(CloseReason.class));
    }

    private ServerSettings settingsWith(int maxSessionsPerUser, int ratePerSecond, int rateBurst) {
        return new ServerSettings(
                ServerSettings.RoomSettings.defaults(),
                new ServerSettings.WebSocketSettings(
                        "/game-ws", List.of("*"), 64 * 1024,
                        Duration.ofSeconds(30), Duration.ofSeconds(5), 128 * 1024,
                        maxSessionsPerUser, ratePerSecond, rateBurst, Duration.ofSeconds(30)));
    }

    @Test
    void shouldSendRoleSpecificStateAndKeepSpectators() throws Exception {
        RoomDTO admin = createRoom("role-admin", gameWebSocketHandler);
        RoomDTO player = roomService.joinToRoom(admin.getCode(), "role-player", "player");
        roomService.changeMemberPlayerStatus(admin.getCode(), player.getUserId(), true);

        TestSession adminSession = connect(admin, gameWebSocketHandler);
        TestSession playerSession = connect(player, gameWebSocketHandler);

        roomService.startGame(admin.getCode(), admin.getUserId(), true);
        gameWebSocketHandler.sendEvent(admin.getCode(), GameWebSocketHandler.START_GAME);
        gameWebSocketHandler.sendEvent(admin.getCode(), GameWebSocketHandler.GAME_STATE);

        assertEquals("display", findEvent(adminSession, GameWebSocketHandler.START_GAME)
                .get("data").get("view").asText());
        assertEquals("player", findEvent(playerSession, GameWebSocketHandler.START_GAME)
                .get("data").get("view").asText());
        assertEquals("display", findEvent(adminSession, GameWebSocketHandler.GAME_STATE)
                .get("data").get("view").asText());
        assertEquals("player", findEvent(playerSession, GameWebSocketHandler.GAME_STATE)
                .get("data").get("view").asText());

        assertNotNull(roomService.getRooms().get(admin.getCode())
                .getMemberByUserId(admin.getUserId()));
    }

    @Test
    void shouldRejectGameEventsFromSpectators() throws Exception {
        RoomDTO admin = createRoom("spectator-send", gameWebSocketHandler);
        RoomDTO player = roomService.joinToRoom(admin.getCode(), "spectator-send-player", "player");
        roomService.changeMemberPlayerStatus(admin.getCode(), player.getUserId(), true);
        roomService.startGame(admin.getCode(), admin.getUserId(), true);

        TestSession adminSession = connect(admin, gameWebSocketHandler);
        gameWebSocketHandler.handleTextMessage(adminSession.webSocketSession, new TextMessage(
                "{\"event\":\"CUSTOM\",\"data\":{\"code\":\"" + admin.getCode() + "\"}}"));

        assertEquals("Only players can send game events",
                findEvent(adminSession, "ERROR").get("data").asText());
    }

    private RoomDTO createRoom(String userId, GameWebSocketHandler handler) {
        RoomDTO roomDTO = roomService.createRoom(userId, "admin");
        handler.createNewSessionsListForRoom(roomDTO.getCode());
        return roomDTO;
    }

    private TestSession connect(RoomDTO roomDTO, GameWebSocketHandler handler) throws Exception {
        StandardWebSocketSession webSocketSession = new StandardWebSocketSession(null, null, null, null);
        WsSession wsSession = mock(WsSession.class);
        when(wsSession.isOpen()).thenReturn(true);
        RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
        when(wsSession.getBasicRemote()).thenReturn(remote);

        List<String> sentMessages = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            sentMessages.add(invocation.getArgument(0));
            return null;
        }).when(remote).sendText(anyString(), anyBoolean());
        doAnswer(invocation -> {
            sentMessages.add(invocation.getArgument(0));
            return null;
        }).when(remote).sendText(anyString());

        webSocketSession.initializeNativeSession(wsSession);
        webSocketSession.getAttributes().put(TokenHandshakeInterceptor.CODE_ATTRIBUTE, roomDTO.getCode());
        webSocketSession.getAttributes().put(TokenHandshakeInterceptor.USER_ID_ATTRIBUTE, roomDTO.getUserId());
        handler.afterConnectionEstablished(webSocketSession);
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !sentMessages.isEmpty());
        sentMessages.clear();

        return new TestSession(webSocketSession, wsSession, sentMessages);
    }

    private JsonNode lastMessage(TestSession session) throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> !session.sentMessages.isEmpty());
        return mapper.readTree(session.sentMessages.get(session.sentMessages.size() - 1));
    }

    private JsonNode findEvent(TestSession session, String event) throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> session.sentMessages.stream()
                .map(payload -> {
                    try {
                        return mapper.readTree(payload);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .anyMatch(node -> event.equals(node.get("event").asText())));
        return session.sentMessages.stream()
                .map(payload -> {
                    try {
                        return mapper.readTree(payload);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .filter(node -> event.equals(node.get("event").asText()))
                .findFirst()
                .orElseThrow();
    }

    private record TestSession(StandardWebSocketSession webSocketSession,
                               WsSession nativeSession,
                               List<String> sentMessages) {
    }
}
