package com.campuslife.service;

import com.campuslife.mapper.CircleStreakMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 圈子连续活跃（火花）。
 *
 * 规则来自方案 §8.1：
 *   1. 某圈子在一个自然日内只要有**一条有效用户消息**就算活跃；同一天多条不重复计天。
 *   2. 连续满 3 天点亮火花；继续活跃则天数递增；漏一天归零，下次活跃从 1 重新开始。
 *   3. 日期统一按**北京时间**判定，不依赖服务器默认时区，也不依赖用户设备时间。
 *   4. 系统消息、发送失败、审核拒绝的消息不计——调用方只在消息真正落库成功后才会进来。
 *
 * 并发安全：靠 circle_activity_day 的 (circle_id, active_date) 唯一键，
 * 不靠"先查再插"这种有竞态的做法。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CircleStreakService {

    /** 统一时区：北京时间。所有"哪天"的判断都走这里。 */
    public static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    /** 连续多少天点亮火花。 */
    public static final int SPARK_THRESHOLD = 3;

    private final CircleStreakMapper mapper;

    /** 服务端认为的"今天"（北京时间）。 */
    public static LocalDate today() {
        return LocalDate.now(BEIJING);
    }

    /**
     * 记一次活跃。消息落库成功后调用；重复调用安全。
     *
     * @return true 表示今天这个圈子是第一次活跃（也就是连续天数可能 +1）
     */
    @Transactional
    public boolean markActive(Long circleId, Long messageId, Long actorId) {
        if (circleId == null || circleId < 1) return false;
        LocalDate today = today();
        int inserted = mapper.markDay(circleId, today, messageId, actorId);
        if (inserted != 1) {
            // 今天已经记过：同一天多条消息不重复计天
            return false;
        }
        Map<String, Object> row = mapper.streak(circleId);
        if (row == null) return true;

        int previousStreak = intOf(row.get("streakDays"));
        LocalDate lastActive = dateOf(row.get("lastActiveDate"));

        int streak;
        if (lastActive != null && lastActive.equals(today.minusDays(1))) {
            // 昨天也活跃 → 连续 +1
            streak = Math.max(1, previousStreak) + 1;
        } else if (lastActive != null && lastActive.equals(today)) {
            // 理论上到不了这里（今天已记过会提前返回），防御性保留
            streak = Math.max(1, previousStreak);
        } else {
            // 漏了一天（或从未活跃）→ 从 1 重新开始
            streak = 1;
        }
        int sparkOn = streak >= SPARK_THRESHOLD ? 1 : 0;
        mapper.updateStreak(circleId, streak, today, sparkOn);
        log.info("[campus] 圈子 {} 今日活跃，连续 {} 天，火花={}", circleId, streak, sparkOn == 1);
        return true;
    }

    /**
     * 展示用状态。注意这里要**再算一次**：
     * 库里存的 streak_days 是"最后一次活跃时的连续天数"，
     * 如果之后漏了一天，展示必须归零，否则用户会看到一个假的连续记录。
     */
    public Map<String, Object> view(Long circleId, Object streakDays, Object lastActiveDate, Object sparkOn) {
        LocalDate today = today();
        LocalDate last = dateOf(lastActiveDate);
        int stored = intOf(streakDays);
        boolean activeToday = last != null && last.equals(today);
        // 漏了一天（或从未活跃）→ 展示上就断了，不能拿库里的旧值骗人
        boolean broken = last == null || last.isBefore(today.minusDays(1));
        int effective = broken ? 0 : Math.max(1, stored);
        boolean spark = effective >= SPARK_THRESHOLD;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeToday", activeToday);
        out.put("streakDays", effective);
        out.put("sparkOn", spark);
        out.put("lastActiveDate", last == null ? null : last.toString());
        out.put("nextSparkIn", Math.max(0, SPARK_THRESHOLD - effective));
        return out;
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    /** JDBC 可能给 java.sql.Date 或 LocalDate，两种都接受。 */
    private static LocalDate dateOf(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof java.util.Date d) return d.toInstant().atZone(BEIJING).toLocalDate();
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (Exception e) {
            return null;
        }
    }
}
