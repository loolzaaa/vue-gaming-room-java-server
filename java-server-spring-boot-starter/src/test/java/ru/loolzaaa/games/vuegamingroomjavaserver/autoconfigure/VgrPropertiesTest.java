package ru.loolzaaa.games.vuegamingroomjavaserver.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.settings.ServerSettings;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VgrPropertiesTest {

    @Test
    void shouldProvideDefaults() {
        VgrProperties properties = bind(Map.of());

        assertThat(properties.getRoom().getMaxMembers()).isEqualTo(200);
        assertThat(properties.getRoom().getInactiveTimeout()).isEqualTo(Duration.ofMinutes(60));
        assertThat(properties.getRoom().getMaxNicknameLength()).isEqualTo(32);
        assertThat(properties.getRoom().getCleanInitialDelayMs()).isEqualTo(10_000);
        assertThat(properties.getRoom().getCleanIntervalMs()).isEqualTo(60_000);
        assertThat(properties.getWebSocket().getEndpointPath()).isEqualTo("/game-ws");
        assertThat(properties.getWebSocket().getAllowedOrigins()).containsExactly("*");
        assertThat(properties.getWebSocket().getMaxMessageSize().toBytes()).isEqualTo(64 * 1024);
        assertThat(properties.getWebSocket().getIdleTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(properties.getWebSocket().getSendTimeLimit()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getWebSocket().getSendBufferSize().toBytes()).isEqualTo(512 * 1024);
        assertThat(properties.getWebSocket().getMaxSessionsPerUser()).isEqualTo(3);
        assertThat(properties.getWebSocket().getRateLimitPerSecond()).isEqualTo(30);
        assertThat(properties.getWebSocket().getRateLimitBurst()).isEqualTo(60);
        assertThat(properties.getWebSocket().getTicketTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getWebSocket().getSendQueueCapacity()).isEqualTo(64);
        assertThat(properties.getHttp().getRateLimitPerSecond()).isEqualTo(20);
        assertThat(properties.getHttp().getRateLimitBurst()).isEqualTo(40);
    }

    @Test
    void shouldBindOverridesAndBuildServerSettings() {
        VgrProperties properties = bind(Map.ofEntries(
                Map.entry("vgr.room.max-members", "10"),
                Map.entry("vgr.room.inactive-timeout", "5m"),
                Map.entry("vgr.room.max-nickname-length", "12"),
                Map.entry("vgr.room.clean-initial-delay-ms", "1000"),
                Map.entry("vgr.room.clean-interval-ms", "2000"),
                Map.entry("vgr.websocket.endpoint-path", "custom-ws"),
                Map.entry("vgr.websocket.allowed-origins", "https://a.example,https://b.example"),
                Map.entry("vgr.websocket.max-message-size", "32KB"),
                Map.entry("vgr.websocket.idle-timeout", "90s"),
                Map.entry("vgr.websocket.send-time-limit", "5s"),
                Map.entry("vgr.websocket.send-buffer-size", "256KB"),
                Map.entry("vgr.websocket.max-sessions-per-user", "5"),
                Map.entry("vgr.websocket.rate-limit-per-second", "10"),
                Map.entry("vgr.websocket.rate-limit-burst", "20"),
                Map.entry("vgr.websocket.ticket-ttl", "45s"),
                Map.entry("vgr.websocket.send-queue-capacity", "128"),
                Map.entry("vgr.http.rate-limit-per-second", "7"),
                Map.entry("vgr.http.rate-limit-burst", "14")));

        ServerSettings settings = properties.toServerSettings();

        assertThat(settings.room().maxMembers()).isEqualTo(10);
        assertThat(settings.room().inactiveTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(settings.room().maxNicknameLength()).isEqualTo(12);
        assertThat(settings.webSocket().endpointPath()).isEqualTo("/custom-ws");
        assertThat(settings.webSocket().allowedOrigins())
                .containsExactly("https://a.example", "https://b.example");
        assertThat(settings.webSocket().maxMessageSize()).isEqualTo(32 * 1024);
        assertThat(settings.webSocket().idleTimeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(settings.webSocket().sendTimeLimit()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.webSocket().sendBufferSize()).isEqualTo(256 * 1024);
        assertThat(settings.webSocket().maxSessionsPerUser()).isEqualTo(5);
        assertThat(settings.webSocket().rateLimitPerSecond()).isEqualTo(10);
        assertThat(settings.webSocket().rateLimitBurst()).isEqualTo(20);
        assertThat(settings.webSocket().ticketTtl()).isEqualTo(Duration.ofSeconds(45));
        assertThat(settings.webSocket().sendQueueCapacity()).isEqualTo(128);
        assertThat(settings.http().rateLimitPerSecond()).isEqualTo(7);
        assertThat(settings.http().rateLimitBurst()).isEqualTo(14);
    }

    @Test
    void shouldRejectMessageSizeThatDoesNotFitInt() {
        VgrProperties properties = bind(Map.of("vgr.websocket.max-message-size", "3GB"));

        assertThatThrownBy(properties::toServerSettings)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-message-size");
    }

    private VgrProperties bind(Map<String, String> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        return binder.bind("vgr", Bindable.of(VgrProperties.class)).orElseGet(VgrProperties::new);
    }
}
