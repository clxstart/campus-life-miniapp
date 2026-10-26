package com.campuslife.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import toolgood.words.IllegalWordsSearch;
import toolgood.words.IllegalWordsSearchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 敏感词引擎（基于 ToolGood.Words）
 *
 * 为什么换成 ToolGood：
 *   - DFA 多模式匹配，性能优于逐词 contains
 *   - 内置全角/半角互换、大小写忽略、重复字符过滤，
 *     能拦住「刷 单」「刷*单」「Ｓ刷单」这类绕过写法
 *   - 支持 SkipWordFilter，可自定义跳过字符（如标点、空格）
 *
 * 与原 houbb 实现的差异处理：
 *   1) ToolGood 的 SetKeywords 是全量替换，重新加载词表时重建实例即可；
 *   2) 它没有内置白名单，白名单仍由 AuditService 的 isCoveredByAllowWord 处理；
 *   3) 它会返回命中的具体词与位置，便于日志与前端提示。
 */
@Slf4j
@Component
public class SensitiveWordEngine {

    /** 当前引擎实例（词表变更时整体替换，volatile 保证可见性） */
    private volatile IllegalWordsSearch search = newSearch();

    /**
     * 创建一个可用但未载入任何词的引擎。
     *
     * 注意：ToolGood 在 SetKeywords(空列表) 时会抛 NPE
     * （内部 java.util.List.get(int).m_values 为 null），
     * 所以空词表时只构造实例、不调用 SetKeywords。
     */
    private static IllegalWordsSearch newSearch() {
        IllegalWordsSearch s = new IllegalWordsSearch();
        s.UseIgnoreCase = true;
        s.UseDBCcaseConverter = true;
        return s;
    }

    /**
     * 载入词表（全量替换）
     *
     * @param words 敏感词列表，null / 空列表表示清空
     */
    public synchronized void load(List<String> words) {
        List<String> cleaned = words == null ? List.of() : words.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(w -> !w.isEmpty())
            .distinct()
            .toList();

        IllegalWordsSearch fresh = new IllegalWordsSearch();
        // 忽略大小写 + 全角半角归一，减少「刷ＤＡＮ」「ShuaDan」类绕过
        fresh.UseIgnoreCase = true;
        fresh.UseDBCcaseConverter = true;
        // 跳过常见分隔符，拦住「刷-单」「刷 单」这类拆字绕过
        fresh.UseSkipWordFilter = true;
        fresh.SetSkipWords(" \t\r\n-_.*#@!~|/\\+=,，。、；：！？\"'“”‘’()（）[]【】{}<>《》");

        if (!cleaned.isEmpty()) {
            fresh.SetKeywords(cleaned);
        }
        this.search = fresh;
        log.info("ToolGood 敏感词引擎已载入 {} 个词", cleaned.size());
    }

    /**
     * 找出文本中命中的敏感词（去重，按首次出现顺序）
     *
     * @param text 待检测文本
     * @return 命中的词列表；无命中返回空列表
     */
    public List<String> match(String text) {
        if (text == null || text.isEmpty()) return List.of();

        List<IllegalWordsSearchResult> results;
        try {
            results = search.FindAll(text);
        } catch (Exception error) {
            log.warn("ToolGood 匹配异常，按未命中处理", error);
            return List.of();
        }
        if (results == null || results.isEmpty()) return List.of();

        Set<String> hits = new LinkedHashSet<>();
        for (IllegalWordsSearchResult item : results) {
            if (item == null) continue;
            String word = item.Keyword;
            if (word == null) continue;
            word = word.trim();
            // 纯符号/纯空白不算命中，避免「---」「。。」这类误判
            if (word.isEmpty()) continue;
            if (word.codePoints().noneMatch(Character::isLetterOrDigit)) continue;
            hits.add(word);
        }
        return new ArrayList<>(hits);
    }

    /** 是否包含任意敏感词（快速判断，不关心具体命中词） */
    public boolean contains(String text) {
        if (text == null || text.isEmpty()) return false;
        try {
            return search.ContainsAny(text);
        } catch (Exception error) {
            log.warn("ToolGood 检测异常，按未命中处理", error);
            return false;
        }
    }

    /** 把命中的敏感词替换为 * */
    public String replace(String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            return search.Replace(text);
        } catch (Exception error) {
            log.warn("ToolGood 替换异常，返回原文", error);
            return text;
        }
    }
}
