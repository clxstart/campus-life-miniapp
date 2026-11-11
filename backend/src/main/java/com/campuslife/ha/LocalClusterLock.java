package com.campuslife.ha;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 单实例模式：进程内租约，只防止本 JVM 内同一任务重叠执行 */
public class LocalClusterLock implements ClusterLock {
    private final Map<String, Long> leases = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String name, Duration lease) {
        long now = System.currentTimeMillis();
        long until = now + lease.toMillis();
        boolean[] acquired = {false};
        leases.compute(name, (key, current) -> {
            if (current == null || current < now) { acquired[0] = true; return until; }
            return current;
        });
        return acquired[0];
    }

    @Override
    public void unlock(String name) { leases.remove(name); }
}
