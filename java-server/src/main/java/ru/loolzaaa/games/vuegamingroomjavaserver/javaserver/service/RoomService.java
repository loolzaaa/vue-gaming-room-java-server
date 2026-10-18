package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service;

import lombok.Getter;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.dto.RoomDTO;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.exception.RoomException;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Game;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Member;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Room;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.settings.ServerSettings;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public class RoomService<G extends Game> {

    private static final String ROOM_CODE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int ROOM_CODE_LENGTH = 4;
    private static final Pattern ROOM_CODE_PATTERN = Pattern.compile("[A-Z]{4}");
    private static final Pattern COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final int MAX_USER_ID_LENGTH = 64;
    private static final int MAX_MEMBER_COLOR = 0xFFFFFF;
    private static final String MEMBER_COLOR_FORMAT = "#%06x";

    @Getter
    private final Map<String, Room<G>> rooms = new ConcurrentHashMap<>();
    @Getter
    private final Lock roomsLock = new ReentrantLock();

    private final GameService<G> gameService;
    private final int maxMembers;
    private final int maxNicknameLength;

    public RoomService(GameService<G> gameService) {
        this(gameService, ServerSettings.defaults());
    }

    public RoomService(GameService<G> gameService, ServerSettings settings) {
        this.gameService = Objects.requireNonNull(gameService, "gameService must not be null");
        this.maxMembers = settings.room().maxMembers();
        this.maxNicknameLength = settings.room().maxNicknameLength();
    }

    public RoomDTO createRoom(String userId, String nickname) {
        nickname = normalizeNickname(nickname);
        userId = normalizeUserId(userId);

        final String memberToken = generateMemberToken();

        Member member = new Member();
        member.setUserId(userId);
        member.setWsToken(memberToken);
        member.setNickname(nickname);
        member.setColor(generateMemberColor());
        member.setAdmin(true);
        member.setSpectator(true);
        member.setReady(false);

        G gameInstance = gameService.createGameInstance();

        final String code;
        final Room<G> room;
        // Генерация кода и публикация комнаты должны быть атомарными,
        // иначе две комнаты могут получить одинаковый код
        roomsLock.lock();
        try {
            code = generateRoomCode();
            room = new Room<>(code, gameInstance);
            room.getMembers().add(member);
            rooms.put(code, room);
        } finally {
            roomsLock.unlock();
        }

        return new RoomDTO(code, memberToken, room.isGameStarted(), userId);
    }

    public RoomDTO joinToRoom(String code, String userId, String nickname) {
        return joinToRoom(code, userId, nickname, null);
    }

    public RoomDTO joinToRoom(String code, String userId, String nickname, String memberToken) {
        nickname = normalizeNickname(nickname);

        Room<G> room = getRoom(code);

        synchronized (room) {
            checkRoomIsRegistered(code, room);

            Member member = null;
            if (userId != null) {
                userId = normalizeUserId(userId);
                member = room.getMemberByUserId(userId);
            }
            boolean existingMember = member != null;
            final String resolvedMemberToken;
            if (member == null) {
                String newUserId = userId != null ? userId : UUID.randomUUID().toString();
                resolvedMemberToken = generateMemberToken();
                member = new Member();
                member.setUserId(newUserId);
                member.setWsToken(resolvedMemberToken);
                member.setColor(generateMemberColor());
                member.setAdmin(false);
                member.setSpectator(true);
                member.setReady(false);
            } else {
                if (!tokensEqual(member.getWsToken(), memberToken)) {
                    throw new RoomException("Invalid member token");
                }
                resolvedMemberToken = member.getWsToken();
            }

            // После старта игры новый участник может войти только как наблюдатель.
            // Игроком он стать уже не сможет: смена роли после старта запрещена.
            // Максимальное количество участников остаётся ограниченным.
            if (!existingMember && room.getMembers().size() >= maxMembers) {
                throw new RoomException("Room is full");
            }

            member.setNickname(validateNickname(room, nickname, member.getUserId()));

            // Добавляем в комнату только тех, кто ранее не состоял в ней
            if (!existingMember) {
                room.getMembers().add(member);
            }

            room.setLastActivity(LocalDateTime.now());

            return new RoomDTO(code, resolvedMemberToken, room.isGameStarted(), member.getUserId());
        }
    }

    public void changeMemberNickname(String code, String userId, String newNickname) {
        Room<G> room = getRoom(code);

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
        Room<G> room = getRoom(code);

        synchronized (room) {
            checkRoomIsRegistered(code, room);

            Member member = room.getMemberByUserId(userId);
            if (member == null) {
                throw new RoomException("The member doesn't exist");
            }

            member.setColor(normalizeColor(newColor));

            room.setLastActivity(LocalDateTime.now());
        }
    }

    public void changeMemberPlayerStatus(String code, String userId, boolean newStatus) {
        Room<G> room = getRoom(code);

        synchronized (room) {
            checkRoomIsRegistered(code, room);
            if (room.isGameStarted()) {
                throw new RoomException("Game already started");
            }

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
        Room<G> room = getRoom(code);

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
        Room<G> room = getRoom(code);

        room.getGameLock().lock();
        try {
            synchronized (room) {
                checkRoomIsRegistered(code, room);
                startGame(room, userId, forceStart);
            }
        } finally {
            room.getGameLock().unlock();
        }
    }

    public void restartGame(String code, String userId) {
        Room<G> room = getRoom(code);

        room.getGameLock().lock();
        try {
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
        } finally {
            room.getGameLock().unlock();
        }
    }

    public List<Member> getAllSpectators(String code) {
        Room<G> room = getRoom(code);

        synchronized (room) {
            checkRoomIsRegistered(code, room);

            return room.getMembers().stream()
                    .filter(Member::isSpectator)
                    .toList();
        }
    }

    /**
     * Проверяет, что переданный токен принадлежит участнику комнаты.
     */
    public void validateMemberToken(String code, String userId, String memberToken) {
        Room<G> room = getRoom(code);

        synchronized (room) {
            checkRoomIsRegistered(code, room);

            Member member = room.getMemberByUserId(userId);
            if (member == null || !tokensEqual(member.getWsToken(), memberToken)) {
                throw new RoomException("Invalid member token");
            }
        }
    }

    /**
     * Старт игры. Вызывается только при удерживаемой блокировке комнаты,
     * чтобы проверки и изменение состояния были атомарными.
     */
    private void startGame(Room<G> room, String userId, boolean forceStart) {
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
                .forEach(players::add);
        // Проверка минимума выполняется до изменения флагов готовности,
        // чтобы неудачный старт не оставлял игроков готовыми к игре
        if (players.size() < room.getGame().getMinPlayers()) {
            throw new RoomException("Players in room less than minimum to start");
        }
        players.forEach(m -> m.setReady(true));

        gameService.startNewGame(room.getGame(), players);

        // Флаг выставляется здесь, пока удерживается блокировка комнаты,
        // иначе параллельные запросы на старт смогут запустить игру повторно
        room.setGameStarted(true);
        room.setLastActivity(LocalDateTime.now());
    }

    private Room<G> getRoom(String code) {
        if (code == null || !ROOM_CODE_PATTERN.matcher(code).matches()) {
            throw new RoomException("The room doesn't exist");
        }
        Room<G> room = rooms.get(code);
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
     * Проверяет, что никнейм не пустой, не слишком длинный, не содержит
     * управляющих символов и не занят другим участником комнаты.
     * Вызывается только при удерживаемой блокировке комнаты.
     *
     * @param excludedUserId идентификатор участника, которому разрешено сохранять свой никнейм
     * @return нормализованный (trim + NFC) никнейм
     */
    private String validateNickname(Room<G> room, String nickname, String excludedUserId) {
        String normalizedNickname = normalizeNickname(nickname);
        boolean sameNicknameExists = room.getMembers().stream()
                .filter(m -> !m.getUserId().equals(excludedUserId))
                .map(Member::getNickname)
                .filter(Objects::nonNull)
                .map(this::normalizeForComparison)
                .anyMatch(normalizedNickname::equalsIgnoreCase);
        if (sameNicknameExists) {
            throw new RoomException("User with same nickname already exists");
        }
        return normalizedNickname;
    }

    private String normalizeNickname(String nickname) {
        if (nickname == null) {
            throw new RoomException("Nickname must be at least 1 character length");
        }
        String trimmedNickname = nickname.trim();
        if (trimmedNickname.isEmpty()) {
            throw new RoomException("Nickname must be at least 1 character length");
        }
        if (trimmedNickname.codePointCount(0, trimmedNickname.length()) > maxNicknameLength) {
            throw new RoomException("Nickname must be at most %d characters length"
                    .formatted(maxNicknameLength));
        }
        if (trimmedNickname.codePoints().anyMatch(Character::isISOControl)) {
            throw new RoomException("Nickname must not contain control characters");
        }
        return Normalizer.normalize(trimmedNickname, Normalizer.Form.NFC);
    }

    private String normalizeForComparison(String nickname) {
        return Normalizer.normalize(nickname.trim(), Normalizer.Form.NFC);
    }

    private String normalizeColor(String color) {
        if (color == null || !COLOR_PATTERN.matcher(color).matches()) {
            throw new RoomException("Color must be in #RRGGBB format");
        }
        return color.toLowerCase(Locale.ROOT);
    }

    private String normalizeUserId(String userId) {
        if (userId == null) {
            return UUID.randomUUID().toString();
        }
        String normalizedUserId = userId.trim();
        if (normalizedUserId.isEmpty()
                || normalizedUserId.length() > MAX_USER_ID_LENGTH
                || normalizedUserId.indexOf(':') >= 0
                || normalizedUserId.codePoints().anyMatch(Character::isISOControl)) {
            throw new RoomException("Invalid user id");
        }
        return normalizedUserId;
    }

    /**
     * Генерирует свободный код комнаты. Должен вызываться при удерживаемом {@link #roomsLock},
     * чтобы проверка и последующая публикация комнаты были атомарными.
     */
    private String generateRoomCode() {
        StringBuilder code;
        do {
            code = new StringBuilder();
            for (int i = 0; i < ROOM_CODE_LENGTH; i++) {
                code.append(ROOM_CODE_CHARACTERS.charAt(
                        ThreadLocalRandom.current().nextInt(ROOM_CODE_CHARACTERS.length())));
            }
        } while (rooms.containsKey(code.toString()));
        return code.toString();
    }

    private String generateMemberColor() {
        int nextInt = ThreadLocalRandom.current().nextInt(MAX_MEMBER_COLOR + 1);
        return String.format(MEMBER_COLOR_FORMAT, nextInt);
    }

    private String generateMemberToken() {
        return UUID.randomUUID().toString();
    }

    private boolean tokensEqual(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
