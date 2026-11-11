package com.campuslife.ha;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;

/**
 * 按 campus.ha.mode 装配单机或集群实现（finding-10）。
 * 这里只放与 Redis/对象存储无关的部分（任务锁）；共享服务适配器见 {@link HaSharedServicesConfiguration}。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(HaProperties.class)
public class HaConfiguration {

    @Bean
    public ClusterLock clusterLock(HaProperties properties, JdbcTemplate jdbc) {
        if (!properties.isCluster()) {
            log.info("[ha] campus.ha.mode=single：周期任务锁为进程内实现（非 HA 部署）");
            return new LocalClusterLock();
        }
        String nodeId = resolveNodeId(properties);
        try {
            // fail-fast：集群模式下租约表必须存在，否则多节点会重复跑对账/补偿任务
            jdbc.queryForObject("SELECT COUNT(*) FROM scheduler_lock", Long.class);
        } catch (Exception e) {
            throw new IllegalStateException("campus.ha.mode=cluster 需要 scheduler_lock 表，请先执行 db/module-commerce-consistency.sql", e);
        }
        log.info("[ha] campus.ha.mode=cluster：周期任务锁使用数据库租约，节点 {}", nodeId);
        return new JdbcClusterLock(jdbc, nodeId);
    }

    static String resolveNodeId(HaProperties properties) {
        if (properties.getNodeId() != null && !properties.getNodeId().isBlank()) return properties.getNodeId();
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { host = "node"; }
        return host + "-" + ManagementFactory.getRuntimeMXBean().getPid();
    }
}
