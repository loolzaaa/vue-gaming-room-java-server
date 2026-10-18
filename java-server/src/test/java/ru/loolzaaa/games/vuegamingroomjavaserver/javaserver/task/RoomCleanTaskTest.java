package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.dto.RoomDTO;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Room;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.RoomService;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.support.TestFixtures.TestGameService;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.GameWebSocketHandler;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class RoomCleanTaskTest {

    private RoomService roomService;
    private GameWebSocketHandler gameWebSocketHandler;
    private RoomCleanTask roomCleanTask;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(new TestGameService(1, 2));
        gameWebSocketHandler = mock(GameWebSocketHandler.class);
        roomCleanTask = new RoomCleanTask(roomService, gameWebSocketHandler);
    }

    @Test
    void shouldRemoveInactiveRoomAndItsSessions() {
        RoomDTO roomDTO = roomService.createRoom("admin-id", "admin");
        setLastActivity(roomDTO.getCode(), LocalDateTime.now().minusMinutes(61));

        roomCleanTask.clean();

        assertThat(roomService.getRooms()).doesNotContainKey(roomDTO.getCode());
        verify(gameWebSocketHandler).removeSessionsListForRoom(roomDTO.getCode());
        verifyNoMoreInteractions(gameWebSocketHandler);
    }

    @Test
    void shouldKeepActiveRoom() {
        RoomDTO roomDTO = roomService.createRoom("admin-id", "admin");
        setLastActivity(roomDTO.getCode(), LocalDateTime.now().minusMinutes(30));

        roomCleanTask.clean();

        assertThat(roomService.getRooms()).containsKey(roomDTO.getCode());
        verifyNoInteractions(gameWebSocketHandler);
    }

    @Test
    void shouldRemoveOnlyInactiveRooms() {
        RoomDTO inactiveRoom = roomService.createRoom("inactive-id", "inactive");
        RoomDTO activeRoom = roomService.createRoom("active-id", "active");
        RoomDTO oldRoom = roomService.createRoom("old-id", "old");
        setLastActivity(inactiveRoom.getCode(), LocalDateTime.now().minusMinutes(61));
        setLastActivity(oldRoom.getCode(), LocalDateTime.now().minusMinutes(120));

        roomCleanTask.clean();

        assertThat(roomService.getRooms())
                .containsOnlyKeys(activeRoom.getCode());
        verify(gameWebSocketHandler).removeSessionsListForRoom(inactiveRoom.getCode());
        verify(gameWebSocketHandler).removeSessionsListForRoom(oldRoom.getCode());
        verifyNoMoreInteractions(gameWebSocketHandler);
    }

    @Test
    void shouldDoNothingWhenThereAreNoRooms() {
        roomCleanTask.clean();

        assertThat(roomService.getRooms()).isEmpty();
        verifyNoInteractions(gameWebSocketHandler);
    }

    @Test
    void shouldRemoveInactiveRoomOnlyOnce() {
        RoomDTO roomDTO = roomService.createRoom("admin-id", "admin");
        setLastActivity(roomDTO.getCode(), LocalDateTime.now().minusMinutes(61));

        roomCleanTask.clean();
        roomCleanTask.clean();

        verify(gameWebSocketHandler, times(1)).removeSessionsListForRoom(roomDTO.getCode());
        verifyNoMoreInteractions(gameWebSocketHandler);
    }

    private void setLastActivity(String code, LocalDateTime lastActivity) {
        Room<?> room = roomService.getRooms().get(code);
        room.setLastActivity(lastActivity);
    }
}
