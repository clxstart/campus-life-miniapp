package com.campuslife.service;

import com.campuslife.mapper.ClientErrorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 前端运行时错误上报。
 *
 * 定位：把「用户那边出错但我们看不到」的情况捞回来。
 * 典型场景：真机白屏、点击无反应、某个机型上的 JS 报错 —— 这些在服务端日志里完全没痕迹。
 *
 * 注意：这是**公开接口**（小程序未登录时也可能崩溃），所以：
 *   - 字段严格限长，避免有人拿它当网盘；
 *   - 调用方限流（控制层做）；
 *   - 只记错误本身，不记用户输入内容。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientErrorService {

    private static final Set<String> CLIENTS = Set.of("miniapp", "web");

    private final ClientErrorMapper mapper;

    public void report(String client, String page, String message, String stack, String userAgent, Long userId) {
        String safeClient = CLIENTS.contains(client) ? client : "web";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("client", safeClient);
        row.put("page", cut(page, 128));
        row.put("message", cut(message, 500));
        row.put("stack", cut(stack, 2000));
        row.put("userAgent", cut(userAgent, 300));
        row.put("userId", userId);
        try {
            mapper.insert(row);
            log.warn("[前端错误][{}] {} :: {}", safeClient, cut(page, 60), cut(message, 200));
        } catch (Exception e) {
            // 上报失败不能影响主流程，也不能再抛错（否则前端会连环报错）
            log.debug("前端错误上报落库失败: {}", e.getMessage());
        }
    }

    public Map<String, Object> list(String client, String keyword, int page, int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        int safePage = Math.max(1, page);
        String kw = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        List<Map<String, Object>> rows = mapper.list(blank(client), kw, safeSize, (safePage - 1) * safeSize);
        long total = mapper.count(blank(client), kw);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", rows);
        out.put("total", total);
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    public long countToday() {
        try { return mapper.countToday(); } catch (Exception e) { return 0; }
    }

    /** 清理旧记录（默认 30 天）。 */
    public int purge(int days) {
        int safe = Math.min(365, Math.max(1, days));
        return mapper.purgeOlderThan(safe);
    }

    private static String cut(String value, int max) {
        if (value == null) return "";
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
