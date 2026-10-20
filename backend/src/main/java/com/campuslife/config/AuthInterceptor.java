package com.campuslife.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

// 轻量鉴权：解析 Authorization 头里的 JWT，把 userId 放进请求属性供接口读取。
// 解析失败不阻断请求 —— 保持「无后端/未登录也能演示」的既有行为，由接口自行决定降级。
@Component @RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtUtil jwt;
    public static final String USER_ID = "campusUserId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("Authorization");
        String token = null;
        if (header != null && header.startsWith("Bearer ")) token = header.substring(7).trim();
        if (token == null) token = request.getHeader("X-Token");
        Long userId = jwt.parse(token);
        if (userId != null) request.setAttribute(USER_ID, userId);
        return true;
    }
}
