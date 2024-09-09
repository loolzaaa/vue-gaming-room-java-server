package ru.loolzaaa.games.vuegamingroomjavaserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.RemoteEndpoint;
import org.apache.tomcat.websocket.WsSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import ru.loolzaaa.games.vuegamingroomjavaserver.autoconfigure.JavaServerAutoConfiguration;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.controller.RoomController;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.dto.RoomDTO;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.RoomService;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.GameWebSocketHandler;

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

        mockMvc.perform(post("/room/{code}/{userId}/player", roomDTO1.getCode(), roomDTO1.getUserId())
                        .param("newStatus", "true"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/room/{code}/{userId}/player", roomDTO2.getCode(), roomDTO2.getUserId())
                        .param("newStatus", "true"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/room/{code}/start", roomDTO1.getCode())
                        .param("userId", roomDTO1.getUserId())
                        .param("forceStart", "true"))
                .andExpect(status().isNoContent());

        gameWebSocketHandler.afterConnectionClosed(webSocketSession2, CloseStatus.GOING_AWAY);

        mockMvc.perform(post("/room/join/{code}", roomDTO1.getCode())
                        .param("userId", "test-id-2")
                        .param("nickname", "second"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }
}