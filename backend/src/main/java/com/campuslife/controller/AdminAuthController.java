package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
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

@RestController @RequestMapping("/api/admin") @RequiredArgsConstructor
public class AdminAuthController {
    private final AdminAuthService service;
    private final AdminMapper mapper;

    /**
     * 账号字符集：只允许 ASCII 字母、数字、下划线、点、连字符。
     * 账号是登录凭证，混入中文会带来输入法状态、全角/半角、大小写等一堆麻烦，
     * 而且日志、URL、命令行里都不好处理。中文名字请填「真实姓名」字段。
     */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]{3,32}$");

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String username = body == null ? null : String.valueOf(body.getOrDefault("username", ""));
        String password = body == null ? null : String.valueOf(body.getOrDefault("password", ""));
        return ApiResponse.ok(service.login(username, password));
    }

    // 当前登录管理员信息：前端用它渲染身份与权限范围
    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", request.getAttribute(AdminInterceptor.ADMIN_ID));
        out.put("role", request.getAttribute(AdminInterceptor.ADMIN_ROLE));
        out.put("schoolId", request.getAttribute(AdminInterceptor.ADMIN_SCHOOL));
        out.put("shopId", request.getAttribute(AdminInterceptor.MERCHANT_SHOP));
        out.put("realName", request.getAttribute(AdminInterceptor.ADMIN_NAME));
        return ApiResponse.ok(out);
    }

    // 管理员账号列表（仅超管）
    @GetMapping("/admins")
    public ApiResponse<List<Map<String, Object>>> admins(HttpServletRequest request) {
        requireSuper(request);
        return ApiResponse.ok(mapper.admins());
    }

    // 新建管理员（仅超管）：校管必须指定 schoolId
    @PostMapping("/admins")
    public ApiResponse<Map<String, Object>> createAdmin(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireSuper(request);
        String username = text(body.get("username")).trim();
        String password = text(body.get("password"));
        String realName = text(body.get("realName")).trim();
        String role = "super".equals(text(body.get("role"))) ? "super" : "school";
        if (username.length() < 3 || username.length() > 32) throw new IllegalArgumentException("账号长度须为3至32位");
        // 只允许字母数字等 ASCII 字符；中文请填「真实姓名」
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("账号只能用字母、数字、下划线、点或连字符（3-32位），中文请填真实姓名");
        }
        if (password.length() < 6) throw new IllegalArgumentException("密码至少6位");
        if (mapper.findByUsername(username) != null) throw new IllegalArgumentException("该账号已存在");
        Long schoolId = null;
        if ("school".equals(role)) {
            Object raw = body.get("schoolId");
            if (!(raw instanceof Number n) || n.longValue() < 1) throw new IllegalArgumentException("校管必须指定所属学校");
            schoolId = n.longValue();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("username", username);
        row.put("passwordHash", AdminAuthService.encode(password));
        row.put("realName", realName);
        row.put("role", role);
        row.put("schoolId", schoolId);
        mapper.insertAdmin(row);
        service.log(adminId(request), adminName(request), "create", "admin", String.valueOf(row.get("id")), "新建管理员 " + username, ip(request));
        return ApiResponse.ok(Map.of("id", row.get("id")));
    }

    // 启用/停用管理员（仅超管）
    @PostMapping("/admins/{id}/status")
    public ApiResponse<Map<String, Object>> setAdminStatus(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireSuper(request);
        int status = intOr(body.get("status"), 1) == 0 ? 0 : 1;
        if (id.equals(adminId(request)) && status == 0) throw new IllegalArgumentException("不能停用当前登录的账号");
        mapper.setAdminStatus(id, status);
        service.log(adminId(request), adminName(request), "update", "admin", String.valueOf(id), "设置状态为 " + status, ip(request));
        return ApiResponse.ok(Map.of("status", status));
    }

    // 操作日志（超管看全部，校管只看自己）
    @GetMapping("/logs")
    public ApiResponse<Map<String, Object>> logs(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 HttpServletRequest request) {
        int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
        boolean isSuper = "super".equals(String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE)));
        Long scopeAdminId = isSuper ? null : adminId(request);
        List<Map<String, Object>> list = mapper.logs(scopeAdminId, s, (p - 1) * s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", mapper.countLogs(scopeAdminId));
        out.put("page", p);
        out.put("size", s);
        return ApiResponse.ok(out);
    }

    // ---- 内部工具 ----
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(text(value).trim()); } catch (Exception error) { return fallback; }
    }

    private static Long adminId(HttpServletRequest request) { return (Long) request.getAttribute(AdminInterceptor.ADMIN_ID); }

    private static String adminName(HttpServletRequest request) {
        Object name = request.getAttribute(AdminInterceptor.ADMIN_NAME);
        return name == null ? "" : String.valueOf(name);
    }

    private static String ip(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }

    private static void requireSuper(HttpServletRequest request) {
        if (!"super".equals(String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE)))) {
            throw new com.campuslife.config.ForbiddenException("仅超级管理员可操作");
        }
    }
}
