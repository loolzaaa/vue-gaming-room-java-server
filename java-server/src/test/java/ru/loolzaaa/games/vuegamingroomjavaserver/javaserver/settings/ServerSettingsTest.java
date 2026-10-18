package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.settings;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerSettingsTest {

    @Test
    void shouldProvideDefaults() {
        ServerSettings settings = ServerSettings.defaults();

        assertThat(settings.room().maxMembers()).isEqualTo(200);
        assertThat(settings.room().inactiveTimeout()).isEqualTo(Duration.ofMinutes(60));
        assertThat(settings.room().maxNicknameLength()).isEqualTo(32);
        assertThat(settings.webSocket().endpointPath()).isEqualTo("/game-ws");
        assertThat(settings.webSocket().allowedOrigins()).containsExactly("*");
        assertThat(settings.webSocket().maxMessageSize()).isEqualTo(64 * 1024);
        assertThat(settings.webSocket().idleTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(settings.webSocket().sendTimeLimit()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.webSocket().sendBufferSize()).isEqualTo(512 * 1024);
    }

    @Test
    void shouldNormalizeEndpointPath() {
        ServerSettings.WebSocketSettings settings = new ServerSettings.WebSocketSettings(
                "custom-ws", List.of("https://example.com"), 1024,
                Duration.ofSeconds(30), Duration.ofSeconds(5), 2048);

        assertThat(settings.endpointPath()).isEqualTo("/custom-ws");
    }

    @Test
    void shouldRejectInvalidRoomSettings() {
        assertThatThrownBy(() -> new ServerSettings.RoomSettings(0, Duration.ofMinutes(1), 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerSettings.RoomSettings(10, Duration.ZERO, 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerSettings.RoomSettings(10, Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidWebSocketSettings() {
        assertThatThrownBy(() -> new ServerSettings.WebSocketSettings(
                " ", List.of("*"), 1024, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerSettings.WebSocketSettings(
                "/ws", List.of(), 1024, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerSettings.WebSocketSettings(
                "/ws", List.of("*"), 0, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerSettings.WebSocketSettings(
                "/ws", List.of("*"), 1024, Duration.ofSeconds(-1), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerSettings.WebSocketSettings(
                "/ws", List.of("*"), 1024, Duration.ofSeconds(1), Duration.ZERO, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerSettings.WebSocketSettings(
                "/ws", List.of("*"), 1024, Duration.ofSeconds(1), Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
