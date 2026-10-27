package com.campuslife.service;

import com.campuslife.config.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用频率限制：按「场景 + 标识」做固定窗口计数。
 *
 * 为什么需要：原来只有上传接口有 limiter，登录、发帖、评论、AI 对话都能被脚本无限刷。
 * 超限统一抛 {@link TooManyRequestsException}（映射成 HTTP 429 + 中文提示），
 * 而不是 500 或默默失败。
 *
 * 实现选择：单机内存计数，够当前规模且零依赖。
 * 将来上多实例要换成 Redis（那时把 acquire 的实现换掉即可，调用方不用改）。
 *
 * 限流值是保守的起步值，真实流量上来后按日志调整。
 */
@Slf4j
@Component
public class RateLimiter {

    /** 一个限流规则：窗口长度 + 窗口内次数上限 + 给人看的名字。 */
    public record Rule(long windowMs, int limit, String label) {}

    /** 登录：同 IP 每分钟 10 次（正常用户不会这么频繁，挡撞库）。 */
    public static final Rule LOGIN = new Rule(60_000L, 10, "登录");
    /** 发帖/评论：同用户每分钟 10 次（挡机器刷屏）。 */
    public static final Rule PUBLISH = new Rule(60_000L, 10, "发布");
    /** AI 对话：同用户每小时 60 条（防刷）。 */
    public static final Rule AI_CHAT = new Rule(3_600_000L, 60, "AI 对话");
    /** 上传：保持原有强度，同来源每分钟 20 次。 */
    public static final Rule UPLOAD = new Rule(60_000L, 20, "上传");

    private record Bucket(int count, long windowStart) {}

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** 记一次请求；超限抛 429。key 建议形如 "login:1.2.3.4"、"publish:42"。 */
    public void acquire(Rule rule, String key) {
        String safeKey = (key == null || key.isBlank()) ? "anonymous" : key;
        String bucketKey = rule.label() + "|" + safeKey;
        long now = System.currentTimeMillis();

        Bucket updated = buckets.compute(bucketKey, (k, old) -> {
            if (old == null || now - old.windowStart() >= rule.windowMs()) {
                return new Bucket(1, now);
            }
            return new Bucket(old.count() + 1, old.windowStart());
        });

        if (updated != null && updated.count() > rule.limit()) {
            long remainMs = rule.windowMs() - (now - updated.windowStart());
            long remainSec = Math.max(1, (remainMs + 999) / 1000);
            log.warn("[campus] 限流命中 {} key={} 第 {} 次", rule.label(), safeKey, updated.count());
            throw new TooManyRequestsException(rule.label() + "过于频繁，请 " + remainSec + " 秒后再试");
        }

        // 顺手清理过期桶，避免长期运行内存持续增长
        if (buckets.size() > 5000) {
            buckets.entrySet().removeIf(e -> now - e.getValue().windowStart() >= 3_600_000L);
        }
    }

    /** 仅供测试/运维查看当前计数 */
    public int currentCount(Rule rule, String key) {
        Bucket b = buckets.get(rule.label() + "|" + (key == null ? "anonymous" : key));
        if (b == null) return 0;
        if (System.currentTimeMillis() - b.windowStart() >= rule.windowMs()) return 0;
        return b.count();
    }
}
