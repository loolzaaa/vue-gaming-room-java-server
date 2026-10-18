package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundQueueTest {

    @Test
    void shouldKeepFifoOrder() throws Exception {
        OutboundQueue queue = new OutboundQueue(10);

        assertThat(queue.offer(message("A", "a"))).isTrue();
        assertThat(queue.offer(message("B", "b"))).isTrue();

        assertThat(queue.take().event()).isEqualTo("A");
        assertThat(queue.take().event()).isEqualTo("B");
    }

    @Test
    void shouldReplacePendingCoalescableMessageAndKeepItsPosition() throws Exception {
        OutboundQueue queue = new OutboundQueue(10);

        queue.offer(message("GAME_STATE", "old"));
        queue.offer(message("ERROR", "error"));
        queue.offer(message("GAME_STATE", "new"));

        assertThat(queue.take().payload().asText()).isEqualTo("new");
        assertThat(queue.take().event()).isEqualTo("ERROR");
    }

    @Test
    void shouldNotCoalesceNonCoalescableMessages() throws Exception {
        OutboundQueue queue = new OutboundQueue(10);

        queue.offer(new OutboundMessage("CUSTOM", new TextNode("one"), false));
        queue.offer(new OutboundMessage("CUSTOM", new TextNode("two"), false));

        assertThat(queue.take().payload().asText()).isEqualTo("one");
        assertThat(queue.take().payload().asText()).isEqualTo("two");
    }

    @Test
    void shouldRejectMessageWhenQueueIsFull() {
        OutboundQueue queue = new OutboundQueue(1);

        assertThat(queue.offer(new OutboundMessage("A", new TextNode("a"), false))).isTrue();
        assertThat(queue.offer(new OutboundMessage("B", new TextNode("b"), false))).isFalse();
    }

    @Test
    void shouldWakeBlockedConsumerOnClose() throws Exception {
        OutboundQueue queue = new OutboundQueue(1);
        AtomicReference<OutboundMessage> result = new AtomicReference<>();

        Thread consumer = new Thread(() -> {
            try {
                result.set(queue.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        queue.close();
        consumer.join(2_000);

        assertThat(consumer.isAlive()).isFalse();
        assertThat(result.get()).isNull();
        assertThat(queue.offer(message("A", "a"))).isFalse();
    }

    private OutboundMessage message(String event, String payload) {
        return new OutboundMessage(event, new TextNode(payload), true);
    }
}
