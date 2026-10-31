package com.campuslife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campuslife.config.ForbiddenException;
import com.campuslife.entity.UserVerification;
import com.campuslife.mapper.UserVerificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学生与实名核验状态。
 *
 * 设计原则（对应方案 §1.1 总闸门）：
 *   1. 没有可信核验源时**不伪造"已认证"**：默认全员 none，状态只能由管理端人工核验写入。
 *   2. 有偿接单是"人已核验" AND "平台门户已开启"两个条件同时成立才放行，
 *      任何一个不满足都 fail-closed。
 *   3. 只存结论不存证件原文，原始材料不进入本库。
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

    private static final Set<String> VALID = Set.of(NONE, PENDING, VERIFIED, REJECTED);

    private final UserVerificationMapper mapper;
    private final FeatureFlags flags;
    private final AdminAuthService adminAuth;

    /** 某个用户的核验状态；没有记录时返回全 none，绝不返回"已验证"。 */
    public Map<String, Object> status(Long userId) {
        UserVerification row = userId == null || userId < 1 ? null
            : mapper.selectOne(new LambdaQueryWrapper<UserVerification>().eq(UserVerification::getUserId, userId));
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
     * 接取有偿岗位前置校验：未核验直接 403。
     * 调用方还要自己判断平台开关（job.paid.enabled），这里只管"人"。
     */
    public void requireVerifiedForPaidJob(Long userId) {
        Map<String, Object> s = status(userId);
        if (Boolean.TRUE.equals(s.get("verified"))) return;
        throw new ForbiddenException("接取有偿任务需要先完成学生与实名认证");
    }

    /** 管理端人工核验：写入状态并留审计。 */
    @Transactional
    public Map<String, Object> review(Long userId, String realNameStatus, String studentStatus,
                                      String reason, Long reviewerId, String reviewerName, String ip) {
        if (userId == null || userId < 1) throw new IllegalArgumentException("用户不存在");
        String realName = normalize(realNameStatus, "实名状态");
        String student = normalize(studentStatus, "学生状态");
        String note = reason == null ? "" : reason.trim();
        if (note.length() > 200) note = note.substring(0, 200);

        UserVerification row = mapper.selectOne(new LambdaQueryWrapper<UserVerification>().eq(UserVerification::getUserId, userId));
        boolean created = row == null;
        if (created) {
            row = new UserVerification();
            row.setUserId(userId);
        }
        String before = created ? "none/none"
            : (row.getRealNameStatus() + "/" + row.getStudentStatus());
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

    private String normalize(String value, String label) {
        String v = value == null ? "" : value.trim().toLowerCase();
        if (!VALID.contains(v)) throw new IllegalArgumentException(label + "只能是 none/pending/verified/rejected");
        return v;
    }

    /** 管理端列表：分页查待审核/已核验用户。 */
    public List<Map<String, Object>> listForAdmin(String status, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        LambdaQueryWrapper<UserVerification> q = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            q.and(w -> w.eq(UserVerification::getStudentStatus, status)
                        .or().eq(UserVerification::getRealNameStatus, status));
        }
        q.orderByDesc(UserVerification::getUpdatedAt)
         .last("LIMIT " + safeSize + " OFFSET " + ((safePage - 1) * safeSize));
        return mapper.selectList(q).stream().map(this::toMap).toList();
    }

    private Map<String, Object> toMap(UserVerification row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", row.getUserId());
        m.put("realNameStatus", row.getRealNameStatus());
        m.put("studentStatus", row.getStudentStatus());
        m.put("provider", row.getProvider());
        m.put("rejectReason", row.getRejectReason());
        m.put("reviewerId", row.getReviewerId());
        m.put("verifiedAt", row.getVerifiedAt());
        m.put("updatedAt", row.getUpdatedAt());
        return m;
    }
}
