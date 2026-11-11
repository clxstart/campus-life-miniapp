package com.campuslife.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 生产配置：缺关键安全项必须启动失败，且提示具体环境变量。 */
class ProductionConfigValidatorTest {

    private static MockEnvironment production() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("campus.wx.appid", "wx1234567890");
        env.setProperty("campus.wx.secret", "real-secret");
        env.setProperty("campus.auth.dev-login-enabled", "false");
        env.setProperty("campus.auth.dev-login-any-user", "false");
        env.setProperty("campus.auth.enforce", "true");
        env.setProperty("campus.pay.simulate", "false");
        env.setProperty("campus.jwt.secret", "0123456789abcdef0123456789abcdef");
        env.setProperty("campus.web.allowed-origins", "https://admin.campus.example.cn");
        env.setProperty("campus.upload.base-url", "https://api.campus.example.cn");
        return env;
    }

    @Test
    void completeProductionConfigPasses() {
        assertThatCode(() -> ProductionConfigValidator.validate(production())).doesNotThrowAnyException();
    }

    @Test
    void devProfileIsNotAffectedByProductionRules() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        assertThatCode(() -> ProductionConfigValidator.validate(env)).doesNotThrowAnyException();
    }

    @Test
    void missingProfileFailsFastInsteadOfFallingBackToDev() {
        MockEnvironment env = new MockEnvironment();
        assertThatThrownBy(() -> ProductionConfigValidator.validate(env))
            .hasMessageContaining("未显式指定 Spring profile")
            .hasMessageContaining("spring.profiles.active=prod");
    }

    @Test
    void missingWechatConfigFailsFast() {
        MockEnvironment env = production();
        env.setProperty("campus.wx.appid", "");
        env.setProperty("campus.wx.secret", "");
        assertThatThrownBy(() -> ProductionConfigValidator.validate(env))
            .hasMessageContaining("CAMPUS_WX_APPID");
    }

    @Test
    void touristAppIdIsNotProductionReady() {
        MockEnvironment env = production();
        env.setProperty("campus.wx.appid", "touristappid");
        assertThatThrownBy(() -> ProductionConfigValidator.validate(env))
            .hasMessageContaining("touristappid");
    }

    @Test
    void developmentLoginAndSimulationAreRejectedInProduction() {
        MockEnvironment env = production();
        env.setProperty("campus.auth.dev-login-enabled", "true");
        env.setProperty("campus.auth.dev-login-any-user", "true");
        env.setProperty("campus.pay.simulate", "true");
        assertThatThrownBy(() -> ProductionConfigValidator.validate(env))
            .hasMessageContaining("dev-login-enabled")
            .hasMessageContaining("dev-login-any-user")
            .hasMessageContaining("CAMPUS_PAY_SIMULATE");
    }

    @Test
    void authEnforcementCannotBeDisabled() {
        MockEnvironment env = production();
        env.setProperty("campus.auth.enforce", "false");
        assertThatThrownBy(() -> ProductionConfigValidator.validate(env))
            .hasMessageContaining("CAMPUS_AUTH_ENFORCE");
    }

    @Test
    void developmentJwtSecretIsRejected() {
        MockEnvironment env = production();
        env.setProperty("campus.jwt.secret", "campus-life-local-dev-secret-please-change-in-production");
        assertThatThrownBy(() -> ProductionConfigValidator.validate(env))
            .hasMessageContaining("CAMPUS_JWT_SECRET");
    }

    @Test
    void placeholderAndHttpUrlsAreRejected() {
        MockEnvironment env = production();
        env.setProperty("campus.web.allowed-origins", "http://localhost:5180");
        env.setProperty("campus.upload.base-url", "https://api.your-domain.example");
        assertThatThrownBy(() -> ProductionConfigValidator.validate(env))
            .hasMessageContaining("CAMPUS_WEB_ORIGINS")
            .hasMessageContaining("CAMPUS_UPLOAD_BASE_URL");
    }

    @Test
    void enabledWxPayRequiresCompleteConfig() {
        MockEnvironment env = production();
        env.setProperty("wxpay.enabled", "true");
        assertThatThrownBy(() -> ProductionConfigValidator.validate(env))
            .hasMessageContaining("WXPAY_MCH_ID")
            .hasMessageContaining("WXPAY_API_V3_KEY")
            .hasMessageContaining("WXPAY_NOTIFY_URL")
            .hasMessageContaining("WXPAY_REFUND_NOTIFY_URL");
    }

    @Test
    void emptyWxPayUrlsAreFineWhenPaymentIsDisabled() {
        MockEnvironment env = production();
        env.setProperty("wxpay.enabled", "false");
        env.setProperty("wxpay.notify-url", "");
        env.setProperty("wxpay.refund-notify-url", "");
        assertThatCode(() -> ProductionConfigValidator.validate(env)).doesNotThrowAnyException();
    }
    @Test
    void fullWxPayConfigPasses() {
        MockEnvironment env = production();
        env.setProperty("wxpay.enabled", "true");
        env.setProperty("wxpay.app-id", "wx1234567890");
        env.setProperty("wxpay.mch-id", "1900000001");
        env.setProperty("wxpay.api-v3-key", "0123456789abcdef0123456789abcdef");
        env.setProperty("wxpay.cert-serial-no", "ABC123");
        env.setProperty("wxpay.private-key-path", "/opt/campus/apiclient_key.pem");
        env.setProperty("wxpay.notify-url", "https://api.campus.example.cn/api/pay/notify");
        env.setProperty("wxpay.refund-notify-url", "https://api.campus.example.cn/api/pay/refund/notify");
        assertThatCode(() -> ProductionConfigValidator.validate(env)).doesNotThrowAnyException();
    }
}