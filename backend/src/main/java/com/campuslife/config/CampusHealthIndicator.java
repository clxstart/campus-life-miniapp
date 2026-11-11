package com.campuslife.config;

import com.campuslife.ha.HaProperties;
import com.campuslife.ha.ObjectStorage;
import com.campuslife.ha.RedisSupport;
import com.campuslife.service.UploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 业务侧健康检查（readiness）：数据库能不能真查、存储能不能读写、本机磁盘还剩多少。
 *
 * 为什么不用默认的健康检查：Spring 自带的只测「连接池能拿到连接」，
 * 但连接拿到了不代表 SQL 能跑（权限被收回、表被锁都可能），这里真跑一条 SELECT 1 才算通。
 *
 * finding-10：存储检查按部署形态走——本机模式测目录可写，对象存储模式测 bucket 可读写；
 * 磁盘容量只在真实本机落盘的部署里才有意义，对象存储模式下跳过。
 *
 * cluster-readiness：集群模式下 Redis 是节点「能提供服务」的必要条件——共享限流默认 fail-closed
 * （rate-limit-fail-open=false），在线名单与群聊跨节点广播都依赖它。所以 Redis 不可用/探测超时时
 * readiness 必须 DOWN，让编排把这个节点摘掉；single 模式完全不碰 Redis。
 *
 * 这个探针会被放进 readiness 组（见 application.yml 的 management.endpoint.health.group），
 * 不要放进 liveness：数据库抖动时应摘掉流量而不是重启实例。
 */
@Slf4j
@Component("campus")
@RequiredArgsConstructor
public class CampusHealthIndicator implements HealthIndicator {

    /**
     * 探测 Redis 的专用线程池：Redis ping 是阻塞的同步命令，绝不能直接占着 health 调用线程等满
     * redisTimeoutMs。固定小线程池 + daemon 线程：hung 的 ping 最多占住一个 worker（Lettuce 的
     * sync 命令不响应 interrupt，cancel(true) 只是尽力而为），后续探测照样按 timeout 返回 DOWN，
     * 而不会每次探测都新起一条永不退出的线程。
     */
    private static final ExecutorService REDIS_PROBE_POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "campus-redis-health");
        thread.setDaemon(true);
        return thread;
    });

    private final DataSource dataSource;
    private final UploadService uploadService;
    private final ObjectStorage storage;
    private final HaProperties haProperties;
    /** single 模式下 RedisSupport bean 是 null（见 HaSharedServicesConfiguration#redisSupport），
     *  构造注入 RedisSupport 会直接失败，必须用 ObjectProvider 延迟取。 */
    private final ObjectProvider<RedisSupport> redisProvider;

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        // 1. 数据库：真跑一次查询
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            builder.withDetail("database", "ok");
        } catch (Exception e) {
            log.warn("健康检查：数据库不可用", e);
            return Health.down().withDetail("database", "unreachable: " + e.getMessage()).build();
        }

        // 2. 存储可读写
        Path local = uploadService.localDisk();
        try {
            storage.healthCheck();
            builder.withDetail("storage", local == null ? "object-storage" : "local-disk");
        } catch (Exception e) {
            log.warn("健康检查：存储不可用", e);
            return Health.down().withDetail("storage", "unusable: " + e.getMessage()).build();
        }

        // 3. 磁盘剩余（仅本机落盘时）
        if (local != null) {
            try {
                Files.createDirectories(local);
                File store = local.toFile();
                long total = store.getTotalSpace();
                long free = store.getUsableSpace();
                if (total > 0) {
                    long usedPercent = (total - free) * 100 / total;
                    Map<String, Object> disk = new LinkedHashMap<>();
                    disk.put("totalMb", total / 1024 / 1024);
                    disk.put("freeMb", free / 1024 / 1024);
                    disk.put("usedPercent", usedPercent);
                    builder.withDetail("disk", disk);
                    if (usedPercent >= 95) {
                        return Health.down().withDetail("disk", "磁盘剩余不足 5%（已用 " + usedPercent + "%）").build();
                    }
                }
            } catch (Exception e) {
                builder.withDetail("disk", "unknown: " + e.getMessage());
            }
        }

        // 4. Redis（仅集群模式，见类注释 cluster-readiness）
        return redisHealth(builder);
    }

    /**
     * Redis 探针：single 模式直接标记 disabled、完全不触碰 Redis；cluster 模式做有界 ping，
     * 任何失败都返回 DOWN（fail-closed 语义）。
     */
    private Health redisHealth(Health.Builder builder) {
        if (!haProperties.isCluster()) {
            return builder.withDetail("redis", "disabled").build();
        }
        RedisSupport redis = redisProvider.getIfAvailable();
        if (redis == null) {
            // 集群模式启动校验已保证 RedisSupport 存在，这里纯属防御：配置错位时宁可 DOWN 也别假装健康
            log.warn("健康检查：集群模式没有 RedisSupport bean");
            return Health.down().withDetail("redis", "unavailable: no RedisSupport bean").build();
        }
        long timeoutMs = Math.max(1L, haProperties.getRedisHealthTimeoutMs());
        Future<String> probe = REDIS_PROBE_POOL.submit((Callable<String>) redis::ping);
        try {
            String reply = probe.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (reply == null || !"PONG".equalsIgnoreCase(reply.trim())) {
                return Health.down().withDetail("redis", "unexpected reply: " + reply).build();
            }
            return builder.withDetail("redis", "ok").build();
        } catch (TimeoutException e) {
            probe.cancel(true);
            log.warn("健康检查：Redis 探测超时（>{}ms），判定不可用", timeoutMs);
            return Health.down().withDetail("redis", "timeout").build();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("健康检查：Redis 不可用", cause);
            return Health.down().withDetail("redis", "unreachable: " + cause.getMessage()).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            probe.cancel(true);
            return Health.down().withDetail("redis", "interrupted").build();
        }
    }
}
