package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.dto.RoomDTO;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.exception.RoomException;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Member;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service.RoomService;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket.GameWebSocketHandler;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/room")
public class RoomController {

    private final GameWebSocketHandler gameWebSocketHandler;

    private final RoomService roomService;

    @PostMapping(path = "/create", produces = "application/json")
    public RoomDTO createRoom(@RequestParam(value = "userId", required = false) String userId,
                              @RequestParam("nickname") String nickname) {
        RoomDTO roomDTO = roomService.createRoom(userId, nickname);
        gameWebSocketHandler.createNewSessionsListForRoom(roomDTO.getWsToken(), roomDTO.getCode());
        return roomDTO;
    }

    @PostMapping(path = "/join/{code}", produces = "application/json")
    public RoomDTO joinToRoom(@PathVariable("code") String code,
                              @RequestParam(value = "userId", required = false) String userId,
                              @RequestParam("nickname") String nickname) {
        RoomDTO roomDTO = roomService.joinToRoom(code, userId, nickname);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
        return roomDTO;
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{code}/{userId}/nickname")
    public void changeMemberNickname(@PathVariable("code") String code,
                                     @PathVariable("userId") String userId,
                                     @RequestParam("newNickname") String newNickname) {
        roomService.changeMemberNickname(code, userId, newNickname);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{code}/{userId}/color")
    public void changeMemberColor(@PathVariable("code") String code,
                                  @PathVariable("userId") String userId,
                                  @RequestParam("newColor") String newColor) {
        roomService.changeMemberColor(code, userId, newColor);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{code}/{userId}/player")
    public void changeMemberPlayerStatus(@PathVariable("code") String code,
                                         @PathVariable("userId") String userId,
                                         @RequestParam("newStatus") boolean newStatus) {
        roomService.changeMemberPlayerStatus(code, userId, newStatus);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{code}/{userId}/ready")
    public void changeMemberReadyStatus(@PathVariable("code") String code,
                                        @PathVariable("userId") String userId,
                                        @RequestParam("newStatus") boolean newStatus) {
        roomService.changeMemberReadyStatus(code, userId, newStatus);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{code}/start")
    public void startGame(@PathVariable("code") String code,
                          @RequestParam("userId") String userId,
                          @RequestParam("forceStart") boolean forceStart) {
        roomService.startGame(code, userId, forceStart);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.START_GAME);
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{code}/restart")
    public void restartGame(@PathVariable("code") String code,
                            @RequestParam("userId") String userId) {
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.RESTART_GAME);
        roomService.startGame(code, userId, true);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.START_GAME);
    }

    @GetMapping("/{code}/spectators")
    public List<Member> getAllSpectators(@PathVariable("code") String code) {
        return roomService.getAllSpectators(code);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(RoomException.class)
    public String roomError(RoomException e) {
        return "{\"message\":\"%s\"}".formatted(e.getLocalizedMessage());
    }
}
