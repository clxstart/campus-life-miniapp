package com.campuslife.ha;

import java.time.Duration;

/**
 * 周期任务协调锁（finding-10/11）。
 *
 * 单实例模式用进程内实现；集群模式用数据库租约（scheduler_lock 表）。
 * 约定：锁只用来「减少重复执行」，任何资金副作用（入账、退款）必须另有唯一键/条件更新兜底，
 * 这样即使租约过期后两个节点短暂重叠，也不会重复扣款或重复退款。
 */
public interface ClusterLock {
    /** 尝试获取名为 name 的租约；已被其他节点持有且未过期时返回 false */
    boolean tryLock(String name, Duration lease);

    /** 释放本节点持有的租约（非本节点持有时无操作） */
    void unlock(String name);

    /** 获取到锁才执行 task，结束后释放；返回是否执行了 */
    default boolean runExclusive(String name, Duration lease, Runnable task) {
        if (!tryLock(name, lease)) return false;
        try { task.run(); return true; }
        finally { unlock(name); }
    }
}
