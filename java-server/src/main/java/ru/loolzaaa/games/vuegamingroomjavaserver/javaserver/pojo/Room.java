package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

@Getter
public class Room<G extends Game> {

    @Setter
    private String code;
    private final List<Member> members = new CopyOnWriteArrayList<>();
    private final G game;
    @JsonIgnore
    private final ReentrantLock gameLock = new ReentrantLock();
    @Setter
    private volatile boolean gameStarted;
    @Setter
    private volatile LocalDateTime lastActivity;

    public Room(String code, G game) {
        this.code = code;
        this.lastActivity = LocalDateTime.now();
        this.game = game;
    }

    public Member getMemberByUserId(String userId) {
        return members.stream()
                .filter(m -> m.getUserId().equals(userId))
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Room<?> room = (Room<?>) o;
        return Objects.equals(code, room.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }
}
