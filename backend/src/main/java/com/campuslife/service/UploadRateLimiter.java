package com.campuslife.service;

import com.campuslife.config.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上传频率限制：按「来源标识」滑动窗口计数。
 *
 * 为什么需要：上传接口原来没有任何限制，一个脚本就能把服务器磁盘刷满。
 * 这里用最轻量的内存计数（单机够用）；将来上多实例要换成 Redis。
 *
 * 窗口策略：60 秒内最多 {@link #MAX_PER_WINDOW} 次。
 * 管理端和游客分别按自己的标识计数，互不影响。
 */
@Component
public class UploadRateLimiter {

    /** 60 秒窗口内允许的最大上传次数 */
    static final int MAX_PER_WINDOW = 20;
    /** 窗口长度（毫秒） */
    static final long WINDOW_MS = 60_000L;

    private record Bucket(int count, long windowStart) {}

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 记一次上传请求；超限抛 {@link TooManyRequestsException}（映射成 HTTP 429）。
     *
     * @param key 来源标识（管理端用 adminId，游客用 IP）
     */
    public void acquire(String key) {
        long now = System.currentTimeMillis();
        String safeKey = (key == null || key.isBlank()) ? "anonymous" : key;

        Bucket updated = buckets.compute(safeKey, (k, old) -> {
            if (old == null || now - old.windowStart() >= WINDOW_MS) {
                return new Bucket(1, now);
            }
            return new Bucket(old.count() + 1, old.windowStart());
        });

        if (updated != null && updated.count() > MAX_PER_WINDOW) {
            long remainMs = WINDOW_MS - (now - updated.windowStart());
            long remainSec = Math.max(1, (remainMs + 999) / 1000);
            throw new TooManyRequestsException("上传过于频繁，请 " + remainSec + " 秒后再试");
        }

        // 顺手清理过期桶，避免长期运行内存增长
        if (buckets.size() > 2000) {
            buckets.entrySet().removeIf(e -> now - e.getValue().windowStart() >= WINDOW_MS);
        }
    }

    /** 仅供测试/运维查看当前计数 */
    public int currentCount(String key) {
        Bucket b = buckets.get(key == null ? "anonymous" : key);
        if (b == null) return 0;
        if (System.currentTimeMillis() - b.windowStart() >= WINDOW_MS) return 0;
        return b.count();
    }
}
