package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.entity.Job;
import com.campuslife.entity.JobApplication;
import com.campuslife.entity.JobContract;
import com.campuslife.entity.JobDispute;
import com.campuslife.entity.User;
import com.campuslife.mapper.EarningsLedgerMapper;
import com.campuslife.mapper.JobApplicationMapper;
import com.campuslife.mapper.JobContractMapper;
import com.campuslife.mapper.JobDisputeMapper;
import com.campuslife.mapper.JobMapper;
import com.campuslife.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 兼职岗位域（§11）。
 *
 * 三条贯穿全文的规则：
 *   1. **闸门在服务端**。校外岗位、替课类目、有偿接单三件事都由 FeatureFlags.enabledStrict
 *      在这里判定 —— 默认关闭，前端藏按钮只是体验，绕过前端照样 403。
 *   2. **并发靠条件 UPDATE**。谁能接、谁能结算，全由「WHERE 当前状态」命中的影响行数决定，
 *      不做先查后写（先查后写在两个请求之间就有一个窗口）。
 *   3. **钱只进 earnings_ledger（履约账单）**。这里没有钱包、没有余额、没有提现；
 *      金额一律人民币分，费率取合约快照。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    /** 校外岗位：没资质就不给发、不给报。默认关闭。 */
    public static final String FLAG_OFFSITE = "job.offsite.enabled";
    /** 替课类目：默认关闭。 */
    public static final String FLAG_TAKEOVER = "job.category.takeover_class.enabled";
    /** 有偿接单总闸门（人已核验 + 平台开放，两个条件都要）。 */
    public static final String FLAG_PAID = VerificationService.FLAG_PAID_JOB;

    /** F10 交易埋点：兼职结算发分的任务键（5P/15G、日限 2 单，见 db/module-points-v31.sql）。 */
    private static final String TASK_JOB_SETTLE = "job_settle";

    private static final Set<String> SCOPES = Set.of("campus", "offsite");
    private static final Set<String> TAKE_MODES = Set.of("first_come", "apply");
    private static final Set<String> PAY_UNITS = Set.of("hour", "order", "shift", "day", "custom");
    private static final Set<String> CATEGORIES = Set.of(
        "other", "takeover_class", "tutor", "promotion", "service", "delivery");
    private static final Set<String> EMPLOYER_KINDS = Set.of("student", "partner");
    private static final Set<String> FEE_MODES = Set.of("none", "amount", "rate");
    /** 履约单状态（与 job_contract.status 的取值保持一致）。 */
    private static final Set<String> CONTRACT_STATUSES = Set.of(
        "created", "working", "pending_confirm", "settled", "disputed", "cancelled", "refunded");

    /**
     * 岗位状态机白名单。**任何** workflow_status 变更都要先过这里。
     *
     *   open ──> taken（校内被接）| recruiting（校外开放报名）| cancelled
     *   recruiting ──> working | cancelled
     *   taken ──> working | cancelled | disputed
     *   working ──> pending_confirm | cancelled | disputed
     *   pending_confirm ──> settled | disputed | cancelled
     *   disputed ──> settled | refunded | cancelled
     *   settled / refunded / cancelled 是终态
     */
    private static final Map<String, Set<String>> JOB_FLOW = Map.of(
        "open", Set.of("taken", "recruiting", "cancelled"),
        "recruiting", Set.of("working", "cancelled"),
        "taken", Set.of("working", "cancelled", "disputed"),
        "working", Set.of("pending_confirm", "cancelled", "disputed"),
        "pending_confirm", Set.of("settled", "disputed", "cancelled"),
        "disputed", Set.of("settled", "refunded", "cancelled"),
        "settled", Set.of(),
        "refunded", Set.of(),
        "cancelled", Set.of());

    private final JobMapper jobs;
    private final JobApplicationMapper applications;
    private final JobContractMapper contracts;
    private final JobDisputeMapper disputes;
    private final EarningsLedgerMapper ledger;
    private final FeatureFlags flags;
    private final VerificationService verification;
    private final UserMapper users;
    private final AuditService auditService;
    /** 段位特权：白银及以上的「快速应答」在列表第 1 页置顶最多 2 条同校岗位。 */
    private final TierService tierService;
    /** 管理端操作留痕（争议裁定/删单/改费率必须写）。 */
    private final AdminAuthService adminAuth;
    /** F10 交易埋点：兼职结算发分（事务提交后、独立事务，失败只记日志，不影响结算）。 */
    private final AfterCommitPointsAwarder awarder;

    // ==================================================================
    // 用户端：列表 / 详情 / 发布
    // ==================================================================

    /**
     * 兼职列表。传 page 返回分页对象，不传返回扁平数组（小程序老调用方不受影响）。
     * 只列 audit_status=passed 且仍在招聘（open / recruiting）的岗位。
     */
    public Object list(String settle, String keyword, String schoolScope, Long explicitSchoolId,
                       Long userId, String scope, Integer page, int size) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.length() > 64) throw new IllegalArgumentException("搜索词不能超过64字");
        String settleValue = settle == null ? "" : settle.trim();
        String like = kw.isEmpty() ? "" : "%" + escapeLike(kw) + "%";
        String scopeValue = normalizeScopeFilter(scope);
        Long schoolId = resolveSchoolId(schoolScope, explicitSchoolId, userId);

        if (page == null) {
            return jobs.list(settleValue, like, schoolId, scopeValue, 200, 0);
        }
        int safeSize = Math.min(50, Math.max(1, size));
        int safePage = Math.max(1, page);
        List<Map<String, Object>> rows = jobs.list(settleValue, like, schoolId, scopeValue,
            safeSize, (safePage - 1) * safeSize);
        if (safePage == 1) injectPriority(rows, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", rows);
        out.put("total", jobs.count(settleValue, like, schoolId, scopeValue));
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    /**
     * 快速应答（白银特权）：只在第 1 页、最多 2 条、同校优先。
     *
     * 为什么是"把已有行提到前面"而不是额外查 2 条插进来：
     *   插进来会导致第 2 页出现重复岗位、总数对不上。
     *   这里只调整第 1 页内的顺序并打 priority 标记，翻页结果与原来完全一致。
     * 同校岗位不足 2 条时有几条标几条，不凑数。
     */
    private void injectPriority(List<Map<String, Object>> rows, Long userId) {
        // 只有 1 条时也要标 priority：设计稿的口径是「岗位总数 ≤2 不显示分隔文案」，
        // 分隔文案是前端的事，后端该给的标记不能因为页面短就不给。
        if (rows == null || rows.isEmpty() || userId == null || userId < 1) return;
        if (!Boolean.TRUE.equals(tierService.privileges(userId).get("priorityReply"))) return;
        User viewer = users.selectById(userId);
        if (viewer == null || viewer.getSchoolId() == null) return;
        Long mySchool = viewer.getSchoolId();

        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < rows.size() && indexes.size() < 2; i++) {
            Map<String, Object> row = rows.get(i);
            if (mySchool.equals(num(row.get("schoolId")))) indexes.add(i);
        }
        if (indexes.isEmpty()) return;

        List<Map<String, Object>> picked = new ArrayList<>();
        for (int idx : indexes) {
            Map<String, Object> row = rows.get(idx);
            row.put("priority", true);
            picked.add(row);
        }
        for (int i = indexes.size() - 1; i >= 0; i--) rows.remove((int) indexes.get(i));
        rows.addAll(0, picked);
    }

    /** 岗位详情：含我的申请、我的履约单；发布者还能看到报名列表。 */
    public Map<String, Object> detail(Long jobId, Long viewerId) {
        if (jobId == null || jobId < 1) throw new IllegalArgumentException("岗位参数无效");
        Map<String, Object> row = jobs.detail(jobId);
        if (row == null) throw new IllegalArgumentException("岗位不存在或已下架");
        Long ownerId = num(row.get("userId"));
        boolean passed = "passed".equals(str(row.get("auditStatus")));
        if (!passed && (viewerId == null || !viewerId.equals(ownerId))) {
            throw new IllegalArgumentException("岗位不存在或已下架");
        }
        // 只给当事人看自己的申请/合约状态，避免把别人的报名信息带出去
        if (viewerId != null) {
            row.put("myApplication", applications.findOne(jobId, viewerId));
            JobContract mine = contracts.findByJobAndWorker(jobId, viewerId);
            if (mine != null) {
                Map<String, Object> brief = new LinkedHashMap<>();
                brief.put("id", mine.getId());
                brief.put("status", mine.getStatus());
                brief.put("workerHours", mine.getWorkerHours());
                brief.put("employerHours", mine.getEmployerHours());
                brief.put("agreedHours", mine.getAgreedHours());
                // F10：结算实际到账积分（0 = 没发）。前端只在 settled 且 >0 时显示提示条。
                brief.put("pointsAwarded", settledPoints(mine.getId(), mine.getWorkerId()));
                row.put("myContract", brief);
            }
            if (viewerId.equals(ownerId)) row.put("applications", applications.byJob(jobId));
        }
        return row;
    }

    /** 我发布的 + 我承接的。 */
    public Map<String, Object> mine(Long userId) {
        if (userId == null || userId < 1) throw new IllegalArgumentException("用户参数无效");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("published", jobs.minePublished(userId));
        List<Map<String, Object>> working = jobs.mineWorking(userId);
        // F10：履约列表要能显示「结算完成，+5 积分已到账」。只对已结算的行补查一次实发积分
        // （积分发给承接方，也就是这里的本人），未结算的行恒定 0，不查。
        for (Map<String, Object> row : working) {
            if (!"settled".equals(str(row.get("contractStatus")))) continue;
            row.put("pointsAwarded", settledPoints(num(row.get("contractId")), userId));
        }
        out.put("working", working);
        // 发布方视角：录用后要能回来确认工时，否则结算流程走不完
        out.put("employer", jobs.mineEmployer(userId));
        return out;
    }

    /**
     * 岗位相关的公开开关状态（只读）。
     *
     * 前端需要知道"校外岗位到底开没开"才能如实地显示"暂未开放"，
     * 而不是靠第一次请求被 403 才反推。这里只暴露布尔值与原因，不含任何配置细节。
     */
    public Map<String, Object> gates() {
        boolean offsite = flags.enabledStrict(FLAG_OFFSITE);
        boolean paid = flags.enabledStrict(FLAG_PAID);
        boolean takeoverClass = flags.enabledStrict(FLAG_TAKEOVER);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("offsiteOpen", offsite);
        out.put("offsiteReason", offsite ? "" : "校外岗位资质未核验，暂未开放");
        out.put("paidOpen", paid);
        out.put("paidReason", paid ? "" : "有偿岗位暂未开放，需平台完成资质与核验流程");
        out.put("takeoverClassOpen", takeoverClass);
        out.put("takeoverClassReason", takeoverClass ? "" : "替课类目需校方规则确认，暂未开放");
        return out;
    }

    /**
     * 发布岗位。**校验链顺序很重要**（越靠前越"刚性"）：
     *   1. 校外岗位 → job.offsite.enabled
     *   2. 替课类目 → job.category.takeover_class.enabled
     *   3. 有偿（pay_cents>0）→ job.paid.enabled，再查"这个人核验过没有"
     *   4. 费率一律忽略请求值：写 none/0，等管理端逐岗位配置
     */
    @Transactional
    public Map<String, Object> publish(Long userId, Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("请填写岗位信息");
        User user = userId == null || userId < 1 ? null : users.selectById(userId);
        if (user == null) throw new IllegalArgumentException("用户不存在");

        String title = str(body.get("title")).trim();
        if (title.isEmpty() || title.length() > 64) throw new IllegalArgumentException("标题长度须为1至64字");
        String description = str(body.get("description")).trim();
        if (description.length() > 200) throw new IllegalArgumentException("描述不能超过200字");
        String settle = strOr(body.get("settle"), "日结");
        if (settle.length() > 8) throw new IllegalArgumentException("结算方式格式不正确");
        String location = strOr(body.get("location"), "").trim();
        if (location.length() > 64) throw new IllegalArgumentException("地点不能超过64字");
        String timeDesc = strOr(body.get("timeDesc"), "").trim();
        if (timeDesc.length() > 64) throw new IllegalArgumentException("时间说明不能超过64字");

        int daySalary = intOf(body.get("daySalary"), 0);
        if (daySalary < 0 || daySalary > 100000) throw new IllegalArgumentException("日薪须为0至100000");

        String scope = normalize(body.get("scope"), "campus", SCOPES, "岗位类型");
        String employerKind = normalize(body.get("employerKind"), "student", EMPLOYER_KINDS, "雇主类型");
        String takeMode = normalize(body.get("takeMode"),
            "offsite".equals(scope) ? "apply" : "first_come", TAKE_MODES, "接单方式");
        String categoryKey = normalize(body.get("categoryKey"), "other", CATEGORIES, "岗位类目");
        String payUnit = normalize(body.get("payUnit"), "day", PAY_UNITS, "计价单位");
        int headcount = intOf(body.get("headcount"), 1);
        if (headcount < 1 || headcount > 100) throw new IllegalArgumentException("招聘人数须为1至100");

        // 报酬：优先 payCents（分）；老前端只传 daySalary（元/天）时自动换成"分"
        int payCents = body.get("payCents") == null ? daySalary * 100 : intOf(body.get("payCents"), 0);
        if (payCents < 0) throw new IllegalArgumentException("报酬不能为负数");
        if (payCents > 100_000_000) throw new IllegalArgumentException("报酬超出允许范围");
        if (body.get("payCents") == null && payCents > 0) payUnit = "day";

        Long partnerId = num(body.get("partnerId"));
        LocalDateTime deadlineAt = parseTime(body.get("deadlineAt"));

        // ---- 校验链（顺序不可调） ----
        if ("offsite".equals(scope) && !flags.enabledStrict(FLAG_OFFSITE)) {
            throw new ForbiddenException("校外岗位资质未核验，暂未开放");
        }
        if ("takeover_class".equals(categoryKey) && !flags.enabledStrict(FLAG_TAKEOVER)) {
            throw new ForbiddenException("替课类岗位暂未开放");
        }
        if (payCents > 0) {
            if (!flags.enabledStrict(FLAG_PAID)) throw new ForbiddenException("有偿岗位暂未开放");
            verification.requireVerifiedForPaidJob(userId);
        }

        // 内容安全：与二手/活动共用同一套审计（敏感词 + 异步图片审核）
        var textAudit = auditService.checkText(title + "\n" + description, "job", userId);
        auditService.rejectWithLog(textAudit, "job", userId, title + "\n" + description);
        String auditStatus = auditService.initialStatus(textAudit, false);

        Job job = new Job();
        job.setTitle(title);
        job.setDaySalary(daySalary);
        job.setSettle(settle);
        job.setLocation(location);
        job.setTimeDesc(timeDesc);
        job.setDescription(description);
        job.setUserId(userId);
        job.setSchoolId(user.getSchoolId());
        job.setStatus("passed".equals(auditStatus) ? "招聘中" : "待审核");
        job.setAuditStatus(auditStatus);
        job.setAuditReason(textAudit.reason() == null ? "" : textAudit.reason());
        job.setAuditAt("passed".equals(auditStatus) ? LocalDateTime.now() : null);
        job.setScope(scope);
        job.setEmployerKind(employerKind);
        job.setPartnerId(partnerId);
        job.setTakeMode(takeMode);
        job.setCategoryKey(categoryKey);
        job.setPayCents(payCents);
        job.setPayUnit(payUnit);
        // 费率只能由管理端配置：这里**故意**忽略请求里的 feeMode/feeValue
        job.setFeeMode("none");
        job.setFeeValue(0);
        job.setHeadcount(headcount);
        job.setDeadlineAt(deadlineAt);
        job.setWorkflowStatus("open");
        job.setAcceptedCount(0);
        jobs.insert(job);

        // 校外岗位发布后进入「开放报名」；校内岗位停在 open 等第一个接单人
        if ("offsite".equals(scope)) {
            try {
                advanceJob(job.getId(), "recruiting", "open");
            } catch (IllegalStateException ignored) {
                // 刚插入的岗位不可能被别人改状态；真出现也说明数据异常，保持 open 不影响可报名判断
                log.warn("[campus] 校外岗位 {} 未能进入 recruiting", job.getId());
            }
        }
        auditService.logTextResult("job", job.getId(), userId, title + "\n" + description, textAudit);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", job.getId());
        out.put("auditStatus", auditStatus);
        out.put("workflowStatus", jobs.selectById(job.getId()).getWorkflowStatus());
        out.put("scope", scope);
        out.put("payCents", payCents);
        out.put("feeMode", "none");
        out.put("feeValue", 0);
        return out;
    }

    // ==================================================================
    // 用户端：接单 / 报名
    // ==================================================================

    /**
     * 校内先到先接（§11.1 核心并发点）。
     *
     * 只有 {@link JobMapper#takeCampus} 这条 UPDATE 影响 1 行才算接单成功；
     * 影响 0 行 → 已经被别人接走 → 409。不要改成先查 taken_by 再写。
     */
    @Transactional
    public Map<String, Object> take(Long jobId, Long userId) {
        Job job = jobId == null || jobId < 1 ? null : jobs.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("岗位不存在或已下架");
        assertCanAccept(job, userId);
        if (!"campus".equals(job.getScope())) throw new IllegalStateException("校外岗位请先报名，由雇主筛选");
        if (!"first_come".equals(job.getTakeMode())) throw new IllegalStateException("该岗位需要报名，由雇主筛选");
        if (userId.equals(job.getUserId())) throw new IllegalStateException("不能接自己发布的岗位");

        // ★ 唯一判定：条件 UPDATE 的影响行数
        if (jobs.takeCampus(jobId, userId) != 1) {
            throw new IllegalStateException("该岗位已被接取");
        }

        JobContract contract = new JobContract();
        contract.setJobId(jobId);
        contract.setWorkerId(userId);
        contract.setEmployerId(job.getUserId());
        contract.setStatus("created");
        // 费率与报酬在接单这一刻快照，之后管理端改价不影响本单
        contract.setFeeModeSnapshot(job.getFeeMode() == null ? "none" : job.getFeeMode());
        contract.setFeeValueSnapshot(job.getFeeValue() == null ? 0 : job.getFeeValue());
        contract.setPayCentsSnapshot(job.getPayCents() == null ? 0 : job.getPayCents());
        contract.setPayUnitSnapshot(job.getPayUnit() == null ? "day" : job.getPayUnit());
        try {
            contracts.insert(contract);
        } catch (DuplicateKeyException duplicate) {
            // 同一人对同一岗位已有履约单（取消后再接）：事务回滚，岗位回到 open
            throw new IllegalStateException("你与该岗位已有履约记录，不能重复接单");
        }
        log.info("[campus] 岗位 {} 被用户 {} 接取，履约单 {}", jobId, userId, contract.getId());
        return contractDetail(contract.getId(), userId);
    }

    /** 校外报名：重复报名幂等（UNIQUE 兜底），已下架岗位拒绝。 */
    @Transactional
    public Map<String, Object> apply(Long jobId, Long userId, String message) {
        Job job = jobId == null || jobId < 1 ? null : jobs.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("岗位不存在或已下架");
        if ("offsite".equals(job.getScope()) && !flags.enabledStrict(FLAG_OFFSITE)) {
            throw new ForbiddenException("校外岗位资质未核验，暂未开放");
        }
        assertGate(job, userId);
        assertSameSchool(job, userId);
        assertNotExpired(job);
        if (!"passed".equals(job.getAuditStatus())
            || !Set.of("open", "recruiting").contains(job.getWorkflowStatus())) {
            throw new IllegalStateException("岗位已下架或已停止报名");
        }
        if (userId.equals(job.getUserId())) throw new IllegalStateException("不能报名自己发布的岗位");
        if ("campus".equals(job.getScope()) && "first_come".equals(job.getTakeMode())) {
            throw new IllegalStateException("校内岗位直接接取即可，无需报名");
        }
        String msg = message == null ? "" : message.trim();
        if (msg.length() > 200) throw new IllegalArgumentException("报名留言不能超过200字");

        int inserted = applications.apply(jobId, userId, msg);
        JobApplication row = applications.findOne(jobId, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row == null ? null : row.getId());
        out.put("jobId", jobId);
        out.put("status", row == null ? "pending" : row.getStatus());
        // 重复报名不报错、也不产生第二条：告诉前端这是幂等命中
        out.put("alreadyApplied", inserted == 0);
        return out;
    }

    /** 雇主看自己岗位的报名列表（只有发布者能看，避免泄露其他报名者）。 */
    public Map<String, Object> applications(Long jobId, Long employerId) {
        Job job = jobId == null || jobId < 1 ? null : jobs.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("岗位不存在或已下架");
        if (!employerId.equals(job.getUserId())) throw new ForbiddenException("只有发布者可以查看报名");
        List<Map<String, Object>> list = new ArrayList<>();
        for (JobApplication row : applications.byJob(jobId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getId());
            m.put("applicantId", row.getApplicantId());
            m.put("status", row.getStatus());
            m.put("message", row.getMessage());
            m.put("appliedAt", row.getAppliedAt());
            m.put("decidedAt", row.getDecidedAt());
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        out.put("scope", job.getScope());
        out.put("takeMode", job.getTakeMode());
        out.put("headcount", job.getHeadcount());
        out.put("acceptedCount", job.getAcceptedCount());
        out.put("list", list);
        return out;
    }

    /**
     * 录用 / 拒绝报名 —— 校外岗位由雇主筛选（方案 §5.1）。
     *
     * 名额控制靠条件 UPDATE（accepted_count < headcount），不是"先查出人数再判断"：
     * 两个雇主同时点录用也只会有一个名额被占掉。
     * 报名本身的状态推进也带 status='pending' 条件，重复录用第二次会失败并整体回滚，名额自动释放。
     */
    @Transactional
    public Map<String, Object> decide(Long jobId, Long applicationId, Long employerId,
                                      boolean approve, String reason) {
        Job job = jobId == null || jobId < 1 ? null : jobs.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("岗位不存在或已下架");
        if (!employerId.equals(job.getUserId())) throw new ForbiddenException("只有发布者可以处理报名");
        if ("offsite".equals(job.getScope()) && !flags.enabledStrict(FLAG_OFFSITE)) {
            throw new ForbiddenException("校外岗位资质未核验，暂未开放");
        }
        if (!"passed".equals(job.getAuditStatus())) throw new IllegalStateException("岗位已下架，不能继续录用");
        JobApplication application = applicationId == null ? null : applications.selectById(applicationId);
        if (application == null || !jobId.equals(application.getJobId())) {
            throw new IllegalArgumentException("报名记录不存在");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        out.put("applicationId", applicationId);

        if (!approve) {
            if (applications.decide(applicationId, "rejected", employerId) != 1) {
                throw new IllegalStateException("该报名已经处理过了");
            }
            out.put("status", "rejected");
            return out;
        }

        if ("campus".equals(job.getScope()) && "first_come".equals(job.getTakeMode())) {
            throw new IllegalStateException("校内岗位是先到先接，不需要录用");
        }
        // 占名额：条件 UPDATE 的影响行数就是唯一判定
        assertNotExpired(job);
        if (jobs.occupySeat(jobId) != 1) {
            throw new IllegalStateException("该岗位名额已满或已停止招募");
        }
        // 报名状态推进；如果不是 pending（已被处理）就抛异常让事务回滚，名额随之释放
        if (applications.decide(applicationId, "accepted", employerId) != 1) {
            throw new IllegalStateException("该报名已经处理过了");
        }

        JobContract contract = new JobContract();
        contract.setJobId(jobId);
        contract.setWorkerId(application.getApplicantId());
        contract.setEmployerId(job.getUserId());
        contract.setStatus("created");
        // 与校内接单一致：费率与报酬在这一刻快照
        contract.setFeeModeSnapshot(job.getFeeMode() == null ? "none" : job.getFeeMode());
        contract.setFeeValueSnapshot(job.getFeeValue() == null ? 0 : job.getFeeValue());
        contract.setPayCentsSnapshot(job.getPayCents() == null ? 0 : job.getPayCents());
        contract.setPayUnitSnapshot(job.getPayUnit() == null ? "day" : job.getPayUnit());
        try {
            contracts.insert(contract);
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("该同学与该岗位已有履约记录，不能重复录用");
        }
        log.info("[campus] 岗位 {} 录用了用户 {}（履约单 {}）", jobId, application.getApplicantId(), contract.getId());
        // 首次录用后岗位应从 open 进入 recruiting，否则「已有合同但状态还是 open」会让
        // 下架判断（takeDown）误走取消分支，岗位显示成“已取消”而合同其实还在。
        refreshJobWorkflow(jobId);
        out.put("status", "accepted");
        out.put("contractId", contract.getId());
        out.put("workerId", application.getApplicantId());
        return out;
    }

    // ==================================================================
    // 用户端：履约（开工 / 工时 / 取消 / 争议 / 详情）
    // ==================================================================

    @Transactional
    public Map<String, Object> start(Long contractId, Long userId) {
        JobContract contract = requireParty(contractId, userId);
        if (contracts.start(contractId) != 1) throw new IllegalStateException("履约单当前状态不能开工");
        refreshJobWorkflow(contract.getJobId());
        return contractDetail(contractId, userId);
    }

    /** 提交工时（承接方或发布方都可以提交自己那一侧）。 */
    @Transactional
    public Map<String, Object> submitHours(Long contractId, Long userId, Object rawHours) {
        BigDecimal hours = parseHours(rawHours);
        JobContract contract = requireParty(contractId, userId);
        return writeHours(contract, userId, hours);
    }

    /**
     * 确认工时。与 submit-hours 写的是同一对列（worker_hours / employer_hours），
     * 差别只在语义：双方各自确认一次，两个值都到齐才判定。
     * 一致 → 结算；不一致 → 自动转争议，账单保持 frozen。
     */
    @Transactional
    public Map<String, Object> confirmHours(Long contractId, Long userId, Object rawHours) {
        BigDecimal hours = parseHours(rawHours);
        JobContract contract = requireParty(contractId, userId);
        return writeHours(contract, userId, hours);
    }

    private Map<String, Object> writeHours(JobContract contract, Long userId, BigDecimal hours) {
        boolean worker = userId.equals(contract.getWorkerId());
        int updated = worker
            ? contracts.setWorkerHours(contract.getId(), hours)
            : contracts.setEmployerHours(contract.getId(), hours);
        if (updated != 1) {
            // 已结算/已取消的单：重复提交按幂等返回现状，不重复记账、也不制造 409
            JobContract fresh = contracts.selectById(contract.getId());
            if (fresh != null && Set.of("settled", "refunded", "cancelled").contains(fresh.getStatus())) {
                return contractDetail(contract.getId(), userId);
            }
            throw new IllegalStateException("履约单当前状态不能提交工时");
        }
        // 提交后按该岗位全部履约单重新聚合岗位状态：多名额岗位不能用“单张合同”的状态覆盖
        refreshJobWorkflow(contract.getJobId());

        JobContract fresh = contracts.selectById(contract.getId());
        BigDecimal w = fresh.getWorkerHours();
        BigDecimal e = fresh.getEmployerHours();
        if (w != null && e != null) {
            if (w.compareTo(e) == 0) {
                return settleContract(fresh, w, userId);
            }
            return raiseDispute(fresh, "双方工时不一致（承接方 " + plain(w) + " / 发布方 " + plain(e) + "）",
                userId, userId);
        }
        return contractDetail(contract.getId(), userId);
    }

    /**
     * 取消履约。
     *   开工前：整单按未履约处理，账单三条全部 refunded（金额 0，从未产生应付）；
     *   开工后：只按**双方都确认过**的履约量（min(worker, employer)）结算，其余不记账即视为退回。
     */
    @Transactional
    public Map<String, Object> cancel(Long contractId, Long userId, String reason) {
        JobContract contract = requireParty(contractId, userId);
        String note = reason == null ? "" : reason.trim();
        if (note.length() > 200) throw new IllegalArgumentException("取消原因不能超过200字");

        if (contracts.cancelBeforeStart(contractId, note) == 1) {
            Job job = jobs.selectById(contract.getJobId());
            // 校外岗位的名额是录用时占掉的，开工前取消要把名额还回去；校内是 taken_by 抢占，不涉及计数
            if (job != null && "offsite".equals(job.getScope())) jobs.releaseSeat(job.getId());
            refreshJobWorkflow(contract.getJobId());
            writeLedger(contract, BigDecimal.ZERO, "refunded", "开工前取消，整单未履约");
            return contractDetail(contractId, userId);
        }
        if (contracts.cancelAfterStart(contractId, note) == 1) {
            JobContract fresh = contracts.selectById(contractId);
            BigDecimal performed = confirmedHours(fresh);
            if (performed.signum() > 0) {
                writeLedger(fresh, performed, "settled", "开工后取消，仅结算双方确认的履约量");
            } else {
                writeLedger(fresh, BigDecimal.ZERO, "refunded", "开工后取消，双方未确认履约量，整单退回");
            }
            refreshJobWorkflow(fresh.getJobId());
            return contractDetail(contractId, userId);
        }
        JobContract fresh = contracts.selectById(contractId);
        if (fresh != null && "cancelled".equals(fresh.getStatus())) return contractDetail(contractId, userId);
        throw new IllegalStateException("履约单当前状态不能取消");
    }

    /** 任一方发起争议：金额立即冻结（frozen），不进收入。 */
    @Transactional
    public Map<String, Object> dispute(Long contractId, Long userId, String reason) {
        JobContract contract = requireParty(contractId, userId);
        String note = reason == null ? "" : reason.trim();
        if (note.isEmpty()) throw new IllegalArgumentException("请填写争议原因");
        if (note.length() > 200) throw new IllegalArgumentException("争议原因不能超过200字");
        return raiseDispute(contract, note, userId, userId);
    }

    public Map<String, Object> contractDetail(Long contractId, Long viewerId) {
        if (contractId == null || contractId < 1) throw new IllegalArgumentException("履约单参数无效");
        Map<String, Object> row = contracts.detail(contractId);
        if (row == null) throw new IllegalArgumentException("履约单不存在");
        Long workerId = num(row.get("workerId"));
        Long employerId = num(row.get("employerId"));
        if (viewerId != null && !viewerId.equals(workerId) && !viewerId.equals(employerId)) {
            throw new ForbiddenException("无权查看该履约单");
        }
        row.put("ledger", ledger.byRef(contractId));
        row.put("dispute", disputes.latest(contractId));
        row.put("settlement", estimate(contractId));
        // F10：这份合约结算实际到账积分（0 = 没发）。积分是发给承接方的，发布方看到的也同一个数。
        row.put("pointsAwarded", settledPoints(contractId, workerId));
        return row;
    }

    // ==================================================================
    // 管理端
    // ==================================================================

    public Map<String, Object> adminList(Long schoolId, String keyword, int page, int size, boolean pendingOnly) {
        int safeSize = Math.min(100, Math.max(1, size));
        int safePage = Math.max(1, page);
        String kw = keyword == null ? "" : keyword.trim();
        String like = kw.isEmpty() ? "" : "%" + escapeLike(kw) + "%";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", jobs.adminList(schoolId, like, pendingOnly, safeSize, offset(safePage, safeSize)));
        out.put("total", jobs.adminCount(schoolId, like, pendingOnly));
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    /** 审核 / 下架。schoolId 非空时只允许操作本校岗位。 */
    @Transactional
    public Map<String, Object> review(Long jobId, boolean approve, String reason,
                                      Long adminId, String adminName, Long scopeSchoolId, String ip) {
        Job job = requireJob(jobId);
        assertSchoolScope(job, scopeSchoolId);
        String note = reason == null ? "" : reason.trim();
        if (note.length() > 200) throw new IllegalArgumentException("审核意见不能超过200字");
        if (!approve && note.isEmpty()) note = "管理员下架";

        int changed = approve ? jobs.approve(jobId, note) : takeDown(job, note);
        if (changed != 1) {
            if (approve) throw new IllegalArgumentException("岗位不存在");
            throw new IllegalStateException("该岗位当前状态不能下架");
        }
        String detail = (approve ? "审核通过" : "审核驳回/下架") + (note.isEmpty() ? "" : "，原因：" + note);
        logAdmin(adminId, adminName, approve ? "job_review_approve" : "job_review_reject",
            "job", String.valueOf(jobId), detail, ip);
        return jobs.detail(jobId);
    }

    /** 逐岗位配置平台服务费。**不预填**：默认 none/0，由管理员显式选择。 */
    @Transactional
    public Map<String, Object> fee(Long jobId, String feeMode, Integer feeValue,
                                   Long adminId, String adminName, Long scopeSchoolId, String ip) {
        Job job = requireJob(jobId);
        assertSchoolScope(job, scopeSchoolId);
        String mode = normalize(feeMode, "none", FEE_MODES, "费率模式");
        int value = feeValue == null ? 0 : feeValue;
        if (value < 0) throw new IllegalArgumentException("费率不能为负数");
        if ("rate".equals(mode) && value > 10000) throw new IllegalArgumentException("比例费率须为 0-10000 的万分比（500 即 5%）");
        if ("amount".equals(mode) && value > 1_000_000) throw new IllegalArgumentException("固定服务费超出允许范围");
        if ("none".equals(mode)) value = 0;
        if (jobs.setFee(jobId, mode, value) != 1) throw new IllegalArgumentException("岗位不存在");
        logAdmin(adminId, adminName, "job_fee", "job", String.valueOf(jobId),
            "服务费配置 feeMode=" + mode + " feeValue=" + value, ip);
        return jobs.detail(jobId);
    }

    /**
     * 管理端履约单列表 —— 争议处理台的唯一入口。
     *
     * 校管只能看本校岗位的履约单（条件写进 SQL，不是查出来再过滤）；
     * 状态过滤只接受白名单值，避免把任意字符串拼进查询。
     * 金额与费率展示的是快照列，与管理端当前配置无关：改价不影响旧单。
     */
    public Map<String, Object> adminContracts(Long schoolId, Long jobId, String status, int page, int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        int safePage = Math.max(1, page);
        String normalized = status == null ? "" : status.trim();
        if (!normalized.isEmpty() && !CONTRACT_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("履约单状态参数无效");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", contracts.adminList(schoolId, jobId, normalized, safeSize, offset(safePage, safeSize)));
        out.put("total", contracts.adminCount(schoolId, jobId, normalized));
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    /** 管理端查看履约单（校管只能看本校岗位的履约单）。 */
    public Map<String, Object> adminContract(Long contractId, Long scopeSchoolId) {
        JobContract contract = contracts.selectById(contractId);
        if (contract == null) throw new IllegalArgumentException("履约单不存在");
        assertSchoolScope(requireJob(contract.getJobId()), scopeSchoolId);
        return contractDetail(contractId, null);
    }

    /**
     * 争议裁定：settled（按管理员确认的计价数量结算）或 refunded（整单退回）。
     * 必须写管理员审计 —— 动钱的动作不能只留一句日志。
     *
     * settled 必须显式传 hours：争议的前提就是双方申报不一致，
     * 自动取承接方申报等于偏向一方（方案 §5.3.4）。管理员看着两侧数字自己填。
     */
    @Transactional
    public Map<String, Object> arbitrate(Long contractId, String decision, Object rawHours, String reason,
                                         Long adminId, String adminName, Long scopeSchoolId, String ip) {
        JobContract contract = contracts.selectById(contractId);
        if (contract == null) throw new IllegalArgumentException("履约单不存在");
        Job job = requireJob(contract.getJobId());
        assertSchoolScope(job, scopeSchoolId);

        String verdict = normalize(decision, "", Set.of("settled", "refunded"), "裁定结果");
        if (verdict.isEmpty()) throw new IllegalArgumentException("裁定只能是 settled 或 refunded");
        String note = reason == null ? "" : reason.trim();
        if (note.length() > 200) throw new IllegalArgumentException("裁定说明不能超过200字");
        if ("settled".equals(verdict) && rawHours == null) {
            throw new IllegalArgumentException("按结算裁定时必须填写确认的计价数量");
        }

        BigDecimal hours = "settled".equals(verdict) ? parseHours(rawHours) : BigDecimal.ZERO;
        int changed = contracts.arbitrate(contractId, verdict, hours);
        if (changed != 1) {
            JobContract fresh = contracts.selectById(contractId);
            if (fresh != null && verdict.equals(fresh.getStatus())) {
                // 幂等只对“同一个裁定”成立：换了数量重提不能被静默忽略
                BigDecimal recorded = fresh.getAgreedHours() == null ? BigDecimal.ZERO : fresh.getAgreedHours();
                if ("settled".equals(verdict) && recorded.compareTo(hours) != 0) {
                    throw new IllegalStateException("该争议已按 " + plain(recorded) + " 个计价单位裁定，不能改为 " + plain(hours));
                }
                return contractDetail(contractId, null);
            }
            throw new IllegalStateException("履约单当前状态不能裁定（需先进入争议）");
        }

        if ("settled".equals(verdict)) {
            refreshJobWorkflow(job.getId());
            writeLedger(contract, hours, "settled", "争议裁定：按管理员确认的 " + plain(hours) + " 个计价单位结算");
            // 争议裁定成 settled 也是「合约结算完成」，同样发一次分（幂等键同 contract:{id}）
            awardSettleAfterCommit(contract);
        } else {
            refreshJobWorkflow(job.getId());
            writeLedger(contract, BigDecimal.ZERO, "refunded", "争议裁定：整单退回");
        }
        JobDispute dispute = disputes.latest(contractId);
        if (dispute != null) disputes.resolve(dispute.getId(), verdict, adminId);
        logAdmin(adminId, adminName, "job_arbitrate", "job_contract", String.valueOf(contractId),
            "争议裁定 " + verdict + ("settled".equals(verdict) ? "，计价数量 " + plain(hours) : "")
                + (note.isEmpty() ? "" : "，说明：" + note), ip);
        return contractDetail(contractId, null);
    }

    /** 管理端删除岗位：软删（下架），保留履约单与账单的上下文。 */
    @Transactional
    public void delete(Long jobId, Long adminId, String adminName, Long scopeSchoolId, String ip) {
        Job job = requireJob(jobId);
        assertSchoolScope(job, scopeSchoolId);
        if (takeDown(job, "管理员删除") != 1) throw new IllegalStateException("该岗位当前状态不能删除");
        logAdmin(adminId, adminName, "job_delete", "job", String.valueOf(jobId), "删除兼职（软删下架）", ip);
    }

    /**
     * 下架的唯一入口：在途履约单保留 workflow_status，只把 audit_status 置为 rejected。
     * 否则管理员一下架，working/pending_confirm/disputed 的合同就会因为岗位状态变 cancelled
     * 而无法提交工时、无法结算、也无法发起争议（方案只要求“下架后不可继续录用”）。
     */
    private int takeDown(Job job, String reason) {
        String status = job.getWorkflowStatus() == null ? "" : job.getWorkflowStatus();
        boolean recruitingWithContracts = "recruiting".equals(status)
            && job.getAcceptedCount() != null && job.getAcceptedCount() > 0;
        if (recruitingWithContracts
            || Set.of("taken", "working", "pending_confirm", "disputed").contains(status)) {
            // 已有人被录用/在履约：只停新报名，岗位状态交给合同聚合，避免先显示“已取消”再被刷回履约中
            int changed = jobs.rejectKeepingWorkflow(job.getId(), reason);
            if (changed == 1) refreshJobWorkflow(job.getId());
            return changed;
        }
        return jobs.reject(job.getId(), reason);
    }

    // ==================================================================
    // 内部：状态机 / 结算 / 账单
    // ==================================================================

    /** 岗位状态流转的唯一入口：先过白名单，再用条件 UPDATE 落库。 */
    private boolean advanceJob(Long jobId, String to, String... froms) {
        for (String from : froms) {
            if (from.equals(to)) continue;
            Set<String> allowed = JOB_FLOW.get(from);
            // 参数里列的是「可能的当前状态」；不在白名单里的候选直接跳过，不能因为它抛错
            if (allowed == null || !allowed.contains(to)) continue;
            if (jobs.setWorkflow(jobId, to, from) == 1) return true;
        }
        Job current = jobs.selectById(jobId);
        if (current == null) throw new IllegalArgumentException("岗位不存在");
        if (to.equals(current.getWorkflowStatus())) return false;   // 已经是目标状态：幂等
        assertTransition(current.getWorkflowStatus(), to);
        throw new IllegalStateException("岗位状态已变更（当前：" + current.getWorkflowStatus() + "），请刷新后重试");
    }

    /**
     * 按岗位下所有履约单聚合岗位展示状态。
     *
     * 为什么不能继续用 {@link #advanceJob}: 岗位只有一条 workflow_status，
     * 而 headcount>1 的校外岗位会有多张合同。第一张结算后岗位进入 settled，
     * 第二张再提交工时时 advanceJob 会从 settled 找不到回 pending_confirm 的边而抛错，
     * 导致整张合同事务回滚 —— 第二个人永远结算不了。
     *
     * 聚合规则（只影响展示；权限与结算仍由 job_contract 的条件 UPDATE 决定）：
     *   有争议 → disputed
     *   有待确认 → pending_confirm
     *   有履约中 → working
     *   有已承接未开工 → 校外 recruiting / 校内 taken
     *   其余：有已结算 → settled；有已退回 → refunded；否则 cancelled
     */
    private void refreshJobWorkflow(Long jobId) {
        Job job = jobs.selectById(jobId);
        if (job == null) return;
        List<JobContract> all = contracts.byJob(jobId);
        if (all == null || all.isEmpty()) return;
        boolean disputed = false, pending = false, working = false, created = false;
        boolean settledAny = false, refundedAny = false;
        for (JobContract c : all) {
            String status = c.getStatus() == null ? "" : c.getStatus();
            switch (status) {
                case "disputed" -> disputed = true;
                case "pending_confirm" -> pending = true;
                case "working" -> working = true;
                case "created" -> created = true;
                case "settled" -> settledAny = true;
                case "refunded" -> refundedAny = true;
                default -> { }
            }
        }
        String desired;
        int accepted = job.getAcceptedCount() == null ? 0 : job.getAcceptedCount();
        int headcount = job.getHeadcount() == null ? 1 : job.getHeadcount();
        // 校外多名额岗位：只要还有名额且仍在审核通过状态，岗位继续处于招募中，
        // 否则第一个人开工后岗位就离开 recruiting，剩余名额再也报不了名、录不了人。
        // 各张合同的进度看合同本身的状态，岗位这一列只表达“还招不招人”。
        if ("offsite".equals(job.getScope()) && "passed".equals(job.getAuditStatus()) && accepted < headcount) {
            desired = "recruiting";
        }
        else if (disputed) desired = "disputed";
        else if (pending) desired = "pending_confirm";
        else if (working) desired = "working";
        else if (created) desired = "offsite".equals(job.getScope()) ? "recruiting" : "taken";
        else if (settledAny) desired = "settled";
        else if (refundedAny) desired = "refunded";
        else desired = "cancelled";
        if (!desired.equals(job.getWorkflowStatus())) jobs.setWorkflowForced(jobId, desired);
    }

    private void assertTransition(String from, String to) {        Set<String> allowed = JOB_FLOW.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalStateException("岗位状态不允许从 " + from + " 变为 " + to);
        }
    }

    /** 结算：只有 pending_confirm 能进 settled，且 agreed_hours = worker_hours。 */
    private Map<String, Object> settleContract(JobContract contract, BigDecimal hours, Long viewerId) {
        if (contracts.settle(contract.getId(), hours) != 1) {
            JobContract fresh = contracts.selectById(contract.getId());
            if (fresh != null && "settled".equals(fresh.getStatus())) {
                // 重复确认/重复请求：不重复记账，直接返回现状
                return contractDetail(contract.getId(), viewerId);
            }
            throw new IllegalStateException("履约单当前状态不能结算");
        }
        refreshJobWorkflow(contract.getJobId());
        writeLedger(contract, hours, "settled", "双方工时一致，结算");
        // F10 交易任务埋点：合约确实进入 settled 且账单已写才发分（+5P/+15G）
        awardSettleAfterCommit(contract);
        return contractDetail(contract.getId(), viewerId);
    }

    /**
     * 兼职结算完成发分（F10，设计稿「兼职发分时机 = 合约 settled」）。
     *
     * 只在真正写 earnings_ledger 的 settled 分支调用：争议冻结（frozen）不发、整单退回
     * （refunded）不发、开工后取消（合约终态是 cancelled，只是按履约量补账）也不发。
     * 发给承接方（contract.workerId）。发分放在事务提交后、走
     * {@link AfterCommitPointsAwarder} 的独立事务 —— 积分服务异常不能把结算回滚掉。
     *
     * 幂等键 contract:{contractId}：重复确认工时/重复裁定同一份合约只发一次。
     */
    private void awardSettleAfterCommit(JobContract contract) {
        Long contractId = contract == null ? null : contract.getId();
        Long workerId = contract == null ? null : contract.getWorkerId();
        if (contractId == null || contractId < 1 || workerId == null || workerId < 1) {
            log.warn("[campus] 兼职结算缺少合约/承接方，跳过发分 contract={}", contractId);
            return;
        }
        Runnable task = () -> awarder.award(workerId, TASK_JOB_SETTLE, "contract:" + contractId, "兼职结算");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            // 没有事务上下文（例如单测直接 new 出来的服务）：立即执行，行为等价于已提交
            task.run();
        }
    }

    /**
     * F10：这份合约**实际**发过多少积分给承接方（0 = 没发）。
     *
     * 判据读 point_log：日上限命中时 task_log 有行但无流水，争议冻结/整单退回从未发分，
     * 所以不能只看 status=settled 就告诉用户「+5 已到账」。
     * 查询失败按 0 处理 —— 提示条不能把履约详情页打成 500。
     */
    private int settledPoints(Long contractId, Long workerId) {
        if (contractId == null || contractId < 1 || workerId == null || workerId < 1) return 0;
        try {
            return contracts.settledPoints(workerId, contractId);
        } catch (RuntimeException e) {
            log.warn("[campus] 查询兼职发分状态失败（按未发分处理） contract={}: {}", contractId, e.getMessage());
            return 0;
        }
    }

    private Map<String, Object> raiseDispute(JobContract contract, String reason, Long raisedBy, Long viewerId) {
        if (contracts.dispute(contract.getId()) != 1) {
            JobContract fresh = contracts.selectById(contract.getId());
            if (fresh != null && "disputed".equals(fresh.getStatus())) {
                return contractDetail(contract.getId(), viewerId);
            }
            throw new IllegalStateException("履约单当前状态不能发起争议");
        }
        refreshJobWorkflow(contract.getJobId());
        JobDispute dispute = new JobDispute();
        dispute.setContractId(contract.getId());
        dispute.setRaisedBy(raisedBy == null ? 0L : raisedBy);
        dispute.setReason(reason);
        dispute.setStatus("open");
        disputes.insert(dispute);
        // 争议期间金额冻结：不进收入、不可结算，只能等裁定
        writeLedger(contract, claimedHours(contracts.selectById(contract.getId())), "frozen",
            "履约争议，金额冻结");
        return contractDetail(contract.getId(), viewerId);
    }

    /**
     * 写履约账单（**不是钱包余额**）。
     *
     * 三条固定 biz_no，天然幂等：
     *   job:{contractId}:payable 雇主应付 = 报酬 × 工时
     *   job:{contractId}:fee     平台服务费（按合约费率快照）
     *   job:{contractId}:income  兼职工净收入 = 应付 - 服务费
     *
     * 状态推进也走条件 UPDATE：frozen → settled / refunded 只命中一次。
     */
    private void writeLedger(JobContract contract, BigDecimal hours, String status, String note) {
        int payCents = contract.getPayCentsSnapshot() == null ? 0 : contract.getPayCentsSnapshot();
        int payable = money(BigDecimal.valueOf(payCents).multiply(hours));
        int fee = Math.min(feeCents(contract, hours), payable);
        int income = Math.max(0, payable - fee);
        Long contractId = contract.getId();
        String base = "job:" + contractId;

        record(base + ":payable", contractId, contract.getEmployerId(), "job_payable", payable, status, note);
        record(base + ":fee", contractId, null, "platform_fee", fee, status, note);
        record(base + ":income", contractId, contract.getWorkerId(), "job_income", income, status, note);

        // 争议冻结过的账单，在这里按条件推进（重复调用只命中一次）
        if (!"frozen".equals(status)) {
            ledger.switchStatus(base + ":payable", "frozen", status);
            ledger.switchStatus(base + ":fee", "frozen", status);
            ledger.switchStatus(base + ":income", "frozen", status);
            // 冻结时 record() 已按承接方申报量写死了金额（INSERT IGNORE 之后不会覆盖）。
            // 争议裁定按管理员确认的 hours 结算时，必须把三行金额重算过来，否则
            // 平台/雇主/承接方看到的都是申报量而非裁定量的钱。refunded 时这里重算为 0，
            // 正好表达「整单退回、不产生任何应付」。
            ledger.reprice(base + ":payable", payable, note);
            ledger.reprice(base + ":fee", fee, note);
            ledger.reprice(base + ":income", income, note);
        }
    }

    private void record(String bizNo, Long contractId, Long userId, String entryType, int amountCents,
                        String status, String note) {
        ledger.record(bizNo, "job", contractId, userId, entryType, amountCents, status, note, null);
    }

    /**
     * 服务费（分），全部取**合约快照**，与当前岗位配置无关：
     *   amount → fee_value（分/单位）× 工时
     *   rate   → round(pay_cents × 工时 × fee_value / 10000)（fee_value 是万分比，500 = 5%）
     *   none   → 0
     */
    private int feeCents(JobContract contract, BigDecimal hours) {
        String mode = contract.getFeeModeSnapshot() == null ? "none" : contract.getFeeModeSnapshot();
        int value = contract.getFeeValueSnapshot() == null ? 0 : contract.getFeeValueSnapshot();
        int pay = contract.getPayCentsSnapshot() == null ? 0 : contract.getPayCentsSnapshot();
        BigDecimal fee = switch (mode) {
            case "amount" -> BigDecimal.valueOf(value).multiply(hours);
            case "rate" -> BigDecimal.valueOf(pay).multiply(hours)
                .multiply(BigDecimal.valueOf(value))
                .divide(BigDecimal.valueOf(10000));
            default -> BigDecimal.ZERO;
        };
        return Math.max(0, money(fee));
    }

    /** 结算预估（只用于展示，不落账）。 */
    private Map<String, Object> estimate(Long contractId) {
        JobContract contract = contracts.selectById(contractId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (contract == null) return out;
        BigDecimal hours = contract.getAgreedHours() != null ? contract.getAgreedHours() : claimedHours(contract);
        int payCents = contract.getPayCentsSnapshot() == null ? 0 : contract.getPayCentsSnapshot();
        int payable = money(BigDecimal.valueOf(payCents).multiply(hours));
        int fee = Math.min(feeCents(contract, hours), payable);
        out.put("hours", hours);
        out.put("payCents", payCents);
        out.put("payUnit", contract.getPayUnitSnapshot());
        out.put("payableCents", payable);
        out.put("feeCents", fee);
        out.put("incomeCents", Math.max(0, payable - fee));
        out.put("feeMode", contract.getFeeModeSnapshot());
        out.put("feeValue", contract.getFeeValueSnapshot());
        return out;
    }

    // ==================================================================
    // 内部：闸门 / 校验 / 小工具
    // ==================================================================

    /** 接单前的三道闸门 + 资质（顺序：校外 → 替课 → 有偿/核验）。 */
    private void assertGate(Job job, Long userId) {
        if ("offsite".equals(job.getScope()) && !flags.enabledStrict(FLAG_OFFSITE)) {
            throw new ForbiddenException("校外岗位资质未核验，暂未开放");
        }
        if ("takeover_class".equals(job.getCategoryKey()) && !flags.enabledStrict(FLAG_TAKEOVER)) {
            throw new ForbiddenException("替课类岗位暂未开放");
        }
        Integer pay = job.getPayCents();
        if (pay != null && pay > 0) {
            if (!flags.enabledStrict(FLAG_PAID)) throw new ForbiddenException("有偿岗位暂未开放");
            verification.requireVerifiedForPaidJob(userId);
        }
    }

    private void assertCanAccept(Job job, Long userId) {
        if (!"passed".equals(job.getAuditStatus())) {
            throw new IllegalStateException("岗位尚未通过审核，暂不能接取");
        }
        assertSameSchool(job, userId);
        assertNotExpired(job);
        assertGate(job, userId);
    }

    /**
     * 校内互助岗位只面向本校学生；跨校直接拿 id 调接口也必须被服务端挡住。
     * 校外岗位由合作方面向多校招募，不做同校限制（由 job.offsite.enabled 闸门与合作方审核兜底）。
     */
    private void assertSameSchool(Job job, Long userId) {
        if (!"campus".equals(job.getScope())) return;
        User user = userId == null || userId < 1 ? null : users.selectById(userId);
        if (user == null) throw new IllegalArgumentException("用户不存在");
        // fail-closed：未绑定学校的用户或岗位不能参与校内互助，不能把“不知道是哪个学校”当成“同校”
        if (job.getSchoolId() == null || user.getSchoolId() == null
            || !job.getSchoolId().equals(user.getSchoolId())) {
            throw new ForbiddenException("只能参与本校岗位（请先完善学校信息）");
        }
    }

    /** 截止时间不是展示字段：过期后接单/报名/录用都必须被服务端拒绝。 */
    private void assertNotExpired(Job job) {
        if (job.getDeadlineAt() != null && job.getDeadlineAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("岗位已过报名/接单截止时间");
        }
    }

    private Job requireJob(Long jobId) {
        Job job = jobId == null || jobId < 1 ? null : jobs.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("岗位不存在");
        return job;
    }

    private JobContract requireParty(Long contractId, Long userId) {
        JobContract contract = contractId == null || contractId < 1 ? null : contracts.selectById(contractId);
        if (contract == null) throw new IllegalArgumentException("履约单不存在");
        if (userId == null
            || (!userId.equals(contract.getWorkerId()) && !userId.equals(contract.getEmployerId()))) {
            throw new ForbiddenException("无权操作该履约单");
        }
        return contract;
    }

    /** 校管只能动本校数据；超管（scopeSchoolId=null）不限制。 */
    private void assertSchoolScope(Job job, Long scopeSchoolId) {
        if (scopeSchoolId == null) return;
        if (!scopeSchoolId.equals(job.getSchoolId())) throw new ForbiddenException("只能管理本校岗位");
    }

    /** 走统一的管理员审计表：争议裁定/删单这类动钱或动数据的动作必须留痕。 */
    private void logAdmin(Long adminId, String adminName, String action, String targetType,
                          String targetId, String detail, String ip) {
        adminAuth.log(adminId, adminName, action, targetType, targetId, detail, ip);
    }

    private static BigDecimal parseHours(Object raw) {
        if (raw == null) throw new IllegalArgumentException("请填写工时");
        BigDecimal hours;
        try {
            hours = new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("工时格式不正确");
        }
        if (hours.signum() <= 0) throw new IllegalArgumentException("工时必须大于0");
        if (hours.compareTo(BigDecimal.valueOf(1000)) > 0) throw new IllegalArgumentException("工时不能超过1000");
        return hours.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal claimedHours(JobContract contract) {
        if (contract.getWorkerHours() != null) return contract.getWorkerHours();
        if (contract.getEmployerHours() != null) return contract.getEmployerHours();
        if (contract.getAgreedHours() != null) return contract.getAgreedHours();
        return BigDecimal.ZERO;
    }

    private static BigDecimal confirmedHours(JobContract contract) {
        BigDecimal w = contract.getWorkerHours();
        BigDecimal e = contract.getEmployerHours();
        if (w == null || e == null) return BigDecimal.ZERO;
        return w.min(e);
    }

    private static int money(BigDecimal value) {
        long cents = value.setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (cents > Integer.MAX_VALUE || cents < 0) {
            throw new IllegalArgumentException("金额超出可记账范围，请核对报酬与计价数量");
        }
        return (int) cents;
    }

    /** 分页偏移：int 乘法在大页码会溢出成负数，MySQL 会直接 500。 */
    private static int offset(int page, int size) {
        long value = (long) (Math.max(1, page) - 1) * Math.max(1, size);
        return (int) Math.min(Integer.MAX_VALUE - Math.max(1, size), Math.max(0, value));
    }

    private Long resolveSchoolId(String schoolScope, Long explicitSchoolId, Long userId) {
        if (explicitSchoolId != null && explicitSchoolId > 0) return explicitSchoolId;
        if ("all".equals(schoolScope)) return null;
        return userSchoolId(userId);
    }

    private Long userSchoolId(Long userId) {
        if (userId == null || userId < 1) return null;
        User user = users.selectById(userId);
        return user == null ? null : user.getSchoolId();
    }

    private static String normalizeScopeFilter(String scope) {
        String value = scope == null ? "" : scope.trim().toLowerCase();
        if (value.isEmpty()) return "";
        if (!SCOPES.contains(value)) throw new IllegalArgumentException("岗位类型只能是 campus 或 offsite");
        return value;
    }

    private static String normalize(Object raw, String fallback, Set<String> allowed, String label) {
        String value = raw == null ? "" : String.valueOf(raw).trim().toLowerCase();
        if (value.isEmpty()) return fallback;
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(label + "只能是 " + String.join(" / ", new ArrayList<>(allowed)));
        }
        return value;
    }

    private static LocalDateTime parseTime(Object raw) {
        String text = raw == null ? "" : String.valueOf(raw).trim();
        if (text.isEmpty()) return null;
        try {
            return LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (Exception ignored) {
            // 兼容只给日期的情况
        }
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text).atStartOfDay();
        } catch (Exception e) {
            throw new IllegalArgumentException("截止时间格式不正确");
        }
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String strOr(Object value, String fallback) {
        String text = str(value);
        return text.isEmpty() ? fallback : text;
    }

    private static Long num(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            if (value instanceof String s && !s.isBlank()) throw new IllegalArgumentException("数字格式不正确");
            return fallback;
        }
    }
}
