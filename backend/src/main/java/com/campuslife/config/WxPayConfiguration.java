package com.campuslife.config;

import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.service.impl.WxPayServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WxPayProperties.class)
public class WxPayConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "wxpay", name = "enabled", havingValue = "true")
    public WxPayService wxPayService(WxPayProperties properties) {
        WxPayConfig config = new WxPayConfig();
        config.setAppId(properties.getAppId());
        config.setMchId(properties.getMchId());
        config.setApiV3Key(properties.getApiV3Key());
        config.setCertSerialNo(properties.getCertSerialNo());
        config.setPrivateKeyPath(properties.getPrivateKeyPath());
        config.setNotifyUrl(properties.getNotifyUrl());
        config.setUseSandboxEnv(properties.isSandbox());
        WxPayServiceImpl service = new WxPayServiceImpl();
        service.setConfig(config);
        return service;
    }
}
