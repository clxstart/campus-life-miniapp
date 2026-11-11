package com.campuslife.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 生产配置闸门：配置不安全时直接拒绝启动，不能等到第一笔真实请求才暴露。
 *
 * 这里只校验环境变量/application-{profile}.yml 能表达的内容；数据库里的演示账号由
 * ProductionDemoAccountGuard 在数据源就绪后校验。故意不打印密钥，错误信息只给变量名。
 */
@Configuration
public class ProductionConfigValidator {

    private static final Set<String> PLACEHOLDER_HOSTS = Set.of(
        "your-domain.example", "example.com", "www.example.com", "localhost", "127.0.0.1", "::1"
    );

    @Bean
    public static BeanFactoryPostProcessor productionConfigValidation(Environment environment) {
        return beanFactory -> validate(environment);
    }

    static void validate(Environment environment) {
        // 没有激活任何 profile 时必须拒绝启动：历史默认值是 dev，部署漏传
        // --spring.profiles.active=prod 时会静默带开发登录/模拟支付上线。
        if (!hasExplicitProfile(environment)) {
            throw new IllegalStateException("未显式指定 Spring profile，拒绝启动。"
                + "本机请用 mvn spring-boot:run（pom 已激活 dev），生产请加 --spring.profiles.active=prod");
        }
        if (!isProduction(environment)) return;
        List<String> problems = new ArrayList<>();

        String appid = text(environment, "campus.wx.appid");
        String secret = text(environment, "campus.wx.secret");
        if (blank(appid) || blank(secret) || "touristappid".equalsIgnoreCase(appid)) {
            problems.add("CAMPUS_WX_APPID/CAMPUS_WX_SECRET 必须配置真实小程序参数（不能为空或 touristappid）");
        }
        if (bool(environment, "campus.auth.dev-login-enabled", false)) {
            problems.add("生产环境禁止 campus.auth.dev-login-enabled=true");
        }
        if (bool(environment, "campus.auth.dev-login-any-user", false)) {
            problems.add("生产环境禁止 campus.auth.dev-login-any-user=true");
        }
        if (!bool(environment, "campus.auth.enforce", true)) {
            problems.add("CAMPUS_AUTH_ENFORCE 必须为 true");
        }
        if (bool(environment, "campus.pay.simulate", true)) {
            problems.add("CAMPUS_PAY_SIMULATE 必须为 false");
        }

        String jwt = text(environment, "campus.jwt.secret");
        if (blank(jwt) || jwt.length() < 32 || isDevelopmentSecret(jwt)) {
            problems.add("CAMPUS_JWT_SECRET 必须是至少 32 位的随机密钥，不能使用开发默认值");
        }

        String origins = text(environment, "campus.web.allowed-origins");
        if (blank(origins)) {
            problems.add("CAMPUS_WEB_ORIGINS 必须配置管理端真实 HTTPS 域名");
        } else {
            for (String origin : origins.split(",")) {
                String value = origin.trim();
                if (value.isEmpty()) continue;
                String error = validatePublicHttpsUrl(value, false);
                if (error != null) problems.add("CAMPUS_WEB_ORIGINS 含非法来源 " + value + "：" + error);
            }
        }

        String upload = text(environment, "campus.upload.base-url");
        String uploadError = validatePublicHttpsUrl(upload, false);
        if (uploadError != null) problems.add("CAMPUS_UPLOAD_BASE_URL：" + uploadError);

        if (bool(environment, "wxpay.enabled", false)) {
            validateWxPay(environment, appid, problems);
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("生产配置不安全，拒绝启动：\n- " + String.join("\n- ", problems)
                + "\n请补齐 .env 后重新启动（见 .env.production.example 与 docs/部署与上线指南.md）。");
        }
    }

    private static void validateWxPay(Environment environment, String wxAppId, List<String> problems) {
        String mchId = text(environment, "wxpay.mch-id");
        String apiV3 = text(environment, "wxpay.api-v3-key");
        String serial = text(environment, "wxpay.cert-serial-no");
        String keyPath = text(environment, "wxpay.private-key-path");
        String notify = text(environment, "wxpay.notify-url");
        String refundNotify = text(environment, "wxpay.refund-notify-url");
        String payAppId = text(environment, "wxpay.app-id");

        if (blank(mchId)) problems.add("WXPAY_MCH_ID 未配置");
        if (blank(apiV3) || apiV3.length() != 32) problems.add("WXPAY_API_V3_KEY 必须是 32 位");
        if (blank(serial)) problems.add("WXPAY_CERT_SERIAL_NO 未配置");
        if (blank(keyPath)) problems.add("WXPAY_PRIVATE_KEY_PATH 未配置");
        if (blank(payAppId) || !payAppId.equals(wxAppId)) problems.add("WXPAY_APP_ID 必须与 CAMPUS_WX_APPID 一致");
        String notifyError = validatePublicHttpsUrl(notify, true);
        if (notifyError != null) problems.add("WXPAY_NOTIFY_URL：" + notifyError);
        String refundError = validatePublicHttpsUrl(refundNotify, true);
        if (refundError != null) problems.add("WXPAY_REFUND_NOTIFY_URL：" + refundError);
    }

    /** 返回 null 表示合法；否则给中文原因。 */
    static String validatePublicHttpsUrl(String value, boolean pathRequired) {
        if (blank(value)) return "未配置";
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (Exception error) {
            return "不是合法 URL";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) return "必须使用 HTTPS";
        if (uri.getHost() == null || uri.getHost().isBlank()) return "缺少域名";
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (PLACEHOLDER_HOSTS.contains(host) || host.endsWith(".example") || host.endsWith(".example.com")) {
            return "仍是占位域名或本机域名";
        }
        if (pathRequired && (uri.getPath() == null || uri.getPath().isBlank() || "/".equals(uri.getPath()))) {
            return "必须包含具体路径";
        }
        return null;
    }

    private static boolean isDevelopmentSecret(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("change-me") || lower.contains("please-change") || lower.contains("local-dev")
            || lower.contains("dev-secret") || lower.contains("chang090011");
    }

    static boolean hasExplicitProfile(Environment environment) {
        return environment.getActiveProfiles().length > 0;
    }

    private static boolean isProduction(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) return true;
        }
        return false;
    }

    private static String text(Environment environment, String key) { return environment.getProperty(key, "").trim(); }

    private static boolean bool(Environment environment, String key, boolean fallback) {
        return Boolean.parseBoolean(environment.getProperty(key, String.valueOf(fallback)).trim());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}