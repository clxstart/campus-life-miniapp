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
        "group.wechat_qr", "group.title", "group.hint", "group.enabled",
        // V3.1 学生认证细则。加键要同时改三处：种子 SQL、这里、前端「认证规则」卡片。
        "verification.require.real_name", "verification.require.student_no",
        "verification.require.college", "verification.require.phone",
        "verification.student_no_pattern", "verification.review_mode", "verification.daily_limit");

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
            validate(key, value);
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

    /**
     * 认证细则的写入校验。
     *
     * 为什么在写入口就拦：这些键组合起来能造出真实的漏洞 ——
     * 「review_mode=auto + 空正则」等于任何学号都通过。读取侧还有一层「空正则禁 auto」的强制兜底
     * （见 VerificationService.effectiveReviewMode），但让后台在保存时就收到明确错误，
     * 比让他保存成功、之后反复怀疑「为什么有人秒过」要好。
     */
    private static void validate(String key, String value) {
        switch (key) {
            case "verification.require.real_name", "verification.require.student_no",
                 "verification.require.college", "verification.require.phone" -> {
                if (!"0".equals(value) && !"1".equals(value)) {
                    throw new IllegalArgumentException(key + " 只能是 0 或 1");
                }
            }
            case "verification.review_mode" -> {
                if (!"manual".equals(value) && !"auto".equals(value)) {
                    throw new IllegalArgumentException("审核方式只能是 manual 或 auto");
                }
            }
            case "verification.student_no_pattern" -> {
                // 允许为空（前端只显示格式提示、后端不预校验），但非空就必须能编译，
                // 否则 apply 时的 matches() 会直接抛异常，变成 500
                if (!value.isEmpty()) {
                    try {
                        java.util.regex.Pattern.compile(value);
                    } catch (java.util.regex.PatternSyntaxException e) {
                        throw new IllegalArgumentException("学号正则无法编译：" + e.getDescription());
                    }
                }
            }
            case "verification.daily_limit" -> {
                try {
                    Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("每日提交次数必须是数字");
                }
            }
            default -> { }
        }
    }
}



