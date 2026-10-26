package com.campuslife.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Set;

/**
 * 身份解析 + 强制鉴权。
 *
 * 历史问题：这里解析失败也 return true，于是所有接口都只能靠客户端自己上报 userId，
 * 任何人把 userId 改成别人的数字就能替别人发帖、投票、退款（越权）。现在分两步：
 *   1. 解析成功 → 把 userId 放进 request attribute，控制器一律从这里取（{@link CurrentUser}）；
 *   2. 需要身份的请求（写操作 + 「我的数据」读接口）没有有效 token → 直接 401，不再放行。
 *
 * 公开只读接口（帖子列表、学校列表、搜索等）仍然允许匿名访问，
 * 它们只用 userId 做「我点过赞吗」这种个性化标记，不涉及权限。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtUtil jwt;
    public static final String USER_ID = "campusUserId";

    /** 关掉后退回「谁传 userId 就代表谁」的旧行为，仅限本机调试。 */
    @Value("${campus.auth.enforce:true}")
    private boolean enforce;

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * 无需 token 的写接口：
     * - 登录本身（/api/auth/**，含开发登录）
     * - 微信服务器回调（支付、退款通知）
     * - AI 陪聊与图片上传：产品上刻意允许游客使用（上传有按 IP 的频率限制兜底）
     */
    private static final List<String> PUBLIC_WRITE_PATHS = List.of(
        "/api/auth/",
        "/api/pay/notify",
        "/api/pay/refund/notify",
        "/api/ai/",
        "/api/upload"
    );

    /** 只有本人能看的读接口（路径前缀）。 */
    private static final List<String> PRIVATE_READ_PREFIXES = List.of(
        "/api/conversations",
        "/api/notifications",
        "/api/timetable",
        "/api/calendar",
        "/api/pet",
        // 圈子群聊历史与未读数也是「我的数据」：之前没拦住，未登录会走到 400「参数无效」，
        // 前端看不出是没登录，也不会去重新登录。现在统一成 401。
        "/api/circle-messages"
    );

    /** /api/users/{id}/xxx 里属于「我的数据」的子资源。 */
    private static final List<String> PRIVATE_USER_SUBRESOURCES = List.of(
        "/favorites", "/views", "/coupons", "/addresses", "/publish-count"
    );

    @PostConstruct
    void warnWhenDisabled() {
        if (!enforce) {
            log.warn("[campus] campus.auth.enforce=false：接口不再校验 token，任何人都能冒充任意用户，"
                + "只允许本机调试使用，请不要带到线上");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Long userId = jwt.parse(token(request));
        if (userId != null) {
            request.setAttribute(USER_ID, userId);
            return true;
        }
        if (!enforce) return true;
        String path = request.getRequestURI();
        if (!requiresIdentity(request, path)) return true;
        reject(response);
        return false;
    }

    private boolean requiresIdentity(HttpServletRequest request, String path) {
        if (WRITE_METHODS.contains(request.getMethod().toUpperCase())) {
            return PUBLIC_WRITE_PATHS.stream().noneMatch(path::startsWith);
        }
        for (String prefix : PRIVATE_READ_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        if (path.startsWith("/api/users/")) {
            return PRIVATE_USER_SUBRESOURCES.stream().anyMatch(path::endsWith);
        }
        return false;
    }

    private static String token(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) return header.substring(7).trim();
        return request.getHeader("X-Token");
    }

    private static void reject(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"请先登录\",\"data\":null}");
        response.getWriter().flush();
    }
}
