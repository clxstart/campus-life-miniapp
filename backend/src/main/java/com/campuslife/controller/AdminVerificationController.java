package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.VerificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理端人工核验。
 *
 * 当前唯一可信的核验来源就是"管理员人工审核"：写入真实状态并留审计，
 * 不接入任何未经验证的自助认证或第三方身份接口。
 */
@RestController
@RequestMapping("/api/admin/verifications")
@RequiredArgsConstructor
public class AdminVerificationController {

    private final VerificationService service;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       HttpServletRequest request) {
        requireManager(request);
        return ApiResponse.ok(service.listForAdmin(status, page, size));
    }

    @PostMapping("/{userId}/review")
    public ApiResponse<Map<String, Object>> review(@PathVariable Long userId,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        requireManager(request);
        if (body == null) throw new IllegalArgumentException("请填写核验结论");
        return ApiResponse.ok(service.review(
            userId,
            text(body.get("realNameStatus")),
            text(body.get("studentStatus")),
            text(body.get("reason")),
            adminId(request),
            adminName(request),
            ip(request)
        ));
    }

    private static void requireManager(HttpServletRequest request) {
        String role = role(request);
        if (!"super".equals(role) && !"school".equals(role)) throw new ForbiddenException("仅校级管理员可审核认证");
    }

    private static String role(HttpServletRequest request) { return String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE)); }
    private static Long adminId(HttpServletRequest request) { return (Long) request.getAttribute(AdminInterceptor.ADMIN_ID); }
    private static String adminName(HttpServletRequest request) { Object v = request.getAttribute(AdminInterceptor.ADMIN_NAME); return v == null ? "" : String.valueOf(v); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String ip(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : request.getRemoteAddr();
    }
}
