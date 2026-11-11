package com.campuslife.service;

import com.campuslife.mapper.TierMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 段位与段位特权。
 *
 * 两条不可动摇的规则：
 *   1. 段位**只由成长值推导**，不存 user.tier。存字段就要维护「什么时候升级」，
 *      一旦漏写就会出现「G 到了、段位没升」；推导永远一致。
 *   2. 特权判定统一走 effectiveTier()：先按 G 推导，再与限时体验段位取较高者。
 *      业务侧不允许各自读 user_growth 判断，否则「显示白银但享受不到白银特权」。
 *
 * 配置读多写极少，缓存 30 秒；V3.1 不提供后台改门槛（只读），
 * 但仍保留 evict()，将来放开写时不用改调用方。
 */
@Slf4j
@Service
public class TierService {

    private static final long CACHE_TTL_MS = 30_000L;

    /** 体验段位权限键前缀：user_privilege.priv_key = tier_trial_silver。 */
    public static final String TRIAL_PREFIX = "tier_trial_";

    public static final String BRONZE = "bronze";
    public static final String SILVER = "silver";
    public static final String GOLD = "gold";

    private record Tier(String code, String name, int threshold, boolean enabled, int sort) {}

    private record Config(List<Tier> tiers, List<Tier> enabled, long at) {}

    private final TierMapper mapper;
    private final FeatureFlags flags;
    /**
     * 时间源：生产用系统时钟，测试注入固定时钟。
     *
     * 两个必须由它决定的地方：
     *   1. 体验段位到期比较（原来交给 SQL 的 NOW()，跨时区/不可测）；
     *   2. 段位配置缓存 30 秒 TTL（原来用 System.currentTimeMillis()，测试只能真等）。
     * 运行时行为与原实现一致，只是把「取当前时间」收敛成一处，可被测试替换。
     */
    private final Clock clock;

    /**
     * 构造函数：Spring 注入前两个依赖；clock 标 @Nullable，容器里没有 Clock bean 时注入 null，
     * 回落系统时钟（生产行为）。测试传固定时钟验证到期边界与 30 秒 TTL。
     */
    public TierService(TierMapper mapper, FeatureFlags flags, @Nullable Clock clock) {
        this.mapper = mapper;
        this.flags = flags;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    private volatile Config cache;

    public void evict() { cache = null; }

    private Config config() {
        Config c = cache;
        long now = clock.millis();
        if (c != null && now - c.at() < CACHE_TTL_MS) return c;
        List<Tier> all = new ArrayList<>();
        for (Map<String, Object> row : mapper.tiers()) {
            all.add(new Tier(
                String.valueOf(row.get("tierCode")),
                String.valueOf(row.get("name")),
                num(row.get("gThreshold")),
                num(row.get("enabled")) == 1,
                num(row.get("sort"))));
        }
        List<Tier> enabled = all.stream().filter(Tier::enabled).toList();
        Config fresh = new Config(all, enabled, now);
        cache = fresh;
        return fresh;
    }

    // ==================== 推导 ====================

    /** 按成长值推导当前段位（含进度与下一段位），供 /api/points/me 使用。 */
    public Map<String, Object> forGrowth(int g) {
        Config c = config();
        List<Tier> tiers = c.enabled();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gTotal", g);
        if (tiers.isEmpty()) {
            out.put("code", BRONZE);
            out.put("name", "青铜·萌新");
            out.put("gThreshold", 0);
            out.put("nextCode", null);
            out.put("nextName", null);
            out.put("nextThreshold", null);
            out.put("percent", 100);
            out.put("maxOpen", true);
            return out;
        }
        Tier current = tiers.get(0);
        Tier next = null;
        for (Tier t : tiers) {
            if (t.threshold() <= g) current = t;
            else { next = t; break; }
        }
        int percent;
        if (next == null) {
            percent = 100;
        } else {
            int span = next.threshold() - current.threshold();
            percent = span <= 0 ? 100
                : (int) Math.floor((g - current.threshold()) * 100.0 / span);
            percent = Math.max(0, Math.min(100, percent));
        }
        out.put("code", current.code());
        out.put("name", current.name());
        out.put("gThreshold", current.threshold());
        out.put("nextCode", next == null ? null : next.code());
        out.put("nextName", next == null ? null : next.name());
        out.put("nextThreshold", next == null ? null : next.threshold());
        out.put("percent", percent);
        out.put("maxOpen", next == null);
        return out;
    }

    public Map<String, Object> forUser(Long userId) {
        return forGrowth(growthOf(userId));
    }

    public int growthOf(Long userId) {
        Integer g = mapper.growth(userId);
        return g == null ? 0 : g;
    }

    /** 只取段位代码。 */
    public String tierCodeForGrowth(int g) {
        return String.valueOf(forGrowth(g).get("code"));
    }

    /** 段位等级序号（青铜=1…），用于比较高低；未知返回 0。 */
    public int rankOf(String code) {
        if (code == null || code.isBlank()) return 0;
        int index = 1;
        for (Tier t : config().tiers()) {
            if (t.code().equals(code)) return index;
            index++;
        }
        return 0;
    }

    /**
     * 生效段位：G 推导出的段位 与 未过期的体验段位 取较高者。
     * 这是所有特权判定的唯一入口。
     */
    public String effectiveTierCode(Long userId) {
        return effectiveTierCode(userId, LocalDateTime.now(clock));
    }

    /** 生效段位（指定时刻）：到期判定用调用方时间，测试可固定「第 8 天」。 */
    private String effectiveTierCode(Long userId, LocalDateTime at) {
        String base = tierCodeForGrowth(growthOf(userId));
        int bestRank = rankOf(base);
        String best = base;
        for (Map<String, Object> p : mapper.activePrivileges(userId, at)) {
            String key = String.valueOf(p.get("privKey"));
            if (!key.startsWith(TRIAL_PREFIX)) continue;
            String code = key.substring(TRIAL_PREFIX.length());
            int rank = rankOf(code);
            if (rank > bestRank) { bestRank = rank; best = code; }
        }
        return best;
    }

    /**
     * 三项 V3.1 特权。开关关闭时对应特权一律 false（入口仍保留，由前端提示）。
     */
    public Map<String, Object> privileges(Long userId) {
        // 只算一次生效段位：一是不重复查 user_privilege（/api/points/me 每次都会走这里），
        // 二是避免两次调用之间恰好有体验特权过期，导致 rank 与返回的 effectiveTierCode 不一致。
        LocalDateTime now = LocalDateTime.now(clock);
        String effective = effectiveTierCode(userId, now);
        int rank = rankOf(effective);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("priorityReply", rank >= rankOf(SILVER));
        out.put("postFastlane", rank >= rankOf(GOLD) && flags.isEnabled("post.fastlane.enabled"));
        out.put("circleCreate", rank >= rankOf(GOLD) && flags.isEnabled("circle.create.enabled"));
        out.put("effectiveTierCode", effective);
        // 体验段位剩余信息：前端提示「白银体验还剩 N 天」用，避免第 8 天突然失去特权
        LocalDateTime trialEnd = null;
        for (Map<String, Object> p : mapper.activePrivileges(userId, now)) {
            String key = String.valueOf(p.get("privKey"));
            if (!key.startsWith(TRIAL_PREFIX)) continue;
            Object expires = p.get("expiresAt");
            if (expires instanceof LocalDateTime dt && (trialEnd == null || dt.isAfter(trialEnd))) trialEnd = dt;
        }
        out.put("trialExpiresAt", trialEnd);
        return out;
    }

    /** 发放体验段位（功能 6 新人礼包调用）。 */
    public void grantTrial(Long userId, String tierCode, int days) {
        if (userId == null || userId < 1) throw new IllegalArgumentException("用户无效");
        if (rankOf(tierCode) == 0) throw new IllegalArgumentException("段位不存在：" + tierCode);
        if (days < 1 || days > 365) throw new IllegalArgumentException("体验天数不合法");
        mapper.grant(userId, TRIAL_PREFIX + tierCode, LocalDateTime.now(clock).plusDays(days));
        log.info("[campus] 用户 {} 获得 {} 体验 {} 天", userId, tierCode, days);
    }

    // ==================== 管理端（只读）====================

    public List<Map<String, Object>> configForAdmin() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tier t : config().tiers()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", t.code());
            row.put("name", t.name());
            row.put("gThreshold", t.threshold());
            row.put("enabled", t.enabled());
            row.put("sort", t.sort());
            row.put("privilegeSummary", switch (t.code()) {
                case SILVER -> "快速应答";
                case GOLD -> "发帖免审 + 创建圈子";
                default -> t.enabled() ? "—" : "未开放";
            });
            out.add(row);
        }
        return out;
    }

    private static int num(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return 0;
        try { return Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException e) { return 0; }
    }
}