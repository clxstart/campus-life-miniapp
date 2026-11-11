package com.campuslife.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class RiderInterceptor implements HandlerInterceptor {
    private final AdminJwtUtil jwt;
    private final AdminTokenGuard guard;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ")
            ? header.substring(7).trim() : request.getHeader("X-Admin-Token");
        Claims claims = jwt.parse(token);
        if (claims == null) return reject(response, 401, "登录已过期，请重新登录");
        if (!"rider".equals(claims.get("role", String.class))) return reject(response, 403, "仅骑手账号可访问");

        // 与管理端共用令牌状态校验：停用、重置密码、调校区后旧令牌立即失效；查库失败拒绝而非放行
        AdminTokenGuard.Result checked = guard.check(claims);
        if (checked.verdict() == AdminTokenGuard.Verdict.UNAVAILABLE) return reject(response, 503, "鉴权服务暂不可用，请稍后重试");
        if (checked.verdict() != AdminTokenGuard.Verdict.OK) return reject(response, 401, "骑手账号已停用或登录已失效，请重新登录");
        Map<String,Object> account = checked.account();
        Long id = Long.valueOf(claims.getSubject());
        request.setAttribute(AdminInterceptor.ADMIN_ID, id);
        request.setAttribute(AdminInterceptor.ADMIN_ROLE, "rider");
        request.setAttribute(AdminInterceptor.ADMIN_SCHOOL, account.get("schoolId"));
        request.setAttribute(AdminInterceptor.ADMIN_NAME, claims.get("name", String.class));
        return true;
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"msg\":\"" + message + "\",\"data\":null}");
        return false;
    }
}
