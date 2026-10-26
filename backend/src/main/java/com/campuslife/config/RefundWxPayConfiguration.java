package com.campuslife.config;

import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.service.refund.RefundService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 退款（微信官方 APIv3 SDK）Bean。
 *
 * 与下单共用 {@link WxPayProperties}（前缀 wxpay），不再单独维护 campus.wxpay：
 * 同一个商户号、同一份私钥、同一个 apiV3 密钥、同一个 enabled 开关。
 */
@Configuration
@EnableConfigurationProperties(WxPayProperties.class)
public class RefundWxPayConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "wxpay", name = "enabled", havingValue = "true")
    RSAAutoCertificateConfig wxPayConfig(WxPayProperties properties) {
        if (blank(properties.getMchId()) || blank(properties.getCertSerialNo()) ||
            blank(properties.getPrivateKeyPath()) || blank(properties.getApiV3Key()) || properties.getApiV3Key().length() != 32) {
            throw new IllegalStateException("微信支付已启用，但商户号、证书序列号、私钥路径或 32 位 APIv3 密钥未正确配置");
        }
        Path privateKey = Path.of(properties.getPrivateKeyPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(privateKey)) throw new IllegalStateException("微信支付商户私钥文件不存在");
        if (blank(properties.getRefundNotifyUrl())) throw new IllegalStateException("微信支付已启用，但退款通知地址（wxpay.refund-notify-url）未配置");
        URI notifyUri = URI.create(properties.getRefundNotifyUrl());
        if (!"https".equalsIgnoreCase(notifyUri.getScheme()) || notifyUri.getHost() == null) {
            throw new IllegalStateException("微信退款通知地址必须是可从公网访问的 HTTPS 地址");
        }

        return new RSAAutoCertificateConfig.Builder()
            .merchantId(properties.getMchId())
            .merchantSerialNumber(properties.getCertSerialNo())
            .privateKeyFromPath(privateKey.toString())
            .apiV3Key(properties.getApiV3Key())
            .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "wxpay", name = "enabled", havingValue = "true")
    RefundService refundService(RSAAutoCertificateConfig config) {
        return new RefundService.Builder().config(config).build();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
