package com.campuslife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campuslife.config.JwtUtil;
import com.campuslife.entity.User;
import com.campuslife.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service @RequiredArgsConstructor
public class AuthService {
    private final UserMapper userMapper;
    private final JwtUtil jwt;
    /** 新人礼包：建号即发 30P + 7 天白银体验（功能 6）。发分走唯一入口，失败不影响登录。 */
    private final PointsService points;
    private final TierService tierService;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RestClient HTTP = RestClient.create();

    @Value("${campus.wx.appid:}") private String appid;
    @Value("${campus.wx.secret:}") private String secret;
    /** 允许开发登录切换到 1 号以外的账号（仅本地多用户调试用，prod 无效）。 */
    @Value("${campus.auth.dev-login-any-user:false}") private boolean devLoginAnyUser;
    /** 是否显式开启开发登录。默认关闭：有微信配置时绝不回落成「谁都能登」。 */
    @Value("${campus.auth.dev-login:false}") private boolean devLoginConfigured;
    private final org.springframework.core.env.Environment environment;

    /** 是否配置了可用的微信小程序 appid/secret（微信游客 appid 视为未配置）。 */
    public boolean wechatConfigured() {
        return appid != null && !appid.isBlank()
            && !"touristappid".equalsIgnoreCase(appid.trim())
            && secret != null && !secret.isBlank();
    }

    private boolean productionProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) return true;
        }
        return false;
    }

    // 开发登录：显式开关 + 未配置微信 + 非生产环境；生产环境即使漏配微信也必须 fail-closed。
    public boolean devLoginEnabled() {
        if (productionProfile()) return false;
        return devLoginConfigured && !wechatConfigured();
    }

    @PostConstruct
    void reportAuthMode() {
        if (devLoginEnabled()) {
            log.warn("[campus] 未配置 campus.wx.appid/secret：开发登录已启用（默认只允许 1 号演示账号）。"
                + "线上必须配置真实微信参数，否则任何人都能以演示账号进入");
        }
        if (devLoginAnyUser && devLoginAnyUserAllowed()) {
            log.warn("[campus] campus.auth.dev-login-any-user=true：开发登录可以登录任意用户，"
                + "仅限本地多用户调试，请不要带到线上");
        }
    }

    /**
     * 开发登录能不能「以任意用户身份登录」。
     *
     * 默认只允许 1 号演示账号（多数场景够用）。
     * 需要切换成别的账号调试多用户场景时，显式设置 campus.auth.dev-login-any-user=true；
     * prod profile 下一律拒绝（配置成 true 也不生效）。
     */
    private boolean devLoginAnyUserAllowed() {
        if (!devLoginAnyUser) return false;
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return false;
            }
        }
        return true;
    }

    public Map<String,Object> login(String code) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("缺少登录凭证 code");
        String openid = resolveOpenid(code);
        return issueFor(openid, null);
    }

    // 开发登录：默认只绑定 1 号演示用户，不创建新账号
    public Map<String,Object> devLogin(Long userId) {
        if (!devLoginEnabled()) throw new IllegalArgumentException("已配置微信登录，开发登录不可用");
        Long demoTarget = (userId == null || userId < 1) ? 1L : userId;
        // 想用别的账号登录，必须是「显式打开开关 + 该用户存在」；否则一律落到演示账号
        if (!demoTarget.equals(1L) && !devLoginAnyUserAllowed()) {
            throw new IllegalArgumentException("开发登录仅支持演示账号；如需切换到其他账号调试，请设置 campus.auth.dev-login-any-user=true（生产环境不支持）");
        }
        if (userMapper.selectById(demoTarget) == null) throw new IllegalArgumentException("演示用户不存在");
        return issueFor("dev-" + demoTarget, demoTarget);
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
        boolean created = false;
        if (user == null) {
            user = new User();
            user.setOpenid(openid);
            user.setNickname("校园同学" + (System.currentTimeMillis() % 10000));
            user.setSchool("内蒙古大学");
            user.setCreatedAt(LocalDateTime.now());
            userMapper.insert(user);
            created = true;
        }
        if (created) grantNewbieGift(user.getId());
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("token", jwt.issue(user.getId()));
        out.put("expiresIn", jwt.ttlSeconds());
        out.put("userId", user.getId());
        out.put("nickname", user.getNickname());
        out.put("newUser", created);
        return out;
    }

    /**
     * 新人礼包：建号即发 30P + 7 天白银体验。
     *
     * 幂等靠 PointsService 的 biz_key='onboard'；即便重试/重复建号也只发一次。
     * 发分或体验发放失败只记日志，不能因为积分模块抖动让用户登录失败（登录是最高优先级路径）。
     */
    private void grantNewbieGift(Long userId) {
        try {
            points.award(userId, "newbie_gift", "onboard", "新人礼包");
        } catch (Exception e) {
            log.warn("[campus] 新人礼包发分失败 user={}: {}", userId, e.getMessage());
        }
        try {
            tierService.grantTrial(userId, TierService.SILVER, 7);
        } catch (Exception e) {
            log.warn("[campus] 新人白银体验发放失败 user={}: {}", userId, e.getMessage());
        }
    }
}
