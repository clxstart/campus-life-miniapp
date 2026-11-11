package com.campuslife.ha;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 单实例模式：进程内在线名单（与原 CircleWebSocketManager 的房间结构对应） */
public class LocalCirclePresence implements CirclePresence {
    private final Map<Long, Set<Long>> circles = new ConcurrentHashMap<>();

    @Override
    public void join(Long circleId, Long userId) {
        circles.computeIfAbsent(circleId, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    @Override
    public void leave(Long circleId, Long userId) {
        circles.computeIfPresent(circleId, (k, set) -> { set.remove(userId); return set.isEmpty() ? null : set; });
    }

    @Override
    public void refresh(Long circleId, Collection<Long> userIds, long ttlMs) { /* 单机无过期语义 */ }

    @Override
    public List<Long> onlineUserIds(Long circleId) {
        Set<Long> set = circles.get(circleId);
        return set == null ? List.of() : new ArrayList<>(set);
    }

    @Override
    public void clearCircle(Long circleId) { circles.remove(circleId); }
}
