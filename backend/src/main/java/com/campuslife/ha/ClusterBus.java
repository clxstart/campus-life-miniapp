package com.campuslife.ha;

import java.util.function.BiConsumer;

/**
 * 群聊跨节点广播（finding-10）：某用户发的消息要推给连在其他节点上的同圈用户。
 *
 * 约定：调用方先在本节点直接投递，再把同一条消息交给 publish；
 * 接收端的监听器只会收到「来自其他节点」的消息，因此不会重复推送。
 */
public interface ClusterBus extends AutoCloseable {
    /** 发布到其他节点（本节点不回调） */
    void publish(Long circleId, String payloadJson);

    /** 注册其他节点消息的回调（circleId, payloadJson） */
    void listen(BiConsumer<Long, String> handler);

    String nodeId();

    @Override
    default void close() { }
}
