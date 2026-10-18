package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * Сообщение, ожидающее отправки в конкретную WebSocket-сессию.
 *
 * @param event       имя события для возможного коалесцирования
 * @param payload     готовое JSON-дерево сообщения
 * @param coalescable можно ли заменить уже ожидающее сообщение с тем же event
 */
record OutboundMessage(String event, JsonNode payload, boolean coalescable) {

    OutboundMessage {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        // Снимок на момент постановки в очередь: игра/обработчик не должны
        // влиять на сообщение после возврата из sendEvent/sendMessage.
        payload = payload.deepCopy();
    }
}
