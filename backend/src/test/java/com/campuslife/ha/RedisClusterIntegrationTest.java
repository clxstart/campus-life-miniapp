package com.campuslife.ha;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 10 的跨节点行为（真实 Redis）。
 *
 * 需要设置环境变量 REDIS_TEST_URL（例如 redis://127.0.0.1:6379）；未设置时自动跳过，
 * 这样没有 Redis 的开发机也能跑完整测试套件。两个 RedisSupport 实例模拟两个应用节点。
 */
class RedisClusterIntegrationTest {
    private static String url;
    private static String available;

    @BeforeAll
    static void setUp() {
        url = System.getenv("REDIS_TEST_URL");
        available = url;
    }

    @AfterAll
    static void tearDown() { }

    private RedisSupport node(String id) {
        Assumptions.assumeTrue(available != null, "未设置 REDIS_TEST_URL，跳过真实 Redis 集成测试");
        return new RedisSupport(url, 2000, id);
    }

    @Test
    void rateCounterIsSharedAcrossNodes() {
        RedisSupport a = node("it-a");
        RedisSupport b = node("it-b");
        try {
            RedisRateCounter counterA = new RedisRateCounter(a::commands, "it:rate:");
            RedisRateCounter counterB = new RedisRateCounter(b::commands, "it:rate:");
            String key = "shared-" + System.nanoTime();
            // 两个节点各自计数，共享同一窗口：阈值不会被放大 N 倍
            assertThat(counterA.increment(key, 60_000).count()).isEqualTo(1);
            assertThat(counterB.increment(key, 60_000).count()).isEqualTo(2);
            assertThat(counterA.increment(key, 60_000).count()).isEqualTo(3);
            assertThat(counterB.current(key, 60_000)).isEqualTo(3);
        } finally { a.close(); b.close(); }
    }

    @Test
    void presenceAggregatesUsersFromAllNodes() {
        RedisSupport a = node("it-a");
        RedisSupport b = node("it-b");
        try {
            RedisCirclePresence presenceA = new RedisCirclePresence(a::commands);
            RedisCirclePresence presenceB = new RedisCirclePresence(b::commands);
            long circleId = 900_000 + (System.nanoTime() % 1000);
            presenceA.join(circleId, 1L);
            presenceB.join(circleId, 2L);
            // 任一节点读取都能看到两边的在线用户
            assertThat(presenceA.onlineUserIds(circleId)).containsExactlyInAnyOrder(1L, 2L);
            assertThat(presenceB.onlineCount(circleId)).isEqualTo(2);

            presenceA.leave(circleId, 1L);
            assertThat(presenceB.onlineUserIds(circleId)).containsExactly(2L);
            presenceA.clearCircle(circleId);
            assertThat(presenceB.onlineCount(circleId)).isZero();
        } finally { a.close(); b.close(); }
    }

    @Test
    void expiredPresenceDisappearsAfterNodeCrash() {
        RedisSupport a = node("it-a");
        RedisSupport b = node("it-b");
        try {
            RedisCirclePresence presenceA = new RedisCirclePresence(a::commands);
            RedisCirclePresence presenceB = new RedisCirclePresence(b::commands);
            long circleId = 950_000 + (System.nanoTime() % 1000);
            presenceA.join(circleId, 1L);
            presenceA.refresh(circleId, List.of(1L), 50); // 50ms 后过期：模拟节点 A 崩溃不再续期
            assertThat(presenceB.onlineCount(circleId)).isEqualTo(1);
            try { Thread.sleep(120); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            assertThat(presenceB.onlineCount(circleId)).isZero();
            presenceA.clearCircle(circleId);
        } finally { a.close(); b.close(); }
    }

    @Test
    void broadcastReachesOtherNodeButNotEchoBack() {
        RedisSupport a = node("it-a");
        RedisSupport b = node("it-b");
        try {
            List<String> atA = new CopyOnWriteArrayList<>();
            List<String> atB = new CopyOnWriteArrayList<>();
            a.listen((circleId, json) -> atA.add(json));
            b.listen((circleId, json) -> atB.add(json));
            long circleId = 990_000 + (System.nanoTime() % 1000);

            a.publish(circleId, "{\"type\":\"message\",\"n\":1}");
            waitUntil(() -> !atB.isEmpty(), 3000);
            assertThat(atB).hasSize(1);
            // 自己发的不回给自己，避免本节点重复投递
            assertThat(atA).isEmpty();
        } finally { a.close(); b.close(); }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
}
