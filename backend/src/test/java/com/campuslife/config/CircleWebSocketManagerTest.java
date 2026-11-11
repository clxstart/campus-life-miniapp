package com.campuslife.config;

import com.campuslife.ha.CirclePresence;
import com.campuslife.ha.ClusterBus;
import com.campuslife.ha.LocalCirclePresence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Finding 10：群聊消息既要投递本节点，也要跨节点转发；在线名单来自可共享的 presence */
class CircleWebSocketManagerTest {

    /** 记录 publish，并能模拟「另一个节点」发来的消息 */
    static final class FakeBus implements ClusterBus {
        final List<Long> publishedTo = new ArrayList<>();
        final List<String> published = new ArrayList<>();
        BiConsumer<Long, String> listener;

        @Override public void publish(Long circleId, String json) { publishedTo.add(circleId); published.add(json); }
        @Override public void listen(BiConsumer<Long, String> handler) { this.listener = handler; }
        @Override public String nodeId() { return "node-a"; }

        void inboundFromOtherNode(Long circleId, String json) { listener.accept(circleId, json); }
    }

    private static WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("s-" + System.nanoTime());
        return session;
    }

    private static CircleWebSocketManager manager(CirclePresence presence, ClusterBus bus) {
        CircleWebSocketManager manager = new CircleWebSocketManager(new ObjectMapper(), presence, bus);
        manager.startHeartbeat();
        return manager;
    }

    @Test
    void broadcastDeliversLocallyAndForwardsToOtherNodes() throws Exception {
        FakeBus bus = new FakeBus();
        CircleWebSocketManager manager = manager(new LocalCirclePresence(), bus);
        WebSocketSession session = session();
        manager.join(1L, 10L, session);

        manager.broadcast(1L, Map.of("type", "message", "data", Map.of("id", 5)));

        verify(session, times(1)).sendMessage(any(TextMessage.class));
        assertThat(bus.publishedTo).containsExactly(1L);
        assertThat(bus.published.get(0)).contains("\"message\"");
    }

    @Test
    void messageFromOtherNodeIsDeliveredWithoutRebroadcast() throws Exception {
        FakeBus bus = new FakeBus();
        CircleWebSocketManager manager = manager(new LocalCirclePresence(), bus);
        WebSocketSession session = session();
        manager.join(1L, 10L, session);

        bus.inboundFromOtherNode(1L, "{\"type\":\"message\"}");

        verify(session, times(1)).sendMessage(any(TextMessage.class));
        // 关键：转发消息不得再次 publish，否则多节点之间会无限放大
        assertThat(bus.published).isEmpty();
    }

    @Test
    void messageForAnotherCircleIsNotDelivered() throws Exception {
        FakeBus bus = new FakeBus();
        CircleWebSocketManager manager = manager(new LocalCirclePresence(), bus);
        WebSocketSession session = session();
        manager.join(1L, 10L, session);

        bus.inboundFromOtherNode(2L, "{\"type\":\"message\"}");

        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void onlineCountComesFromPresenceAndFollowsJoinLeave() {
        CirclePresence presence = new LocalCirclePresence();
        CircleWebSocketManager manager = manager(presence, new FakeBus());
        WebSocketSession a = session();
        WebSocketSession b = session();
        manager.join(1L, 10L, a);
        manager.join(1L, 11L, b);
        assertThat(manager.onlineCount(1L)).isEqualTo(2);
        assertThat(manager.onlineUserIds(1L)).containsExactlyInAnyOrder(10L, 11L);

        manager.leave(1L, 10L, a);
        assertThat(manager.onlineCount(1L)).isEqualTo(1);
        manager.leave(1L, 11L, b);
        assertThat(manager.onlineCount(1L)).isZero();
    }

    @Test
    void replacedSessionDoesNotDropTheUserWhenOldConnectionCloses() {
        CirclePresence presence = new LocalCirclePresence();
        CircleWebSocketManager manager = manager(presence, new FakeBus());
        WebSocketSession old = session();
        WebSocketSession fresh = session();
        manager.join(1L, 10L, old);
        manager.join(1L, 10L, fresh);
        // 旧连接的 close 回调不能把新连接的在线状态删掉
        assertThat(manager.leave(1L, 10L, old)).isFalse();
        assertThat(manager.onlineCount(1L)).isEqualTo(1);
    }

    @Test
    void closingBroadcastIsStillForwardedAcrossNodes() throws Exception {
        // 断线事件也要跨节点，否则其他节点看到的在线名单不会更新
        FakeBus bus = new FakeBus();
        CircleWebSocketManager manager = manager(new LocalCirclePresence(), bus);
        manager.broadcast(3L, Map.of("type", "leave", "data", Map.of("userId", 7)));
        assertThat(bus.publishedTo).containsExactly(3L);
    }
}
