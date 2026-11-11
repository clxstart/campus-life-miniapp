package com.campuslife.service;

import com.campuslife.mapper.RecommendMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 首页推荐的缓存边界：只缓存「按结果集算」的部分，按人算的收藏标记必须每次现算。
 *
 * 背景：hotGoods / hotShops 的排序键是计算式，任何索引都用不上，每次都等于全表扫 + filesort。
 * 缓存必须真的命中，否则这次改造等于没做；但收藏标记一旦被缓存，A 用户会看到 B 用户的收藏状态。
 */
class RecommendServiceTest {

    private static Map<String, Object> shop(long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", "店铺" + id);
        m.put("cover", "");
        m.put("category", "快餐");
        m.put("rating", 4.5);
        m.put("minPrice", 10);
        m.put("deliveryFee", 2);
        m.put("deliveryTime", 30);
        m.put("discount", "");
        m.put("notice", "");
        m.put("orderCount", 3);
        m.put("shopViewCount", 10);
        m.put("heat", 25);
        return m;
    }

    private static Map<String, Object> goods(long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", "商品" + id);
        m.put("price", "9.90");
        m.put("image", "");
        m.put("category", "教材");
        m.put("conditionLevel", "九成新");
        m.put("description", "描述" + id);
        m.put("mode", 1);
        m.put("viewCount", 5);
        m.put("favoriteCount", 2);
        m.put("contactCount", 1);
        m.put("heat", 33);
        return m;
    }

    private static RecommendMapper mapperWith() {
        RecommendMapper mapper = mock(RecommendMapper.class);
        when(mapper.countShops()).thenReturn(2L);
        when(mapper.countGoods()).thenReturn(2L);
        when(mapper.hotShops(anyInt(), anyInt())).thenReturn(List.of(shop(1), shop(2)));
        when(mapper.hotGoods(anyInt(), anyInt())).thenReturn(List.of(goods(10), goods(11)));
        return mapper;
    }

    @Test
    void cachesTheExpensivePartsAcrossRequests() {
        RecommendMapper mapper = mapperWith();
        RecommendService service = new RecommendService(mapper);

        service.home(2L, 0, 1);
        service.home(2L, 0, 1);
        service.home(3L, 0, 1);   // 换个人看同一批：结果集仍应复用

        // 两条 COUNT(*) 与两条全表排序都只跑一次
        verify(mapper, times(1)).countShops();
        verify(mapper, times(1)).countGoods();
        verify(mapper, times(1)).hotShops(anyInt(), anyInt());
        verify(mapper, times(1)).hotGoods(anyInt(), anyInt());
    }

    @Test
    void doesNotCacheFavoredFlagPerUser() {
        RecommendMapper mapper = mapperWith();
        when(mapper.favoriteGoodsIds(anyLong(), anyList())).thenReturn(List.of(10L));
        RecommendService service = new RecommendService(mapper);

        service.home(2L, 0, 1);
        service.home(3L, 0, 1);

        // 每个用户都要现算一次，绝不能复用上一个人的收藏状态
        verify(mapper, times(1)).favoriteGoodsIds(eq(2L), anyList());
        verify(mapper, times(1)).favoriteGoodsIds(eq(3L), anyList());
    }

    @Test
    void stampsFavoredFromTheBatchQuery() {
        RecommendMapper mapper = mapperWith();
        when(mapper.favoriteGoodsIds(anyLong(), anyList())).thenReturn(List.of(10L));
        RecommendService service = new RecommendService(mapper);

        Map<String, Object> out = service.home(2L, 0, 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) out.get("cards");
        Map<String, Object> first = cards.stream()
            .filter(c -> "goods".equals(c.get("type")) && Long.valueOf(10L).equals(c.get("id")))
            .findFirst().orElseThrow();
        Map<String, Object> second = cards.stream()
            .filter(c -> "goods".equals(c.get("type")) && Long.valueOf(11L).equals(c.get("id")))
            .findFirst().orElseThrow();

        assertThat(first.get("favored")).isEqualTo(true);
        assertThat(second.get("favored")).isEqualTo(false);
    }

    @Test
    void favoriteLookupFailureDoesNotBreakTheHomePage() {
        RecommendMapper mapper = mapperWith();
        when(mapper.favoriteGoodsIds(anyLong(), anyList())).thenThrow(new IllegalStateException("库挂了"));
        RecommendService service = new RecommendService(mapper);

        Map<String, Object> out = service.home(2L, 0, 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) out.get("cards");

        assertThat(cards).hasSize(4);   // 2 店 + 2 商品，星标降级为 false 但页面照常返回
        assertThat(cards).allSatisfy(c -> {
            if ("goods".equals(c.get("type"))) assertThat(c.get("favored")).isEqualTo(false);
        });
    }

    @Test
    void emptyTablesStillReturnAnEmptyDeck() {
        RecommendMapper mapper = mock(RecommendMapper.class);
        when(mapper.countShops()).thenReturn(0L);
        when(mapper.countGoods()).thenReturn(0L);
        when(mapper.hotShops(anyInt(), anyInt())).thenReturn(List.of());
        when(mapper.hotGoods(anyInt(), anyInt())).thenReturn(List.of());
        RecommendService service = new RecommendService(mapper);

        Map<String, Object> out = service.home(2L, 5, 3);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) out.get("cards");
        assertThat(cards).isEmpty();
        assertThat(out.get("hasMore")).isEqualTo(false);
        // 空表时不该去查收藏（IN () 会直接语句错误）
        verify(mapper, times(0)).favoriteGoodsIds(anyLong(), anyList());
    }
}