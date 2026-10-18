package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Room;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.RoomService;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.settings.ServerSettings;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.GameWebSocketHandler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class RoomCleanTask {

    private static final Logger log = LoggerFactory.getLogger(RoomCleanTask.class);

    private final RoomService<?> roomService;

    private final GameWebSocketHandler gameWebSocketHandler;

    private final Duration inactiveTimeout;

    public RoomCleanTask(RoomService<?> roomService, GameWebSocketHandler gameWebSocketHandler) {
        this(roomService, gameWebSocketHandler, ServerSettings.defaults());
    }

    public RoomCleanTask(RoomService<?> roomService,
                         GameWebSocketHandler gameWebSocketHandler,
                         ServerSettings settings) {
        this.roomService = roomService;
        this.gameWebSocketHandler = gameWebSocketHandler;
        this.inactiveTimeout = settings.room().inactiveTimeout();
        log.info("Room clean task scheduled");
    }

    @Scheduled(
            initialDelayString = "${vgr.room.clean-initial-delay-ms:10000}",
            fixedDelayString = "${vgr.room.clean-interval-ms:60000}")
    public void clean() {
        LocalDateTime now = LocalDateTime.now();
        List<String> toDeleteRooms = new ArrayList<>();
        for (Room<?> room : List.copyOf(roomService.getRooms().values())) {
            String code = room.getCode();
            synchronized (room) {
                // Активность могла обновиться, пока мы ждали блокировку комнаты,
                // поэтому проверяем её повторно и удаляем комнату атомарно с проверкой
                if (!room.getLastActivity().plus(inactiveTimeout).isBefore(now)) {
                    continue;
                }
                if (roomService.getRooms().remove(code, room)) {
                    toDeleteRooms.add(code);
                    log.debug("Removed room {}. Last activity: {}", code, room.getLastActivity());
                }
            }
        }
        if (!toDeleteRooms.isEmpty()) {
            for (String code : toDeleteRooms) {
                gameWebSocketHandler.removeSessionsListForRoom(code);
            }
            log.info("Removed {} rooms because of inactivity: {}", toDeleteRooms.size(), toDeleteRooms);
        }
    }
}
