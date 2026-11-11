package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.ha.ClusterLock;
import com.campuslife.mapper.DormMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宿舍域：建宿 / 邀请码加入 / 退出 / 全员签到 / 周榜。
 *
 * 三条硬规矩（设计稿「三、设计取舍」「九、风险」）：
 *   1. 一人一宿舍 —— 数据库 uk_dorm_user 兜底；服务层的「请先退出当前宿舍」只是友好文案。
 *   2. 全员判定按**实际成员数**且当日冻结 —— dorm_sign.member_count 只在当日首行写入时固化，
 *      中途退出/加入不改变判定基数，避免「最后一刻退出把 7/8 变成 7/7 白拿奖励」。
 *   3. 奖励只发一次 —— dorm_sign.all_done 用条件更新从 0 变 1，只有返回 1 的那一次发分；
 *      发分本身仍走 PointsService 的 biz_key 幂等，双保险。
 *
 * 与签到的关系：宿舍不单独做签到按钮，复用全局签到；{@link SignService} 在**事务提交后**
 * 通过 {@link SignCompletedListener} 回调本类，宿舍判定失败只记日志，不回滚签到。
 *
 * 时区：自然日/自然周一律 Asia/Shanghai，时间参数在 Java 算好后传给 Mapper，不依赖 MySQL CURDATE()。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DormService implements SignCompletedListener {

    /** 与 SignService / PointsService / ForumRankService 同一口径。 */
    public static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    static final String TASK_ALL_SIGN = "dorm_all_sign";
    static final String TASK_WEEK_FIRST = "dorm_week_first";

    /** 奶茶券商品键（设计稿「五、周榜结算」：有库存才发，没有只发成长值并记日志）。 */
    static final String MILK_TEA_ITEM = "milk_tea";

    /** 邀请码字母表：去掉 0/O/1/I 等易混字符，避免口头传播时抄错。 */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 6;
    private static final int CODE_RETRY = 10;

    /** 周榜结算租约：单轮（最多 8 人发分 + 发券）远小于 10 分钟。 */
    private static final Duration LEASE = Duration.ofMinutes(10);

    private final DormMapper mapper;
    private final PointsService points;
    private final VerificationService verification;
    private final ClusterLock clusterLock;

    private final SecureRandom random = new SecureRandom();

    // ==================== 用户侧：我的宿舍 ====================

    /**
     * 我的宿舍：宿舍信息、成员与今日签到、本周榜。
     * 未加入返回 joined=false；已解散返回 joined=false + dissolved=true（页面降级为「未加入」并提示）。
     */
    public Map<String, Object> me(Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> member = mapper.memberOf(userId);
        if (member == null || member.get("dormId") == null) {
            return notJoined(out, false);
        }
        Long dormId = longOf(member.get("dormId"));
        Map<String, Object> dorm = dormId == null ? null : mapper.dormById(dormId);
        if (dorm == null || num(dorm.get("status")) != 1) {
            // 宿舍被解散：成员关系可能还没清掉，页面按「未加入 + 已解散」处理
            return notJoined(out, true);
        }

        LocalDate today = LocalDate.now(BEIJING);
        List<Map<String, Object>> members = mapper.members(List.of(dormId), today);
        int memberCount = members.size();
        int signedCount = 0;
        boolean mySigned = false;
        List<Map<String, Object>> brief = new ArrayList<>(memberCount);
        for (Map<String, Object> row : members) {
            boolean signed = num(row.get("signedToday")) == 1;
            if (signed) signedCount++;
            if (longOf(row.get("userId")) != null && longOf(row.get("userId")).equals(userId)) mySigned = signed;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", row.get("userId"));
            m.put("nickname", row.get("nickname"));
            m.put("avatar", row.get("avatar"));
            m.put("role", row.get("role"));
            m.put("signedToday", signed);
            brief.add(m);
        }

        Map<String, Object> sign = mapper.signOf(dormId, today);
        boolean allDone = sign != null && num(sign.get("allDone")) == 1;

        Map<String, Object> dormOut = new LinkedHashMap<>();
        dormOut.put("id", dormId);
        dormOut.put("name", dorm.get("name"));
        dormOut.put("building", dorm.get("building"));
        dormOut.put("room", dorm.get("room"));
        dormOut.put("inviteCode", dorm.get("inviteCode"));
        dormOut.put("leaderId", dorm.get("leaderId"));
        dormOut.put("memberLimit", dorm.get("memberLimit"));
        dormOut.put("memberCount", memberCount);

        Map<String, Object> todayOut = new LinkedHashMap<>();
        todayOut.put("signedCount", signedCount);
        todayOut.put("memberCount", memberCount);
        todayOut.put("allDone", allDone);
        // 差人提示用：还差几人；全员达成后为 0
        todayOut.put("gap", Math.max(0, memberCount - signedCount));

        out.put("joined", true);
        out.put("dissolved", false);
        out.put("dorm", dormOut);
        out.put("members", brief);
        out.put("today", todayOut);
        out.put("me", Map.of("role", member.get("role") == null ? "member" : member.get("role"),
            "signedToday", mySigned));
        out.put("week", weekView(dormId));
        return out;
    }

    private Map<String, Object> notJoined(Map<String, Object> out, boolean dissolved) {
        out.put("joined", false);
        out.put("dissolved", dissolved);
        out.put("dorm", null);
        out.put("members", List.of());
        out.put("today", Map.of("signedCount", 0, "memberCount", 0, "allDone", false, "gap", 0));
        out.put("me", null);
        out.put("week", null);
        return out;
    }

    /** 本周榜：分数 = 宿舍成员本周任务流水 p 之和；并列时 dorm_id 小者在前（Mapper 已排序）。 */
    private Map<String, Object> weekView(Long dormId) {
        LocalDate monday = mondayOf(LocalDate.now(BEIJING));
        List<Map<String, Object>> scores = mapper.weekScores(monday, monday.plusDays(6));
        int rank = 0, score = 0, top = 0;
        for (int i = 0; i < scores.size(); i++) {
            Map<String, Object> row = scores.get(i);
            int s = num(row.get("score"));
            if (i == 0) top = s;
            if (longOf(row.get("dormId")) != null && longOf(row.get("dormId")).equals(dormId)) {
                rank = i + 1;
                score = s;
            }
        }
        Map<String, Object> week = new LinkedHashMap<>();
        week.put("periodKey", weekKey(monday));
        week.put("rank", rank);
        week.put("score", score);
        week.put("dormCount", scores.size());
        week.put("gap", Math.max(0, top - score));
        week.put("champion", rank == 1 && scores.size() > 0);
        return week;
    }

    // ==================== 用户侧：创建 / 加入 / 退出 ====================

    /**
     * 创建宿舍：需要已认证（建的人是组织者，风险最高）。
     * 邀请码唯一由 uk_dorm_code 兜底，撞码重试；「一人一宿舍」由 uk_dorm_user 兜底。
     */
    @Transactional
    public Map<String, Object> create(Long userId, String name, String building, String room) {
        requireVerified(userId);
        if (mapper.memberOf(userId) != null) throw new ForbiddenException("你已在宿舍中");

        String dormName = trim(name);
        if (dormName.isEmpty()) throw new IllegalArgumentException("请填写宿舍名");
        if (dormName.length() > 32) throw new IllegalArgumentException("宿舍名最多 32 个字");
        String b = trim(building);
        String r = trim(room);
        if (b.length() > 32) throw new IllegalArgumentException("楼栋最多 32 个字");
        if (r.length() > 16) throw new IllegalArgumentException("房间号最多 16 个字");

        Long schoolId = mapper.userSchoolId(userId);
        Long dormId = null;
        String code = null;
        for (int attempt = 0; attempt < CODE_RETRY && dormId == null; attempt++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schoolId", schoolId);
            row.put("name", dormName);
            row.put("building", b);
            row.put("room", r);
            row.put("leaderId", userId);
            row.put("inviteCode", newInviteCode());
            row.put("memberLimit", 8);
            try {
                mapper.insertDorm(row);
                code = String.valueOf(row.get("inviteCode"));
                dormId = longOf(row.get("id"));
            } catch (DuplicateKeyException e) {
                // 撞码：换一个再试（6 位 32 字符 = 10 亿组合，实际几乎不会撞）
                log.info("[campus] 邀请码冲突，重试第 {} 次", attempt + 1);
            }
        }
        if (dormId == null || code == null) throw new IllegalStateException("邀请码生成失败，请稍后重试");

        try {
            mapper.addMember(dormId, userId, "leader");
        } catch (DuplicateKeyException e) {
            // 并发双击建宿：唯一键挡住第二行，事务回滚，不会留下两个宿舍
            throw new ForbiddenException("你已在宿舍中");
        }
        log.info("[campus] 用户 {} 创建宿舍 {}（{}）", userId, dormId, dormName);
        return me(userId);
    }

    /** 用邀请码加入。加入前看不到宿舍详情（隐私口径）。 */
    @Transactional
    public Map<String, Object> join(Long userId, String inviteCode) {
        String code = trim(inviteCode).toUpperCase();
        if (code.length() != CODE_LENGTH) throw new IllegalArgumentException("邀请码无效，请确认后重试");
        if (mapper.memberOf(userId) != null) throw new ForbiddenException("请先退出当前宿舍");

        Map<String, Object> dorm = mapper.dormByCode(code);
        if (dorm == null) throw new IllegalArgumentException("邀请码无效，请确认后重试");
        Long dormId = longOf(dorm.get("id"));
        if (dormId == null) throw new IllegalArgumentException("邀请码无效，请确认后重试");
        if (num(dorm.get("status")) != 1) throw new IllegalArgumentException("该宿舍已解散");

        // 先锁宿舍行再数人数：否则 8 人宿舍能被第 9、10 个人同时挤进来
        mapper.lockDorm(dormId);
        int limit = num(dorm.get("memberLimit"));
        if (limit <= 0) limit = 8;
        if (mapper.memberCount(dormId) >= limit) throw new IllegalArgumentException("该宿舍已满 " + limit + " 人");
        try {
            mapper.addMember(dormId, userId, "member");
        } catch (DuplicateKeyException e) {
            throw new ForbiddenException("请先退出当前宿舍");
        }
        log.info("[campus] 用户 {} 加入宿舍 {}", userId, dormId);
        return me(userId);
    }

    /**
     * 退出宿舍。
     * 舍长不能直接退出（其他成员的数据会失去组织者），需先转让或由管理员解散；
     * 单人宿舍的舍长退出时自动解散空壳宿舍。
     */
    @Transactional
    public void leave(Long userId) {
        Map<String, Object> member = mapper.memberOf(userId);
        if (member == null || member.get("dormId") == null) throw new IllegalArgumentException("你还没有加入宿舍");
        Long dormId = longOf(member.get("dormId"));
        boolean leader = "leader".equals(String.valueOf(member.get("role")));
        int count = dormId == null ? 0 : mapper.memberCount(dormId);
        if (leader && count > 1) {
            throw new ForbiddenException("舍长退出前需先把宿舍交给其他成员，或联系管理员解散");
        }
        mapper.removeMember(userId);
        if (leader && dormId != null && count <= 1) {
            mapper.closeLoneDorm(dormId, LocalDateTime.now(BEIJING));
        }
        log.info("[campus] 用户 {} 退出宿舍 {}", userId, dormId);
    }

    // ==================== 全员签到（签到成功后回调）====================

    /**
     * 签到已提交后调用。
     * 实现方必须自己吞掉异常：宿舍判定失败不能影响签到结果（设计稿「九、风险」最后一行）。
     */
    @Override
    public void onSigned(Long userId, LocalDate bizDate) {
        try {
            handleSigned(userId, bizDate == null ? LocalDate.now(BEIJING) : bizDate);
        } catch (RuntimeException e) {
            log.warn("[campus] 宿舍全员签到判定失败 user={} date={}：{}", userId, bizDate, e.getMessage(), e);
        }
    }

    private void handleSigned(Long userId, LocalDate bizDate) {
        Map<String, Object> member = mapper.memberOf(userId);
        if (member == null || member.get("dormId") == null) return;
        Long dormId = longOf(member.get("dormId"));
        if (dormId == null) return;

        Map<String, Object> dorm = mapper.dormById(dormId);
        if (dorm == null || num(dorm.get("status")) != 1) return;

        int memberCount = mapper.memberCount(dormId);
        if (memberCount <= 0) return;

        // 当日首行固化成员数；重复签到/并发签到都走同一行，成员数不会变
        mapper.ensureSignRow(dormId, bizDate, memberCount);
        // 已签人数按成员集合 + sign_roll 实时重算，天然幂等，不会因为重复回调多加一个
        int signed = mapper.countSignedMembers(dormId, bizDate);
        mapper.updateSignedCount(dormId, bizDate, signed);

        // 条件更新：并发下只有一次能从 0 变 1，只有这一次发分
        if (mapper.claimAllDone(dormId, bizDate) != 1) return;

        String bizKey = "dorm:" + dormId + ":" + bizDate;
        int awarded = 0;
        for (Long memberId : mapper.memberIds(dormId)) {
            try {
                if (points.award(memberId, TASK_ALL_SIGN, bizKey, "宿舍全员签到") == PointsService.AwardResult.AWARDED) {
                    awarded++;
                }
            } catch (RuntimeException e) {
                // 单个成员发分失败不回滚其他成员；当日 all_done 已置位，不重复补发（避免重复发）
                log.warn("[campus] 宿舍 {} 全员签到给成员 {} 发分失败：{}", dormId, memberId, e.getMessage());
            }
        }
        log.info("[campus] 宿舍 {} 于 {} 全员到齐，发放 {} 人", dormId, bizDate, awarded);
    }

    // ==================== 周榜结算 ====================

    /**
     * 每周一 00:20 结算上一周（排在论坛周榜 00:10 之后，避免两个重结算任务抢连接）。
     * 多实例下用租约锁减少重复执行；正确性靠 task_log 唯一键（同一 bizKey 只发一次）。
     */
    @Scheduled(cron = "0 20 0 * * MON", zone = "Asia/Shanghai")
    public void scheduledSettle() {
        clusterLock.runExclusive("dorm-week-settle", LEASE, () -> {
            try {
                settleWeek(mondayOf(LocalDate.now(BEIJING).minusWeeks(1)));
            } catch (RuntimeException e) {
                log.error("[campus] 宿舍周榜结算失败：{}", e.getMessage(), e);
            }
        });
    }

    /**
     * 结算某一周（按周一日期归一）。公开给后台补跑与测试。
     * 第一名每个成员 +30G；reward_item 的 milk_tea 有库存则每人再发一张券，
     * 没有库存/没配置只记日志，绝不让发券失败把成长值一起拖垮。
     */
    public Map<String, Object> settleWeek(LocalDate anyDayOfWeek) {
        LocalDate monday = mondayOf(anyDayOfWeek);
        LocalDate sunday = monday.plusDays(6);
        String periodKey = weekKey(monday);
        List<Map<String, Object>> scores = mapper.weekScores(monday, sunday);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodKey", periodKey);
        out.put("from", monday.toString());
        out.put("to", sunday.toString());
        out.put("dormCount", scores.size());
        if (scores.isEmpty()) {
            out.put("championDormId", null);
            out.put("members", 0);
            out.put("awarded", 0);
            out.put("coupons", 0);
            return out;
        }

        // Mapper 已按 score DESC, dorm_id ASC 排序：第一个就是冠军（并列取 dorm_id 小者）
        Map<String, Object> top = scores.get(0);
        Long dormId = longOf(top.get("dormId"));
        int score = num(top.get("score"));
        List<Long> memberIds = dormId == null ? List.of() : mapper.memberIds(dormId);

        String bizKey = "week:" + periodKey;
        int awarded = 0;
        for (Long memberId : memberIds) {
            try {
                if (points.award(memberId, TASK_WEEK_FIRST, bizKey, "宿舍周榜第一") == PointsService.AwardResult.AWARDED) {
                    awarded++;
                }
            } catch (RuntimeException e) {
                log.warn("[campus] 宿舍 {} 周榜奖励给成员 {} 发放失败：{}", dormId, memberId, e.getMessage());
            }
        }

        int coupons = grantMilkTea(memberIds);

        out.put("championDormId", dormId);
        out.put("championName", top.get("name"));
        out.put("score", score);
        out.put("members", memberIds.size());
        out.put("awarded", awarded);
        out.put("coupons", coupons);
        log.info("[campus] 宿舍周榜 {} 冠军 {}（{} 分），发奖 {} 人，发券 {} 张",
            periodKey, dormId, score, awarded, coupons);
        return out;
    }

    /**
     * 奶茶券：设计稿把它定为「有商户券库存才发」。
     * reward_item 表属于功能 6，可能尚未建表/未配置 milk_tea —— 所有异常都必须吞掉。
     */
    private int grantMilkTea(List<Long> memberIds) {
        Map<String, Object> item;
        try {
            item = mapper.rewardItem(MILK_TEA_ITEM);
        } catch (RuntimeException e) {
            log.warn("[campus] milk_tea 券不可用（reward_item 缺失或未配置），本次只发成长值：{}", e.getMessage());
            return 0;
        }
        if (item == null) {
            log.info("[campus] milk_tea 券库存不足或未上架，本次只发成长值");
            return 0;
        }
        String title = String.valueOf(item.get("couponTitle"));
        String amount = item.get("couponAmount") == null ? "" : String.valueOf(item.get("couponAmount"));
        int expireDays = num(item.get("expireDays"));
        if (expireDays <= 0) expireDays = 30;
        String expireAt = LocalDate.now(BEIJING).plusDays(expireDays).toString();
        int granted = 0;
        for (Long memberId : memberIds) {
            try {
                if (mapper.takeRewardStock(MILK_TEA_ITEM) != 1) {
                    log.info("[campus] milk_tea 券库存不足，剩余成员跳过发券（已发 {} 张）", granted);
                    break;
                }
                mapper.grantCoupon(memberId, title, amount, expireAt);
                granted++;
            } catch (RuntimeException e) {
                // 券表异常不能影响成长值；已扣的库存不回滚（库存扣减与发券同一事务，异常会由调用方事务回滚）
                log.warn("[campus] 给成员 {} 发奶茶券失败：{}", memberId, e.getMessage());
            }
        }
        return granted;
    }

    // ==================== 管理端 ====================

    public Map<String, Object> adminList(int page, int size, Long schoolId, Integer status,
                                         String keyword, Long scopeSchoolId) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        Long filterSchool = scopeSchoolId != null ? scopeSchoolId : schoolId;
        LocalDate monday = mondayOf(LocalDate.now(BEIJING));
        LocalDate sunday = monday.plusDays(6);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        List<Map<String, Object>> rows = mapper.adminList(filterSchool, status, kw, monday, sunday, s, (p - 1) * s);
        long total = mapper.countForAdmin(filterSchool, status, kw);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", rows);
        out.put("total", total);
        out.put("page", p);
        out.put("size", s);
        out.put("weekFrom", monday.toString());
        out.put("weekTo", sunday.toString());
        return out;
    }

    public Map<String, Object> adminDetail(Long dormId, Long scopeSchoolId) {
        Map<String, Object> dorm = mapper.dormById(dormId);
        if (dorm == null) throw new IllegalArgumentException("宿舍不存在");
        if (scopeSchoolId != null && longOf(dorm.get("schoolId")) != null
            && !scopeSchoolId.equals(longOf(dorm.get("schoolId")))) {
            throw new ForbiddenException("无权查看其他学校的宿舍");
        }
        LocalDate today = LocalDate.now(BEIJING);
        List<Map<String, Object>> members = mapper.members(List.of(dormId), today);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dorm", dorm);
        out.put("members", members);
        out.put("week", weekView(dormId));
        return out;
    }

    /**
     * 管理员解散：只改 status=0 + 解除成员关系，历史 dorm_sign 与流水保留（可恢复的误操作）。
     */
    @Transactional
    public void dismiss(Long dormId, Long adminId, String reason, Long scopeSchoolId) {
        String r = trim(reason);
        if (r.isEmpty()) throw new IllegalArgumentException("请填写解散原因");
        if (r.length() > 200) throw new IllegalArgumentException("解散原因最多 200 个字");
        Map<String, Object> dorm = mapper.dormById(dormId);
        if (dorm == null) throw new IllegalArgumentException("宿舍不存在");
        if (scopeSchoolId != null) {
            Long schoolId = longOf(dorm.get("schoolId"));
            if (schoolId == null || !scopeSchoolId.equals(schoolId)) {
                throw new ForbiddenException("无权解散其他学校的宿舍");
            }
        }
        if (num(dorm.get("status")) != 1) throw new IllegalArgumentException("该宿舍已解散");
        if (mapper.dismiss(dormId, adminId, LocalDateTime.now(BEIJING), r) != 1) {
            throw new IllegalArgumentException("该宿舍已解散");
        }
        List<Long> memberIds = mapper.memberIds(dormId);
        mapper.removeAllMembers(dormId);
        // 成员关系解除后用户只剩「未加入」态，靠通知告知「该宿舍已解散」
        for (Long memberId : memberIds) {
            try {
                mapper.notifyDismissed(memberId, "宿舍「" + dorm.get("name") + "」已解散：" + r,
                    "dorm_dismiss:" + dormId);
            } catch (RuntimeException e) {
                log.warn("[campus] 解散通知写入失败 dorm={} user={}：{}", dormId, memberId, e.getMessage());
            }
        }
        log.info("[campus] 管理员 {} 解散宿舍 {}：{}", adminId, dormId, r);
    }

    // ==================== 工具 ====================

    private void requireVerified(Long userId) {
        Map<String, Object> status = verification.status(userId);
        if (!Boolean.TRUE.equals(status.get("verified"))) {
            throw new ForbiddenException("创建宿舍需要先完成学生认证");
        }
    }

    private String newInviteCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        return sb.toString();
    }

    static LocalDate mondayOf(LocalDate date) {
        return date.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    static String weekKey(LocalDate monday) {
        WeekFields iso = WeekFields.ISO;
        int year = monday.get(iso.weekBasedYear());
        int week = monday.get(iso.weekOfWeekBasedYear());
        return String.format("%d-W%02d", year, week);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static int num(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static Long longOf(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return null;
        try { return Long.parseLong(String.valueOf(value).trim()); } catch (NumberFormatException e) { return null; }
    }
}