package com.campuslife.service;

import com.campuslife.entity.Coupon;
import com.campuslife.mapper.CouponMapper;
import com.campuslife.mapper.RewardMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 积分兑换：商品池、库存、限购、发券、扣分。
 *
 * 一笔兑换在同一个事务里做五件事：
 *   1. 库存条件更新（卖完即失败）
 *   2. 发券（面额严格等于积分价格，1P=1分）
 *   3. 写兑换记录（限购计数依据）
 *   4. PointsService.spend 条件扣分（余额不足即失败）
 *   5. 返回券与余额
 * 任一步失败整体回滚，不会出现「扣了分没券」或「发了券没扣分」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RewardMapper mapper;
    private final CouponMapper couponMapper;
    private final PointsService points;

    /** 用户侧商品池：下架不返回；带上我的已兑次数与售罄/达限状态。 */
    public Map<String, Object> rewards(Long userId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : mapper.all()) {
            if (num(row.get("enabled")) != 1) continue;
            String itemKey = String.valueOf(row.get("itemKey"));
            int price = num(row.get("priceP"));
            int stock = num(row.get("stock"));
            int limit = num(row.get("perUserLimit"));
            int mine = mapper.countRedeem(userId, itemKey);
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("priceP", price);
            item.put("valueYuan", PointsService.yuan(price));
            item.put("valueCents", price);
            item.put("stock", Math.max(stock, 0));
            item.put("myRedeemed", mine);
            item.put("soldOut", stock <= 0);
            item.put("limitReached", limit > 0 && mine >= limit);
            items.add(item);
        }
        int balance = points.balance(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("balance", balance);
        out.put("valueYuan", PointsService.yuan(balance));
        out.put("pointsPerYuan", 100);
        out.put("items", items);
        return out;
    }

    @Transactional
    public Map<String, Object> redeem(Long userId, String itemKey) {
        Map<String, Object> item = mapper.item(itemKey);
        if (item == null || num(item.get("enabled")) != 1) throw new IllegalArgumentException("该商品已下架");
        int stock = num(item.get("stock"));
        int limit = num(item.get("perUserLimit"));
        int mine = mapper.countRedeem(userId, itemKey);
        if (limit > 0 && mine >= limit) throw new IllegalArgumentException("每人限兑 " + limit + " 件");
        if (stock <= 0) throw new IllegalArgumentException("该商品已兑完");
        if (mapper.decreaseStock(itemKey) != 1) throw new IllegalArgumentException("手慢了，刚刚被兑完");

        int price = num(item.get("priceP"));
        int expireDays = Math.max(num(item.get("expireDays")), 1);
        LocalDate expireOn = LocalDate.now(SHANGHAI).plusDays(expireDays);
        Coupon coupon = new Coupon();
        coupon.setUserId(userId);
        coupon.setTitle(String.valueOf(item.get("couponTitle")));
        coupon.setAmount(String.valueOf(item.get("couponAmount")));
        coupon.setExpireAt(expireOn.format(DATE));
        coupon.setExpireOn(expireOn);
        coupon.setValueCents(price); // 严格 1P = 1 分：券面额不允许超过积分价值
        coupon.setThresholdCents(num(item.get("thresholdCents")));
        coupon.setScope("takeout");
        coupon.setSource("redeem");
        coupon.setStatus(1);
        couponMapper.insert(coupon);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("userId", userId);
        record.put("itemKey", itemKey);
        record.put("priceP", price);
        record.put("couponId", coupon.getId());
        mapper.insertRedeemRecord(record);
        Object idValue = record.get("id");
        Long recordId = idValue instanceof Number n ? n.longValue() : null;

        boolean paid = points.spend(userId, "redeem", "redeem:" + recordId, price, "redeem",
            "兑换 " + item.get("name"));
        if (!paid) throw new IllegalArgumentException("积分不足，还差 " + price + " 分");

        Map<String, Object> couponOut = new LinkedHashMap<>();
        couponOut.put("id", coupon.getId());
        couponOut.put("title", coupon.getTitle());
        couponOut.put("amount", coupon.getAmount());
        couponOut.put("valueCents", coupon.getValueCents());
        couponOut.put("thresholdCents", coupon.getThresholdCents());
        couponOut.put("expireOn", coupon.getExpireOn() == null ? null : coupon.getExpireOn().format(DATE));
        couponOut.put("status", coupon.getStatus());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coupon", couponOut);
        out.put("balance", points.balance(userId));
        out.put("valueYuan", PointsService.yuan(points.balance(userId)));
        out.put("stock", Math.max(stock - 1, 0));
        return out;
    }

    // ==================== 管理端 ====================

    public List<Map<String, Object>> adminList() {
        return mapper.all();
    }

    @Transactional
    public void updateItem(String itemKey, Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("没有要保存的配置");
        Map<String, Object> current = mapper.item(itemKey);
        if (current == null) throw new IllegalArgumentException("商品不存在：" + itemKey);
        int price = intOr(body.get("priceP"), num(current.get("priceP")));
        int stock = intOr(body.get("stock"), num(current.get("stock")));
        int limit = intOr(body.get("perUserLimit"), num(current.get("perUserLimit")));
        int threshold = intOr(body.get("thresholdCents"), num(current.get("thresholdCents")));
        int expireDays = intOr(body.get("expireDays"), num(current.get("expireDays")));
        int sort = intOr(body.get("sort"), num(current.get("sort")));
        int enabled = body.get("enabled") == null ? num(current.get("enabled"))
            : (Boolean.TRUE.equals(body.get("enabled")) || "1".equals(String.valueOf(body.get("enabled"))) ? 1 : 0);
        if (price < 1) throw new IllegalArgumentException("价格至少 1 积分");
        if (stock < 0) throw new IllegalArgumentException("库存不能为负");
        if (limit < 1) throw new IllegalArgumentException("每人限购至少 1 件");
        if (threshold < 0) throw new IllegalArgumentException("使用门槛不能为负");
        if (expireDays < 1 || expireDays > 365) throw new IllegalArgumentException("券有效期须在 1-365 天");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("itemKey", itemKey);
        row.put("priceP", price);
        row.put("stock", stock);
        row.put("perUserLimit", limit);
        row.put("thresholdCents", threshold);
        row.put("expireDays", expireDays);
        row.put("sort", sort);
        row.put("enabled", enabled);
        row.put("couponTitle", body.get("couponTitle") == null ? current.get("couponTitle") : String.valueOf(body.get("couponTitle")).trim());
        row.put("couponAmount", body.get("couponAmount") == null ? current.get("couponAmount") : String.valueOf(body.get("couponAmount")).trim());
        row.put("couponExpire", body.get("couponExpire") == null ? current.get("couponExpire") : String.valueOf(body.get("couponExpire")).trim());
        if (String.valueOf(row.get("couponTitle")).isBlank()) throw new IllegalArgumentException("券标题不能为空");
        mapper.updateItem(row);
        log.info("[campus] 兑换商品 {} 已更新 price={} stock={} enabled={}", itemKey, price, stock, enabled);
    }

    private static int num(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return 0;
        try { return Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException e) { return 0; }
    }

    private static int intOr(Object value, int fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        return num(value);
    }
}