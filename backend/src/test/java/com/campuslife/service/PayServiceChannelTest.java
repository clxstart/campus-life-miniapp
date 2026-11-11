package com.campuslife.service;

import com.campuslife.config.WxPayProperties;
import com.campuslife.ha.LocalClusterLock;
import com.campuslife.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.binarywang.wxpay.bean.result.WxPayOrderQueryV3Result;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Finding 02/04/11：回调验签失败不落库；对账分批、网络调用在事务外、异常留痕、超时关单先关渠道 */
class PayServiceChannelTest {
    private final UserOrderMapper orders = mock(UserOrderMapper.class);
    private final PaymentRecordMapper records = mock(PaymentRecordMapper.class);
    private final PaymentLedgerService ledger = mock(PaymentLedgerService.class);
    private final WxPayService wxPay = mock(WxPayService.class);
    private final WxPayProperties props = new WxPayProperties();
    private SimpleMeterRegistry meters;
    private PayService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        props.setEnabled(true);
        meters = new SimpleMeterRegistry();
        ObjectProvider<WxPayService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(wxPay);
        service = new PayService(orders, records, mock(RefundRecordMapper.class), mock(UserMapper.class), mock(ShopMapper.class),
            mock(UserRefundService.class), new ObjectMapper(), props, provider, mock(Environment.class),
            ledger, new PaymentAlerts(meters), new LocalClusterLock(), mock(CouponService.class));
    }

    private static Map<String, Object> row(long id, LocalDateTime createdAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id); row.put("outTradeNo", "T" + id); row.put("payAmountCents", 1500); row.put("createdAt", createdAt);
        return row;
    }

    private static WxPayOrderQueryV3Result query(String state, String txn, Integer total) {
        WxPayOrderQueryV3Result r = new WxPayOrderQueryV3Result();
        r.setTradeState(state); r.setTransactionId(txn);
        if (total != null) { WxPayOrderQueryV3Result.Amount a = new WxPayOrderQueryV3Result.Amount(); a.setTotal(total); r.setAmount(a); }
        return r;
    }

    @Test
    void forgedNotifyIsRejectedWithoutAnyDatabaseWrite() throws Exception {
        when(wxPay.parseOrderNotifyV3Result(anyString(), any())).thenThrow(new WxPayException("签名错误"));
        String forged = "{\"out_trade_no\":\"T1\",\"trade_state\":\"SUCCESS\"}";
        for (int i = 0; i < 50; i++) {
            assertThat(service.handleNotify(forged, "1", "n", "bad", "s")).isEqualTo(PayService.NotifyResult.REJECT);
        }
        verifyNoInteractions(records, ledger);
        verify(orders, never()).insert(any(com.campuslife.entity.UserOrder.class));
        assertThat(meters.counter("campus.pay.events", "event", "notify", "reason", "verify_failed").count()).isEqualTo(50);
    }

    @Test
    void ledgerFailureAsksChannelToRetry() throws Exception {
        var notify = new com.github.binarywang.wxpay.bean.notify.WxPayNotifyV3Result();
        var result = new com.github.binarywang.wxpay.bean.notify.WxPayNotifyV3Result.DecryptNotifyResult();
        result.setOutTradeNo("T1"); result.setTransactionId("W1"); result.setTradeState("SUCCESS");
        notify.setResult(result);
        when(wxPay.parseOrderNotifyV3Result(anyString(), any())).thenReturn(notify);
        when(ledger.applyVerifiedPayment(any(), any(), any(), any(), any(), any())).thenThrow(new org.springframework.dao.QueryTimeoutException("db down"));
        assertThat(service.handleNotify("{}", "1", "n", "sig", "s")).isEqualTo(PayService.NotifyResult.RETRY);
    }

    @Test
    void reconcileQueriesByOutTradeNoAndAppliesThroughLedger() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(orders.selectReconcileBatch(any(), any(), eq(100))).thenReturn(List.of(row(1, now.minusMinutes(8))));
        when(wxPay.queryOrderV3(null, "T1")).thenReturn(query("SUCCESS", "W1", 1500));
        PayService.ReconcileStats stats = service.reconcileOnce(now);
        assertThat(stats.paid()).isEqualTo(1);
        // 参数顺序：queryOrderV3(transactionId, outTradeNo)，历史代码把商户单号放错了位置
        verify(wxPay).queryOrderV3(null, "T1");
        verify(ledger).applyVerifiedPayment(eq("T1"), eq("W1"), eq("SUCCESS"), eq(1500), anyString(), eq("reconcile"));
    }

    @Test
    void channelErrorIsRecordedNotSwallowed() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(orders.selectReconcileBatch(any(), any(), anyInt())).thenReturn(List.of(row(2, now.minusMinutes(20))));
        when(wxPay.queryOrderV3(isNull(), anyString())).thenThrow(new WxPayException("timeout"));
        PayService.ReconcileStats stats = service.reconcileOnce(now);
        assertThat(stats.failed()).isEqualTo(1);
        verify(orders).recordPayCheck(eq(2L), contains("WxPayException"));
        // 渠道状态未知且未超过强制关单时限：不能本地关单，防止关单后到账
        verify(orders, never()).closeIfUnpaid(anyLong(), anyString(), anyString());
    }

    @Test
    void expiredUnpaidOrderClosesChannelBeforeLocal() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(orders.selectReconcileBatch(any(), any(), anyInt())).thenReturn(List.of(row(3, now.minusMinutes(20))));
        when(wxPay.queryOrderV3(null, "T3")).thenReturn(query("NOTPAY", null, null));
        when(orders.closeIfUnpaid(3L, "closed", "支付超时")).thenReturn(1);
        var inOrder = inOrder(wxPay, orders);
        assertThat(service.reconcileOnce(now).closed()).isEqualTo(1);
        inOrder.verify(wxPay).closeOrderV3("T3");
        inOrder.verify(orders).closeIfUnpaid(3L, "closed", "支付超时");
    }

    @Test
    void channelCloseFailureLeavesLocalOrderOpen() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(orders.selectReconcileBatch(any(), any(), anyInt())).thenReturn(List.of(row(4, now.minusMinutes(20))));
        when(wxPay.queryOrderV3(null, "T4")).thenReturn(query("NOTPAY", null, null));
        doThrow(new WxPayException("close failed")).when(wxPay).closeOrderV3("T4");
        assertThat(service.reconcileOnce(now).failed()).isEqualTo(1);
        verify(orders, never()).closeIfUnpaid(anyLong(), anyString(), anyString());
    }

    @Test
    void longUnreachableChannelForceClosesWithMarkedReason() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(orders.selectReconcileBatch(any(), any(), anyInt())).thenReturn(List.of(row(5, now.minusHours(3))));
        when(wxPay.queryOrderV3(isNull(), anyString())).thenThrow(new WxPayException("unreachable"));
        when(orders.closeIfUnpaid(eq(5L), eq("closed"), contains("未确认"))).thenReturn(1);
        assertThat(service.reconcileOnce(now).closed()).isEqualTo(1);
    }

    @Test
    void roundBudgetDefersRemainingOrders() throws Exception {
        ReflectionTestUtils.setField(service, "reconcileBudgetMs", 0L);
        LocalDateTime now = LocalDateTime.now();
        when(orders.selectReconcileBatch(any(), any(), anyInt())).thenReturn(List.of(row(6, now.minusMinutes(8)), row(7, now.minusMinutes(8))));
        when(wxPay.queryOrderV3(isNull(), anyString())).thenAnswer(inv -> { Thread.sleep(5); return query("NOTPAY", null, null); });
        PayService.ReconcileStats stats = service.reconcileOnce(now);
        assertThat(stats.scanned() + stats.deferred()).isEqualTo(2);
        assertThat(stats.deferred()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void adminStatusEndpointCannotWriteRefundStatesDirectly() {
        // 退款状态只能经退款流程写入；直接改 refunded 会让订单「已退款」却没有流水、没有出款
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.transitionStatus(9L, "refunded"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("退款流程");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.transitionStatus(9L, "refunding"))
            .isInstanceOf(IllegalStateException.class);
        verify(orders, never()).updateById(any(com.campuslife.entity.UserOrder.class));
    }

    @Test
    void reconcileMethodIsNotTransactional() throws Exception {
        assertThat(PayService.class.getMethod("reconcile").isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(PayService.class.getMethod("reconcileOnce", LocalDateTime.class).isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(PayService.class.getMethod("handleNotify", String.class, String.class, String.class, String.class, String.class)
            .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
    }
}
