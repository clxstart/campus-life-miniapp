package com.campuslife.service;

import com.campuslife.entity.User;
import com.campuslife.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户是否可用的轻量校验。
 *
 * 为什么需要：JWT 是无状态的，账号注销后旧 token 仍然有效 ——
 * 不做这个校验，「注销」就只是清空了资料，用户拿旧 token 还能继续操作。
 *
 * 性能取舍：每个已登录请求都要知道用户状态，但每请求查一次库太浪费，
 * 所以本地缓存 60 秒。注销时主动失效缓存，做到"注销立即生效"，
 * 而正常请求基本都命中缓存，不增加数据库压力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserStatusGuard {

    private static final long TTL_MS = 60_000L;

    private final UserMapper userMapper;

    private record Entry(boolean active, long at) {}

    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    /** 账号是否可用（存在且未注销）。读不到时按"可用"处理，避免一次查询失败把所有人踢下线。 */
    public boolean isActive(Long userId) {
        if (userId == null || userId < 1) return false;
        long now = System.currentTimeMillis();
        Entry cached = cache.get(userId);
        if (cached != null && now - cached.at() < TTL_MS) return cached.active();
        boolean active = true;
        try {
            User user = userMapper.selectById(userId);
            active = user != null && (user.getStatus() == null || user.getStatus() != 0);
        } catch (Exception e) {
            log.warn("[campus] 读取用户状态失败，本次按可用处理 userId={}: {}", userId, e.getMessage());
        }
        cache.put(userId, new Entry(active, now));
        if (cache.size() > 5000) {
            cache.entrySet().removeIf(en -> now - en.getValue().at() >= TTL_MS);
        }
        return active;
    }

    /** 状态变化（注销/恢复）后立即失效，保证下一次请求就能看到新状态。 */
    public void invalidate(Long userId) {
        if (userId != null) cache.remove(userId);
    }
}
