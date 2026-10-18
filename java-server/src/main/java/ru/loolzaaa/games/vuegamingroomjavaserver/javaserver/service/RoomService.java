package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.dto.RoomDTO;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.exception.RoomException;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Game;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Member;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Room;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@RequiredArgsConstructor
public class RoomService {

    private static final String ROOM_CODE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int MAX_ROOM_MEMBERS = 200;

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

        final String code;
        final Room<?> room;
        // Генерация кода и публикация комнаты должны быть атомарными,
        // иначе две комнаты могут получить одинаковый код
        roomsLock.lock();
        try {
            code = generateRoomCode();
            room = new Room<>(code, webSocketToken, gameInstance);
            room.getMembers().add(member);
            rooms.put(code, room);
        } finally {
            roomsLock.unlock();
        }

        return new RoomDTO(code, webSocketToken, room.isGameStarted(), userId);
    }

    public RoomDTO joinToRoom(String code, String userId, String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new RoomException("Nickname must be at least 1 character length");
        }
        nickname = nickname.trim();

        Room<? extends Game> room = getRoom(code);

        synchronized (room) {
            checkRoomIsRegistered(code, room);

            Member member = null;
            if (userId != null) {
                member = room.getMemberByUserId(userId);
            }
            boolean existingMember = member != null;
            if (member == null) {
                String newUserId = userId != null ? userId : UUID.randomUUID().toString();
                member = new Member();
                member.setUserId(newUserId);
                member.setColor(generateMemberColor());
                member.setAdmin(false);
                member.setSpectator(true);
                member.setReady(false);
            }

            // Войти в комнату после старта игры может только тот, кто в ней уже состоит
            if (!existingMember && room.isGameStarted()) {
                throw new RoomException("Game already started");
            }
            // Войти в комнату с максимальным кол-во членов может только тот, кто в ней уже состоит
            if (!existingMember && room.getMembers().size() >= MAX_ROOM_MEMBERS) {
                throw new RoomException("Room is full");
            }

            member.setNickname(validateNickname(room, nickname, member.getUserId()));

            // Добавляем в комнату только тех, кто ранее не состоял в ней
            if (!existingMember) {
                room.getMembers().add(member);
            }

            room.setLastActivity(LocalDateTime.now());

            return new RoomDTO(code, room.getWebSocketToken(), room.isGameStarted(), member.getUserId());
        }
    }

    public void changeMemberNickname(String code, String userId, String newNickname) {
        Room<? extends Game> room = getRoom(code);

        synchronized (room) {
            checkRoomIsRegistered(code, room);

            Member member = room.getMemberByUserId(userId);
            if (member == null) {
                throw new RoomException("The member doesn't exist");
            }

            member.setNickname(validateNickname(room, newNickname, userId));

            room.setLastActivity(LocalDateTime.now());
        }
    }

    public void changeMemberColor(String code, String userId, String newColor) {
        Room<? extends Game> room = getRoom(code);

        synchronized (room) {
            checkRoomIsRegistered(code, room);

            Member member = room.getMemberByUserId(userId);
            if (member == null) {
                throw new RoomException("The member doesn't exist");
            }

            member.setColor(newColor);

            room.setLastActivity(LocalDateTime.now());
        }
    }

    public void changeMemberPlayerStatus(String code, String userId, boolean newStatus) {
        Room<? extends Game> room = getRoom(code);

        synchronized (room) {
            checkRoomIsRegistered(code, room);

            Member member = room.getMemberByUserId(userId);
            if (member == null) {
                throw new RoomException("The member doesn't exist");
            }

            long playersCount = room.getMembers().stream().filter(Member::isPlayer).count();
            if (newStatus && playersCount >= room.getGame().getMaxPlayers()) {
                throw new RoomException("Max players reached");
            }

            member.setPlayer(newStatus);
            if (!newStatus) {
                member.setReady(false);
            }

            room.setLastActivity(LocalDateTime.now());
        }
    }

    public void changeMemberReadyStatus(String code, String userId, boolean newStatus) {
        Room<? extends Game> room = getRoom(code);

        synchronized (room) {
            checkRoomIsRegistered(code, room);

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
    }

    public void startGame(String code, String userId, boolean forceStart) {
        Room<? extends Game> room = getRoom(code);

        synchronized (room) {
            checkRoomIsRegistered(code, room);
            startGame(room, userId, forceStart);
        }
    }

    public void restartGame(String code, String userId) {
        Room<? extends Game> room = getRoom(code);

        synchronized (room) {
            checkRoomIsRegistered(code, room);

            if (!room.isGameStarted()) {
                throw new RoomException("Game is not started");
            }

            Member member = room.getMemberByUserId(userId);
            if (member == null || !member.isAdmin()) {
                throw new RoomException("The member doesn't exist or it is not an admin");
            }

            room.setGameStarted(false);
            try {
                startGame(room, userId, true);
            } catch (RuntimeException e) {
                // Не оставляем комнату в состоянии "игра не начата",
                // если перезапуск не удался
                room.setGameStarted(true);
                throw e;
            }
        }
    }

    public List<Member> getAllSpectators(String code) {
        Room<? extends Game> room = getRoom(code);

        synchronized (room) {
            checkRoomIsRegistered(code, room);

            return room.getMembers().stream()
                    .filter(Member::isSpectator)
                    .toList();
        }
    }

    /**
     * Старт игры. Вызывается только при удерживаемой блокировке комнаты,
     * чтобы проверки и изменение состояния были атомарными.
     */
    private void startGame(Room<? extends Game> room, String userId, boolean forceStart) {
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

        // Флаг выставляется здесь, пока удерживается блокировка комнаты,
        // иначе параллельные запросы на старт смогут запустить игру повторно
        room.setGameStarted(true);
        room.setLastActivity(LocalDateTime.now());
    }

    private Room<? extends Game> getRoom(String code) {
        Room<? extends Game> room = rooms.get(code);
        if (room == null) {
            throw new RoomException("The room doesn't exist");
        }
        return room;
    }

    /**
     * Проверяет, что комната всё ещё зарегистрирована. Нужна на случай,
     * если комната была удалена планировщиком между получением ссылки и блокировкой.
     */
    private void checkRoomIsRegistered(String code, Room<?> room) {
        if (rooms.get(code) != room) {
            throw new RoomException("The room doesn't exist");
        }
    }

    /**
     * Проверяет, что никнейм не пустой и не занят другим участником комнаты.
     * Вызывается только при удерживаемой блокировке комнаты.
     *
     * @param excludedUserId идентификатор участника, которому разрешено сохранять свой никнейм
     * @return нормализованный (обрезанный) никнейм
     */
    private String validateNickname(Room<? extends Game> room, String nickname, String excludedUserId) {
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new RoomException("Nickname must be at least 1 character length");
        }
        String normalizedNickname = nickname.trim();
        boolean sameNicknameExists = room.getMembers().stream()
                .filter(m -> !m.getUserId().equals(excludedUserId))
                .anyMatch(m -> normalizedNickname.equals(m.getNickname()));
        if (sameNicknameExists) {
            throw new RoomException("User with same nickname already exists");
        }
        return normalizedNickname;
    }

    /**
     * Генерирует свободный код комнаты. Должен вызываться при удерживаемом {@link #roomsLock},
     * чтобы проверка и последующая публикация комнаты были атомарными.
     */
    private String generateRoomCode() {
        StringBuilder code;
        do {
            code = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                code.append(ROOM_CODE_CHARACTERS.charAt(
                        ThreadLocalRandom.current().nextInt(ROOM_CODE_CHARACTERS.length())));
            }
        } while (rooms.containsKey(code.toString()));
        return code.toString();
    }

    private String generateMemberColor() {
        int nextInt = ThreadLocalRandom.current().nextInt(0xffffff + 1);
        return String.format("#%06x", nextInt);
    }
}
