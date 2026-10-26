package com.campuslife.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信支付唯一配置源，前缀 wxpay。
 *
 * 历史包袱：退款曾经用另一套前缀 campus.wxpay 单独配一次（merchant-id / merchant-serial-number /
 * private-key-path / api-v3-key / refund-notify-url 全是重复的），两份配置各自有 enabled 开关。
 * 结果是只改一边就能出现「能下单但不能退款」或「退款走模拟、钱没退」的半配置状态。
 * 现在统一到这里：下单、退款、退款回调共用同一组密钥与同一个开关。
 */
@Data
@ConfigurationProperties(prefix = "wxpay")
public class WxPayProperties {
    /** 总开关：false 时下单与退款都不会调用微信接口。 */
    private boolean enabled;
    private String appId = "";
    private String mchId = "";
    /** 商户 API 证书序列号（退款 SDK 用的就是这一个）。 */
    private String certSerialNo = "";
    private String apiV3Key = "";
    private String privateKeyPath = "";
    /** 支付结果回调地址（下单时传给微信）。 */
    private String notifyUrl = "";
    /** 退款结果回调地址（申请退款时传给微信）。 */
    private String refundNotifyUrl = "";
    private boolean sandbox;
}
