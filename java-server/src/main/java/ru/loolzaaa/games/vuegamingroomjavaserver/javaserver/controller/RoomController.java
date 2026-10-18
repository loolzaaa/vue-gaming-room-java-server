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

    public static final String MEMBER_TOKEN_HEADER = "X-Member-Token";

    private final GameWebSocketHandler gameWebSocketHandler;

    private final RoomService roomService;

    @PostMapping(path = "/create", produces = "application/json")
    public RoomDTO createRoom(@RequestParam(value = "userId", required = false) String userId,
                              @RequestParam("nickname") String nickname) {
        RoomDTO roomDTO = roomService.createRoom(userId, nickname);
        gameWebSocketHandler.createNewSessionsListForRoom(roomDTO.getCode());
        return roomDTO;
    }

    @PostMapping(path = "/join/{code}", produces = "application/json")
    public RoomDTO joinToRoom(@PathVariable("code") String code,
                              @RequestParam(value = "userId", required = false) String userId,
                              @RequestParam("nickname") String nickname,
                              @RequestHeader(value = MEMBER_TOKEN_HEADER, required = false) String memberToken) {
        RoomDTO roomDTO = roomService.joinToRoom(code, userId, nickname, memberToken);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
        return roomDTO;
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{code}/{userId}/nickname")
    public void changeMemberNickname(@PathVariable("code") String code,
                                     @PathVariable("userId") String userId,
                                     @RequestParam("newNickname") String newNickname,
                                     @RequestHeader(value = MEMBER_TOKEN_HEADER, required = false) String memberToken) {
        roomService.validateMemberToken(code, userId, memberToken);
        roomService.changeMemberNickname(code, userId, newNickname);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{code}/{userId}/color")
    public void changeMemberColor(@PathVariable("code") String code,
                                  @PathVariable("userId") String userId,
                                  @RequestParam("newColor") String newColor,
                                  @RequestHeader(value = MEMBER_TOKEN_HEADER, required = false) String memberToken) {
        roomService.validateMemberToken(code, userId, memberToken);
        roomService.changeMemberColor(code, userId, newColor);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{code}/{userId}/player")
    public void changeMemberPlayerStatus(@PathVariable("code") String code,
                                         @PathVariable("userId") String userId,
                                         @RequestParam("newStatus") boolean newStatus,
                                         @RequestHeader(value = MEMBER_TOKEN_HEADER, required = false) String memberToken) {
        roomService.validateMemberToken(code, userId, memberToken);
        roomService.changeMemberPlayerStatus(code, userId, newStatus);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{code}/{userId}/ready")
    public void changeMemberReadyStatus(@PathVariable("code") String code,
                                        @PathVariable("userId") String userId,
                                        @RequestParam("newStatus") boolean newStatus,
                                        @RequestHeader(value = MEMBER_TOKEN_HEADER, required = false) String memberToken) {
        roomService.validateMemberToken(code, userId, memberToken);
        roomService.changeMemberReadyStatus(code, userId, newStatus);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{code}/start")
    public void startGame(@PathVariable("code") String code,
                          @RequestParam("userId") String userId,
                          @RequestParam("forceStart") boolean forceStart,
                          @RequestHeader(value = MEMBER_TOKEN_HEADER, required = false) String memberToken) {
        roomService.validateMemberToken(code, userId, memberToken);
        roomService.startGame(code, userId, forceStart);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.START_GAME);
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{code}/restart")
    public void restartGame(@PathVariable("code") String code,
                            @RequestParam("userId") String userId,
                            @RequestHeader(value = MEMBER_TOKEN_HEADER, required = false) String memberToken) {
        roomService.validateMemberToken(code, userId, memberToken);
        // Перезапуск выполняется атомарно до рассылки событий,
        // чтобы при отказе клиенты не получили RESTART_GAME
        roomService.restartGame(code, userId);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.RESTART_GAME);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.UPDATE_MEMBERS);
        gameWebSocketHandler.sendEvent(code, GameWebSocketHandler.START_GAME);
    }

    @PostMapping(path = "/{code}/ws-ticket", produces = "application/json")
    public GameWebSocketHandler.WsTicket createWsTicket(
            @PathVariable("code") String code,
            @RequestParam("userId") String userId,
            @RequestHeader(value = MEMBER_TOKEN_HEADER, required = false) String memberToken) {
        roomService.validateMemberToken(code, userId, memberToken);
        return gameWebSocketHandler.createWsTicket(code, userId);
    }

    @GetMapping("/{code}/spectators")
    public List<Member> getAllSpectators(@PathVariable("code") String code,
                                         @RequestParam("userId") String userId,
                                         @RequestHeader(value = MEMBER_TOKEN_HEADER, required = false) String memberToken) {
        roomService.validateMemberToken(code, userId, memberToken);
        return roomService.getAllSpectators(code);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(RoomException.class)
    public ErrorResponse roomError(RoomException e) {
        return new ErrorResponse(e.getLocalizedMessage());
    }

    public record ErrorResponse(String message) {
    }
}
