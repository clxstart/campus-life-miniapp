package com.campuslife.service;

import com.campuslife.mapper.CircleChatMapper;
import com.campuslife.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 圈子群聊业务：HTTP 写入、审核、幂等和 WebSocket 广播由控制层协作完成。 */
@Service
@RequiredArgsConstructor
public class CircleChatService {
    private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> BLOCKED_WORDS = Set.of("赌博", "色情", "毒品", "诈骗", "暴恐");
    private static final RestClient HTTP = RestClient.create();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CircleChatMapper mapper;
    private final UserMapper users;

    @Value("${campus.wx.appid:}") private String appid;
    @Value("${campus.wx.secret:}") private String secret;
    private volatile String accessToken;
    private volatile long accessTokenExpiresAt;

    public Map<String, Object> assertMember(Long circleId, Long userId) {
        if (circleId == null || circleId < 1 || userId == null || userId < 1) throw new IllegalArgumentException("圈子或用户参数无效");
        Map<String, Object> circle = mapper.circle(circleId);
        if (circle == null || number(circle.get("status")) != 1) throw new IllegalArgumentException("圈子不存在或已解散");
        if (mapper.member(circleId, userId) < 1) throw new IllegalArgumentException("你不是这个圈子的成员");
        if (users.selectById(userId) == null) throw new IllegalArgumentException("用户不存在");
        return circle;
    }

    public Map<String, Object> history(Long circleId, Long userId, Long beforeId, Long afterId, int size) {
        assertMember(circleId, userId);
        if (beforeId != null && beforeId < 1 || afterId != null && afterId < 0) throw new IllegalArgumentException("分页游标无效");
        int limit = Math.min(Math.max(size, 1), 50);
        List<Map<String, Object>> rows = mapper.history(circleId, beforeId, afterId, limit + 1);
        boolean hasMore = rows.size() > limit;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, limit));
        // SQL 在正向游标（afterId）下已是升序；其余情况按 id DESC 取最近 N 条，这里翻回升序，保证接口始终按时间正序返回。
        if (afterId == null) Collections.reverse(rows);
        rows.forEach(this::normalize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("hasMore", hasMore);
        result.put("nextBeforeId", rows.isEmpty() ? null : rows.get(0).get("id"));
        return result;
    }

    @Transactional
    public SendResult send(Long circleId, Long userId, String type, String content, String image, String clientId) {
        assertMember(circleId, userId);
        String normalizedType = type == null || type.isBlank() ? "text" : type.trim().toLowerCase(Locale.ROOT);
        if (!normalizedType.equals("text") && !normalizedType.equals("image")) throw new IllegalArgumentException("消息类型不支持");
        String text = content == null ? "" : content.trim();
        String picture = image == null ? "" : image.trim();
        if (normalizedType.equals("text") && text.isEmpty()) throw new IllegalArgumentException("请输入消息");
        if (normalizedType.equals("text") && text.codePointCount(0, text.length()) > 1000) throw new IllegalArgumentException("消息不能超过1000字");
        if (normalizedType.equals("image") && picture.isEmpty()) throw new IllegalArgumentException("请先选择图片");
        if (picture.length() > 255) throw new IllegalArgumentException("图片地址过长");
        for (String word : BLOCKED_WORDS) if (text.contains(word)) throw new IllegalArgumentException("消息包含违规内容，无法发送");
        if (normalizedType.equals("text")) moderateWithWechat(text, userId);
        String dedupeKey = clientId == null ? "" : clientId.trim();
        if (dedupeKey.isEmpty()) dedupeKey = UUID.randomUUID().toString();
        if (dedupeKey.length() > 64) throw new IllegalArgumentException("消息标识过长");

        Long previousId = mapper.messageIdByClient(userId, dedupeKey);
        if (previousId != null) {
            Map<String, Object> previous = mapper.message(previousId);
            boolean sameRequest = previous != null
                && number(previous.get("circleId")) == circleId
                && number(previous.get("senderId")) == userId
                && normalizedType.equals(previous.get("messageType"))
                && text.equals(previous.get("content"))
                && Objects.equals(picture.isEmpty() ? null : picture, previous.get("image"));
            if (!sameRequest) throw new IllegalArgumentException("消息请求标识已被其他内容使用");
        }
        Map<String, Object> row = new HashMap<>();
        row.put("circleId", circleId); row.put("senderId", userId); row.put("type", normalizedType);
        row.put("content", text); row.put("image", picture.isEmpty() ? null : picture); row.put("clientId", dedupeKey);
        int affected = mapper.insertMessage(row);
        Long id = number(row.get("id"));
        if (id < 1) throw new IllegalStateException("消息保存失败");
        Map<String, Object> message = mapper.message(id);
        if (message == null) throw new IllegalStateException("消息保存失败");
        normalize(message);
        // 并发提交同一个 clientId 时，只有真正插入成功的那次广播，避免重复推送。
        boolean created = previousId == null && affected > 0;
        if (created) mapper.updateCircleSummary(circleId);
        return new SendResult(message, created);
    }

    public void markRead(Long circleId, Long userId, Long lastReadId) {
        assertMember(circleId, userId);
        if (lastReadId == null || lastReadId < 1) throw new IllegalArgumentException("消息游标无效");
        if (mapper.messageExists(circleId, lastReadId) < 1) throw new IllegalArgumentException("消息游标不属于这个圈子");
        mapper.markRead(circleId, userId, lastReadId);
    }

    public List<Map<String, Object>> unread(Long userId) {
        if (userId == null || userId < 1 || users.selectById(userId) == null) throw new IllegalArgumentException("用户不存在");
        return mapper.unread(userId);
    }

    public String userName(Long userId) {
        var user = users.selectById(userId);
        return user == null || user.getNickname() == null || user.getNickname().isBlank() ? "校园同学" : user.getNickname();
    }

    // 微信审核只在服务端已配置正式凭据时启用；touristappid 开发环境仍由本地词库拦截。
    private void moderateWithWechat(String content, Long userId) {
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) return;
        var user = users.selectById(userId);
        String openid = user == null ? null : user.getOpenid();
        if (openid == null || openid.isBlank() || openid.startsWith("dev-")) return;
        try {
            String url = "https://api.weixin.qq.com/wxa/msg_sec_check?access_token=" + accessToken();
            String body = HTTP.post().uri(url).body(Map.of("content", content, "version", 2, "scene", 2, "openid", openid)).retrieve().body(String.class);
            JsonNode result = JSON.readTree(body == null ? "{}" : body);
            int errcode = result.path("errcode").asInt(-1);
            String suggest = result.path("result").path("suggest").asText("");
            if (errcode == 0 && "pass".equalsIgnoreCase(suggest)) return;
            if ("review".equalsIgnoreCase(suggest)) throw new IllegalArgumentException("消息正在审核，请稍后再试");
            if (errcode == 0) throw new IllegalArgumentException("消息包含违规内容，无法发送");
            throw new IllegalArgumentException("消息审核暂不可用，请稍后重试");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("消息审核暂不可用，请稍后重试");
        }
    }

    private String accessToken() {
        long now = System.currentTimeMillis();
        if (accessToken != null && accessTokenExpiresAt > now + 60_000) return accessToken;
        synchronized (this) {
            now = System.currentTimeMillis();
            if (accessToken != null && accessTokenExpiresAt > now + 60_000) return accessToken;
            try {
                String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=" + appid + "&secret=" + secret;
                String body = HTTP.get().uri(url).retrieve().body(String.class);
                JsonNode result = JSON.readTree(body == null ? "{}" : body);
                String token = result.path("access_token").asText("");
                if (token.isBlank()) throw new IllegalStateException("微信 access_token 获取失败");
                accessToken = token;
                accessTokenExpiresAt = System.currentTimeMillis() + Math.max(60, result.path("expires_in").asLong(7200) - 300) * 1000;
                return token;
            } catch (Exception error) {
                throw new IllegalArgumentException("消息审核暂不可用，请稍后重试");
            }
        }
    }

    public void normalize(Map<String, Object> message) {
        Object created = message.get("createdAt");
        if (created instanceof LocalDateTime time) message.put("createdAt", MESSAGE_TIME.format(time));
        message.putIfAbsent("content", "");
        message.put("mine", false);
    }

    private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0; }

    public record SendResult(Map<String, Object> message, boolean created) {}
}
