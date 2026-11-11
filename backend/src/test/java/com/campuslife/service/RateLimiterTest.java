package com.campuslife.service;

import com.campuslife.config.ServiceUnavailableException;
import com.campuslife.config.TooManyRequestsException;
import com.campuslife.ha.HaProperties;
import com.campuslife.ha.LocalRateCounter;
import com.campuslife.ha.RateCounter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Finding 10：限流走可替换的计数器；共享存储故障时默认拒绝（fail-closed），不静默退回内存计数 */
class RateLimiterTest {

    private static final RateLimiter.Rule RULE = new RateLimiter.Rule(60_000L, 3, "测试");

    private static RateLimiter limiter(RateCounter counter, boolean failOpen) {
        HaProperties p = new HaProperties();
        p.setRateLimitFailOpen(failOpen);
        return new RateLimiter(counter, p);
    }

    @Test
    void countsWithinWindowThenRejects() {
        RateLimiter limiter = limiter(new LocalRateCounter(), false);
        for (int i = 0; i < 3; i++) assertThatCode(() -> limiter.acquire(RULE, "k")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire(RULE, "k")).isInstanceOf(TooManyRequestsException.class).hasMessageContaining("过于频繁");
        // 不同 key 互不影响
        assertThatCode(() -> limiter.acquire(RULE, "other")).doesNotThrowAnyException();
    }

    @Test
    void windowExpiryAllowsAgain() throws Exception {
        RateCounter counter = new LocalRateCounter();
        RateLimiter.Rule quick = new RateLimiter.Rule(30L, 1, "窗口");
        RateLimiter limiter = limiter(counter, false);
        limiter.acquire(quick, "k");
        assertThatThrownBy(() -> limiter.acquire(quick, "k")).isInstanceOf(TooManyRequestsException.class);
        Thread.sleep(60);
        assertThatCode(() -> limiter.acquire(quick, "k")).doesNotThrowAnyException();
    }

    @Test
    void sharedCounterOutageIsRejectedWith503ByDefault() {
        RateCounter broken = new RateCounter() {
            @Override public Hit increment(String key, long windowMs) { throw new RateCounterUnavailableException("redis down", null); }
            @Override public long current(String key, long windowMs) { throw new RateCounterUnavailableException("redis down", null); }
        };
        assertThatThrownBy(() -> limiter(broken, false).acquire(RULE, "k"))
            .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("稍后");
    }

    @Test
    void failOpenMustBeExplicitAndIsNotSilentFallback() {
        RateCounter broken = new RateCounter() {
            @Override public Hit increment(String key, long windowMs) { throw new RateCounterUnavailableException("redis down", null); }
            @Override public long current(String key, long windowMs) { throw new RateCounterUnavailableException("redis down", null); }
        };
        // 显式打开才放行；注意这里不会退回本地计数，而是完全不计数（配置为 true 时的已知取舍）
        assertThatCode(() -> limiter(broken, true).acquire(RULE, "k")).doesNotThrowAnyException();
    }

    @Test
    void clusterCounterIsConsultedForEveryRequest() {
        // 模拟集群：两个「节点」共用同一个远端计数器时，阈值不会被放大
        LocalRateCounter shared = new LocalRateCounter();
        RateLimiter nodeA = limiter(shared, false);
        RateLimiter nodeB = limiter(shared, false);
        nodeA.acquire(RULE, "same-ip");
        nodeA.acquire(RULE, "same-ip");
        nodeB.acquire(RULE, "same-ip");
        assertThatThrownBy(() -> nodeB.acquire(RULE, "same-ip")).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void currentCountReflectsHits() {
        RateLimiter limiter = limiter(new LocalRateCounter(), false);
        limiter.acquire(RULE, "k");
        assertThat(limiter.currentCount(RULE, "k")).isEqualTo(1);
    }
}
