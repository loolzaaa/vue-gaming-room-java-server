package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenHandshakeInterceptorTests {

    private final GameWebSocketHandler handler = mock(GameWebSocketHandler.class);
    private final TokenHandshakeInterceptor interceptor = new TokenHandshakeInterceptor(handler);

    @Test
    void shouldAcceptValidTicket() {
        when(handler.consumeWsTicket("ticket-1"))
                .thenReturn(new GameWebSocketHandler.MemberToken("ABCD", "user-1"));

        Map<String, Object> attributes = handshake("http://localhost/game-ws?ticket=ticket-1");

        assertEquals("ABCD", attributes.get(TokenHandshakeInterceptor.CODE_ATTRIBUTE));
        assertEquals("user-1", attributes.get(TokenHandshakeInterceptor.USER_ID_ATTRIBUTE));
    }

    @Test
    void shouldAcceptValidLegacyToken() {
        when(handler.resolveMemberToken("secret"))
                .thenReturn(new GameWebSocketHandler.MemberToken("ABCD", "user-1"));

        Map<String, Object> attributes = handshake("http://localhost/game-ws?token=user-1%3Asecret");

        assertEquals("ABCD", attributes.get(TokenHandshakeInterceptor.CODE_ATTRIBUTE));
        assertEquals("user-1", attributes.get(TokenHandshakeInterceptor.USER_ID_ATTRIBUTE));
    }

    @Test
    void shouldRejectMissingOrInvalidCredentials() {
        assertTrue(handshake("http://localhost/game-ws").isEmpty());
        assertTrue(handshake("http://localhost/game-ws?ticket=unknown").isEmpty());
        assertTrue(handshake("http://localhost/game-ws?token=user-1").isEmpty());
        assertTrue(handshake("http://localhost/game-ws?token=user-1%3Aunknown").isEmpty());
    }

    @Test
    void shouldRejectTokenForAnotherUser() {
        when(handler.resolveMemberToken("secret"))
                .thenReturn(new GameWebSocketHandler.MemberToken("ABCD", "user-2"));

        assertTrue(handshake("http://localhost/game-ws?token=user-1%3Asecret").isEmpty());
    }

    private Map<String, Object> handshake(String url) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(url));

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(request, null, null, attributes);
        return accepted ? attributes : Map.of();
    }
}
