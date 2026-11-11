package com.campuslife.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移脚本幂等性回归测试。
 *
 * 为什么要有这个测试：交付要求写着「涉及建表/改表的 SQL 必须幂等可重复执行」，此前这条只有
 * 人工声明——每个 module 文件顶部写着「幂等」，但没有任何一条用例真的把它跑第二遍。
 * 一旦有人写了一个裸 `ALTER TABLE ... ADD COLUMN`（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS），
 * 只有到上线那天在线上库执行第二遍时才会报 1060 Duplicate column。
 *
 * 做法：新建一个临时 schema，按 schema.sql → module-*.sql（文件名排序）的顺序整批执行两遍。
 * 两遍都不抛异常才算通过；结束时 DROP 临时库（不碰 campus_life，也不碰生产库）。
 *
 * 环境变量（未设置则整组跳过，与 MysqlIntegrationSupport 一致）：
 *   MYSQL_TEST_URL / MYSQL_TEST_USER / MYSQL_TEST_PASSWORD
 */
class SqlMigrationIdempotencyTest {

    /** surefire 的工作目录是 backend/，所以这里是 backend/src/main/resources/db。 */
    private static final String DB_DIR = "src/main/resources/db";

    @Test
    @DisplayName("schema.sql + 全部 module-*.sql 连续执行两遍都不报错")
    void everyMigrationRunsTwiceWithoutError() throws Exception {
        String serverUrl = System.getenv("MYSQL_TEST_URL");
        String user = System.getenv("MYSQL_TEST_USER");
        String password = System.getenv("MYSQL_TEST_PASSWORD");
        Assumptions.assumeTrue(serverUrl != null && !serverUrl.isBlank(), "未设置 MYSQL_TEST_URL，跳过迁移幂等测试");
        Assumptions.assumeTrue(user != null && !user.isBlank(), "未设置 MYSQL_TEST_USER，跳过迁移幂等测试");

        File dir = new File(DB_DIR);
        Assumptions.assumeTrue(dir.isDirectory(), "找不到迁移目录 " + dir.getAbsolutePath() + "，跳过");

        List<File> files = new ArrayList<>();
        File schema = new File(dir, "schema.sql");
        assertThat(schema).as("schema.sql 必须存在（它是所有 module 的前置）").isFile();
        files.add(schema);
        File[] modules = dir.listFiles((d, name) -> name.startsWith("module-") && name.endsWith(".sql"));
        assertThat(modules).as("db/ 下应当有 module-*.sql").isNotNull();
        Arrays.sort(modules);
        files.addAll(Arrays.asList(modules));
        assertThat(files.size()).as("迁移文件数量异常（少于 10 说明目录读错了）").isGreaterThan(10);

        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        String adminUrl = base + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        String schemaName = "campus_sqlit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String dbUrl = base + "/" + schemaName
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&allowMultiQueries=true";

        try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE `" + schemaName + "` DEFAULT CHARACTER SET utf8mb4");
        }

        List<String> failures = new ArrayList<>();
        try {
            for (int round = 1; round <= 2; round++) {
                try (Connection conn = DriverManager.getConnection(dbUrl, user, password)) {
                    for (File file : files) {
                        String sql = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                        if (sql.isBlank()) continue;
                        try (Statement st = conn.createStatement()) {
                            st.execute(sql);
                        } catch (Exception error) {
                            failures.add("第 " + round + " 遍 " + file.getName() + " → " + rootMessage(error));
                        }
                    }
                }
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
                 Statement st = admin.createStatement()) {
                st.execute("DROP DATABASE `" + schemaName + "`");
            }
        }

        assertThat(failures)
            .as("迁移脚本必须可以重复执行（第二遍不应报 1060/1050 之类），实际失败：%s", failures)
            .isEmpty();
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String msg = cause.getMessage();
        return msg == null ? cause.getClass().getSimpleName() : msg.replaceAll("\\s+", " ").trim();
    }
}