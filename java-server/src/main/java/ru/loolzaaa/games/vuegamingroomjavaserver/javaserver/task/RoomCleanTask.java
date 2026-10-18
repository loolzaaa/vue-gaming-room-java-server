package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Room;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.RoomService;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.GameWebSocketHandler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RoomCleanTask {

    private static final Logger log = LogManager.getLogger(RoomCleanTask.class);

    private static final int INACTIVE_TIME = 60;

    private final RoomService roomService;

    private final GameWebSocketHandler gameWebSocketHandler;

    public RoomCleanTask(RoomService roomService, GameWebSocketHandler gameWebSocketHandler) {
        this.roomService = roomService;
        this.gameWebSocketHandler = gameWebSocketHandler;
        log.info("Room clean task scheduled");
    }

    @Scheduled(initialDelay = 10, fixedDelay = 60, timeUnit = TimeUnit.SECONDS)
    public void clean() {
        LocalDateTime now = LocalDateTime.now();
        List<String> toDeleteRooms = new ArrayList<>();
        for (Room<?> room : List.copyOf(roomService.getRooms().values())) {
            String code = room.getCode();
            synchronized (room) {
                // Активность могла обновиться, пока мы ждали блокировку комнаты,
                // поэтому проверяем её повторно и удаляем комнату атомарно с проверкой
                if (!room.getLastActivity().plusMinutes(INACTIVE_TIME).isBefore(now)) {
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
