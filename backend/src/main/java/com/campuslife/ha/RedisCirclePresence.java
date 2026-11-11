package com.campuslife.ha;

import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * 集群模式：用 Redis ZSET 存某圈的在线成员，score = 到期时间戳。
 *
 * - join/refresh：ZADD 写入 now+ttl；
 * - 读取时先 ZREMRANGEBYSCORE 清掉过期成员，天然处理节点崩溃；
 * - 一个圈子一个键，跨节点汇总只需一次读，无需 SCAN。
 */
public class RedisCirclePresence implements CirclePresence {
    private static final String PREFIX = "circle:presence:";

    private final Supplier<RedisCommands<String, String>> commands;

    public RedisCirclePresence(Supplier<RedisCommands<String, String>> commands) { this.commands = commands; }

    private String key(Long circleId) { return PREFIX + circleId; }

    @Override
    public void join(Long circleId, Long userId) {
        commands.get().zadd(key(circleId), ZAddArgs.Builder.ch(), now() + 120_000, String.valueOf(userId));
    }

    @Override
    public void leave(Long circleId, Long userId) { commands.get().zrem(key(circleId), String.valueOf(userId)); }

    @Override
    public void refresh(Long circleId, Collection<Long> userIds, long ttlMs) {
        if (userIds == null || userIds.isEmpty()) return;
        long score = now() + ttlMs;
        Object[] args = new Object[userIds.size()];
        int i = 0;
        for (Long userId : userIds) args[i++] = String.valueOf(userId);
        // 逐个 ZADD 保证类型安全（Lettuce 的 ZADD 变参重载版本间有差异）
        for (Object member : args) commands.get().zadd(key(circleId), score, (String) member);
    }

    @Override
    public List<Long> onlineUserIds(Long circleId) {
        String k = key(circleId);
        commands.get().zremrangebyscore(k, (double) Long.MIN_VALUE, (double) now());
        List<String> members = commands.get().zrangebyscore(k, (double) now(), Double.MAX_VALUE);
        List<Long> out = new ArrayList<>(members.size());
        for (String member : members) {
            try { out.add(Long.valueOf(member)); } catch (NumberFormatException ignored) { /* 脏数据跳过 */ }
        }
        return out;
    }

    @Override
    public void clearCircle(Long circleId) { commands.get().del(key(circleId)); }

    private static long now() { return System.currentTimeMillis(); }
}
