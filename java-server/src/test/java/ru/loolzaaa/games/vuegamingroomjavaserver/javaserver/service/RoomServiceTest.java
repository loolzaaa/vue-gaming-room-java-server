package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.dto.RoomDTO;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.exception.RoomException;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Member;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo.Room;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.settings.ServerSettings;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.support.TestFixtures.TestGame;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.support.TestFixtures.TestGameService;

import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomServiceTest {

    private static final String ADMIN_ID = "admin-id";

    private TestGameService gameService;
    private RoomService<TestGame> roomService;

    @BeforeEach
    void setUp() {
        gameService = new TestGameService(1, 2);
        roomService = new RoomService<>(gameService);
    }

    // ---------- createRoom ----------

    @Test
    void shouldRejectNullOrBlankNicknameOnCreate() {
        assertThatThrownBy(() -> roomService.createRoom("user", null))
                .isInstanceOf(RoomException.class)
                .hasMessage("Nickname must be at least 1 character length");
        assertThatThrownBy(() -> roomService.createRoom("user", ""))
                .isInstanceOf(RoomException.class)
                .hasMessage("Nickname must be at least 1 character length");
        assertThatThrownBy(() -> roomService.createRoom("user", "   "))
                .isInstanceOf(RoomException.class)
                .hasMessage("Nickname must be at least 1 character length");

        assertThat(roomService.getRooms()).isEmpty();
    }

    @Test
    void shouldCreateRoomWithAdminSpectatorAndNormalizedNickname() {
        RoomDTO roomDTO = roomService.createRoom(ADMIN_ID, "  Alice  ");

        assertThat(roomDTO.getCode()).matches("[A-Z]{4}");
        assertThat(roomDTO.getWsToken()).isNotBlank();
        assertThat(roomDTO.getUserId()).isEqualTo(ADMIN_ID);
        assertThat(roomDTO.isGameStarted()).isFalse();

        Room<?> room = room(roomDTO.getCode());
        assertThat(room).isNotNull();
        assertThat(room.getCode()).isEqualTo(roomDTO.getCode());
        assertThat(room.getGame()).isInstanceOf(TestGame.class);
        assertThat(room.getGame().getMinPlayers()).isEqualTo(1);
        assertThat(room.getGame().getMaxPlayers()).isEqualTo(2);
        assertThat(room.getLastActivity()).isNotNull();
        assertThat(room.isGameStarted()).isFalse();
        assertThat(room.getMembers()).hasSize(1);

        Member admin = room.getMembers().getFirst();
        assertThat(admin.getUserId()).isEqualTo(ADMIN_ID);
        assertThat(admin.getWsToken()).isEqualTo(roomDTO.getWsToken());
        assertThat(admin.getNickname()).isEqualTo("Alice");
        assertThat(admin.isAdmin()).isTrue();
        assertThat(admin.isSpectator()).isTrue();
        assertThat(admin.isPlayer()).isFalse();
        assertThat(admin.isReady()).isFalse();
        assertThat(admin.getColor()).matches("#[0-9a-f]{6}");
    }

    @Test
    void shouldGenerateUserIdWhenItIsNotProvided() {
        RoomDTO roomDTO = roomService.createRoom(null, "Alice");

        assertThat(roomDTO.getUserId()).isNotBlank();
        assertThatCode(() -> UUID.fromString(roomDTO.getUserId())).doesNotThrowAnyException();
        assertThat(room(roomDTO.getCode()).getMemberByUserId(roomDTO.getUserId())).isNotNull();
    }

    @Test
    void shouldGenerateUniqueRoomCodesAndTokens() {
        int roomsCount = 200;

        List<RoomDTO> created = IntStream.range(0, roomsCount)
                .mapToObj(i -> roomService.createRoom("user-" + i, "member-" + i))
                .toList();

        assertThat(created).hasSize(roomsCount);
        assertThat(created).extracting(RoomDTO::getCode).doesNotHaveDuplicates()
                .allMatch(code -> code.matches("[A-Z]{4}"));
        assertThat(created).extracting(RoomDTO::getWsToken).doesNotHaveDuplicates();
        assertThat(roomService.getRooms()).hasSize(roomsCount);
    }

    // ---------- joinToRoom ----------

    @Test
    void shouldRejectJoinToUnknownRoom() {
        assertThatThrownBy(() -> roomService.joinToRoom("NONE", "user", "Alice"))
                .isInstanceOf(RoomException.class)
                .hasMessage("The room doesn't exist");
    }

    @Test
    void shouldRejectBlankNicknameOnJoin() {
        RoomDTO roomDTO = createRoom("admin");

        assertThatThrownBy(() -> roomService.joinToRoom(roomDTO.getCode(), "user", "  "))
                .isInstanceOf(RoomException.class)
                .hasMessage("Nickname must be at least 1 character length");
    }

    @Test
    void shouldRejectJoinWithSameNickname() {
        RoomDTO roomDTO = createRoom("admin");

        assertThatThrownBy(() -> roomService.joinToRoom(roomDTO.getCode(), "user", "admin"))
                .isInstanceOf(RoomException.class)
                .hasMessage("User with same nickname already exists");
    }

    @Test
    void shouldTrimNicknameAndReturnRoomDataOnJoin() {
        RoomDTO roomDTO = createRoom("admin");

        RoomDTO joined = roomService.joinToRoom(roomDTO.getCode(), "user-1", "  Bob  ");

        assertThat(joined.getCode()).isEqualTo(roomDTO.getCode());
        assertThat(joined.getWsToken()).isNotBlank().isNotEqualTo(roomDTO.getWsToken());
        assertThat(joined.getUserId()).isEqualTo("user-1");
        assertThat(joined.isGameStarted()).isFalse();
        assertThat(member(roomDTO.getCode(), "user-1").getNickname()).isEqualTo("Bob");
        assertThat(member(roomDTO.getCode(), "user-1").isSpectator()).isTrue();
        assertThat(member(roomDTO.getCode(), "user-1").isAdmin()).isFalse();
        assertThat(room(roomDTO.getCode()).getMembers()).hasSize(2);
    }

    @Test
    void shouldReconnectExistingMemberAndUpdateNickname() {
        RoomDTO roomDTO = createRoom("admin");
        RoomDTO joined = roomService.joinToRoom(roomDTO.getCode(), "user-1", "Bob");
        String color = member(roomDTO.getCode(), "user-1").getColor();

        RoomDTO rejoined = roomService.joinToRoom(
                roomDTO.getCode(), "user-1", "  Bobby  ", joined.getWsToken());

        assertThat(rejoined.getUserId()).isEqualTo("user-1");
        assertThat(rejoined.getWsToken()).isEqualTo(joined.getWsToken());
        assertThat(room(roomDTO.getCode()).getMembers()).hasSize(2);
        assertThat(member(roomDTO.getCode(), "user-1").getNickname()).isEqualTo("Bobby");
        assertThat(member(roomDTO.getCode(), "user-1").getColor()).isEqualTo(color);
        assertThat(member(roomDTO.getCode(), "user-1").isAdmin()).isFalse();
    }

    @Test
    void shouldRejectReconnectWithInvalidMemberToken() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.joinToRoom(roomDTO.getCode(), "user-1", "Bob");

        assertThatThrownBy(() -> roomService.joinToRoom(
                roomDTO.getCode(), "user-1", "Bob", "wrong-token"))
                .isInstanceOf(RoomException.class)
                .hasMessage("Invalid member token");
    }

    @Test
    void shouldIssueUniqueMemberTokens() {
        RoomDTO adminRoom = createRoom("admin");
        RoomDTO joined = roomService.joinToRoom(adminRoom.getCode(), "user-1", "Bob");

        assertThat(joined.getWsToken()).isNotBlank();
        assertThat(joined.getWsToken()).isNotEqualTo(adminRoom.getWsToken());
        assertThat(member(adminRoom.getCode(), "user-1").getWsToken())
                .isEqualTo(joined.getWsToken());
    }

    @Test
    void shouldValidateMemberToken() {
        RoomDTO roomDTO = createRoom("admin");

        assertThatCode(() -> roomService.validateMemberToken(
                roomDTO.getCode(), ADMIN_ID, roomDTO.getWsToken()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> roomService.validateMemberToken(
                roomDTO.getCode(), ADMIN_ID, "wrong-token"))
                .isInstanceOf(RoomException.class)
                .hasMessage("Invalid member token");
        assertThatThrownBy(() -> roomService.validateMemberToken(
                roomDTO.getCode(), "unknown", roomDTO.getWsToken()))
                .isInstanceOf(RoomException.class)
                .hasMessage("Invalid member token");
    }

    @Test
    void shouldAllowExistingMemberToReconnectAfterGameStarted() {
        RoomDTO roomDTO = createStartedRoom();

        RoomDTO rejoined = roomService.joinToRoom(
                roomDTO.getCode(), ADMIN_ID, "admin", roomDTO.getWsToken());

        assertThat(rejoined.getCode()).isEqualTo(roomDTO.getCode());
        assertThat(rejoined.getWsToken()).isEqualTo(roomDTO.getWsToken());
        assertThat(rejoined.isGameStarted()).isTrue();
        assertThat(room(roomDTO.getCode()).getMembers()).hasSize(1);
    }

    @Test
    void shouldAllowNewSpectatorAfterGameStarted() {
        RoomDTO roomDTO = createStartedRoom();

        RoomDTO joined = roomService.joinToRoom(roomDTO.getCode(), "new-user", "new");

        assertThat(joined.isGameStarted()).isTrue();
        assertThat(member(roomDTO.getCode(), "new-user").isSpectator()).isTrue();
        assertThat(member(roomDTO.getCode(), "new-user").isPlayer()).isFalse();
    }

    @Test
    void shouldRejectNewMemberWhenRoomIsFull() {
        RoomDTO roomDTO = createRoom("admin");
        fillRoomToMaxMembers(roomDTO.getCode());

        assertThatThrownBy(() -> roomService.joinToRoom(roomDTO.getCode(), "extra-user", "extra"))
                .isInstanceOf(RoomException.class)
                .hasMessage("Room is full");
        assertThat(room(roomDTO.getCode()).getMembers()).hasSize(200);
    }

    @Test
    void shouldAllowExistingMemberToReconnectWhenRoomIsFull() {
        RoomDTO roomDTO = createRoom("admin");
        fillRoomToMaxMembers(roomDTO.getCode());

        assertThatCode(() -> roomService.joinToRoom(
                roomDTO.getCode(), "user-1", "member-1",
                member(roomDTO.getCode(), "user-1").getWsToken()))
                .doesNotThrowAnyException();
        assertThat(room(roomDTO.getCode()).getMembers()).hasSize(200);
    }

    // ---------- changeMemberNickname / changeMemberColor ----------

    @Test
    void shouldRejectNicknameChangeInUnknownRoom() {
        assertThatThrownBy(() -> roomService.changeMemberNickname("NONE", ADMIN_ID, "new"))
                .isInstanceOf(RoomException.class)
                .hasMessage("The room doesn't exist");
    }

    @Test
    void shouldRejectNicknameChangeForUnknownMember() {
        RoomDTO roomDTO = createRoom("admin");

        assertThatThrownBy(() -> roomService.changeMemberNickname(roomDTO.getCode(), "unknown", "new"))
                .isInstanceOf(RoomException.class)
                .hasMessage("The member doesn't exist");
    }

    @Test
    void shouldRejectNicknameChangeToExistingNickname() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.joinToRoom(roomDTO.getCode(), "user-1", "Bob");

        assertThatThrownBy(() -> roomService.changeMemberNickname(roomDTO.getCode(), "user-1", "admin"))
                .isInstanceOf(RoomException.class)
                .hasMessage("User with same nickname already exists");
    }

    @Test
    void shouldRejectBlankNicknameChange() {
        RoomDTO roomDTO = createRoom("admin");

        assertThatThrownBy(() -> roomService.changeMemberNickname(roomDTO.getCode(), ADMIN_ID, "   "))
                .isInstanceOf(RoomException.class)
                .hasMessage("Nickname must be at least 1 character length");
    }

    @Test
    void shouldAllowKeepingOwnNickname() {
        RoomDTO roomDTO = createRoom("admin");

        assertThatCode(() -> roomService.changeMemberNickname(roomDTO.getCode(), ADMIN_ID, "admin"))
                .doesNotThrowAnyException();
        assertThat(member(roomDTO.getCode(), ADMIN_ID).getNickname()).isEqualTo("admin");
    }

    @Test
    void shouldTrimAndChangeNickname() {
        RoomDTO roomDTO = createRoom("admin");

        roomService.changeMemberNickname(roomDTO.getCode(), ADMIN_ID, "  Alice  ");

        assertThat(member(roomDTO.getCode(), ADMIN_ID).getNickname()).isEqualTo("Alice");
    }

    @Test
    void shouldRejectColorChangeInUnknownRoom() {
        assertThatThrownBy(() -> roomService.changeMemberColor("NONE", ADMIN_ID, "#ffffff"))
                .isInstanceOf(RoomException.class)
                .hasMessage("The room doesn't exist");
    }

    @Test
    void shouldRejectColorChangeForUnknownMember() {
        RoomDTO roomDTO = createRoom("admin");

        assertThatThrownBy(() -> roomService.changeMemberColor(roomDTO.getCode(), "unknown", "#ffffff"))
                .isInstanceOf(RoomException.class)
                .hasMessage("The member doesn't exist");
    }

    @Test
    void shouldChangeMemberColor() {
        RoomDTO roomDTO = createRoom("admin");

        roomService.changeMemberColor(roomDTO.getCode(), ADMIN_ID, "#123abc");

        assertThat(member(roomDTO.getCode(), ADMIN_ID).getColor()).isEqualTo("#123abc");
    }

    // ---------- changeMemberPlayerStatus ----------

    @Test
    void shouldRejectPlayerStatusChangeInUnknownRoom() {
        assertThatThrownBy(() -> roomService.changeMemberPlayerStatus("NONE", ADMIN_ID, true))
                .isInstanceOf(RoomException.class)
                .hasMessage("The room doesn't exist");
    }

    @Test
    void shouldRejectPlayerStatusChangeForUnknownMember() {
        RoomDTO roomDTO = createRoom("admin");

        assertThatThrownBy(() -> roomService.changeMemberPlayerStatus(roomDTO.getCode(), "unknown", true))
                .isInstanceOf(RoomException.class)
                .hasMessage("The member doesn't exist");
    }

    @Test
    void shouldPromoteSpectatorToPlayer() {
        RoomDTO roomDTO = createRoom("admin");

        roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, true);

        Member admin = member(roomDTO.getCode(), ADMIN_ID);
        assertThat(admin.isPlayer()).isTrue();
        assertThat(admin.isSpectator()).isFalse();
    }

    @Test
    void shouldRejectPlayerStatusChangeWhenMaxPlayersReached() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.joinToRoom(roomDTO.getCode(), "user-1", "Bob");
        roomService.joinToRoom(roomDTO.getCode(), "user-2", "Carol");

        roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, true);
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), "user-1", true);

        assertThatThrownBy(() -> roomService.changeMemberPlayerStatus(roomDTO.getCode(), "user-2", true))
                .isInstanceOf(RoomException.class)
                .hasMessage("Max players reached");
        assertThat(member(roomDTO.getCode(), "user-2").isSpectator()).isTrue();
    }

    @Test
    void shouldRejectPlayerStatusChangeAfterGameStarted() {
        RoomDTO roomDTO = createStartedRoom();

        assertThatThrownBy(() -> roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, false))
                .isInstanceOf(RoomException.class)
                .hasMessage("Game already started");
        assertThat(member(roomDTO.getCode(), ADMIN_ID).isPlayer()).isTrue();
        assertThat(member(roomDTO.getCode(), ADMIN_ID).isReady()).isTrue();
    }

    @Test
    void shouldResetReadyWhenPlayerBecomesSpectator() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, true);
        roomService.changeMemberReadyStatus(roomDTO.getCode(), ADMIN_ID, true);

        roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, false);

        Member admin = member(roomDTO.getCode(), ADMIN_ID);
        assertThat(admin.isPlayer()).isFalse();
        assertThat(admin.isSpectator()).isTrue();
        assertThat(admin.isReady()).isFalse();
    }

    @Test
    void shouldAllowPromotingBackToPlayerAfterBecomingSpectator() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, true);
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, false);

        roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, true);

        Member admin = member(roomDTO.getCode(), ADMIN_ID);
        assertThat(admin.isPlayer()).isTrue();
        assertThat(admin.isReady()).isFalse();
        assertThatCode(() -> roomService.changeMemberReadyStatus(roomDTO.getCode(), ADMIN_ID, true))
                .doesNotThrowAnyException();
        assertThat(admin.isReady()).isTrue();
    }

    // ---------- changeMemberReadyStatus ----------

    @Test
    void shouldRejectReadyStatusForUnknownMember() {
        RoomDTO roomDTO = createRoom("admin");

        assertThatThrownBy(() -> roomService.changeMemberReadyStatus(roomDTO.getCode(), "unknown", true))
                .isInstanceOf(RoomException.class)
                .hasMessage("The member doesn't exist or it is not a player");
    }

    @Test
    void shouldRejectReadyStatusForNonPlayer() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.joinToRoom(roomDTO.getCode(), "user-1", "Bob");

        assertThatThrownBy(() -> roomService.changeMemberReadyStatus(roomDTO.getCode(), "user-1", true))
                .isInstanceOf(RoomException.class)
                .hasMessage("The member doesn't exist or it is not a player");
    }

    @Test
    void shouldRejectReadyStatusAfterGameStarted() {
        RoomDTO roomDTO = createStartedRoom();

        assertThatThrownBy(() -> roomService.changeMemberReadyStatus(roomDTO.getCode(), ADMIN_ID, false))
                .isInstanceOf(RoomException.class)
                .hasMessage("Game already started");
    }

    @Test
    void shouldChangeReadyStatus() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, true);

        roomService.changeMemberReadyStatus(roomDTO.getCode(), ADMIN_ID, true);
        assertThat(member(roomDTO.getCode(), ADMIN_ID).isReady()).isTrue();

        roomService.changeMemberReadyStatus(roomDTO.getCode(), ADMIN_ID, false);
        assertThat(member(roomDTO.getCode(), ADMIN_ID).isReady()).isFalse();
    }

    // ---------- startGame ----------

    @Test
    void shouldRejectStartInUnknownRoom() {
        assertThatThrownBy(() -> roomService.startGame("NONE", ADMIN_ID, true))
                .isInstanceOf(RoomException.class)
                .hasMessage("The room doesn't exist");
    }

    @Test
    void shouldRejectStartByNonAdmin() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.joinToRoom(roomDTO.getCode(), "user-1", "Bob");

        assertThatThrownBy(() -> roomService.startGame(roomDTO.getCode(), "user-1", true))
                .isInstanceOf(RoomException.class)
                .hasMessage("The member doesn't exist or it is not an admin");
        assertThat(room(roomDTO.getCode()).isGameStarted()).isFalse();
    }

    @Test
    void shouldRejectStartWhenNotAllPlayersReady() {
        RoomDTO roomDTO = createRoomWithTwoPlayers();
        roomService.changeMemberReadyStatus(roomDTO.getCode(), ADMIN_ID, true);

        assertThatThrownBy(() -> roomService.startGame(roomDTO.getCode(), ADMIN_ID, false))
                .isInstanceOf(RoomException.class)
                .hasMessage("Some members aren't ready");
        assertThat(room(roomDTO.getCode()).isGameStarted()).isFalse();
        assertThat(gameService.getStartCalls()).isZero();
    }

    @Test
    void shouldForceStartWhenNotAllPlayersReady() {
        RoomDTO roomDTO = createRoomWithTwoPlayers();

        roomService.startGame(roomDTO.getCode(), ADMIN_ID, true);

        assertThat(room(roomDTO.getCode()).isGameStarted()).isTrue();
        assertThat(member(roomDTO.getCode(), ADMIN_ID).isReady()).isTrue();
        assertThat(member(roomDTO.getCode(), "user-1").isReady()).isTrue();
        assertThat(gameService.getStartCalls()).isEqualTo(1);
    }

    @Test
    void shouldRejectStartWhenPlayersLessThanMinimum() {
        gameService = new TestGameService(2, 2);
        roomService = new RoomService<>(gameService);
        RoomDTO roomDTO = createRoom("admin");
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, true);
        roomService.changeMemberReadyStatus(roomDTO.getCode(), ADMIN_ID, true);

        assertThatThrownBy(() -> roomService.startGame(roomDTO.getCode(), ADMIN_ID, false))
                .isInstanceOf(RoomException.class)
                .hasMessage("Players in room less than minimum to start");
        assertThat(room(roomDTO.getCode()).isGameStarted()).isFalse();
        assertThat(gameService.getStartCalls()).isZero();
    }

    @Test
    void shouldNotMutateReadyFlagsWhenForcedStartFails() {
        gameService = new TestGameService(2, 2);
        roomService = new RoomService<>(gameService);
        RoomDTO roomDTO = createRoom("admin");
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, true);

        assertThatThrownBy(() -> roomService.startGame(roomDTO.getCode(), ADMIN_ID, true))
                .isInstanceOf(RoomException.class)
                .hasMessage("Players in room less than minimum to start");

        assertThat(member(roomDTO.getCode(), ADMIN_ID).isReady()).isFalse();
        assertThat(room(roomDTO.getCode()).isGameStarted()).isFalse();
        assertThat(gameService.getStartCalls()).isZero();
    }

    @Test
    void shouldStartGameWhenAllPlayersReady() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, true);
        roomService.changeMemberReadyStatus(roomDTO.getCode(), ADMIN_ID, true);

        roomService.startGame(roomDTO.getCode(), ADMIN_ID, false);

        assertThat(room(roomDTO.getCode()).isGameStarted()).isTrue();
        assertThat(member(roomDTO.getCode(), ADMIN_ID).isReady()).isTrue();
        assertThat(gameService.getStartCalls()).isEqualTo(1);
        assertThat(gameService.getStartedGames()).hasSize(1);
        assertThat(gameService.getStartedGames().getFirst())
                .extracting(Member::getUserId)
                .containsExactly(ADMIN_ID);
    }

    @Test
    void shouldStartGameWhenAdminIsSpectator() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.joinToRoom(roomDTO.getCode(), "player-1", "player");
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), "player-1", true);
        roomService.changeMemberReadyStatus(roomDTO.getCode(), "player-1", true);

        roomService.startGame(roomDTO.getCode(), ADMIN_ID, false);

        assertThat(room(roomDTO.getCode()).isGameStarted()).isTrue();
        assertThat(member(roomDTO.getCode(), ADMIN_ID).isSpectator()).isTrue();
        assertThat(member(roomDTO.getCode(), "player-1").isPlayer()).isTrue();
    }

    @Test
    void shouldRejectSecondStart() {
        RoomDTO roomDTO = createStartedRoom();

        assertThatThrownBy(() -> roomService.startGame(roomDTO.getCode(), ADMIN_ID, true))
                .isInstanceOf(RoomException.class)
                .hasMessage("Game already started");
        assertThat(gameService.getStartCalls()).isEqualTo(1);
    }

    @Test
    void shouldRejectStartWithoutPlayers() {
        RoomDTO roomDTO = createRoom("admin");

        assertThatThrownBy(() -> roomService.startGame(roomDTO.getCode(), ADMIN_ID, true))
                .isInstanceOf(RoomException.class)
                .hasMessage("Players in room less than minimum to start");
        assertThat(gameService.getStartCalls()).isZero();
    }

    // ---------- restartGame ----------

    @Test
    void shouldRejectRestartInUnknownRoom() {
        assertThatThrownBy(() -> roomService.restartGame("NONE", ADMIN_ID))
                .isInstanceOf(RoomException.class)
                .hasMessage("The room doesn't exist");
    }

    @Test
    void shouldRejectRestartWhenGameIsNotStarted() {
        RoomDTO roomDTO = createRoom("admin");

        assertThatThrownBy(() -> roomService.restartGame(roomDTO.getCode(), ADMIN_ID))
                .isInstanceOf(RoomException.class)
                .hasMessage("Game is not started");
    }

    @Test
    void shouldRejectRestartByNonAdmin() {
        RoomDTO roomDTO = createStartedRoom();

        assertThatThrownBy(() -> roomService.restartGame(roomDTO.getCode(), "unknown"))
                .isInstanceOf(RoomException.class)
                .hasMessage("The member doesn't exist or it is not an admin");
        assertThat(room(roomDTO.getCode()).isGameStarted()).isTrue();
        assertThat(gameService.getStartCalls()).isEqualTo(1);
    }

    @Test
    void shouldRestartGameForAdmin() {
        RoomDTO roomDTO = createStartedRoom();

        roomService.restartGame(roomDTO.getCode(), ADMIN_ID);

        assertThat(room(roomDTO.getCode()).isGameStarted()).isTrue();
        assertThat(member(roomDTO.getCode(), ADMIN_ID).isReady()).isTrue();
        assertThat(gameService.getStartCalls()).isEqualTo(2);
        assertThat(gameService.getStartedGames()).hasSize(2);
    }

    @Test
    void shouldKeepGameStartedWhenRestartFails() {
        RoomDTO roomDTO = createStartedRoom();
        gameService.failNextStart(new RoomException("restart failed"));

        assertThatThrownBy(() -> roomService.restartGame(roomDTO.getCode(), ADMIN_ID))
                .isInstanceOf(RoomException.class)
                .hasMessage("restart failed");

        assertThat(room(roomDTO.getCode()).isGameStarted()).isTrue();
        assertThat(gameService.getStartCalls()).isEqualTo(2);
    }

    // ---------- getAllSpectators ----------

    @Test
    void shouldReturnOnlySpectators() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.joinToRoom(roomDTO.getCode(), "user-1", "Bob");
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), "user-1", true);

        List<Member> spectators = roomService.getAllSpectators(roomDTO.getCode());

        assertThat(spectators).extracting(Member::getUserId).containsExactly(ADMIN_ID);
    }

    @Test
    void shouldRejectSpectatorsOfUnknownRoom() {
        assertThatThrownBy(() -> roomService.getAllSpectators("NONE"))
                .isInstanceOf(RoomException.class)
                .hasMessage("The room doesn't exist");
    }

    // ---------- validation ----------

    @Test
    void shouldRejectTooLongNickname() {
        RoomDTO roomDTO = createRoom("admin");

        assertThatThrownBy(() -> roomService.changeMemberNickname(
                roomDTO.getCode(), ADMIN_ID, "a".repeat(33)))
                .isInstanceOf(RoomException.class)
                .hasMessage("Nickname must be at most 32 characters length");
    }

    @Test
    void shouldRejectControlCharactersInNickname() {
        RoomDTO roomDTO = createRoom("admin");

        assertThatThrownBy(() -> roomService.changeMemberNickname(
                roomDTO.getCode(), ADMIN_ID, "bad\u0000name"))
                .isInstanceOf(RoomException.class)
                .hasMessage("Nickname must not contain control characters");
    }

    @Test
    void shouldRejectCaseInsensitiveDuplicateNickname() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.joinToRoom(roomDTO.getCode(), "user-1", "Bob");

        assertThatThrownBy(() -> roomService.joinToRoom(roomDTO.getCode(), "user-2", "BOB"))
                .isInstanceOf(RoomException.class)
                .hasMessage("User with same nickname already exists");
    }

    @Test
    void shouldTreatUnicodeEquivalentNicknamesAsDuplicates() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.joinToRoom(roomDTO.getCode(), "user-1", "\u00e9");
        String decomposed = Normalizer.normalize("\u00e9", Normalizer.Form.NFD);

        assertThat(decomposed).isNotEqualTo("\u00e9");
        assertThatThrownBy(() -> roomService.joinToRoom(roomDTO.getCode(), "user-2", decomposed))
                .isInstanceOf(RoomException.class)
                .hasMessage("User with same nickname already exists");
    }

    @Test
    void shouldRejectInvalidColor() {
        RoomDTO roomDTO = createRoom("admin");

        for (String color : new String[]{null, "", "red", "#fff", "#1234567", "#GGGGGG"}) {
            assertThatThrownBy(() -> roomService.changeMemberColor(roomDTO.getCode(), ADMIN_ID, color))
                    .isInstanceOf(RoomException.class)
                    .hasMessage("Color must be in #RRGGBB format");
        }
    }

    @Test
    void shouldNormalizeColorToLowerCase() {
        RoomDTO roomDTO = createRoom("admin");

        roomService.changeMemberColor(roomDTO.getCode(), ADMIN_ID, "#AABBCC");

        assertThat(member(roomDTO.getCode(), ADMIN_ID).getColor()).isEqualTo("#aabbcc");
    }

    @Test
    void shouldRejectInvalidRoomCode() {
        assertThatThrownBy(() -> roomService.joinToRoom("abc", "user", "Alice"))
                .isInstanceOf(RoomException.class)
                .hasMessage("The room doesn't exist");
        assertThatThrownBy(() -> roomService.joinToRoom("abcde", "user", "Alice"))
                .isInstanceOf(RoomException.class)
                .hasMessage("The room doesn't exist");
    }

    @Test
    void shouldRejectUserIdWithColon() {
        assertThatThrownBy(() -> roomService.createRoom("bad:id", "admin"))
                .isInstanceOf(RoomException.class)
                .hasMessage("Invalid user id");
    }

    @Test
    void shouldRejectTooLongUserId() {
        assertThatThrownBy(() -> roomService.createRoom("u".repeat(65), "admin"))
                .isInstanceOf(RoomException.class)
                .hasMessage("Invalid user id");
    }

    @Test
    void shouldUseConfiguredNicknameLengthAndMaxMembers() {
        ServerSettings settings = new ServerSettings(
                new ServerSettings.RoomSettings(2, Duration.ofMinutes(1), 5),
                ServerSettings.WebSocketSettings.defaults());
        roomService = new RoomService<>(gameService, settings);
        RoomDTO roomDTO = createRoom("admin");
        roomService.joinToRoom(roomDTO.getCode(), "user-1", "Bob");

        assertThatThrownBy(() -> roomService.changeMemberNickname(
                roomDTO.getCode(), ADMIN_ID, "Alice!"))
                .isInstanceOf(RoomException.class)
                .hasMessage("Nickname must be at most 5 characters length");
        assertThatThrownBy(() -> roomService.joinToRoom(roomDTO.getCode(), "user-2", "Carol"))
                .isInstanceOf(RoomException.class)
                .hasMessage("Room is full");
    }

    // ---------- helpers ----------

    private RoomDTO createRoom(String nickname) {
        return roomService.createRoom(ADMIN_ID, nickname);
    }

    private RoomDTO createStartedRoom() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, true);
        roomService.startGame(roomDTO.getCode(), ADMIN_ID, true);
        return roomDTO;
    }

    private RoomDTO createRoomWithTwoPlayers() {
        RoomDTO roomDTO = createRoom("admin");
        roomService.joinToRoom(roomDTO.getCode(), "user-1", "Bob");
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), ADMIN_ID, true);
        roomService.changeMemberPlayerStatus(roomDTO.getCode(), "user-1", true);
        return roomDTO;
    }

    private void fillRoomToMaxMembers(String code) {
        // Администратор уже занимает одно место, всего в комнате может быть 200 участников
        for (int i = 1; i < 200; i++) {
            roomService.joinToRoom(code, "user-" + i, "member-" + i);
        }
    }

    private Room<?> room(String code) {
        return roomService.getRooms().get(code);
    }

    private Member member(String code, String userId) {
        return room(code).getMemberByUserId(userId);
    }
}
