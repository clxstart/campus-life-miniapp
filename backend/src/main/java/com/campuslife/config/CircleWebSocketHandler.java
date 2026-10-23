package com.campuslife.config;

import com.campuslife.service.CircleChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 原生 WebSocket 处理器。客户端只发送 pong，消息写入始终走 HTTP。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircleWebSocketHandler extends TextWebSocketHandler {
    private final JwtUtil jwt;
    private final CircleChatService chat;
    private final CircleWebSocketManager manager;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try {
            Map<String, String> query = query(session.getUri());
            Long userId = jwt.parse(query.get("token"));
            Long circleId = parseId(query.get("circleId"));
            Map<String, Object> circle = chat.assertMember(circleId, userId);
            session.getAttributes().put("circleId", circleId);
            session.getAttributes().put("userId", userId);
            session.getAttributes().put("userName", String.valueOf(userName(userId)));
            WebSocketSession previous = manager.join(circleId, userId, session);
            if (previous != null && previous != session) try { previous.close(CloseStatus.NORMAL); } catch (Exception ignored) { }
            manager.broadcast(circleId, Map.of("type", "join", "data", Map.of("userId", userId, "userName", userName(userId))));
            broadcastOnline(circleId);
        } catch (Exception error) {
            reject(session, error.getMessage() == null ? "群聊连接失败" : error.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            if (node != null && "pong".equalsIgnoreCase(node.path("type").asText())) manager.touch(session);
        } catch (Exception error) { log.debug("忽略非法群聊帧", error); }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long circleId = id(session.getAttributes().get("circleId"));
        Long userId = id(session.getAttributes().get("userId"));
        if (circleId == null || userId == null || !manager.leave(circleId, userId, session)) return;
        manager.broadcast(circleId, Map.of("type", "leave", "data", Map.of("userId", userId, "userName", session.getAttributes().getOrDefault("userName", "校园同学"))));
        broadcastOnline(circleId);
    }

    private void broadcastOnline(Long circleId) {
        List<Map<String, Object>> users = manager.onlineUserIds(circleId).stream()
            .map(userId -> Map.<String, Object>of("userId", userId, "userName", userName(userId)))
            .toList();
        manager.broadcast(circleId, Map.of("type", "online", "data", Map.of("count", users.size(), "users", users)));
    }

    private String userName(Long userId) {
        // CircleChatService 的成员校验已经确认用户存在，使用圈子消息查询侧的统一名称兜底。
        return chat.userName(userId);
    }

    private void reject(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("type", "error", "data", Map.of("msg", message)))));
                session.close(new CloseStatus(1008, message));
            }
        } catch (Exception error) { log.debug("关闭未授权群聊连接失败", error); }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new HashMap<>();
        if (uri == null || uri.getRawQuery() == null) return result;
        for (String part : uri.getRawQuery().split("&")) {
            int at = part.indexOf('=');
            String key = at < 0 ? part : part.substring(0, at);
            String value = at < 0 ? "" : part.substring(at + 1);
            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }

    private static Long parseId(String value) {
        try { return value == null ? null : Long.valueOf(value); } catch (NumberFormatException ignored) { return null; }
    }

    private static Long id(Object value) { return value instanceof Number n ? n.longValue() : parseId(String.valueOf(value)); }
}
