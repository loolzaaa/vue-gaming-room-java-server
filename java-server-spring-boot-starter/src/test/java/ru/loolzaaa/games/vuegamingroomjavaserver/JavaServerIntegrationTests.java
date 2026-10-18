package ru.loolzaaa.games.vuegamingroomjavaserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.RemoteEndpoint;
import org.apache.tomcat.websocket.WsSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import ru.loolzaaa.games.vuegamingroomjavaserver.autoconfigure.JavaServerAutoConfiguration;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.controller.RoomController;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.dto.RoomDTO;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.exception.RoomException;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.RoomService;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.GameWebSocketHandler;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.TokenHandshakeInterceptor;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.*;

@SpringJUnitWebConfig(value = { JavaServerAutoConfiguration.class, IntegrationTestConfiguration.class })
class JavaServerIntegrationTests {

    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    GameWebSocketHandler gameWebSocketHandler;
    @Autowired
    RoomService roomService;

    @BeforeEach
    void setUp() {
        this.mockMvc = standaloneSetup(new RoomController(gameWebSocketHandler, roomService)).build();
    }

    @Test
    void shouldAllowReconnectExistingMemberWhenWebSocketCrash() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/room/create")
                        .param("userId", "test-id-1")
                        .param("nickname", "first"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        RoomDTO roomDTO1 = mapper.readValue(mvcResult.getResponse().getContentAsByteArray(), RoomDTO.class);

        StandardWebSocketSession webSocketSession1 = new StandardWebSocketSession(null, null, null, null);
        WsSession mockWsSession1 = mock(WsSession.class);
        when(mockWsSession1.getBasicRemote()).thenReturn(mock(RemoteEndpoint.Basic.class));
        webSocketSession1.initializeNativeSession(mockWsSession1);
        webSocketSession1.getAttributes().put("token", roomDTO1.getUserId() + ":" + roomDTO1.getWsToken());
        gameWebSocketHandler.afterConnectionEstablished(webSocketSession1);

        mvcResult = mockMvc.perform(post("/room/join/{code}", roomDTO1.getCode())
                        .param("userId", "test-id-2")
                        .param("nickname", "second"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        RoomDTO roomDTO2 = mapper.readValue(mvcResult.getResponse().getContentAsByteArray(), RoomDTO.class);

        StandardWebSocketSession webSocketSession2 = new StandardWebSocketSession(null, null, null, null);
        WsSession mockWsSession2 = mock(WsSession.class);
        when(mockWsSession2.getBasicRemote()).thenReturn(mock(RemoteEndpoint.Basic.class));
        webSocketSession2.initializeNativeSession(mockWsSession2);
        webSocketSession2.getAttributes().put("token", roomDTO2.getUserId() + ":" + roomDTO2.getWsToken());
        gameWebSocketHandler.afterConnectionEstablished(webSocketSession2);

        mockMvc.perform(withToken(post("/room/{code}/{userId}/player", roomDTO1.getCode(), roomDTO1.getUserId()), roomDTO1)
                        .param("newStatus", "true"))
                .andExpect(status().isNoContent());
        mockMvc.perform(withToken(post("/room/{code}/{userId}/player", roomDTO2.getCode(), roomDTO2.getUserId()), roomDTO2)
                        .param("newStatus", "true"))
                .andExpect(status().isNoContent());

        mockMvc.perform(withToken(post("/room/{code}/start", roomDTO1.getCode()), roomDTO1)
                        .param("userId", roomDTO1.getUserId())
                        .param("forceStart", "true"))
                .andExpect(status().isNoContent());

        gameWebSocketHandler.afterConnectionClosed(webSocketSession2, CloseStatus.GOING_AWAY);

        mockMvc.perform(withToken(post("/room/join/{code}", roomDTO1.getCode()), roomDTO2)
                        .param("userId", "test-id-2")
                        .param("nickname", "second"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void shouldDenyJoinWithSameNickname() throws Exception {
        RoomDTO roomDTO = createRoom("duplicate-nickname-admin", "admin");

        mockMvc.perform(post("/room/join/{code}", roomDTO.getCode())
                        .param("userId", "duplicate-nickname-member")
                        .param("nickname", "admin"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldAllowMemberToKeepOrChangeOwnNickname() throws Exception {
        RoomDTO roomDTO = createRoom("own-nickname-user", "admin");

        mockMvc.perform(withToken(post("/room/{code}/{userId}/nickname", roomDTO.getCode(), roomDTO.getUserId()), roomDTO)
                        .param("newNickname", "admin"))
                .andExpect(status().isNoContent());

        RoomDTO memberDTO = joinRoom(roomDTO.getCode(), "own-nickname-member", "member");

        mockMvc.perform(withToken(post("/room/{code}/{userId}/nickname", roomDTO.getCode(), "own-nickname-member"), memberDTO)
                        .param("newNickname", "admin"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldKeepMemberUntilLastSessionClosed() throws Exception {
        RoomDTO adminRoomDTO = createRoom("multi-session-admin", "admin");
        RoomDTO roomDTO = joinRoom(adminRoomDTO.getCode(), "multi-session-user", "multi");

        WebSocketSession session1 = connect(roomDTO);
        WebSocketSession session2 = connect(roomDTO);

        gameWebSocketHandler.afterConnectionClosed(session1, CloseStatus.NORMAL);

        assertNotNull(roomService.getRooms().get(roomDTO.getCode()).getMemberByUserId(roomDTO.getUserId()));

        gameWebSocketHandler.afterConnectionClosed(session2, CloseStatus.NORMAL);

        assertNull(roomService.getRooms().get(roomDTO.getCode()).getMemberByUserId(roomDTO.getUserId()));
    }

    @Test
    void shouldNotRestartGameForNonAdmin() throws Exception {
        RoomDTO roomDTO = createRoom("restart-admin", "admin");
        RoomDTO memberDTO = joinRoom(roomDTO.getCode(), "restart-member", "member");

        mockMvc.perform(withToken(post("/room/{code}/{userId}/player", roomDTO.getCode(), roomDTO.getUserId()), roomDTO)
                        .param("newStatus", "true"))
                .andExpect(status().isNoContent());
        mockMvc.perform(withToken(post("/room/{code}/start", roomDTO.getCode()), roomDTO)
                        .param("userId", roomDTO.getUserId())
                        .param("forceStart", "true"))
                .andExpect(status().isNoContent());

        assertTrue(roomService.getRooms().get(roomDTO.getCode()).isGameStarted());

        mockMvc.perform(withToken(post("/room/{code}/restart", roomDTO.getCode()), memberDTO)
                        .param("userId", memberDTO.getUserId()))
                .andExpect(status().isBadRequest());

        assertTrue(roomService.getRooms().get(roomDTO.getCode()).isGameStarted());
    }

    @Test
    void shouldAllowOnlyOneMemberWithSameNicknameUnderConcurrency() throws Exception {
        RoomDTO roomDTO = createRoom("concurrent-nickname-admin", "admin");
        int attempts = 8;

        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                String userId = "concurrent-nickname-user-" + i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        roomService.joinToRoom(roomDTO.getCode(), userId, "same-nickname");
                        return true;
                    } catch (RoomException e) {
                        return false;
                    }
                }));
            }
            start.countDown();

            long joined = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(5, TimeUnit.SECONDS)) {
                    joined++;
                }
            }
            assertEquals(1, joined);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldNotExceedMaxPlayersUnderConcurrency() throws Exception {
        RoomDTO roomDTO = createRoom("concurrent-players-admin", "admin");
        int members = 8;
        List<String> userIds = new ArrayList<>();
        for (int i = 0; i < members; i++) {
            String userId = "concurrent-player-user-" + i;
            joinRoom(roomDTO.getCode(), userId, "player-" + i);
            userIds.add(userId);
        }

        ExecutorService executor = Executors.newFixedThreadPool(members);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (String userId : userIds) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        roomService.changeMemberPlayerStatus(roomDTO.getCode(), userId, true);
                        return true;
                    } catch (RoomException e) {
                        return false;
                    }
                }));
            }
            start.countDown();

            long promoted = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(5, TimeUnit.SECONDS)) {
                    promoted++;
                }
            }
            assertEquals(new IntegrationTestConfiguration.GameImpl().getMaxPlayers(), promoted);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldStartGameOnlyOnceUnderConcurrency() throws Exception {
        RoomDTO roomDTO = createRoom("concurrent-start-admin", "admin");
        mockMvc.perform(withToken(post("/room/{code}/{userId}/player", roomDTO.getCode(), roomDTO.getUserId()), roomDTO)
                        .param("newStatus", "true"))
                .andExpect(status().isNoContent());

        int attempts = 8;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        roomService.startGame(roomDTO.getCode(), roomDTO.getUserId(), true);
                        return true;
                    } catch (RoomException e) {
                        return false;
                    }
                }));
            }
            start.countDown();

            long started = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(5, TimeUnit.SECONDS)) {
                    started++;
                }
            }
            assertEquals(1, started);
            assertTrue(roomService.getRooms().get(roomDTO.getCode()).isGameStarted());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReturnJsonErrorForRoomException() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/room/join/{code}", "NONE")
                        .param("userId", "json-error-user")
                        .param("nickname", "user"))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = mapper.readTree(mvcResult.getResponse().getContentAsString());
        assertEquals("The room doesn't exist", body.get("message").asText());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, mvcResult.getResponse().getContentType());
    }

    @Test
    void shouldRejectMutationWithoutMemberToken() throws Exception {
        RoomDTO roomDTO = createRoom("auth-missing", "admin");

        mockMvc.perform(post("/room/{code}/{userId}/player", roomDTO.getCode(), roomDTO.getUserId())
                        .param("newStatus", "true"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectMutationWithInvalidMemberToken() throws Exception {
        RoomDTO roomDTO = createRoom("auth-invalid", "admin");

        mockMvc.perform(post("/room/{code}/{userId}/player", roomDTO.getCode(), roomDTO.getUserId())
                        .header(RoomController.MEMBER_TOKEN_HEADER, "wrong-token")
                        .param("newStatus", "true"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectRejoinExistingMemberWithoutToken() throws Exception {
        RoomDTO roomDTO = createRoom("auth-rejoin", "admin");
        joinRoom(roomDTO.getCode(), "auth-rejoin-member", "member");

        mockMvc.perform(post("/room/join/{code}", roomDTO.getCode())
                        .param("userId", "auth-rejoin-member")
                        .param("nickname", "member"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldIssueWebSocketTicket() throws Exception {
        RoomDTO roomDTO = createRoom("ticket-user", "admin");

        MvcResult mvcResult = mockMvc.perform(withToken(
                        post("/room/{code}/ws-ticket", roomDTO.getCode()), roomDTO)
                        .param("userId", roomDTO.getUserId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        JsonNode body = mapper.readTree(mvcResult.getResponse().getContentAsByteArray());
        assertTrue(body.get("ticket").asText().length() > 0);
        assertTrue(body.get("expiresInMs").asLong() > 0);
    }

    @Test
    void shouldConsumeWebSocketTicketOnlyOnce() throws Exception {
        RoomDTO roomDTO = createRoom("ticket-once", "admin");
        MvcResult mvcResult = mockMvc.perform(withToken(
                        post("/room/{code}/ws-ticket", roomDTO.getCode()), roomDTO)
                        .param("userId", roomDTO.getUserId()))
                .andExpect(status().isOk())
                .andReturn();
        String ticket = mapper.readTree(mvcResult.getResponse().getContentAsByteArray())
                .get("ticket").asText();

        TokenHandshakeInterceptor interceptor = new TokenHandshakeInterceptor(gameWebSocketHandler);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/game-ws?ticket=" + ticket));

        Map<String, Object> attributes = new HashMap<>();
        assertTrue(interceptor.beforeHandshake(request, null, null, attributes));
        assertEquals(roomDTO.getCode(), attributes.get(TokenHandshakeInterceptor.CODE_ATTRIBUTE));
        assertEquals(roomDTO.getUserId(), attributes.get(TokenHandshakeInterceptor.USER_ID_ATTRIBUTE));

        assertFalse(interceptor.beforeHandshake(request, null, null, new HashMap<>()));
    }

    private static MockHttpServletRequestBuilder withToken(
            MockHttpServletRequestBuilder builder, RoomDTO roomDTO) {
        return builder.header(RoomController.MEMBER_TOKEN_HEADER, roomDTO.getWsToken());
    }

    private RoomDTO createRoom(String userId, String nickname) throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/room/create")
                        .param("userId", userId)
                        .param("nickname", nickname))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        return mapper.readValue(mvcResult.getResponse().getContentAsByteArray(), RoomDTO.class);
    }

    private RoomDTO joinRoom(String code, String userId, String nickname) throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/room/join/{code}", code)
                        .param("userId", userId)
                        .param("nickname", nickname))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        return mapper.readValue(mvcResult.getResponse().getContentAsByteArray(), RoomDTO.class);
    }

    private WebSocketSession connect(RoomDTO roomDTO) throws Exception {
        StandardWebSocketSession webSocketSession = new StandardWebSocketSession(null, null, null, null);
        WsSession mockWsSession = mock(WsSession.class);
        when(mockWsSession.getBasicRemote()).thenReturn(mock(RemoteEndpoint.Basic.class));
        webSocketSession.initializeNativeSession(mockWsSession);
        webSocketSession.getAttributes().put(TokenHandshakeInterceptor.CODE_ATTRIBUTE, roomDTO.getCode());
        webSocketSession.getAttributes().put(TokenHandshakeInterceptor.USER_ID_ATTRIBUTE, roomDTO.getUserId());
        gameWebSocketHandler.afterConnectionEstablished(webSocketSession);
        return webSocketSession;
    }
}