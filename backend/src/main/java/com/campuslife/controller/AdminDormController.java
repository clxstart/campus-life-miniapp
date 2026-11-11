package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.DormService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * 宿舍管理（管理端）。
 *
 * 权限：super 看全平台；school 只看本校（前端传别的学校直接 403）。
 * 解散只改 status=0 并解除成员关系，历史签到与积分流水保留，误操作可由运维恢复。
 */
@RestController
@RequestMapping("/api/admin/dorm")
@RequiredArgsConstructor
public class AdminDormController {

    private final DormService service;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 @RequestParam(required = false) Long schoolId,
                                                 @RequestParam(required = false) Integer status,
                                                 @RequestParam(required = false) String keyword,
                                                 HttpServletRequest request) {
        requireManager(request);
        return ApiResponse.ok(service.adminList(page, size, effectiveSchool(request, schoolId), status,
            keyword, scopeSchool(request)));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id, HttpServletRequest request) {
        requireManager(request);
        return ApiResponse.ok(service.adminDetail(id, scopeSchool(request)));
    }

    /** 解散：{reason} 必填（留审计与用户可见理由）。 */
    @PostMapping("/{id}/dismiss")
    public ApiResponse<Object> dismiss(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       HttpServletRequest request) {
        requireManager(request);
        Map<String, Object> input = body == null ? Map.of() : body;
        service.dismiss(id, adminId(request),
            input.get("reason") == null ? "" : String.valueOf(input.get("reason")), scopeSchool(request));
        return ApiResponse.ok(null);
    }

    /**
     * 手动补跑某一周宿舍周榜（定时任务发版/重启错过时用）。
     * 只允许 super：一次补跑会影响全平台宿舍的成长值。
     */
    @PostMapping("/settle-week")
    public ApiResponse<Map<String, Object>> settleWeek(@RequestBody(required = false) Map<String, Object> body,
                                                       HttpServletRequest request) {
        if (!"super".equals(String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE)))) {
            throw new ForbiddenException("仅平台超管可补跑宿舍周榜");
        }
        Map<String, Object> input = body == null ? Map.of() : body;
        Object period = input.get("period");
        LocalDate day = period == null || String.valueOf(period).isBlank()
            ? LocalDate.now(DormService.BEIJING).minusWeeks(1)
            : LocalDate.parse(String.valueOf(period).trim());
        return ApiResponse.ok(service.settleWeek(day));
    }

    // ---------- 鉴权 helper ----------

    private static void requireManager(HttpServletRequest request) {
        String role = String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE));
        if (!"super".equals(role) && !"school".equals(role)) throw new ForbiddenException("仅校级管理员可管理宿舍");
    }

    /** super 返回 null（不限学校），school 返回自己的学校。 */
    private static Long scopeSchool(HttpServletRequest request) {
        if ("super".equals(String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE)))) return null;
        Object value = request.getAttribute(AdminInterceptor.ADMIN_SCHOOL);
        Long id = value instanceof Number n ? n.longValue() : null;
        if (id == null) throw new ForbiddenException("当前账号未绑定学校");
        return id;
    }

    /** 校管只能用本校；超管可以用前端传的 schoolId 切换学校。 */
    private static Long effectiveSchool(HttpServletRequest request, Long requestedSchoolId) {
        Long own = scopeSchool(request);
        if (own != null) {
            if (requestedSchoolId != null && requestedSchoolId > 0 && !own.equals(requestedSchoolId)) {
                throw new ForbiddenException("只能查看本校数据");
            }
            return own;
        }
        return requestedSchoolId != null && requestedSchoolId > 0 ? requestedSchoolId : null;
    }

    private static Long adminId(HttpServletRequest request) {
        Object value = request.getAttribute(AdminInterceptor.ADMIN_ID);
        return value instanceof Number n ? n.longValue() : null;
    }
}