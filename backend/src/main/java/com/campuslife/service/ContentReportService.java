package com.campuslife.service;

import com.campuslife.mapper.ContentReportMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内容举报。
 *
 * 定位：给用户一个「发现问题能反馈」的出口，举报进管理端队列由人判断，
 * **不自动下架** —— 自动下架容易被恶意举报利用。
 *
 * 防刷：同一个人对同一条内容只能举报一次（数据库唯一键兜底），
 * 重复举报返回可读提示而不是 500。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentReportService {

    /** 允许举报的对象类型，白名单避免前端传任意表名。 */
    private static final Set<String> TYPES = Set.of("post", "comment", "goods", "message", "user");
    /** 举报原因白名单。 */
    private static final Set<String> REASONS = Set.of("广告", "辱骂", "色情", "诈骗", "侵权", "其他");

    private final ContentReportMapper mapper;

    public Map<String, Object> submit(Long reporterId, String targetType, Long targetId, String reason, String detail) {
        String type = targetType == null ? "" : targetType.trim();
        if (!TYPES.contains(type)) throw new IllegalArgumentException("不支持的举报类型");
        if (targetId == null || targetId < 1) throw new IllegalArgumentException("举报对象无效");
        String why = reason == null ? "" : reason.trim();
        if (!REASONS.contains(why)) throw new IllegalArgumentException("请选择举报原因");
        String text = detail == null ? "" : detail.trim();
        if (text.length() > 200) throw new IllegalArgumentException("补充说明不能超过 200 字");

        if (mapper.findByTargetAndReporter(type, targetId, reporterId) != null) {
            throw new IllegalStateException("你已经举报过这条内容，我们正在处理");
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("targetType", type);
        row.put("targetId", targetId);
        row.put("reporterId", reporterId);
        row.put("reason", why);
        row.put("detail", text);
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发下两个请求同时提交，唯一键兜住
            throw new IllegalStateException("你已经举报过这条内容，我们正在处理");
        }
        log.info("[campus] 收到举报 用户={} 类型={} 对象={} 原因={}", reporterId, type, targetId, why);
        return Map.of("reported", true);
    }

    public Map<String, Object> list(String status, String targetType, int page, int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        int safePage = Math.max(1, page);
        List<Map<String, Object>> rows = mapper.list(blank(status), blank(targetType), safeSize, (safePage - 1) * safeSize);
        long total = mapper.count(blank(status), blank(targetType));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", rows);
        out.put("total", total);
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    /** 处理举报：handled=已处理（内容已被下架等），ignored=判定不成立。 */
    public void handle(Long id, String action, String operator) {
        Map<String, Object> brief = mapper.brief(id);
        if (brief == null) throw new IllegalArgumentException("举报记录不存在");
        if (!"pending".equals(String.valueOf(brief.get("status")))) throw new IllegalStateException("该举报已处理过");
        String next = "ignored".equals(action) ? "ignored" : "handled";
        if (mapper.handle(id, next, operator == null ? "" : operator) != 1) {
            throw new IllegalStateException("该举报已处理过");
        }
    }

    public long countPending() {
        try { return mapper.countPending(); } catch (Exception e) { return 0; }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
