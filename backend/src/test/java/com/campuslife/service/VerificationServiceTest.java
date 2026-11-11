package com.campuslife.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.ForbiddenException;
import com.campuslife.controller.AppConfigController;
import com.campuslife.entity.User;
import com.campuslife.entity.UserVerification;
import com.campuslife.mapper.UserMapper;
import com.campuslife.mapper.UserVerificationMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 学生认证的服务层规则（纯 Mockito，不连库、不起 HTTP）。
 *
 * 对应设计稿 `docs/v3.1/学生认证-功能说明与测试手册.md` 第七节 17 条用例里
 * 能在 Service 决策点收敛的部分：提交校验、重复提交、每日限频、审核、校管跨校、
 * 开关、状态透传、接单联动、配置必填 / 空正则回落 / 改严不追溯 / 限频截断。
 *
 * 不在本类自动化的（见交付报告第 2 节 curl 手工清单）：
 *   - HTTP 401/403 状态码（JUnit 不启动 Spring MVC，接口层由 curl 验证）
 *   - 小程序 / Web 页面的真实渲染与跳转
 */
class VerificationServiceTest {

    private static final long UID = 7L;
    private static final long OWN_SCHOOL = 5L;
    private static final long OTHER_SCHOOL = 6L;
    private static final long REVIEWER = 9L;

    private UserVerificationMapper mapper;
    private UserMapper users;
    private FeatureFlags flags;
    private AdminAuthService adminAuth;
    private AppConfigService appConfig;
    private VerificationService service;

    /** 模拟库里的那一行：insert/updateById 捕获同一对象，selectOne 再读回它。 */
    private final AtomicReference<UserVerification> row = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        mapper = mock(UserVerificationMapper.class);
        users = mock(UserMapper.class);
        flags = mock(FeatureFlags.class);
        adminAuth = mock(AdminAuthService.class);
        appConfig = mock(AppConfigService.class);
        service = new VerificationService(mapper, users, flags, adminAuth, appConfig);
        row.set(null);

        // 开关默认：自助入口开，有偿接单关（与当前线上配置一致）
        when(flags.enabledStrict(anyString())).thenReturn(false);
        when(flags.enabledStrict(VerificationService.FLAG_SELF_APPLY)).thenReturn(true);
        when(flags.enabledStrict(VerificationService.FLAG_PAID_JOB)).thenReturn(false);

        // 配置默认：姓名 + 学号必填，学院选填，manual，每天 3 次
        // 注意：必须显式转 Object —— String.valueOf(T) 会优先匹配 char[] 重载，运行时 ClassCastException
        when(appConfig.get(anyString())).thenAnswer(inv -> switch (String.valueOf((Object) inv.getArgument(0))) {
            case VerificationService.CFG_REQUIRE_REAL_NAME -> "1";
            case VerificationService.CFG_REQUIRE_STUDENT_NO -> "1";
            case VerificationService.CFG_REQUIRE_COLLEGE -> "0";
            case VerificationService.CFG_REQUIRE_PHONE -> "0";
            case VerificationService.CFG_STUDENT_NO_PATTERN -> "^(20[0-9]{2})[0-9]{6,10}$";
            case VerificationService.CFG_REVIEW_MODE -> VerificationService.MODE_MANUAL;
            case VerificationService.CFG_DAILY_LIMIT -> String.valueOf(VerificationService.DAILY_LIMIT_DEFAULT);
            default -> "";
        });

        when(mapper.schoolExists(OWN_SCHOOL)).thenReturn(1);
        when(mapper.schoolExists(OTHER_SCHOOL)).thenReturn(1);
        when(mapper.schoolExists(999L)).thenReturn(0);

        when(mapper.selectOne(any(Wrapper.class))).thenAnswer(inv -> row.get());
        doAnswer(inv -> {
            row.set(inv.getArgument(0));
            return 1;
        }).when(mapper).insert(any(UserVerification.class));
        doAnswer(inv -> {
            row.set(inv.getArgument(0));
            return 1;
        }).when(mapper).updateById(any(UserVerification.class));

        User target = new User();
        target.setId(UID);
        target.setSchoolId(OWN_SCHOOL);
        when(users.selectById(UID)).thenReturn(target);
    }

    // ==================== 工具 ====================

    private UserVerification existing(String realNameStatus, String studentStatus) {
        UserVerification row = new UserVerification();
        row.setId(1L);
        row.setUserId(UID);
        row.setRealNameStatus(realNameStatus);
        row.setStudentStatus(studentStatus);
        this.row.set(row);
        return row;
    }

    private Map<String, Object> validApply() {
        return service.apply(UID, OWN_SCHOOL, "文学与新闻传播学院", "张三", "2026123456", "");
    }

    private void config(String key, String value) {
        when(appConfig.get(key)).thenReturn(value);
    }

    private HttpServletRequest requestWithRole(String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AdminInterceptor.ADMIN_ROLE)).thenReturn(role);
        return request;
    }

    // ==================== 1. 提交成功 ====================

    @Test
    void validApplyCreatesPendingRowAndReturnsReviewingState() {
        Map<String, Object> out = validApply();

        UserVerification saved = row.get();
        assertThat(saved).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(UID);
        assertThat(saved.getRealNameStatus()).isEqualTo(VerificationService.PENDING);
        assertThat(saved.getStudentStatus()).isEqualTo(VerificationService.PENDING);
        assertThat(saved.getProvider()).isEqualTo("manual_admin");
        assertThat(saved.getSource()).isEqualTo("self");
        assertThat(saved.getSubmittedAt()).isNotNull();
        assertThat(saved.getRejectReason()).isEmpty();
        assertThat(saved.getSchoolId()).isEqualTo(OWN_SCHOOL);
        assertThat(saved.getCollege()).isEqualTo("文学与新闻传播学院");
        assertThat(saved.getRealName()).isEqualTo("张三");
        assertThat(saved.getStudentNo()).isEqualTo("2026123456");
        // 限频计数：当天第 1 次
        assertThat(saved.getSubmitDate()).isEqualTo(LocalDate.now(CircleStreakService.BEIJING));
        assertThat(saved.getSubmitCount()).isEqualTo(1);

        assertThat(out.get("realNameStatus")).isEqualTo(VerificationService.PENDING);
        assertThat(out.get("studentStatus")).isEqualTo(VerificationService.PENDING);
        assertThat(out.get("verified")).isEqualTo(false);
        assertThat(out.get("label")).isEqualTo("认证审核中");
        verify(mapper).insert(any(UserVerification.class));
        verify(mapper, never()).updateById(any(UserVerification.class));
    }

    // ==================== 2. 学号格式 ====================

    @Test
    void rejectsStudentNoShorterThanFourChars() {
        assertThatThrownBy(() -> service.apply(UID, OWN_SCHOOL, "", "张三", "123", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("学号格式不正确");
        verify(mapper, never()).insert(any(UserVerification.class));
        verify(mapper, never()).updateById(any(UserVerification.class));
    }

    @Test
    void rejectsStudentNoWithIllegalCharacters() {
        assertThatThrownBy(() -> service.apply(UID, OWN_SCHOOL, "", "张三", "2026中文", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("学号格式不正确");
    }

    // ==================== 3. 姓名格式 ====================

    @Test
    void rejectsRealNameWithDigits() {
        assertThatThrownBy(() -> service.apply(UID, OWN_SCHOOL, "", "张三3", "2026123456", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("请填写真实姓名");
        verify(mapper, never()).insert(any(UserVerification.class));
    }

    @Test
    void acceptsMinorityNameWithMiddleDot() {
        Map<String, Object> out = service.apply(UID, OWN_SCHOOL, "", "阿依古丽·买买提", "2026123456", "");
        assertThat(out.get("realName")).isEqualTo("阿依古丽·买买提");
    }

    // ==================== 4. 学校不存在 ====================

    @Test
    void rejectsUnknownSchoolId() {
        assertThatThrownBy(() -> service.apply(UID, 999L, "", "张三", "2026123456", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("请选择学校");
        verify(mapper, never()).insert(any(UserVerification.class));
    }

    @Test
    void rejectsNullSchoolId() {
        assertThatThrownBy(() -> service.apply(UID, null, "", "张三", "2026123456", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("请选择学校");
    }

    // ==================== 5. 重复提交 ====================

    @Test
    void rejectsResubmitWhilePending() {
        existing(VerificationService.PENDING, VerificationService.PENDING);
        assertThatThrownBy(() -> service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("正在审核中，请耐心等待");
        verify(mapper, never()).updateById(any(UserVerification.class));
    }

    @Test
    void rejectsResubmitWhenAlreadyVerified() {
        existing(VerificationService.VERIFIED, VerificationService.VERIFIED);
        assertThatThrownBy(() -> service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("你已完成认证，无需重复提交");
        verify(mapper, never()).updateById(any(UserVerification.class));
    }

    // ==================== 6. 每日限频 ====================

    @Test
    void fourthSubmitOnSameDayIsRejected() {
        UserVerification current = existing(VerificationService.REJECTED, VerificationService.REJECTED);
        current.setSubmitDate(LocalDate.now(CircleStreakService.BEIJING));
        current.setSubmitCount(3);

        assertThatThrownBy(() -> service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("今天提交次数过多，请明天再试");
        verify(mapper, never()).updateById(any(UserVerification.class));
    }

    @Test
    void thirdSubmitOnSameDayIsAllowedAndCountsUp() {
        UserVerification current = existing(VerificationService.REJECTED, VerificationService.REJECTED);
        current.setSubmitDate(LocalDate.now(CircleStreakService.BEIJING));
        current.setSubmitCount(2);

        Map<String, Object> out = service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", "");

        assertThat(row.get().getSubmitCount()).isEqualTo(3);
        assertThat(out.get("label")).isEqualTo("认证审核中");
        verify(mapper).updateById(any(UserVerification.class));
    }

    @Test
    void submitCountResetsOnANewDay() {
        UserVerification current = existing(VerificationService.REJECTED, VerificationService.REJECTED);
        current.setSubmitDate(LocalDate.now(CircleStreakService.BEIJING).minusDays(1));
        current.setSubmitCount(3);

        service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", "");

        assertThat(row.get().getSubmitCount()).isEqualTo(1);
        assertThat(row.get().getSubmitDate()).isEqualTo(LocalDate.now(CircleStreakService.BEIJING));
    }

    // ==================== 7/8. 审核通过 / 驳回 ====================

    @Test
    void reviewApprovalWritesVerifiedAndClearsReason() {
        UserVerification current = existing(VerificationService.PENDING, VerificationService.PENDING);
        current.setRejectReason("上次驳回原因");
        current.setSource("self");

        Map<String, Object> out = service.review(UID, "verified", "verified", "", REVIEWER,
            "超管", "127.0.0.1", null);

        assertThat(row.get().getRealNameStatus()).isEqualTo(VerificationService.VERIFIED);
        assertThat(row.get().getStudentStatus()).isEqualTo(VerificationService.VERIFIED);
        assertThat(row.get().getProvider()).isEqualTo("manual_admin");
        assertThat(row.get().getVerifiedAt()).isNotNull();
        assertThat(row.get().getRejectReason()).isEmpty();
        assertThat(row.get().getSource()).isEqualTo("self"); // 审核不改来源
        assertThat(row.get().getReviewerId()).isEqualTo(REVIEWER);
        assertThat(out.get("verified")).isEqualTo(true);
        assertThat(out.get("label")).isEqualTo("已认证");
        assertThat(out.get("canTakePaidJob")).isEqualTo(false); // job.paid.enabled 仍关
        verify(adminAuth).log(eq(REVIEWER), eq("超管"), eq("verify_user"), eq("user"),
            eq(String.valueOf(UID)), anyString(), eq("127.0.0.1"));
    }

    @Test
    void reviewRejectionStoresReasonAndUserCanResubmit() {
        existing(VerificationService.PENDING, VerificationService.PENDING);

        Map<String, Object> out = service.review(UID, "rejected", "rejected", "学号与学院不匹配",
            REVIEWER, "超管", "127.0.0.1", null);

        assertThat(out.get("label")).isEqualTo("认证未通过");
        assertThat(out.get("rejectReason")).isEqualTo("学号与学院不匹配");
        assertThat(row.get().getVerifiedAt()).isNull();

        // 驳回后可重新提交：状态回到 pending，旧原因清空
        Map<String, Object> again = service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", "");
        assertThat(again.get("label")).isEqualTo("认证审核中");
        assertThat(row.get().getRejectReason()).isEmpty();
    }

    @Test
    void reviewRejectsUnknownStatusValue() {
        existing(VerificationService.PENDING, VerificationService.PENDING);
        assertThatThrownBy(() -> service.review(UID, "ok", "verified", "", REVIEWER, "超管", "", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("实名状态只能是");
        verify(mapper, never()).updateById(any(UserVerification.class));
    }

    // ==================== 9. 校管跨校 ====================

    @Test
    void schoolAdminCannotReviewUserOfAnotherSchool() {
        User other = new User();
        other.setId(UID);
        other.setSchoolId(OTHER_SCHOOL);
        when(users.selectById(UID)).thenReturn(other);

        assertThatThrownBy(() -> service.review(UID, "verified", "verified", "",
            REVIEWER, "A校管理员", "127.0.0.1", OWN_SCHOOL))
            .isInstanceOf(ForbiddenException.class)
            .hasMessage("只能审核本校用户");
        verify(mapper, never()).updateById(any(UserVerification.class));
        verify(adminAuth, never()).log(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void schoolAdminCanReviewOwnSchoolUser() {
        User own = new User();
        own.setId(UID);
        own.setSchoolId(OWN_SCHOOL);
        when(users.selectById(UID)).thenReturn(own);

        Map<String, Object> out = service.review(UID, "verified", "verified", "",
            REVIEWER, "A校管理员", "127.0.0.1", OWN_SCHOOL);
        assertThat(out.get("verified")).isEqualTo(true);
    }

    // ==================== 10. 开关关闭 ====================

    @Test
    void closedSwitchBlocksApplyBeforeFieldValidation() {
        when(flags.enabledStrict(VerificationService.FLAG_SELF_APPLY)).thenReturn(false);

        // 材料全是错的，也必须先报「暂未开放」——否则用户会以为是自己填错了
        assertThatThrownBy(() -> service.apply(UID, 999L, "x", "1", "1", ""))
            .isInstanceOf(ForbiddenException.class)
            .hasMessage("认证申请暂未开放");
        verify(mapper, never()).insert(any(UserVerification.class));
    }

    // ==================== 11. 状态透传 ====================

    @Test
    void statusLabelsCoverAllFourStates() {
        assertThat(service.status(UID).get("label")).isEqualTo("未认证");

        existing(VerificationService.PENDING, VerificationService.PENDING);
        assertThat(service.status(UID).get("label")).isEqualTo("认证审核中");

        existing(VerificationService.VERIFIED, VerificationService.VERIFIED);
        assertThat(service.status(UID).get("label")).isEqualTo("已认证");

        existing(VerificationService.REJECTED, VerificationService.REJECTED);
        assertThat(service.status(UID).get("label")).isEqualTo("认证未通过");
    }

    @Test
    void verifiedRequiresBothDimensions() {
        existing(VerificationService.VERIFIED, VerificationService.PENDING);
        Map<String, Object> out = service.status(UID);
        assertThat(out.get("verified")).isEqualTo(false);
        assertThat(out.get("label")).isEqualTo("认证审核中");
    }

    // ==================== 12. 接单联动 ====================

    @Test
    void canTakePaidJobStaysFalseWhilePlatformGateClosed() {
        existing(VerificationService.VERIFIED, VerificationService.VERIFIED);

        Map<String, Object> out = service.status(UID);
        assertThat(out.get("verified")).isEqualTo(true);       // 人已核验
        assertThat(out.get("paidJobsOpen")).isEqualTo(false);  // 平台门户未开
        assertThat(out.get("canTakePaidJob")).isEqualTo(false);
    }

    @Test
    void canTakePaidJobTrueOnlyWhenBothConditionsHold() {
        existing(VerificationService.VERIFIED, VerificationService.VERIFIED);
        when(flags.enabledStrict(VerificationService.FLAG_PAID_JOB)).thenReturn(true);

        Map<String, Object> out = service.status(UID);
        assertThat(out.get("canTakePaidJob")).isEqualTo(true);
    }

    @Test
    void requireVerifiedForPaidJobRejectsUnverifiedUser() {
        assertThatThrownBy(() -> service.requireVerifiedForPaidJob(UID))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("接取有偿任务需要先完成学生与实名认证");
    }

    // ==================== 13. 配置-必填项 ====================

    @Test
    void optionalCollegeCanBeEmpty() {
        Map<String, Object> out = service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", "");
        assertThat(out.get("label")).isEqualTo("认证审核中");
        assertThat(row.get().getCollege()).isEmpty();
    }

    @Test
    void requiredCollegeCannotBeEmpty() {
        config(VerificationService.CFG_REQUIRE_COLLEGE, "1");
        assertThatThrownBy(() -> service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("请填写学院");
    }

    @Test
    void optionalRealNameStillValidatesFormatWhenFilled() {
        config(VerificationService.CFG_REQUIRE_REAL_NAME, "0");
        // 关掉必填 ≠ 不校验格式：填了就要能存
        assertThatThrownBy(() -> service.apply(UID, OWN_SCHOOL, "", "张三3", "2026123456", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("请填写真实姓名");
    }

    @Test
    void applyIgnoresOptionalEmptyRealName() {
        config(VerificationService.CFG_REQUIRE_REAL_NAME, "0");
        Map<String, Object> out = service.apply(UID, OWN_SCHOOL, "", "", "2026123456", "");
        assertThat(out.get("label")).isEqualTo("认证审核中");
        assertThat(row.get().getRealName()).isEmpty();
    }

    @Test
    void configExposesRequireMapAndDefaults() {
        Map<String, Object> cfg = service.config();
        assertThat(cfg.get("enabled")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> require = (Map<String, Object>) cfg.get("require");
        assertThat(require).containsEntry("realName", true)
            .containsEntry("studentNo", true)
            .containsEntry("college", false)
            .containsEntry("phone", false);
        assertThat(cfg.get("reviewMode")).isEqualTo(VerificationService.MODE_MANUAL);
        assertThat(cfg.get("dailyLimit")).isEqualTo(3);
    }

    // ==================== 13b. 手机号链路（必填/选填/格式/脱敏）====================

    @Test
    void requiredPhoneCannotBeEmpty() {
        config(VerificationService.CFG_REQUIRE_PHONE, "1");
        assertThatThrownBy(() -> service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("请填写手机号");
        verify(mapper, never()).insert(any(UserVerification.class));
    }

    @Test
    void optionalPhoneCanBeEmptyAndIsStoredEmpty() {
        config(VerificationService.CFG_REQUIRE_PHONE, "0");
        Map<String, Object> out = service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", "");
        assertThat(out.get("label")).isEqualTo("认证审核中");
        assertThat(row.get().getPhone()).isEmpty();
        assertThat(String.valueOf(out.get("phone"))).isEmpty();
    }

    @Test
    void filledPhoneMustBeElevenDigitsStartingWithOne() {
        // 选填 ≠ 随便填：填了就要能存，格式不对直接拒（与学院语义一致）
        for (String bad : List.of("1381234", "23812341234", "1381234123a", "138123412345")) {
            assertThatThrownBy(() -> service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", bad))
                .as("非法手机号 %s 应被拒绝", bad)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("手机号格式不正确");
        }
    }

    @Test
    void validPhoneIsPersistedVerbatimAndMaskedInStatus() {
        Map<String, Object> out = service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", "13812341234");

        // 库里存原文：审核员要能联系到人
        assertThat(row.get().getPhone()).isEqualTo("13812341234");
        // 接口只下发脱敏值：138****1234，且不含完整号码
        assertThat(out.get("phone")).isEqualTo("138****1234");
        assertThat(String.valueOf(out.get("phone"))).doesNotContain("13812341234");
    }

    @Test
    void optionalPhoneWithValidFormatStillPasses() {
        config(VerificationService.CFG_REQUIRE_PHONE, "0");
        Map<String, Object> out = service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", "13900001111");
        assertThat(row.get().getPhone()).isEqualTo("13900001111");
        assertThat(out.get("phone")).isEqualTo("139****1111");
    }

    @Test
    void maskPhoneKeepsShortValuesAndBlanks() {
        assertThat(VerificationService.maskPhone("13812341234")).isEqualTo("138****1234");
        assertThat(VerificationService.maskPhone("")).isEmpty();
        assertThat(VerificationService.maskPhone(null)).isEmpty();
        assertThat(VerificationService.maskPhone("12345")).isEqualTo("12345");
    }

    @Test
    void adminListMasksPhoneBeforeReturning() {
        Map<String, Object> adminRow = new LinkedHashMap<>();
        adminRow.put("userId", UID);
        adminRow.put("phone", "13812341234");
        when(mapper.adminList(any(), any(), anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(new ArrayList<>(List.of(adminRow)));

        List<Map<String, Object>> rows = service.listForAdmin("pending", 1, 20, null, null, "");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("phone")).isEqualTo("138****1234");
    }

    // ==================== 14. 配置-空正则保护 ====================

    @Test
    void autoModeFallsBackToManualWhenPatternIsBlank() {
        assertThat(service.effectiveReviewMode(VerificationService.MODE_AUTO, "")).isEqualTo(VerificationService.MODE_MANUAL);
        assertThat(service.effectiveReviewMode(VerificationService.MODE_AUTO, null)).isEqualTo(VerificationService.MODE_MANUAL);
    }

    @Test
    void whitespaceOnlyPatternIsTrimmedToEmptyBeforeModeDecision() {
        config(VerificationService.CFG_REVIEW_MODE, VerificationService.MODE_AUTO);
        config(VerificationService.CFG_STUDENT_NO_PATTERN, "   ");
        // config() 先 trim 再判空：全是空格等于没填
        assertThat(service.config().get("reviewMode")).isEqualTo(VerificationService.MODE_MANUAL);
    }

    @Test
    void autoModeFallsBackToManualWhenPatternCannotCompile() {
        assertThat(service.effectiveReviewMode(VerificationService.MODE_AUTO, "^(20[0-9]{2}"))
            .isEqualTo(VerificationService.MODE_MANUAL);
    }

    @Test
    void configuredAutoWithBlankPatternNeverAutoPasses() {
        config(VerificationService.CFG_REVIEW_MODE, VerificationService.MODE_AUTO);
        config(VerificationService.CFG_STUDENT_NO_PATTERN, "");

        // 配置被写成「任何学号都通过」也不允许：必须进人工队列
        assertThat(service.config().get("reviewMode")).isEqualTo(VerificationService.MODE_MANUAL);
        Map<String, Object> out = service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", "");
        assertThat(out.get("label")).isEqualTo("认证审核中");
        assertThat(row.get().getProvider()).isEqualTo("manual_admin");
    }

    @Test
    void autoModeWithValidPatternVerifiesImmediately() {
        config(VerificationService.CFG_REVIEW_MODE, VerificationService.MODE_AUTO);
        config(VerificationService.CFG_STUDENT_NO_PATTERN, "^20[0-9]{8}$");

        Map<String, Object> out = service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", "");

        assertThat(out.get("label")).isEqualTo("已认证");
        assertThat(row.get().getProvider()).isEqualTo("auto_rule"); // 与人工核验可区分
        assertThat(row.get().getVerifiedAt()).isNotNull();
        assertThat(row.get().getSubmitCount()).isEqualTo(1);       // 自动通过也计一次
    }

    @Test
    void autoModeWithNonMatchingStudentNoStillGoesManual() {
        config(VerificationService.CFG_REVIEW_MODE, VerificationService.MODE_AUTO);
        config(VerificationService.CFG_STUDENT_NO_PATTERN, "^20[0-9]{8}$");

        Map<String, Object> out = service.apply(UID, OWN_SCHOOL, "", "张三", "abcd1234", "");
        assertThat(out.get("label")).isEqualTo("认证审核中");
        assertThat(row.get().getProvider()).isEqualTo("manual_admin");
    }

    // ==================== 15. 配置-改严不追溯 ====================

    @Test
    void tighteningConfigDoesNotRevalidateVerifiedUser() {
        existing(VerificationService.VERIFIED, VerificationService.VERIFIED);
        // 把「学院」改成必填、开启 auto 且正则收紧：status 只读状态列，不读配置
        config(VerificationService.CFG_REQUIRE_COLLEGE, "1");
        config(VerificationService.CFG_REVIEW_MODE, VerificationService.MODE_AUTO);
        config(VerificationService.CFG_STUDENT_NO_PATTERN, "^999[0-9]{7}$");

        Map<String, Object> out = service.status(UID);

        assertThat(out.get("label")).isEqualTo("已认证");
        assertThat(out.get("verified")).isEqualTo(true);
        // 有记录时 status 不该碰任何配置项（配置改严不能把老用户判成未通过）
        verify(appConfig, never()).get(anyString());
    }

    // ==================== 16. 配置-限频边界 ====================

    @Test
    void dailyLimitClampsOutOfRangeValues() {
        config(VerificationService.CFG_DAILY_LIMIT, "0");
        assertThat(service.dailyLimit()).isEqualTo(1);
        config(VerificationService.CFG_DAILY_LIMIT, "-5");
        assertThat(service.dailyLimit()).isEqualTo(1);
        config(VerificationService.CFG_DAILY_LIMIT, "99");
        assertThat(service.dailyLimit()).isEqualTo(10);
        config(VerificationService.CFG_DAILY_LIMIT, "3");
        assertThat(service.dailyLimit()).isEqualTo(3);
    }

    @Test
    void dailyLimitFallsBackToDefaultWhenMissingOrNotANumber() {
        config(VerificationService.CFG_DAILY_LIMIT, "");
        assertThat(service.dailyLimit()).isEqualTo(VerificationService.DAILY_LIMIT_DEFAULT);
        config(VerificationService.CFG_DAILY_LIMIT, "abc");
        assertThat(service.dailyLimit()).isEqualTo(VerificationService.DAILY_LIMIT_DEFAULT);
    }

    @Test
    void configuredLimitOfOneBlocksTheSecondSubmitSameDay() {
        config(VerificationService.CFG_DAILY_LIMIT, "0"); // 截断为 1
        UserVerification current = existing(VerificationService.REJECTED, VerificationService.REJECTED);
        current.setSubmitDate(LocalDate.now(CircleStreakService.BEIJING));
        current.setSubmitCount(1);

        assertThatThrownBy(() -> service.apply(UID, OWN_SCHOOL, "", "张三", "2026123456", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("今天提交次数过多，请明天再试");
    }

    // ==================== 17. 配置写入权限（平台级配置只认 super）====================

    @Test
    void schoolAdminCannotWritePlatformAppConfig() {
        AppConfigService configService = mock(AppConfigService.class);
        AppConfigController controller = new AppConfigController(configService);
        HttpServletRequest request = requestWithRole("school");

        assertThatThrownBy(() -> controller.save(Map.<String, Object>of("verification.review_mode", "auto"), request))
            .isInstanceOf(ForbiddenException.class)
            .hasMessage("仅超级管理员可以修改全平台配置");
        assertThatThrownBy(() -> controller.list(request))
            .isInstanceOf(ForbiddenException.class);
        verify(configService, never()).update(any());
        verify(configService, never()).listAll();
    }

    @Test
    void superAdminCanWritePlatformAppConfig() {
        AppConfigService configService = mock(AppConfigService.class);
        AppConfigController controller = new AppConfigController(configService);
        HttpServletRequest request = requestWithRole("super");

        controller.save(Map.<String, Object>of("verification.review_mode", "manual"), request);
        controller.list(request);

        verify(configService).update(any());
        verify(configService).listAll();
    }
}