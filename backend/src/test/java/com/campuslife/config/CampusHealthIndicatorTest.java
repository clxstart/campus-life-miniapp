package com.campuslife.config;

import com.campuslife.ha.HaProperties;
import com.campuslife.ha.ObjectStorage;
import com.campuslife.ha.RedisSupport;
import com.campuslife.service.UploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.yaml.snakeyaml.Yaml;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * cluster-readiness：Redis 不可用/超时必须让 readiness DOWN，且探测有界（不等满命令超时）；
 * single 模式完全不触碰 Redis；数据库故障仍是 DOWN；liveness 组不受影响。
 */
class CampusHealthIndicatorTest {

    // ---------- 装置 ----------

    private static HaProperties props(String mode) {
        HaProperties p = new HaProperties();
        p.setMode(mode);
        return p;
    }

    /** 数据库真跑 SELECT 1 成功；本地盘为 null 走对象存储分支，跳过磁盘容量检查 */
    private static DataSource healthyDataSource() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        return dataSource;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RedisSupport> provider(RedisSupport redis) {
        ObjectProvider<RedisSupport> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return provider;
    }

    private static CampusHealthIndicator indicator(HaProperties props, DataSource dataSource,
                                                   ObjectProvider<RedisSupport> provider) {
        UploadService uploadService = mock(UploadService.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(uploadService.localDisk()).thenReturn(null);
        return new CampusHealthIndicator(dataSource, uploadService, storage, props, provider);
    }

    private static Map<String, Object> child(Map<String, Object> node, String key) {
        Object value = node.get(key);
        assertThat(value).as("配置节点 %s 必须存在且是 Map", key).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> child = (Map<String, Object>) value;
        return child;
    }

    // ---------- 分支覆盖 ----------

    @Test
    void singleModeNeverTouchesRedis() throws Exception {
        HaProperties props = props("single");
        RedisSupport redis = mock(RedisSupport.class);
        ObjectProvider<RedisSupport> provider = provider(redis);

        Health health = indicator(props, healthyDataSource(), provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("redis", "disabled");
        // 不取 bean、不 ping：single 模式连 Redis 客户端都不该存在
        verify(provider, never()).getIfAvailable();
        verifyNoInteractions(redis);
    }

    @Test
    void clusterPongIsUpAndReportedOk() throws Exception {
        HaProperties props = props("cluster");
        RedisSupport redis = mock(RedisSupport.class);
        when(redis.ping()).thenReturn("PONG");

        Health health = indicator(props, healthyDataSource(), provider(redis)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("redis", "ok");
        verify(redis).ping();
    }

    @Test
    void clusterPingThrowingIsDown() throws Exception {
        HaProperties props = props("cluster");
        RedisSupport redis = mock(RedisSupport.class);
        when(redis.ping()).thenThrow(new IllegalStateException("connection refused"));

        Health health = indicator(props, healthyDataSource(), provider(redis)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat((String) health.getDetails().get("redis"))
            .contains("unreachable")
            .contains("connection refused");
    }

    @Test
    void clusterHungPingIsDownWithinBoundedTime() throws Exception {
        HaProperties props = props("cluster");
        props.setRedisHealthTimeoutMs(200);
        RedisSupport redis = mock(RedisSupport.class);
        // 模拟 Redis 挂死：ping 阻塞 2 秒（= redis-timeout-ms 的默认量级），探针不能陪着等
        when(redis.ping()).thenAnswer(invocation -> {
            Thread.sleep(2000);
            return "PONG";
        });

        long startedAt = System.nanoTime();
        Health health = indicator(props, healthyDataSource(), provider(redis)).health();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("redis", "timeout");
        assertThat(elapsedMs).isLessThan(1500);
    }

    @Test
    void databaseFailureIsDownEvenWhenRedisIsOk() throws Exception {
        HaProperties props = props("cluster");
        RedisSupport redis = mock(RedisSupport.class);
        when(redis.ping()).thenReturn("PONG");
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("db down"));

        Health health = indicator(props, dataSource, provider(redis)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat((String) health.getDetails().get("database")).contains("unreachable: db down");
    }

    // ---------- 配置：Redis 只能进 readiness，绝不能进 liveness ----------

    @Test
    void livenessGroupExcludesRedisAndCampusButReadinessIncludesCampus() throws Exception {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("application.yml");
        assertThat(stream).as("classpath:application.yml 必须存在").isNotNull();
        Map<String, Object> root;
        try (InputStream in = stream) {
            root = new Yaml().load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        Map<String, Object> group = child(child(child(child(root, "management"), "endpoint"), "health"), "group");
        String liveness = String.valueOf(child(group, "liveness").get("include"));
        String readiness = String.valueOf(child(group, "readiness").get("include"));

        assertThat(liveness).doesNotContain("campus").doesNotContain("redis");
        assertThat(readiness).contains("campus").contains("readinessState");
        // 有界探测的上界必须真的落到配置里（HaProperties.redisHealthTimeoutMs = 500）
        Map<String, Object> ha = child(child(root, "campus"), "ha");
        assertThat(ha).containsKey("redis-health-timeout-ms");
    }
}
