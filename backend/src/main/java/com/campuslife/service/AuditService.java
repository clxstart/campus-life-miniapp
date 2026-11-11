package com.campuslife.service;

import com.campuslife.config.AuditRejectedException;
import com.campuslife.dto.AuditResult;
import com.campuslife.mapper.AuditMapper;
import com.campuslife.mapper.CampusMapper;
import com.campuslife.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {
    private static final Set<String> TYPES = Set.of("post", "comment", "goods", "job", "chat", "circle");
    private static final Set<String> CATEGORIES = Set.of("fraud", "abuse", "politics", "custom");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RestClient HTTP = RestClient.create();

    private final AuditMapper auditMapper;
    private final UserMapper userMapper;
    private final CampusMapper campusMapper;
    private final UploadService uploadService;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Qualifier("auditExecutor") private final Executor auditExecutor;
    /** 敏感词引擎：ToolGood.Words（词表变更时整体重建） */
    private final SensitiveWordEngine wordEngine;

    @Value("${campus.wx.appid:}") private String appid;
    @Value("${campus.wx.secret:}") private String secret;
    private volatile String accessToken;
    private volatile long tokenExpiresAt;

    @PostConstruct
    public void initialize() {
        reloadWords();
    }

    /**
     * 常见误判白名单。
     *
     * 内置词典里有不少单字/双字词（如「靠」），会误伤「靠窗」「依靠」「靠背」这类正常校园表达。
     * 这里只放「确实会被误判、且本身无违规含义」的常用词，不放任何可能被滥用的词。
     */
    private static final List<String> COMMON_ALLOW = List.of(
        // 与内置单字敏感词冲突的日常词
        "靠窗", "靠背", "靠近", "依靠", "可靠", "靠山", "靠着", "靠谱",
        // 校园场景高频词
        "兼职", "信息", "上课", "下课", "上课时间", "自习", "作业", "考试", "复习",
        "宿舍", "食堂", "图书馆", "老师", "同学", "课程", "选修", "成绩",
        // 交易/配送场景
        "押金退还", "退款", "退押金", "预约", "预订单", "代取", "代拿", "代购",
        // 其他易误伤
        "方便", "便利", "包邮", "包邮区", "包月", "包年"
    );

    /**
     * 数据库词表不可用时的兜底词表，与 module-audit.sql 里的种子数据保持一致。
     * 覆盖校园最常见的两类风险：兼职诈骗、学术不端。
     */
    private static final List<String> FALLBACK_WORDS = List.of(
        "刷单", "押金", "保证金", "先交钱", "垫付", "返利", "日赚", "月入过万",
        "代写", "代考", "代课", "包过", "内部渠道", "包录取",
        "兼职日结高薪", "无需经验高薪", "加微信私聊", "加QQ",
        "刷信誉", "点赞返现", "任务佣金", "垫付返现"
    );

    public synchronized void reloadWords() {
        List<String> list;
        try {
            list = auditMapper.enabledWords();
        } catch (Exception error) {
            log.warn("Sensitive-word table unavailable; falling back to built-in safety words", error);
            list = List.of();
        }
        List<String> configured = list == null ? List.of() : list.stream()
            .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();

        // 只使用数据库里配置的词表（+ FALLBACK_WORDS 兜底）。
        // 不加载第三方内置大词典：其中含大量单字（如「靠」），
        // 会误伤「靠窗」「依靠」等正常校园表达；
        // 本项目的审核重点是「校园诈骗/学术不端」，词表由业务方维护更可控。
        List<String> deny = new ArrayList<>(configured);
        if (deny.isEmpty()) deny.addAll(FALLBACK_WORDS);

        // 交给 ToolGood 引擎：内部做 DFA 多模式匹配 + 全角半角/大小写归一 + 跳过分隔符，
        // 能拦住「刷 单」「刷-单」「Ｓ刷单」这类绕过写法。
        // 白名单（COMMON_ALLOW）不交给引擎，仍由 isCoveredByAllowWord 按位置判断，
        // 因为需要支持「违规词是白名单词子串」的场景（如词表「押金」vs 白名单「押金退还」）。
        wordEngine.load(deny);
        log.info("Sensitive-word engine (ToolGood) loaded with {} deny words, {} allow words",
            deny.size(), COMMON_ALLOW.size());
    }

    /**
     * 取出一段文本里真正需要拦截的词。
     *
     * 引擎自带的 wordAllow 只按「整词相等」剔除，管不了「违规词是白名单词的子串」这种情况：
     * 词表里有「押金」，白名单里写了「押金退还」，但「押金退还」照样会被判违规。
     * 这里补一层按出现位置的白名单覆盖判断：命中词在文本里的每一处都被某个白名单词包住，
     * 才算误判并放行；只要有一处露在外面（例如「先交押金」）就仍然拦截。
     */
    public List<String> matchWords(String text) {
        String value = text == null ? "" : text;
        if (value.isEmpty()) return List.of();
        // ToolGood 已完成全角半角/大小写归一与跳字符，这里只做业务层过滤：
        // 去掉纯符号命中，并按出现位置剔除被白名单词包住的误判。
        return wordEngine.match(value).stream()
            .filter(hit -> hit != null && !hit.isBlank())
            .filter(hit -> hit.codePoints().anyMatch(Character::isLetterOrDigit))
            .distinct()
            .filter(hit -> !isCoveredByAllowWord(value, hit))
            .toList();
    }

    /** 命中词在文本中的所有出现位置是否都被白名单词覆盖。 */
    private static boolean isCoveredByAllowWord(String text, String hit) {
        int from = 0;
        boolean seen = false;
        while (true) {
            int at = text.indexOf(hit, from);
            if (at < 0) break;
            seen = true;
            if (!coveredAt(text, hit, at)) return false;
            from = at + 1;
        }
        return seen;
    }

    private static boolean coveredAt(String text, String hit, int at) {
        int end = at + hit.length();
        for (String allow : COMMON_ALLOW) {
            if (allow.length() <= hit.length() || !allow.contains(hit)) continue;
            int pos = text.indexOf(allow);
            while (pos >= 0) {
                if (pos <= at && pos + allow.length() >= end) return true;
                pos = text.indexOf(allow, pos + 1);
            }
        }
        return false;
    }

    public AuditResult checkText(String text, String targetType) {
        return checkText(text, targetType, (Long) null);
    }

    public AuditResult checkText(String text, String targetType, Long userId) {
        String value = text == null ? "" : text;
        List<String> hits = matchWords(value);
        if (!hits.isEmpty()) {
            return new AuditResult(false, false, "内容包含违规词：" + String.join("、", hits), hits);
        }

        String openid = null;
        if (userId != null) {
            var user = userMapper.selectById(userId);
            if (user != null) openid = user.getOpenid();
        }
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            return AuditResult.passed();
        }
        if (openid == null || openid.isBlank() || openid.startsWith("dev-")) {
            log.warn("WeChat text moderation skipped for {}: a real openid is unavailable", targetType);
            return AuditResult.passed();
        }
        try {
            JsonNode response = HTTP.post()
                .uri("https://api.weixin.qq.com/wxa/msg_sec_check?access_token=" + token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("content", value, "version", 2, "scene", 2, "openid", openid))
                .retrieve().body(JsonNode.class);
            if (response == null || response.path("errcode").asInt(-1) != 0) {
                throw new IllegalStateException("WeChat text moderation returned an error: " + response);
            }
            String suggest = response.path("result").path("suggest").asText("pass");
            if ("risky".equalsIgnoreCase(suggest)) {
                return new AuditResult(false, false, "微信内容安全检测未通过", List.of());
            }
            if ("review".equalsIgnoreCase(suggest)) {
                return new AuditResult(true, true, "微信判定为待人工审核", List.of());
            }
            return AuditResult.passed();
        } catch (Exception error) {
            log.warn("WeChat text moderation unavailable; accepting after local moderation for {}", targetType, error);
            return AuditResult.passed();
        }
    }

    public void logTextResult(String type, Long targetId, Long userId, String text, AuditResult result) {
        String state = !result.pass() ? "reject" : result.reviewing() ? "review" : "pass";
        writeLog(type, targetId, userId, text, "local", state, result.words());
        if (result.pass() && !result.reviewing() && appid != null && !appid.isBlank()) {
            writeLog(type, targetId, userId, text, "wechat", "pass", List.of());
        }
    }

    /**
     * 先发后审留痕：source='fastlane'。
     * 审核日志要能区分「谁让这条内容先上线」，否则事后追查只能靠猜。
     */
    public void logFastlane(String type, Long targetId, Long userId, String text) {
        writeLog(type, targetId, userId, text, "fastlane", "pass", List.of());
    }

    public void rejectIfNeeded(AuditResult result) {
        if (!result.pass()) throw new AuditRejectedException(result.reason());
    }

    /**
     * 抛出拒绝前先把这次拦截写进 audit_log，否则被拒内容在库里没有任何痕迹，
     * 后台「内容审核」页看不到拦了什么，也无法证明审核确实在工作。
     *
     * 用 TransactionTemplate 单开事务而不是 @Transactional 注解：本方法是业务方法内部
     * 直接调用的（同类自调用不走 Spring 代理），注解不会生效，日志仍会被业务回滚带走。
     */
    public void rejectWithLog(AuditResult result, String targetType, Long userId, String text) {
        if (result.pass()) return;
        try {
            // 必须用 REQUIRES_NEW：默认 REQUIRED 会加入外层业务事务，
            // 业务方法随后抛出 AuditRejectedException 回滚时，日志会被一起撤掉。
            org.springframework.transaction.support.TransactionTemplate fresh =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            fresh.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            fresh.executeWithoutResult(status -> writeRejectLog(targetType, userId, text, result.words()));
            log.info("Reject audit log persisted: type={} words={}", targetType, result.words());
        } catch (Exception error) {
            log.warn("Failed to persist reject audit log for {}", targetType, error);
        }
        throw new AuditRejectedException(result.reason());
    }

    public String initialStatus(AuditResult result, boolean hasImages) {
        if (result.reviewing()) return "reviewing";
        return hasImages ? "pending" : "passed";
    }

    public void scheduleImagesAfterCommit(List<String> urls, String targetType, Long targetId, Long userId) {
        if (urls == null || urls.stream().filter(Objects::nonNull).map(String::trim).noneMatch(value -> !value.isEmpty())) return;
        Runnable submit = () -> {
            try {
                auditExecutor.execute(() -> auditImages(urls, targetType, targetId, userId));
            } catch (RejectedExecutionException error) {
                log.warn("Image audit queue is full; allowing image after local text moderation for {}:{}", targetType, targetId, error);
                imagePassed(requireType(targetType), targetId);
                writeLog(targetType, targetId, userId, String.join(" ", urls), "local", "pass", List.of());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { submit.run(); }
            });
        } else {
            submit.run();
        }
    }

    @Async("auditExecutor")
    public void checkImageAsync(String imageUrl, String targetType, Long targetId) {
        auditImages(List.of(imageUrl), targetType, targetId, null);
    }

    @Async("auditExecutor")
    public void checkImagesAsync(List<String> urls, String targetType, Long targetId) {
        auditImages(urls, targetType, targetId, null);
    }

    @Async("auditExecutor")
    public void checkImagesAsync(List<String> urls, String targetType, Long targetId, Long userId) {
        auditImages(urls, targetType, targetId, userId);
    }

    private void auditImages(List<String> urls, String targetType, Long targetId, Long userId) {
        String type = requireType(targetType);
        if (!"post".equals(type) && !"goods".equals(type)) throw new IllegalArgumentException("Images are supported for posts and goods only");
        try {
            if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
                log.warn("WeChat image moderation is not configured for {}:{}; image remains subject to local text moderation", type, targetId);
                imagePassed(type, targetId);
                writeLog(type, targetId, userId, String.join(" ", urls), "local", "pass", List.of());
                return;
            }
            for (String url : urls) {
                if (url == null || url.isBlank()) continue;
                JsonNode response = checkImage(url);
                int errcode = response.path("errcode").asInt(-1);
                // 命中/未通过的落地逻辑抽到 applyImageVerdict：不依赖微信 HTTP 也能测「下架 + 留痕」
                if (!applyImageVerdict(type, targetId, userId, url, errcode)) return;
                if (errcode != 0) throw new IllegalStateException("WeChat image moderation returned an error: " + response);
            }
            imagePassed(type, targetId);
            writeLog(type, targetId, userId, String.join(" ", urls), "wechat", "pass", List.of());
        } catch (Exception error) {
            log.warn("WeChat image moderation unavailable; allowing image after local text moderation for {}:{}", type, targetId, error);
            imagePassed(type, targetId);
            writeLog(type, targetId, userId, String.join(" ", urls), "local", "pass", List.of());
        }
    }

    private JsonNode checkImage(String imageUrl) throws IOException {
        byte[] bytes = Files.readAllBytes(localUpload(imageUrl));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("media", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return "upload-image"; }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        JsonNode response = HTTP.post()
            .uri("https://api.weixin.qq.com/wxa/img_sec_check?access_token=" + token())
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(body.build())
            .retrieve().body(JsonNode.class);
        if (response == null) throw new IllegalStateException("Empty WeChat image response");
        return response;
    }

    private Path localUpload(String url) throws IOException {
        String path = java.net.URI.create(url).getPath();
        String prefix = "/uploads/";
        if (path == null || !path.startsWith(prefix)) throw new IOException("Image is not a locally uploaded file");
        Path root = uploadService.root().toAbsolutePath().normalize();
        Path file = root.resolve(path.substring(prefix.length())).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new IOException("Uploaded image does not exist");
        return file;
    }

    private synchronized String token() {
        if (accessToken != null && tokenExpiresAt > System.currentTimeMillis() + 30_000) return accessToken;
        JsonNode response = HTTP.get().uri("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=" + appid + "&secret=" + secret)
            .retrieve().body(JsonNode.class);
        if (response == null || !response.hasNonNull("access_token")) throw new IllegalStateException("Unable to acquire WeChat access_token: " + response);
        accessToken = response.path("access_token").asText();
        tokenExpiresAt = System.currentTimeMillis() + response.path("expires_in").asLong(7200) * 1000L;
        return accessToken;
    }

    private void imagePassed(String type, Long id) {
        if ("post".equals(type)) auditMapper.imagePassedPost(id);
        else auditMapper.imagePassedGoods(id);
    }

    private void imageRejected(String type, Long id, String reason, Long userId) {
        if ("post".equals(type)) {
            auditMapper.imageRejectedPost(id, reason);
            // 先发后审的帖子被撤回：补一条 source='fastlane_revoked' 留痕。
            // 只有走过免审（audit_log 里有 source='fastlane'）的帖子才写，普通审核的违规不冒充免审撤回。
            if (auditMapper.countFastlaneLog(type, id) > 0) {
                writeLog(type, id, userId, reason, "fastlane_revoked", "reject", List.of());
            }
            // 先发后审命中：帖子已下架必须通知作者，否则用户只会看到「帖子不见了」
            if (userId != null && userId > 0) {
                campusMapper.notifyUser(userId, "帖子已下架", "你的帖子因违反社区规范已被下架", "audit", id);
            }
        } else {
            auditMapper.imageRejectedGoods(id, reason);
        }
    }

    /**
     * 图片审核结论落地：只有命中违规（87014）才动数据。
     *
     * 抽成独立方法有两个理由：
     *   1. 生产路径（auditImages）与测试共用同一段落地逻辑，不会出现「测试通过、线上另一套」；
     *   2. 落地不依赖微信 HTTP（取图/换 token），可对真实库断言「下架 + audit_log + 通知」。
     *
     * @return true 表示这张图通过、继续检查下一张；false 表示已命中违规并下架，停止
     */
    boolean applyImageVerdict(String type, Long targetId, Long userId, String url, int errcode) {
        if (errcode != 87014) return true;
        imageRejected(requireType(type), targetId, "图片未通过微信内容安全检测", userId);
        writeLog(type, targetId, userId, url, "wechat", "reject", List.of());
        return false;
    }

    public Map<String,Object> list(String type, String status, String keyword, String startDate, String endDate,
                                   int page, int size, Long schoolId) {
        validateFilter(type, status);
        int safePage = Math.max(1, page), safeSize = Math.min(100, Math.max(1, size));
        List<Map<String,Object>> rows = auditMapper.list(blankToNull(type), blankToNull(status), blankToNull(keyword),
            blankToNull(startDate), blankToNull(endDate), schoolId, safeSize, (safePage - 1) * safeSize);
        long total = auditMapper.count(blankToNull(type), blankToNull(status), blankToNull(keyword),
            blankToNull(startDate), blankToNull(endDate), schoolId);
        for (Map<String,Object> row : rows) {
            String content = String.valueOf(row.getOrDefault("content", ""));
            row.put("snippet", content.length() > 60 ? content.substring(0, 60) + "..." : content);
            String reason = Objects.toString(row.get("auditReason"), "");
            row.put("matchedWords", reason.startsWith("内容包含违规词：")
                ? Arrays.asList(reason.substring("内容包含违规词：".length()).split("、")) : List.of());
            row.put("typeName", typeName(String.valueOf(row.get("type"))));
        }
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("list", rows); out.put("total", total); out.put("page", safePage); out.put("size", safeSize);
        return out;
    }

    public Map<String,Object> stats(Long schoolId) {
        Map<String,Object> result = new LinkedHashMap<>(auditMapper.stats(schoolId));
        result.put("words", auditMapper.countEnabledWords());
        return result;
    }

    public Map<String,Object> detail(String type, Long id, Long schoolId) {
        Map<String,Object> row = getTarget(requireType(type), id, schoolId);
        if (row == null) throw new IllegalArgumentException("内容不存在或无权查看");
        String name = typeName(type);
        row.put("type", type); row.put("typeName", name);
        if (row.get("userId") != null) {
            var user = userMapper.selectById(Long.valueOf(String.valueOf(row.get("userId"))));
            row.put("userName", user == null ? "" : user.getNickname());
        }
        return row;
    }

    public void pass(String type, Long id, Long schoolId) {
        Map<String,Object> row = detail(type, id, schoolId);
        if (!"reviewing".equals(row.get("auditStatus")) && !"pending".equals(row.get("auditStatus"))) {
            throw new IllegalArgumentException("该内容当前不能审核通过");
        }
        decide(type, id, "passed", null, 1);
        if ("comment".equals(type) && row.get("postId") != null) {
            auditMapper.incrementPostComment(asLong(row.get("postId")));
        }
        writeLog(type, id, asLong(row.get("userId")), content(row), "manual", "pass", List.of());
    }

    public void reject(String type, Long id, Long schoolId, String reason) {
        String explanation = reason == null ? "" : reason.trim();
        if (explanation.isEmpty() || explanation.length() > 200) throw new IllegalArgumentException("拒绝原因必填，最多200字");
        Map<String,Object> row = detail(type, id, schoolId);
        if (!"reviewing".equals(row.get("auditStatus")) && !"pending".equals(row.get("auditStatus"))) {
            throw new IllegalArgumentException("该内容当前不能拒绝");
        }
        decide(type, id, "rejected", explanation, 0);
        writeLog(type, id, asLong(row.get("userId")), content(row), "manual", "reject", List.of());
        Long userId = asLong(row.get("userId"));
        if (userId != null) campusMapper.notifyUser(userId, "内容未通过审核", explanation, "system", null);
    }

    public void restore(String type, Long id, Long schoolId) {
        Map<String,Object> row = detail(type, id, schoolId);
        if (!"rejected".equals(row.get("auditStatus"))) throw new IllegalArgumentException("只能恢复已拒绝的内容");
        decide(type, id, "passed", null, 1);
        writeLog(type, id, asLong(row.get("userId")), content(row), "manual", "pass", List.of());
    }

    private void decide(String type, Long id, String state, String reason, int visible) {
        int changed = switch (type) {
            case "post" -> auditMapper.decidePost(id, state, reason, visible);
            case "comment" -> auditMapper.decideComment(id, state, reason);
            case "goods" -> auditMapper.decideGoods(id, state, reason, visible);
            case "job" -> auditMapper.decideJob(id, state, reason, visible);
            case "chat" -> auditMapper.decideChat(id, state, reason);
            case "circle" -> auditMapper.decideCircle(id, state, reason, visible);
            default -> throw new IllegalArgumentException("不支持的内容类型");
        };
        if (changed != 1) throw new IllegalArgumentException("内容不存在或已被处理");
    }

    private Map<String,Object> getTarget(String type, Long id, Long schoolId) {
        return switch (type) {
            case "post" -> auditMapper.post(id, schoolId);
            case "comment" -> auditMapper.comment(id, schoolId);
            case "goods" -> auditMapper.goods(id, schoolId);
            case "job" -> auditMapper.job(id, schoolId);
            case "chat" -> auditMapper.chat(id, schoolId);
            case "circle" -> auditMapper.circle(id, schoolId);
            default -> throw new IllegalArgumentException("不支持的内容类型");
        };
    }

    public Map<String,Object> words(String category, String keyword, int page, int size) {
        int p = Math.max(1, page), s = Math.min(100, Math.max(1, size));
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("list", auditMapper.words(blankToNull(category), blankToNull(keyword), s, (p - 1) * s));
        out.put("total", auditMapper.countWords(blankToNull(category), blankToNull(keyword)));
        out.put("page", p); out.put("size", s); return out;
    }

    public Map<String,Object> addWord(String word, String category) {
        String value = validateWord(word, category);
        int inserted = auditMapper.insertWord(value, category);
        if (inserted > 0) reloadWords();
        return Map.of("inserted", inserted > 0, "duplicate", inserted == 0);
    }

    public void updateWord(Long id, String word, String category, boolean enabled) {
        String value = validateWord(word, category);
        if (auditMapper.updateWord(id, value, category, enabled ? 1 : 0) != 1) throw new IllegalArgumentException("敏感词不存在");
        reloadWords();
    }

    public void deleteWord(Long id) {
        if (auditMapper.deleteWord(id) != 1) throw new IllegalArgumentException("敏感词不存在");
        reloadWords();
    }

    public Map<String,Object> importWords(List<String> imported, String category) {
        if (imported == null || imported.isEmpty()) throw new IllegalArgumentException("请提供要导入的词语");
        if (imported.size() > 5000) throw new IllegalArgumentException("单次最多导入5000个词语");
        int inserted = 0, duplicate = 0;
        for (String word : imported) {
            if (word == null || word.trim().isEmpty()) continue;
            if (auditMapper.insertWord(validateWord(word, category), category) > 0) inserted++; else duplicate++;
        }
        if (inserted > 0) reloadWords();
        return Map.of("inserted", inserted, "duplicate", duplicate);
    }

    private void writeLog(String type, Long id, Long userId, String text, String source, String result, List<String> matched) {
        if (id == null) return;
        String snippet = text == null ? "" : text.substring(0, Math.min(200, text.length()));
        String encoded;
        try { encoded = JSON.writeValueAsString(matched == null ? List.of() : matched); }
        catch (Exception error) { encoded = "[]"; }
        auditMapper.log(requireType(type), id, userId, snippet, source, result, encoded);
    }

    /** 被拦下的内容还没有入库，没有 target_id，用 0 占位并靠 snippet 记录原文。 */
    private void writeRejectLog(String type, Long userId, String text, List<String> matched) {
        String snippet = text == null ? "" : text.substring(0, Math.min(200, text.length()));
        String encoded;
        try { encoded = JSON.writeValueAsString(matched == null ? List.of() : matched); }
        catch (Exception error) { encoded = "[]"; }
        auditMapper.log(requireType(type), 0L, userId, snippet, "local", "reject", encoded);
    }

    private static String validateWord(String word, String category) {
        String value = word == null ? "" : word.trim();
        if (value.isEmpty() || value.length() > 64) throw new IllegalArgumentException("敏感词必填，最多64字");
        if (!CATEGORIES.contains(category)) throw new IllegalArgumentException("敏感词分类无效");
        return value;
    }

    private static void validateFilter(String type, String status) {
        if (type != null && !type.isBlank() && !TYPES.contains(type)) throw new IllegalArgumentException("内容类型无效");
        if (status != null && !status.isBlank() && !Set.of("pending", "reviewing", "passed", "rejected").contains(status)) throw new IllegalArgumentException("审核状态无效");
    }

    private static String requireType(String type) {
        if (!TYPES.contains(type)) throw new IllegalArgumentException("内容类型无效");
        return type;
    }

    private static String typeName(String type) {
        return switch (type) { case "post" -> "发帖"; case "comment" -> "评论"; case "goods" -> "二手"; case "job" -> "兼职"; case "chat" -> "私信"; case "circle" -> "圈子"; default -> type; };
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Long asLong(Object value) { try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (NumberFormatException error) { return null; } }
    private static String content(Map<String,Object> row) { return Objects.toString(row.get("content"), Objects.toString(row.get("description"), Objects.toString(row.get("title"), Objects.toString(row.get("name"), "")))); }
}
