package com.campuslife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campuslife.config.WxPayProperties;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.binarywang.wxpay.bean.notify.SignatureHeader;
import com.github.binarywang.wxpay.bean.notify.WxPayNotifyV3Result;
import com.github.binarywang.wxpay.bean.request.WxPayRefundV3Request;
import com.github.binarywang.wxpay.bean.request.WxPayUnifiedOrderV3Request;
import com.github.binarywang.wxpay.bean.result.WxPayRefundV3Result;
import com.github.binarywang.wxpay.bean.result.WxPayUnifiedOrderV3Result;
import com.github.binarywang.wxpay.bean.result.WxPayOrderQueryV3Result;
import com.github.binarywang.wxpay.bean.result.enums.TradeTypeEnum;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.PrivateKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PayService {
    private final UserOrderMapper orderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final UserMapper userMapper;
    private final ShopMapper shopMapper;
    private final ObjectMapper json;
    private final WxPayProperties properties;
    private final ObjectProvider<WxPayService> wxPayProvider;

    @Transactional
    public Map<String, Object> createPayment(Long orderId, Long userId) {
        UserOrder order = requireOrder(orderId);
        if (userId != null && !userId.equals(order.getUserId())) throw new IllegalArgumentException("无权操作该订单");
        if ("paid".equals(order.getPayStatus()) || OrderStatus.PAID.value().equals(order.getStatus())) return Map.of("simulated", !properties.isEnabled(), "payStatus", "paid", "outTradeNo", order.getOutTradeNo());
        if (!OrderStatus.CREATED.value().equals(order.getStatus()) || !"unpaid".equals(order.getPayStatus())) throw new IllegalStateException("订单当前不可支付");
        if (!properties.isEnabled()) {
            markPaid(order, "SIM-" + order.getOutTradeNo(), "SUCCESS", "{\"simulated\":true}", true);
            return Map.of("simulated", true, "payStatus", "paid", "outTradeNo", order.getOutTradeNo());
        }
        WxPayService wxPay = requireWxPay();
        User user = userMapper.selectById(order.getUserId());
        if (user == null || user.getOpenid() == null || user.getOpenid().isBlank()) throw new IllegalArgumentException("用户未绑定微信，无法发起支付");
        WxPayUnifiedOrderV3Request.Amount amount = new WxPayUnifiedOrderV3Request.Amount().setTotal(order.getPayAmountCents()).setCurrency("CNY");
        WxPayUnifiedOrderV3Request.Payer payer = new WxPayUnifiedOrderV3Request.Payer().setOpenid(user.getOpenid());
        WxPayUnifiedOrderV3Request request = new WxPayUnifiedOrderV3Request()
            .setAppid(properties.getAppId()).setMchid(properties.getMchId()).setDescription("校园外卖订单")
            .setOutTradeNo(order.getOutTradeNo()).setNotifyUrl(properties.getNotifyUrl()).setAmount(amount).setPayer(payer);
        try {
            WxPayUnifiedOrderV3Result result = wxPay.unifiedOrderV3(TradeTypeEnum.JSAPI, request);
            order.setPrepayId(result.getPrepayId());
            orderMapper.updateById(order);
            @SuppressWarnings("unchecked") Map<String, String> payInfo = (Map<String, String>) result.getPayInfo(TradeTypeEnum.JSAPI, properties.getAppId(), properties.getMchId(), wxPay.getConfig().getPrivateKey());
            Map<String, Object> out = new LinkedHashMap<>();
            out.putAll(payInfo);
            out.put("simulated", false);
            out.put("payStatus", order.getPayStatus());
            out.put("outTradeNo", order.getOutTradeNo());
            return out;
        } catch (WxPayException e) {
            throw new IllegalStateException("微信支付下单失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public boolean handleNotify(String body, String timestamp, String nonce, String signature, String serial) {
        if (!properties.isEnabled()) return false;
        WxPayNotifyV3Result notify;
        try {
            SignatureHeader header = new SignatureHeader(timestamp, nonce, signature, serial);
            notify = requireWxPay().parseOrderNotifyV3Result(body, header);
        } catch (Exception e) {
            recordUnverifiedPlainNotify(body);
            return false;
        }
        WxPayNotifyV3Result.DecryptNotifyResult result = notify.getResult();
        if (result == null || result.getTransactionId() == null || result.getOutTradeNo() == null) return false;
        int amount = result.getAmount() == null || result.getAmount().getTotal() == null ? -1 : result.getAmount().getTotal();
        UserOrder order = orderMapper.selectOne(new LambdaQueryWrapper<UserOrder>().eq(UserOrder::getOutTradeNo, result.getOutTradeNo()).last("LIMIT 1"));
        if (order == null || amount != order.getPayAmountCents()) return false;
        PaymentRecord duplicate = paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>().eq(PaymentRecord::getTransactionId, result.getTransactionId()).last("LIMIT 1"));
        if (duplicate != null) return true;
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(order.getId()); record.setOutTradeNo(result.getOutTradeNo()); record.setTransactionId(result.getTransactionId()); record.setAmountCents(amount); record.setTradeState(result.getTradeState()); record.setRawBody(body); record.setVerified(1); record.setCreatedAt(LocalDateTime.now());
        paymentRecordMapper.insert(record);
        if ("SUCCESS".equalsIgnoreCase(result.getTradeState()) && !"paid".equals(order.getPayStatus())) markOrderPaid(order, result.getTransactionId());
        return true;
    }

    /** Keep a best-effort audit row for plain or test notifications that fail verification. */
    private void recordUnverifiedPlainNotify(String body) {
        try {
            JsonNode root = json.readTree(body);
            String outTradeNo = root.path("out_trade_no").asText(root.path("outTradeNo").asText(""));
            String transactionId = root.path("transaction_id").asText(root.path("transactionId").asText("UNVERIFIED-" + UUID.randomUUID()));
            if (outTradeNo.isBlank()) return;
            if (paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>().eq(PaymentRecord::getTransactionId, transactionId).last("LIMIT 1")) != null) return;
            UserOrder order = orderMapper.selectOne(new LambdaQueryWrapper<UserOrder>().eq(UserOrder::getOutTradeNo, outTradeNo).last("LIMIT 1"));
            PaymentRecord record = new PaymentRecord(); record.setOrderId(order == null ? null : order.getId()); record.setOutTradeNo(outTradeNo); record.setTransactionId(transactionId); record.setAmountCents(root.path("amount_cents").asInt(root.path("amount").path("total").asInt(0))); record.setTradeState(root.path("trade_state").asText(root.path("tradeState").asText("UNKNOWN"))); record.setRawBody(body); record.setVerified(0); record.setCreatedAt(LocalDateTime.now()); paymentRecordMapper.insert(record);
        } catch (Exception ignored) { }
    }

    @Transactional
    public Map<String, Object> cancel(Long orderId, Long userId, String reason) {
        UserOrder order = requireOrder(orderId);
        if (userId != null && !userId.equals(order.getUserId())) throw new IllegalArgumentException("无权操作该订单");
        if (!OrderStatus.CREATED.value().equals(order.getStatus()) || !"unpaid".equals(order.getPayStatus())) throw new IllegalStateException("只有待支付订单可以取消");
        transition(order, OrderStatus.CANCELLED.value());
        order.setPayStatus("closed"); order.setCancelledAt(LocalDateTime.now()); order.setCancelReason(reason == null ? "用户取消" : reason.trim()); orderMapper.updateById(order);
        return detail(order);
    }

    @Transactional
    public Map<String, Object> refund(Long orderId, Long userId, String reason) {
        UserOrder order = requireOrder(orderId);
        if (userId != null && !userId.equals(order.getUserId())) throw new IllegalArgumentException("无权操作该订单");
        if ("refunding".equals(order.getStatus()) || "refunded".equals(order.getStatus())) throw new IllegalStateException("订单已在退款流程中");
        if (!List.of("paid", "accepted", "delivering", "completed").contains(order.getStatus())) throw new IllegalStateException("当前订单不能退款");
        transition(order, OrderStatus.REFUNDING.value());
        order.setPayStatus("refunding"); orderMapper.updateById(order);
        RefundRecord record = new RefundRecord();
        record.setOrderId(order.getId()); record.setOutRefundNo("RF" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + UUID.randomUUID().toString().replace("-", "").substring(0, 10)); record.setAmountCents(order.getPayAmountCents()); record.setReason(reason == null ? "用户申请退款" : reason.trim()); record.setStatus("pending"); record.setCreatedAt(LocalDateTime.now()); refundRecordMapper.insert(record);
        if (!properties.isEnabled()) {
            completeRefund(order, record, "SIM-REFUND-" + record.getId());
            return detail(order);
        }
        try {
            WxPayRefundV3Request.Amount amount = new WxPayRefundV3Request.Amount().setRefund(order.getPayAmountCents()).setTotal(order.getPayAmountCents()).setCurrency("CNY");
            WxPayRefundV3Request request = new WxPayRefundV3Request().setOutTradeNo(order.getOutTradeNo()).setTransactionId(order.getTransactionId()).setOutRefundNo(record.getOutRefundNo()).setReason(record.getReason()).setAmount(amount);
            WxPayRefundV3Result result = requireWxPay().refundV3(request);
            record.setRefundId(result.getRefundId());
            if ("SUCCESS".equalsIgnoreCase(result.getStatus())) completeRefund(order, record, result.getRefundId()); else refundRecordMapper.updateById(record);
        } catch (WxPayException e) {
            record.setStatus("failed"); refundRecordMapper.updateById(record); throw new IllegalStateException("微信退款失败: " + e.getMessage(), e);
        }
        return detail(order);
    }

    @Transactional
    public void transitionStatus(Long orderId, String target) {
        UserOrder order = requireOrder(orderId);
        transition(order, target);
        if (OrderStatus.REFUNDED.value().equals(target)) order.setPayStatus("refunded");
        orderMapper.updateById(order);
    }

    public Map<String, Object> detail(Long orderId) { return detail(requireOrder(orderId)); }

    public Map<String, Object> detail(UserOrder order) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", order.getId()); out.put("orderId", order.getId()); out.put("userId", order.getUserId()); out.put("shopId", order.getShopId()); out.put("items", parseItems(order.getItems())); out.put("address", order.getAddress()); out.put("remark", order.getRemark()); out.put("subtotal", order.getSubtotal()); out.put("packingFee", order.getPackingFee()); out.put("deliveryFee", order.getDeliveryFee()); out.put("discountAmount", order.getDiscountAmount()); out.put("total", order.getTotal()); out.put("payAmountCents", order.getPayAmountCents()); out.put("outTradeNo", order.getOutTradeNo()); out.put("transactionId", order.getTransactionId()); out.put("prepayId", order.getPrepayId()); out.put("status", order.getStatus()); out.put("payStatus", order.getPayStatus()); out.put("createdAt", order.getCreatedAt()); out.put("paidAt", order.getPaidAt()); out.put("cancelledAt", order.getCancelledAt()); out.put("cancelReason", order.getCancelReason());
        User user = userMapper.selectById(order.getUserId()); Shop shop = shopMapper.selectById(order.getShopId());
        out.put("userName", user == null ? "" : user.getNickname()); out.put("shopName", shop == null ? "" : shop.getName());
        out.put("paymentRecords", paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>().eq(PaymentRecord::getOrderId, order.getId()).orderByDesc(PaymentRecord::getId)));
        out.put("refundRecords", refundRecordMapper.selectList(new LambdaQueryWrapper<RefundRecord>().eq(RefundRecord::getOrderId, order.getId()).orderByDesc(RefundRecord::getId)));
        // task-15 骑手端：小程序订单详情页要展示配送进度与骑手联系方式
        out.put("contactName", order.getContactName()); out.put("contactPhone", order.getContactPhone());
        out.put("riderId", order.getRiderId()); out.put("riderName", order.getRiderName());
        out.put("acceptedAt", order.getAcceptedAt()); out.put("pickedAt", order.getPickedAt()); out.put("deliveredAt", order.getDeliveredAt());
        return out;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @Transactional
    public void reconcile() {
        LocalDateTime now = LocalDateTime.now();
        List<UserOrder> stale = orderMapper.selectList(new LambdaQueryWrapper<UserOrder>().eq(UserOrder::getStatus, OrderStatus.CREATED.value()).eq(UserOrder::getPayStatus, "unpaid").lt(UserOrder::getCreatedAt, now.minusMinutes(5)).le(UserOrder::getCreatedAt, now));
        for (UserOrder order : stale) {
            if (order.getCreatedAt() != null && order.getCreatedAt().isBefore(now.minusMinutes(15))) {
                transition(order, OrderStatus.CLOSED.value()); order.setPayStatus("closed"); order.setCancelledAt(now); order.setCancelReason("支付超时"); orderMapper.updateById(order); continue;
            }
            if (!properties.isEnabled()) continue;
            try {
                WxPayOrderQueryV3Result result = requireWxPay().queryOrderV3(order.getOutTradeNo(), null);
                if ("SUCCESS".equalsIgnoreCase(result.getTradeState()) && result.getTransactionId() != null && result.getAmount() != null && result.getAmount().getTotal() != null && result.getAmount().getTotal().equals(order.getPayAmountCents())) {
                    markPaid(order, result.getTransactionId(), result.getTradeState(), "{\"source\":\"reconcile\"}", true);
                }
            } catch (Exception ignored) { }
        }
    }

    private void markPaid(UserOrder order, String transactionId, String tradeState, String rawBody, boolean verified) {
        if (paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>().eq(PaymentRecord::getTransactionId, transactionId).last("LIMIT 1")) != null) return;
        PaymentRecord record = new PaymentRecord(); record.setOrderId(order.getId()); record.setOutTradeNo(order.getOutTradeNo()); record.setTransactionId(transactionId); record.setAmountCents(order.getPayAmountCents()); record.setTradeState(tradeState); record.setRawBody(rawBody); record.setVerified(verified ? 1 : 0); record.setCreatedAt(LocalDateTime.now()); paymentRecordMapper.insert(record);
        if (verified) markOrderPaid(order, transactionId);
    }

    private void markOrderPaid(UserOrder order, String transactionId) {
        if ("paid".equals(order.getPayStatus())) return;
        if (!OrderStatus.canTransit(order.getStatus(), OrderStatus.PAID.value())) return;
        transition(order, OrderStatus.PAID.value()); order.setPayStatus("paid"); order.setTransactionId(transactionId); order.setPaidAt(LocalDateTime.now()); orderMapper.updateById(order);
    }

    private void completeRefund(UserOrder order, RefundRecord record, String refundId) {
        record.setRefundId(refundId); record.setStatus("success"); refundRecordMapper.updateById(record);
        transition(order, OrderStatus.REFUNDED.value()); order.setPayStatus("refunded"); orderMapper.updateById(order);
    }

    private void transition(UserOrder order, String target) { OrderStatus.requireTransition(order.getStatus(), target); order.setStatus(target); }
    private UserOrder requireOrder(Long id) { if (id == null || id < 1) throw new IllegalArgumentException("订单参数无效"); UserOrder order = orderMapper.selectById(id); if (order == null) throw new IllegalArgumentException("订单不存在"); if (order.getPayStatus() == null) order.setPayStatus("unpaid"); return order; }
    private WxPayService requireWxPay() { WxPayService service = wxPayProvider.getIfAvailable(); if (service == null) throw new IllegalStateException("微信支付未配置"); return service; }
    private List<?> parseItems(String raw) { try { return raw == null ? List.of() : json.readValue(raw, List.class); } catch (Exception e) { return List.of(); } }
}
