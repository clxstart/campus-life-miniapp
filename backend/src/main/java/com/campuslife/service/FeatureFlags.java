package com.campuslife.service;

import com.campuslife.mapper.FeatureFlagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 功能开关。线上某个功能出问题时，管理员在后台点一下即可关闭，无需改代码或重启。
 *
 * 实现要点：
 *   1. 本地缓存 30 秒 —— 开关是"读多写极少"的数据，每次请求查库没必要；
 *      30 秒的延迟对止血完全够用，也不会让数据库多一份压力。
 *   2. 缓存未命中/查库失败时**默认开启** —— 宁可功能可用，也不要因为一次查询失败
 *      把全站功能关掉（那是把可用性问题变成事故）。
 *   3. 关闭某个开关只影响入口判断，不改数据，随时可以打开恢复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlags {

    private static final long CACHE_TTL_MS = 30_000L;

    private final FeatureFlagMapper mapper;

    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();
    private final AtomicLong loadedAt = new AtomicLong(0);

    /** 开关是否开启。key 不存在或读取失败都按「开启」处理。 */
    public boolean isEnabled(String key) {
        refreshIfStale();
        Boolean value = cache.get(key);
        return value == null || value;
    }

    /**
     * 安全闸门专用：**默认关闭**。
     *
     * 与 {@link #isEnabled} 的取舍刚好相反：isEnabled 面向"功能可用性"，
     * 查不到就当开启，避免一次查询失败把全站功能关掉；
     * 而校外招聘、有偿接单、替课、自由抢单、通用钱包这类涉及资质与资金的能力，
     * 查不到必须当**关闭**处理——多关一次只是少卖一次，多开一次可能违规。
     *
     * 所以：key 不存在 / 从未成功读到过 → false；读库失败 → 沿用上一次成功的值。
     */
    public boolean enabledStrict(String key) {
        refreshIfStale();
        return Boolean.TRUE.equals(cache.get(key));
    }

    /** 关闭时给学生看的提示文案（来自 remark），取不到给一句通用的。 */
    public String disabledMessage(String key, String fallback) {
        refreshIfStale();
        Object remark = remarks().get(key);
        if (remark instanceof String text && !text.isBlank()) return text;
        return fallback == null || fallback.isBlank() ? "该功能暂时维护中，请稍后再试" : fallback;
    }

    /** 管理端修改后立即生效（清缓存）。 */
    public void invalidate() {
        cache.clear();
        remarks().clear();
        loadedAt.set(0);
    }

    /** 全部开关（管理端列表用，不走缓存，保证看到的是最新值）。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = mapper.listAll();
        return rows == null ? List.of() : rows;
    }

    public void update(String key, boolean enabled, String remark, String operator) {
        int changed = mapper.update(key, enabled ? 1 : 0, remark == null ? "" : remark.trim(), operator == null ? "" : operator);
        if (changed != 1) throw new IllegalArgumentException("开关不存在：" + key);
        invalidate();
        log.info("[campus] 功能开关 {} 被 {} 设置为 {}", key, operator, enabled ? "开启" : "关闭");
    }

    // ---- 内部 ----

    private final Map<String, Object> remarkCache = new ConcurrentHashMap<>();

    private Map<String, Object> remarks() {
        return remarkCache;
    }

    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        long last = loadedAt.get();
        if (last != 0 && now - last < CACHE_TTL_MS) return;
        synchronized (this) {
            if (loadedAt.get() != 0 && System.currentTimeMillis() - loadedAt.get() < CACHE_TTL_MS) return;
            try {
                List<Map<String, Object>> rows = mapper.listAll();
                Map<String, Boolean> next = new LinkedHashMap<>();
                Map<String, Object> remarks = new LinkedHashMap<>();
                if (rows != null) {
                    for (Map<String, Object> row : rows) {
                        Object key = row.get("flagKey");
                        if (key == null) continue;
                        Object enabled = row.get("enabled");
                        boolean on = enabled instanceof Number n ? n.intValue() != 0
                            : Boolean.parseBoolean(String.valueOf(enabled));
                        next.put(String.valueOf(key), on);
                        remarks.put(String.valueOf(key), row.get("remark"));
                    }
                }
                cache.clear();
                cache.putAll(next);
                remarkCache.clear();
                remarkCache.putAll(remarks);
                loadedAt.set(System.currentTimeMillis());
            } catch (Exception e) {
                // 查库失败：保持上一次的缓存；从未成功过则视为全开
                log.warn("[campus] 读取功能开关失败，本次按默认（全开）处理: {}", e.getMessage());
                loadedAt.set(System.currentTimeMillis());
            }
        }
    }
}
