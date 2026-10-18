package ru.loolzaaa.games.vuegamingroomjavaserver.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.controller.GameController;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.controller.RoomController;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Game;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.GameService;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.RoomService;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.settings.ServerSettings;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.task.RoomCleanTask;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.GameWebSocketHandler;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.TokenHandshakeInterceptor;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.WebSocketEventProcessor;
import ru.loolzaaa.games.vuegamingroomjavaserver.security.HttpRateLimitFilter;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(VgrProperties.class)
@ConditionalOnClass({WebSocketHandler.class, WebSocketConfigurer.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class JavaServerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JavaServerAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    ServerSettings serverSettings(VgrProperties properties) {
        return properties.toServerSettings();
    }

    @Bean
    @ConditionalOnMissingBean
    RoomService roomService(GameService<? extends Game> gameService, ServerSettings settings) {
        return new RoomService(gameService, settings);
    }

    @Bean
    @ConditionalOnMissingBean
    GameWebSocketHandler gameWebSocketHandler(ObjectMapper mapper,
                                              RoomService roomService,
                                              WebSocketEventProcessor webSocketEventProcessor,
                                              ServerSettings settings) {
        return new GameWebSocketHandler(mapper, roomService, webSocketEventProcessor, settings);
    }

    @Bean
    @ConditionalOnMissingBean
    GameController gameController(GameService<?> gameService, ServerSettings settings) {
        return new GameController(gameService, settings);
    }

    @Bean
    @ConditionalOnMissingBean
    RoomController roomController(GameWebSocketHandler gameWebSocketHandler, RoomService roomService) {
        return new RoomController(gameWebSocketHandler, roomService);
    }

    @Bean
    @ConditionalOnMissingBean
    RoomCleanTask roomCleanTask(GameWebSocketHandler gameWebSocketHandler,
                                RoomService roomService,
                                ServerSettings settings) {
        return new RoomCleanTask(roomService, gameWebSocketHandler, settings);
    }

    @Bean
    @ConditionalOnMissingBean(HttpRateLimitFilter.class)
    FilterRegistrationBean<HttpRateLimitFilter> httpRateLimitFilter(ServerSettings settings) {
        FilterRegistrationBean<HttpRateLimitFilter> registration = new FilterRegistrationBean<>(
                new HttpRateLimitFilter(
                        settings.http().rateLimitPerSecond(),
                        settings.http().rateLimitBurst()));
        registration.addUrlPatterns("/room", "/room/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSocket
    static class WebSocketAutoConfiguration implements WebSocketConfigurer {

        private final GameWebSocketHandler webSocketHandler;
        private final VgrProperties properties;

        WebSocketAutoConfiguration(GameWebSocketHandler webSocketHandler, VgrProperties properties) {
            this.webSocketHandler = webSocketHandler;
            this.properties = properties;
        }

        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            final String endpointPath = properties.getWebSocket().getEndpointPath();
            final String[] allowedOrigins = properties.getWebSocket().getAllowedOrigins()
                    .toArray(String[]::new);
            registry
                    .addHandler(webSocketHandler, endpointPath)
                    .addInterceptors(new TokenHandshakeInterceptor(webSocketHandler))
                    .setAllowedOriginPatterns(allowedOrigins);
            log.info("Registered websocket endpoint: {}", endpointPath);
        }
    }
}
