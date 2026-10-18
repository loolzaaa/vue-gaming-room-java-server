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

    private final TokenHandshakeInterceptor interceptor = new TokenHandshakeInterceptor();

    @Test
    void shouldExtractAndDecodeToken() {
        Map<String, Object> attributes = handshake("http://localhost/game-ws?token=user%3Aroom-token");

        assertEquals("user:room-token", attributes.get("token"));
    }

    @Test
    void shouldRejectMissingToken() {
        assertTrue(handshake("http://localhost/game-ws").isEmpty());
        assertTrue(handshake("http://localhost/game-ws?token=").isEmpty());
        assertTrue(handshake("http://localhost/game-ws?tokenExtra=user%3Atoken").isEmpty());
    }

    @Test
    void shouldRejectMalformedToken() {
        assertTrue(handshake("http://localhost/game-ws?token=user").isEmpty());
        assertTrue(handshake("http://localhost/game-ws?token=%3Aroom").isEmpty());
    }

    private Map<String, Object> handshake(String url) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(url));

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(request, null, null, attributes);
        return accepted ? attributes : Map.of();
    }
}
