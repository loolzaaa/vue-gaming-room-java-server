package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Асинхронный отправитель сообщений одной WebSocket-сессии.
 * <p>
 * Использует virtual thread и ограниченную очередь: медленный клиент
 * блокирует только свой sender, но не broadcast и не игровые события.
 */
final class SessionOutboundSender {

    private static final Logger log = LoggerFactory.getLogger(SessionOutboundSender.class);

    private final WebSocketSession session;
    private final ObjectMapper mapper;
    private final OutboundQueue queue;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread senderThread;

    SessionOutboundSender(WebSocketSession session, ObjectMapper mapper, int queueCapacity) {
        this.session = session;
        this.mapper = mapper;
        this.queue = new OutboundQueue(queueCapacity);
        this.senderThread = Thread.ofVirtual()
                .name("vgr-ws-sender-" + session.getId())
                .start(this::run);
    }

    /**
     * Ставит сообщение в очередь отправки.
     *
     * @return {@code false}, если сессия уже закрыта или очередь переполнена
     */
    boolean send(String event, JsonNode payload, boolean coalescable) {
        if (closed.get()) {
            return false;
        }
        if (!queue.offer(new OutboundMessage(event, payload, coalescable))) {
            log.warn("Outbound queue overflow for websocket session {}; closing session", session.getId());
            close(CloseStatus.SESSION_NOT_RELIABLE);
            return false;
        }
        return true;
    }

    void close(CloseStatus status) {
        if (closed.compareAndSet(false, true)) {
            queue.close();
            senderThread.interrupt();
            closeSession(status);
        }
    }

    private void run() {
        try {
            while (!closed.get()) {
                OutboundMessage message = queue.take();
                if (message == null) {
                    break;
                }
                if (!session.isOpen()) {
                    break;
                }
                try {
                    String json = mapper.writeValueAsString(message.payload());
                    session.sendMessage(new TextMessage(json));
                } catch (IOException | RuntimeException e) {
                    log.warn("Unable to send message to websocket session {}: {}",
                            session.getId(), e.getLocalizedMessage());
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close(CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    private void closeSession(CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Unable to close websocket session {}: {}",
                    session.getId(), e.getLocalizedMessage());
        }
    }
}
