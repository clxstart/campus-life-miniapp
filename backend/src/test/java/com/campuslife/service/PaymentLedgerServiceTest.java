package com.campuslife.service;

import com.campuslife.entity.PaymentRecord;
import com.campuslife.entity.UserOrder;
import com.campuslife.mapper.PaymentExceptionMapper;
import com.campuslife.mapper.PaymentRecordMapper;
import com.campuslife.mapper.UserOrderMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Finding 02/04：入账唯一口的幂等、金额校验与关单竞态补偿 */
class PaymentLedgerServiceTest {
    private final UserOrderMapper orders = mock(UserOrderMapper.class);
    private final PaymentRecordMapper records = mock(PaymentRecordMapper.class);
    private final PaymentExceptionMapper exceptions = mock(PaymentExceptionMapper.class);
    private final PaymentLedgerService ledger = new PaymentLedgerService(orders, records, exceptions,
        new PaymentAlerts(new SimpleMeterRegistry()), mock(CouponService.class), mock(com.campuslife.mapper.EarningsLedgerMapper.class));

    private static UserOrder order(String status, String payStatus, String txn) {
        UserOrder o = new UserOrder();
        o.setId(7L); o.setOutTradeNo("T7"); o.setPayAmountCents(1500); o.setStatus(status); o.setPayStatus(payStatus); o.setTransactionId(txn);
        return o;
    }

    @Test
    void appliesSuccessExactlyOnce() {
        when(orders.selectOne(any())).thenReturn(order("created", "unpaid", null));
        when(orders.markPaidIfUnpaid(7L, "W1", 1500)).thenReturn(1);
        assertThat(ledger.applyVerifiedPayment("T7", "W1", "SUCCESS", 1500, "{}", "notify")).isEqualTo(PaymentLedgerService.Outcome.APPLIED);
        verify(records).insert(argThat((PaymentRecord r) -> r.getVerified() == 1 && "W1".equals(r.getTransactionId())));
    }

    @Test
    void duplicateNotifyDoesNotDoubleRecord() {
        when(orders.selectOne(any())).thenReturn(order("created", "unpaid", null));
        PaymentRecord existing = new PaymentRecord(); existing.setId(1L); existing.setTransactionId("W1"); existing.setVerified(1);
        when(records.selectOne(any())).thenReturn(existing);
        when(orders.markPaidIfUnpaid(anyLong(), anyString(), anyInt())).thenReturn(0);
        when(orders.selectById(7L)).thenReturn(order("paid", "paid", "W1"));
        assertThat(ledger.applyVerifiedPayment("T7", "W1", "SUCCESS", 1500, "{}", "notify")).isEqualTo(PaymentLedgerService.Outcome.DUPLICATE);
        verify(records, never()).insert(any(PaymentRecord.class));
        verify(exceptions, never()).insertIgnore(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void concurrentInsertRaceIsIdempotent() {
        when(orders.selectOne(any())).thenReturn(order("created", "unpaid", null));
        PaymentRecord winner = new PaymentRecord(); winner.setId(2L); winner.setVerified(1);
        when(records.selectOne(any())).thenReturn(null, winner);
        when(records.insert(any(PaymentRecord.class))).thenThrow(new DuplicateKeyException("uk_payment_transaction"));
        when(orders.markPaidIfUnpaid(anyLong(), anyString(), anyInt())).thenReturn(0);
        when(orders.selectById(7L)).thenReturn(order("paid", "paid", "W1"));
        assertThat(ledger.applyVerifiedPayment("T7", "W1", "SUCCESS", 1500, "{}", "notify")).isEqualTo(PaymentLedgerService.Outcome.DUPLICATE);
    }

    @Test
    void amountMismatchKeepsOrderUntouched() {
        when(orders.selectOne(any())).thenReturn(order("created", "unpaid", null));
        assertThat(ledger.applyVerifiedPayment("T7", "W1", "SUCCESS", 1, "{}", "notify")).isEqualTo(PaymentLedgerService.Outcome.EXCEPTION);
        verify(orders, never()).markPaidIfUnpaid(anyLong(), anyString(), anyInt());
        verify(records, never()).insert(any(PaymentRecord.class));
        verify(exceptions).insertIgnore(eq(7L), eq("T7"), eq("W1"), eq(1), eq("AMOUNT_MISMATCH"), eq("manual"), anyString());
    }

    @Test
    void unknownOrderOnlyRegistersException() {
        when(orders.selectOne(any())).thenReturn(null);
        assertThat(ledger.applyVerifiedPayment("NOPE", "W1", "SUCCESS", 1500, "{}", "notify")).isEqualTo(PaymentLedgerService.Outcome.EXCEPTION);
        verify(records, never()).insert(any(PaymentRecord.class));
        verify(exceptions).insertIgnore(isNull(), eq("NOPE"), eq("W1"), eq(1500), eq("UNKNOWN_ORDER"), eq("manual"), anyString());
    }

    @Test
    void paidAfterCloseEntersCompensation() {
        when(orders.selectOne(any())).thenReturn(order("created", "unpaid", null));
        when(orders.markPaidIfUnpaid(anyLong(), anyString(), anyInt())).thenReturn(0); // 关单先赢
        when(orders.selectById(7L)).thenReturn(order("closed", "closed", null));
        assertThat(ledger.applyVerifiedPayment("T7", "W1", "SUCCESS", 1500, "{}", "notify")).isEqualTo(PaymentLedgerService.Outcome.EXCEPTION);
        // 已验证扣款必须留痕（verified=1），并登记 open 状态的待退款异常单
        verify(records).insert(argThat((PaymentRecord r) -> r.getVerified() == 1));
        verify(exceptions).insertIgnore(eq(7L), eq("T7"), eq("W1"), eq(1500), eq("PAID_AFTER_CLOSE"), eq("open"), anyString());
    }

    @Test
    void secondDifferentTransactionIsFlagged() {
        when(orders.selectOne(any())).thenReturn(order("created", "unpaid", null));
        when(orders.markPaidIfUnpaid(anyLong(), anyString(), anyInt())).thenReturn(0);
        when(orders.selectById(7L)).thenReturn(order("accepted", "paid", "W-OTHER"));
        assertThat(ledger.applyVerifiedPayment("T7", "W2", "SUCCESS", 1500, "{}", "notify")).isEqualTo(PaymentLedgerService.Outcome.EXCEPTION);
        verify(exceptions).insertIgnore(eq(7L), eq("T7"), eq("W2"), eq(1500), eq("DUPLICATE_PAYMENT"), eq("manual"), anyString());
    }

    @Test
    void legacyUnverifiedRowIsUpgradedNotTreatedAsDuplicate() {
        when(orders.selectOne(any())).thenReturn(order("created", "unpaid", null));
        PaymentRecord forged = new PaymentRecord(); forged.setId(3L); forged.setTransactionId("W1"); forged.setVerified(0); forged.setAmountCents(1);
        when(records.selectOne(any())).thenReturn(forged);
        when(orders.markPaidIfUnpaid(7L, "W1", 1500)).thenReturn(1);
        assertThat(ledger.applyVerifiedPayment("T7", "W1", "SUCCESS", 1500, "{}", "notify")).isEqualTo(PaymentLedgerService.Outcome.APPLIED);
        verify(records).updateById(argThat((PaymentRecord r) -> r.getVerified() == 1 && r.getAmountCents() == 1500));
    }

    @Test
    void nonSuccessStateIsIgnored() {
        assertThat(ledger.applyVerifiedPayment("T7", "W1", "NOTPAY", 1500, "{}", "notify")).isEqualTo(PaymentLedgerService.Outcome.IGNORED);
        verifyNoInteractions(records, exceptions);
    }
}
