package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

@ToString
@Getter
@RequiredArgsConstructor
public abstract class AbstractPlayer {
    @JsonIgnore
    private final Member member;
}
