package com.campuslife.config;

import com.campuslife.ha.CirclePresence;
import com.campuslife.ha.ClusterBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 群聊房间管理器：一个用户在同一圈子只保留一条连接。
 *
 * 多实例（finding-10）：
 *  - 本节点只持有自己的连接，跨节点推送走 {@link ClusterBus}，消息不会只在单节点内可见；
 *  - 在线人数与名单由 {@link CirclePresence} 汇总（集群模式读 Redis），不再只统计本机；
 *  - 在线成员带 TTL，节点崩溃后其记录自动过期，不会长期显示幽灵在线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircleWebSocketManager {
    /** 在线成员 TTL：心跳每 30 秒续期一次，90 秒没续期即视为掉线 */
    static final long PRESENCE_TTL_MS = 90_000;

    private final ObjectMapper objectMapper;
    private final CirclePresence presence;
    private final ClusterBus bus;
    private final Map<Long, Map<Long, WebSocketSession>> rooms = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPong = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeat;

    @PostConstruct
    void startHeartbeat() {
        // 其他节点发来的消息：只投递给本节点同圈连接
        bus.listen((circleId, json) -> sendRaw(circleId, json));
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "circle-websocket-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleAtFixedRate(this::heartbeat, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopHeartbeat() {
        if (heartbeat != null) heartbeat.shutdownNow();
    }

    /** 返回被同一用户的新连接替换的旧会话。 */
    public WebSocketSession join(Long circleId, Long userId, WebSocketSession session) {
        lastPong.put(session.getId(), Instant.now().toEpochMilli());
        presence.join(circleId, userId);
        return rooms.computeIfAbsent(circleId, key -> new ConcurrentHashMap<>()).put(userId, session);
    }

    /** 只移除仍然属于该用户的会话，避免旧连接的 close 回调误删新连接。 */
    public boolean leave(Long circleId, Long userId, WebSocketSession session) {
        lastPong.remove(session.getId());
        boolean[] removed = {false};
        rooms.computeIfPresent(circleId, (key, room) -> {
            removed[0] = room.remove(userId, session);
            return room.isEmpty() ? null : room;
        });
        if (removed[0]) presence.leave(circleId, userId);
        return removed[0];
    }

    public void touch(WebSocketSession session) { lastPong.put(session.getId(), Instant.now().toEpochMilli()); }

    public int onlineCount(Long circleId) { return presence.onlineCount(circleId); }

    public List<Long> onlineUserIds(Long circleId) { return presence.onlineUserIds(circleId); }

    /** 本节点投递 + 跨节点转发 */
    public void broadcast(Long circleId, Object payload) {
        final String json;
        try { json = objectMapper.writeValueAsString(payload); }
        catch (Exception error) { log.warn("群聊消息序列化失败", error); return; }
        sendRaw(circleId, json);
        try { bus.publish(circleId, json); }
        catch (RuntimeException error) { log.warn("群聊跨节点广播失败，本节点已投递", error); }
    }

    /** 只投递本节点连接（跨节点消息回调也走这里，不会二次 publish） */
    private void sendRaw(Long circleId, String json) {
        Map<Long, WebSocketSession> room = rooms.get(circleId);
        if (room == null) return;
        room.values().forEach(session -> send(session, json));
    }

    private void send(WebSocketSession session, String json) {
        if (!session.isOpen()) return;
        try {
            synchronized (session) { if (session.isOpen()) session.sendMessage(new TextMessage(json)); }
        } catch (IOException error) { log.warn("群聊推送失败，session={}", session.getId(), error); }
    }

    private void heartbeat() {
        long now = Instant.now().toEpochMilli();
        rooms.forEach((circleId, room) -> {
            List<Long> alive = new ArrayList<>();
            new ArrayList<>(room.entrySet()).forEach(entry -> {
                WebSocketSession session = entry.getValue();
                Long pong = lastPong.get(session.getId());
                if (pong == null || now - pong > PRESENCE_TTL_MS) {
                    try { session.close(); } catch (IOException ignored) { }
                    return;
                }
                alive.add(entry.getKey());
                send(session, "{\"type\":\"ping\"}");
            });
            // 续期本节点在线成员，使其他节点汇总到的在线人数保持准确
            try { presence.refresh(circleId, alive, PRESENCE_TTL_MS); }
            catch (RuntimeException error) { log.warn("群聊在线心跳续期失败，circleId={}", circleId, error); }
        });
    }
}
