package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.mapper.MarketTradeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 二手成交（F10）：买家发起 → 卖家确认 → 双方各得积分。
 *
 * 三条贯穿全文的规则：
 *   1. **双方确认才成交**。只有卖家能 confirm，单方面点「我已买到」只是 pending，
 *      否则一个人就能给自己刷成交。
 *   2. **防互刷三层**：一件商品只有一笔成交（uk_trade_goods）、同一对用户 7 天内只计一次分、
 *      积分日限 2 笔（task_catalog）。
 *   3. **成交与发分解耦**。成交状态、商品置已售是主流程，发分放在事务提交后、走
 *      {@link AfterCommitPointsAwarder}（REQUIRES_NEW），积分服务异常不影响成交结果。
 *
 * 只做确认，不涉及资金：没有担保交易、没有线上支付、没有评价（设计稿 §七）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketTradeService {

    /** task_catalog 里的任务键：5P/12G，日限 2 笔（见 db/module-points-v31.sql）。 */
    public static final String TASK_KEY = "market_trade";

    private static final String PENDING = "pending";
    private static final String CONFIRMED = "confirmed";
    private static final String CANCELLED = "cancelled";

    /** 本次成交「没有发分」的两种原因，回给前端决定提示文案（设计稿 §2.3 状态表）。 */
    public static final String SKIP_PAIR_7_DAYS = "pair7days";
    public static final String SKIP_DAILY_CAP = "daily_cap";

    /** goods.status 现有取值：0 下架/未过审，1 在售，2 已售出（F10 新增）。 */
    private static final int GOODS_ON_SALE = 1;
    private static final int GOODS_SOLD = 2;

    private final MarketTradeMapper mapper;
    private final AfterCommitPointsAwarder awarder;

    // ==================== 买家：发起成交 ====================

    /**
     * 发起成交（买家）。
     *
     * 拦在本事务内的都是「不该产生 pending」的情况：商品不存在、已售/已下架、自己买自己的、
     * 已有成交记录。被卖家取消过的商品可以再次发起：复用同一行（uk_trade_goods 只允许一行），
     * 否则「被拒后可以再次发起」这条产品规则会被唯一键挡死。
     */
    @Transactional
    public Map<String, Object> initiate(Long goodsId, Long buyerId) {
        if (goodsId == null || goodsId < 1) throw new IllegalArgumentException("商品编号无效");
        if (buyerId == null || buyerId < 1) throw new ForbiddenException("请先登录");

        Map<String, Object> goods = mapper.findGoods(goodsId);
        if (goods == null) throw new IllegalArgumentException("商品不存在");
        long sellerId = number(goods.get("sellerId"), "商品卖家信息异常");
        if (sellerId == buyerId) throw new IllegalArgumentException("不能购买自己发布的商品");
        if (intNumber(goods.get("status")) != GOODS_ON_SALE) {
            // 已售出的商品必然有一笔 confirmed 成交：这时给「该商品已有成交记录」（设计稿 §6 用例 10 的
            // 约定语义，400），比笼统的「已下架或已售出」更能说明为什么不能点。
            // 注意顺序：先判在售、再判成交记录 —— 反过来会让「已下架 + 曾被取消」的商品被 reuseCancelled 复活。
            Map<String, Object> sold = mapper.findByGoodsId(goodsId);
            if (sold != null && CONFIRMED.equals(str(sold.get("status")))) {
                throw new IllegalArgumentException("该商品已有成交记录");
            }
            throw new IllegalStateException("商品已下架或已售出");
        }

        Map<String, Object> existing = mapper.findByGoodsId(goodsId);
        if (existing != null) {
            String status = str(existing.get("status"));
            if (CONFIRMED.equals(status)) throw new IllegalArgumentException("该商品已有成交记录");
            if (PENDING.equals(status)) {
                long pendingBuyerId = number(existing.get("buyerId"), "成交记录异常");
                // 同一个人重复点：幂等返回同一笔，不报错（网络重试/连点）
                if (pendingBuyerId == buyerId) return tradeView(existing, true);
                throw new IllegalStateException("该商品已有待确认的成交");
            }
            // cancelled：复用同一行重新发起，并发下只有一条 UPDATE 能命中
            if (mapper.reuseCancelled(goodsId, buyerId, sellerId) != 1) {
                throw new IllegalStateException("该商品已有待确认的成交");
            }
            return tradeView(mapper.findByGoodsId(goodsId), false);
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("goodsId", goodsId);
        row.put("buyerId", buyerId);
        row.put("sellerId", sellerId);
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException race) {
            // uk_trade_goods 兜底：并发的第二个 INSERT 撞键
            throw new IllegalArgumentException("该商品已有成交记录");
        }
        return tradeView(mapper.findById(number(row.get("id"), "成交编号生成失败")), false);
    }

    // ==================== 卖家：确认成交 ====================

    /**
     * 确认成交（仅卖家）。成功后：market_trade → confirmed、商品 → 已售、双方各发一次分。
     *
     * 7 天互刷只影响发分，不影响成交：同一对用户 7 天内已经成交过，这一笔照样确认成功、
     * 商品照样置已售，只是不发分并记 warn —— 产品规则是"同一对用户 7 天内只计一次积分"，
     * 不是"7 天内不许再交易"。
     */
    @Transactional
    public Map<String, Object> confirm(Long tradeId, Long sellerId) {
        if (tradeId == null || tradeId < 1) throw new IllegalArgumentException("成交编号无效");
        if (sellerId == null || sellerId < 1) throw new ForbiddenException("请先登录");

        Map<String, Object> trade = mapper.findById(tradeId);
        if (trade == null) throw new IllegalArgumentException("成交记录不存在");
        if (!Objects.equals(number(trade.get("sellerId"), "成交记录异常"), sellerId)) {
            throw new ForbiddenException("只有卖家可以确认成交");
        }
        String status = str(trade.get("status"));
        if (CONFIRMED.equals(status)) return confirmView(trade, false, true, null);
        if (CANCELLED.equals(status)) throw new IllegalStateException("该成交已取消，不能确认");

        long buyerId = number(trade.get("buyerId"), "成交记录异常");
        long goodsId = number(trade.get("goodsId"), "成交记录异常");

        // 先判定 7 天互刷（此时本笔还是 pending，查询用 id 排除自身，避免边界差一）
        boolean samePairWithin7Days = mapper.countRecentPair(tradeId, buyerId, sellerId) > 0;

        // 条件 UPDATE 是唯一判定：并发下只有一次能从 pending 走到 confirmed
        if (mapper.confirm(tradeId, sellerId) != 1) {
            Map<String, Object> fresh = mapper.findById(tradeId);
            if (fresh != null && CONFIRMED.equals(str(fresh.get("status")))) {
                return confirmView(fresh, false, true, null);
            }
            throw new IllegalStateException("成交状态已变化，请刷新后重试");
        }
        mapper.markGoodsSold(goodsId);

        // 只是「本次没有发分」的说明性原因，不改变发分行为：两条 award 照旧发出，
        // 日上限/开关仍由 PointsService 最终判定（这里算的是确认人自己的上限）。
        String skipReason = skipReason(samePairWithin7Days, sellerId);

        if (samePairWithin7Days) {
            log.warn("[campus] 二手成交 {} 同一对用户 7 天内已成交过，本次成交成功但不发分（buyer={} seller={}）",
                tradeId, buyerId, sellerId);
        } else {
            awardBothAfterCommit(tradeId, buyerId, sellerId);
        }
        if (SKIP_DAILY_CAP.equals(skipReason)) {
            log.info("[campus] 二手成交 {} 确认人 {} 今日成交积分已达上限，本次不发分", tradeId, sellerId);
        }
        return confirmView(mapper.findById(tradeId), !samePairWithin7Days, false, skipReason);
    }

    // ==================== 买卖任一方：取消 ====================

    /** 取消待确认的成交。已确认的不能取消（成交与商品已售不能回退），已取消的幂等返回。 */
    @Transactional
    public Map<String, Object> cancel(Long tradeId, Long userId) {
        if (tradeId == null || tradeId < 1) throw new IllegalArgumentException("成交编号无效");
        if (userId == null || userId < 1) throw new ForbiddenException("请先登录");

        Map<String, Object> trade = mapper.findById(tradeId);
        if (trade == null) throw new IllegalArgumentException("成交记录不存在");
        long buyerId = number(trade.get("buyerId"), "成交记录异常");
        long sellerId = number(trade.get("sellerId"), "成交记录异常");
        if (userId != buyerId && userId != sellerId) throw new ForbiddenException("无权取消该成交");

        String status = str(trade.get("status"));
        if (CANCELLED.equals(status)) return cancelView(trade);
        if (CONFIRMED.equals(status)) throw new IllegalStateException("已确认的成交不能取消");

        if (mapper.cancel(tradeId, userId) != 1) {
            Map<String, Object> fresh = mapper.findById(tradeId);
            if (fresh != null && CANCELLED.equals(str(fresh.get("status")))) return cancelView(fresh);
            throw new IllegalStateException("成交状态已变化，请刷新后重试");
        }
        log.info("[campus] 二手成交 {} 已被用户 {} 取消", tradeId, userId);
        return cancelView(mapper.findById(tradeId));
    }

    // ==================== 买家：刷新/重进商品页时恢复自己的成交状态 ====================

    /**
     * 买家侧：返回**当前登录用户自己**在这件商品上的那笔成交。
     *
     * 只按 (goodsId, buyerId) 查，所以：
     *   - 别的买家查同一商品拿不到（互相不可见）；
     *   - 卖家查自己发布的商品也拿不到买家的成交（卖家视角走「我的发布」）。
     * 没有记录返回空 Map 而不是 404 —— 前端拿 {} 统一按「正常售卖态」渲染。
     */
    public Map<String, Object> findMine(Long goodsId, Long buyerId) {
        if (goodsId == null || goodsId < 1) throw new IllegalArgumentException("商品编号无效");
        if (buyerId == null || buyerId < 1) throw new ForbiddenException("请先登录");
        Map<String, Object> row = mapper.findMineByGoodsAndBuyer(goodsId, buyerId);
        if (row == null) return new LinkedHashMap<>();
        // 显式收敛成约定的 6 个字段：MyBatis 对 NULL 列默认不放进 Map，
        // 这里补上 confirmedAt=null，保证前端拿到的结构稳定，也避免内部字段外泄。
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("status", row.get("status"));
        out.put("createdAt", row.get("createdAt"));
        out.put("confirmedAt", row.get("confirmedAt"));
        out.put("sellerId", row.get("sellerId"));
        out.put("buyerId", row.get("buyerId"));
        return out;
    }

    // ==================== 卖家：我的发布 + 最近一笔成交 ====================

    /**
     * 「我的发布」角标数据源：我发布的二手商品 + 该商品最近一笔成交。
     * 一件商品最多一笔成交（uk_trade_goods），所以一个商品一行，不需要在 Java 里再聚合。
     */
    public Map<String, Object> mine(Long sellerId) {
        if (sellerId == null || sellerId < 1) throw new ForbiddenException("请先登录");
        List<Map<String, Object>> list = mapper.sellerTrades(sellerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", list.size());
        return out;
    }

    // ==================== 内部 ====================

    /**
     * 双方发分放在事务提交后：成交本身已经落库，积分服务异常不能把成交回滚掉。
     * afterCommit 里必须走 REQUIRES_NEW（见 {@link AfterCommitPointsAwarder} 的类注释）。
     */
    private void awardBothAfterCommit(Long tradeId, Long buyerId, Long sellerId) {
        Runnable task = () -> {
            awarder.award(buyerId, TASK_KEY, bizKey(tradeId, buyerId), "二手成交（买家）");
            awarder.award(sellerId, TASK_KEY, bizKey(tradeId, sellerId), "二手成交（卖家）");
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            // 没有事务上下文（例如单元测试直接 new 出来的服务）：立即执行，行为等价于已提交
            task.run();
        }
    }

    /** 幂等键：一笔成交里每个用户各一次（买卖双方各算一次日上限）。 */
    private static String bizKey(Long tradeId, Long userId) {
        return "trade:" + tradeId + ":" + userId;
    }

    private Map<String, Object> tradeView(Map<String, Object> row, boolean alreadyPending) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tradeId", row.get("id"));
        out.put("goodsId", row.get("goodsId"));
        out.put("buyerId", row.get("buyerId"));
        out.put("sellerId", row.get("sellerId"));
        out.put("status", row.get("status"));
        out.put("createdAt", row.get("createdAt"));
        // 同一个人重复点「我已买到」时置 true，前端据此保持「等待卖家确认」
        out.put("alreadyPending", alreadyPending);
        return out;
    }

    private Map<String, Object> confirmView(Map<String, Object> row, boolean awarded, boolean alreadyConfirmed,
                                           String skipReason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tradeId", row.get("id"));
        out.put("goodsId", row.get("goodsId"));
        out.put("buyerId", row.get("buyerId"));
        out.put("sellerId", row.get("sellerId"));
        out.put("status", row.get("status"));
        out.put("goodsStatus", GOODS_SOLD);
        out.put("confirmedAt", row.get("confirmedAt"));
        // 是否安排了发分（7 天互刷命中时为 false；积分日上限是否命中由积分服务判定，这里不预判）
        out.put("awarded", awarded);
        // 本次没发分的原因：pair7days / daily_cap / null（前端据此选提示文案）
        out.put("skipReason", skipReason);
        out.put("alreadyConfirmed", alreadyConfirmed);
        return out;
    }

    /**
     * 「本次会不会发分」的说明性判定，只影响提示文案，不影响是否真的调用发分。
     *
     * 确认人（卖家）自己今日到账已满 task_catalog 的日上限时返回 {@link #SKIP_DAILY_CAP}。
     * 上限读不到（任务行缺失 / 表异常）时不拦也不报错，按「可能发分」处理 ——
     * 积分服务才是最终判定，这里错了最多是一句提示文案不精确。
     */
    private String skipReason(boolean samePairWithin7Days, Long sellerId) {
        if (samePairWithin7Days) return SKIP_PAIR_7_DAYS;
        try {
            Integer cap = mapper.marketTaskDailyCap();
            if (cap == null || cap <= 0) return null;
            return mapper.countTodayTradeAwards(sellerId) >= cap ? SKIP_DAILY_CAP : null;
        } catch (RuntimeException e) {
            log.warn("[campus] 查询二手成交日上限失败（按可能发分处理）: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> cancelView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tradeId", row.get("id"));
        out.put("goodsId", row.get("goodsId"));
        out.put("status", row.get("status"));
        out.put("cancelledAt", row.get("cancelledAt"));
        return out;
    }

    private static long number(Object value, String message) {
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException e) {
            throw new IllegalStateException(message);
        }
    }

    private static int intNumber(Object value) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}