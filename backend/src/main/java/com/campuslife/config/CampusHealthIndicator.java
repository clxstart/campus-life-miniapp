package com.campuslife.config;

import com.campuslife.service.UploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务侧健康检查：数据库能不能真查、上传目录能不能写、磁盘还剩多少。
 *
 * 为什么不用默认的健康检查：Spring 自带的只测「连接池能拿到连接」，
 * 但连接拿到了不代表 SQL 能跑（权限被收回、表被锁都可能）。
 * 这里真跑一条 SELECT 1 才算通。
 *
 * 磁盘阈值：已用超过 95% 报 DOWN —— 磁盘满的表现和"服务挂了"几乎一样，
 * 提前在 health 里暴露，比等用户报错强。
 */
@Slf4j
@Component("campus")
@RequiredArgsConstructor
public class CampusHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;
    private final UploadService uploadService;

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        // 1. 数据库：真跑一次查询
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            builder.withDetail("database", "ok");
        } catch (Exception e) {
            log.warn("健康检查：数据库不可用", e);
            return Health.down().withDetail("database", "unreachable: " + e.getMessage()).build();
        }

        // 2. 上传目录可写
        try {
            Path root = uploadService.root();
            if (!Files.exists(root)) Files.createDirectories(root);
            File probe = Files.createTempFile(root, "health-", ".probe").toFile();
            boolean deleted = probe.delete();
            builder.withDetail("uploadDir", deleted ? "writable" : "writable-but-cannot-delete");
        } catch (Exception e) {
            log.warn("健康检查：上传目录不可写", e);
            return Health.down().withDetail("uploadDir", "not writable: " + e.getMessage()).build();
        }

        // 3. 磁盘剩余
        try {
            File store = uploadService.root().toFile();
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            if (total > 0) {
                long usedPercent = (total - free) * 100 / total;
                Map<String, Object> disk = new LinkedHashMap<>();
                disk.put("totalMb", total / 1024 / 1024);
                disk.put("freeMb", free / 1024 / 1024);
                disk.put("usedPercent", usedPercent);
                builder.withDetail("disk", disk);
                if (usedPercent >= 95) {
                    return Health.down().withDetail("disk", "磁盘剩余不足 5%（已用 " + usedPercent + "%）").build();
                }
            }
        } catch (Exception e) {
            builder.withDetail("disk", "unknown: " + e.getMessage());
        }

        return builder.build();
    }
}
