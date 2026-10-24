package com.campuslife.config;

import com.campuslife.mapper.AdminMapper;
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
    private final AdminMapper adminMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ")
            ? header.substring(7).trim() : request.getHeader("X-Admin-Token");
        Claims claims = jwt.parse(token);
        if (claims == null) return reject(response, 401, "登录已过期，请重新登录");
        if (!"rider".equals(claims.get("role", String.class))) return reject(response, 403, "仅骑手账号可访问");

        Long id;
        try { id = Long.valueOf(claims.getSubject()); }
        catch (RuntimeException error) { return reject(response, 401, "登录已过期，请重新登录"); }
        Map<String,Object> account = adminMapper.accountBrief(id);
        Object status = account == null ? null : account.get("status");
        if (account == null || !"rider".equals(account.get("role")) || !(status instanceof Number number) || number.intValue() != 1) {
            return reject(response, 401, "骑手账号已停用，请联系管理员");
        }
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
