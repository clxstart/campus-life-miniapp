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
import com.campuslife.service.UserStatusGuard;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtUtil jwt;
    /** 账号是否可用的校验（注销后 token 立即失效）。用 ObjectProvider 避免与 mapper 初始化顺序耦合。 */
    private final org.springframework.beans.factory.ObjectProvider<UserStatusGuard> userStatusGuardProvider;
    private volatile UserStatusGuard userStatusGuard;
    public static final String USER_ID = "campusUserId";

    private UserStatusGuard userStatusGuard() {
        if (userStatusGuard == null) {
            try { userStatusGuard = userStatusGuardProvider.getIfAvailable(); } catch (Exception ignored) { }
        }
        return userStatusGuard;
    }

    /** 关掉后退回「谁传 userId 就代表谁」的旧行为，仅限本机调试。 */
    @Value("${campus.auth.enforce:true}")
    private boolean enforce;

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * 无需 token 的写接口：
     * - 登录本身：小程序 /api/auth/**、管理端 /api/admin/login、商家与骑手也走这个入口
     * - 商家入驻申请 /api/merchant/apply：还没账号的人也要能提交
     * - 微信服务器回调（支付、退款通知）
     * - AI 陪聊与图片上传：产品上刻意允许游客使用（上传有按 IP 的频率限制兜底）
     *
     * 注意：登录接口一旦漏在这份名单外，整个管理端/商家端/骑手端都会因为「请先登录」而打不开
     * （本文件曾经漏掉 /api/admin/login，属于回归过的点，改动时请连带跑一遍登录冒烟）。
     */
    private static final List<String> PUBLIC_WRITE_PATHS = List.of(
        "/api/auth/",
        "/api/admin/login",
        "/api/merchant/apply",
        "/api/pay/notify",
        "/api/pay/refund/notify",
        "/api/ai/",
        // 前端错误上报：未登录也可能崩溃，必须允许匿名上报，否则最该被看到的问题收不上来（靠限流防刷）
        "/api/log/client",
        "/api/upload"
    );

    /** 只有本人能看的读接口（路径前缀）。 */
    private static final List<String> PRIVATE_READ_PREFIXES = List.of(
        "/api/conversations",
        "/api/notifications",
        "/api/timetable",
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
        // 管理端/商家端/骑手端有自己的拦截器（AdminInterceptor / RiderInterceptor），
        // 它们的 token 是 AdminJwtUtil 用另一套密钥签的，在这里一定解析不出来。
        // 如果这里也要求登录，管理端所有写操作（PUT/POST）都会先被 401 掉，
        // 连 GET 能过、PUT 不能过的怪现象也都是这个原因 —— 直接放行，交给下游拦截器判定。
        if (isDelegatedPath(request.getRequestURI())) return true;

        Long userId = jwt.parse(token(request));
        if (userId != null) {
            // 已注销的账号：token 本身仍能解析（JWT 无状态），但必须立刻失效，
            // 否则「注销」只是清空了资料，用户拿旧 token 还能继续操作。
            UserStatusGuard guard = userStatusGuard();
            if (guard != null && !guard.isActive(userId)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"msg\":\"账号已注销，请重新登录\",\"data\":null}");
                return false;
            }
            request.setAttribute(USER_ID, userId);
            return true;
        }
        if (!enforce) return true;
        String path = request.getRequestURI();
        if (!requiresIdentity(request, path)) return true;
        reject(response);
        return false;
    }

    /** 由各自专用拦截器负责鉴权的路径，这里不参与。 */
    private static boolean isDelegatedPath(String path) {
        return path.startsWith("/api/admin/")
            || path.startsWith("/api/merchant/")
            || path.startsWith("/api/rider/");
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


