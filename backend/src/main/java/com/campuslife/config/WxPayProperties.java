package com.campuslife.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** task-12: 外卖下单支付（binarywang weixin-java-pay）配置，前缀 wxpay。 */
@Data
@ConfigurationProperties(prefix = "wxpay")
public class WxPayProperties {
    private boolean enabled;
    private String appId = "";
    private String mchId = "";
    private String apiV3Key = "";
    private String certSerialNo = "";
    private String privateKeyPath = "";
    private String notifyUrl = "";
    private boolean sandbox;
}
