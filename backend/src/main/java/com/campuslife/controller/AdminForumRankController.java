package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.ForumRankService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理端：榜单快照查看与手动重结算。
 *
 * 为什么必须有重结算入口：定时任务可能因为发版/重启错过 00:10 那一次，
 * 没有后台补跑，那一天的上榜者就永远拿不到奖励（设计稿「三、设计取舍」明确要求）。
 *
 * 鉴权：/api/admin/** 由 AdminInterceptor 强制校验管理端 token；这里再限定 super|school 两种角色。
 */
@RestController
@RequestMapping("/api/admin/forum-rank")
@RequiredArgsConstructor
public class AdminForumRankController {

    private final ForumRankService service;

    /** 查看任意周期快照与发放状态；period 为空取最近一期。 */
    @GetMapping
    public ApiResponse<Map<String, Object>> board(@RequestParam(defaultValue = "daily") String type,
                                                  @RequestParam(required = false) String period,
                                                  HttpServletRequest request) {
        requireManager(request);
        return ApiResponse.ok(service.adminBoard(type, period));
    }

    /**
     * 手动重结算 {type, periodKey}。
     *
     * 幂等：已发放的行不会重复发分、不会重复通知；只对 awarded=0 的行补发。
     */
    @PostMapping("/resettle")
    public ApiResponse<Map<String, Object>> resettle(@RequestBody(required = false) Map<String, Object> body,
                                                     HttpServletRequest request) {
        requireManager(request);
        Map<String, Object> input = body == null ? Map.of() : body;
        String type = input.get("type") == null ? "daily" : String.valueOf(input.get("type"));
        String periodKey = input.get("periodKey") == null ? "" : String.valueOf(input.get("periodKey"));
        return ApiResponse.ok(service.resettle(type, periodKey));
    }

    private static void requireManager(HttpServletRequest request) {
        String role = String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE));
        if (!"super".equals(role) && !"school".equals(role)) throw new ForbiddenException("仅校级管理员可操作论坛榜单");
    }
}