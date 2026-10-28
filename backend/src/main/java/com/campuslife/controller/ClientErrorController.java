package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.ClientErrorService;
import com.campuslife.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 前端错误上报与管理端查看。
 *
 * 上报接口刻意保持**公开**：小程序在未登录状态下也可能崩溃，
 * 如果要求登录，最需要被看到的错误反而收不上来。用限流代替鉴权。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ClientErrorController {

    private final ClientErrorService service;
    private final RateLimiter limiter;

    /** 公开上报：同 IP 每分钟最多 30 条，够用且防刷。 */
    @PostMapping("/log/client")
    public ApiResponse<Object> report(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        Map<String, Object> input = body == null ? Map.of() : body;
        String ip = clientIp(request);
        limiter.acquire(new RateLimiter.Rule(60_000L, 30, "错误上报"), "client-error:" + ip);
        service.report(
            text(input.get("client")),
            text(input.get("page")),
            text(input.get("message")),
            text(input.get("stack")),
            text(request.getHeader("User-Agent")),
            CurrentUser.optional(request));
        return ApiResponse.ok(null);
    }

    // ---- 管理端 ----

    @GetMapping("/admin/client-errors")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String client,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(client, keyword, page, size));
    }

    @PostMapping("/admin/client-errors/purge")
    public ApiResponse<Map<String, Object>> purge(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(Map.of("deleted", service.purge(days)));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
