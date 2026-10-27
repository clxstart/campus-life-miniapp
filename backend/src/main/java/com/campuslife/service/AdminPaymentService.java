package com.campuslife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campuslife.entity.PaymentRecord;
import com.campuslife.entity.RefundRecord;
import com.campuslife.entity.Shop;
import com.campuslife.entity.User;
import com.campuslife.entity.UserOrder;
import com.campuslife.mapper.PaymentRecordMapper;
import com.campuslife.mapper.RefundRecordMapper;
import com.campuslife.mapper.ShopMapper;
import com.campuslife.mapper.UserMapper;
import com.campuslife.mapper.UserOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminPaymentService {
    private final UserOrderMapper orderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final ShopMapper shopMapper;
    private final UserMapper userMapper;
    private final PayService payService;

    public Map<String, Object> list(Long schoolId, String role, String payStatus, Long shopId, String startDate, String endDate, String keyword, int page, int size) {
        List<Long> shopIds = accessibleShopIds(schoolId, role, shopId);
        if (shopIds.isEmpty()) return Map.of("list", List.of(), "total", 0, "page", page, "size", size);
        LambdaQueryWrapper<UserOrder> wrapper = new LambdaQueryWrapper<UserOrder>().in(UserOrder::getShopId, shopIds).orderByDesc(UserOrder::getPaidAt, UserOrder::getId);
        if (payStatus != null && !payStatus.isBlank()) wrapper.eq(UserOrder::getPayStatus, payStatus.trim());
        LocalDateTime from = AdminOrderService.parseStart(startDate), to = AdminOrderService.parseEnd(endDate);
        if (from != null) wrapper.ge(UserOrder::getPaidAt, from);
        if (to != null) wrapper.lt(UserOrder::getPaidAt, to);
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            List<Long> ids = userMapper.selectList(new LambdaQueryWrapper<User>().like(User::getNickname, kw)).stream().map(User::getId).toList();
            List<String> tx = paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>().like(PaymentRecord::getTransactionId, kw)).stream().map(PaymentRecord::getOutTradeNo).toList();
            wrapper.and(w -> w.like(UserOrder::getOutTradeNo, kw).or().in(!ids.isEmpty(), UserOrder::getUserId, ids).or().in(!tx.isEmpty(), UserOrder::getOutTradeNo, tx));
        }
        IPage<UserOrder> result = orderMapper.selectPage(new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))), wrapper);
        return Map.of("list", result.getRecords().stream().map(this::row).toList(), "total", result.getTotal(), "page", result.getCurrent(), "size", result.getSize());
    }

    /**
     * 支付统计。
     *
     * 改造点：全部改成数据库聚合，不再把订单读进内存循环，也不再有逐条 selectById 的 N+1。
     * 改造前：1 万笔订单 = 1 万条对象进堆 + 未核验/退款各一轮 N+1 查询；
     * 改造后：固定 3 条聚合 SQL，耗时与数据量基本无关。
     */
    public Map<String, Object> stats(Long schoolId, String role, String startDate, String endDate) {
        List<Long> shopIds = accessibleShopIds(schoolId, role, null);
        LocalDateTime from = AdminOrderService.parseStart(startDate), to = AdminOrderService.parseEnd(endDate);
        // 超管不按店铺限制；其余角色没有任何可见店铺时不该看到数据
        boolean confined = !"super".equalsIgnoreCase(role);
        if (confined && shopIds.isEmpty()) {
            return Map.of("todayAmountCents", 0, "todayOrderCount", 0, "totalAmountCents", 0,
                "unverifiedCount", 0, "refundAmountCents", 0);
        }
        List<Long> scope = confined ? shopIds : null;

        Map<String, Object> total = orderMapper.sumPaid(scope, from, to);
        int totalAmount = num(total, "amount");
        int totalCount = num(total, "cnt");

        LocalDateTime today = LocalDateTime.now().toLocalDate().atStartOfDay();
        Map<String, Object> todayRow = orderMapper.sumPaid(scope, today, null);
        int todayAmount = num(todayRow, "amount");
        int todayCount = num(todayRow, "cnt");

        long unverified = orderMapper.countUnverifiedPayments(scope, from, to);
        long refundAmount = orderMapper.sumRefunded(scope, from, to);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("todayAmountCents", todayAmount);
        out.put("todayOrderCount", todayCount);
        out.put("totalAmountCents", totalAmount);
        out.put("totalOrderCount", totalCount);
        out.put("unverifiedCount", unverified);
        out.put("refundAmountCents", refundAmount);
        return out;
    }

    private static int num(Map<String, Object> row, String key) {
        if (row == null) return 0;
        Object value = row.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value == null) return 0;
        try { return new java.math.BigDecimal(String.valueOf(value)).intValue(); } catch (Exception e) { return 0; }
    }

    public Map<String, Object> detail(String outTradeNo, Long schoolId, String role) {
        UserOrder order = orderMapper.selectOne(new LambdaQueryWrapper<UserOrder>().eq(UserOrder::getOutTradeNo, outTradeNo).last("LIMIT 1"));
        if (order == null) throw new IllegalArgumentException("订单不存在");
        if (!accessibleShopIds(schoolId, role, order.getShopId()).contains(order.getShopId())) throw new com.campuslife.config.ForbiddenException("无权查看该订单");
        return payService.detail(order);
    }

    public String exportCsv(Long schoolId, String role, String payStatus, Long shopId, String startDate, String endDate, String keyword) {
        Map<String, Object> result = list(schoolId, role, payStatus, shopId, startDate, endDate, keyword, 1, 10000);
        StringBuilder csv = new StringBuilder("\uFEFF订单号,微信交易号,用户ID,店铺,金额(元),支付状态,对账状态,支付时间\n");
        for (Object value : (List<?>) result.get("list")) { Map<?, ?> row = (Map<?, ?>) value; csv.append(csv(row.get("outTradeNo"))).append(',').append(csv(row.get("transactionId"))).append(',').append(csv(row.get("userId"))).append(',').append(csv(row.get("shopName"))).append(',').append(csv(row.get("amount"))).append(',').append(csv(row.get("payStatus"))).append(',').append(csv(row.get("verified"))).append(',').append(csv(row.get("paidAt"))).append('\n'); }
        return csv.toString();
    }

    private Map<String, Object> row(UserOrder order) {
        User user = userMapper.selectById(order.getUserId()); Shop shop = shopMapper.selectById(order.getShopId());
        PaymentRecord record = paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>().eq(PaymentRecord::getOrderId, order.getId()).orderByDesc(PaymentRecord::getId).last("LIMIT 1"));
        Map<String, Object> row = new LinkedHashMap<>(); row.put("id", order.getId()); row.put("outTradeNo", order.getOutTradeNo()); row.put("transactionId", order.getTransactionId()); row.put("userId", order.getUserId()); row.put("userName", user == null ? "" : user.getNickname()); row.put("shopId", order.getShopId()); row.put("shopName", shop == null ? "" : shop.getName()); row.put("amountCents", order.getPayAmountCents()); row.put("amount", BigDecimal.valueOf(order.getPayAmountCents() == null ? 0 : order.getPayAmountCents()).movePointLeft(2)); row.put("payStatus", order.getPayStatus()); row.put("verified", record == null ? 1 : record.getVerified()); row.put("paidAt", order.getPaidAt()); return row;
    }
    private List<Long> accessibleShopIds(Long schoolId, String role, Long requestedShopId) { LambdaQueryWrapper<Shop> w = new LambdaQueryWrapper<Shop>().eq(Shop::getStatus, 1); if (!"super".equalsIgnoreCase(role)) w.eq(Shop::getSchoolId, schoolId); if (requestedShopId != null) w.eq(Shop::getId, requestedShopId); return shopMapper.selectList(w).stream().map(Shop::getId).toList(); }
    private static String csv(Object value) { String s = value == null ? "" : String.valueOf(value); return "\"" + s.replace("\"", "\"\"") + "\""; }
}
