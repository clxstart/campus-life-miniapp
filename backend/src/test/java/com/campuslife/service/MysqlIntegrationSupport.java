package com.campuslife.service;

import com.campuslife.mapper.MerchantMapper;
import com.campuslife.mapper.PaymentExceptionMapper;
import com.campuslife.mapper.RefundRecordMapper;
import com.campuslife.mapper.UserOrderMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

/**
 * 真实 MySQL 集成测试脚手架（不走生产库）。
 *
 * 环境变量（未设置则整组测试跳过，报告必须如实写明跳过原因）：
 *   MYSQL_TEST_URL      例如 jdbc:mysql://127.0.0.1:3306（不要带库名/查询参数）
 *   MYSQL_TEST_USER     例如 root
 *   MYSQL_TEST_PASSWORD 对应密码
 *
 * 每个测试类创建一个临时 schema（campus_life_it_<随机>），只建本组用例需要的最小表，
 * 结束后 DROP 该临时 schema。不读取、不写入 campus_life 等业务库。
 */
final class MysqlIntegrationSupport implements AutoCloseable {
    final String schema;
    final DataSource dataSource;
    /** 原生 SqlSession 用：JdbcTransactionFactory 会真正按 autoCommit=false 开启事务并持锁 */
    final SqlSessionFactory sessionFactory;
    /** SqlSessionTemplate 用：必须 SpringManagedTransactionFactory 才能参与 TransactionTemplate */
    final SqlSessionFactory springSessionFactory;
    final JdbcTemplate jdbc;
    final TransactionTemplate transactionTemplate;

    private MysqlIntegrationSupport(String schema, DataSource dataSource,
                                    SqlSessionFactory sessionFactory, SqlSessionFactory springSessionFactory) {
        this.schema = schema;
        this.dataSource = dataSource;
        this.sessionFactory = sessionFactory;
        this.springSessionFactory = springSessionFactory;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    static MysqlIntegrationSupport create() {
        String serverUrl = System.getenv("MYSQL_TEST_URL");
        String user = System.getenv("MYSQL_TEST_USER");
        String password = System.getenv("MYSQL_TEST_PASSWORD");
        Assumptions.assumeTrue(serverUrl != null && !serverUrl.isBlank(),
            "未设置 MYSQL_TEST_URL，跳过真实 MySQL 并发/约束集成测试");
        Assumptions.assumeTrue(user != null && !user.isBlank(), "未设置 MYSQL_TEST_USER，跳过真实 MySQL 集成测试");

        String schema = "campus_life_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        String dbUrl = base + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true";

        DriverManagerDataSource admin = new DriverManagerDataSource(base + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", user, password);
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE `" + schema + "` DEFAULT CHARACTER SET utf8mb4");
        } catch (Exception e) {
            throw new IllegalStateException("创建临时测试库失败（需要 CREATE/DROP 权限）", e);
        }

        DriverManagerDataSource ds = new DriverManagerDataSource(dbUrl, user, password);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE user_order (
                  id BIGINT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  status VARCHAR(24) NOT NULL,
                  pay_status VARCHAR(24) NOT NULL,
                  pay_amount_cents INT NULL,
                  out_trade_no VARCHAR(64) NULL,
                  transaction_id VARCHAR(64) NULL,
                  rider_id BIGINT NULL,
                  cancel_reason VARCHAR(255) NULL,
                  cancelled_at DATETIME NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  coupon_id BIGINT NULL,
                  coupon_discount DECIMAL(10,2) NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            s.execute("""
                CREATE TABLE refund_record (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  order_id BIGINT NOT NULL,
                  out_refund_no VARCHAR(64) NOT NULL,
                  refund_id VARCHAR(64) NULL,
                  amount_cents INT NOT NULL,
                  reason VARCHAR(200) NOT NULL DEFAULT '',
                  status VARCHAR(16) NOT NULL DEFAULT 'pending',
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_refund_out_no (out_refund_no),
                  UNIQUE KEY uk_refund_order (order_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            s.execute("""
                CREATE TABLE payment_exception (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  order_id BIGINT NULL,
                  out_trade_no VARCHAR(64) NOT NULL,
                  transaction_id VARCHAR(64) NOT NULL,
                  amount_cents INT NOT NULL,
                  kind VARCHAR(32) NOT NULL,
                  status VARCHAR(24) NOT NULL DEFAULT 'open',
                  out_refund_no VARCHAR(64) NULL,
                  note VARCHAR(255) NOT NULL DEFAULT '',
                  attempts INT NOT NULL DEFAULT 0,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_payexc_txn_kind (transaction_id, kind),
                  KEY idx_payexc_status (status, updated_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            s.execute("""
                CREATE TABLE notification (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  user_id BIGINT NULL,
                  title VARCHAR(128) NULL,
                  content VARCHAR(512) NULL,
                  category VARCHAR(32) NULL,
                  tone VARCHAR(16) NULL,
                  source_key VARCHAR(128) NULL,
                  UNIQUE KEY uk_notification_source (source_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            s.execute("""
                CREATE TABLE scheduler_lock (
                  name VARCHAR(64) PRIMARY KEY,
                  owner VARCHAR(128) NOT NULL DEFAULT '',
                  locked_until DATETIME NOT NULL DEFAULT '1970-01-01 00:00:00',
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        } catch (Exception e) {
            throw new IllegalStateException("初始化临时测试表失败", e);
        }

        SqlSessionFactory rawFactory = build(ds, new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory());
        SqlSessionFactory springFactory = build(ds, new SpringManagedTransactionFactory());
        return new MysqlIntegrationSupport(schema, ds, rawFactory, springFactory);
    }

    private static SqlSessionFactory build(DataSource ds, org.apache.ibatis.transaction.TransactionFactory transactionFactory) {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("mysql-it", transactionFactory, ds));
        configuration.addMapper(UserOrderMapper.class);
        configuration.addMapper(RefundRecordMapper.class);
        configuration.addMapper(MerchantMapper.class);
        configuration.addMapper(PaymentExceptionMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    void insertPaidOrder(long id, long userId, String status, String payStatus, int amountCents, String outTradeNo, String transactionId) {
        jdbc.update("""
            INSERT INTO user_order(id,user_id,status,pay_status,pay_amount_cents,out_trade_no,transaction_id)
            VALUES(?,?,?,?,?,?,?)
            """, id, userId, status, payStatus, amountCents, outTradeNo, transactionId);
    }

    void insertException(long id, long orderId, String outTradeNo, String transactionId, int amountCents) {
        jdbc.update("""
            INSERT INTO payment_exception(id,order_id,out_trade_no,transaction_id,amount_cents,kind,status)
            VALUES(?,?,?,?,?,'PAID_AFTER_CLOSE','open')
            """, id, orderId, outTradeNo, transactionId, amountCents);
    }

    @Override
    public void close() {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        } catch (Exception ignored) {
            // 清理失败不掩盖测试结果；数据库名带随机后缀不会影响业务库
        }
    }
}
