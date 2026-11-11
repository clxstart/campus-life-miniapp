package com.campuslife.service;

import com.campuslife.config.ServiceUnavailableException;
import com.campuslife.config.TooManyRequestsException;
import com.campuslife.ha.HaProperties;
import com.campuslife.ha.RateCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 通用频率限制：按「场景 + 标识」做固定窗口计数。
 *
 * 为什么需要：原来只有上传接口有 limiter，登录、发帖、评论、AI 对话都能被脚本无限刷。
 * 超限统一抛 {@link TooManyRequestsException}（映射成 HTTP 429 + 中文提示），而不是 500 或默默失败。
 *
 * 计数实现由 campus.ha.mode 决定（finding-10）：
 *   single  → 进程内计数，本地开发零依赖；
 *   cluster → Redis 共享计数，所有节点同一阈值，不会因为多实例把限额放大 N 倍。
 * 共享存储故障时默认拒绝请求（fail-closed）并抛 503，绝不静默退回进程内计数——
 * 否则一次 Redis 抖动就等于所有限流形同虚设。确需放行时显式打开 campus.ha.rate-limit-fail-open。
 *
 * 限流值是保守的起步值，真实流量上来后按日志调整。
 */
@Slf4j
@Component
@RequiredArgsConstructor
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

    private final RateCounter counter;
    private final HaProperties haProperties;

    /** 记一次请求；超限抛 429。key 建议形如 "login:1.2.3.4"、"publish:42"。 */
    public void acquire(Rule rule, String key) {
        String safeKey = (key == null || key.isBlank()) ? "anonymous" : key;
        String bucketKey = rule.label() + "|" + safeKey;
        RateCounter.Hit hit;
        try {
            hit = counter.increment(bucketKey, rule.windowMs());
        } catch (RateCounter.RateCounterUnavailableException error) {
            // 共享限流不可用：默认拒绝（fail-closed），状态码与「限流命中」区分，便于告警区分原因
            log.warn("[campus] 共享限流计数不可用，规则={} key={}：{}", rule.label(), safeKey, error.getMessage());
            if (!haProperties.isRateLimitFailOpen()) throw new ServiceUnavailableException("服务繁忙，请稍后重试", error);
            log.warn("[campus] campus.ha.rate-limit-fail-open=true，本次请求已放行（限流未生效）");
            return;
        }
        if (hit.count() > rule.limit()) {
            long remainSec = Math.max(1, (hit.remainMs() + 999) / 1000);
            log.warn("[campus] 限流命中 {} key={} 第 {} 次", rule.label(), safeKey, hit.count());
            throw new TooManyRequestsException(rule.label() + "过于频繁，请 " + remainSec + " 秒后再试");
        }
    }

    /** 仅供测试/运维查看当前计数；共享存储不可用时返回 -1 */
    public int currentCount(Rule rule, String key) {
        try { return (int) counter.current(rule.label() + "|" + (key == null ? "anonymous" : key), rule.windowMs()); }
        catch (RateCounter.RateCounterUnavailableException error) { return -1; }
    }
}
