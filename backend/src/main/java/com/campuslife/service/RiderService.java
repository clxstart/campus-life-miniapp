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

    /** 订单池响应白名单：未分配订单只给遮掩号码，不含任何完整号码别名。 */
    static final List<String> POOL_FIELDS = List.of(
        "id", "shopId", "shopName", "shopAddress", "address", "contactName", "contactPhone",
        "items", "deliveryFee", "createdAt");
    /** 我的配送单响应白名单：完整号码改由 {@link #revealPhone} 按单、按承运关系单独查询。 */
    static final List<String> MINE_FIELDS = List.of(
        "id", "shopId", "shopName", "shopAddress", "shopPhone", "address", "contactName", "contactPhone",
        "items", "deliveryFee", "createdAt", "pickedAt", "status");

    public List<Map<String,Object>> pool(Long schoolId) {
        return mapper.pool(schoolId).stream().map(row -> project(row, POOL_FIELDS)).toList();
    }

    public List<Map<String,Object>> mine(Long riderId) {
        return mapper.mine(riderId).stream().map(row -> project(row, MINE_FIELDS)).toList();
    }

    /**
     * 承运骑手在配送中按需查看收件人完整号码（联系送达用）。
     * 只对“本人承运 + 配送中/已取货”的订单生效；订单池与已完成订单一律拒绝。
     */
    public String revealPhone(Long orderId, Long riderId) {
        String phone = mapper.activeContactPhone(orderId, riderId);
        if (phone == null) throw new com.campuslife.config.ForbiddenException("仅可查看本人配送中订单的联系电话");
        return phone;
    }

    /** 按白名单拷贝字段，并把联系电话统一遮掩；未列入白名单的列不会进入响应。 */
    static Map<String,Object> project(Map<String,Object> row, List<String> fields) {
        Map<String,Object> out = new LinkedHashMap<>();
        for (String key : fields) {
            if (!row.containsKey(key)) continue;
            out.put(key, "contactPhone".equals(key) ? maskPhone(row.get(key)) : row.get(key));
        }
        return out;
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
        // 送达与报酬记账必须在同一事务：记账失败就整体回滚，不能出现“已送达但收入丢了”的假成功。
        dispatch.recordDeliveryIncome(orderId, riderId);
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
