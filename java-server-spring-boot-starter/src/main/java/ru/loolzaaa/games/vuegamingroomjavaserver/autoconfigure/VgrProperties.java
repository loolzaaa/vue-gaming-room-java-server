package ru.loolzaaa.games.vuegamingroomjavaserver.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.settings.ServerSettings;

import java.time.Duration;
import java.util.List;

/**
 * Конфигурационные свойства сервера с префиксом {@code vgr}.
 */
@ConfigurationProperties(prefix = "vgr")
public class VgrProperties {

    private final Room room = new Room();
    private final WebSocket webSocket = new WebSocket();
    private final Http http = new Http();

    public Room getRoom() {
        return room;
    }

    public WebSocket getWebSocket() {
        return webSocket;
    }

    public Http getHttp() {
        return http;
    }

    public ServerSettings toServerSettings() {
        if (room.cleanInitialDelayMs < 0) {
            throw new IllegalArgumentException("vgr.room.clean-initial-delay-ms must not be negative");
        }
        if (room.cleanIntervalMs < 1) {
            throw new IllegalArgumentException("vgr.room.clean-interval-ms must be positive");
        }
        return new ServerSettings(
                new ServerSettings.RoomSettings(
                        room.maxMembers,
                        room.inactiveTimeout,
                        room.maxNicknameLength),
                new ServerSettings.WebSocketSettings(
                        webSocket.endpointPath,
                        webSocket.allowedOrigins,
                        toIntBytes(webSocket.maxMessageSize, "max-message-size"),
                        webSocket.idleTimeout,
                        webSocket.sendTimeLimit,
                        toIntBytes(webSocket.sendBufferSize, "send-buffer-size"),
                        webSocket.maxSessionsPerUser,
                        webSocket.rateLimitPerSecond,
                        webSocket.rateLimitBurst,
                        webSocket.ticketTtl),
                new ServerSettings.HttpSettings(
                        http.rateLimitPerSecond,
                        http.rateLimitBurst));
    }

    private static int toIntBytes(DataSize dataSize, String propertyName) {
        long bytes = dataSize.toBytes();
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "vgr.websocket." + propertyName + " must not exceed " + Integer.MAX_VALUE + " bytes");
        }
        return (int) bytes;
    }

    public static class Room {

        private int maxMembers = 200;
        private Duration inactiveTimeout = Duration.ofMinutes(60);
        private int maxNicknameLength = 32;
        private long cleanInitialDelayMs = 10_000;
        private long cleanIntervalMs = 60_000;

        public int getMaxMembers() {
            return maxMembers;
        }

        public void setMaxMembers(int maxMembers) {
            this.maxMembers = maxMembers;
        }

        public Duration getInactiveTimeout() {
            return inactiveTimeout;
        }

        public void setInactiveTimeout(Duration inactiveTimeout) {
            this.inactiveTimeout = inactiveTimeout;
        }

        public int getMaxNicknameLength() {
            return maxNicknameLength;
        }

        public void setMaxNicknameLength(int maxNicknameLength) {
            this.maxNicknameLength = maxNicknameLength;
        }

        public long getCleanInitialDelayMs() {
            return cleanInitialDelayMs;
        }

        public void setCleanInitialDelayMs(long cleanInitialDelayMs) {
            this.cleanInitialDelayMs = cleanInitialDelayMs;
        }

        public long getCleanIntervalMs() {
            return cleanIntervalMs;
        }

        public void setCleanIntervalMs(long cleanIntervalMs) {
            this.cleanIntervalMs = cleanIntervalMs;
        }
    }

    public static class WebSocket {

        private String endpointPath = "/game-ws";
        private List<String> allowedOrigins = List.of("*");
        private DataSize maxMessageSize = DataSize.ofKilobytes(64);
        private Duration idleTimeout = Duration.ofSeconds(120);
        private Duration sendTimeLimit = Duration.ofSeconds(10);
        private DataSize sendBufferSize = DataSize.ofKilobytes(512);
        private int maxSessionsPerUser = 3;
        private int rateLimitPerSecond = 30;
        private int rateLimitBurst = 60;
        private Duration ticketTtl = Duration.ofSeconds(30);

        public String getEndpointPath() {
            return endpointPath;
        }

        public void setEndpointPath(String endpointPath) {
            this.endpointPath = endpointPath;
        }

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public DataSize getMaxMessageSize() {
            return maxMessageSize;
        }

        public void setMaxMessageSize(DataSize maxMessageSize) {
            this.maxMessageSize = maxMessageSize;
        }

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }

        public Duration getSendTimeLimit() {
            return sendTimeLimit;
        }

        public void setSendTimeLimit(Duration sendTimeLimit) {
            this.sendTimeLimit = sendTimeLimit;
        }

        public DataSize getSendBufferSize() {
            return sendBufferSize;
        }

        public void setSendBufferSize(DataSize sendBufferSize) {
            this.sendBufferSize = sendBufferSize;
        }

        public int getMaxSessionsPerUser() {
            return maxSessionsPerUser;
        }

        public void setMaxSessionsPerUser(int maxSessionsPerUser) {
            this.maxSessionsPerUser = maxSessionsPerUser;
        }

        public int getRateLimitPerSecond() {
            return rateLimitPerSecond;
        }

        public void setRateLimitPerSecond(int rateLimitPerSecond) {
            this.rateLimitPerSecond = rateLimitPerSecond;
        }

        public int getRateLimitBurst() {
            return rateLimitBurst;
        }

        public void setRateLimitBurst(int rateLimitBurst) {
            this.rateLimitBurst = rateLimitBurst;
        }

        public Duration getTicketTtl() {
            return ticketTtl;
        }

        public void setTicketTtl(Duration ticketTtl) {
            this.ticketTtl = ticketTtl;
        }
    }

    public static class Http {

        private int rateLimitPerSecond = 20;
        private int rateLimitBurst = 40;

        public int getRateLimitPerSecond() {
            return rateLimitPerSecond;
        }

        public void setRateLimitPerSecond(int rateLimitPerSecond) {
            this.rateLimitPerSecond = rateLimitPerSecond;
        }

        public int getRateLimitBurst() {
            return rateLimitBurst;
        }

        public void setRateLimitBurst(int rateLimitBurst) {
            this.rateLimitBurst = rateLimitBurst;
        }
    }
}
