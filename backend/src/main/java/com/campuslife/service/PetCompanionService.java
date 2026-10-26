package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.mapper.PetCompanionMapper;
import com.campuslife.mapper.PetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 校园搭子：外观 / 衣柜 / 派遣
 *
 * 原则：
 * - 外观只接受明确的部件编号，服务端白名单校验，不信任前端
 * - 积分扣减用带条件的 UPDATE（balance >= cost），杜绝并发扣成负数
 * - 派遣进度以数据库 start_at/end_at 为准，客户端倒计时仅用于展示
 */
@Service
@RequiredArgsConstructor
public class PetCompanionService {

    private final PetCompanionMapper mapper;
    private final PetMapper petMapper;
    private final SecureRandom random = new SecureRandom();

    /**
     * 合法外观取值 —— 与 utils/pet-appearance.js 严格对应。
     * 美术为 Kenney Cube Pets（CC0），物种为图片，色调走 CSS filter，配饰为叠加元素。
     */
    private static final Map<String, Set<String>> VALID = Map.of(
        "species", Set.of("cat", "bunny", "dog", "panda", "fox", "koala", "tiger", "penguin", "chick"),
        "tint", Set.of("natural", "warm", "mint", "rose", "mono"),
        "accessory", Set.of("none", "scarf", "bell", "glasses", "leaf"),
        // 数据库列名是 mood_tag（旧 mood 列已被 int 情绪值占用）
        "moodTag", Set.of("happy", "calm", "hungry", "sleepy")
    );

    private static final Map<String, String> DEFAULT_APPEARANCE = Map.of(
        "species", "cat", "tint", "natural", "accessory", "scarf", "moodTag", "calm"
    );

    /** 对外字段名与数据库列名的映射 */
    private static final Map<String, String> COLUMN = Map.of(
        "species", "species", "tint", "tint",
        "accessory", "accessory", "moodTag", "mood_tag"
    );

    // ==================== 外观 ====================

    /** 读取外观；没领养过宠物返回 null */
    public Map<String, Object> appearance(Long userId) {
        requireUser(userId);
        Map<String, Object> row = mapper.appearance(userId);
        if (row == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : VALID.keySet()) {
            Object v = row.get(COLUMN.get(key));
            out.put(key, v == null || String.valueOf(v).isBlank() ? DEFAULT_APPEARANCE.get(key) : String.valueOf(v));
        }
        return out;
    }

    /** 保存外观：逐项白名单校验，任一非法直接拒绝 */
    @Transactional
    public Map<String, Object> saveAppearance(Long userId, Map<String, Object> body) {
        requireUser(userId);
        if (mapper.appearance(userId) == null) {
            throw new IllegalArgumentException("还没有领养宠物，请先领养");
        }
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : VALID.entrySet()) {
            String key = e.getKey();
            Object raw = body == null ? null : body.get(key);
            // 未传的字段保留默认值，不判错（兼容只改单一项的场景）
            String id = raw == null || String.valueOf(raw).isBlank()
                ? DEFAULT_APPEARANCE.get(key)
                : String.valueOf(raw).trim();
            if (!e.getValue().contains(id)) {
                throw new IllegalArgumentException("不支持的 " + key + "：" + id);
            }
            clean.put(key, id);
        }
        mapper.saveAppearance(userId, clean.get("species"), clean.get("tint"),
            clean.get("accessory"), clean.get("moodTag"));
        return appearance(userId);
    }

    /** 重置为默认外观 */
    @Transactional
    public Map<String, Object> resetAppearance(Long userId) {
        requireUser(userId);
        if (mapper.appearance(userId) == null) throw new IllegalArgumentException("还没有领养宠物，请先领养");
        mapper.saveAppearance(userId, DEFAULT_APPEARANCE.get("species"), DEFAULT_APPEARANCE.get("tint"),
            DEFAULT_APPEARANCE.get("accessory"), DEFAULT_APPEARANCE.get("moodTag"));
        return appearance(userId);
    }

    // ==================== 衣柜 ====================

    /** 物品列表：已拥有 / 已装备 / 未解锁 三态 + 当前积分 */
    public Map<String, Object> wardrobe(Long userId) {
        requireUser(userId);
        List<Map<String, Object>> rows = mapper.items(userId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            boolean owned = number(r.get("owned")) == 1;
            boolean equipped = number(r.get("equipped")) == 1;
            item.put("id", r.get("id"));
            item.put("code", r.get("code"));
            item.put("name", r.get("name"));
            item.put("partId", r.get("partId"));
            item.put("price", number(r.get("price")));
            item.put("description", r.get("description"));
            item.put("owned", owned);
            item.put("equipped", equipped);
            // 状态：owned / equipped / locked —— 前端据此显示不同按钮
            item.put("state", equipped ? "equipped" : (owned ? "owned" : "locked"));
            list.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("balance", points(userId));
        return out;
    }

    private int points(Long userId) {
        Integer b = mapper.balance(userId);
        return b == null ? 0 : b;
    }

    /** 兑换：校验积分 → 扣分 → 写入拥有记录，全程一个事务 */
    @Transactional
    public Map<String, Object> purchase(Long userId, String code) {
        requireUser(userId);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("请指定要兑换的物品");
        Map<String, Object> item = mapper.itemByCode(code);
        if (item == null) throw new IllegalArgumentException("物品不存在或已下架");
        Long itemId = id(item.get("id"));
        int price = number(item.get("price"));

        if (mapper.owned(userId, itemId) != null) throw new IllegalStateException("你已经拥有这件物品");

        mapper.ensurePointsRow(userId);
        if (price > 0) {
            int affected = mapper.deductPoints(userId, price);
            if (affected != 1) {
                throw new IllegalStateException("积分不足，还差 " + (price - points(userId)) + " 分");
            }
            mapper.logPoints(userId, -price, "pet_wardrobe", "兑换宠物装扮：" + item.get("name"));
        }
        try {
            mapper.addOwned(userId, itemId);
        } catch (DuplicateKeyException e) {
            // 并发重复兑换：扣的分回滚由事务保证
            throw new IllegalStateException("你已经拥有这件物品");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("balance", points(userId));
        out.put("itemCode", code);
        return out;
    }

    /** 装备：先全部卸下，再装这一件，并同步到宠物外观 */
    @Transactional
    public Map<String, Object> equip(Long userId, String code) {
        requireUser(userId);
        Map<String, Object> item = mapper.itemByCode(code);
        if (item == null) throw new IllegalArgumentException("物品不存在或已下架");
        Long itemId = id(item.get("id"));
        if (mapper.owned(userId, itemId) == null) throw new ForbiddenException("还没有拥有这件物品，请先兑换");

        mapper.unequipAll(userId);
        mapper.equip(userId, itemId);

        // 同步宠物外观的 accessory 字段，保证首页/衣柜/聊天头像一致
        String partId = String.valueOf(item.get("partId"));
        Map<String, Object> current = appearance(userId);
        if (current == null) throw new IllegalArgumentException("还没有领养宠物，请先领养");
        mapper.saveAppearance(userId,
            String.valueOf(current.get("species")),
            String.valueOf(current.get("tint")),
            VALID.get("accessory").contains(partId) ? partId : "none",
            String.valueOf(current.get("moodTag")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("equipped", code);
        out.put("appearance", appearance(userId));
        return out;
    }

    /** 卸下当前配饰 */
    @Transactional
    public Map<String, Object> unequip(Long userId) {
        requireUser(userId);
        mapper.unequipAll(userId);
        Map<String, Object> current = appearance(userId);
        if (current != null) {
            mapper.saveAppearance(userId,
                String.valueOf(current.get("species")), String.valueOf(current.get("tint")),
                "none", String.valueOf(current.get("moodTag")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("appearance", appearance(userId));
        return out;
    }

    // ==================== 派遣 ====================

    public Map<String, Object> tasks() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", mapper.tasks());
        return out;
    }

    /** 派遣状态：以服务端时间为准。每次查询先刷新到期的 running → claimable */
    public Map<String, Object> dispatchStatus(Long userId) {
        requireUser(userId);
        mapper.markDue();
        Map<String, Object> latest = mapper.latestDispatch(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskList", mapper.tasks());
        out.put("claimedCount", mapper.claimedCount(userId));
        if (latest == null) {
            out.put("current", null);
            return out;
        }
        long remain = number(latest.get("remainSeconds"));
        String status = String.valueOf(latest.get("status"));
        // 双重兜底：即使 markDue 没跑到，也按时间判断
        if ("running".equals(status) && remain <= 0) status = "claimable";
        latest.put("status", status);
        latest.put("remainSeconds", Math.max(0, remain));
        latest.put("canClaim", "claimable".equals(status));
        out.put("current", latest);
        return out;
    }

    /** 开始派遣：一个用户同时只允许一个进行中/待领取的任务 */
    @Transactional
    public Map<String, Object> startDispatch(Long userId, String code) {
        requireUser(userId);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("请选择要派遣的任务");
        if (mapper.appearance(userId) == null) throw new IllegalArgumentException("还没有领养宠物，请先领养");
        if (mapper.activeCount(userId) > 0) throw new IllegalStateException("小云已经在路上了，先等等它回来");

        Map<String, Object> task = mapper.taskByCode(code);
        if (task == null) throw new IllegalArgumentException("任务不存在或已下架");
        int minutes = number(task.get("durationMin"));
        if (minutes <= 0) throw new IllegalArgumentException("任务时长配置异常");

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("userId", userId);
        row.put("taskId", number(task.get("id")));
        row.put("startAt", now);
        row.put("endAt", now.plusMinutes(minutes));
        mapper.startDispatch(row);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("dispatchId", row.get("id"));
        return dispatchStatus(userId);
    }

    /** 领取奖励：幂等 —— 只能领一次，重复领取返回明确错误 */
    @Transactional
    public Map<String, Object> claimDispatch(Long userId, Long dispatchId) {
        requireUser(userId);
        if (dispatchId == null || dispatchId < 1) throw new IllegalArgumentException("派遣编号无效");
        mapper.markDue();

        Map<String, Object> row = mapper.dispatch(userId, dispatchId);
        if (row == null) throw new ForbiddenException("派遣记录不存在或不属于你");

        String status = String.valueOf(row.get("status"));
        if ("claimed".equals(status)) throw new IllegalStateException("这份奖励已经领取过了");

        Map<String, Object> latest = mapper.latestDispatch(userId);
        int min = latest == null ? 0 : number(latest.get("rewardMin"));
        int max = latest == null ? 0 : number(latest.get("rewardMax"));
        if (max < min) { int t = min; min = max; max = t; }
        int reward = min >= max ? min : min + random.nextInt(max - min + 1);

        int affected = mapper.claim(userId, dispatchId, reward);
        if (affected != 1) {
            // 时间未到 或 并发重复领取
            throw new IllegalStateException("还不能领取：任务未完成或已被领取");
        }
        mapper.ensurePointsRow(userId);
        mapper.addPoints(userId, reward);
        mapper.logPoints(userId, reward, "pet_dispatch", "派遣任务奖励");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("reward", reward);
        out.put("balance", points(userId));
        return out;
    }

    // ==================== 工具 ====================

    private static void requireUser(Long userId) {
        if (userId == null || userId < 1) throw new IllegalArgumentException("用户编号无效");
    }

    private static int number(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return 0; }
    }

    private static Long id(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }
}
