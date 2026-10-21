package com.campuslife.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

// 管理端鉴权：/api/admin/** 除登录外必须携带有效 token，否则 401。
// 注意：这与小程序的「轻量鉴权」（解析失败只降级）**刻意不同** ——
// 管理接口绝不能匿名访问，否则任何人都能改数据。
@Component @RequiredArgsConstructor
public class AdminInterceptor implements HandlerInterceptor {
    private final AdminJwtUtil jwt;

    public static final String ADMIN_ID = "adminId";
    public static final String ADMIN_ROLE = "adminRole";
    public static final String ADMIN_SCHOOL = "adminSchoolId";
    public static final String ADMIN_NAME = "adminName";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        // 登录接口本身不需要 token
        if (path.endsWith("/api/admin/login")) return true;

        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer "))
            ? header.substring(7).trim()
            : request.getHeader("X-Admin-Token");
        Claims claims = jwt.parse(token);
        if (claims == null) {
            reject(response, "登录已过期，请重新登录");
            return false;
        }
        if ("rider".equals(claims.get("role", String.class))) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"msg\":\"骑手账号不能访问管理端\",\"data\":null}");
            return false;
        }
        request.setAttribute(ADMIN_ID, Long.valueOf(claims.getSubject()));
        request.setAttribute(ADMIN_ROLE, claims.get("role", String.class));
        Object schoolId = claims.get("schoolId");
        if (schoolId instanceof Number n) request.setAttribute(ADMIN_SCHOOL, n.longValue());
        request.setAttribute(ADMIN_NAME, claims.get("name", String.class));
        return true;
    }

    private void reject(HttpServletResponse response, String message) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"" + message + "\",\"data\":null}");
    }
}
