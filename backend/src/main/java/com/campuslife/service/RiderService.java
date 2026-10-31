package com.campuslife.service;

import com.campuslife.mapper.RiderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RiderService {
    private final RiderMapper mapper;
    private final DispatchService dispatch;
    private final FeatureFlags flags;

    public Map<String,Object> stats(Long schoolId, Long riderId) {
        return mapper.stats(schoolId, riderId);
    }

    public List<Map<String,Object>> pool(Long schoolId) {
        List<Map<String,Object>> orders = mapper.pool(schoolId);
        orders.forEach(order -> order.put("contactPhone", maskPhone(order.get("contactPhoneFull"))));
        return orders;
    }

    public List<Map<String,Object>> mine(Long riderId) {
        List<Map<String,Object>> orders = mapper.mine(riderId);
        orders.forEach(order -> order.put("contactPhone", maskPhone(order.get("contactPhoneFull"))));
        return orders;
    }

    @Transactional
    public void grab(Long orderId, Long riderId, String riderName) {
        // 方案 §7.1：不开放全校自由抢单，默认走楼栋责任区自动派单。
        // 抢单接口保留但默认关闭，需要运营显式开启才可用（fail-closed）。
        if (!flags.enabledStrict("rider.grab.enabled")) {
            throw new com.campuslife.config.ForbiddenException("当前采用楼栋责任区派单，暂不开放自由抢单，请等待系统派单或联系负责人");
        }
        // MySQL's multi-table UPDATE reports both the order and rider rows when both changed.
        if (mapper.grab(orderId, riderId, riderName == null ? "" : riderName) == 0) {
            throw new IllegalStateException("手慢了，这单已被抢走或不在本校配送范围");
        }
    }

    /**
     * 标记取餐：delivering → picked
     * SQL 已带 status='delivering' 前置条件，这里补状态机校验做双保险。
     */
    @Transactional
    public void pick(Long orderId, Long riderId) {
        Map<String, Object> order = mapper.dispatchOf(orderId, riderId);
        if (order == null) throw new IllegalStateException("订单不存在或不属于你");
        OrderStatus.requireTransition(String.valueOf(order.get("status")), OrderStatus.PICKED.value());
        if (mapper.pick(orderId, riderId) != 1) {
            throw new IllegalStateException("订单状态已变化，无法标记取货");
        }
    }

    /**
     * 标记送达：picked → completed
     * 完成后重算骑手忙碌状态（可能还有其他进行中的订单）。
     */
    @Transactional
    public void deliver(Long orderId, Long riderId) {
        Map<String, Object> order = mapper.dispatchOf(orderId, riderId);
        if (order == null) throw new IllegalStateException("订单不存在或不属于你");
        OrderStatus.requireTransition(String.valueOf(order.get("status")), OrderStatus.COMPLETED.value());
        if (mapper.deliver(orderId, riderId) != 1) {
            throw new IllegalStateException("订单状态已变化，无法标记送达");
        }
        mapper.refreshRiderStatus(riderId);
        // 送达才记配送报酬，且只记一次（delivery_pay_recorded + ledger.biz_no 双保险）。
        try {
            dispatch.recordDeliveryIncome(orderId, riderId);
        } catch (Exception e) {
            // 记账失败不能把"已送达"这个事实回滚掉，留给对账处理
            org.slf4j.LoggerFactory.getLogger(RiderService.class)
                .warn("[campus] 订单 {} 配送报酬记账失败: {}", orderId, e.getMessage());
        }
    }

    public Map<String,Object> history(Long riderId, String startDate, String endDate, int page, int size) {
        String start = validDate(startDate);
        String end = validDate(endDate);
        if (start != null && end != null && start.compareTo(end) > 0) throw new IllegalArgumentException("开始日期不能晚于结束日期");
        int currentPage = Math.max(1, page);
        int pageSize = Math.min(Math.max(1, size), 100);
        Map<String,Object> summary = mapper.monthlySummary(riderId);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("list", mapper.history(riderId, start, end, pageSize, (currentPage - 1) * pageSize));
        result.put("total", mapper.countHistory(riderId, start, end));
        result.put("page", currentPage);
        result.put("size", pageSize);
        result.put("monthCount", summary.get("monthCount"));
        result.put("monthFee", summary.get("monthFee"));
        return result;
    }

    public Map<String,Object> studentOrder(Long orderId, Long userId) {
        Map<String,Object> order = mapper.studentOrder(orderId, userId);
        if (order == null) throw new IllegalArgumentException("订单不存在");
        order.put("contactPhone", maskPhone(order.get("contactPhone")));
        order.put("riderPhone", maskPhone(order.get("riderPhone")));
        return order;
    }

    private static String validDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value).toString(); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd"); }
    }

    private static String maskPhone(Object raw) {
        if (raw == null) return "";
        String phone = String.valueOf(raw);
        return phone.matches("^\\d{7,}$") ? phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4) : phone;
    }
}
