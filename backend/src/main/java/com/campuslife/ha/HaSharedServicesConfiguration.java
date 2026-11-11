package com.campuslife.ha;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 共享服务装配（finding-10）。
 *
 * 单实例模式：全部使用进程内实现，不建立任何 Redis/对象存储连接，本地开发零依赖可启动。
 * 集群模式：必须提供 Redis（限流、群聊跨节点广播、在线名单）和 S3 兼容对象存储（上传），
 * 缺任何一项在启动阶段直接失败——绝不带着单机实现悄悄跑起来。
 */
@Slf4j
@Configuration
public class HaSharedServicesConfiguration {

    @Bean(destroyMethod = "close")
    public RedisSupport redisSupport(HaProperties properties) {
        if (!properties.isCluster()) return null;
        if (properties.getRedisUrl() == null || properties.getRedisUrl().isBlank()) {
            // 兜底：正常应由 HaConfigValidation 在更早阶段拦下并给出变量名
            throw new IllegalStateException("campus.ha.mode=cluster 必须配置 CAMPUS_HA_REDIS_URL");
        }
        return new RedisSupport(properties.getRedisUrl(), properties.getRedisTimeoutMs(), nodeId(properties));
    }

    @Bean
    public RateCounter rateCounter(HaProperties properties, org.springframework.beans.factory.ObjectProvider<RedisSupport> redis) {
        if (properties.isCluster()) return new RedisRateCounter(() -> redis.getObject().commands(), "campus:rate:");
        return new LocalRateCounter();
    }

    @Bean
    public CirclePresence circlePresence(HaProperties properties, org.springframework.beans.factory.ObjectProvider<RedisSupport> redis) {
        if (properties.isCluster()) return new RedisCirclePresence(() -> redis.getObject().commands());
        return new LocalCirclePresence();
    }

    @Bean
    public ClusterBus clusterBus(HaProperties properties, org.springframework.beans.factory.ObjectProvider<RedisSupport> redis) {
        if (properties.isCluster()) return new RedisClusterBus(redis.getObject());
        return new LocalClusterBus(nodeId(properties));
    }

    @Bean
    public ObjectStorage objectStorage(HaProperties properties,
                                       @Value("${campus.upload.public-prefix:/uploads}") String publicPrefix,
                                       @Value("${campus.upload.private-prefix:/private}") String privatePrefix,
                                       @Value("${campus.upload.dir:uploads}") String dir,
                                       @Value("${campus.upload.base-url:http://127.0.0.1:8080}") String baseUrl) {
        if (properties.isS3()) {
            log.info("[ha] 上传存储：S3 兼容对象存储 bucket={}", properties.getStorage().getBucket());
            return new S3ObjectStorage(properties.getStorage(), publicPrefix, privatePrefix);
        }
        return new LocalObjectStorage(dir, publicPrefix, privatePrefix, baseUrl);
    }

    /**
     * 配置校验（fail-fast）。
     *
     * 必须是 BeanFactoryPostProcessor 且为 static @Bean：它在所有业务 Bean 实例化之前执行。
     * 之前写成普通 @Bean，结果 RedisSupport 先被创建并抛 "URI must not be empty"，
     * 用户看不到「缺哪个环境变量」这条真正有用的信息。
     */
    @Bean
    public static org.springframework.beans.factory.config.BeanFactoryPostProcessor haConfigValidation(Environment environment) {
        return beanFactory -> HaStartupValidator.validate(environment);
    }

    static String nodeId(HaProperties properties) {
        if (properties.getNodeId() != null && !properties.getNodeId().isBlank()) return properties.getNodeId();
        String host;
        try { host = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception e) { host = "node"; }
        return host + "-" + java.lang.management.ManagementFactory.getRuntimeMXBean().getPid();
    }

    /** 启动时校验部署形态配置；单实例生产明确标记为非 HA 并给出限制说明 */
    public static class HaStartupValidator {

        /** 从 Environment 绑定配置后校验（BeanFactoryPostProcessor 阶段调用，此时还没有任何业务 Bean） */
        public static void validate(Environment environment) {
            HaProperties properties = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("campus.ha", org.springframework.boot.context.properties.bind.Bindable.of(HaProperties.class))
                .orElseGet(HaProperties::new);
            validate(properties, environment);
        }

        public static void validate(HaProperties properties, Environment environment) {
            boolean prod = false;
            for (String profile : environment.getActiveProfiles()) {
                if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) prod = true;
            }
            if (!"single".equalsIgnoreCase(properties.getMode()) && !"cluster".equalsIgnoreCase(properties.getMode())) {
                throw new IllegalStateException("campus.ha.mode 只能是 single 或 cluster，当前值：" + properties.getMode());
            }
            if (properties.isCluster()) {
                java.util.List<String> missing = new java.util.ArrayList<>();
                if (blank(properties.getRedisUrl())) missing.add("CAMPUS_HA_REDIS_URL");
                HaProperties.Storage s = properties.getStorage();
                if (!"s3".equalsIgnoreCase(s.getType())) missing.add("CAMPUS_HA_STORAGE_TYPE=s3");
                if (blank(s.getEndpoint())) missing.add("CAMPUS_HA_STORAGE_ENDPOINT");
                if (blank(s.getBucket())) missing.add("CAMPUS_HA_STORAGE_BUCKET");
                if (blank(s.getAccessKey())) missing.add("CAMPUS_HA_STORAGE_ACCESS_KEY");
                if (blank(s.getSecretKey())) missing.add("CAMPUS_HA_STORAGE_SECRET_KEY");
                if (!missing.isEmpty()) {
                    throw new IllegalStateException("campus.ha.mode=cluster 缺少必需配置：" + String.join("、", missing)
                        + "。集群模式不会退回单机实现，请补齐后重启（见 docs/高可用部署与故障切换.md）。");
                }
                log.info("[ha] 启动校验通过：cluster 模式（Redis + 对象存储 + 数据库任务锁）");
            } else {
                if (prod) {
                    log.warn("[ha] 当前为 single 模式（非 HA）：上传只落本机磁盘、限流与群聊在线名单不跨节点、"
                        + "周期任务只能单实例执行。故障切换需人工介入，RTO≈人工发现+重启时间，RPO≈最近一次数据库备份。"
                        + "需要多实例时设置 CAMPUS_HA_MODE=cluster 并配置 Redis 与对象存储。");
                } else {
                    log.info("[ha] 启动校验通过：single 模式（本机磁盘 + 进程内限流/群聊）");
                }
            }
        }

        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }
}
