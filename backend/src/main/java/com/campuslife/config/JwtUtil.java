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

// JWT 签发与校验。密钥从配置读取；未配置时用开发默认值并打印告警。
@Component
public class JwtUtil {
    private final SecretKey key;
    private final long ttlSeconds;

    public JwtUtil(@Value("${campus.jwt.secret:}") String secret,
                   @Value("${campus.jwt.ttl-seconds:604800}") long ttlSeconds) {
        String value = (secret == null || secret.isBlank()) ? "campus-life-dev-secret-change-me-in-production-0123456789" : secret;
        if (secret == null || secret.isBlank()) {
            System.out.println("[campus] 未配置 campus.jwt.secret，正在使用开发默认密钥，请勿用于生产");
        }
        this.key = Keys.hmacShaKeyFor(value.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(ttlSeconds)))
            .signWith(key)
            .compact();
    }

    // 解析失败（过期、篡改、格式错误）一律返回 null，由调用方决定如何降级
    public Long parse(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
            return Long.valueOf(claims.getSubject());
        } catch (Exception error) {
            return null;
        }
    }

    public long ttlSeconds() { return ttlSeconds; }
}
