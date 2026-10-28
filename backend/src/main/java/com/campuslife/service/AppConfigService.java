package com.campuslife.service;

import com.campuslife.mapper.AppConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用运营配置（键值）。
 *
 * 目前主要用于「论文指导咨询入口」：二维码图片、微信号、文案。
 * 单独建表而不是塞进 ai_chat_config —— 那张表语义是 AI 话术，混用会越用越乱。
 *
 * 读多写极少，所以本地缓存 30 秒；管理端改动后主动失效，做到立即生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppConfigService {

    private static final long TTL_MS = 30_000L;

    private final AppConfigMapper mapper;

    private record Entry(String value, long at) {}

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** 对外暴露的项（小程序公开读）。注意：*.enabled 不在这里，它只以 enabled 字段对外。 */
    private static final List<String> PUBLIC_KEYS = List.of(
        "contact.wechat_qr", "contact.wechat_id", "contact.title", "contact.hint");

    /** 微信群入口对外暴露的项。 */
    private static final List<String> PUBLIC_GROUP_KEYS = List.of(
        "group.wechat_qr", "group.title", "group.hint");

    /** 管理端可写的项。比对外项多一个开关，因为开关不需要原样对外。 */
    private static final List<String> WRITABLE_KEYS = List.of(
        "contact.wechat_qr", "contact.wechat_id", "contact.title", "contact.hint", "contact.enabled",
        "group.wechat_qr", "group.title", "group.hint", "group.enabled");

    public String get(String key) {
        long now = System.currentTimeMillis();
        Entry cached = cache.get(key);
        if (cached != null && now - cached.at() < TTL_MS) return cached.value();
        String value;
        try {
            value = mapper.value(key);
        } catch (Exception e) {
            log.warn("读取配置失败 key={}: {}", key, e.getMessage());
            value = null;
        }
        if (value == null) value = "";
        cache.put(key, new Entry(value, now));
        return value;
    }

    public Map<String, String> publicContact() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : PUBLIC_KEYS) out.put(key, get(key));
        // 默认开启；只有明确写 0 才收起
        out.put("enabled", "0".equals(get("contact.enabled")) ? "0" : "1");
        return out;
    }

    /** 微信群入口配置（小程序公开读）。 */
    public Map<String, String> publicGroup() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : PUBLIC_GROUP_KEYS) out.put(key, get(key));
        out.put("enabled", "0".equals(get("group.enabled")) ? "0" : "1");
        return out;
    }

    /** 管理端：全部配置项（带说明）。 */
    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> rows = mapper.listAll();
        return rows == null ? List.of() : rows;
    }

    /** 管理端：批量更新。只允许改已存在的 key（由 mapper 的 UPDATE 保证）。 */
    public void update(Map<String, Object> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("没有要保存的配置");
        int changed = 0;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String key = e.getKey();
            if (!WRITABLE_KEYS.contains(key)) {
                throw new IllegalArgumentException("不支持的配置项：" + key);
            }
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            if (value.length() > 500) throw new IllegalArgumentException("配置值过长：" + key);
            changed += mapper.update(key, value);
            cache.remove(key);
        }
        if (changed == 0) throw new IllegalArgumentException("配置项不存在，请先执行 module-app-config.sql");
        log.info("[campus] 运营配置已更新 {} 项", changed);
    }

    /** 咨询入口是否开启（供小程序判断要不要显示入口）。 */
    public boolean contactEnabled() {
        return "1".equals(publicContact().get("enabled"));
    }
}



