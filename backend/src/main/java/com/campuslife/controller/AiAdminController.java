package com.campuslife.controller;

import com.campuslife.dto.ApiResponse;
import com.campuslife.service.AiChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI 机器人配置（Web 管理端）。
 *
 * 路径挂在 /api/admin/** 下，由 AdminInterceptor 强制校验 token ——
 * 这是和公开的 /api/ai/** 的关键区别：规则和话术属于运营配置，不能匿名读。
 */
@RestController @RequestMapping("/api/admin") @RequiredArgsConstructor
public class AiAdminController {

    private final AiChatService service;

    // ---------- 规则管理 ----------

    @GetMapping("/ai-rules")
    public ApiResponse<List<Map<String, Object>>> rules(@RequestParam(defaultValue = "") String category,
                                                        @RequestParam(defaultValue = "") String keyword) {
        return ApiResponse.ok(service.rulesForAdmin(category, keyword));
    }

    @PostMapping("/ai-rules")
    public ApiResponse<Map<String, Object>> createRule(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.createRule(body));
    }

    @PutMapping("/ai-rules/{id}")
    public ApiResponse<Object> updateRule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        service.updateRule(id, body);
        return ApiResponse.ok(null);
    }

    @PostMapping("/ai-rules/{id}/status")
    public ApiResponse<Object> setRuleStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        service.setRuleStatus(id, intOr(body.get("status"), 1));
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/ai-rules/{id}")
    public ApiResponse<Object> deleteRule(@PathVariable Long id) {
        service.deleteRule(id);
        return ApiResponse.ok(null);
    }

    // ---------- 未命中统计 ----------

    @GetMapping("/ai-stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(service.stats());
    }

    // ---------- 欢迎语 ----------

    @PutMapping("/ai-welcome")
    public ApiResponse<Object> setWelcome(@RequestBody Map<String, Object> body) {
        service.setWelcome(body);
        return ApiResponse.ok(null);
    }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value).trim()); } catch (Exception e) { return fallback; }
    }
}
