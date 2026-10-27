package com.campuslife.service;

import com.campuslife.mapper.AdminModuleMapper;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 汇总系统状态，供管理端「系统状态」页展示。
 *
 * 设计原则：**任何一项取不到都不能让接口失败**。运维页在系统出问题时最能派上用场，
 * 如果它自己也 500 了就没意义了。所以每段都单独 try/catch，取不到就返回 unknown。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemStatusService {

    private final DataSource dataSource;
    private final UploadService uploadService;
    private final AdminModuleMapper adminModuleMapper;
    private final ContentReportService reportService;

    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("health", health());
        out.put("jvm", jvm());
        out.put("pool", pool());
        out.put("disk", disk());
        out.put("business", business());
        out.put("slowRequests", slowRequests());
        out.put("timestamp", java.time.LocalDateTime.now().toString());
        return out;
    }

    private Map<String, Object> health() {
        Map<String, Object> row = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        // 数据库：真跑一条查询
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            row.put("database", "ok");
        } catch (Exception e) {
            row.put("database", "不可用: " + e.getMessage());
            problems.add("数据库不可用");
        }

        // 上传目录
        try {
            Path root = uploadService.root();
            if (!Files.exists(root)) Files.createDirectories(root);
            File probe = Files.createTempFile(root, "status-", ".probe").toFile();
            row.put("uploadDir", probe.delete() ? "writable" : "writable-but-cannot-delete");
        } catch (Exception e) {
            row.put("uploadDir", "不可写: " + e.getMessage());
            problems.add("上传目录不可写");
        }

        // 磁盘
        try {
            File store = uploadService.root().toFile();
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            if (total > 0) {
                long usedPercent = (total - free) * 100 / total;
                row.put("diskUsedPercent", usedPercent);
                if (usedPercent >= 95) problems.add("磁盘剩余不足 5%");
            }
        } catch (Exception e) {
            row.put("disk", "未知: " + e.getMessage());
        }

        // 连接池是否有线程排队（排到了说明已经打满）
        try {
            if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
                int awaiting = hikari.getHikariPoolMXBean().getThreadsAwaitingConnection();
                row.put("poolAwaiting", awaiting);
                if (awaiting > 0) problems.add("连接池有 " + awaiting + " 个线程在等待");
            }
        } catch (Exception ignored) {
        }

        row.put("status", problems.isEmpty() ? "UP" : "DOWN");
        row.put("problems", problems);
        return row;
    }

    private Map<String, Object> jvm() {
        Map<String, Object> row = new LinkedHashMap<>();
        try {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            row.put("heapUsedMb", heap.getUsed() / 1024 / 1024);
            row.put("heapMaxMb", heap.getMax() / 1024 / 1024);
            row.put("heapUsedPercent", heap.getMax() > 0 ? heap.getUsed() * 100 / heap.getMax() : 0);
            row.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
            row.put("uptimeMinutes", ManagementFactory.getRuntimeMXBean().getUptime() / 1000 / 60);
            row.put("processors", Runtime.getRuntime().availableProcessors());
        } catch (Exception e) {
            row.put("error", e.getMessage());
        }
        return row;
    }

    private Map<String, Object> pool() {
        Map<String, Object> row = new LinkedHashMap<>();
        try {
            if (dataSource instanceof HikariDataSource hikari) {
                row.put("active", hikari.getHikariPoolMXBean() == null ? -1 : hikari.getHikariPoolMXBean().getActiveConnections());
                row.put("idle", hikari.getHikariPoolMXBean() == null ? -1 : hikari.getHikariPoolMXBean().getIdleConnections());
                row.put("total", hikari.getHikariPoolMXBean() == null ? -1 : hikari.getHikariPoolMXBean().getTotalConnections());
                row.put("max", hikari.getMaximumPoolSize());
                row.put("awaiting", hikari.getHikariPoolMXBean() == null ? 0 : hikari.getHikariPoolMXBean().getThreadsAwaitingConnection());
            } else {
                row.put("note", "连接池不是 HikariCP，未采集");
            }
        } catch (Exception e) {
            row.put("error", e.getMessage());
        }
        return row;
    }

    private Map<String, Object> disk() {
        Map<String, Object> row = new LinkedHashMap<>();
        try {
            File store = uploadService.root().toFile();
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            row.put("totalGb", round1(total / 1024.0 / 1024 / 1024));
            row.put("freeGb", round1(free / 1024.0 / 1024 / 1024));
            row.put("usedPercent", total > 0 ? (total - free) * 100 / total : 0);
        } catch (Exception e) {
            row.put("error", e.getMessage());
        }
        return row;
    }

    private Map<String, Object> business() {
        Map<String, Object> row = new LinkedHashMap<>();
        try {
            Map<String, Object> dashboard = adminModuleMapper.dashboard(null);
            if (dashboard != null) {
                // 字段名与 AdminModuleMapper.dashboard 的 SELECT 别名保持一致
                row.put("users", dashboard.get("users"));
                row.put("posts", dashboard.get("posts"));
                row.put("goods", dashboard.get("goods"));
                row.put("jobs", dashboard.get("jobs"));
                row.put("activities", dashboard.get("activities"));
                row.put("comments", dashboard.get("comments"));
                row.put("orders", dashboard.get("orders"));
            }
        } catch (Exception e) {
            row.put("error", e.getMessage());
        }
        // 待处理举报单独查：它是运维需要立刻看到的一项
        try {
            row.put("pendingReports", reportService.countPending());
        } catch (Exception e) {
            row.put("pendingReports", "unknown");
        }
        return row;
    }

    /**
     * 最近的慢请求。
     * 实现方式：从 app.log 尾部读一段（最多 256KB），筛出 WARN「慢请求」的行。
     * 不引入日志解析框架，够用且零依赖。
     */
    private List<String> slowRequests() {
        List<String> hits = new ArrayList<>();
        Path logFile = Path.of(System.getenv().getOrDefault("LOG_DIR", "logs"), "app.log");
        if (!Files.exists(logFile)) return hits;
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long length = raf.length();
            long from = Math.max(0, length - 256 * 1024);
            raf.seek(from);
            byte[] buf = new byte[(int) (length - from)];
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                if (line.contains("慢请求")) hits.add(line.trim());
            }
            if (hits.size() > 10) hits = hits.subList(hits.size() - 10, hits.size());
        } catch (Exception e) {
            log.debug("读取慢请求日志失败", e);
        }
        return hits;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}


