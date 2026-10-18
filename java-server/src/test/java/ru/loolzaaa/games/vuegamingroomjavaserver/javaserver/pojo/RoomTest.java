package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo;

import org.junit.jupiter.api.Test;
import ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.support.TestFixtures.TestGame;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RoomTest {

    @Test
    void shouldFindMemberByUserId() {
        Member alice = member("user-1", "Alice");
        Member bob = member("user-2", "Bob");
        Room<TestGame> room = room("ABCD");
        room.getMembers().add(alice);
        room.getMembers().add(bob);

        assertThat(room.getMemberByUserId("user-2")).isSameAs(bob);
        assertThat(room.getMemberByUserId("user-1")).isSameAs(alice);
        assertThat(room.getMemberByUserId("unknown")).isNull();
        assertThat(room.getMemberByUserId(null)).isNull();
        // Поиск выполняется по идентификатору, а не по никнейму
        assertThat(room.getMemberByUserId("Alice")).isNull();
    }

    @Test
    void shouldInitializeRoomState() {
        LocalDateTime before = LocalDateTime.now();
        Room<TestGame> room = new Room<>("ABCD", new TestGame());

        assertThat(room.getCode()).isEqualTo("ABCD");
        assertThat(room.getMembers()).isEmpty();
        assertThat(room.isGameStarted()).isFalse();
        assertThat(room.getLastActivity()).isAfterOrEqualTo(before);
    }

    @Test
    void shouldBeEqualByCode() {
        Room<TestGame> first = new Room<>("ABCD", new TestGame());
        Room<TestGame> second = new Room<>("ABCD", new TestGame("OTHER", 2, 4));

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSameHashCodeAs(second);
    }

    @Test
    void shouldNotBeEqualForDifferentCodes() {
        Room<TestGame> first = room("ABCD");
        Room<TestGame> second = room("EFGH");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldNotBeEqualToNullOrOtherType() {
        Room<TestGame> room = room("ABCD");

        assertThat(room).isEqualTo(room);
        assertThat(room).isNotEqualTo(null);
        assertThat(room).isNotEqualTo("ABCD");
    }

    private Room<TestGame> room(String code) {
        return new Room<>(code, new TestGame());
    }

    private Member member(String userId, String nickname) {
        Member member = new Member();
        member.setUserId(userId);
        member.setNickname(nickname);
        return member;
    }
}
