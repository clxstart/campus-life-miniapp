package com.campuslife.service;

import com.campuslife.entity.RefundRecord;
import com.campuslife.mapper.MerchantMapper;
import com.campuslife.mapper.PaymentExceptionMapper;
import com.campuslife.mapper.RefundRecordMapper;
import com.wechat.pay.java.service.refund.model.Amount;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 退款账本状态机单测：只有渠道确认 SUCCESS 才写成功；处理中/失败/迟到结果不能把终态改回去。
 * 数据库层的条件 UPDATE 语义由 RefundCompensationIntegrationTest 在真实 MySQL 上验证。
 */
class MerchantRefundStatusServiceTest {
    private final MerchantMapper mapper = mock(MerchantMapper.class);
    private final PaymentExceptionMapper exceptionMapper = mock(PaymentExceptionMapper.class);
    private final RefundRecordMapper refundRecordMapper = mock(RefundRecordMapper.class);
    private MerchantRefundStatusService service;

    @BeforeEach
    void setUp() {
        service = new MerchantRefundStatusService(mapper, exceptionMapper, refundRecordMapper,
            mock(CouponService.class), mock(com.campuslife.mapper.EarningsLedgerMapper.class));
    }

    private static Map<String, Object> context(String refundStatus) {
        Map<String, Object> c = new HashMap<>();
        c.put("orderId", 7L);
        c.put("userId", 3L);
        c.put("outRefundNo", "PAC11");
        c.put("refundStatus", refundStatus);
        c.put("amountCents", 1500);
        c.put("transactionId", "W1");
        c.put("outTradeNo", "T1");
        return c;
    }

    private static Refund refund(Status status) {
        Refund r = new Refund();
        r.setOutRefundNo("PAC11");
        r.setOutTradeNo("T1");
        r.setTransactionId("W1");
        r.setRefundId("R1");
        r.setStatus(status);
        Amount amount = new Amount();
        amount.setRefund(1500L);
        amount.setTotal(1500L);
        r.setAmount(amount);
        return r;
    }

    @Test
    void successConvergesRefundOrderAndException() {
        when(mapper.refundContextByNumber("PAC11")).thenReturn(context("pending"));
        String status = service.applyCompensationResult(11L, "PAC11", refund(Status.SUCCESS));
        assertThat(status).isEqualTo("success");
        verify(mapper).updateRefund("PAC11", "R1", "success", "");
        verify(mapper).updatePayStatus(7L, "refunded");
        verify(mapper).updateOrderStatusForCompensation(7L);
        verify(mapper, never()).updateOrderStatusForRefund(anyLong(), anyString());
        verify(exceptionMapper).markResolved(eq(11L), contains("已自动退款"), eq(0));
    }

    @Test
    void processingIsNotReportedAsRefunded() {
        when(mapper.refundContextByNumber("PAC11")).thenReturn(context("refund_submitted"));
        String status = service.applyCompensationResult(11L, "PAC11", refund(Status.PROCESSING));
        assertThat(status).isEqualTo("pending");
        verify(mapper).updateRefund("PAC11", "R1", "pending", "");
        verify(mapper).updatePayStatus(7L, "refunding");
        verify(mapper, never()).updateOrderStatusForCompensation(anyLong());
        verify(exceptionMapper).markRefundSubmitted(eq(11L), contains("退款处理中"), eq(0));
        verify(exceptionMapper, never()).markResolved(anyLong(), anyString(), anyInt());
    }

    @Test
    void explicitFailureGoesToManualWithoutFakingRefund() {
        when(mapper.refundContextByNumber("PAC11")).thenReturn(context("refund_submitted"));
        String status = service.applyCompensationResult(11L, "PAC11", refund(Status.CLOSED));
        assertThat(status).isEqualTo("failed");
        verify(mapper).updateRefund("PAC11", "R1", "failed", "");
        verify(mapper).updatePayStatus(7L, "paid");
        verify(mapper, never()).updateOrderStatusForCompensation(anyLong());
        verify(exceptionMapper).markManual(eq(11L), contains("渠道退款失败"), eq(0));
        verify(exceptionMapper, never()).markResolved(anyLong(), anyString(), anyInt());
    }

    @Test
    void lateFailureAfterSuccessIsIgnored() {
        when(mapper.refundContextByNumber("PAC11")).thenReturn(context("success"));
        String status = service.applyCompensationResult(11L, "PAC11", refund(Status.CLOSED));
        // 已成功：账本不写任何回退，异常单保持 resolved（markResolved 的条件 UPDATE 在数据库层保证幂等）
        assertThat(status).isEqualTo("success");
        verify(mapper, never()).updateRefund(anyString(), anyString(), anyString(), anyString());
        verify(mapper, never()).updatePayStatus(anyLong(), anyString());
        verify(mapper, never()).updateOrderStatusForCompensation(anyLong());
        verify(exceptionMapper).markResolved(eq(11L), anyString(), eq(0));
    }

    @Test
    void lateProcessingAfterFailureStaysFailed() {
        when(mapper.refundContextByNumber("PAC11")).thenReturn(context("failed"));
        String status = service.applyCompensationResult(11L, "PAC11", refund(Status.PROCESSING));
        assertThat(status).isEqualTo("failed");
        verify(mapper, never()).updateRefund(anyString(), anyString(), anyString(), anyString());
        verify(exceptionMapper).markManual(eq(11L), anyString(), eq(0));
    }

    @Test
    void mismatchedAmountIsRejectedBeforeAnyWrite() {
        when(mapper.refundContextByNumber("PAC11")).thenReturn(context("refund_submitted"));
        Refund wrong = refund(Status.SUCCESS);
        wrong.getAmount().setRefund(100L);
        assertThatThrownBy(() -> service.applyCompensationResult(11L, "PAC11", wrong))
            .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).updateRefund(anyString(), anyString(), anyString(), anyString());
        verify(exceptionMapper, never()).markResolved(anyLong(), anyString(), anyInt());
    }

    @Test
    void claimCompensationWritesLedgerAndRejectsSecondCall() {
        when(exceptionMapper.claimForRefund(11L, "PAC11")).thenReturn(1);
        when(refundRecordMapper.findByOrderId(7L)).thenReturn(null);
        when(refundRecordMapper.insertRefundIfAbsent(7L, "PAC11", 1500, "订单已关闭，退还支付款")).thenReturn(1);
        assertThat(service.claimCompensation(11L, 7L, "PAC11", 1500, "订单已关闭，退还支付款")).isTrue();

        when(exceptionMapper.claimForRefund(11L, "PAC11")).thenReturn(0);
        assertThat(service.claimCompensation(11L, 7L, "PAC11", 1500, "订单已关闭，退还支付款")).isFalse();
    }

    @Test
    void claimCompensationDetectsConflictingLedgerAndGoesManual() {
        when(exceptionMapper.claimForRefund(11L, "PAC11")).thenReturn(1);
        when(refundRecordMapper.findByOrderId(7L)).thenReturn(existing("MR7"));
        assertThat(service.claimCompensation(11L, 7L, "PAC11", 1500, "订单已关闭，退还支付款")).isFalse();
        verify(refundRecordMapper, never()).insertRefundIfAbsent(anyLong(), anyString(), anyInt(), anyString());
        verify(exceptionMapper).markManual(eq(11L), contains("MR7"), eq(0));
    }

    private static RefundRecord existing(String outRefundNo) {
        RefundRecord r = new RefundRecord();
        r.setOutRefundNo(outRefundNo);
        return r;
    }
}
