package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.settings;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Настройки сервера с безопасными значениями по умолчанию.
 * <p>
 * Ядро не зависит от Spring Boot, поэтому настройки передаются обычным объектом.
 * Стартер связывает их с конфигурационными свойствами {@code vgr.*}.
 */
public record ServerSettings(RoomSettings room, WebSocketSettings webSocket, HttpSettings http) {

    public ServerSettings {
        Objects.requireNonNull(room, "room settings must not be null");
        Objects.requireNonNull(webSocket, "webSocket settings must not be null");
        Objects.requireNonNull(http, "http settings must not be null");
    }

    public ServerSettings(RoomSettings room, WebSocketSettings webSocket) {
        this(room, webSocket, HttpSettings.defaults());
    }

    public static ServerSettings defaults() {
        return new ServerSettings(
                RoomSettings.defaults(),
                WebSocketSettings.defaults(),
                HttpSettings.defaults());
    }

    public record RoomSettings(int maxMembers, Duration inactiveTimeout, int maxNicknameLength) {

        public RoomSettings {
            if (maxMembers < 1) {
                throw new IllegalArgumentException("maxMembers must be positive");
            }
            Objects.requireNonNull(inactiveTimeout, "inactiveTimeout must not be null");
            if (inactiveTimeout.isZero() || inactiveTimeout.isNegative()) {
                throw new IllegalArgumentException("inactiveTimeout must be positive");
            }
            if (maxNicknameLength < 1) {
                throw new IllegalArgumentException("maxNicknameLength must be positive");
            }
        }

        public static RoomSettings defaults() {
            return new RoomSettings(200, Duration.ofMinutes(60), 32);
        }
    }

    public record WebSocketSettings(
            String endpointPath,
            List<String> allowedOrigins,
            int maxMessageSize,
            Duration idleTimeout,
            Duration sendTimeLimit,
            int sendBufferSize,
            int maxSessionsPerUser,
            int rateLimitPerSecond,
            int rateLimitBurst,
            Duration ticketTtl) {

        public WebSocketSettings {
            if (endpointPath == null || endpointPath.isBlank()) {
                throw new IllegalArgumentException("endpointPath must not be blank");
            }
            endpointPath = endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath;

            Objects.requireNonNull(allowedOrigins, "allowedOrigins must not be null");
            if (allowedOrigins.isEmpty()) {
                throw new IllegalArgumentException("allowedOrigins must not be empty");
            }
            allowedOrigins = List.copyOf(allowedOrigins);

            if (maxMessageSize < 1) {
                throw new IllegalArgumentException("maxMessageSize must be positive");
            }
            Objects.requireNonNull(idleTimeout, "idleTimeout must not be null");
            if (idleTimeout.isNegative()) {
                throw new IllegalArgumentException("idleTimeout must not be negative");
            }
            Objects.requireNonNull(sendTimeLimit, "sendTimeLimit must not be null");
            if (sendTimeLimit.isZero() || sendTimeLimit.isNegative()) {
                throw new IllegalArgumentException("sendTimeLimit must be positive");
            }
            if (sendTimeLimit.toMillis() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("sendTimeLimit is too large");
            }
            if (sendBufferSize < 1) {
                throw new IllegalArgumentException("sendBufferSize must be positive");
            }
            if (maxSessionsPerUser < 1) {
                throw new IllegalArgumentException("maxSessionsPerUser must be positive");
            }
            if (rateLimitPerSecond < 1) {
                throw new IllegalArgumentException("rateLimitPerSecond must be positive");
            }
            if (rateLimitBurst < 1) {
                throw new IllegalArgumentException("rateLimitBurst must be positive");
            }
            Objects.requireNonNull(ticketTtl, "ticketTtl must not be null");
            if (ticketTtl.isZero() || ticketTtl.isNegative()) {
                throw new IllegalArgumentException("ticketTtl must be positive");
            }
        }

        public static WebSocketSettings defaults() {
            return new WebSocketSettings(
                    "/game-ws",
                    List.of("*"),
                    64 * 1024,
                    Duration.ofSeconds(120),
                    Duration.ofSeconds(10),
                    512 * 1024,
                    3,
                    30,
                    60,
                    Duration.ofSeconds(30));
        }
    }

    public record HttpSettings(int rateLimitPerSecond, int rateLimitBurst) {

        public HttpSettings {
            if (rateLimitPerSecond < 1) {
                throw new IllegalArgumentException("rateLimitPerSecond must be positive");
            }
            if (rateLimitBurst < 1) {
                throw new IllegalArgumentException("rateLimitBurst must be positive");
            }
        }

        public static HttpSettings defaults() {
            return new HttpSettings(20, 40);
        }
    }
}
