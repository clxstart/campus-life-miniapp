package com.campuslife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campuslife.config.ForbiddenException;
import com.campuslife.entity.User;
import com.campuslife.entity.UserVerification;
import com.campuslife.mapper.UserMapper;
import com.campuslife.mapper.UserVerificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 学生与实名核验状态。
 *
 * 设计原则（对应方案 §1.1 总闸门）：
 *   1. 没有可信核验源时**不伪造"已认证"**：默认全员 none，状态只能由管理端人工核验写入
 *      （或者开了 auto 规则后由学号正则命中写入）。
 *   2. 有偿接单是"人已核验" AND "平台门户已开启"两个条件同时成立才放行，
 *      任何一个不满足都 fail-closed。
 *   3. 只存结论与审核必需的四项文本材料（学校/学院/姓名/学号），证件影像不进本库。
 *
 * V3.1 学生认证新增的可配置规则（平台一套，存 app_config）：
 *   verification.require.*       哪几项必填（关掉的项不校验非空，但用户填了仍保存）
 *   verification.student_no_pattern  学号正则（auto 判定 + 前端即时提示）
 *   verification.review_mode     manual=全部人工审，auto=正则命中直接通过
 *   verification.daily_limit     每人每天提交上限（1-10，越界截断）
 *
 * 三条铁律（都写在代码里，不只写在文档里）：
 *   1. **空正则 + auto = 谁都能过**：auto 必须同时满足「正则非空」且「能编译」，否则强制回落 manual 并记 warn。
 *   2. **改严不追溯**：{@link #status} 只读状态列，不读配置——把学院从选填改成必填，
 *      已通过的老用户不能被重新判成「认证未通过」。
 *   3. **平台一套 ≠ 永远一套**：auto 只适合当前单校阶段（第二所学校学号规则必然冲突），
 *      这句提示写在 app_config 的 remark 里，别让后人靠翻代码猜。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {

    public static final String NONE = "none";
    public static final String PENDING = "pending";
    public static final String VERIFIED = "verified";
    public static final String REJECTED = "rejected";

    /** 有偿接单总闸门：没有它，即使人已核验也不能接单。 */
    public static final String FLAG_PAID_JOB = "job.paid.enabled";
    /** 学生自助申请入口（未接入可信核验源时保持关闭）。 */
    public static final String FLAG_SELF_APPLY = "verification.student.enabled";

    /** 认证细则配置键（与 db/module-app-config-verification.sql 一一对应）。 */
    public static final String CFG_REQUIRE_REAL_NAME = "verification.require.real_name";
    public static final String CFG_REQUIRE_STUDENT_NO = "verification.require.student_no";
    public static final String CFG_REQUIRE_COLLEGE = "verification.require.college";
    public static final String CFG_REQUIRE_PHONE = "verification.require.phone";
    public static final String CFG_STUDENT_NO_PATTERN = "verification.student_no_pattern";
    public static final String CFG_REVIEW_MODE = "verification.review_mode";
    public static final String CFG_DAILY_LIMIT = "verification.daily_limit";

    public static final String MODE_MANUAL = "manual";
    public static final String MODE_AUTO = "auto";

    public static final int DAILY_LIMIT_DEFAULT = 3;
    public static final int DAILY_LIMIT_MIN = 1;
    public static final int DAILY_LIMIT_MAX = 10;

    /** 真实姓名：2–20 字，只含中文/字母/·（少数民族姓名里的间隔号）。 */
    private static final Pattern REAL_NAME = Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z·]{2,20}$");
    /** 学号：4–20 位数字/字母。正则只防乱填，防不了冒用——那靠抽查。 */
    private static final Pattern STUDENT_NO = Pattern.compile("^[A-Za-z0-9]{4,20}$");
    /** 手机号：11 位、1 开头（与小程序表单的即时校验同口径）。 */
    private static final Pattern PHONE = Pattern.compile("^1[0-9]{10}$");

    private static final Set<String> VALID = Set.of(NONE, PENDING, VERIFIED, REJECTED);

    private final UserVerificationMapper mapper;
    private final UserMapper users;
    private final FeatureFlags flags;
    private final AdminAuthService adminAuth;
    private final AppConfigService appConfig;

    /** 只在「auto 被强制回落 manual」这个配置值变化时打一次 warn，避免每次读配置都刷日志。 */
    private volatile String lastAutoFallbackWarn = "";

    // ==================== 用户侧 ====================

    /** 某个用户的核验状态；没有记录时返回全 none，绝不返回"已验证"。 */
    public Map<String, Object> status(Long userId) {
        UserVerification row = userId == null || userId < 1 ? null : load(userId);
        String realName = row == null || row.getRealNameStatus() == null ? NONE : row.getRealNameStatus();
        String student = row == null || row.getStudentStatus() == null ? NONE : row.getStudentStatus();
        boolean verified = VERIFIED.equals(realName) && VERIFIED.equals(student);
        boolean paidJobsOpen = flags.enabledStrict(FLAG_PAID_JOB);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("realNameStatus", realName);
        out.put("studentStatus", student);
        out.put("provider", row == null || row.getProvider() == null ? "" : row.getProvider());
        out.put("rejectReason", row == null || row.getRejectReason() == null ? "" : row.getRejectReason());
        out.put("verifiedAt", row == null ? null : row.getVerifiedAt());
        // 人是否已核验（与平台开关无关）
        out.put("verified", verified);
        // 平台是否开放有偿接单
        out.put("paidJobsOpen", paidJobsOpen);
        // 最终能不能接有偿岗位：两个条件都满足，否则一律 false
        out.put("canTakePaidJob", verified && paidJobsOpen);
        // 自助申请入口是否开放（未接入可信核验源时关闭）
        out.put("selfApplyOpen", flags.enabledStrict(FLAG_SELF_APPLY));
        out.put("label", label(realName, student));

        // 审核材料回显：认证页「审核中/已认证/已驳回」三态都要把上次填的内容带出来
        out.put("schoolId", row == null ? null : row.getSchoolId());
        out.put("college", row == null || row.getCollege() == null ? "" : row.getCollege());
        out.put("realName", row == null || row.getRealName() == null ? "" : row.getRealName());
        out.put("studentNo", row == null || row.getStudentNo() == null ? "" : row.getStudentNo());
        // 手机号只下发脱敏值：页面回显与后续重提都不需要原文，减少泄露面
        out.put("phone", row == null ? "" : maskPhone(row.getPhone()));
        out.put("submittedAt", row == null ? null : row.getSubmittedAt());
        out.put("source", row == null || row.getSource() == null ? "" : row.getSource());
        return out;
    }

    /** 给人看的中文状态，避免前端各自拼。 */
    private String label(String realName, String student) {
        if (VERIFIED.equals(realName) && VERIFIED.equals(student)) return "已认证";
        if (REJECTED.equals(realName) || REJECTED.equals(student)) return "认证未通过";
        if (PENDING.equals(realName) || PENDING.equals(student)) return "认证审核中";
        return "未认证";
    }

    /**
     * 认证配置（公开读，用于渲染表单）。
     *
     * 页面按 require 决定渲染哪几个输入框，**不写死四个**：
     * 现在默认只要求姓名 + 学号，学院选填；打开学院必填后抽查才有「学号与学院是否匹配」的依据。
     */
    public Map<String, Object> config() {
        Map<String, Object> require = new LinkedHashMap<>();
        require.put("realName", on(CFG_REQUIRE_REAL_NAME));
        require.put("studentNo", on(CFG_REQUIRE_STUDENT_NO));
        require.put("college", on(CFG_REQUIRE_COLLEGE));
        require.put("phone", on(CFG_REQUIRE_PHONE));

        String pattern = trim(appConfig.get(CFG_STUDENT_NO_PATTERN));
        String mode = effectiveReviewMode(trim(appConfig.get(CFG_REVIEW_MODE)), pattern);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", flags.enabledStrict(FLAG_SELF_APPLY));
        out.put("require", require);
        out.put("studentNoPattern", pattern);
        out.put("reviewMode", mode);
        out.put("dailyLimit", dailyLimit());
        return out;
    }

    /**
     * 铁律 1：auto 必须「正则非空」且「能编译」，否则强制回落 manual。
     * 系统不允许被配置成「任何学号都通过」——那等于认证入口不存在。
     */
    String effectiveReviewMode(String rawMode, String pattern) {
        String mode = MODE_AUTO.equalsIgnoreCase(rawMode == null ? "" : rawMode.trim()) ? MODE_AUTO : MODE_MANUAL;
        if (!MODE_AUTO.equals(mode)) return MODE_MANUAL;
        String reason = null;
        if (pattern == null || pattern.isEmpty()) reason = "学号正则为空";
        else {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                reason = "学号正则无法编译：" + e.getDescription();
            }
        }
        if (reason == null) return MODE_AUTO;
        String marker = pattern + "|" + reason;
        if (!marker.equals(lastAutoFallbackWarn)) {
            lastAutoFallbackWarn = marker;
            log.warn("[campus] review_mode=auto 但{}，已强制回落 manual（不允许配置成任何学号都通过）", reason);
        }
        return MODE_MANUAL;
    }

    /** 每日提交上限：配置读不到按 3，越界按 1/10 截断（设计稿「配置-限频边界」）。 */
    public int dailyLimit() {
        String raw = trim(appConfig.get(CFG_DAILY_LIMIT));
        int value;
        try {
            value = raw.isEmpty() ? DAILY_LIMIT_DEFAULT : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            value = DAILY_LIMIT_DEFAULT;
        }
        return Math.max(DAILY_LIMIT_MIN, Math.min(DAILY_LIMIT_MAX, value));
    }

    /**
     * 提交学生认证。
     *
     * 顺序很重要：先查开关，再校验材料，再查「是否审核中」，最后才动限频计数。
     * 如果先记次数再校验，用户填错一个字就白白消耗一次机会，第二天来又要等。
     *
     * @return 最新的认证状态（前端据此就地切到「审核中」或「已认证」）
     */
    @Transactional
    public Map<String, Object> apply(Long userId, Long schoolId, String college,
                                     String realName, String studentNo, String phone) {
        if (userId == null || userId < 1) throw new IllegalArgumentException("请先登录");
        // 总闸门：先看入口开没开，未开放时连字段都不校验（避免把「暂未开放」说成「学号格式不对」）
        if (!flags.enabledStrict(FLAG_SELF_APPLY)) throw new ForbiddenException("认证申请暂未开放");

        Map<String, Object> cfg = config();
        Map<?, ?> require = (Map<?, ?>) cfg.get("require");
        boolean needRealName = Boolean.TRUE.equals(require.get("realName"));
        boolean needStudentNo = Boolean.TRUE.equals(require.get("studentNo"));
        boolean needCollege = Boolean.TRUE.equals(require.get("college"));
        boolean needPhone = Boolean.TRUE.equals(require.get("phone"));

        String name = trim(realName);
        String number = trim(studentNo);
        String schoolOfUser = trim(college);
        String phoneOfUser = trim(phone);

        if (schoolId == null || schoolId < 1 || mapper.schoolExists(schoolId) < 1) {
            throw new IllegalArgumentException("请选择学校");
        }
        // 必填项空 → 报该字段的文案；选填项「用户填了」也照样校验格式（填了就要能存）
        if (needRealName || !name.isEmpty()) {
            if (name.isEmpty()) throw new IllegalArgumentException("请填写真实姓名");
            if (!REAL_NAME.matcher(name).matches()) throw new IllegalArgumentException("请填写真实姓名");
        }
        if (needStudentNo || !number.isEmpty()) {
            if (!STUDENT_NO.matcher(number).matches()) throw new IllegalArgumentException("学号格式不正确");
        }
        if (needCollege || !schoolOfUser.isEmpty()) {
            if (schoolOfUser.length() < 2 || schoolOfUser.length() > 32) throw new IllegalArgumentException("请填写学院");
        }
        // 手机号语义与学院一致：必填时不能空；选填时空放行，填了就要能存（格式必须对）
        if (needPhone || !phoneOfUser.isEmpty()) {
            if (phoneOfUser.isEmpty()) throw new IllegalArgumentException("请填写手机号");
            if (!PHONE.matcher(phoneOfUser).matches()) throw new IllegalArgumentException("手机号格式不正确");
        }

        UserVerification row = load(userId);
        String realNameStatus = row == null ? NONE : nvl(row.getRealNameStatus(), NONE);
        String studentStatus = row == null ? NONE : nvl(row.getStudentStatus(), NONE);
        if (PENDING.equals(realNameStatus) || PENDING.equals(studentStatus)) {
            // 审核中不允许重复提交：重复提交只会把审核队列搞乱，材料也不会变
            throw new IllegalArgumentException("正在审核中，请耐心等待");
        }
        if (VERIFIED.equals(realNameStatus) && VERIFIED.equals(studentStatus)) {
            throw new IllegalArgumentException("你已完成认证，无需重复提交");
        }

        LocalDate today = LocalDate.now(CircleStreakService.BEIJING);
        int used = row != null && today.equals(row.getSubmitDate()) ? nvlInt(row.getSubmitCount()) : 0;
        int limit = dailyLimit();
        if (used >= limit) throw new IllegalArgumentException("今天提交次数过多，请明天再试");

        // auto 规则命中 → 直接通过；否则进人工队列
        boolean autoPass = MODE_AUTO.equals(cfg.get("reviewMode")) && !number.isEmpty()
            && Pattern.compile(String.valueOf(cfg.get("studentNoPattern"))).matcher(number).matches();
        LocalDateTime now = LocalDateTime.now();

        boolean created = row == null;
        if (created) {
            row = new UserVerification();
            row.setUserId(userId);
        }
        row.setSchoolId(schoolId);
        row.setCollege(schoolOfUser);
        row.setRealName(name);
        row.setStudentNo(number);
        row.setPhone(phoneOfUser);
        row.setSubmittedAt(now);
        row.setSource("self");
        row.setRealNameStatus(autoPass ? VERIFIED : PENDING);
        row.setStudentStatus(autoPass ? VERIFIED : PENDING);
        // 自动通过如实记 auto_rule：出了冒用问题，要能一眼区分「人工核验过」还是「正则放行」
        row.setProvider(autoPass ? "auto_rule" : "manual_admin");
        row.setRejectReason("");
        row.setVerifiedAt(autoPass ? now : null);
        row.setSubmitDate(today);
        row.setSubmitCount(used + 1);
        row.setUpdatedAt(now);
        if (created) mapper.insert(row);
        else mapper.updateById(row);

        log.info("[campus] 用户 {} 提交学生认证（{}），学校 {}，{}",
            userId, autoPass ? "正则命中自动通过" : "转人工审核", schoolId, "第 " + (used + 1) + " 次");
        return status(userId);
    }

    /**
     * 接取有偿岗位前置校验：未核验直接 403。
     * 调用方还要自己判断平台开关（job.paid.enabled），这里只管"人"。
     */
    public void requireVerifiedForPaidJob(Long userId) {
        Map<String, Object> s = status(userId);
        if (Boolean.TRUE.equals(s.get("verified"))) return;
        throw new ForbiddenException("接取有偿任务需要先完成学生与实名认证");
    }

    // ==================== 管理端 ====================

    /** 管理端人工核验：写入状态并留审计。 */
    @Transactional
    public Map<String, Object> review(Long userId, String realNameStatus, String studentStatus,
                                      String reason, Long reviewerId, String reviewerName, String ip,
                                      Long scopeSchoolId) {
        if (userId == null || userId < 1) throw new IllegalArgumentException("用户不存在");
        User target = users.selectById(userId);
        if (target == null) throw new IllegalArgumentException("用户不存在");
        // 校管只能核验本校用户；校园身份必须绑定学校，否则会把 B 校学生核验成 A 校“已认证”。
        if (scopeSchoolId != null && !scopeSchoolId.equals(target.getSchoolId())) {
            throw new ForbiddenException("只能审核本校用户");
        }
        String realName = normalize(realNameStatus, "实名状态");
        String student = normalize(studentStatus, "学生状态");
        String note = reason == null ? "" : reason.trim();
        if (note.length() > 200) note = note.substring(0, 200);

        UserVerification row = load(userId);
        boolean created = row == null;
        if (created) {
            row = new UserVerification();
            row.setUserId(userId);
            // 管理员直接核验：没有用户提交的环节，来源标 admin，便于日后统计自助/人工占比
            row.setSource("admin");
        }
        String before = created ? "none/none" : (row.getRealNameStatus() + "/" + row.getStudentStatus());
        row.setRealNameStatus(realName);
        row.setStudentStatus(student);
        row.setProvider("manual_admin");
        row.setRejectReason(REJECTED.equals(realName) || REJECTED.equals(student) ? note : "");
        row.setReviewerId(reviewerId);
        row.setVerifiedAt(VERIFIED.equals(realName) && VERIFIED.equals(student) ? LocalDateTime.now() : null);
        row.setUpdatedAt(LocalDateTime.now());
        if (created) mapper.insert(row);
        else mapper.updateById(row);

        adminAuth.log(reviewerId, reviewerName, "verify_user", "user", String.valueOf(userId),
            "核验状态 " + before + " -> " + realName + "/" + student + (note.isBlank() ? "" : "，原因：" + note), ip);
        log.info("[campus] 用户 {} 核验状态被 {} 更新为 {}/{}", userId, reviewerName, realName, student);
        return status(userId);
    }

    /** 管理端列表：分页查待审核/已核验用户；校管只看本校，可按学校/姓名/学号再筛。 */
    public List<Map<String, Object>> listForAdmin(String status, int page, int size, Long scopeSchoolId,
                                                 Long schoolId, String keyword) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        long offset = Math.min(Integer.MAX_VALUE - safeSize, (long) (safePage - 1) * safeSize);
        String normalized = status == null || status.isBlank() ? "" : status.trim();
        String like = keyword == null || keyword.isBlank() ? "" : keyword.trim();
        if (like.length() > 32) like = like.substring(0, 32);
        List<Map<String, Object>> rows = mapper.adminList(scopeSchoolId, schoolId, normalized, like, safeSize, (int) offset);
        // 后台列表同样只给脱敏手机号：审核要的是「能联系」，不是把整串号码摊在页面上
        for (Map<String, Object> row : rows) {
            Object phone = row.get("phone");
            row.put("phone", maskPhone(phone == null ? "" : String.valueOf(phone)));
        }
        return rows;
    }

    private String normalize(String value, String label) {
        String v = value == null ? "" : value.trim().toLowerCase();
        if (!VALID.contains(v)) throw new IllegalArgumentException(label + "只能是 none/pending/verified/rejected");
        return v;
    }

    // ==================== 工具 ====================

    private UserVerification load(Long userId) {
        return mapper.selectOne(new LambdaQueryWrapper<UserVerification>()
            .eq(UserVerification::getUserId, userId));
    }

    private boolean on(String configKey) {
        return "1".equals(trim(appConfig.get(configKey)));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int nvlInt(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 手机号脱敏：13812341234 → 138****1234。
     *
     * 所有对外的接口（/me、管理端列表、审核返回）都走这一个函数，避免某处漏脱敏。
     * 太短的值（< 7 位）没有可遮的部分，原样返回；空值返回空串。
     */
    static String maskPhone(String phone) {
        String value = trim(phone);
        if (value.length() < 7) return value;
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }
}