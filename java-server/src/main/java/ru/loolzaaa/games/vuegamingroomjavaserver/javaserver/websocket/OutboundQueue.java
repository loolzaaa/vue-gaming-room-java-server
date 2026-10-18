package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import java.util.LinkedList;
import java.util.ListIterator;

/**
 * Ограниченная очередь исходящих сообщений одной WebSocket-сессии.
 * <p>
 * Для коалесцируемых событий (например, {@code GAME_STATE}) новое сообщение
 * заменяет уже ожидающее сообщение с тем же именем, сохраняя его позицию
 * в очереди. Это не даёт медленному клиенту накопить устаревшие состояния.
 */
final class OutboundQueue {

    private final LinkedList<OutboundMessage> queue = new LinkedList<>();
    private final int capacity;
    private boolean closed;

    OutboundQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * @return {@code false}, если очередь закрыта или переполнена
     */
    synchronized boolean offer(OutboundMessage message) {
        if (closed) {
            return false;
        }
        if (message.coalescable()) {
            ListIterator<OutboundMessage> iterator = queue.listIterator();
            while (iterator.hasNext()) {
                OutboundMessage pending = iterator.next();
                if (pending.coalescable() && pending.event().equals(message.event())) {
                    iterator.set(message);
                    return true;
                }
            }
        }
        if (queue.size() >= capacity) {
            return false;
        }
        queue.addLast(message);
        notifyAll();
        return true;
    }

    /**
     * @return следующее сообщение или {@code null}, если очередь закрыта
     */
    synchronized OutboundMessage take() throws InterruptedException {
        while (queue.isEmpty() && !closed) {
            wait();
        }
        if (queue.isEmpty()) {
            return null;
        }
        return queue.removeFirst();
    }

    synchronized void close() {
        closed = true;
        queue.clear();
        notifyAll();
    }

    synchronized int size() {
        return queue.size();
    }
}
