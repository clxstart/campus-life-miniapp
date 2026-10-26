package com.campuslife.service;

import com.campuslife.mapper.AiChatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * AI 陪聊（关键词匹配）。
 *
 * 不做大模型：规则存数据库，命中关键词返回预设话术。
 * 部分意图会接真实校园数据（吃什么 / 有什么活动 / 有什么兼职），
 * 这是和「纯话术机器人」的区别 —— 回复里会出现本校真实的店铺和岗位名。
 *
 * 匹配策略：
 *   1. 遍历所有启用规则，看用户消息是否包含任一关键词
 *   2. 命中的规则里按「关键词长度降序 + priority 降序」排序，取第一个
 *      （长关键词更精确，比如「不想学习」应该优先于「学习」）
 *   3. 都没命中 → 用 fallback 规则
 */
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final AiChatMapper mapper;

    /** 消息长度上限，防止超长文本拖慢匹配 */
    private static final int MAX_MESSAGE = 200;

    /**
     * 处理一条用户消息。
     *
     * @param userId  用户 id（用于取学校数据；可为空）
     * @param schoolId 当前选中学校（为空时回落到用户所属学校）
     * @param message 用户消息
     */
    public Map<String, Object> chat(Long userId, Long schoolId, String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("说点什么吧");
        if (text.length() > MAX_MESSAGE) throw new IllegalArgumentException("消息不能超过 " + MAX_MESSAGE + " 字");

        List<Map<String, Object>> rules = mapper.activeRules();
        Match best = findBest(rules, text);

        Map<String, Object> out = new LinkedHashMap<>();
        if (best == null) {
            Map<String, Object> fallback = rules.stream()
                .filter(r -> "fallback".equals(String.valueOf(r.get("category"))))
                .findFirst().orElse(null);
            out.put("reply", fallback != null ? String.valueOf(fallback.get("reply"))
                : "这句话我还没学会怎么接。你可以问我「今天吃什么」「有什么活动」，或者随便聊聊今天过得怎么样。");
            out.put("quickReplies", fallback != null ? splitList(fallback.get("quickReplies"))
                : List.of("今天吃什么", "有什么活动", "陪我聊聊"));
            out.put("matched", false);
            out.put("category", "fallback");
            // 记日志：未命中的问题最有价值，运营据此补规则
            logChat(userId, schoolId, text, false, "fallback", null);
            return out;
        }

        String reply = String.valueOf(best.rule.get("reply"));
        String category = String.valueOf(best.rule.get("category"));

        // 部分意图接真实校园数据，让回复不是死话术
        String enriched = enrichWithCampusData(category, text, userId, schoolId);
        if (enriched != null) reply = enriched;

        out.put("reply", reply);
        out.put("quickReplies", splitList(best.rule.get("quickReplies")));
        out.put("matched", true);
        out.put("category", category);
        logChat(userId, schoolId, text, true, category, toLong(best.rule.get("id")));
        return out;
    }

    /** 记一条对话日志；写失败不影响对话本身 */
    private void logChat(Long userId, Long schoolId, String message, boolean matched,
                         String category, Long ruleId) {
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", userId);
            row.put("schoolId", schoolId);
            row.put("message", message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message);
            row.put("matched", matched ? 1 : 0);
            row.put("category", category);
            row.put("ruleId", ruleId);
            mapper.insertLog(row);
        } catch (Exception ignored) {
            // 日志是辅助功能，不能因为它让对话失败
        }
    }

    /** 所有启用规则（供管理端查看/维护） */
    public List<Map<String, Object>> rules() {
        return mapper.activeRules();
    }

    // ================= 管理端：规则 CRUD =================

    /** 规则里允许的分类（与数据库种子保持一致） */
    private static final Set<String> CATEGORIES =
        Set.of("greeting", "study", "food", "sleep", "mood", "campus", "help", "fallback");

    public List<Map<String, Object>> rulesForAdmin(String category, String keyword) {
        String cat = category == null ? "" : category.trim();
        String kw = keyword == null ? "" : keyword.trim();
        return mapper.rulesForAdmin(cat, kw.isEmpty() ? "" : "%" + kw + "%");
    }

    public Map<String, Object> createRule(Map<String, Object> body) {
        Map<String, Object> row = ruleRow(body);
        mapper.insertRule(row);
        return Map.of("id", row.get("id"));
    }

    public void updateRule(Long id, Map<String, Object> body) {
        if (mapper.ruleBrief(id) == null) throw new IllegalArgumentException("规则不存在");
        Map<String, Object> row = ruleRow(body);
        row.put("id", id);
        mapper.updateRule(row);
    }

    public void setRuleStatus(Long id, int status) {
        if (mapper.ruleBrief(id) == null) throw new IllegalArgumentException("规则不存在");
        mapper.setRuleStatus(id, status == 0 ? 0 : 1);
    }

    public void deleteRule(Long id) {
        if (mapper.ruleBrief(id) == null) throw new IllegalArgumentException("规则不存在");
        mapper.deleteRule(id);
    }

    private static Map<String, Object> ruleRow(Map<String, Object> body) {
        String category = trim(body.get("category"));
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("分类只能是：" + String.join("/", CATEGORIES));
        }
        String keywords = requireText(body.get("keywords"), "关键词", 255);
        String reply = requireText(body.get("reply"), "回复话术", 500);
        // 注意：status 不传时默认「启用」。
        // 不能写成 toInt(...) == 0 ? 0 : 1 —— 不传时 toInt 返回 0，会把新规则默认停用。
        Object rawStatus = body.get("status");
        int status = rawStatus == null ? 1 : (toInt(rawStatus) == 0 ? 0 : 1);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("category", category);
        row.put("keywords", keywords);
        row.put("reply", reply);
        row.put("quickReplies", trim(body.get("quickReplies")));
        row.put("priority", toInt(body.get("priority")));
        row.put("status", status);
        return row;
    }

    private static String requireText(Object value, String label, int max) {
        String text = trim(value);
        if (text.isEmpty()) throw new IllegalArgumentException(label + "不能为空");
        if (text.length() > max) throw new IllegalArgumentException(label + "不能超过 " + max + " 字");
        return text;
    }

    // ================= 管理端：未命中统计 =================

    public Map<String, Object> stats() {
        long total = mapper.totalChats();
        long matched = mapper.matchedChats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("matched", matched);
        out.put("unmatchedCount", total - matched);
        out.put("unmatched", mapper.unmatchedTop(30));
        out.put("byCategory", mapper.chatsByCategory());
        return out;
    }

    // ================= 话术配置 =================

    /** 欢迎语（小程序进入对话页时读） */
    public Map<String, Object> welcome() {
        String reply = mapper.configValue("welcome_reply");
        String quick = mapper.configValue("welcome_quick");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reply", reply != null ? reply
            : "你好呀，我是你的校园小助手。可以问我今天吃什么、有什么活动、有兼职吗，也可以随便聊聊。");
        out.put("quickReplies", splitList(quick != null ? quick
            : "今天吃什么,有什么活动,我该学习了吗,陪我聊聊"));
        return out;
    }

    public void setWelcome(Map<String, Object> body) {
        String reply = requireText(body.get("reply"), "欢迎语", 500);
        String quick = trim(body.get("quickReplies"));
        mapper.upsertConfig("welcome_reply", reply);
        mapper.upsertConfig("welcome_quick", quick);
    }

    private static String trim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(value).trim()); } catch (Exception e) { return null; }
    }

    // ---------- 匹配 ----------

    private record Match(Map<String, Object> rule, int keywordLength, int priority) {}

    private static Match findBest(List<Map<String, Object>> rules, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        Match best = null;
        for (Map<String, Object> rule : rules) {
            String category = String.valueOf(rule.get("category"));
            // 兜底规则不参与关键词匹配，只在无命中时使用
            if ("fallback".equals(category)) continue;

            for (String kw : splitList(rule.get("keywords"))) {
                String keyword = kw.toLowerCase(Locale.ROOT);
                if (keyword.isEmpty()) continue;
                if (!lower.contains(keyword)) continue;

                int priority = toInt(rule.get("priority"));
                Match candidate = new Match(rule, keyword.length(), priority);
                if (best == null
                    || candidate.keywordLength() > best.keywordLength()
                    || (candidate.keywordLength() == best.keywordLength() && candidate.priority() > best.priority())) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    // ---------- 校园数据接入 ----------

    /**
     * 给特定意图补充真实数据。返回 null 表示不改写原话术。
     * 数据为空时也要给出可用的回复，不能崩。
     */
    private String enrichWithCampusData(String category, String text, Long userId, Long schoolId) {
        Long school = resolveSchoolId(userId, schoolId);
        if ("food".equals(category) && mentions(text, "吃什么", "吃饭", "饿了", "食堂", "外卖", "午饭", "晚饭", "夜宵")) {
            List<String> names = mapper.shopNames(school, 3);
            if (names.isEmpty()) return "这个点该吃饭了。食堂和小程序里的外卖都可以看看，别拖到太晚。";
            return "这个点该吃饭了。你学校这边有 " + join(names) + "，要不要去试试？";
        }
        if ("campus".equals(category) && mentions(text, "兼职", "打工", "赚钱", "日结")) {
            List<String> titles = mapper.jobTitles(school, 3);
            if (titles.isEmpty()) return "最近你学校这边还没有新岗位，过两天再看看。";
            return "你学校这边在招的有：" + join(titles) + "。详细要求去「兼职」页看看。";
        }
        if ("campus".equals(category) && mentions(text, "活动", "社团", "报名")) {
            List<String> titles = mapper.activityTitles(school, 3);
            if (titles.isEmpty()) return "最近没有新的校园活动，等有消息我提醒你。";
            return "最近的活动有：" + join(titles) + "。报名入口在「季节活动」页。";
        }
        return null;
    }

    private Long resolveSchoolId(Long userId, Long schoolId) {
        if (schoolId != null && schoolId > 0) return schoolId;
        if (userId == null || userId < 1) return null;
        return mapper.userSchoolId(userId);
    }

    private static boolean mentions(String text, String... words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    private static String join(List<String> items) {
        if (items.size() == 1) return "「" + items.get(0) + "」";
        return "「" + String.join("」「", items) + "」";
    }

    // ---------- 工具 ----------

    private static List<String> splitList(Object value) {
        if (value == null) return List.of();
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : text.split("[,，]")) {
            String item = part.trim();
            if (!item.isEmpty()) out.add(item);
        }
        return out;
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value).trim()); } catch (Exception e) { return 0; }
    }
}
