package com.campuslife.service;

import com.campuslife.mapper.RecommendMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// 首页「今日推荐」：外卖最火 + 二手最火，按真实行为热度排序。
// 「换一批」通过 offset 轮换，永远展示当前真正最热的那几条。
@Service @RequiredArgsConstructor
public class RecommendService {
    private final RecommendMapper mapper;

    // 60 秒结果缓存。
    //
    // 为什么需要：hotGoods / hotShops 的排序键是计算式（浏览×3 + 收藏×5 + 咨询×8），
    // 任何索引都用不上，每次请求都等于「全表扫 + filesort」；再配两条 COUNT(*)。
    // 这是首页——进入小程序的第一屏——最贵的部分。缓存 60 秒把它从「每人每次」
    // 降到「每分钟一次」，而 60 秒的陈旧度对「今日推荐」完全够用。
    //
    // 只缓存「按结果集算」的部分：商品的 "我收藏过没有" 是按人算的，不进缓存，
    // 每次请求用一次 IN 查询现算，所以点收藏立即生效。
    private static final long CACHE_TTL_MS = 60_000L;
    /** 条目上限：offset 虽然被取模收敛，仍兜一道，防止有人用 batch 试出大量 key。 */
    private static final int CACHE_MAX = 256;

    private record Cached(Object value, long at) {}

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, java.util.function.Supplier<T> loader) {
        long now = System.currentTimeMillis();
        Cached hit = cache.get(key);
        if (hit != null && now - hit.at() < CACHE_TTL_MS) return (T) hit.value();
        T value = loader.get();
        if (cache.size() >= CACHE_MAX) cache.clear();
        cache.put(key, new Cached(value, now));
        return value;
    }

    private long countShops() { return cached("count:shop", mapper::countShops); }

    private long countGoods() { return cached("count:goods", mapper::countGoods); }

    private List<Map<String,Object>> hotShops(int size, int offset) {
        return cached("shops:" + size + ":" + offset, () -> mapper.hotShops(size, offset));
    }

    private List<Map<String,Object>> hotGoods(int size, int offset) {
        return cached("goods:" + size + ":" + offset, () -> mapper.hotGoods(size, offset));
    }

    /** 本用户在这批商品里收藏过哪几个。按人算，不进缓存。 */
    private Set<Long> favoredIds(Long userId, List<Map<String,Object>> goods) {
        if (goods.isEmpty()) return Set.of();
        List<Long> ids = new ArrayList<>(goods.size());
        for (Map<String,Object> item : goods) {
            long id = number(item.get("id"));
            if (id > 0) ids.add(id);
        }
        if (ids.isEmpty()) return Set.of();
        try {
            List<Long> hit = mapper.favoriteGoodsIds(userId, ids);
            return hit == null ? Set.of() : new HashSet<>(hit);
        } catch (Exception e) {
            // 收藏星标是装饰信息，查不出来不该让整个首页失败
            return Set.of();
        }
    }

    private static long number(Object value) {
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof Number n) return n.longValue();
        return 0;
    }

    // 一次取推荐所需的全部内容：外卖 1 条 + 二手 1 条（与首页两张卡对齐）。
    // batch 为第几批，用于「换一批」轮换。
    public Map<String,Object> home(Long userId, int batch, int perType) {
        Long uid = (userId == null || userId < 1) ? 1L : userId;
        int size = Math.max(1, Math.min(perType, 10));
        long shopTotal = countShops();
        long goodsTotal = countGoods();

        // 轮换：批次 * 每类条数，取模保证循环；总数为 0 时不取模，避免除零
        int shopOffset = shopTotal == 0 ? 0 : (int) (((long) batch * size) % shopTotal);
        int goodsOffset = goodsTotal == 0 ? 0 : (int) (((long) batch * size) % goodsTotal);

        List<Map<String,Object>> shops = hotShops(size, shopOffset);
        List<Map<String,Object>> goods = hotGoods(size, goodsOffset);
        Set<Long> favored = favoredIds(uid, goods);

        List<Map<String,Object>> cards = new ArrayList<>();
        for (Map<String,Object> shop : shops) {
            Map<String,Object> card = new LinkedHashMap<>();
            card.put("type", "shop");
            card.put("id", shop.get("id"));
            card.put("tag", "美食");
            card.put("title", shop.get("name"));
            card.put("subtitle", shop.get("discount") == null || String.valueOf(shop.get("discount")).isBlank()
                ? "查看店铺菜单与优惠"
                : String.valueOf(shop.get("discount")) + " · 查看菜单");
            card.put("image", shop.get("cover"));
            card.put("location", shop.get("category"));
            card.put("heat", number(shop.get("heat")));
            card.put("heatText", orderHeatText(number(shop.get("orderCount"))));
            card.put("url", "/pages/food-shop/food-shop?id=" + shop.get("id"));
            cards.add(card);
        }
        for (Map<String,Object> item : goods) {
            Map<String,Object> card = new LinkedHashMap<>();
            card.put("type", "goods");
            card.put("id", item.get("id"));
            card.put("tag", number(item.get("mode")) == 2 ? "求购" : "闲置");
            card.put("title", item.get("title"));
            card.put("subtitle", item.get("description") == null ? "" : item.get("description"));
            card.put("image", item.get("image"));
            card.put("location", item.get("category"));
            card.put("price", item.get("price"));
            card.put("heat", number(item.get("heat")));
            card.put("heatText", goodsHeatText(number(item.get("viewCount")), number(item.get("favoriteCount")), number(item.get("contactCount"))));
            card.put("favored", favored.contains(number(item.get("id"))));
            card.put("url", "/pages/market-detail/market-detail?id=" + item.get("id"));
            cards.add(card);
        }

        Map<String,Object> out = new LinkedHashMap<>();
        out.put("cards", cards);
        out.put("batch", batch);
        out.put("shopTotal", shopTotal);
        out.put("goodsTotal", goodsTotal);
        // 两类都有下一条时才算还有下一批，避免只有一类在轮换
        out.put("hasMore", shopTotal > size || goodsTotal > size);
        out.put("generatedAt", java.time.LocalDateTime.now().toString());
        return out;
    }

    // 外卖卡片的热度文案：以真实订单为主，没有订单时退回浏览，都没有则显示新店
    private static String orderHeatText(long orderCount) {
        if (orderCount > 0) return orderCount + " 人下单";
        return "新入驻";
    }

    // 闲置卡片的热度文案：按最能说明热度的行为依次展示
    private static String goodsHeatText(long views, long favorites, long contacts) {
        if (contacts > 0) return contacts + " 人咨询";
        if (favorites > 0) return favorites + " 人收藏";
        if (views > 0) return views + " 次浏览";
        return "新上架";
    }

    // ---- 埋点：由真实用户行为触发 ----
    public void viewGoods(Long id) { if (id != null && id > 0) mapper.viewGoods(id); }
    public void contactGoods(Long id) { if (id != null && id > 0) mapper.contactGoods(id); }
    public void viewShop(Long id) { if (id != null && id > 0) mapper.viewShop(id); }
    public void orderPlaced(Long shopId) { if (shopId != null && shopId > 0) mapper.increaseShopOrder(shopId); }

    @Transactional public Map<String,Object> toggleGoodsFavorite(Long goodsId, Long userId) {
        if (goodsId == null || goodsId < 1) throw new IllegalArgumentException("商品参数无效");
        Long uid = (userId == null || userId < 1) ? 1L : userId;
        boolean favored;
        if (mapper.hasGoodsFavorite(goodsId, uid) > 0) {
            mapper.removeGoodsFavorite(goodsId, uid);
            favored = false;
        } else {
            mapper.addGoodsFavorite(goodsId, uid);
            favored = true;
        }
        mapper.syncGoodsFavorite(goodsId);
        return Map.of("favored", favored);
    }
}
