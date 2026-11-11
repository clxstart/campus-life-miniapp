package com.campuslife.ha;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 单实例模式：进程内固定窗口计数（与原 RateLimiter 行为一致） */
public class LocalRateCounter implements RateCounter {
    private record Bucket(long count, long windowStart) {}

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public Hit increment(String key, long windowMs) {
        long now = System.currentTimeMillis();
        Bucket updated = buckets.compute(key, (k, old) ->
            old == null || now - old.windowStart() >= windowMs ? new Bucket(1, now) : new Bucket(old.count() + 1, old.windowStart()));
        // 防止长期运行内存增长：桶数过多时清理一小时前的窗口
        if (buckets.size() > 5000) buckets.entrySet().removeIf(e -> now - e.getValue().windowStart() >= 3_600_000L);
        return new Hit(updated.count(), windowMs - (now - updated.windowStart()));
    }

    @Override
    public long current(String key, long windowMs) {
        Bucket b = buckets.get(key);
        if (b == null || System.currentTimeMillis() - b.windowStart() >= windowMs) return 0;
        return b.count();
    }
}
