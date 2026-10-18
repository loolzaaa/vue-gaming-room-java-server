package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberTest {

    @Test
    void shouldSwitchPlayerAndSpectatorFlags() {
        Member member = new Member();

        member.setPlayer(true);
        assertThat(member.isPlayer()).isTrue();
        assertThat(member.isSpectator()).isFalse();

        member.setPlayer(false);
        assertThat(member.isPlayer()).isFalse();
        assertThat(member.isSpectator()).isTrue();

        member.setSpectator(false);
        assertThat(member.isPlayer()).isTrue();
        assertThat(member.isSpectator()).isFalse();

        member.setSpectator(true);
        assertThat(member.isPlayer()).isFalse();
        assertThat(member.isSpectator()).isTrue();
    }

    @Test
    void shouldKeepPlayerAndSpectatorFlagsMutuallyExclusive() {
        Member member = new Member();
        member.setPlayer(true);
        member.setSpectator(true);

        assertThat(member.isPlayer()).isFalse();
        assertThat(member.isSpectator()).isTrue();

        member.setSpectator(false);
        member.setPlayer(false);

        assertThat(member.isPlayer()).isFalse();
        assertThat(member.isSpectator()).isTrue();
    }

    @Test
    void shouldBeEqualByUserId() {
        Member first = member("user-1", "Alice");
        Member second = member("user-1", "Bob");

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSameHashCodeAs(second);
    }

    @Test
    void shouldNotBeEqualForDifferentUserIds() {
        Member first = member("user-1", "Alice");
        Member second = member("user-2", "Alice");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldNotBeEqualToNullOrOtherType() {
        Member member = member("user-1", "Alice");

        assertThat(member).isEqualTo(member);
        assertThat(member).isNotEqualTo(null);
        assertThat(member).isNotEqualTo("user-1");
    }

    private Member member(String userId, String nickname) {
        Member member = new Member();
        member.setUserId(userId);
        member.setNickname(nickname);
        return member;
    }
}
