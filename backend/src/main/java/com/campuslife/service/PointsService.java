package com.campuslife.service;

import com.campuslife.mapper.PointsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 积分与成长值的**唯一发放口**。
 *
 * 为什么必须只有一个口：V3.1 有十余个发分场景（签到、发帖、外卖、榜单、宿舍、新人礼包…），
 * 每个场景各写一套「查余额 → 加 → 写日志」的话，迟早会出现余额与流水对不上，
 * 而且没有任何一处能拦住刷分。这里把「幂等 + 日上限 + 余额 + 成长值 + 流水」五件事
 * 收在一次调用里，业务侧只负责决定「该不该发」和「业务号是什么」。
 *
 * 三个入口，各有明确边界：
 *   award()        —— 固定奖励，P/G 由 task_catalog 决定（后台改了立即生效）
 *   awardCustom()  —— 变额奖励（派遣、周榜），P/G 由调用方给，目录只管开关与日上限
 *   spend()        —— 扣分（喂食、衣柜），不写 task_log，靠 point_log 的唯一键幂等
 *
 * 事务：发分与写流水同事务，保证「发帖成功 ⇄ 加分」强一致，不做异步队列。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsService {

    /** 日上限与「今天」的口径一律按北京时间，服务器时区无关。 */
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 100 积分 = 1 元，仅作展示换算，不参与任何结算。 */
    private static final int POINTS_PER_YUAN = 100;

    private static final long CATALOG_TTL_MS = 30_000L;

    public enum AwardResult {
        /** 已发放 */
        AWARDED,
        /** 同一 bizKey 已发过，幂等命中 */
        ALREADY,
        /** 当日次数已达上限 */
        CAPPED,
        /** 任务不存在或已停用 */
        DISABLED,
        /** 本次调用不构成发放（例如 P=G=0），不写任何数据 */
        NOOP
    }

    private final PointsMapper mapper;

    // ---- 任务目录缓存：管理端改完 30 秒内生效，读多写极少 ----
    private record Catalog(Map<String, Map<String, Object>> byKey, List<Map<String, Object>> list, long at) {}

    private volatile Catalog catalogCache;

    private Catalog catalog() {
        Catalog c = catalogCache;
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < CATALOG_TTL_MS) return c;
        List<Map<String, Object>> list = mapper.taskCatalog();
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> row : list) byKey.put(String.valueOf(row.get("taskKey")), row);
        Catalog fresh = new Catalog(byKey, list, now);
        catalogCache = fresh;
        return fresh;
    }

    /** 管理端保存后主动失效，不用等 30 秒。 */
    public void evictCatalog() { catalogCache = null; }

    // ==================== 发放 ====================

    /** 固定奖励：P/G 以 task_catalog 为准。 */
    @Transactional
    public AwardResult award(Long userId, String taskKey, String bizKey, String remark) {
        return doAward(userId, taskKey, bizKey, null, null, remark);
    }

    /** 变额奖励：P/G 由调用方给，目录只负责开关与日上限。 */
    @Transactional
    public AwardResult awardCustom(Long userId, String taskKey, String bizKey, int p, int g, String remark) {
        return doAward(userId, taskKey, bizKey, p, g, remark);
    }

    private AwardResult doAward(Long userId, String taskKey, String bizKey,
                                Integer overrideP, Integer overrideG, String remark) {
        if (userId == null || userId < 1) throw new IllegalArgumentException("用户无效");
        if (taskKey == null || taskKey.isBlank()) throw new IllegalArgumentException("任务键无效");

        Map<String, Object> task = catalog().byKey().get(taskKey);
        if (task == null || num(task.get("enabled")) != 1) return AwardResult.DISABLED;

        int p = overrideP != null ? overrideP : num(task.get("pReward"));
        int g = overrideG != null ? overrideG : num(task.get("gReward"));
        if (p == 0 && g == 0) return AwardResult.NOOP;

        int cap = num(task.get("dailyCap"));
        String module = String.valueOf(task.get("module"));
        String bizKeyValue = (bizKey == null || bizKey.isBlank()) ? taskKey : bizKey.trim();
        LocalDate bizDate = LocalDate.now(SHANGHAI);

        // 幂等靠 task_log 的唯一键，先插事实再动余额：并发下只有一个请求能插进去
        try {
            mapper.insertTaskLog(userId, taskKey, bizDate, bizKeyValue, p, g);
        } catch (DuplicateKeyException e) {
            return AwardResult.ALREADY;
        }

        // 日上限：插入后再数，这样上限值本身也把本次算进去
        if (cap > 0 && mapper.countTaskToday(userId, taskKey, bizDate) > cap) {
            // 留痕但不发分；同一 bizKey 之后也不会重试，避免刷上限的人反复试探
            return AwardResult.CAPPED;
        }

        mapper.ensurePointsRow(userId);
        mapper.ensureGrowthRow(userId);
        if (p != 0) mapper.addPoints(userId, p, p);
        if (g != 0) mapper.addGrowth(userId, g);
        int after = mapper.balance(userId);
        mapper.insertLog(userId, p, taskKey, null, remark == null ? "" : remark, module,
            taskKey + ":" + bizKeyValue, g, after);
        return AwardResult.AWARDED;
    }

    // ==================== 扣分 ====================

    /**
     * 扣分（喂食、衣柜兑换）。余额不足返回 false，绝不扣成负数。
     *
     * 不写 task_log：扣分不是"任务达成"，写进去会污染日上限计数。
     * 幂等靠 point_log 的 (user_id, biz_key) 唯一键。
     */
    @Transactional
    public boolean spend(Long userId, String module, String bizKey, int cost, String type, String remark) {
        if (userId == null || userId < 1) throw new IllegalArgumentException("用户无效");
        if (cost <= 0) return true;
        // bizKey 为空表示"这是可重复消费，不需要业务幂等"（例如喂食），
        // 用随机号占位，既满足唯一索引，又不会把第二次消费挡掉。
        String bizNo = (bizKey == null || bizKey.isBlank())
            ? "auto:" + java.util.UUID.randomUUID()
            : bizKey.trim();
        String key = module + ":" + bizNo;
        mapper.ensurePointsRow(userId);
        int rows = mapper.deductPoints(userId, cost);
        if (rows != 1) return false;
        int after = mapper.balance(userId);
        try {
            mapper.insertLog(userId, -cost, type, null, remark == null ? "" : remark, module, key, 0, after);
        } catch (DuplicateKeyException e) {
            // 同一业务号重复扣分：事务回滚，余额不会被二次扣
            throw new IllegalStateException("这笔操作已经处理过了");
        }
        return true;
    }

    // ==================== 任务目录（供签到/特权等业务判定开关）====================

    /** 任务是否启用。业务侧在写事实之前先问这一句，避免关闭期间留下"已完成"的假记录。 */
    public boolean taskEnabled(String taskKey) {
        if (taskKey == null) return false;
        Map<String, Object> task = catalog().byKey().get(taskKey);
        return task != null && num(task.get("enabled")) == 1;
    }

    /** 任务当前配置（P/G/日上限）。不存在返回 null。 */
    public Map<String, Object> taskReward(String taskKey) {
        if (taskKey == null) return null;
        Map<String, Object> task = catalog().byKey().get(taskKey);
        if (task == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskKey", taskKey);
        out.put("name", task.get("name"));
        out.put("p", num(task.get("pReward")));
        out.put("g", num(task.get("gReward")));
        out.put("dailyCap", num(task.get("dailyCap")));
        out.put("enabled", num(task.get("enabled")) == 1);
        return out;
    }

    /** 当前余额（内部使用；对外一律走 /api/points/me）。 */
    public int balance(Long userId) {
        return mapper.balance(userId);
    }

    // ==================== 查询 ====================

    public Map<String, Object> me(Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        int balance = mapper.balance(userId);
        int total = mapper.totalEarned(userId);
        int growth = mapper.growth(userId);
        out.put("balance", balance);
        out.put("totalEarned", total);
        out.put("gTotal", growth);
        out.put("valueYuan", yuan(balance));
        out.put("pointsPerYuan", POINTS_PER_YUAN);
        return out;
    }

    /** 任务目录 + 今日完成次数。一次查完今日计数，不做 N+1。 */
    public List<Map<String, Object>> tasks(Long userId) {
        Catalog c = catalog();
        Map<String, Integer> done = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.taskDoneToday(userId, LocalDate.now(SHANGHAI))) {
            done.put(String.valueOf(row.get("taskKey")), num(row.get("done")));
        }
        List<Map<String, Object>> out = new ArrayList<>(c.list().size());
        for (Map<String, Object> row : c.list()) {
            if (num(row.get("enabled")) != 1) continue;
            String key = String.valueOf(row.get("taskKey"));
            int cap = num(row.get("dailyCap"));
            int todayDone = done.getOrDefault(key, 0);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskKey", key);
            item.put("module", row.get("module"));
            item.put("name", row.get("name"));
            item.put("p", num(row.get("pReward")));
            item.put("g", num(row.get("gReward")));
            item.put("dailyCap", cap);
            item.put("doneToday", todayDone);
            item.put("available", cap <= 0 || todayDone < cap);
            out.add(item);
        }
        return out;
    }

    public Map<String, Object> logs(Long userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        long offset = Math.min(Integer.MAX_VALUE - safeSize, (long) (safePage - 1) * safeSize);
        List<Map<String, Object>> rows = mapper.logs(userId, safeSize, (int) offset);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", rows);
        out.put("page", safePage);
        out.put("size", safeSize);
        out.put("total", mapper.countLogs(userId));
        return out;
    }

    // ==================== 管理端 ====================

    public List<Map<String, Object>> catalogForAdmin() { return catalog().list(); }

    @Transactional
    public void updateTask(String taskKey, Integer p, Integer g, Integer dailyCap, Boolean enabled) {
        if (taskKey == null || taskKey.isBlank()) throw new IllegalArgumentException("任务键无效");
        Map<String, Object> current = catalog().byKey().get(taskKey);
        if (current == null) throw new IllegalArgumentException("任务不存在：" + taskKey);
        int newP = p == null ? num(current.get("pReward")) : p;
        int newG = g == null ? num(current.get("gReward")) : g;
        int newCap = dailyCap == null ? num(current.get("dailyCap")) : dailyCap;
        int newEnabled = enabled == null ? num(current.get("enabled")) : (enabled ? 1 : 0);
        if (newP < 0 || newG < 0) throw new IllegalArgumentException("P/G 不能为负");
        if (newCap < 0) throw new IllegalArgumentException("日上限不能为负");
        if (newP > 1000 || newG > 1000) throw new IllegalArgumentException("单次奖励超过上限，请核对");
        if (mapper.updateTask(taskKey, newP, newG, newCap, newEnabled) != 1) {
            throw new IllegalArgumentException("任务不存在：" + taskKey);
        }
        evictCatalog();
        log.info("[campus] 任务配置已更新 {} p={} g={} cap={} enabled={}", taskKey, newP, newG, newCap, newEnabled);
    }

    // ==================== 工具 ====================

    /** 100P = 1 元，两位小数，仅作展示。 */
    public static String yuan(int points) {
        return BigDecimal.valueOf(points).divide(BigDecimal.valueOf(POINTS_PER_YUAN), 2, RoundingMode.DOWN).toPlainString();
    }

    /** 对外暴露的数值转换：其他域拼装返回体时复用同一口径。 */
    public static int number(Object value) { return num(value); }

    static int num(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return 0;
        try { return Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException e) { return 0; }
    }
}