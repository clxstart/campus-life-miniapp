package com.campuslife.service;

import com.campuslife.config.AdminJwtUtil;
import com.campuslife.entity.AdminUser;
import com.campuslife.mapper.AdminMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

// 管理端登录：BCrypt 校验 + 失败锁定。
@Service @RequiredArgsConstructor
public class AdminAuthService {
    private final AdminMapper mapper;
    private final AdminJwtUtil jwt;
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final int MAX_FAIL = 5;
    private static final int LOCK_MINUTES = 15;

    public static String encode(String raw) { return ENCODER.encode(raw); }

    public Map<String,Object> login(String username, String password) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("请输入账号");
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("请输入密码");
        AdminUser admin = mapper.findByUsername(username.trim());
        // 账号不存在与密码错误给同样提示，避免被枚举账号
        if (admin == null) throw new IllegalArgumentException("账号或密码错误");
        if (admin.getStatus() != null && admin.getStatus() == 0) throw new IllegalArgumentException("账号已停用，请联系管理员");
        if (admin.getLockedUntil() != null && admin.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("登录失败次数过多，请 " + LOCK_MINUTES + " 分钟后再试");
        }
        if (!ENCODER.matches(password, admin.getPasswordHash())) {
            int fails = (admin.getFailCount() == null ? 0 : admin.getFailCount()) + 1;
            LocalDateTime lock = fails >= MAX_FAIL ? LocalDateTime.now().plusMinutes(LOCK_MINUTES) : null;
            mapper.bumpFail(admin.getId(), lock);
            throw new IllegalArgumentException("账号或密码错误");
        }
        if ("merchant".equals(admin.getRole()) && (admin.getShopId() == null || admin.getShopId() < 1)) {
            throw new IllegalArgumentException("商家账号尚未绑定店铺");
        }
        if ("school".equals(admin.getRole()) && (admin.getSchoolId() == null || admin.getSchoolId() < 1)) {
            throw new IllegalArgumentException("学校管理员账号尚未绑定学校");
        }
        mapper.markLogin(admin.getId());
        Map<String,Object> out = new LinkedHashMap<>();
        // 令牌携带当前 token_version，账号停用/重置密码后旧令牌由 AdminTokenGuard 拒绝（finding-03）
        Map<String,Object> state = mapper.tokenState(admin.getId());
        long tokenVersion = state != null && state.get("tokenVersion") instanceof Number n ? n.longValue() : 0L;
        out.put("token", jwt.issue(admin.getId(), admin.getRole(), admin.getSchoolId(), admin.getShopId(), admin.getRealName(), tokenVersion));
        out.put("expiresIn", jwt.ttlSeconds());
        out.put("id", admin.getId());
        out.put("username", admin.getUsername());
        out.put("realName", admin.getRealName());
        out.put("role", admin.getRole());
        out.put("schoolId", admin.getSchoolId());
        out.put("shopId", admin.getShopId());
        return out;
    }

    // 写操作日志：管理端所有变更都要留痕
    public void log(Long adminId, String adminName, String action, String targetType, String targetId, String detail, String ip) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("adminId", adminId);
        row.put("adminName", adminName == null ? "" : adminName);
        row.put("action", action == null ? "" : action);
        row.put("targetType", targetType == null ? "" : targetType);
        row.put("targetId", targetId == null ? "" : targetId);
        row.put("detail", detail == null ? "" : (detail.length() > 500 ? detail.substring(0, 500) : detail));
        row.put("ip", ip == null ? "" : ip);
        mapper.insertLog(row);
    }
}
