package com.campuslife.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

// 管理端 JWT：密钥在小程序用户密钥基础上加后缀，两种 token 不能互相冒用。
@Component
public class AdminJwtUtil {
    private final SecretKey key;
    private final long ttlSeconds;

    public AdminJwtUtil(@Value("${campus.jwt.secret:}") String secret,
                        @Value("${campus.jwt.admin-ttl-seconds:28800}") long ttlSeconds) {
        String base = (secret == null || secret.isBlank())
            ? "campus-life-dev-secret-change-me-in-production-0123456789"
            : secret;
        this.key = Keys.hmacShaKeyFor((base + "-admin-scope").getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(Long adminId, String role, Long schoolId, Long shopId, String name) {
        return issue(adminId, role, schoolId, shopId, name, 0L);
    }

    /** tokenVersion 写入 tv 声明；账号停用/改权限时库里版本 +1，旧令牌即被 AdminTokenGuard 拒绝 */
    public String issue(Long adminId, String role, Long schoolId, Long shopId, String name, long tokenVersion) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
            .subject(String.valueOf(adminId))
            .claim("role", role == null ? "school" : role)
            .claim("tv", tokenVersion)
            .claim("name", name == null ? "" : name)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(ttlSeconds)));
        if (schoolId != null) builder.claim("schoolId", schoolId);
        if (shopId != null) builder.claim("shopId", shopId);
        return builder.signWith(key).compact();
    }

    // 解析失败（过期/篡改/格式错）返回 null，由拦截器决定拒绝
    public Claims parse(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (Exception error) {
            return null;
        }
    }

    public long ttlSeconds() { return ttlSeconds; }
}
