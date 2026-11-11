package com.campuslife.ha;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.List;
import java.util.function.Supplier;

/**
 * 集群模式：Redis 固定窗口计数。INCR 与首次 PEXPIRE 在同一段 Lua 中原子执行，
 * 避免「INCR 成功、设置过期失败」留下永不过期的计数键。
 */
public class RedisRateCounter implements RateCounter {
    static final String SCRIPT = """
        local c = redis.call('INCR', KEYS[1])
        if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
        local ttl = redis.call('PTTL', KEYS[1])
        if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); ttl = tonumber(ARGV[1]) end
        return {c, ttl}
        """;

    private final Supplier<RedisCommands<String, String>> commands;
    private final String prefix;

    public RedisRateCounter(Supplier<RedisCommands<String, String>> commands, String prefix) {
        this.commands = commands;
        this.prefix = prefix;
    }

    private String redisKey(String key, long windowMs) {
        // 按窗口序号分键：窗口切换自然换键，旧键靠过期回收
        return prefix + key + ":" + (System.currentTimeMillis() / windowMs);
    }

    @Override
    public Hit increment(String key, long windowMs) {
        try {
            List<Object> result = commands.get().eval(SCRIPT, ScriptOutputType.MULTI, new String[] { redisKey(key, windowMs) }, String.valueOf(windowMs));
            long count = ((Number) result.get(0)).longValue();
            long ttl = ((Number) result.get(1)).longValue();
            return new Hit(count, Math.max(0, ttl));
        } catch (RuntimeException e) {
            throw new RateCounterUnavailableException("共享限流计数不可用", e);
        }
    }

    @Override
    public long current(String key, long windowMs) {
        try {
            String value = commands.get().get(redisKey(key, windowMs));
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException e) {
            throw new RateCounterUnavailableException("共享限流计数不可用", e);
        }
    }
}
