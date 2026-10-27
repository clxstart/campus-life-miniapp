package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.CurrentUser;
import com.campuslife.config.UnauthorizedException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.ContentReportService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 内容举报。
 *
 * 用户侧：POST /api/reports（需登录 —— 匿名举报无法追责，也容易被批量刷）
 * 管理端：GET /api/admin/reports、POST /api/admin/reports/{id}/handle
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ContentReportController {

    private final ContentReportService service;

    @PostMapping("/reports")
    public ApiResponse<Map<String, Object>> submit(@RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest request) {
        Map<String, Object> input = body == null ? Map.of() : body;
        Long reporterId = CurrentUser.optional(request);
        if (reporterId == null) throw new UnauthorizedException("请先登录后再举报");
        return ApiResponse.ok(service.submit(
            reporterId,
            input.get("targetType") == null ? "" : String.valueOf(input.get("targetType")),
            longOr(input.get("targetId")),
            input.get("reason") == null ? "" : String.valueOf(input.get("reason")),
            input.get("detail") == null ? "" : String.valueOf(input.get("detail"))));
    }

    // ---- 管理端 ----

    @GetMapping("/admin/reports")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String targetType,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(status, targetType, page, size));
    }

    @PostMapping("/admin/reports/{id}/handle")
    public ApiResponse<Object> handle(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        Map<String, Object> input = body == null ? Map.of() : body;
        String action = input.get("action") == null ? "handled" : String.valueOf(input.get("action"));
        Object adminName = request.getAttribute(AdminInterceptor.ADMIN_NAME);
        service.handle(id, action, adminName == null ? "admin" : String.valueOf(adminName));
        return ApiResponse.ok(null);
    }

    private static Long longOr(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(value).trim()); } catch (Exception e) { return null; }
    }
}
