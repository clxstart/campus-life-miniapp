package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.JobService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理端兼职：列表（含待审核）、审核/下架、逐岗位费率、履约单与争议裁定。
 *
 * 权限：super 可管全校；school 只能管自己学校（学校从登录态取，不接受前端传入）。
 * helper 写法与 {@link DispatchAdminController} 保持一致。
 */
@RestController
@RequestMapping("/api/admin/jobs")
@RequiredArgsConstructor
public class AdminJobController {

    private final JobService jobs;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "") String keyword,
                                                 @RequestParam(required = false) Long schoolId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 @RequestParam(defaultValue = "false") boolean pending,
                                                 HttpServletRequest request) {
        requireManager(request);
        return ApiResponse.ok(jobs.adminList(effectiveSchool(request, schoolId), keyword, page, size, pending));
    }

    /** 删除岗位：软删下架（履约单与账单的历史上下文必须保留）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Object> delete(@PathVariable Long id, HttpServletRequest request) {
        requireManager(request);
        jobs.delete(id, adminId(request), adminName(request), scopeSchool(request), ip(request));
        return ApiResponse.ok(null);
    }

    /** 审核 / 下架：{approve:boolean, reason}。 */
    @PostMapping("/{id}/review")
    public ApiResponse<Map<String, Object>> review(@PathVariable Long id,
                                                   @RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest request) {
        requireManager(request);
        if (body == null || body.get("approve") == null) throw new IllegalArgumentException("请选择通过或驳回");
        boolean approve = Boolean.parseBoolean(String.valueOf(body.get("approve")));
        String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        return ApiResponse.ok(jobs.review(id, approve, reason,
            adminId(request), adminName(request), scopeSchool(request), ip(request)));
    }

    /** 逐岗位配置平台服务费：{feeMode, feeValue}（不预填，由管理员显式选择）。 */
    @PostMapping("/{id}/fee")
    public ApiResponse<Map<String, Object>> fee(@PathVariable Long id,
                                                @RequestBody(required = false) Map<String, Object> body,
                                                HttpServletRequest request) {
        requireManager(request);
        if (body == null) throw new IllegalArgumentException("请填写费率配置");
        String feeMode = body.get("feeMode") == null ? "none" : String.valueOf(body.get("feeMode"));
        Integer feeValue = null;
        if (body.get("feeValue") != null) {
            try {
                feeValue = Integer.parseInt(String.valueOf(body.get("feeValue")).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("费率格式不正确");
            }
        }
        return ApiResponse.ok(jobs.fee(id, feeMode, feeValue,
            adminId(request), adminName(request), scopeSchool(request), ip(request)));
    }

    @GetMapping("/contracts/{id}")
    public ApiResponse<Map<String, Object>> contract(@PathVariable Long id, HttpServletRequest request) {
        requireManager(request);
        return ApiResponse.ok(jobs.adminContract(id, scopeSchool(request)));
    }

    /** 争议裁定：{decision: settled|refunded, reason}，必须留审计。 */
    @PostMapping("/contracts/{id}/arbitrate")
    public ApiResponse<Map<String, Object>> arbitrate(@PathVariable Long id,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest request) {
        requireManager(request);
        if (body == null || body.get("decision") == null) throw new IllegalArgumentException("请选择裁定结果");
        String decision = String.valueOf(body.get("decision"));
        String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        return ApiResponse.ok(jobs.arbitrate(id, decision, reason,
            adminId(request), adminName(request), scopeSchool(request), ip(request)));
    }

    // ---------- 鉴权 helper ----------

    private static void requireManager(HttpServletRequest request) {
        String role = String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE));
        if (!"super".equals(role) && !"school".equals(role)) throw new ForbiddenException("仅校级管理员可管理兼职岗位");
    }

    /** super 返回 null（不限学校），school 返回自己的学校。 */
    private static Long scopeSchool(HttpServletRequest request) {
        if ("super".equals(String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE)))) return null;
        Object value = request.getAttribute(AdminInterceptor.ADMIN_SCHOOL);
        Long id = value instanceof Number n ? n.longValue() : null;
        if (id == null) throw new ForbiddenException("当前账号未绑定学校");
        return id;
    }

    /** 校管只能看本校；超管可以用前端传的 schoolId 切换学校。 */
    private static Long effectiveSchool(HttpServletRequest request, Long requestedSchoolId) {
        Long own = scopeSchool(request);
        if (own != null) {
            if (requestedSchoolId != null && !own.equals(requestedSchoolId)) {
                throw new ForbiddenException("只能查看本校数据");
            }
            return own;
        }
        return requestedSchoolId != null && requestedSchoolId > 0 ? requestedSchoolId : null;
    }

    private static Long adminId(HttpServletRequest request) {
        return (Long) request.getAttribute(AdminInterceptor.ADMIN_ID);
    }

    private static String adminName(HttpServletRequest request) {
        Object name = request.getAttribute(AdminInterceptor.ADMIN_NAME);
        return name == null ? "" : String.valueOf(name);
    }

    private static String ip(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
