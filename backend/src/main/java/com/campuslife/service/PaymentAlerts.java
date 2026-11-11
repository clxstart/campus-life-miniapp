package com.campuslife.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 支付链路的告警出口：计数器 + 限速日志。
 *
 * - 计数器 campus.pay.events{event,reason} 供 /actuator/metrics 与外部告警规则使用；
 *   event/reason 只允许调用方传入固定常量，绝不放请求方可控的字段，避免指标基数爆炸。
 * - 同一 event+reason 的告警日志 30 秒内最多打一条，防止攻击流量把日志刷满（finding-02）。
 */
@Slf4j
@Component
public class PaymentAlerts {
    static final long LOG_INTERVAL_MS = 30_000;

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> lastLogAt = new ConcurrentHashMap<>();

    public PaymentAlerts(MeterRegistry registry) { this.registry = registry; }

    public void count(String event, String reason) {
        registry.counter("campus.pay.events", "event", event, "reason", reason).increment();
    }

    /** 计数并限速输出告警日志；message 中只应包含订单号/交易号等内部已校验的标识 */
    public void warn(String event, String reason, String message, Object... args) {
        count(event, reason);
        String key = event + ":" + reason;
        long now = System.currentTimeMillis();
        AtomicLong last = lastLogAt.computeIfAbsent(key, k -> new AtomicLong(0));
        long previous = last.get();
        if (now - previous >= LOG_INTERVAL_MS && last.compareAndSet(previous, now)) {
            log.warn("[pay-alert] " + event + "/" + reason + " " + message, args);
        }
    }

    /** 积压量等瞬时值：持有强引用，避免 Micrometer 弱引用 gauge 被 GC 后读成 NaN */
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public void gauge(String name, long value) {
        gauges.computeIfAbsent(name, n -> registry.gauge(n, new AtomicLong())).set(value);
    }
}
