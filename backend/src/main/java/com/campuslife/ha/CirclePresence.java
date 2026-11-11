package com.campuslife.ha;

import java.util.Collection;
import java.util.List;

/**
 * 群聊在线名单（finding-10）：多实例时同圈用户可能连在不同节点，在线人数与名单必须跨节点汇总。
 *
 * 成员带过期时间、由节点心跳续期，节点崩溃后其成员在 TTL 后自动消失，不需要额外的下线通知。
 */
public interface CirclePresence {
    void join(Long circleId, Long userId);

    void leave(Long circleId, Long userId);

    /** 心跳续期：只续本节点当前在线的用户 */
    void refresh(Long circleId, Collection<Long> userIds, long ttlMs);

    List<Long> onlineUserIds(Long circleId);

    default int onlineCount(Long circleId) { return onlineUserIds(circleId).size(); }

    /** 节点下线时清理本节点在该圈的全部记录 */
    void clearCircle(Long circleId);
}
