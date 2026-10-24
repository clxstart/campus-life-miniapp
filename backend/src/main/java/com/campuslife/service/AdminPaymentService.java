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

    public Map<String, Object> stats(Long schoolId, String role, String startDate, String endDate) {
        List<Long> shopIds = accessibleShopIds(schoolId, role, null);
        LocalDateTime from = AdminOrderService.parseStart(startDate), to = AdminOrderService.parseEnd(endDate);
        LambdaQueryWrapper<UserOrder> wrapper = new LambdaQueryWrapper<UserOrder>().in(!shopIds.isEmpty(), UserOrder::getShopId, shopIds).eq(UserOrder::getPayStatus, "paid");
        if (from != null) wrapper.ge(UserOrder::getPaidAt, from); if (to != null) wrapper.lt(UserOrder::getPaidAt, to);
        List<UserOrder> paid = shopIds.isEmpty() ? List.of() : orderMapper.selectList(wrapper);
        LocalDateTime today = LocalDateTime.now().toLocalDate().atStartOfDay();
        int todayAmount = 0, todayCount = 0, totalAmount = 0;
        for (UserOrder order : paid) { int cents = order.getPayAmountCents() == null ? 0 : order.getPayAmountCents(); totalAmount += cents; if (order.getPaidAt() != null && !order.getPaidAt().isBefore(today)) { todayAmount += cents; todayCount++; } }
        LambdaQueryWrapper<PaymentRecord> recordWrapper = new LambdaQueryWrapper<PaymentRecord>().eq(PaymentRecord::getVerified, 0);
        if (from != null) recordWrapper.ge(PaymentRecord::getCreatedAt, from); if (to != null) recordWrapper.lt(PaymentRecord::getCreatedAt, to);
        int unverified = paymentRecordMapper.selectList(recordWrapper).stream().filter(record -> {
            UserOrder order = orderMapper.selectById(record.getOrderId());
            return order != null && ("super".equalsIgnoreCase(role) || shopIds.contains(order.getShopId()));
        }).toList().size();
        LambdaQueryWrapper<RefundRecord> refundWrapper = new LambdaQueryWrapper<RefundRecord>().eq(RefundRecord::getStatus, "success");
        if (from != null) refundWrapper.ge(RefundRecord::getCreatedAt, from); if (to != null) refundWrapper.lt(RefundRecord::getCreatedAt, to);
        int refundAmount = refundRecordMapper.selectList(refundWrapper).stream().filter(r -> { UserOrder o = orderMapper.selectById(r.getOrderId()); return o != null && ("super".equalsIgnoreCase(role) || shopIds.contains(o.getShopId())); }).mapToInt(r -> r.getAmountCents() == null ? 0 : r.getAmountCents()).sum();
        return Map.of("todayAmountCents", todayAmount, "todayOrderCount", todayCount, "totalAmountCents", totalAmount, "unverifiedCount", unverified, "refundAmountCents", refundAmount);
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
