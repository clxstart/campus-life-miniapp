package com.campuslife.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 生产启动账号守卫：数据库里只要还存在“已知演示密码且启用”的管理/商家/骑手账号，就拒绝启动。
 *
 * 按泄露的 BCrypt 哈希判断，而不是按用户名判断：真实运营账号也可以叫 admin，
 * 只要密码已经换过就应允许启动。清理脚本见 db/module-production-demo-account-cleanup.sql。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "campus.production-guards.enabled", havingValue = "true", matchIfMissing = true)
public class ProductionDemoAccountGuard implements ApplicationRunner {

    /** 这些哈希对应的弱口令已经写进仓库历史，必须视为公开泄露。 */
    static final List<String> LEAKED_PASSWORD_HASHES = List.of(
        "$2a$10$PV1n24LAUK3TuFpFLQYPZe6.O0chnDszruExsHfjxjGbTWwacJMj6",
        "$2a$10$gQydHVsyFSX3FcvTGUKXye.Fci6kmHqqBVV/Wo6/rPGmvysibepWK",
        "$2a$10$BA1cECo5jz15309jzCFUReVdEWS7PZzLLevGyHqc57e13zJrEBKB6",
        "$2a$10$ubOWq/4fPhG11dtWoRVvvOKuZ.672R.y/uCjKYwX0PiFejdx0iHhe"
    );

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        if (!isProduction(environment)) return;
        validate(jdbcTemplate, LEAKED_PASSWORD_HASHES);
        validateRotatedCredentials(jdbcTemplate);
    }

    /**
     * 商家/骑手历史上使用「手机号后 6 位」等可预测初始密码。
     * 升级代码不等于轮换了存量凭据，因此生产启动要求这些账号已有 credential_rotated_at；
     * 未轮换的账号可能已经被猜到，必须先由管理员重置（重置会写时间并强制首次改密）。
     */
    static void validateRotatedCredentials(JdbcTemplate jdbcTemplate) {
        List<java.util.Map<String, Object>> stale = jdbcTemplate.queryForList(
            "SELECT id, username, role FROM admin_user WHERE status=1 AND role IN ('merchant','rider') "
                + "AND credential_rotated_at IS NULL");
        if (stale.isEmpty()) return;
        StringBuilder accounts = new StringBuilder();
        for (java.util.Map<String, Object> row : stale) {
            if (accounts.length() > 0) accounts.append("、");
            accounts.append(row.get("username")).append("(").append(row.get("role")).append(",id=")
                .append(row.get("id")).append(")");
        }
        throw new IllegalStateException("生产库仍有未轮换初始凭据的商家/骑手账号：" + accounts
            + "。请先在管理端重置这些账号的密码，再启动服务。");
    }
    static void validate(JdbcTemplate jdbcTemplate, List<String> hashes) {
        if (hashes == null || hashes.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(hashes.size(), "?"));
        String sql = "SELECT id, username, role FROM admin_user WHERE status=1 AND password_hash IN (" + placeholders + ")";
        List<java.util.Map<String, Object>> risky = jdbcTemplate.queryForList(sql, hashes.toArray());
        if (!risky.isEmpty()) {
            StringBuilder accounts = new StringBuilder();
            for (java.util.Map<String, Object> row : risky) {
                if (accounts.length() > 0) accounts.append("、");
                accounts.append(row.get("username")).append("(").append(row.get("role")).append(",id=")
                    .append(row.get("id")).append(")");
            }
            throw new IllegalStateException("生产库仍存在使用已泄露演示密码的启用账号：" + accounts
                + "。请先执行 db/module-production-demo-account-cleanup.sql 停用/改密后再启动。");
        }
    }

    private static boolean isProduction(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) return true;
        }
        return false;
    }
}
