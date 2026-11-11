package com.campuslife.service;

import com.campuslife.mapper.PetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service @RequiredArgsConstructor public class PetService {
    private final PetMapper mapper;
    /** 积分只走 PointsService：宠物自己的加减分（喂食扣分、签到加分）也必须有唯一入口。 */
    private final PointsService points;

    // 升级所需经验：Lv n→n+1 需要 50*n*(n+1)
    private static int needExp(int level) { return level >= 10 ? Integer.MAX_VALUE : 50 * level * (level + 1); }

    /** 查宠物：先做惰性衰减，再算状态 */
    public Map<String, Object> petOf(Long userId) {
        Map<String, Object> pet = mapper.findPet(userId);
        if (pet == null) { mapper.createPet(userId); pet = mapper.findPet(userId); }
        mapper.ensurePoints(userId);

        // 惰性衰减：按小时扣饥饿和心情，不用定时任务
        LocalDateTime now = LocalDateTime.now();
        Object lastDecayObj = pet.get("lastDecayAt");
        LocalDateTime lastDecay = lastDecayObj == null ? now
                : ((LocalDateTime) lastDecayObj);
        long hours = Duration.between(lastDecay, now).toHours();
        if (hours > 0) {
            int hunger = Math.max(0, ((Number) pet.get("hunger")).intValue() - (int) (hours * 3));
            int mood = Math.max(0, ((Number) pet.get("mood")).intValue() - (int) (hours * 2));
            mapper.updateVitals(userId, hunger, mood, now);
            pet.put("hunger", hunger);
            pet.put("mood", mood);
        }

        // 今日互动次数
        int feeds = mapper.countToday(userId, "feed");
        int touches = mapper.countToday(userId, "touch");
        boolean checkedIn = mapper.countToday(userId, "checkin") > 0;

        int level = ((Number) pet.get("level")).intValue();
        int exp = ((Number) pet.get("exp")).intValue();
        int need = needExp(level);

        Map<String, Object> out = new HashMap<>(pet);
        out.remove("lastDecayAt"); out.remove("lastFeedAt"); out.remove("lastTouchAt");
        out.put("expNeed", need == Integer.MAX_VALUE ? 0 : need);
        out.put("expPercent", need == Integer.MAX_VALUE ? 100 : Math.min(100, exp * 100 / need));
        out.put("state", stateOf((Integer) out.get("hunger"), (Integer) out.get("mood")));
        out.put("canFeed", feeds < 5);
        out.put("canTouch", touches < 10);
        out.put("canCheckin", !checkedIn);
        out.put("points", mapper.pointsOf(userId));
        return out;
    }

    private static String stateOf(Integer hunger, Integer mood) {
        var t = LocalDateTime.now();
        if (t.getHour() >= 23 || t.getHour() < 7) return "sleep";
        if (hunger < 30) return "hungry";
        if (mood < 20) return "sleep";
        return "idle";
    }

    /** 通用互动：扣积分（可选）、加经验、加数值、检查升级 */
    public Map<String, Object> interact(Long userId, String action) {
        int cost = 0, expGain = 0, hungerGain = 0, moodGain = 0, pointsGain = 0;
        int maxPerDay = 0;
        switch (action) {
            case "feed" -> { cost = 10; expGain = 15; hungerGain = 30; maxPerDay = 5; }
            case "touch" -> { expGain = 5; moodGain = 10; maxPerDay = 10; }
            case "checkin" -> { expGain = 20; pointsGain = 5; maxPerDay = 1; }
            default -> throw new IllegalArgumentException("未知操作");
        }
        if (mapper.countToday(userId, action) >= maxPerDay)
            throw new IllegalStateException("今天已经做过太多次啦");

        if (cost > 0) {
            // 喂食是可重复消费（每天最多 5 次），所以不带业务幂等键，让每次都是一笔新支出；
            // 扣分不能扣成负数由 spend() 的条件更新保证。
            boolean paid = points.spend(userId, "pet", null, cost, "pet_feed", "喂食消耗");
            if (!paid) throw new IllegalStateException("积分不足，去发帖赚积分吧");
        }
        if (pointsGain > 0) {
            // 宠物签到并入统一任务体系（task_key=pet_checkin），bizKey 用日期天然做到每天一次
            points.award(userId, "pet_checkin", java.time.LocalDate.now(PointsService.SHANGHAI).toString(), "宠物签到");
        }

        Map<String, Object> pet = mapper.findPet(userId);
        if (pet == null) throw new IllegalStateException("宠物不存在");
        int hunger = Math.min(100, ((Number) pet.get("hunger")).intValue() + hungerGain);
        int mood = Math.min(100, ((Number) pet.get("mood")).intValue() + moodGain);
        int level = ((Number) pet.get("level")).intValue();
        int exp = ((Number) pet.get("exp")).intValue() + expGain;
        boolean leveled = false;
        while (level < 10 && exp >= needExp(level)) { exp -= needExp(level); level++; leveled = true; }

        mapper.applyInteract(userId, hunger, mood, level, exp, action, expGain, cost);
        mapper.logInteract(userId, action, expGain, cost);
        Map<String, Object> out = petOf(userId);
        out.put("leveled", leveled);
        return out;
    }

    private static LocalDateTime now() { return LocalDateTime.now(); }

    public void rename(Long userId, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("名字不能为空");
        if (name.length() > 20) throw new IllegalArgumentException("名字最多 20 个字");
        mapper.rename(userId, name.trim());
    }
}
