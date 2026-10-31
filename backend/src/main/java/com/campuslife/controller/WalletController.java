package com.campuslife.controller;

import com.campuslife.dto.ApiResponse;
import com.campuslife.service.FeatureFlags;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钱包状态占位接口 —— **故意不提供任何资金能力**。
 *
 * 为什么要有这个接口：前端需要知道"钱包不可用"而不是"钱包是 0 元"。
 * 如果干脆不提供接口，页面只能显示空白，用户会以为加载失败；
 * 如果提供一个返回 0 的接口，用户会以为那是自己的余额 —— 后者是绝对禁止的。
 *
 * 因此这里：
 *   1. **不返回任何金额字段**（没有 balance / available / withdrawable）；
 *   2. 永远 enabled=false，并给出真实原因；
 *   3. 不提供充值、支付、提现任何写接口。
 *
 * 只有当支付机构就本商户号与业务形态出具书面能力确认、且法务/财务结论书面落地后，
 * 才允许另立需求实现真实钱包；在此之前任何"余额"都不得被创建或展示。
 */
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private static final String REASON = "通用钱包（充值 / 余额支付 / 提现）尚未取得支付机构就本商户号与业务形态的书面能力确认，功能未开放";

    private final FeatureFlags flags;

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 始终关闭：即使有人误把开关打开，也不存在可用的钱包能力可展示
        out.put("enabled", false);
        out.put("reason", REASON);
        // 明确告诉前端"没有余额这个概念"，避免页面自行渲染 0.00
        out.put("hasBalance", false);
        out.put("entries", List.of());
        out.put("note", "兼职与配送的金额记录为履约账单，不是可提现余额，也不是已支付款项");
        out.put("flagEnabled", flags.enabledStrict("wallet.enabled"));
        return ApiResponse.ok(out);
    }
}
