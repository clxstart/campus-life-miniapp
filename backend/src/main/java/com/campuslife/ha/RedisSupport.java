package com.campuslife.ha;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.function.BiConsumer;

/**
 * 集群模式的 Redis 通道：一条普通连接用于命令（限流/在线名单），一条订阅连接用于群聊广播。
 * 单个客户端实例同时被 RateCounter / Presence / Bus 复用，避免每处各建一套连接。
 */
@Slf4j
// 注意：本类不实现 ClusterBus。它同时被计数器/在线名单复用；若它也实现 ClusterBus，
// Spring 会把它当成 ClusterBus 的第二个候选，导致注入歧义。跨节点广播见 RedisClusterBus。
public class RedisSupport implements AutoCloseable {
    static final String CHANNEL = "campus:circle:broadcast";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final StatefulRedisPubSubConnection<String, String> pubsub;
    private final String nodeId;
    private volatile BiConsumer<Long, String> handler;

    public RedisSupport(String url, long timeoutMs, String nodeId) {
        RedisURI uri = RedisURI.create(url);
        if (uri.getTimeout() == null || uri.getTimeout().isZero()) uri.setTimeout(Duration.ofMillis(timeoutMs));
        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.pubsub = client.connectPubSub();
        this.nodeId = nodeId;
        pubsub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                BiConsumer<Long, String> consumer = handler;
                if (consumer == null) return;
                int first = message.indexOf('|');
                int second = message.indexOf('|', first + 1);
                if (first < 0 || second < 0) return;
                String origin = message.substring(0, first);
                if (nodeId.equals(origin)) return; // 本节点已直接投递，跳过回声
                try {
                    consumer.accept(Long.valueOf(message.substring(first + 1, second)), message.substring(second + 1));
                } catch (RuntimeException e) {
                    log.warn("[ha] 跨节点消息解析失败：{}", e.getClass().getSimpleName());
                }
            }
        });
        pubsub.sync().subscribe(CHANNEL);
        log.info("[ha] Redis 已连接，节点 {} 订阅 {}", nodeId, CHANNEL);
    }

    public RedisCommands<String, String> commands() { return connection.sync(); }

    public void publish(Long circleId, String payloadJson) {
        connection.sync().publish(CHANNEL, nodeId + "|" + circleId + "|" + payloadJson);
    }

    public void listen(BiConsumer<Long, String> handler) { this.handler = handler; }

    public String nodeId() { return nodeId; }

    /** 供 readiness 探针使用 */
    public String ping() { return connection.sync().ping(); }

    @Override
    public void close() {
        try { pubsub.close(); } catch (RuntimeException ignored) { }
        try { connection.close(); } catch (RuntimeException ignored) { }
        try { client.shutdown(); } catch (RuntimeException ignored) { }
    }
}
