package com.campuslife.service;

import com.campuslife.mapper.SignMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.lang.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日签到与连签。
 *
 * 设计要点：
 *   1. 「今天」一律按 Asia/Shanghai 计算，服务器时区无关（与 CircleStreakService 同一写法）。
 *   2. 幂等靠 sign_roll 主键，不靠客户端状态：同一天重复请求只会有一次奖励。
 *   3. 连签靠「昨天那行是否存在」推算，客户端传什么都不用。
 *   4. 里程碑（3/5/7/15/20/30 天）用 sign_streak_N + 当天日期做幂等键：
 *      断签后重新连到 7 天应该再给一次，否则用户没有重新开始的理由。
 *   5. 签到、连签回写、发分在同一事务；宿舍等附加判定在提交后执行，失败不影响签到。
 */
@Slf4j
@Service
public class SignService {

    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    static final String SIGN_TASK = "daily_sign";

    /** 六档连签里程碑，与 task_catalog 的 sign_streak_N 一致。 */
    private static final int[] MILESTONES = {3, 5, 7, 15, 20, 30};

    private final SignMapper mapper;
    private final PointsService points;
    private final List<SignCompletedListener> listeners;
    /**
     * 业务日期的唯一时间源。收敛到一处是为了可测：原来直接 LocalDate.now(SHANGHAI)，
     * 「23:59:59 签一次、次日 00:00:01 再签」这条边界只能靠真等或造昨天行来近似。
     * 容器里没有 Clock bean 时注入 null、回落上海系统时钟，生产行为不变。
     */
    private final Clock clock;

    public SignService(SignMapper mapper, PointsService points, List<SignCompletedListener> listeners,
                       @Nullable Clock clock) {
        this.mapper = mapper;
        this.points = points;
        this.listeners = listeners;
        this.clock = clock == null ? Clock.system(SHANGHAI) : clock;
    }

    /**
     * 签到。返回结构与设计稿一致：
     * { signedToday, streakDays, awarded:[{taskKey,p,g}], balance, nextMilestone, message }
     */
    @Transactional
    public Map<String, Object> sign(Long userId) {
        if (userId == null || userId < 1) throw new IllegalArgumentException("用户无效");
        LocalDate today = LocalDate.now(clock);

        // 开关关闭：不写签到事实，避免"关闭期间也算签过"
        if (!points.taskEnabled(SIGN_TASK)) {
            Map<String, Object> out = status(userId);
            out.put("awarded", List.of());
            out.put("message", "签到暂未开放");
            return out;
        }

        // 直接插入，不先 count：主键 (user_id, biz_date) 本身就是幂等键，多一次查询没有意义。
        boolean first;
        try {
            mapper.insert(userId, today);
            first = true;
        } catch (DuplicateKeyException e) {
            // 并发双击 / 今天已经签过：另一个请求先插入了，本次按"今天已签"返回
            first = false;
        }
        if (!first) {
            // 不能直接调 status()：本事务在检查任务开关时已经读过 task_catalog，
            // 一致性读快照是在那一刻建立的。并发双击的失败方随后用普通 SELECT
            // 读不到对方"后提交"的那一行，会返回 signedToday=false、streakDays=0，
            // 前端按钮重新亮起，用户以为没签上。加锁读拿最新已提交版本，
            // 再显式置 signedToday=true（主键冲突本身就证明这一行存在）。
            Integer todayStreak = mapper.streakOnShared(userId, today);
            int streak = todayStreak == null ? 1 : todayStreak;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("signedToday", true);
            out.put("streakDays", streak);
            out.put("nextMilestone", nextMilestone(streak));
            out.put("awarded", List.of());
            out.put("message", "");
            return out;
        }

        Integer yesterday = mapper.streakOn(userId, today.minusDays(1));
        int streak = yesterday == null ? 1 : yesterday + 1;
        mapper.updateStreak(userId, today, streak);

        List<Map<String, Object>> awarded = new ArrayList<>();
        addAwarded(awarded, SIGN_TASK,
            points.award(userId, SIGN_TASK, today.toString(), "每日签到"));
        if (isMilestone(streak)) {
            String milestoneKey = "sign_streak_" + streak;
            addAwarded(awarded, milestoneKey,
                points.award(userId, milestoneKey, today.toString(), "连续签到 " + streak + " 天"));
        }

        Map<String, Object> out = status(userId);
        out.put("awarded", awarded);
        out.put("balance", points.balance(userId));
        out.put("message", "");
        notifyAfterCommit(userId, today);
        return out;
    }

    /** 只读状态：是否已签、连签天数、下一档里程碑（不产生任何写入）。 */
    public Map<String, Object> status(Long userId) {
        LocalDate today = LocalDate.now(clock);
        Integer todayStreak = mapper.streakOn(userId, today);
        boolean signedToday = todayStreak != null;
        int streak;
        if (signedToday) {
            streak = todayStreak;
        } else {
            Integer yesterday = mapper.streakOn(userId, today.minusDays(1));
            streak = yesterday == null ? 0 : yesterday;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signedToday", signedToday);
        out.put("streakDays", streak);
        out.put("nextMilestone", nextMilestone(streak));
        return out;
    }

    private Map<String, Object> nextMilestone(int streak) {
        for (int days : MILESTONES) {
            if (days <= streak) continue;
            Map<String, Object> reward = points.taskReward("sign_streak_" + days);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("days", days);
            out.put("taskKey", "sign_streak_" + days);
            out.put("p", reward == null ? 0 : PointsService.number(reward.get("p")));
            out.put("g", reward == null ? 0 : PointsService.number(reward.get("g")));
            return out;
        }
        return null;
    }

    private static boolean isMilestone(int streak) {
        for (int m : MILESTONES) if (m == streak) return true;
        return false;
    }

    private void addAwarded(List<Map<String, Object>> awarded, String taskKey,
                            PointsService.AwardResult result) {
        if (result != PointsService.AwardResult.AWARDED) return;
        Map<String, Object> reward = points.taskReward(taskKey);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskKey", taskKey);
        item.put("p", reward == null ? 0 : PointsService.number(reward.get("p")));
        item.put("g", reward == null ? 0 : PointsService.number(reward.get("g")));
        awarded.add(item);
    }

    /** 附加动作放在事务提交后：宿舍判定失败不能把签到一起回滚。 */
    private void notifyAfterCommit(Long userId, LocalDate today) {
        Runnable run = () -> {
            for (SignCompletedListener listener : listeners) {
                try {
                    listener.onSigned(userId, today);
                } catch (Exception e) {
                    log.warn("[campus] 签到附加动作失败 user={} date={}: {}", userId, today, e.getMessage());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { run.run(); }
            });
        } else {
            run.run();
        }
    }
}