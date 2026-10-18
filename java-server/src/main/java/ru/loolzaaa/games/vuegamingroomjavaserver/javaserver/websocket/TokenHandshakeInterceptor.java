package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class TokenHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TokenHandshakeInterceptor.class);

    public static final String TOKEN_ATTRIBUTE = "token";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String token;
        try {
            token = UriComponentsBuilder.fromUri(request.getURI())
                    .build()
                    .getQueryParams()
                    .getFirst("token");
        } catch (IllegalArgumentException e) {
            log.warn("Unable to parse websocket handshake query: {}", e.getLocalizedMessage());
            return false;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            // query params могут приходить в URL-encoded виде
            token = URLDecoder.decode(token, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("Websocket handshake rejected: invalid token encoding");
            return false;
        }
        int separator = token.indexOf(':');
        if (separator <= 0 || separator == token.length() - 1) {
            log.warn("Websocket handshake rejected: invalid token format");
            return false;
        }
        attributes.put(TOKEN_ATTRIBUTE, token);
        log.debug("Websocket handshake successful");
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
    }
}
