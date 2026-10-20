package com.campuslife.service;

import com.campuslife.mapper.SearchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 全站搜索：帖子 / 圈子 / 二手商品 / 活动 / 兼职 / 外卖店铺 / 外卖商品。
@Service @RequiredArgsConstructor
public class SearchService {
    private final SearchMapper mapper;

    // 支持的分类，顺序即结果页 tab 顺序。
    // 「外卖」同时包含店铺与店内商品（如搜「奶茶」既要命中店名，也要命中菜名）。
    public static final List<String> TYPES = List.of("all", "post", "circle", "goods", "activity", "job", "food");

    private static String validate(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) throw new IllegalArgumentException("请输入搜索关键词");
        String text = keyword.trim();
        if (text.length() > 64) throw new IllegalArgumentException("关键词不能超过64字");
        return text;
    }

    // 关键字 → LIKE 模式。与前端 keywordMatcher 语义保持一致：
    // % 匹配任意多字符、_ 匹配单字符、\ 转义下一个字符；其余字符（含单引号）按字面处理。
    // 因为走的是 #{like} 参数绑定，这里的转义只影响 LIKE 语义，不涉及 SQL 注入。
    static String likePattern(String keyword) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == '\\' && i + 1 < keyword.length()) {
                out.append(escapeChar(keyword.charAt(++i)));
            } else if (c == '%') {
                out.append('%');
            } else if (c == '_') {
                out.append('_');
            } else {
                out.append(escapeChar(c));
            }
        }
        return "%" + out + "%";
    }

    private static String escapeChar(char c) {
        if (c == '%' || c == '_' || c == '\\') return "\\" + c;
        return String.valueOf(c);
    }

    public Map<String,Object> search(String keyword, Long userId, String type, int page, int size) {
        String text = validate(keyword);
        Long uid = (userId == null || userId < 1) ? 1L : userId;
        String kind = (type == null || type.isBlank()) ? "all" : type.trim().toLowerCase();
        if (!TYPES.contains(kind)) throw new IllegalArgumentException("不支持的搜索类型：" + type);
        String like = likePattern(text);
        int offset = (page - 1) * size;

        Map<String,Object> counts = new LinkedHashMap<>();
        Map<String,Object> raw = mapper.counts(like);
        for (String key : List.of("post", "circle", "goods", "activity", "job", "food")) {
            counts.put(key, number(raw == null ? null : raw.get(key)));
        }

        Map<String,Object> out = new LinkedHashMap<>();
        out.put("keyword", text);
        out.put("type", kind);
        out.put("counts", counts);

        // 单类查询：只查这一类，并返回该类的总数
        if (!"all".equals(kind)) {
            List<Map<String,Object>> list = fetch(kind, like, uid, size, offset);
            out.put("list", list);
            out.put("total", counts.getOrDefault(kind, 0L));
            out.put("hasMore", offset + list.size() < number(counts.get(kind)));
            return out;
        }

        // 混排：各类按权重穿插，保证第一屏每类都有露脸机会。
        // 权重顺序体现「先内容后服务」——帖子与圈子是内容，商品/活动/兼职/外卖是服务。
        List<Map<String,Object>> mixed = new ArrayList<>();
        int per = Math.max(2, size / 3);
        for (String k : List.of("post", "circle", "goods", "activity", "job", "food")) {
            long total = number(counts.get(k));
            if (total <= 0) continue;
            List<Map<String,Object>> rows = fetch(k, like, uid, per, 0);
            for (Map<String,Object> row : rows) {
                row.put("_type", row.containsKey("_type") ? row.get("_type") : k);
                mixed.add(row);
            }
        }
        out.put("list", mixed);
        out.put("total", counts.values().stream().mapToLong(SearchService::number).sum());
        out.put("hasMore", false);
        return out;
    }

    private List<Map<String,Object>> fetch(String kind, String like, Long userId, int limit, int offset) {
        return switch (kind) {
            case "post" -> normalize(mapper.posts(like, userId, limit, offset));
            case "circle" -> {
                List<Map<String,Object>> rows = mapper.circles(like, userId, limit, offset);
                for (Map<String,Object> row : rows) row.put("joined", number(row.get("joined")) > 0);
                yield rows;
            }
            case "goods" -> {
                List<Map<String,Object>> rows = mapper.goods(like, limit, offset);
                for (Map<String,Object> row : rows) row.put("mode", number(row.get("mode")) == 2 ? 2 : 1);
                yield rows;
            }
            case "activity" -> {
                List<Map<String,Object>> rows = mapper.activities(like, userId, limit, offset);
                for (Map<String,Object> row : rows) row.put("enrolledByMe", number(row.get("enrolledByMe")) > 0);
                yield rows;
            }
            case "job" -> mapper.jobs(like, limit, offset);
            // 外卖：店铺与商品合并成一类，各自标注 _type 便于前端区分跳转目标
            case "food" -> {
                List<Map<String,Object>> rows = new ArrayList<>();
                List<Map<String,Object>> shops = normalize(mapper.shops(like, limit, offset));
                for (Map<String,Object> row : shops) {
                    row.put("_type", "shop");
                    rows.add(row);
                }
                List<Map<String,Object>> goods = mapper.shopGoods(like, limit, offset);
                for (Map<String,Object> row : goods) {
                    row.put("_type", "shopGoods");
                    rows.add(row);
                }
                yield rows;
            }
            default -> List.of();
        };
    }

    private static List<Map<String,Object>> normalize(List<Map<String,Object>> rows) {
        for (Map<String,Object> row : rows) {
            if (row.containsKey("liked")) row.put("liked", number(row.get("liked")) > 0);
            if (row.containsKey("enrolledByMe")) row.put("enrolledByMe", number(row.get("enrolledByMe")) > 0);
        }
        return rows;
    }

    private static long number(Object value) {
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException error) { return 0; }
        }
        return 0;
    }
}
