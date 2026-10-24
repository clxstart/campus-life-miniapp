package com.campuslife.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "campus.wxpay")
public class WxPayProperties {
    private boolean enabled;
    private String merchantId = "";
    private String merchantSerialNumber = "";
    private String privateKeyPath = "";
    private String apiV3Key = "";
    private String refundNotifyUrl = "";
}
