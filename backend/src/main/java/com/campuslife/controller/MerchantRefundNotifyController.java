package com.campuslife.controller;

import com.campuslife.config.RefundWxPayProperties;
import com.campuslife.service.MerchantRefundStatusService;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/pay/refund")
@RequiredArgsConstructor
public class MerchantRefundNotifyController {
    private final ObjectProvider<RSAAutoCertificateConfig> configProvider;
    private final RefundWxPayProperties properties;
    private final MerchantRefundStatusService statusService;

    @PostMapping("/notify")
    public ResponseEntity<Map<String,String>> notify(
        @RequestHeader("Wechatpay-Serial") String serial,
        @RequestHeader("Wechatpay-Timestamp") String timestamp,
        @RequestHeader("Wechatpay-Nonce") String nonce,
        @RequestHeader("Wechatpay-Signature") String signature,
        @RequestHeader(value = "Wechatpay-Signature-Type", required = false) String signType,
        @RequestBody String body) {
        RSAAutoCertificateConfig config = configProvider.getIfAvailable();
        if (!properties.isEnabled() || config == null) return response(HttpStatus.SERVICE_UNAVAILABLE, "FAIL", "退款通知未启用");

        RefundNotification notification;
        try {
            RequestParam.Builder request = new RequestParam.Builder()
                .serialNumber(serial).timestamp(timestamp).nonce(nonce).signature(signature).body(body);
            if (signType != null && !signType.isBlank()) request.signType(signType);
            notification = new NotificationParser(config).parse(request.build(), RefundNotification.class);
        } catch (RuntimeException invalidSignatureOrBody) {
            return response(HttpStatus.UNAUTHORIZED, "FAIL", "通知验签或解密失败");
        }

        try {
            statusService.applyNotification(notification);
            return response(HttpStatus.OK, "SUCCESS", "成功");
        } catch (IllegalArgumentException invalidNotification) {
            return response(HttpStatus.BAD_REQUEST, "FAIL", "退款通知与本地记录不匹配");
        } catch (RuntimeException databaseFailure) {
            return response(HttpStatus.INTERNAL_SERVER_ERROR, "FAIL", "处理失败");
        }
    }

    private ResponseEntity<Map<String,String>> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
