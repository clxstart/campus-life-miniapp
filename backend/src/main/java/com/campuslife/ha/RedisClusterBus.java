package com.campuslife.ha;

import java.util.function.BiConsumer;

/** 集群模式的跨节点广播：委托给共享的 RedisSupport（订阅已在其构造时建立） */
public class RedisClusterBus implements ClusterBus {
    private final RedisSupport redis;

    public RedisClusterBus(RedisSupport redis) { this.redis = redis; }

    @Override
    public void publish(Long circleId, String payloadJson) { redis.publish(circleId, payloadJson); }

    @Override
    public void listen(BiConsumer<Long, String> handler) { redis.listen(handler); }

    @Override
    public String nodeId() { return redis.nodeId(); }

    @Override
    public void close() { /* 连接生命周期由 RedisSupport 管理与关闭 */ }
}
