package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.entity.RefundRecord;
import com.campuslife.entity.UserOrder;
import com.campuslife.mapper.RefundRecordMapper;
import com.campuslife.mapper.UserOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 退款领取的单元语义（行锁/唯一约束/并发可见性在真实 MySQL 集成测试中验证） */
class RefundInitiationServiceTest {
    private final UserOrderMapper orderMapper = mock(UserOrderMapper.class);
    private final RefundRecordMapper refundRecordMapper = mock(RefundRecordMapper.class);
    private RefundInitiationService service;

    @BeforeEach
    void setUp() {
        service = new RefundInitiationService(orderMapper, refundRecordMapper);
    }

    private static UserOrder order(long id, long userId, String status, String payStatus) {
        UserOrder o = new UserOrder();
        o.setId(id);
        o.setUserId(userId);
        o.setStatus(status);
        o.setPayStatus(payStatus);
        o.setPayAmountCents(1500);
        return o;
    }

    private static RefundRecord record(String outRefundNo, String status) {
        RefundRecord r = new RefundRecord();
        r.setOrderId(7L);
        r.setOutRefundNo(outRefundNo);
        r.setStatus(status);
        return r;
    }

    @Test
    void firstRequestClaimsAndWritesDeterministicNumber() {
        when(orderMapper.findByIdForUpdate(7L)).thenReturn(order(7, 3, "paid", "paid"));
        when(orderMapper.claimRefund(7L)).thenReturn(1);
        when(refundRecordMapper.findByOrderId(7L)).thenReturn(record("RF7", "pending"));

        RefundInitiationService.Initiated result = service.initiate(7L, 3L, false, "不想要了");

        assertThat(result.created()).isTrue();
        assertThat(result.outRefundNo()).isEqualTo("RF7");
        assertThat(result.refundStatus()).isEqualTo("pending");
        verify(refundRecordMapper).insertRefundIfAbsent(7L, "RF7", 1500, "不想要了");
    }

    @Test
    void retryAfterRefundingReturnsExistingWithoutClaimingAgain() {
        when(orderMapper.findByIdForUpdate(7L)).thenReturn(order(7, 3, "refunding", "refunding"));
        when(refundRecordMapper.findByOrderId(7L)).thenReturn(record("RF7", "pending"));

        RefundInitiationService.Initiated result = service.initiate(7L, 3L, false, "");

        assertThat(result.created()).isFalse();
        assertThat(result.outRefundNo()).isEqualTo("RF7");
        verify(orderMapper, never()).claimRefund(anyLong());
        verify(refundRecordMapper, never()).insertRefundIfAbsent(anyLong(), anyString(), anyInt(), anyString());
    }

    @Test
    void loserOfConcurrentClaimReadsCommittedRefund() {
        when(orderMapper.claimRefund(7L)).thenReturn(0);
        // 第一次读到 paid；claim 失败后当前读回最新已提交状态：赢家已把订单改成 refunding
        when(orderMapper.findByIdForUpdate(7L)).thenReturn(order(7, 3, "paid", "paid"), order(7, 3, "refunding", "refunding"));
        when(refundRecordMapper.findByOrderId(7L)).thenReturn(record("RF7", "pending"));

        RefundInitiationService.Initiated result = service.initiate(7L, 3L, false, "");

        assertThat(result.created()).isFalse();
        assertThat(result.outRefundNo()).isEqualTo("RF7");
    }

    @Test
    void claimMissWithChangedStateFailsLoudly() {
        when(orderMapper.findByIdForUpdate(7L)).thenReturn(order(7, 3, "paid", "paid"), order(7, 3, "cancelled", "closed"));
        when(orderMapper.claimRefund(7L)).thenReturn(0);

        assertThatThrownBy(() -> service.initiate(7L, 3L, false, ""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("状态已变化");
    }

    @Test
    void nonRefundableStateIsRejected() {
        when(orderMapper.findByIdForUpdate(7L)).thenReturn(order(7, 3, "created", "unpaid"));
        assertThatThrownBy(() -> service.initiate(7L, 3L, false, ""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不能退款");
    }

    @Test
    void otherUsersOrderIsForbidden() {
        when(orderMapper.findByIdForUpdate(7L)).thenReturn(order(7, 3, "paid", "paid"));
        assertThatThrownBy(() -> service.initiate(7L, 99L, false, ""))
            .isInstanceOf(ForbiddenException.class);
        verify(orderMapper, never()).claimRefund(anyLong());
    }

    @Test
    void adminMayRefundWithoutOwnershipCheck() {
        when(orderMapper.findByIdForUpdate(7L)).thenReturn(order(7, 3, "paid", "paid"));
        when(orderMapper.claimRefund(7L)).thenReturn(1);
        when(refundRecordMapper.findByOrderId(7L)).thenReturn(record("RF7", "pending"));

        RefundInitiationService.Initiated result = service.initiate(7L, null, true, "管理端退款");
        assertThat(result.created()).isTrue();
    }

    @Test
    void refundableSetMatchesOrderStateMachine() {
        for (OrderStatus status : OrderStatus.values()) {
            boolean canTransit = OrderStatus.canTransit(status.value(), OrderStatus.REFUNDING.value());
            assertThat(RefundInitiationService.REFUNDABLE.contains(status.value()))
                .as("状态 %s 的可退判定必须与 OrderStatus 一致", status.value())
                .isEqualTo(canTransit);
        }
    }
}
