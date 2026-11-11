package com.campuslife.service;

import com.campuslife.dto.CreateOrderRequest;
import com.campuslife.entity.UserOrder;
import com.campuslife.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Finding 05：同一幂等键重复/并发下单只得到同一订单；已关闭订单要求换键重下 */
class OrderIdempotencyTest {
    private final ShopMapper shops = mock(ShopMapper.class);
    private final UserOrderMapper orders = mock(UserOrderMapper.class);
    private final BizService service = new BizService(shops, mock(ShopGoodsMapper.class), orders, mock(UserMapper.class),
        mock(UserAddressMapper.class), mock(ModuleMapper.class), mock(RecommendMapper.class), new ObjectMapper(),
        mock(AuditService.class), mock(FeatureFlags.class), mock(CouponService.class));

    private static CreateOrderRequest request(String key) {
        CreateOrderRequest r = new CreateOrderRequest();
        r.setUserId(1L); r.setShopId(2L); r.setAddress("6 号楼 312"); r.setIdempotencyKey(key);
        CreateOrderRequest.Item item = new CreateOrderRequest.Item(); item.setGoodsId(3L); item.setCount(1);
        r.setItems(List.of(item));
        return r;
    }

    private static UserOrder existing(String status, String payStatus) {
        UserOrder o = new UserOrder();
        o.setId(42L); o.setStatus(status); o.setPayStatus(payStatus); o.setOutTradeNo("CL42"); o.setPayAmountCents(1500); o.setTotal(new BigDecimal("15.00"));
        return o;
    }

    @Test
    void repeatedSubmitReturnsSameOrderWithoutCreating() {
        when(orders.findIdByIdempotencyKey(1L, "attempt-0001", false)).thenReturn(42L);
        when(orders.selectById(42L)).thenReturn(existing("created", "unpaid"));
        Map<String, Object> first = service.order(request("attempt-0001"));
        Map<String, Object> second = service.order(request("attempt-0001"));
        assertThat(first.get("orderId")).isEqualTo(42L);
        assertThat(second.get("orderId")).isEqualTo(42L);
        verify(orders, never()).insert(any(UserOrder.class));
        verifyNoInteractions(shops);
    }

    @Test
    void concurrentFirstSubmitConvergesThroughLockingRead() {
        when(orders.findIdByIdempotencyKey(1L, "attempt-0002", false)).thenReturn(null);
        // 店铺校验阶段即模拟“对方已插入成功、本请求撞唯一键”
        when(shops.selectById(2L)).thenThrow(new DuplicateKeyException("uk_order_user_idem"));
        when(orders.findIdByIdempotencyKey(1L, "attempt-0002", true)).thenReturn(42L);
        when(orders.selectById(42L)).thenReturn(existing("created", "unpaid"));
        assertThat(service.order(request("attempt-0002")).get("orderId")).isEqualTo(42L);
        verify(orders).findIdByIdempotencyKey(1L, "attempt-0002", true);
    }

    @Test
    void closedOrderUnderSameKeyAsksForNewSubmission() {
        when(orders.findIdByIdempotencyKey(1L, "attempt-0003", false)).thenReturn(42L);
        when(orders.selectById(42L)).thenReturn(existing("closed", "closed"));
        assertThatThrownBy(() -> service.order(request("attempt-0003"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("重新提交");
    }

    @Test
    void paidOrderUnderSameKeyIsReturnedNotDuplicated() {
        when(orders.findIdByIdempotencyKey(1L, "attempt-0004", false)).thenReturn(42L);
        when(orders.selectById(42L)).thenReturn(existing("paid", "paid"));
        assertThat(service.order(request("attempt-0004")).get("payStatus")).isEqualTo("paid");
        verify(orders, never()).insert(any(UserOrder.class));
    }

    @Test
    void duplicateWithoutKeyIsNotSwallowed() {
        when(shops.selectById(2L)).thenThrow(new DuplicateKeyException("uk_order_out_trade_no"));
        assertThatThrownBy(() -> service.order(request(null))).isInstanceOf(DuplicateKeyException.class);
    }
}
