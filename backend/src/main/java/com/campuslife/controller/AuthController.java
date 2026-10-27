package com.campuslife.controller;

import com.campuslife.dto.*;
import com.campuslife.service.AuthService;
import com.campuslife.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService service;
    private final RateLimiter limiter;

    // 微信登录：前端 wx.login() 拿 code 换取 token
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        // 防撞库：同 IP 每分钟最多 10 次。正常用户不会这样连续登录。
        limiter.acquire(RateLimiter.LOGIN, "login:" + ip(request));
        return ApiResponse.ok(service.login(body == null ? null : body.get("code")));
    }

    // 开发登录：未配置微信 AppID 时启用，便于 touristappid 下跑通流程
    @PostMapping("/dev-login")
    public ApiResponse<Map<String, Object>> devLogin(@RequestBody(required = false) Map<String, Long> body,
                                                     HttpServletRequest request) {
        // 开发登录同样限流：它能在演示环境下切换任意账号，不限制等于开了后门
        limiter.acquire(RateLimiter.LOGIN, "dev-login:" + ip(request));
        return ApiResponse.ok(service.devLogin(body == null ? null : body.get("userId")));
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(Map.of("devLogin", service.devLoginEnabled()));
    }

    private static String ip(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
