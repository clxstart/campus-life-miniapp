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
    private final AdminTokenGuard guard;

    public static final String ADMIN_ID = "adminId";
    public static final String ADMIN_ROLE = "adminRole";
    public static final String ADMIN_SCHOOL = "adminSchoolId";
    public static final String ADMIN_NAME = "adminName";
    public static final String MERCHANT_SHOP = "merchantShopId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        if (path.endsWith("/api/merchant/apply")) return true;
        // License files use private storage while application submission remains public.
        if (path.endsWith("/api/admin/upload/private") && noToken(request)) return true;
        // Login does not need a token.
        if (path.endsWith("/api/admin/login")) return true;

        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer "))
            ? header.substring(7).trim()
            : request.getHeader("X-Admin-Token");
        Claims claims = jwt.parse(token);
        if (claims == null) {
            reject(response, 401, "登录已过期，请重新登录");
            return false;
        }
        String role = claims.get("role", String.class);
        boolean merchantPath = path.startsWith(request.getContextPath() + "/api/merchant/");
        if (merchantPath && !"merchant".equals(role)) {
            reject(response, 403, "商家账号才可访问该接口");
            return false;
        }
        if (!merchantPath && !"super".equals(role) && !"school".equals(role)) {
            reject(response, 403, "无权访问管理端接口");
            return false;
        }
        if ("rider".equals(claims.get("role", String.class))) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"msg\":\"骑手账号不能访问管理端\",\"data\":null}");
            return false;
        }
        // finding-03：签名有效还不够，必须确认账号当前仍启用、令牌版本与权限范围未变；查库失败一律拒绝
        AdminTokenGuard.Result checked = guard.check(claims);
        if (checked.verdict() == AdminTokenGuard.Verdict.UNAVAILABLE) {
            reject(response, 503, "鉴权服务暂不可用，请稍后重试");
            return false;
        }
        if (checked.verdict() != AdminTokenGuard.Verdict.OK) {
            reject(response, 401, "账号已停用或权限已变更，请重新登录");
            return false;
        }
        request.setAttribute(ADMIN_ID, Long.valueOf(claims.getSubject()));
        request.setAttribute(ADMIN_ROLE, role);
        Object schoolId = claims.get("schoolId");
        if (schoolId instanceof Number n) request.setAttribute(ADMIN_SCHOOL, n.longValue());
        request.setAttribute(ADMIN_NAME, claims.get("name", String.class));
        Object shopId = claims.get("shopId");
        if (shopId instanceof Number n) request.setAttribute(MERCHANT_SHOP, n.longValue());
        if (merchantPath && (!(shopId instanceof Number n) || n.longValue() < 1)) {
            reject(response, 403, "商家账号未绑定店铺");
            return false;
        }
        return true;
    }

    private boolean noToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : request.getHeader("X-Admin-Token");
        return token == null || token.isBlank();
    }

    private void reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"msg\":\"" + message + "\",\"data\":null}");
    }
}
