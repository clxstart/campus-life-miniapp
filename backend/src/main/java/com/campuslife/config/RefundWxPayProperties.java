package com.campuslife.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** task-14: 拒单自动退款（微信官方 APIv3 SDK）配置，前缀 campus.wxpay。 */
@Data
@ConfigurationProperties(prefix = "campus.wxpay")
public class RefundWxPayProperties {
    private boolean enabled;
    private String merchantId = "";
    private String merchantSerialNumber = "";
    private String privateKeyPath = "";
    private String apiV3Key = "";
    private String refundNotifyUrl = "";
}
