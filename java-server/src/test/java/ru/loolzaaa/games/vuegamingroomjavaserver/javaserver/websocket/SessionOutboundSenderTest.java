package ru.loolzaaa.games.vuegamingroomjavaserver.javaserver.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionOutboundSenderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldDeliverMessagesInOrder() throws Exception {
        WebSocketSession session = session();
        List<String> payloads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            payloads.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any());

        SessionOutboundSender sender = new SessionOutboundSender(session, mapper, 10);
        try {
            assertThat(sender.send("A", new TextNode("a"), false)).isTrue();
            assertThat(sender.send("B", new TextNode("b"), false)).isTrue();

            Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> payloads.size() == 2);
            assertThat(mapper.readTree(payloads.get(0)).asText()).isEqualTo("a");
            assertThat(mapper.readTree(payloads.get(1)).asText()).isEqualTo("b");
        } finally {
            sender.close(CloseStatus.NORMAL);
        }
    }

    @Test
    void shouldNotBlockProducerWhenSendIsSlow() throws Exception {
        WebSocketSession session = session();
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstSendStarted.countDown();
            releaseFirstSend.await(2, TimeUnit.SECONDS);
            return null;
        }).when(session).sendMessage(any());

        SessionOutboundSender sender = new SessionOutboundSender(session, mapper, 10);
        try {
            assertThat(sender.send("A", new TextNode("a"), false)).isTrue();
            assertThat(firstSendStarted.await(1, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            assertThat(sender.send("B", new TextNode("b"), false)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(Duration.ofMillis(500));
        } finally {
            releaseFirstSend.countDown();
            sender.close(CloseStatus.NORMAL);
        }
    }

    @Test
    void shouldCloseSessionWhenQueueOverflows() throws Exception {
        WebSocketSession session = session();
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstSendStarted.countDown();
            releaseFirstSend.await(2, TimeUnit.SECONDS);
            return null;
        }).when(session).sendMessage(any());

        SessionOutboundSender sender = new SessionOutboundSender(session, mapper, 1);
        try {
            assertThat(sender.send("A", new TextNode("a"), false)).isTrue();
            assertThat(firstSendStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(sender.send("B", new TextNode("b"), false)).isTrue();

            assertThat(sender.send("C", new TextNode("c"), false)).isFalse();

            verify(session, timeout(1_000)).close(any(CloseStatus.class));
        } finally {
            releaseFirstSend.countDown();
            sender.close(CloseStatus.NORMAL);
        }
    }

    @Test
    void shouldStopSendingAfterClose() throws Exception {
        WebSocketSession session = session();
        List<String> payloads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            payloads.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any());

        SessionOutboundSender sender = new SessionOutboundSender(session, mapper, 10);
        assertThat(sender.send("A", new TextNode("a"), false)).isTrue();
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> payloads.size() == 1);

        sender.close(CloseStatus.NORMAL);

        assertThat(sender.send("B", new TextNode("b"), false)).isFalse();
        verify(session).close(CloseStatus.NORMAL);
    }

    private WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
