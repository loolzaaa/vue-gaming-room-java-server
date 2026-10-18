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

    public static final String TOKEN_ATTRIBUTE = "token";
    public static final String CODE_ATTRIBUTE = "code";
    public static final String USER_ID_ATTRIBUTE = "userId";

    private static final String TICKET_PARAMETER = "ticket";
    private static final String TOKEN_PARAMETER = "token";

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

        GameWebSocketHandler.MemberToken tokenInfo = resolveTicket(queryParams);
        if (tokenInfo == null) {
            tokenInfo = resolveLegacyToken(queryParams, attributes);
        }
        if (tokenInfo == null) {
            log.debug("Websocket handshake rejected for session");
            return false;
        }

        attributes.put(CODE_ATTRIBUTE, tokenInfo.code());
        attributes.put(USER_ID_ATTRIBUTE, tokenInfo.userId());
        log.debug("Websocket handshake successful for user {} in room {}",
                tokenInfo.userId(), tokenInfo.code());
        return true;
    }

    private GameWebSocketHandler.MemberToken resolveTicket(MultiValueMap<String, String> queryParams) {
        String ticket = queryParams.getFirst(TICKET_PARAMETER);
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        try {
            ticket = URLDecoder.decode(ticket, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("Websocket handshake rejected: invalid ticket encoding");
            return null;
        }
        return gameWebSocketHandler.consumeWsTicket(ticket);
    }

    private GameWebSocketHandler.MemberToken resolveLegacyToken(
            MultiValueMap<String, String> queryParams, Map<String, Object> attributes) {
        String token = queryParams.getFirst(TOKEN_PARAMETER);
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            token = URLDecoder.decode(token, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("Websocket handshake rejected: invalid token encoding");
            return null;
        }
        int separator = token.indexOf(':');
        if (separator <= 0 || separator == token.length() - 1) {
            log.warn("Websocket handshake rejected: invalid token format");
            return null;
        }
        String userId = token.substring(0, separator);
        String secret = token.substring(separator + 1);
        GameWebSocketHandler.MemberToken tokenInfo = gameWebSocketHandler.resolveMemberToken(secret);
        if (tokenInfo == null || !tokenInfo.userId().equals(userId)) {
            return null;
        }
        attributes.put(TOKEN_ATTRIBUTE, token);
        return tokenInfo;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
    }
}
