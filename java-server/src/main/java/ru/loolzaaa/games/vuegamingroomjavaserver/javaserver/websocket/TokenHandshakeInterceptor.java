package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

public class TokenHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LogManager.getLogger(TokenHandshakeInterceptor.class);

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token")) {
                    String token = param.split("=")[1];
                    attributes.put("token", token);
                    log.debug("Websocket handshake successful with token: {}", token);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
    }
}
