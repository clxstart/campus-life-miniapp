package com.campuslife.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 统一取「当前是谁」。
 *
 * 规则很简单：token 解析出来的身份永远优先，客户端上报的 userId 只在匿名只读接口里当个性化参数用。
 * 写操作和「我的数据」接口由 {@link AuthInterceptor} 保证一定带有效 token，
 * 所以这些接口里 resolve() 取到的必然是真实登录用户，不会是别人传进来的数字。
 */
public final class CurrentUser {

    private CurrentUser() {}

    /** 已登录用户 id；匿名访问返回 null。 */
    public static Long optional(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.USER_ID);
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 以登录身份为准；匿名时回落到调用方给的 claimed（仅用于公开只读接口的个性化）
     * 或默认值 def。
     */
    public static Long resolve(HttpServletRequest request, Long claimed, Long def) {
        Long authenticated = optional(request);
        if (authenticated != null) return authenticated;
        if (claimed != null && claimed > 0) return claimed;
        return def;
    }

    public static Long resolve(HttpServletRequest request, Long claimed) {
        return resolve(request, claimed, 1L);
    }
}
