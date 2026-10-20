package com.campuslife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campuslife.config.JwtUtil;
import com.campuslife.entity.User;
import com.campuslife.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service @RequiredArgsConstructor
public class AuthService {
    private final UserMapper userMapper;
    private final JwtUtil jwt;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RestClient HTTP = RestClient.create();

    @Value("${campus.wx.appid:}") private String appid;
    @Value("${campus.wx.secret:}") private String secret;

    // 未配置微信 AppID 时用开发登录，保证 touristappid 下也能跑通完整流程
    public boolean devLoginEnabled() { return appid == null || appid.isBlank() || secret == null || secret.isBlank(); }

    public Map<String,Object> login(String code) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("缺少登录凭证 code");
        String openid = resolveOpenid(code);
        return issueFor(openid, null);
    }

    // 开发登录：固定绑定 1 号演示用户，不创建新账号
    public Map<String,Object> devLogin(Long userId) {
        if (!devLoginEnabled()) throw new IllegalArgumentException("已配置微信登录，开发登录不可用");
        Long id = (userId == null || userId < 1) ? 1L : userId;
        if (userMapper.selectById(id) == null) throw new IllegalArgumentException("演示用户不存在");
        return issueFor("dev-" + id, id);
    }

    private String resolveOpenid(String code) {
        if (devLoginEnabled()) return "dev-1";
        try {
            String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + appid
                + "&secret=" + secret + "&js_code=" + code + "&grant_type=authorization_code";
            String body = HTTP.get().uri(url).retrieve().body(String.class);
            JsonNode node = JSON.readTree(body == null ? "{}" : body);
            if (node.hasNonNull("openid")) return node.get("openid").asText();
            String msg = node.hasNonNull("errmsg") ? node.get("errmsg").asText() : "微信登录失败";
            throw new IllegalArgumentException(msg);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("微信登录服务不可用，请稍后重试");
        }
    }

    // 首次登录自动建号；后续登录复用同一账号
    @Transactional public Map<String,Object> issueFor(String openid, Long existId) {
        User user = existId != null ? userMapper.selectById(existId)
            : userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getOpenid, openid).last("LIMIT 1"));
        if (user == null) {
            user = new User();
            user.setOpenid(openid);
            user.setNickname("校园同学" + (System.currentTimeMillis() % 10000));
            user.setSchool("内蒙古大学");
            user.setCreatedAt(LocalDateTime.now());
            userMapper.insert(user);
        }
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("token", jwt.issue(user.getId()));
        out.put("expiresIn", jwt.ttlSeconds());
        out.put("userId", user.getId());
        out.put("nickname", user.getNickname());
        out.put("newUser", existId == null);
        return out;
    }
}
