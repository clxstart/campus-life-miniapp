package com.campuslife.service;

import com.campuslife.config.WxPayProperties;
import com.campuslife.entity.RefundRecord;
import com.campuslife.entity.UserOrder;
import com.campuslife.mapper.RefundRecordMapper;
import com.campuslife.mapper.RiderMapper;
import com.campuslife.mapper.UserOrderMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 退款编排：并发/重试只复用既有单号；只有 pending 才重新提交；模拟退款也必须走统一账本 */
class UserRefundServiceTest {
    private final RefundInitiationService initiation = mock(RefundInitiationService.class);
    private final MerchantRefundService merchantRefundService = mock(MerchantRefundService.class);
    private final MerchantRefundStatusService statusService = mock(MerchantRefundStatusService.class);
    private final UserOrderMapper orderMapper = mock(UserOrderMapper.class);
    private final RefundRecordMapper refundRecordMapper = mock(RefundRecordMapper.class);
    private final RiderMapper riderMapper = mock(RiderMapper.class);
    private final WxPayProperties properties = new WxPayProperties();
    private final Environment environment = mock(Environment.class);
    private UserRefundService service;

    @BeforeEach
    void setUp() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        service = new UserRefundService(initiation, merchantRefundService, statusService, orderMapper,
            refundRecordMapper, riderMapper, properties, environment, new PaymentAlerts(new SimpleMeterRegistry()));
    }

    private static RefundRecord record(String no, String status) {
        RefundRecord r = new RefundRecord();
        r.setOrderId(7L);
        r.setOutRefundNo(no);
        r.setStatus(status);
        return r;
    }

    private static UserOrder paidOrder() {
        UserOrder o = new UserOrder();
        o.setId(7L);
        o.setUserId(3L);
        o.setStatus("refunding");
        o.setPayStatus("refunding");
        o.setPayAmountCents(1500);
        o.setOutTradeNo("T1");
        o.setTransactionId("SIM-W1");
        return o;
    }

    @Test
    void duplicateRequestReturnsExistingSuccessWithoutSubmittingAgain() {
        properties.setEnabled(true);
        when(initiation.initiate(7L, 3L, false, "")).thenReturn(
            new RefundInitiationService.Initiated(7L, "RF7", "success", "R1", "refunded", "refunded", false));
        when(refundRecordMapper.findByOrderId(7L)).thenReturn(record("RF7", "success"));

        UserRefundService.Outcome outcome = service.refund(7L, 3L, "");

        assertThat(outcome.idempotent()).isTrue();
        assertThat(outcome.outRefundNo()).isEqualTo("RF7");
        assertThat(outcome.refundStatus()).isEqualTo("success");
        verifyNoInteractions(merchantRefundService);
    }

    @Test
    void timeoutRetryOfPendingUsesSameNumber() {
        properties.setEnabled(true);
        when(initiation.initiate(7L, 3L, false, "")).thenReturn(
            new RefundInitiationService.Initiated(7L, "RF7", "pending", "", "refunding", "refunding", false));
        when(merchantRefundService.submitAfterCommit(7L)).thenReturn("pending");
        when(refundRecordMapper.findByOrderId(7L)).thenReturn(record("RF7", "pending"));

        UserRefundService.Outcome outcome = service.refund(7L, 3L, "");

        assertThat(outcome.idempotent()).isTrue();
        assertThat(outcome.outRefundNo()).isEqualTo("RF7");
        // 复用同一单号重试，微信按 out_refund_no 去重；不会生成新单号
        verify(merchantRefundService).submitAfterCommit(7L);
    }

    @Test
    void simulatedRefundGoesThroughSharedLedger() {
        when(initiation.initiate(7L, 3L, false, "不想要了")).thenReturn(
            new RefundInitiationService.Initiated(7L, "RF7", "pending", "", "refunding", "refunding", true));
        when(orderMapper.selectById(7L)).thenReturn(paidOrder());
        when(statusService.applyProviderResult(eq("RF7"), any())).thenReturn("success");
        when(refundRecordMapper.findByOrderId(7L)).thenReturn(record("RF7", "success"));
        ReflectionTestUtils.setField(service, "simulate", true);

        UserRefundService.Outcome outcome = service.refund(7L, 3L, "不想要了");

        assertThat(outcome.refundStatus()).isEqualTo("success");
        assertThat(outcome.outRefundNo()).isEqualTo("RF7");
        verify(merchantRefundService, never()).submitAfterCommit(anyLong());
        verify(statusService).applyProviderResult(eq("RF7"), any());
    }

    @Test
    void simulationDisabledFailsBeforeAnyDatabaseWrite() {
        ReflectionTestUtils.setField(service, "simulate", false);
        assertThatThrownBy(() -> service.refund(7L, 3L, ""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("暂不能在线退款");
        verifyNoInteractions(initiation, refundRecordMapper);
    }

    @Test
    void submitUncertaintyKeepsPendingRatherThanFailing() {
        properties.setEnabled(true);
        when(initiation.initiate(7L, 3L, false, "")).thenReturn(
            new RefundInitiationService.Initiated(7L, "RF7", "pending", "", "refunding", "refunding", true));
        when(merchantRefundService.submitAfterCommit(7L)).thenThrow(new RuntimeException("timeout"));
        when(refundRecordMapper.findByOrderId(7L)).thenReturn(record("RF7", "pending"));

        UserRefundService.Outcome outcome = service.refund(7L, 3L, "");

        assertThat(outcome.refundStatus()).isEqualTo("pending");
        assertThat(outcome.outRefundNo()).isEqualTo("RF7");
    }

    @Test
    void adminRefundSkipsOwnershipCheckByCallingInitiationAsAdmin() {
        properties.setEnabled(true);
        when(initiation.initiate(7L, null, true, "管理端退款")).thenReturn(
            new RefundInitiationService.Initiated(7L, "RF7", "success", "R1", "refunded", "refunded", false));
        when(refundRecordMapper.findByOrderId(7L)).thenReturn(record("RF7", "success"));

        service.refundAsAdmin(7L, "管理端退款");
        verify(initiation).initiate(7L, null, true, "管理端退款");
    }
}
