package ru.loolzaaa.games.vuegamingroomjavaserver.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.task.RoomCleanTask;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.GameWebSocketHandler;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.TokenHandshakeInterceptor;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.WebSocketEventProcessor;

@AutoConfiguration
@EnableScheduling
public class JavaServerAutoConfiguration {

    private final static Logger log = LogManager.getLogger(JavaServerAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    RoomService roomService(GameService<? extends Game> gameService) {
        return new RoomService(gameService);
    }

    @Bean
    @ConditionalOnMissingBean
    GameWebSocketHandler gameWebSocketHandler(ObjectMapper mapper,
                                              RoomService roomService,
                                              WebSocketEventProcessor webSocketEventProcessor) {
        return new GameWebSocketHandler(mapper, roomService, webSocketEventProcessor);
    }

    @Bean
    @ConditionalOnMissingBean
    GameController gameController(GameService<?> gameService) {
        return new GameController(gameService);
    }

    @Bean
    @ConditionalOnMissingBean
    RoomController roomController(GameWebSocketHandler gameWebSocketHandler, RoomService roomService) {
        return new RoomController(gameWebSocketHandler, roomService);
    }

    @Bean
    @ConditionalOnMissingBean
    RoomCleanTask roomCleanTask(GameWebSocketHandler gameWebSocketHandler, RoomService roomService) {
        return new RoomCleanTask(roomService, gameWebSocketHandler);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSocket
    static class WebSocketAutoConfiguration implements WebSocketConfigurer {

        private final WebSocketHandler webSocketHandler;

        WebSocketAutoConfiguration(WebSocketHandler webSocketHandler) {
            this.webSocketHandler = webSocketHandler;
        }

        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            final String endpointPath = "/game-ws";
            registry
                    .addHandler(webSocketHandler, endpointPath)
                    .addInterceptors(new TokenHandshakeInterceptor())
                    .setAllowedOriginPatterns("*");
            log.info("Registered websocket endpoint: {}", endpointPath);
        }
    }
}
