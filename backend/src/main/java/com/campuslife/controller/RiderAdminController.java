package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.mapper.AdminMapper;
import com.campuslife.service.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin/riders")
@RequiredArgsConstructor
public class RiderAdminController {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]{3,32}$");
    private final AdminMapper mapper;
    private final AdminAuthService auth;

    @GetMapping
    public ApiResponse<List<Map<String,Object>>> riders(HttpServletRequest request) {
        requireManager(request);
        return ApiResponse.ok(mapper.riders("super".equals(role(request)) ? null : schoolId(request)));
    }

    @PostMapping
    public ApiResponse<Map<String,Object>> create(@RequestBody Map<String,Object> body, HttpServletRequest request) {
        requireManager(request);
        if (body == null) throw new IllegalArgumentException("请填写骑手信息");
        String username = text(body.get("username")).trim();
        String realName = text(body.get("realName")).trim();
        String password = text(body.get("password"));
        String phone = text(body.get("phone")).trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) throw new IllegalArgumentException("账号只能使用字母、数字、下划线、点或连字符（3-32位）");
        if (realName.isEmpty() || realName.length() > 32) throw new IllegalArgumentException("请填写1至32字的骑手姓名");
        if (password.length() < 6 || password.length() > 72) throw new IllegalArgumentException("初始密码须为6至72位");
        if (phone.length() > 20) throw new IllegalArgumentException("联系电话不能超过20位");
        if (mapper.findByUsername(username) != null) throw new IllegalArgumentException("该账号已存在");
        Long schoolId = "super".equals(role(request)) ? number(body.get("schoolId")) : schoolId(request);
        if (schoolId == null || schoolId < 1 || mapper.activeSchool(schoolId) == null) throw new IllegalArgumentException("请选择有效学校");

        Map<String,Object> row = new LinkedHashMap<>();
        row.put("username", username);
        row.put("passwordHash", AdminAuthService.encode(password));
        row.put("realName", realName);
        row.put("schoolId", schoolId);
        row.put("phone", phone);
        mapper.insertRider(row);
        auth.log(adminId(request), adminName(request), "create", "rider", String.valueOf(row.get("id")), "新建骑手 " + username, ip(request));
        return ApiResponse.ok(Map.of("id", row.get("id")));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<Map<String,Object>> status(@PathVariable Long id, @RequestBody Map<String,Object> body, HttpServletRequest request) {
        requireManager(request);
        Object rawStatus = body == null ? null : body.get("status");
        if (!(rawStatus instanceof Number requestedStatus) || (requestedStatus.intValue() != 0 && requestedStatus.intValue() != 1)) {
            throw new IllegalArgumentException("status 必须为 0 或 1");
        }
        int enabled = requestedStatus.intValue();
        Map<String,Object> account = mapper.accountBrief(id);
        assertManageable(account, request);
        if (mapper.setRiderStatus(id, enabled) != 1) throw new IllegalArgumentException("骑手账号不存在");
        auth.log(adminId(request), adminName(request), "update", "rider", String.valueOf(id), "设置启用状态为 " + enabled, ip(request));
        return ApiResponse.ok(Map.of("status", enabled));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Object> resetPassword(@PathVariable Long id, @RequestBody Map<String,Object> body, HttpServletRequest request) {
        requireManager(request);
        if (body == null) throw new IllegalArgumentException("请填写新密码");
        Map<String,Object> account = mapper.accountBrief(id);
        assertManageable(account, request);
        String password = text(body.get("password"));
        if (password.length() < 6 || password.length() > 72) throw new IllegalArgumentException("新密码须为6至72位");
        if (mapper.resetRiderPassword(id, AdminAuthService.encode(password)) != 1) throw new IllegalArgumentException("骑手账号不存在");
        auth.log(adminId(request), adminName(request), "update", "rider", String.valueOf(id), "重置骑手密码", ip(request));
        return ApiResponse.ok(null);
    }

    private static void assertManageable(Map<String,Object> account, HttpServletRequest request) {
        if (account == null || !"rider".equals(account.get("role"))) throw new IllegalArgumentException("骑手账号不存在");
        if (!"super".equals(role(request)) && (schoolId(request) == null || !schoolId(request).equals(number(account.get("schoolId"))))) throw new ForbiddenException("只能管理本校骑手");
    }

    private static void requireManager(HttpServletRequest request) {
        if (!"super".equals(role(request)) && !"school".equals(role(request))) throw new ForbiddenException("仅校级管理员可管理骑手");
    }
    private static String role(HttpServletRequest request) { return String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE)); }
    private static Long adminId(HttpServletRequest request) { return (Long) request.getAttribute(AdminInterceptor.ADMIN_ID); }
    private static Long schoolId(HttpServletRequest request) { Object value = request.getAttribute(AdminInterceptor.ADMIN_SCHOOL); return value instanceof Number n ? n.longValue() : null; }
    private static String adminName(HttpServletRequest request) { return text(request.getAttribute(AdminInterceptor.ADMIN_NAME)); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static Long number(Object value) { return value instanceof Number n ? n.longValue() : null; }
    private static String ip(HttpServletRequest request) { String fwd = request.getHeader("X-Forwarded-For"); return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : request.getRemoteAddr(); }
}
