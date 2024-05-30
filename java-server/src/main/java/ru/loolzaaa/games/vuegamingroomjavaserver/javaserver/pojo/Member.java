package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@ToString
@Getter
@Setter
public class Member {
    @JsonIgnore
    private String userId;
    private String nickname;
    private String color;
    private boolean isAdmin;
    private boolean isPlayer;
    private boolean isSpectator;
    private boolean isReady;

    public void setPlayer(boolean isPlayer) {
        this.isPlayer = isPlayer;
        this.isSpectator = !isPlayer;
    }

    public void setSpectator(boolean isSpectator) {
        this.isPlayer = !isSpectator;
        this.isSpectator = isSpectator;
    }
}
