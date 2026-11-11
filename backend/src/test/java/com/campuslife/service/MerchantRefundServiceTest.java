package com.campuslife.service;

import com.campuslife.config.WxPayProperties;
import com.campuslife.mapper.MerchantMapper;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 渠道提交回归：商家拒单（MR+id、pay_status=paid）与用户退款（RF+id、pay_status=refunding）
 * 共用这一处，必须都能提交、同号重试、终态不重复提交。
 */
class MerchantRefundServiceTest {
    private final MerchantMapper mapper = mock(MerchantMapper.class);
    private final RefundService refunds = mock(RefundService.class);
    private final MerchantRefundStatusService statusService = mock(MerchantRefundStatusService.class);
    private final WxPayProperties props = new WxPayProperties();
    private MerchantRefundService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        props.setEnabled(true);
        ObjectProvider<RefundService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(refunds);
        service = new MerchantRefundService(mapper, provider, props, statusService);
    }

    private static Map<String, Object> context(String no, String refundStatus, String payStatus, String reason) {
        Map<String, Object> c = new HashMap<>();
        c.put("orderId", 7L); c.put("outRefundNo", no); c.put("refundStatus", refundStatus);
        c.put("payStatus", payStatus); c.put("amountCents", 1800); c.put("transactionId", "W7");
        c.put("outTradeNo", "T7"); c.put("reason", reason);
        return c;
    }

    @Test
    void merchantRejectSubmitsWithStableNumberAndRealReason() {
        when(mapper.refundContext(7L)).thenReturn(context("MR7", "pending", "paid", "商家拒单：食材售罄"));
        Refund ok = new Refund(); ok.setStatus(Status.SUCCESS);
        when(refunds.create(any())).thenReturn(ok);
        when(statusService.applyProviderResult("MR7", ok)).thenReturn("success");

        assertThat(service.submitAfterCommit(7L)).isEqualTo("success");
        verify(refunds).create(argThat((CreateRequest r) -> "MR7".equals(r.getOutRefundNo())
            && "W7".equals(r.getTransactionId()) && r.getAmount().getRefund() == 1800L
            && "商家拒单：食材售罄".equals(r.getReason())));
    }

    @Test
    void userRefundInRefundingStateIsSubmittable() {
        when(mapper.refundContext(7L)).thenReturn(context("RF7", "pending", "refunding", "不想要了"));
        when(refunds.create(any())).thenReturn(new Refund());
        when(statusService.applyProviderResult(eq("RF7"), any())).thenReturn("pending");
        assertThat(service.submitAfterCommit(7L)).isEqualTo("pending");
        verify(statusService, never()).markFailed(anyString(), anyString());
    }

    @Test
    void createFailureQueriesSameNumberThenKeepsPending() {
        when(mapper.refundContext(7L)).thenReturn(context("MR7", "pending", "paid", "商家拒单"));
        when(refunds.create(any())).thenThrow(new RuntimeException("timeout"));
        when(refunds.queryByOutRefundNo(any())).thenThrow(new RuntimeException("down"));
        when(statusService.keepPending(eq("MR7"), anyString())).thenReturn("pending");

        assertThat(service.submitAfterCommit(7L)).isEqualTo("pending");
        verify(refunds).queryByOutRefundNo(argThat(q -> "MR7".equals(q.getOutRefundNo())));
        verify(statusService, never()).markFailed(anyString(), anyString());
    }

    @Test
    void terminalRefundIsNotSubmittedAgain() {
        when(mapper.refundContext(7L)).thenReturn(context("MR7", "success", "refunded", "商家拒单"));
        assertThat(service.submitAfterCommit(7L)).isEqualTo("success");
        verifyNoInteractions(refunds);
    }

    @Test
    void alreadyRefundedPayStatusIsRejectedWithoutChannelCall() {
        when(mapper.refundContext(7L)).thenReturn(context("MR7", "pending", "refunded", "商家拒单"));
        when(statusService.markFailed(eq("MR7"), anyString())).thenReturn("failed");
        assertThat(service.submitAfterCommit(7L)).isEqualTo("failed");
        verifyNoInteractions(refunds);
    }
}
