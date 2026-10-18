package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class TokenHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TokenHandshakeInterceptor.class);

    public static final String CODE_ATTRIBUTE = "code";
    public static final String USER_ID_ATTRIBUTE = "userId";

    private static final String TICKET_PARAMETER = "ticket";

    private final GameWebSocketHandler gameWebSocketHandler;

    public TokenHandshakeInterceptor(GameWebSocketHandler gameWebSocketHandler) {
        this.gameWebSocketHandler = gameWebSocketHandler;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        MultiValueMap<String, String> queryParams;
        try {
            queryParams = UriComponentsBuilder.fromUri(request.getURI())
                    .build()
                    .getQueryParams();
        } catch (IllegalArgumentException e) {
            log.warn("Unable to parse websocket handshake query: {}", e.getLocalizedMessage());
            return false;
        }

        String ticket = queryParams.getFirst(TICKET_PARAMETER);
        if (ticket == null || ticket.isBlank()) {
            log.debug("Websocket handshake rejected: ticket is missing");
            return false;
        }
        try {
            ticket = URLDecoder.decode(ticket, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("Websocket handshake rejected: invalid ticket encoding");
            return false;
        }

        GameWebSocketHandler.WsIdentity identity = gameWebSocketHandler.consumeWsTicket(ticket);
        if (identity == null) {
            log.debug("Websocket handshake rejected: invalid or expired ticket");
            return false;
        }

        attributes.put(CODE_ATTRIBUTE, identity.code());
        attributes.put(USER_ID_ATTRIBUTE, identity.userId());
        log.debug("Websocket handshake successful for user {} in room {}",
                identity.userId(), identity.code());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
    }
}
