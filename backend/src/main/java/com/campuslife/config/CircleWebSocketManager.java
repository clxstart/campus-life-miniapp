package com.campuslife.config;

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

/** 进程内房间管理器：一个用户在同一圈子只保留一条连接。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircleWebSocketManager {
    private final ObjectMapper objectMapper;
    private final Map<Long, Map<Long, WebSocketSession>> rooms = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPong = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeat;

    @PostConstruct
    void startHeartbeat() {
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
        return removed[0];
    }

    public void touch(WebSocketSession session) { lastPong.put(session.getId(), Instant.now().toEpochMilli()); }

    public int onlineCount(Long circleId) {
        Map<Long, WebSocketSession> room = rooms.get(circleId);
        return room == null ? 0 : room.size();
    }

    public List<Long> onlineUserIds(Long circleId) {
        Map<Long, WebSocketSession> room = rooms.get(circleId);
        return room == null ? List.of() : new ArrayList<>(room.keySet());
    }

    public void broadcast(Long circleId, Object payload) {
        final String json;
        try { json = objectMapper.writeValueAsString(payload); }
        catch (Exception error) { log.warn("群聊消息序列化失败", error); return; }
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
        rooms.forEach((circleId, room) -> new ArrayList<>(room.entrySet()).forEach(entry -> {
            WebSocketSession session = entry.getValue();
            Long pong = lastPong.get(session.getId());
            if (pong == null || now - pong > 90_000) {
                try { session.close(); } catch (IOException ignored) { }
                return;
            }
            send(session, "{\"type\":\"ping\"}");
        }));
    }
}
