package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.AiChatService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI 陪聊（关键词匹配）。
 *
 * 刻意不要求登录 —— 游客也能试试，降低体验门槛；
 * 登录后能拿到本校数据，回复更具体。
 */
@RestController @RequestMapping("/api/ai") @RequiredArgsConstructor
public class AiChatController {

    private final AiChatService service;

    /** 发一条消息，拿到回复。游客可用；已登录时以 token 身份为准，回复能带本校数据。 */
    @PostMapping("/chat")
    public ApiResponse<Map<String, Object>> chat(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = CurrentUser.resolve(request, longOr(body.get("userId"), null), 1L);
        Long schoolId = body.get("schoolId") == null ? null : longOr(body.get("schoolId"), 0L);
        String message = body.get("message") == null ? "" : String.valueOf(body.get("message"));
        return ApiResponse.ok(service.chat(userId, schoolId, message));
    }

    /** 欢迎语（小程序进入对话页时读，可被管理端配置覆盖） */
    @GetMapping("/welcome")
    public ApiResponse<Map<String, Object>> welcome() {
        return ApiResponse.ok(service.welcome());
    }

    private static Long longOr(Object value, Long fallback) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(value).trim()); } catch (Exception e) { return fallback; }
    }
}
