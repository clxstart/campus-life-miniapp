package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.BizService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理端：活动报名审核 + 报名设置。
 *
 * 报名有「一键报名」和「材料+审核」两种模式，由每个活动自己决定（campus_activity.enroll_mode）。
 * 这个控制器只负责管理端那一半：看报名列表、通过/驳回、改报名设置。
 * 学生侧提交入口在 BizController#enrollActivity。
 *
 * 鉴权：路径在 /api/admin/** 下，由 AdminInterceptor 强制校验管理员 token。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminEnrollmentController {
    private final BizService bizService;

    /** 报名列表：可按活动、状态筛选。status 传 pending 就是待审核队列。 */
    @GetMapping("/enrollments")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) Long activityId,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) Long schoolId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 HttpServletRequest request) {
        // 校管只能看本校活动；超管可指定学校或看全部
        Long scoped = scopedSchool(request, schoolId);
        return ApiResponse.ok(bizService.enrollments(activityId, blankToNull(status), scoped, page, size));
    }

    /** 审核通过：报名数 +1 */
    @PostMapping("/enrollments/{id}/approve")
    public ApiResponse<Object> approve(@PathVariable Long id, HttpServletRequest request) {
        bizService.decideEnrollment(id, true, "", adminId(request));
        return ApiResponse.ok(null);
    }

    /** 驳回：必须填原因，学生能看到 */
    @PostMapping("/enrollments/{id}/reject")
    public ApiResponse<Object> reject(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        String reason = body == null || body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        bizService.decideEnrollment(id, false, reason, adminId(request));
        return ApiResponse.ok(null);
    }

    /** 改某个活动的报名设置：simple 一键报名 / material 填表+材料+审核 */
    @PutMapping("/activities/{id}/enroll-config")
    public ApiResponse<Object> updateConfig(@PathVariable Long id,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            HttpServletRequest request) {
        Map<String, Object> input = body == null ? Map.of() : body;
        String mode = input.get("enrollMode") == null ? "simple" : String.valueOf(input.get("enrollMode"));
        String hint = input.get("enrollHint") == null ? "" : String.valueOf(input.get("enrollHint"));
        int maxFiles = 3;
        Object raw = input.get("enrollMaxFiles");
        if (raw instanceof Number n) maxFiles = n.intValue();
        else if (raw != null) {
            try { maxFiles = Integer.parseInt(String.valueOf(raw).trim()); } catch (Exception ignored) { }
        }
        bizService.updateEnrollConfig(id, mode, hint, maxFiles);
        return ApiResponse.ok(null);
    }

    private static Long scopedSchool(HttpServletRequest request, Long requested) {
        Object role = request.getAttribute(AdminInterceptor.ADMIN_ROLE);
        if ("super".equals(String.valueOf(role))) {
            return (requested != null && requested > 0) ? requested : null;
        }
        Object own = request.getAttribute(AdminInterceptor.ADMIN_SCHOOL);
        if (own instanceof Number n && n.longValue() > 0) return n.longValue();
        throw new com.campuslife.config.ForbiddenException("当前账号未绑定学校，请联系超级管理员");
    }

    private static Long adminId(HttpServletRequest request) {
        Object value = request.getAttribute(AdminInterceptor.ADMIN_ID);
        return value instanceof Number n ? n.longValue() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
