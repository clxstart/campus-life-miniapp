package com.campuslife.service;

import com.campuslife.ha.ClusterLock;
import com.campuslife.mapper.ForumRankMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
import java.util.regex.Pattern;

/**
 * 论坛日榜 / 周榜：算分 → 快照 → 发奖 → 通知。
 *
 * 为什么不是「实时排名 + 实时发分」：
 *   1. 实时排名会随互动抖动，用户看到自己第 3 名转眼变第 4 名会投诉；快照是当日最终结果，可复核。
 *   2. 实时发分无法回答「这笔分是哪一天的榜单给的」，也没法做幂等 —— 发帖被删一次就重算一次。
 *   所以先落 forum_rank 快照，再按快照逐行发奖，重结算只覆盖分数、不动已发放的奖励。
 *
 * 时区：周期边界一律 Asia/Shanghai 自然日/自然周（与 CircleStreakService.BEIJING、
 * PointsService.SHANGHAI 同一口径）。数据库时间窗由 Java 算好再传进去，不依赖 MySQL 的 CURDATE()，
 * 否则换一台 UTC 服务器整个榜单会错位一天（设计稿「九、风险」里明确点过这个坑）。
 *
 * 幂等：三层兜底 ——
 *   forum_rank 的 uk_rank(board, period_key, user_id)  → 重复结算不会插出第二行
 *   task_log 的 (user_id, task_key, biz_key=periodKey) → 重复发分只算一次
 *   notification 的 (user_id, source_key)              → 重复结算不会重复通知
 * 定时任务另加集群租约锁，多实例部署时只有一个节点真正跑（锁只用来减少重复执行，正确性靠上面三层）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForumRankService {

    /** 统一时区：北京时间。与 CircleStreakService.BEIJING 同源，不要在别处另写一份。 */
    public static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    public static final String DAILY = "daily";
    public static final String WEEKLY = "weekly";

    /** 日榜前 10、周榜前 20（设计稿 A-9）。 */
    public static final int TOP_DAILY = 10;
    public static final int TOP_WEEKLY = 20;

    /** 任务目录里对应的两个任务键。 */
    static final String TASK_DAILY = "rank_daily";
    static final String TASK_WEEKLY = "rank_weekly";

    /** 设计稿给出的默认奖励（目录读不到时兜底，正常情况下以目录为准）。 */
    static final int FALLBACK_DAILY_P = 10, FALLBACK_DAILY_G = 15;
    static final int FALLBACK_WEEKLY_P = 15, FALLBACK_WEEKLY_G = 25;

    /** 租约长度：单轮结算（前 20 行发分）远小于它，正常不会执行中过期。 */
    static final Duration LEASE = Duration.ofMinutes(10);

    /** 周期键格式校验，避免管理端手滑把非法字符串写进快照。 */
    private static final Pattern DAILY_KEY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern WEEKLY_KEY = Pattern.compile("^\\d{4}-W\\d{2}$");

    private final ForumRankMapper mapper;
    private final PointsService points;
    private final ClusterLock clusterLock;

    // ==================== 用户侧 ====================

    /**
     * 榜单页数据。只读快照，不做实时计算。
     *
     * 「今日尚未结算」的处理：当前周期还没有快照时回落到最近一期已结算的周期，
     * settled=false 让前端把顶部说明换成「今日尚未结算，展示昨日榜」。
     */
    public Map<String, Object> board(String type, Long viewerId) {
        String board = boardOf(type);
        String currentKey = periodKeyOf(board, today());
        String latest = mapper.latestPeriod(board);
        boolean currentSettled = mapper.countRows(board, currentKey) > 0;
        String key = currentSettled ? currentKey : latest;

        List<Map<String, Object>> rows = key == null ? List.of() : decorate(mapper.rows(board, key), board);
        Map<String, Object> me = null;
        if (viewerId != null && viewerId > 0 && key != null) {
            Map<String, Object> row = mapper.myRow(board, key, viewerId);
            me = viewMeta(row, board);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", board);
        out.put("periodKey", key == null ? currentKey : key);
        out.put("cycleLabel", cycleLabel(board, key == null ? currentKey : key));
        // 本期是否已经结算过；前端据此决定顶部显示「本榜已于 00:10 结算」还是「今日尚未结算」
        out.put("settled", currentSettled);
        out.put("topN", topOf(board));
        out.put("rows", rows);
        out.put("me", me);
        return out;
    }

    // ==================== 管理端 ====================

    /** 任意周期快照 + 发放状态。periodKey 为空时取最近一期，方便后台打开就有内容。 */
    public Map<String, Object> adminBoard(String type, String periodKey) {
        String board = boardOf(type);
        String key = periodKey == null || periodKey.isBlank() ? mapper.latestPeriod(board) : periodKey.trim();
        if (key == null) {
            return Map.of("type", board, "periodKey", "", "cycleLabel", "", "rows", List.of());
        }
        validatePeriodKey(board, key);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : mapper.adminRows(board, key)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rankNo", intOf(row.get("rankNo")));
            item.put("userId", row.get("userId"));
            item.put("nickname", row.get("nickname"));
            item.put("avatar", row.get("avatar"));
            item.put("score", intOf(row.get("score")));
            item.put("rewardP", rewardOf(board).p());
            item.put("awarded", intOf(row.get("awarded")) == 1);
            item.put("settledAt", row.get("settledAt"));
            rows.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", board);
        out.put("periodKey", key);
        out.put("cycleLabel", cycleLabel(board, key));
        out.put("rows", rows);
        return out;
    }

    /**
     * 手动补跑。定时任务可能因为重启错过 00:10，必须有后台入口；重复执行安全。
     *
     * 只允许结算「已经结束」的周期：结算一个还在进行的周期，分数会随之后几分钟的互动继续变，
     * 等于把一份不稳定结果当成最终结果发出去。
     */
    public Map<String, Object> resettle(String type, String periodKey) {
        String board = boardOf(type);
        if (periodKey == null || periodKey.isBlank()) throw new IllegalArgumentException("请指定要重算的周期");
        String key = periodKey.trim();
        validatePeriodKey(board, key);
        if (!isFinished(board, key)) throw new IllegalArgumentException("该周期还未结束，不能结算");

        Map<String, Object> summary = settle(board, key);
        log.info("[campus] 论坛{}榜手动重结算 {}：上榜 {} 人，本轮发放 {} 人",
            DAILY.equals(board) ? "日" : "周", key, summary.get("rows"), summary.get("awarded"));
        return summary;
    }

    // ==================== 调度 ====================

    /**
     * 每天 00:10 结算：日榜结「昨天」，周榜只在周一结「上一周」。
     *
     * 为什么是 00:10 而不是 23:59：给最后一分钟的互动留出写入与事务提交时间，
     * 23:59:59 发的帖子必须能算进当天（设计稿「三、设计取舍」）。
     */
    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Shanghai")
    public void scheduledSettle() {
        clusterLock.runExclusive("forum-rank-settle", LEASE, this::settleDuePeriods);
    }

    /** 单轮要结的周期；公开给测试与运维脚本调用（不经过锁）。 */
    public void settleDuePeriods() {
        LocalDate yesterday = today().minusDays(1);
        settleQuietly(DAILY, periodKeyOf(DAILY, yesterday));
        // 周一 00:10 的「昨天」是周日，正好属于上一周
        if (today().getDayOfWeek() == DayOfWeek.MONDAY) {
            settleQuietly(WEEKLY, periodKeyOf(WEEKLY, yesterday));
        }
    }

    /** 一个榜算错不能连带另一个榜也不结，所以逐榜吞异常并留错误日志。 */
    private void settleQuietly(String board, String periodKey) {
        try {
            Map<String, Object> summary = settle(board, periodKey);
            log.info("[campus] 论坛{}榜结算 {}：上榜 {} 人，本轮发放 {} 人",
                DAILY.equals(board) ? "日" : "周", periodKey, summary.get("rows"), summary.get("awarded"));
        } catch (RuntimeException e) {
            log.error("[campus] 论坛{}榜结算 {} 失败：{}", DAILY.equals(board) ? "日" : "周", periodKey, e.getMessage(), e);
        }
    }

    // ==================== 结算核心 ====================

    /**
     * 结算一个周期：算分 → 清旧快照（未发奖的）→ 写快照 → 逐行发奖 → 通知。
     *
     * 逐行事务：某一行发奖失败（数据库抖动、任务被停用）不影响其他行，
     * 失败行留 awarded=0，下次重结算继续补。
     */
    public Map<String, Object> settle(String type, String periodKey) {
        String board = boardOf(type);
        validatePeriodKey(board, periodKey);
        LocalDateTime start = windowStart(board, periodKey);
        LocalDateTime end = start.plusDays(DAILY.equals(board) ? 1 : 7);
        int top = topOf(board);

        List<Map<String, Object>> ranking = mapper.scoreBoard(start, end, top);
        // 先清掉本周期还没发奖的旧快照：重结算可能算出更少的人（帖子被删/下架）
        mapper.deleteUnawarded(board, periodKey);

        List<Map<String, Object>> rows = new ArrayList<>(ranking.size());
        int rankNo = 0;
        for (Map<String, Object> row : ranking) {
            Long userId = longOf(row.get("uid"));
            int score = intOf(row.get("score"));
            if (userId == null || userId < 1) continue;
            rankNo++;
            mapper.upsert(board, periodKey, userId, score, rankNo);
            rows.add(Map.of("rankNo", rankNo, "userId", userId, "score", score));
        }

        Reward reward = rewardOf(board);
        int awarded = 0;
        for (Map<String, Object> row : rows) {
            int no = intOf(row.get("rankNo"));
            Long userId = (Long) row.get("userId");
            try {
                if (grant(board, periodKey, userId, no, reward)) awarded++;
            } catch (RuntimeException e) {
                // 单行失败不影响其他行；这一行 awarded 仍为 0，下次重结算补
                log.warn("[campus] 论坛{}榜 {} 第 {} 名（用户 {}）发奖失败，留待补跑：{}",
                    DAILY.equals(board) ? "日" : "周", periodKey, no, userId, e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", board);
        out.put("periodKey", periodKey);
        out.put("cycleLabel", cycleLabel(board, periodKey));
        out.put("rows", rows.size());
        out.put("awarded", awarded);
        return out;
    }

    /** 给一个人发奖 + 通知；返回 true 表示这一行本轮真的发了（或确认之前已发）。 */
    private boolean grant(String board, String periodKey, Long userId, int rankNo, Reward reward) {
        if (reward.p() <= 0 && reward.g() <= 0) {
            // 后台把奖励改成 0：没有可发的东西，但要把状态收掉，避免每次重结算都重试一次
            mapper.markAwarded(board, periodKey, userId);
            return false;
        }
        PointsService.AwardResult result = points.awardCustom(userId,
            DAILY.equals(board) ? TASK_DAILY : TASK_WEEKLY, periodKey, reward.p(), reward.g(),
            "论坛" + (DAILY.equals(board) ? "日" : "周") + "榜第 " + rankNo + " 名");

        switch (result) {
            case AWARDED -> {
                mapper.markAwarded(board, periodKey, userId);
                notify(userId, board, periodKey, rankNo, reward.p());
                return true;
            }
            case ALREADY -> {
                // 分早就发过了，只是快照没置位（例如上次发完分进程被杀）：补状态与通知，不再发分
                mapper.markAwarded(board, periodKey, userId);
                notify(userId, board, periodKey, rankNo, reward.p());
                return true;
            }
            case CAPPED -> {
                // 当日上限命中：task_log 已留痕但没发分。留 awarded=0，第二天重结算还能补。
                log.warn("[campus] 论坛{}榜 {} 用户 {} 命中当日上限，未发放，留待补跑",
                    DAILY.equals(board) ? "日" : "周", periodKey, userId);
                return false;
            }
            case DISABLED -> {
                // 后台把上榜任务关了：属于配置问题，不置位，等重新开启后重结算补发
                log.warn("[campus] 上榜任务已停用（{}），本轮跳过发放", DAILY.equals(board) ? TASK_DAILY : TASK_WEEKLY);
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private void notify(Long userId, String board, String periodKey, int rankNo, int p) {
        String title = "你登上论坛" + (DAILY.equals(board) ? "日" : "周") + "榜第 " + rankNo + " 名";
        String content = (p > 0 ? "+" + p + " 积分已到账" : "奖励已到账");
        try {
            mapper.notify(userId, title, content, "forum_rank:" + board + ":" + periodKey + ":" + userId);
        } catch (RuntimeException e) {
            // 通知失败不能回滚发分：分已经到账，少一条消息不影响账目正确性
            log.warn("[campus] 上榜通知写入失败 user={}：{}", userId, e.getMessage());
        }
    }

    // ==================== 返回值修饰 ====================

    /** 展示行：把快照里的 awarded 0/1 换成前端要的「积分值 / 0」并附布尔标记。 */
    private List<Map<String, Object>> decorate(List<Map<String, Object>> rows, String board) {
        int p = rewardOf(board).p();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rankNo", intOf(row.get("rankNo")));
            item.put("userId", row.get("userId"));
            item.put("nickname", row.get("nickname"));
            item.put("avatar", row.get("avatar"));
            item.put("score", intOf(row.get("score")));
            boolean awarded = intOf(row.get("awarded")) == 1;
            // awarded 给「发放的积分值」（未发为 0），与设计稿返回样例一致；awardedFlag 供前端判断灰字「已发放」
            item.put("awarded", awarded ? p : 0);
            item.put("awardedFlag", awarded);
            out.add(item);
        }
        return out;
    }

    private Map<String, Object> viewMeta(Map<String, Object> row, String board) {
        Map<String, Object> me = new LinkedHashMap<>();
        if (row == null) {
            // 没上榜：不算是「第 0 名」，前端按 listed=false 显示「你还没上榜，发个帖子试试」
            me.put("listed", false);
            me.put("rankNo", 0);
            me.put("score", 0);
            me.put("awarded", 0);
            me.put("awardedFlag", false);
            return me;
        }
        boolean awarded = intOf(row.get("awarded")) == 1;
        me.put("listed", true);
        me.put("rankNo", intOf(row.get("rankNo")));
        me.put("score", intOf(row.get("score")));
        me.put("awarded", awarded ? rewardOf(board).p() : 0);
        me.put("awardedFlag", awarded);
        return me;
    }

    // ==================== 周期计算 ====================

    public static LocalDate today() {
        return LocalDate.now(BEIJING);
    }

    /** 日榜：yyyy-MM-dd；周榜：ISO 周 yyyy-Www（2026-W45）。 */
    public static String periodKeyOf(String board, LocalDate date) {
        if (WEEKLY.equals(board)) {
            WeekFields iso = WeekFields.ISO;
            return String.format("%d-W%02d", date.get(iso.weekBasedYear()), date.get(iso.weekOfWeekBasedYear()));
        }
        return date.toString();
    }

    /** 周期起点的 00:00（北京时间）。日榜 1 天、周榜 7 天。 */
    static LocalDateTime windowStart(String board, String periodKey) {
        if (WEEKLY.equals(board)) return weekMonday(periodKey).atStartOfDay();
        return LocalDate.parse(periodKey).atStartOfDay();
    }

    /** 由 ISO 周键反推周一：当年 1 月 4 日必在 ISO 第 1 周，取它的周一再加 (week-1) 周。 */
    static LocalDate weekMonday(String periodKey) {
        int dash = periodKey.indexOf("-W");
        int year = Integer.parseInt(periodKey.substring(0, dash));
        int week = Integer.parseInt(periodKey.substring(dash + 2));
        LocalDate jan4 = LocalDate.of(year, 1, 4);
        return jan4.with(WeekFields.ISO.dayOfWeek(), 1).plusWeeks(week - 1L);
    }

    static void validatePeriodKey(String board, String periodKey) {
        if (periodKey == null || periodKey.isBlank()) throw new IllegalArgumentException("周期不能为空");
        if (WEEKLY.equals(board)) {
            if (!WEEKLY_KEY.matcher(periodKey).matches()) throw new IllegalArgumentException("周榜周期格式应为 yyyy-Www");
            int week = Integer.parseInt(periodKey.substring(periodKey.indexOf("-W") + 2));
            if (week < 1 || week > 53) throw new IllegalArgumentException("周号必须在 01-53 之间");
        } else if (!DAILY_KEY.matcher(periodKey).matches()) {
            throw new IllegalArgumentException("日榜周期格式应为 yyyy-MM-dd");
        }
        try {
            LocalDate day = WEEKLY.equals(board) ? weekMonday(periodKey) : LocalDate.parse(periodKey);
            if (day.getYear() < 2020) throw new IllegalArgumentException("周期过早");
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("周期不合法：" + periodKey);
        }
    }

    /** 这个周期是否已经完整结束（结算进行中的周期会把抖动当成最终结果）。 */
    static boolean isFinished(String board, String periodKey) {
        LocalDateTime start = windowStart(board, periodKey);
        LocalDateTime end = start.plusDays(DAILY.equals(board) ? 1 : 7);
        return !end.isAfter(LocalDateTime.now(BEIJING));
    }

    static int topOf(String board) {
        return WEEKLY.equals(board) ? TOP_WEEKLY : TOP_DAILY;
    }

    static String cycleLabel(String board, String periodKey) {
        if (WEEKLY.equals(board)) {
            int dash = periodKey.indexOf("-W");
            if (dash < 0) return periodKey;
            return "第 " + Integer.parseInt(periodKey.substring(dash + 2)) + " 周";
        }
        // 11-08：榜单页周期标注就是这个形状
        return periodKey.length() >= 10 ? periodKey.substring(5) : periodKey;
    }

    static String boardOf(String type) {
        String value = type == null ? "" : type.trim().toLowerCase();
        if (DAILY.equals(value) || WEEKLY.equals(value)) return value;
        throw new IllegalArgumentException("榜单类型只能是 daily 或 weekly");
    }

    /** 从任务目录读奖励：后台改 P/G 立即生效，通知文案也就不会和实发对不上。 */
    Reward rewardOf(String board) {
        String taskKey = WEEKLY.equals(board) ? TASK_WEEKLY : TASK_DAILY;
        int fallbackP = WEEKLY.equals(board) ? FALLBACK_WEEKLY_P : FALLBACK_DAILY_P;
        int fallbackG = WEEKLY.equals(board) ? FALLBACK_WEEKLY_G : FALLBACK_DAILY_G;
        try {
            for (Map<String, Object> task : points.catalogForAdmin()) {
                if (!taskKey.equals(String.valueOf(task.get("taskKey")))) continue;
                return new Reward(intOf(task.get("pReward")), intOf(task.get("gReward")));
            }
        } catch (RuntimeException e) {
            log.warn("[campus] 读取任务目录失败，上榜奖励按默认值 {}P/{}G 处理：{}", fallbackP, fallbackG, e.getMessage());
        }
        return new Reward(fallbackP, fallbackG);
    }

    record Reward(int p, int g) {}

    static int intOf(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return 0;
        try { return (int) Double.parseDouble(String.valueOf(value)); } catch (NumberFormatException e) { return 0; }
    }

    static Long longOf(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return null;
        try { return Long.parseLong(String.valueOf(value)); } catch (NumberFormatException e) { return null; }
    }
}