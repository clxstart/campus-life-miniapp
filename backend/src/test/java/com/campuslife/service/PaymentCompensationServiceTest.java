package com.campuslife.service;

import com.campuslife.config.WxPayProperties;
import com.campuslife.ha.LocalClusterLock;
import com.campuslife.mapper.PaymentExceptionMapper;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Finding 04 / followup：关单后到账的自动退款必须单号确定、可重试、结果一致且不会重复退款 */
class PaymentCompensationServiceTest {
    private final PaymentExceptionMapper mapper = mock(PaymentExceptionMapper.class);
    private final RefundService refunds = mock(RefundService.class);
    private final MerchantRefundStatusService statusService = mock(MerchantRefundStatusService.class);
    private final WxPayProperties props = new WxPayProperties();
    private PaymentCompensationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        props.setEnabled(true);
        ObjectProvider<RefundService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(refunds);
        service = new PaymentCompensationService(mapper, provider, props, new PaymentAlerts(new SimpleMeterRegistry()),
            new LocalClusterLock(), statusService);
    }

    private static Map<String, Object> row(String status, int attempts) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 11L); row.put("orderId", 7L); row.put("transactionId", "W1");
        row.put("amountCents", 1500); row.put("status", status); row.put("attempts", attempts);
        return row;
    }

    private static Refund refund(Status status) { Refund r = new Refund(); r.setStatus(status); r.setRefundId("R1"); return r; }

    @Test
    void claimsThenRefundsWithDeterministicNo() {
        when(mapper.selectCompensationBatch(20)).thenReturn(List.of(row("open", 0)));
        when(statusService.claimCompensation(11L, 7L, "PAC11", 1500, "订单已关闭，退还支付款")).thenReturn(true);
        Refund success = refund(Status.SUCCESS);
        when(refunds.create(any())).thenReturn(success);
        service.runOnce();
        verify(refunds).create(argThat((CreateRequest r) -> "PAC11".equals(r.getOutRefundNo()) && r.getAmount().getRefund() == 1500L));
        // 结果必须交给统一账本状态机落库（refund_record + 订单 + payment_exception 同事务收敛）
        verify(statusService).applyCompensationResult(11L, "PAC11", success);
    }

    @Test
    void otherNodeAlreadyClaimedMeansNoSubmit() {
        when(mapper.selectCompensationBatch(20)).thenReturn(List.of(row("open", 0)));
        when(statusService.claimCompensation(anyLong(), any(), anyString(), anyInt(), anyString())).thenReturn(false);
        service.runOnce();
        verifyNoInteractions(refunds);
        verify(statusService, never()).applyCompensationResult(anyLong(), anyString(), any());
    }

    @Test
    void uncertainSubmitStaysSubmittedAndRetriesSameNo() {
        when(mapper.selectCompensationBatch(20)).thenReturn(List.of(row("open", 0)));
        when(statusService.claimCompensation(anyLong(), any(), anyString(), anyInt(), anyString())).thenReturn(true);
        when(refunds.create(any())).thenThrow(new RuntimeException("timeout"));
        service.runOnce();
        // 结果未知：不能写失败/人工，保持 refund_submitted 等下一轮查询
        verify(mapper, never()).markManual(eq(11L), anyString(), anyInt());

        // 下一轮：查询失败 → 用同一单号重提，微信按单号去重
        when(mapper.selectCompensationBatch(20)).thenReturn(List.of(row("refund_submitted", 1)));
        when(refunds.queryByOutRefundNo(any())).thenThrow(new RuntimeException("not found"));
        doReturn(refund(Status.PROCESSING)).when(refunds).create(any());
        service.runOnce();
        verify(refunds, times(2)).create(argThat((CreateRequest r) -> "PAC11".equals(r.getOutRefundNo())));
        verify(mapper).markRefundSubmitted(eq(11L), anyString(), eq(1));
    }

    @Test
    void givesUpToManualAfterMaxAttempts() {
        when(mapper.selectCompensationBatch(20)).thenReturn(List.of(row("refund_submitted", PaymentCompensationService.MAX_ATTEMPTS)));
        when(refunds.queryByOutRefundNo(any())).thenThrow(new RuntimeException("down"));
        service.runOnce();
        verify(mapper).markManual(eq(11L), anyString(), eq(0));
        verify(refunds, never()).create(any());
    }

    @Test
    void refundNotConfiguredGoesManual() {
        props.setEnabled(false);
        when(mapper.selectCompensationBatch(20)).thenReturn(List.of(row("open", 0)));
        service.runOnce();
        verify(mapper).markManual(eq(11L), anyString(), eq(0));
        verifyNoInteractions(refunds);
    }

    @Test
    void retryUsesStoredRefundNumberInsteadOfRecomputing() {
        Map<String, Object> submitted = row("refund_submitted", 1);
        submitted.put("outRefundNo", "PAC-LEGACY-11");
        when(mapper.selectCompensationBatch(20)).thenReturn(List.of(submitted));
        when(refunds.queryByOutRefundNo(any())).thenThrow(new RuntimeException("down"));
        when(refunds.create(any())).thenReturn(refund(Status.PROCESSING));
        service.runOnce();
        // 已落库的单号优先：编号规则变化也不会换号重提
        verify(refunds).create(argThat((CreateRequest r) -> "PAC-LEGACY-11".equals(r.getOutRefundNo())));
    }

    @Test
    void exhaustedBudgetStopsClaimingMoreRows() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "budgetMs", -1L);
        when(mapper.selectCompensationBatch(20)).thenReturn(List.of(row("open", 0)));
        service.runOnce();
        // 预算耗尽时不领取新单，避免轮次越过租约
        verify(statusService, never()).claimCompensation(anyLong(), any(), anyString(), anyInt(), anyString());
        verifyNoInteractions(refunds);
    }

    @Test
    void queryResultIsAppliedThroughLedgerAndNeverDirectlyWritesResolved() {
        Refund processing = refund(Status.PROCESSING);
        when(mapper.selectCompensationBatch(20)).thenReturn(List.of(row("refund_submitted", 1)));
        when(refunds.queryByOutRefundNo(any())).thenReturn(processing);
        service.runOnce();
        verify(statusService).applyCompensationResult(11L, "PAC11", processing);
        // 处理中不能直接写 resolved；状态由统一账本服务按渠道结果决定
        verify(mapper, never()).markResolved(anyLong(), anyString(), anyInt());
    }
}
