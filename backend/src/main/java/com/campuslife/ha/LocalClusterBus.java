package com.campuslife.ha;

import java.util.function.BiConsumer;

/** 单实例模式：没有其他节点，publish 为空操作（本节点已直接投递） */
public class LocalClusterBus implements ClusterBus {
    private final String nodeId;

    public LocalClusterBus(String nodeId) { this.nodeId = nodeId; }

    @Override
    public void publish(Long circleId, String payloadJson) { /* 单机无需跨节点转发 */ }

    @Override
    public void listen(BiConsumer<Long, String> handler) { /* 单机不会收到其他节点消息 */ }

    @Override
    public String nodeId() { return nodeId; }
}
