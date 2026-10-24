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

/** task-14: 拒单自动退款（微信官方 APIv3 SDK）。默认关闭，campus.wxpay.enabled=true 时生效。 */
@Configuration
@EnableConfigurationProperties(RefundWxPayProperties.class)
public class RefundWxPayConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "campus.wxpay", name = "enabled", havingValue = "true")
    RSAAutoCertificateConfig wxPayConfig(RefundWxPayProperties properties) {
        if (blank(properties.getMerchantId()) || blank(properties.getMerchantSerialNumber()) ||
            blank(properties.getPrivateKeyPath()) || blank(properties.getApiV3Key()) || properties.getApiV3Key().length() != 32) {
            throw new IllegalStateException("微信退款已启用，但商户号、证书序列号、私钥路径或 32 位 APIv3 密钥未正确配置");
        }
        Path privateKey = Path.of(properties.getPrivateKeyPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(privateKey)) throw new IllegalStateException("微信支付商户私钥文件不存在");
        if (blank(properties.getRefundNotifyUrl())) throw new IllegalStateException("微信退款已启用，但退款通知地址未配置");
        URI notifyUri = URI.create(properties.getRefundNotifyUrl());
        if (!"https".equalsIgnoreCase(notifyUri.getScheme()) || notifyUri.getHost() == null) {
            throw new IllegalStateException("微信退款通知地址必须是可从公网访问的 HTTPS 地址");
        }

        return new RSAAutoCertificateConfig.Builder()
            .merchantId(properties.getMerchantId())
            .merchantSerialNumber(properties.getMerchantSerialNumber())
            .privateKeyFromPath(privateKey.toString())
            .apiV3Key(properties.getApiV3Key())
            .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "campus.wxpay", name = "enabled", havingValue = "true")
    RefundService refundService(RSAAutoCertificateConfig config) {
        return new RefundService.Builder().config(config).build();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
