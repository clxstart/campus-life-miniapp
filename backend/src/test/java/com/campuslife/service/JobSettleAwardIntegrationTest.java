package com.campuslife.service;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.campuslife.mapper.AdminMapper;
import com.campuslife.mapper.EarningsLedgerMapper;
import com.campuslife.mapper.JobContractMapper;
import com.campuslife.mapper.JobDisputeMapper;
import com.campuslife.mapper.JobMapper;
import com.campuslife.mapper.PointsMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 兼职结算发分（F10 交易任务埋点）的真实 MySQL 集成测试。
 *
 * 对应设计稿 `docs/v3.1/交易任务埋点-功能说明与测试手册.md` 第六节用例 5-6：
 *   5 兼职结算发分（合约走完 settled，写 earnings_ledger，承接方 +5P/+15G）
 *   6 兼职争议不发（合约进 disputed 不发分，争议裁定 settled 才发，整单退回 refunded 不发）
 *
 * 手册原本把这两条标成「⚠️ 未自动化：挂在 JobService.settleContract() 的提交后回调，
 * 且要同时写 earnings_ledger、刷岗位工作流，手工装配成本高」。本测试把那张对象图搭出来：
 * 真库临时 schema + DataSourceTransactionManager + 真实 AfterCommitPointsAwarder，
 * 因此能断言「提交后才发 / 争议与退回不发 / contract:{id} 幂等」这些手工很难说清的事。
 *
 * 装配要点：
 *   - JobService 直接 new（@RequiredArgsConstructor 12 个依赖）。结算/裁定链路只用到
 *     jobs / contracts / disputes / ledger / awarder，以及 arbitrate 里的 adminAuth（写审计）；
 *     applications / verification / users / auditService / tierService 传 null。
 *     JobMapper / JobContractMapper / JobDisputeMapper 继承 MyBatis-Plus BaseMapper，
 *     必须用 MybatisConfiguration + MybatisSqlSessionFactoryBuilder 才会注入 selectById / insert。
 *   - 没有 Spring 代理 → @Transactional 不生效；事务边界由用例里的 TransactionTemplate 控制，
 *     这让我们能在事务未提交时读余额，验证「提交后才发」。
 *   - AdminAuthService 用真实对象 + AdminMapper（只写 admin_log），AdminJwtUtil 传 null。
 *
 * 不碰 campus_life：{@link MysqlIntegrationSupport} 建临时 schema（campus_life_it_&lt;随机&gt;），
 * 结束即 DROP。未设置 MYSQL_TEST_URL / MYSQL_TEST_USER / MYSQL_TEST_PASSWORD 时整组跳过
 * （跳过即「未验证」，交付报告必须写明）。
 */
class JobSettleAwardIntegrationTest {

    private static final long WORKER = 991001L;
    private static final long EMPLOYER = 991002L;
    private static final long ADMIN_ID = 1L;
    private static final String TASK_JOB = "job_settle";
    private static final String TASK_MODULE = "jobs";

    private static MysqlIntegrationSupport db;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static JobService jobService;
    private static AfterCommitPointsAwarder awarder;
    private static PointsService points;

    @BeforeAll
    static void setUp() {
        db = MysqlIntegrationSupport.create();
        jdbc = db.jdbc;
        for (String ddl : TABLES) jdbc.execute(ddl);
        // task_catalog 的 job_settle 行照 module-points-v31.sql：jobs / 5P / 15G / 日限 2。
        jdbc.update("INSERT INTO task_catalog(task_key,module,name,p_reward,g_reward,daily_cap,enabled,sort) VALUES(?,?,?,?,?,?,?,?)",
            TASK_JOB, TASK_MODULE, "兼职结算", 5, 15, 2, 1, 50);

        // 三个 Mapper 继承 MyBatis-Plus BaseMapper：必须走 MybatisConfiguration 才会注入
        // selectById / insert；id 策略对齐 application.yml（AUTO）。
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.getDbConfig(configuration).setIdType(IdType.AUTO);
        configuration.setEnvironment(new Environment(
            "job-settle-it", new SpringManagedTransactionFactory(), db.dataSource));
        configuration.addMapper(JobMapper.class);
        configuration.addMapper(JobContractMapper.class);
        configuration.addMapper(JobDisputeMapper.class);
        configuration.addMapper(EarningsLedgerMapper.class);
        configuration.addMapper(PointsMapper.class);
        configuration.addMapper(AdminMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        points = new PointsService(template.getMapper(PointsMapper.class));
        awarder = new AfterCommitPointsAwarder(points, new DataSourceTransactionManager(db.dataSource));
        FeatureFlags flags = new FeatureFlags(null) {
            @Override
            public boolean enabledStrict(String key) {
                return false;
            }

            @Override
            public boolean isEnabled(String key) {
                return true;
            }
        };
        AdminAuthService adminAuth =
            new AdminAuthService(template.getMapper(AdminMapper.class), null);
        jobService = new JobService(
            template.getMapper(JobMapper.class),
            null,                                   // JobApplicationMapper：结算/裁定链路不用
            template.getMapper(JobContractMapper.class),
            template.getMapper(JobDisputeMapper.class),
            template.getMapper(EarningsLedgerMapper.class),
            flags,
            null,                                   // VerificationService：结算/裁定链路不用
            null,                                   // UserMapper：结算/裁定链路不用
            null,                                   // AuditService：结算/裁定链路不用
            null,                                   // TierService：结算/裁定链路不用
            adminAuth,
            awarder);
        tx = db.transactionTemplate;
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    /** 每个用例前清账：承接方余额从 100 起，便于区分「旧值 → 新值」。 */
    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM job_contract");
        jdbc.update("DELETE FROM job_dispute");
        jdbc.update("DELETE FROM job");
        jdbc.update("DELETE FROM earnings_ledger");
        jdbc.update("DELETE FROM admin_log");
        jdbc.update("DELETE FROM point_log");
        jdbc.update("DELETE FROM task_log");
        jdbc.update("DELETE FROM user_points");
        jdbc.update("DELETE FROM user_growth");
        jdbc.update("DELETE FROM `user`");
        jdbc.update("INSERT INTO `user`(id,nickname) VALUES(?,?)", WORKER, "承接同学");
        jdbc.update("INSERT INTO `user`(id,nickname) VALUES(?,?)", EMPLOYER, "发布同学");
        jdbc.update("INSERT INTO user_points(user_id,balance,total_earned) VALUES(?,100,100)", WORKER);
        points.evictCatalog();
    }

    // ==================== 用例 5：结算发分 + 提交后才发 + 幂等 ====================

    @Test
    void settledContractAwardsWorkerOnce() {
        seedJob(8001L, "working");
        // 发布方已确认 3 个计价单位，承接方再确认一次即结算（状态停在 pending_confirm 等双方确认齐）。
        seedContract(6001L, 8001L, "pending_confirm", null, "3.00");

        // 「提交后才发」：confirmHours() 在事务里执行完、execute 还没返回（事务未提交）时，
        // afterCommit 回调不能提前跑 —— 余额仍是旧的 100，point_log 仍是空的。
        int[] duringTransaction = new int[2];
        tx.execute(status -> {
            jobService.confirmHours(6001L, WORKER, "3");
            duringTransaction[0] = balance();
            duringTransaction[1] = pointLogs();
            return null;
        });
        assertThat(duringTransaction[0]).as("事务提交前余额必须还是旧值").isEqualTo(100);
        assertThat(duringTransaction[1]).as("事务提交前不应有积分流水").isZero();

        // execute 返回（已提交、afterCommit 已执行）：承接方 +5P / +15G，流水写明来由与模块。
        assertThat(balance()).isEqualTo(105);
        assertThat(growth()).isEqualTo(15);
        assertThat(pointLogs()).isEqualTo(1);
        Map<String, Object> log = jdbc.queryForMap("SELECT * FROM point_log WHERE user_id=?", WORKER);
        assertThat(((Number) log.get("amount")).intValue()).isEqualTo(5);
        assertThat(((Number) log.get("g_amount")).intValue()).isEqualTo(15);
        assertThat(((Number) log.get("balance_after")).intValue()).isEqualTo(105);
        assertThat(log.get("remark")).isEqualTo("兼职结算");
        assertThat(log.get("module")).isEqualTo(TASK_MODULE);
        assertThat(log.get("biz_key")).isEqualTo("job_settle:contract:6001");

        // 履约侧事实：合约 settled、工时一致、岗位状态收敛、账单三行落 settled。
        assertThat(contractStatus(6001L)).isEqualTo("settled");
        assertThat(agreedHours(6001L)).isEqualByComparingTo("3.00");
        assertThat(jobWorkflow(8001L)).isEqualTo("settled");
        assertThat(ledgerStatuses(6001L)).containsExactly("settled", "settled", "settled");
        assertThat(ledgerAmount(6001L, "job:6001:payable")).isEqualTo(7500);   // 2500 分 × 3
        assertThat(ledgerAmount(6001L, "job:6001:fee")).isZero();               // 费率 none
        assertThat(ledgerAmount(6001L, "job:6001:income")).isEqualTo(7500);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM task_log WHERE user_id=? AND task_key=? AND biz_key='contract:6001'",
            Integer.class, WORKER, TASK_JOB)).isEqualTo(1);
        // 详情页「结算完成，+5 已到账」的判据只认 point_log 流水。
        assertThat(pointsAwarded(6001L)).isEqualTo(5);

        // 幂等：合约已 settled，重复确认工时走状态机幂等（不抛错、不重复记账），
        // 即使直接重复调用发分桥，contract:6001 的唯一键也只放行一次。
        jobService.confirmHours(6001L, WORKER, "3");
        assertThat(awarder.award(WORKER, TASK_JOB, "contract:6001", "兼职结算"))
            .isEqualTo(PointsService.AwardResult.ALREADY);
        assertThat(balance()).isEqualTo(105);
        assertThat(growth()).isEqualTo(15);
        assertThat(pointLogs()).isEqualTo(1);
        assertThat(ledgerStatuses(6001L)).containsExactly("settled", "settled", "settled");
    }

    // ==================== 用例 6（前半）：争议冻结 / 整单退回都不发 ====================

    @Test
    void rejectedOrRefundedContractNeverAwards() {
        seedJob(8002L, "pending_confirm");
        seedContract(6101L, 8002L, "pending_confirm", "2.00", "4.00");

        // 双方工时不一致 → 任一方发起争议：合约进 disputed、账单冻结，但绝不发分。
        jobService.dispute(6101L, WORKER, "承接方报 2，发布方报 4");
        assertThat(contractStatus(6101L)).isEqualTo("disputed");
        assertThat(ledgerStatuses(6101L)).containsExactly("frozen", "frozen", "frozen");
        assertThat(balance()).isEqualTo(100);
        assertThat(growth()).isZero();
        assertThat(pointLogs()).isZero();
        assertThat(taskLogs()).isZero();
        assertThat(pointsAwarded(6101L)).isZero();

        // 管理端裁定「整单退回」：账单冻结 → refunded，依然不发分。
        jobService.arbitrate(6101L, "refunded", null, "核实未履约，整单退回",
            ADMIN_ID, "测试管理员", null, "127.0.0.1");
        assertThat(contractStatus(6101L)).isEqualTo("refunded");
        assertThat(ledgerStatuses(6101L)).containsExactly("refunded", "refunded", "refunded");
        assertThat(balance()).isEqualTo(100);
        assertThat(growth()).isZero();
        assertThat(pointLogs()).isZero();
        assertThat(taskLogs()).isZero();
        assertThat(pointsAwarded(6101L)).isZero();
        assertThat(disputeStatus(6101L)).isEqualTo("resolved");
        assertThat(disputeDecision(6101L)).isEqualTo("refunded");

        // 重复裁定同一结果：幂等返回现状，仍然不发分。
        jobService.arbitrate(6101L, "refunded", null, "重复提交",
            ADMIN_ID, "测试管理员", null, "127.0.0.1");
        assertThat(balance()).isEqualTo(100);
        assertThat(pointLogs()).isZero();
    }

    // ==================== 用例 6（后半）：裁定 settled 才发，refunded 不发 ====================

    @Test
    void arbitrationSettledAwardsRefundedDoesNot() {
        // 合约 A：争议 → 整单退回，不发分。
        seedJob(8003L, "pending_confirm");
        seedContract(6201L, 8003L, "pending_confirm", "2.00", "4.00");
        jobService.dispute(6201L, WORKER, "工时不一致");
        jobService.arbitrate(6201L, "refunded", null, "整单退回",
            ADMIN_ID, "测试管理员", null, "127.0.0.1");
        assertThat(contractStatus(6201L)).isEqualTo("refunded");
        assertThat(balance()).isEqualTo(100);
        assertThat(pointLogs()).isZero();

        // 合约 B：争议 → 裁定按 3 个计价单位结算，才发 +5P/+15G；同样验证提交后才发。
        seedJob(8004L, "pending_confirm");
        seedContract(6202L, 8004L, "pending_confirm", "3.00", "4.00");
        jobService.dispute(6202L, WORKER, "工时不一致");
        assertThat(pointLogs()).isZero();

        int[] duringTransaction = new int[2];
        tx.execute(status -> {
            jobService.arbitrate(6202L, "settled", "3", "按 3 个计价单位结算",
                ADMIN_ID, "测试管理员", null, "127.0.0.1");
            duringTransaction[0] = balance();
            duringTransaction[1] = pointLogs();
            return null;
        });
        assertThat(duringTransaction[0]).as("裁定事务提交前余额必须还是旧值").isEqualTo(100);
        assertThat(duringTransaction[1]).as("裁定事务提交前不应有积分流水").isZero();

        assertThat(balance()).isEqualTo(105);
        assertThat(growth()).isEqualTo(15);
        assertThat(pointLogs()).isEqualTo(1);
        Map<String, Object> log = jdbc.queryForMap("SELECT * FROM point_log WHERE user_id=?", WORKER);
        assertThat(((Number) log.get("amount")).intValue()).isEqualTo(5);
        assertThat(((Number) log.get("g_amount")).intValue()).isEqualTo(15);
        assertThat(log.get("remark")).isEqualTo("兼职结算");
        assertThat(log.get("module")).isEqualTo(TASK_MODULE);
        assertThat(log.get("biz_key")).isEqualTo("job_settle:contract:6202");
        assertThat(contractStatus(6202L)).isEqualTo("settled");
        assertThat(agreedHours(6202L)).isEqualByComparingTo("3.00");
        assertThat(ledgerStatuses(6202L)).containsExactly("settled", "settled", "settled");
        assertThat(ledgerAmount(6202L, "job:6202:payable")).isEqualTo(7500);
        assertThat(disputeStatus(6202L)).isEqualTo("resolved");
        assertThat(disputeDecision(6202L)).isEqualTo("settled");
        assertThat(pointsAwarded(6202L)).isEqualTo(5);

        // 幂等：重复裁定同一结果（同一计价数量）不重复发分；发分桥同 bizKey 返回 ALREADY。
        jobService.arbitrate(6202L, "settled", "3", "重复裁定",
            ADMIN_ID, "测试管理员", null, "127.0.0.1");
        assertThat(awarder.award(WORKER, TASK_JOB, "contract:6202", "兼职结算"))
            .isEqualTo(PointsService.AwardResult.ALREADY);
        assertThat(balance()).isEqualTo(105);
        assertThat(pointLogs()).isEqualTo(1);
        assertThat(growth()).isEqualTo(15);
    }

    // ==================== 回归：争议裁定后账单按「裁定量」重算，不是申报量 ====================

    /**
     * 锁住 writeLedger() 的 reprice：争议冻结时 record() 已按承接方申报量写死金额
     * （INSERT IGNORE 之后不会被覆盖），裁定 settled/refunded 后必须把三行金额重算到裁定量。
     * 申报 5、裁定 3 —— 两个数字必须不同，否则测不出「按 3 而不是按 5」。
     */
    @Test
    void arbitrationSettledRepricesLedgerToConfirmedHours() {
        // 承接方申报 5 个计价单位（发布方报 6）：先争议冻结，账单按 5 写。
        seedJob(8005L, "pending_confirm");
        seedContractWithFee(6301L, 8005L, "pending_confirm", "5.00", "6.00", "amount", 100);
        jobService.dispute(6301L, WORKER, "承接方报 5，发布方报 6");

        assertThat(contractStatus(6301L)).isEqualTo("disputed");
        assertThat(ledgerStatuses(6301L)).containsExactly("frozen", "frozen", "frozen");
        // 冻结金额按承接方申报的 5 个：2500×5=12500；费率 amount 100 分/单位 → 500；收入 12000。
        assertThat(ledgerAmount(6301L, "job:6301:payable")).isEqualTo(12500);
        assertThat(ledgerAmount(6301L, "job:6301:fee")).isEqualTo(500);
        assertThat(ledgerAmount(6301L, "job:6301:income")).isEqualTo(12000);

        // 管理员确认 3 个（≠ 申报的 5）：状态 settled，金额必须按 3 重算，而不是保持 12500。
        jobService.arbitrate(6301L, "settled", "3", "按 3 个计价单位结算",
            ADMIN_ID, "测试管理员", null, "127.0.0.1");

        assertThat(contractStatus(6301L)).isEqualTo("settled");
        assertThat(ledgerStatuses(6301L)).containsExactly("settled", "settled", "settled");
        int payable = ledgerAmount(6301L, "job:6301:payable");
        int fee = ledgerAmount(6301L, "job:6301:fee");
        int income = ledgerAmount(6301L, "job:6301:income");
        assertThat(payable).as("payable 按裁定 3 个重算：2500×3").isEqualTo(7500);
        assertThat(fee).as("fee 按费率档重算：amount 100×3").isEqualTo(300);
        assertThat(income).as("income = payable - fee").isEqualTo(7200);
        // 明确排除「保持申报 5 个的旧值」这一实现。
        assertThat(payable).isNotEqualTo(12500);
        assertThat(fee).isNotEqualTo(500);
        assertThat(income).isNotEqualTo(12000);

        // 重复裁定同一数量：幂等，金额不再变。
        jobService.arbitrate(6301L, "settled", "3", "重复裁定",
            ADMIN_ID, "测试管理员", null, "127.0.0.1");
        assertThat(ledgerAmount(6301L, "job:6301:payable")).isEqualTo(7500);
        assertThat(ledgerAmount(6301L, "job:6301:fee")).isEqualTo(300);
        assertThat(ledgerAmount(6301L, "job:6301:income")).isEqualTo(7200);

        // 另一半：整单退回时三行金额被重算为 0，而不是保持申报量。
        seedJob(8006L, "pending_confirm");
        seedContractWithFee(6302L, 8006L, "pending_confirm", "5.00", "6.00", "amount", 100);
        jobService.dispute(6302L, WORKER, "承接方报 5，发布方报 6");
        assertThat(ledgerAmount(6302L, "job:6302:payable")).isEqualTo(12500);

        jobService.arbitrate(6302L, "refunded", null, "整单退回",
            ADMIN_ID, "测试管理员", null, "127.0.0.1");
        assertThat(contractStatus(6302L)).isEqualTo("refunded");
        assertThat(ledgerStatuses(6302L)).containsExactly("refunded", "refunded", "refunded");
        assertThat(ledgerAmount(6302L, "job:6302:payable")).isZero();
        assertThat(ledgerAmount(6302L, "job:6302:fee")).isZero();
        assertThat(ledgerAmount(6302L, "job:6302:income")).isZero();

        // 顺带复核上一轮结论：settled 的 6301 发一次分（+5），refunded 的 6302 不发。
        assertThat(balance()).isEqualTo(105);
        assertThat(pointLogs()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT biz_key FROM point_log WHERE user_id=?", String.class, WORKER))
            .isEqualTo("job_settle:contract:6301");
    }

    // ==================== 工具 ====================

    private static void seedJob(long id, String workflowStatus) {
        jdbc.update("""
            INSERT INTO job(id,title,day_salary,status,user_id,description,school_id,audit_status,scope,
                            employer_kind,take_mode,category_key,pay_cents,pay_unit,fee_mode,fee_value,
                            headcount,workflow_status,accepted_count)
            VALUES(?,?,0,'招聘中',?,?,1,'passed','campus','student','first_come','other',
                   2500,'hour','none',0,1,?,1)
            """, id, "测试兼职-" + id, EMPLOYER, "结算链路测试岗位", workflowStatus);
    }

    private static void seedContract(long id, long jobId, String status,
                                     String workerHours, String employerHours) {
        seedContractWithFee(id, jobId, status, workerHours, employerHours, "none", 0);
    }

    private static void seedContractWithFee(long id, long jobId, String status,
                                            String workerHours, String employerHours,
                                            String feeMode, int feeValue) {
        jdbc.update("""
            INSERT INTO job_contract(id,job_id,worker_id,employer_id,status,fee_mode_snapshot,
                                     fee_value_snapshot,pay_cents_snapshot,pay_unit_snapshot,
                                     worker_hours,employer_hours,agreed_hours)
            VALUES(?,?,?,?,?,?,?,2500,'hour',?,?,NULL)
            """, id, jobId, WORKER, EMPLOYER, status, feeMode, feeValue, workerHours, employerHours);
    }

    private static String contractStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM job_contract WHERE id=?", String.class, id);
    }

    private static BigDecimal agreedHours(long id) {
        return jdbc.queryForObject("SELECT agreed_hours FROM job_contract WHERE id=?", BigDecimal.class, id);
    }

    private static String jobWorkflow(long id) {
        return jdbc.queryForObject("SELECT workflow_status FROM job WHERE id=?", String.class, id);
    }

    private static List<String> ledgerStatuses(long contractId) {
        return jdbc.queryForList(
            "SELECT status FROM earnings_ledger WHERE biz_type='job' AND ref_id=? ORDER BY id",
            String.class, contractId);
    }

    private static int ledgerAmount(long contractId, String bizNo) {
        return jdbc.queryForObject(
            "SELECT amount_cents FROM earnings_ledger WHERE biz_type='job' AND ref_id=? AND biz_no=?",
            Integer.class, contractId, bizNo);
    }

    private static String disputeStatus(long contractId) {
        return jdbc.queryForObject(
            "SELECT status FROM job_dispute WHERE contract_id=? ORDER BY id DESC LIMIT 1",
            String.class, contractId);
    }

    private static String disputeDecision(long contractId) {
        return jdbc.queryForObject(
            "SELECT decision FROM job_dispute WHERE contract_id=? ORDER BY id DESC LIMIT 1",
            String.class, contractId);
    }

    private static int pointsAwarded(long contractId) {
        Object value = jobService.contractDetail(contractId, WORKER).get("pointsAwarded");
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static int balance() {
        List<Integer> rows = jdbc.queryForList(
            "SELECT balance FROM user_points WHERE user_id=?", Integer.class, WORKER);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private static int growth() {
        List<Integer> rows = jdbc.queryForList(
            "SELECT g_total FROM user_growth WHERE user_id=?", Integer.class, WORKER);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private static int pointLogs() {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM point_log", Integer.class);
        return rows == null ? 0 : rows;
    }

    private static int taskLogs() {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM task_log", Integer.class);
        return rows == null ? 0 : rows;
    }

    // ==================== 建表（最小集，列对齐实体/Mapper 的 SELECT *） ====================

    private static final String[] TABLES = {
        """
        CREATE TABLE `user` (
          id BIGINT PRIMARY KEY,
          nickname VARCHAR(64) NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE job (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          title VARCHAR(64) NOT NULL,
          day_salary INT NOT NULL DEFAULT 0,
          settle VARCHAR(8) DEFAULT '日结',
          location VARCHAR(64) NULL,
          time_desc VARCHAR(64) NULL,
          status VARCHAR(8) DEFAULT '招聘中',
          user_id BIGINT NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          description TEXT NULL,
          school_id BIGINT NULL,
          audit_status VARCHAR(16) NOT NULL DEFAULT 'pending',
          audit_reason VARCHAR(200) NULL,
          audit_at DATETIME NULL,
          scope VARCHAR(16) NOT NULL DEFAULT 'campus',
          employer_kind VARCHAR(16) NOT NULL DEFAULT 'student',
          partner_id BIGINT NULL,
          take_mode VARCHAR(16) NOT NULL DEFAULT 'first_come',
          category_key VARCHAR(32) NOT NULL DEFAULT 'other',
          pay_cents INT NOT NULL DEFAULT 0,
          pay_unit VARCHAR(16) NOT NULL DEFAULT 'day',
          fee_mode VARCHAR(16) NOT NULL DEFAULT 'none',
          fee_value INT NOT NULL DEFAULT 0,
          headcount INT NOT NULL DEFAULT 1,
          deadline_at DATETIME NULL,
          workflow_status VARCHAR(24) NOT NULL DEFAULT 'open',
          taken_by BIGINT NULL,
          accepted_count INT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE job_contract (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          job_id BIGINT NOT NULL,
          worker_id BIGINT NOT NULL,
          employer_id BIGINT NOT NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'created',
          fee_mode_snapshot VARCHAR(16) NOT NULL DEFAULT 'none',
          fee_value_snapshot INT NOT NULL DEFAULT 0,
          pay_cents_snapshot INT NOT NULL DEFAULT 0,
          pay_unit_snapshot VARCHAR(16) NOT NULL DEFAULT 'day',
          worker_hours DECIMAL(8,2) NULL,
          employer_hours DECIMAL(8,2) NULL,
          agreed_hours DECIMAL(8,2) NULL,
          cancel_reason VARCHAR(200) NOT NULL DEFAULT '',
          started_at DATETIME NULL,
          submitted_at DATETIME NULL,
          both_confirmed_at DATETIME NULL,
          settled_at DATETIME NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
          UNIQUE KEY uk_job_worker(job_id, worker_id),
          KEY idx_worker(worker_id, status),
          KEY idx_employer(employer_id, status)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE job_dispute (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          contract_id BIGINT NOT NULL,
          raised_by BIGINT NOT NULL,
          reason VARCHAR(200) NOT NULL DEFAULT '',
          status VARCHAR(16) NOT NULL DEFAULT 'open',
          decided_by BIGINT NULL,
          decision VARCHAR(16) NULL,
          decided_at DATETIME NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          KEY idx_contract(contract_id),
          KEY idx_status(status)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE earnings_ledger (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          biz_no VARCHAR(80) NOT NULL,
          biz_type VARCHAR(16) NOT NULL,
          ref_id BIGINT NOT NULL,
          user_id BIGINT DEFAULT NULL,
          entry_type VARCHAR(24) NOT NULL,
          amount_cents INT NOT NULL,
          status VARCHAR(16) NOT NULL DEFAULT 'recorded',
          note VARCHAR(200) NOT NULL DEFAULT '',
          operator_id BIGINT DEFAULT NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_ledger_biz_no (biz_no),
          KEY idx_ledger_ref (biz_type, ref_id),
          KEY idx_ledger_user (user_id, created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE admin_log (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          admin_id BIGINT NOT NULL,
          admin_name VARCHAR(32) NOT NULL DEFAULT '',
          action VARCHAR(32) NOT NULL,
          target_type VARCHAR(32) NOT NULL DEFAULT '',
          target_id VARCHAR(64) NOT NULL DEFAULT '',
          detail VARCHAR(500) NOT NULL DEFAULT '',
          ip VARCHAR(64) NOT NULL DEFAULT '',
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          KEY idx_log_admin (admin_id, created_at),
          KEY idx_log_created (created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE user_points (
          user_id BIGINT PRIMARY KEY,
          balance INT NOT NULL DEFAULT 0,
          total_earned INT NOT NULL DEFAULT 0,
          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE point_log (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          amount INT NOT NULL,
          type VARCHAR(32) NOT NULL,
          ref_id BIGINT NULL,
          remark VARCHAR(100) NULL,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          module VARCHAR(16) NOT NULL DEFAULT 'jobs',
          biz_key VARCHAR(64) NULL,
          g_amount INT NOT NULL DEFAULT 0,
          balance_after INT NULL,
          KEY idx_user_time (user_id, created_at),
          UNIQUE KEY uk_user_biz (user_id, biz_key)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE user_growth (
          user_id BIGINT PRIMARY KEY,
          g_total INT NOT NULL DEFAULT 0,
          updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE task_catalog (
          task_key VARCHAR(32) PRIMARY KEY,
          module VARCHAR(16) NOT NULL,
          name VARCHAR(32) NOT NULL,
          p_reward INT NOT NULL DEFAULT 0,
          g_reward INT NOT NULL DEFAULT 0,
          daily_cap INT NOT NULL DEFAULT 1,
          enabled TINYINT NOT NULL DEFAULT 1,
          sort INT NOT NULL DEFAULT 0
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE task_log (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          user_id BIGINT NOT NULL,
          task_key VARCHAR(32) NOT NULL,
          biz_date DATE NOT NULL,
          biz_key VARCHAR(64) NOT NULL,
          p INT NOT NULL DEFAULT 0,
          g INT NOT NULL DEFAULT 0,
          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE KEY uk_task_idem (user_id, task_key, biz_key),
          KEY idx_task_day (user_id, task_key, biz_date)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """
    };
}