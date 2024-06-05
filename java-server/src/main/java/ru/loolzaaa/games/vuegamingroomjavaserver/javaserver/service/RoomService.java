package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.dto.RoomDTO;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.exception.RoomException;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Game;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Member;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Room;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@RequiredArgsConstructor
public class RoomService {

    private static final String ROOM_CODE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    @Getter
    private final Map<String, Room<? extends Game>> rooms = new ConcurrentHashMap<>();
    @Getter
    private final Lock roomsLock = new ReentrantLock();

    private final GameService<? extends Game> gameService;

    public RoomDTO createRoom(String userId, String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new RoomException("Nickname must be at least 1 character length");
        }
        nickname = nickname.trim();

        final String code = generateRoomCode();
        final String webSocketToken = UUID.randomUUID().toString();
        if (userId == null) {
            userId = UUID.randomUUID().toString();
        }

        Member member = new Member();
        member.setUserId(userId);
        member.setNickname(nickname);
        member.setColor(generateMemberColor());
        member.setAdmin(true);
        member.setSpectator(true);
        member.setReady(false);

        Game gameInstance = gameService.createGameInstance();
        Room<?> room = new Room<>(code, webSocketToken, gameInstance);
        room.getMembers().add(member);

        roomsLock.lock();
        try {
            rooms.put(code, room);
        } finally {
            roomsLock.unlock();
        }

        return new RoomDTO(code, webSocketToken, gameInstance.getName(), room.isGameStarted(), userId);
    }

    public RoomDTO joinToRoom(String code, String userId, String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new RoomException("Nickname must be at least 1 character length");
        }
        nickname = nickname.trim();

        Room<? extends Game> room = rooms.get(code);
        if (room == null) {
            throw new RoomException("The room doesn't exist");
        }

        boolean existingMember = false;
        Member member = null;
        if (userId != null) {
            member = room.getMemberByUserId(userId);
            existingMember = member != null;
        }
        if (member == null) {
            String newUserId = userId != null ? userId : UUID.randomUUID().toString();
            member = new Member();
            member.setUserId(newUserId);
            member.setColor(generateMemberColor());
            member.setAdmin(false);
            member.setSpectator(true);
            member.setReady(false);
        }
        member.setNickname(nickname);

        // Войти в комнату после старта игры может только тот, кто в ней уже состоит
        if (!existingMember && room.isGameStarted()) {
            throw new RoomException("Game already started");
        }
        // Войти в комнату с максимальным кол-во членов может только тот, кто в ней уже состоит
        if (!existingMember && room.getMembers().size() >= 200) {
            throw new RoomException("Room is full");
        }

        // Добавляем в комнату только тех, кто ранее не состоял в ней
        if (!existingMember) {
            room.getMembers().add(member);
        }

        room.setLastActivity(LocalDateTime.now());

        return new RoomDTO(code, room.getWebSocketToken(), room.getGame().getName(), room.isGameStarted(), member.getUserId());
    }

    public void changeMemberNickname(String code, String userId, String newNickname) {
        Room<? extends Game> room = rooms.get(code);
        if (room == null) {
            throw new RoomException("The room doesn't exist");
        }

        Member member = room.getMemberByUserId(userId);
        if (member == null) {
            throw new RoomException("The member doesn't exist");
        }

        member.setNickname(newNickname);

        room.setLastActivity(LocalDateTime.now());
    }

    public void changeMemberColor(String code, String userId, String newColor) {
        Room<? extends Game> room = rooms.get(code);
        if (room == null) {
            throw new RoomException("The room doesn't exist");
        }

        Member member = room.getMemberByUserId(userId);
        if (member == null) {
            throw new RoomException("The member doesn't exist");
        }

        member.setColor(newColor);

        room.setLastActivity(LocalDateTime.now());
    }

    public void changeMemberPlayerStatus(String code, String userId, boolean newStatus) {
        Room<? extends Game> room = rooms.get(code);
        if (room == null) {
            throw new RoomException("The room doesn't exist");
        }

        Member member = room.getMemberByUserId(userId);
        if (member == null) {
            throw new RoomException("The member doesn't exist");
        }

        long playersCount = room.getMembers().stream().filter(Member::isPlayer).count();
        if (playersCount == room.getGame().getMaxPlayers()) {
            throw new RoomException("Max players reached");
        }

        member.setPlayer(newStatus);
        if (!newStatus) {
            member.setReady(false);
        }

        room.setLastActivity(LocalDateTime.now());
    }

    public void changeMemberReadyStatus(String code, String userId, boolean newStatus) {
        Room<? extends Game> room = rooms.get(code);
        if (room == null) {
            throw new RoomException("The room doesn't exist");
        }
        if (room.isGameStarted()) {
            throw new RoomException("Game already started");
        }

        Member member = room.getMemberByUserId(userId);
        if (member == null || !member.isPlayer()) {
            throw new RoomException("The member doesn't exist or it is not a player");
        }

        member.setReady(newStatus);

        room.setLastActivity(LocalDateTime.now());
    }

    public void startGame(String code, String userId, boolean forceStart) {
        Room<? extends Game> room = rooms.get(code);
        if (room == null) {
            throw new RoomException("The room doesn't exist");
        }
        if (room.isGameStarted()) {
            throw new RoomException("Game already started");
        }

        Member member = room.getMemberByUserId(userId);
        if (member == null || !member.isAdmin()) {
            throw new RoomException("The member doesn't exist or it is not an admin");
        }

        boolean allPlayersReady = room.getMembers().stream()
                .filter(Member::isPlayer)
                .allMatch(Member::isReady);
        if (!forceStart && !allPlayersReady) {
            throw new RoomException("Some members aren't ready");
        }

        List<Member> players = new ArrayList<>(room.getGame().getMaxPlayers());
        room.getMembers().stream()
                .filter(Member::isPlayer)
                .forEach(m -> {
                    m.setReady(true);
                    players.add(m);
                });
        if (players.size() < room.getGame().getMinPlayers()) {
            throw new RoomException("Players in room less than minimum to start");
        }

        gameService.startNewGame(room.getGame(), players);

        room.setLastActivity(LocalDateTime.now());
    }

    public List<Member> getAllSpectators(String code) {
        Room<? extends Game> room = rooms.get(code);
        if (room == null) {
            throw new RoomException("The room doesn't exist");
        }

        return room.getMembers().stream()
                .filter(Member::isSpectator)
                .toList();
    }

    private String generateRoomCode() {
        Random random = new Random();
        StringBuilder code;
        do {
            code = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                code.append(ROOM_CODE_CHARACTERS.charAt(random.nextInt(ROOM_CODE_CHARACTERS.length())));
            }
        } while (rooms.containsKey(code.toString()));
        return code.toString();
    }

    private String generateMemberColor() {
        Random random = new Random();
        int nextInt = random.nextInt(0xffffff + 1);
        return String.format("#%06x", nextInt);
    }
}
