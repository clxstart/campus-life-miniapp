package com.campuslife.ha;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/**
 * 集群模式：基于共享 MySQL 的租约锁（scheduler_lock 表，见 db/module-commerce-consistency.sql）。
 *
 * - 过期判断统一用数据库时钟 NOW()，不受各节点本地时钟漂移影响；
 * - 获取 = 条件 UPDATE（未过期且非本节点持有时 0 行命中）；MySQL Connector/J 默认返回「匹配行数」，
 *   因此本节点续租时即使值未变也返回 1；
 * - 节点崩溃不释放也无妨，租约到期后其他节点自动接手。
 */
public class JdbcClusterLock implements ClusterLock {
    private final JdbcTemplate jdbc;
    private final String nodeId;

    public JdbcClusterLock(JdbcTemplate jdbc, String nodeId) {
        this.jdbc = jdbc;
        this.nodeId = nodeId;
    }

    @Override
    public boolean tryLock(String name, Duration lease) {
        jdbc.update("INSERT IGNORE INTO scheduler_lock(name) VALUES (?)", name);
        int matched = jdbc.update(
            "UPDATE scheduler_lock SET owner=?, locked_until=DATE_ADD(NOW(), INTERVAL ? SECOND) " +
            "WHERE name=? AND (locked_until < NOW() OR owner=?)",
            nodeId, Math.max(1, lease.toSeconds()), name, nodeId);
        return matched == 1;
    }

    @Override
    public void unlock(String name) {
        jdbc.update("UPDATE scheduler_lock SET locked_until='1970-01-01 00:00:00' WHERE name=? AND owner=?", name, nodeId);
    }

    public String nodeId() { return nodeId; }
}
