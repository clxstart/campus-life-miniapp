package com.campuslife.controller;

import com.campuslife.config.AuthInterceptor;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.CircleChatService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 圈子群聊 HTTP API；WebSocket 只负责推送，不接收消息写入。 */
@RestController
@RequestMapping("/api/circle-messages")
@RequiredArgsConstructor
public class CircleChatController {
    private final CircleChatService chat;
    private final com.campuslife.config.CircleWebSocketManager sockets;

    @GetMapping
    public ApiResponse<Map<String, Object>> history(@RequestParam Long circleId,
                                                     @RequestParam(required = false) Long beforeId,
                                                     @RequestParam(required = false) Long afterId,
                                                     @RequestParam(defaultValue = "30") int limit,
                                                     HttpServletRequest request) {
        return ApiResponse.ok(chat.history(circleId, resolveUser(request), beforeId, afterId, limit));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> send(@RequestBody(required = false) Map<String, Object> body,
                                                 HttpServletRequest request) {
        Map<String, Object> input = body == null ? Map.of() : body;
        Long circleId = number(input.get("circleId"));
        Long userId = resolveUser(request);
        CircleChatService.SendResult result = chat.send(circleId, userId, text(input.get("type")),
            text(input.get("content")), text(input.get("image")), text(input.get("clientId")));
        if (result.created()) sockets.broadcast(circleId, Map.of("type", "message", "data", result.message()));
        return ApiResponse.ok(result.message());
    }

    @PostMapping("/read")
    public ApiResponse<Object> read(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> input = body == null ? Map.of() : body;
        chat.markRead(number(input.get("circleId")), resolveUser(request), number(input.get("lastReadId")));
        return ApiResponse.ok(null);
    }

    @GetMapping("/unread")
    public ApiResponse<List<Map<String, Object>>> unread(HttpServletRequest request) {
        return ApiResponse.ok(chat.unread(resolveUser(request)));
    }

    private static Long resolveUser(HttpServletRequest request) {
        Object authenticated = request.getAttribute(AuthInterceptor.USER_ID);
        if (authenticated instanceof Number n) return n.longValue();
        return null;
    }

    private static Long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return value == null || String.valueOf(value).isBlank() ? null : Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
