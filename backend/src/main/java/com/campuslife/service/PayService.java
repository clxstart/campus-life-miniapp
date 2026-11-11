package com.campuslife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campuslife.config.ForbiddenException;
import com.campuslife.config.UnauthorizedException;
import com.campuslife.config.WxPayProperties;
import com.campuslife.entity.PaymentRecord;
import com.campuslife.entity.RefundRecord;
import com.campuslife.entity.Shop;
import com.campuslife.entity.User;
import com.campuslife.entity.UserOrder;
import com.campuslife.mapper.PaymentRecordMapper;
import com.campuslife.mapper.RefundRecordMapper;
import com.campuslife.mapper.RiderMapper;
import com.campuslife.mapper.ShopMapper;
import com.campuslife.mapper.UserMapper;
import com.campuslife.mapper.UserOrderMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.binarywang.wxpay.bean.notify.SignatureHeader;
import com.github.binarywang.wxpay.bean.notify.WxPayNotifyV3Result;
import com.github.binarywang.wxpay.bean.request.WxPayUnifiedOrderV3Request;
import com.github.binarywang.wxpay.bean.result.WxPayUnifiedOrderV3Result;
import com.github.binarywang.wxpay.bean.result.WxPayOrderQueryV3Result;
import com.github.binarywang.wxpay.bean.result.enums.TradeTypeEnum;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
@Slf4j
public class PayService {
    private final UserOrderMapper orderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final UserMapper userMapper;
    private final ShopMapper shopMapper;
    /** 用户/管理端退款编排：短事务领取 + 事务外渠道提交（渠道提交与商家拒单共用 MerchantRefundService） */
    private final UserRefundService userRefundService;
    private final ObjectMapper json;
    private final WxPayProperties properties;
    private final ObjectProvider<WxPayService> wxPayProvider;
    private final org.springframework.core.env.Environment environment;
    /** 已验证支付结果的唯一入账口 */
    private final PaymentLedgerService ledger;
    private final PaymentAlerts alerts;
    private final com.campuslife.ha.ClusterLock clusterLock;
    /** 取消/关单时释放锁定的券（功能 6 核销三态）。 */
    private final CouponService couponService;

    /** 是否允许在「微信支付未开启」时用模拟支付跑通流程；生产 profile 下永远不允许。 */
    @Value("${campus.pay.simulate:false}")
    private boolean simulate;

    @Transactional
    public Map<String, Object> createPayment(Long orderId, Long userId) {
        UserOrder order = requireOrder(orderId);
        requireOwner(order, userId);
        if ("paid".equals(order.getPayStatus()) || OrderStatus.PAID.value().equals(order.getStatus())) return Map.of("simulated", !properties.isEnabled(), "payStatus", "paid", "outTradeNo", order.getOutTradeNo());
        if (!OrderStatus.CREATED.value().equals(order.getStatus()) || !"unpaid".equals(order.getPayStatus())) throw new IllegalStateException("订单当前不可支付");
        if (!properties.isEnabled()) {
            requireSimulationAllowed(orderId);
            ledger.applyVerifiedPayment(order.getOutTradeNo(), "SIM-" + order.getOutTradeNo(), "SUCCESS",
                order.getPayAmountCents(), "{\"simulated\":true}", "simulate");
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

    /** 支付回调处理结果：控制器据此决定 HTTP 状态（微信只把 2xx 视为成功，其余会重试） */
    public enum NotifyResult { ACK, REJECT, RETRY }

    /**
     * 微信支付结果通知（finding-02/04）。
     *
     * - 不加事务：验签/解密是纯计算，入账由 {@link PaymentLedgerService} 开短事务完成；
     * - 验签失败：**不写任何数据库记录**，只计数 + 限速告警，返回 REJECT（401），
     *   这样伪造请求无法制造流水；若是证书轮换等导致的真实通知失败，微信会按策略重试；
     * - 已验签：无论入账、重复、异常单登记，都 ACK，避免渠道无意义重试；
     * - 数据库等暂时性故障：RETRY（500），交给微信重试，入账逻辑幂等。
     */
    public NotifyResult handleNotify(String body, String timestamp, String nonce, String signature, String serial) {
        if (!properties.isEnabled()) {
            alerts.warn("notify", "pay_disabled", "微信支付未启用时收到支付回调");
            return NotifyResult.REJECT;
        }
        WxPayNotifyV3Result notify;
        try {
            SignatureHeader header = new SignatureHeader(timestamp, nonce, signature, serial);
            notify = requireWxPay().parseOrderNotifyV3Result(body, header);
        } catch (Exception e) {
            alerts.warn("notify", "verify_failed", "支付回调验签/解密失败：{}", e.getClass().getSimpleName());
            return NotifyResult.REJECT;
        }
        WxPayNotifyV3Result.DecryptNotifyResult result = notify.getResult();
        if (result == null) {
            alerts.warn("notify", "empty_result", "已验签回调缺少解密结果");
            return NotifyResult.ACK;
        }
        Integer amount = result.getAmount() == null ? null : result.getAmount().getTotal();
        try {
            ledger.applyVerifiedPayment(result.getOutTradeNo(), result.getTransactionId(), result.getTradeState(), amount, body, "notify");
            return NotifyResult.ACK;
        } catch (RuntimeException e) {
            alerts.warn("notify", "apply_failed", "支付回调入账失败 outTradeNo={}：{}", result.getOutTradeNo(), e.getClass().getSimpleName());
            return NotifyResult.RETRY;
        }
    }

    /**
     * 用户取消待支付订单（finding-04）。
     * 先向渠道确认未付款并关闭渠道订单（网络调用在事务外），再用条件 UPDATE 原子关单；
     * 若渠道显示已付款，则先入账并拒绝取消，引导用户走退款。
     */
    public Map<String, Object> cancel(Long orderId, Long userId, String reason) {
        UserOrder order = requireOrder(orderId);
        requireOwner(order, userId);
        if (!OrderStatus.CREATED.value().equals(order.getStatus()) || !"unpaid".equals(order.getPayStatus())) throw new IllegalStateException("只有待支付订单可以取消");
        if (properties.isEnabled()) {
            ChannelCheck check = closeAtChannel(order);
            if (check == ChannelCheck.PAID) throw new IllegalStateException("该订单已支付成功，无法取消；如需退款请在订单详情申请退款");
            if (check == ChannelCheck.PAYING) throw new IllegalStateException("支付正在处理中，请稍后再取消");
            // UNKNOWN（渠道暂不可达）仍允许本地取消：若之后渠道到账，会登记「关单后到账」异常单并自动退款
        }
        String text = reason == null || reason.isBlank() ? "用户取消" : reason.trim();
        if (text.length() > 100) text = text.substring(0, 100);
        if (orderMapper.closeIfUnpaid(order.getId(), OrderStatus.CANCELLED.value(), text) != 1) {
            throw new IllegalStateException("订单状态已变化，请刷新后重试");
        }
        couponService.releaseByOrder(order.getId());
        return detail(requireOrder(orderId));
    }

    enum ChannelCheck { PAID, CLOSED, PAYING, UNKNOWN }

    /**
     * 查询渠道状态，未付款时关闭渠道订单，防止关单后用户仍能完成付款。
     * 渠道已付款时直接入账并返回 PAID。全部网络调用都不持有数据库事务。
     */
    ChannelCheck closeAtChannel(UserOrder order) {
        WxPayService wxPay = requireWxPay();
        try {
            // 注意参数顺序：queryOrderV3(transactionId, outTradeNo)。历史代码把商户单号传进了 transactionId 位，
            // 导致对账查询永远查错单、异常又被吞掉。
            WxPayOrderQueryV3Result result = wxPay.queryOrderV3(null, order.getOutTradeNo());
            String state = result.getTradeState();
            if ("SUCCESS".equalsIgnoreCase(state)) {
                Integer total = result.getAmount() == null ? null : result.getAmount().getTotal();
                ledger.applyVerifiedPayment(order.getOutTradeNo(), result.getTransactionId(), state, total, "{\"source\":\"query\"}", "query");
                return ChannelCheck.PAID;
            }
            if ("USERPAYING".equalsIgnoreCase(state)) return ChannelCheck.PAYING;
            if (!"CLOSED".equalsIgnoreCase(state)) wxPay.closeOrderV3(order.getOutTradeNo());
            return ChannelCheck.CLOSED;
        } catch (WxPayException e) {
            alerts.warn("channel", "query_or_close_failed", "订单 {} 渠道查询/关单失败：{}", order.getId(), e.getErrCode());
            return ChannelCheck.UNKNOWN;
        }
    }

    /**
     * 用户申请退款（finding-followup：并发互斥 + 幂等单号）。
     *
     * 事务边界与并发控制已下沉到 {@link UserRefundService} / {@link RefundInitiationService}：
     *  - 短事务里锁订单行、条件迁移到 refunding、按每订单唯一写退款账本；
     *  - 渠道调用在事务提交之后；
     *  - 并发/超时重试复用同一个确定性退款单号，不生成第二个可出款单。
     *
     * 这里只负责组装返回给客户端的订单详情，并附带退款单状态与幂等标记。
     */
    public Map<String, Object> refund(Long orderId, Long userId, String reason) {
        return refundResult(userRefundService.refund(orderId, userId, reason));
    }

    /** 管理端退款：权限由 AdminInterceptor + AdminOrderService.assertAccessible 校验。 */
    public Map<String, Object> refundByAdmin(Long orderId, String reason) {
        return refundResult(userRefundService.refundAsAdmin(orderId, reason));
    }

    private Map<String, Object> refundResult(UserRefundService.Outcome outcome) {
        UserOrder order = requireOrder(outcome.orderId());
        Map<String, Object> out = detail(order);
        out.put("refundStatus", outcome.refundStatus());
        out.put("outRefundNo", outcome.outRefundNo());
        out.put("idempotent", outcome.idempotent());
        return out;
    }

    @Transactional
    public void transitionStatus(Long orderId, String target) {
        // 退款状态只能由退款流程写入：refunding 走 refundByAdmin（建账本 + 提交渠道），
        // refunded 只能由渠道确认成功后写入。直接改状态会造成「订单已退款但没有退款流水/没有出款」。
        if (OrderStatus.REFUNDING.value().equals(target) || OrderStatus.REFUNDED.value().equals(target)) {
            throw new IllegalStateException("退款状态只能通过退款流程变更，不能直接修改");
        }
        UserOrder order = requireOrder(orderId);
        transition(order, target);
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

    /** 对账：超过 5 分钟未支付开始向渠道核验，超过 15 分钟仍未付则关单 */
    static final int CHECK_AFTER_MINUTES = 5;
    static final int CLOSE_AFTER_MINUTES = 15;
    /** 渠道持续不可达超过该时长，仍按超时本地关单（之后到账走异常单自动退款），避免订单永远悬挂 */
    static final int FORCE_CLOSE_AFTER_MINUTES = 120;

    @Value("${campus.pay.reconcile.batch-size:100}")
    private int reconcileBatchSize = 100;
    @Value("${campus.pay.reconcile.budget-ms:20000}")
    private long reconcileBudgetMs = 20000;

    /** 单轮对账统计，供测试与日志使用 */
    public record ReconcileStats(int scanned, int paid, int closed, int failed, int deferred) {}

    /**
     * 周期对账（finding-11）：多实例下只有拿到租约的节点执行；租约过期后的短暂重叠由入账/关单的条件更新兜底。
     * 注意本方法不持有数据库事务——每单的查询、入账、关单各自是独立的短语句或短事务。
     */
    @Scheduled(fixedDelayString = "${campus.pay.reconcile.interval-ms:60000}", initialDelay = 60000)
    public void reconcile() {
        clusterLock.runExclusive("pay-reconcile", java.time.Duration.ofMillis(reconcileBudgetMs + 30_000), () -> {
            ReconcileStats stats = reconcileOnce(LocalDateTime.now());
            if (stats.scanned() > 0) log.info("[pay] 对账完成 {}", stats);
        });
    }

    public ReconcileStats reconcileOnce(LocalDateTime now) {
        long deadline = System.currentTimeMillis() + reconcileBudgetMs;
        LocalDateTime cutoff = now.minusMinutes(CHECK_AFTER_MINUTES);
        // recheckBefore：上一轮刚核验过（且未关单）的订单本轮跳过，给渠道/网络一点恢复时间
        List<Map<String, Object>> batch = orderMapper.selectReconcileBatch(cutoff, now.minusSeconds(50), reconcileBatchSize);
        int paid = 0, closed = 0, failed = 0, deferred = 0, scanned = 0;
        for (Map<String, Object> row : batch) {
            if (System.currentTimeMillis() > deadline) { deferred = batch.size() - scanned; break; }
            scanned++;
            switch (reconcileOne(row, now)) {
                case PAID -> paid++;
                case CLOSED -> closed++;
                case FAILED -> failed++;
                default -> { }
            }
        }
        try { alerts.gauge("campus.pay.reconcile.backlog", orderMapper.countReconcileBacklog(cutoff)); }
        catch (RuntimeException ignored) { /* 指标失败不影响对账 */ }
        if (deferred > 0) alerts.warn("reconcile", "budget_exceeded", "单轮预算耗尽，{} 单顺延到下一轮", deferred);
        return new ReconcileStats(scanned, paid, closed, failed, deferred);
    }

    enum ReconcileResult { PAID, CLOSED, PENDING, FAILED }

    ReconcileResult reconcileOne(Map<String, Object> row, LocalDateTime now) {
        Long id = ((Number) row.get("id")).longValue();
        String outTradeNo = String.valueOf(row.get("outTradeNo"));
        LocalDateTime createdAt = toDateTime(row.get("createdAt"));
        boolean expired = createdAt != null && createdAt.isBefore(now.minusMinutes(CLOSE_AFTER_MINUTES));
        if (!properties.isEnabled()) {
            if (!expired) return ReconcileResult.PENDING;
            if (orderMapper.closeIfUnpaid(id, OrderStatus.CLOSED.value(), "支付超时") == 1) {
                couponService.releaseByOrder(id);
                return ReconcileResult.CLOSED;
            }
            return ReconcileResult.PENDING;
        }
        try {
            WxPayOrderQueryV3Result result = requireWxPay().queryOrderV3(null, outTradeNo);
            String state = result.getTradeState();
            if ("SUCCESS".equalsIgnoreCase(state)) {
                Integer total = result.getAmount() == null ? null : result.getAmount().getTotal();
                ledger.applyVerifiedPayment(outTradeNo, result.getTransactionId(), state, total, "{\"source\":\"reconcile\"}", "reconcile");
                orderMapper.recordPayCheck(id, null);
                return ReconcileResult.PAID;
            }
            if (!expired || "USERPAYING".equalsIgnoreCase(state)) {
                orderMapper.recordPayCheck(id, null);
                return ReconcileResult.PENDING;
            }
            // 先关渠道单再关本地单：渠道关单失败时本轮不动本地状态，下轮重试，避免本地已关、用户仍能付款
            if (!"CLOSED".equalsIgnoreCase(state)) requireWxPay().closeOrderV3(outTradeNo);
            orderMapper.recordPayCheck(id, null);
            if (orderMapper.closeIfUnpaid(id, OrderStatus.CLOSED.value(), "支付超时") == 1) {
                couponService.releaseByOrder(id);
                return ReconcileResult.CLOSED;
            }
            return ReconcileResult.PENDING;
        } catch (Exception e) {
            String error = (e.getClass().getSimpleName() + ": " + e.getMessage());
            if (error.length() > 250) error = error.substring(0, 250);
            try { orderMapper.recordPayCheck(id, error); } catch (RuntimeException ignored) { }
            alerts.warn("reconcile", "channel_error", "订单 {} 对账失败：{}", id, e.getClass().getSimpleName());
            boolean stuck = createdAt != null && createdAt.isBefore(now.minusMinutes(FORCE_CLOSE_AFTER_MINUTES));
            if (stuck && orderMapper.closeIfUnpaid(id, OrderStatus.CLOSED.value(), "支付超时（渠道状态未确认）") == 1) {
                couponService.releaseByOrder(id);
                alerts.warn("reconcile", "force_closed", "订单 {} 渠道长期不可达，已本地关单", id);
                return ReconcileResult.CLOSED;
            }
            return ReconcileResult.FAILED;
        }
    }

    private static LocalDateTime toDateTime(Object value) {
        if (value instanceof LocalDateTime t) return t;
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (value instanceof java.util.Date d) return new java.sql.Timestamp(d.getTime()).toLocalDateTime();
        return null;
    }

    private void transition(UserOrder order, String target) { OrderStatus.requireTransition(order.getStatus(), target); order.setStatus(target); }

    /** 订单必须属于下单人；userId 为空直接拒绝（不能让「没有身份」变成「跳过校验」）。 */
    private void requireOwner(UserOrder order, Long userId) {
        if (userId == null || userId < 1) throw new UnauthorizedException("请先登录后操作订单");
        if (!userId.equals(order.getUserId())) throw new ForbiddenException("无权操作该订单");
    }

    /**
     * 模拟支付/退款的准入。
     *
     * 以前只要 wxpay.enabled=false 就直接把订单标记成已付款，配置漏改就等于线上白送货。
     * 现在必须显式打开 campus.pay.simulate，且生产 profile 下一律拒绝。
     */
    private void requireSimulationAllowed(Long orderId) {
        if (isProduction()) {
            throw new IllegalStateException("生产环境禁止模拟支付（campus.pay.simulate），请配置微信支付商户参数并开启 wxpay.enabled");
        }
        if (!simulate) {
            throw new IllegalStateException("微信支付未配置，暂不能在线支付或退款，请稍后再试");
        }
        log.warn("[campus] 模拟支付生效：订单 {} 未调用微信接口，直接按已付款/已退款处理", orderId);
    }

    private boolean isProduction() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) return true;
        }
        return false;
    }
    private UserOrder requireOrder(Long id) { if (id == null || id < 1) throw new IllegalArgumentException("订单参数无效"); UserOrder order = orderMapper.selectById(id); if (order == null) throw new IllegalArgumentException("订单不存在"); if (order.getPayStatus() == null) order.setPayStatus("unpaid"); return order; }
    private WxPayService requireWxPay() { WxPayService service = wxPayProvider.getIfAvailable(); if (service == null) throw new IllegalStateException("微信支付未配置"); return service; }
    private List<?> parseItems(String raw) { try { return raw == null ? List.of() : json.readValue(raw, List.class); } catch (Exception e) { return List.of(); } }
}
