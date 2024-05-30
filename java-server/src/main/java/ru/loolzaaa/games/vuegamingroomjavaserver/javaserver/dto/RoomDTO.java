package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RoomDTO {
    private String code;
    private String wsToken;
    private String gameName;
    private boolean isGameStarted;
    private String userId;
}
