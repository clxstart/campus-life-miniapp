package com.campuslife.service;

import com.campuslife.mapper.TierMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 段位推导与特权判定的单元测试（不连库）。
 *
 * 段位是纯函数：输入成长值，输出段位与进度。边界值（59/60/199/200/9999）
 * 与「只开放前三段」的显示口径是这一层最容易写错的地方，必须逐个钉死。
 */
class TierServiceTest {

    private TierMapper mapper;
    private FeatureFlags flags;
    private TierService service;

    private static Map<String, Object> tier(String code, String name, int threshold, int enabled) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tierCode", code);
        row.put("name", name);
        row.put("gThreshold", threshold);
        row.put("enabled", enabled);
        row.put("sort", threshold);
        return row;
    }

    @BeforeEach
    void setUp() {
        mapper = mock(TierMapper.class);
        flags = mock(FeatureFlags.class);
        when(flags.isEnabled(anyString())).thenReturn(true);
        List<Map<String, Object>> tiers = new ArrayList<>();
        tiers.add(tier("bronze", "青铜·萌新", 0, 1));
        tiers.add(tier("silver", "白银·圈内熟人", 60, 1));
        tiers.add(tier("gold", "黄金·活跃分子", 200, 1));
        tiers.add(tier("platinum", "铂金·风云人物", 600, 0));
        tiers.add(tier("diamond", "钻石·一校红人", 1500, 0));
        tiers.add(tier("star", "星耀·校园顶流", 3500, 0));
        tiers.add(tier("king", "王者·城市之星", 8000, 0));
        tiers.add(tier("legend", "传奇·校宝名人堂", 18000, 0));
        when(mapper.tiers()).thenReturn(tiers);
        service = new TierService(mapper, flags, Clock.systemDefaultZone());
    }

    private Map<String, Object> growthOf(int g) {
        return service.forGrowth(g);
    }

    @Test
    void newUserIsBronzeWithZeroProgress() {
        Map<String, Object> tier = growthOf(0);
        assertThat(tier.get("code")).isEqualTo("bronze");
        assertThat(tier.get("nextCode")).isEqualTo("silver");
        assertThat(tier.get("nextThreshold")).isEqualTo(60);
        assertThat(tier.get("percent")).isEqualTo(0);
        assertThat(tier.get("maxOpen")).isEqualTo(false);
    }

    @Test
    void boundaryFiftyNineStaysBronzeAndSixtyBecomesSilver() {
        assertThat(growthOf(59).get("code")).isEqualTo("bronze");
        assertThat(growthOf(60).get("code")).isEqualTo("silver");
        assertThat(growthOf(199).get("code")).isEqualTo("silver");
        assertThat(growthOf(200).get("code")).isEqualTo("gold");
    }

    @Test
    void beyondLastOpenTierStaysGoldAndMarksMaxOpen() {
        Map<String, Object> tier = growthOf(9999);
        assertThat(tier.get("code")).isEqualTo("gold");
        assertThat(tier.get("maxOpen")).isEqualTo(true);
        assertThat(tier.get("nextCode")).isNull();
        assertThat(tier.get("percent")).isEqualTo(100);
        // 未开放的铂金不应被当作已达成
        assertThat(tier.get("name")).isEqualTo("黄金·活跃分子");
    }

    @Test
    void progressIsFloorAndMatchesDesignExample() {
        // 设计稿用例 6：G=142，白银 60，黄金 200 → (142-60)/(200-60)=58.57 → 58
        assertThat(growthOf(142).get("percent")).isEqualTo(58);
        assertThat(growthOf(60).get("percent")).isEqualTo(0);
    }

    @Test
    void expandedTierGrantsSilverAndGoldPrivileges() {
        when(mapper.growth(1L)).thenReturn(142);
        Map<String, Object> priv = service.privileges(1L);
        assertThat(priv.get("priorityReply")).isEqualTo(true);
        assertThat(priv.get("postFastlane")).isEqualTo(false);
        assertThat(priv.get("circleCreate")).isEqualTo(false);

        when(mapper.growth(2L)).thenReturn(200);
        Map<String, Object> gold = service.privileges(2L);
        assertThat(gold.get("priorityReply")).isEqualTo(true);
        assertThat(gold.get("postFastlane")).isEqualTo(true);
        assertThat(gold.get("circleCreate")).isEqualTo(true);
    }

    @Test
    void featureFlagsCanTurnOffFastlaneAndCircleCreation() {
        when(mapper.growth(2L)).thenReturn(200);
        when(flags.isEnabled("post.fastlane.enabled")).thenReturn(false);
        when(flags.isEnabled("circle.create.enabled")).thenReturn(false);
        Map<String, Object> priv = service.privileges(2L);
        assertThat(priv.get("priorityReply")).isEqualTo(true);
        assertThat(priv.get("postFastlane")).isEqualTo(false);
        assertThat(priv.get("circleCreate")).isEqualTo(false);
    }

    @Test
    void trialTierRaisesEffectiveTierWithoutChangingGrowth() {
        when(mapper.growth(3L)).thenReturn(0);
        Map<String, Object> trial = new LinkedHashMap<>();
        trial.put("privKey", "tier_trial_silver");
        trial.put("expiresAt", LocalDateTime.now().plusDays(7));
        when(mapper.activePrivileges(eq(3L), any())).thenReturn(List.of(trial));

        assertThat(service.tierCodeForGrowth(0)).isEqualTo("bronze"); // 展示仍按 G
        assertThat(service.effectiveTierCode(3L)).isEqualTo("silver"); // 特权按生效段位
        assertThat(service.privileges(3L).get("priorityReply")).isEqualTo(true);
        assertThat(service.privileges(3L).get("postFastlane")).isEqualTo(false);
    }

    @Test
    void expiredOrOtherPrivilegesDoNotRaiseTier() {
        when(mapper.growth(4L)).thenReturn(0);
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("privKey", "priority_reply");
        other.put("expiresAt", null);
        when(mapper.activePrivileges(eq(4L), any())).thenReturn(List.of(other));
        assertThat(service.effectiveTierCode(4L)).isEqualTo("bronze");
    }

    // ==================== 固定时钟：体验段位到期边界（设计稿用例 12）====================

    /**
     * 授体验当天有效 → 第 7 天末仍有效 → 第 8 天（到期时刻之后）立刻回落。
     *
     * 用固定时钟 + 模拟「库只返回未过期行」的 mapper，把时间边界钉死；
     * 真实 SQL 的 `expires_at > #{at}` 由 TierGrowthIntegrationTest 覆盖。
     */
    @Test
    void trialTierIsValidOnDayOneAndFallsBackOnDayEight() {
        Instant start = Instant.parse("2026-01-01T02:00:00Z"); // 北京时间 10:00
        MutableClock clock = new MutableClock(start, ZoneId.of("Asia/Shanghai"));
        TierService fixed = new TierService(mapper, flags, clock);
        when(mapper.growth(5L)).thenReturn(0);

        // 模拟库行为：只返回 expires_at > at 的行
        LocalDateTime expiresAt = LocalDateTime.now(clock).plusDays(7);
        when(mapper.activePrivileges(eq(5L), any())).thenAnswer(inv -> {
            LocalDateTime at = inv.getArgument(1);
            return expiresAt.isAfter(at)
                ? List.of(Map.of("privKey", "tier_trial_silver", "expiresAt", expiresAt))
                : List.of();
        });

        // 授体验：到期时间 = 授体验时刻 + 7 天（时间源是注入的时钟，不是系统时钟）
        fixed.grantTrial(5L, "silver", 7);
        verify(mapper).grant(5L, "tier_trial_silver", expiresAt);

        // 第 1 天：白银生效
        assertThat(fixed.effectiveTierCode(5L)).isEqualTo("silver");
        assertThat(fixed.privileges(5L).get("priorityReply")).isEqualTo(true);

        // 第 7 天最后一刻：仍然有效（expires_at > at）
        clock.advance(Duration.ofDays(7).minusSeconds(1));
        assertThat(fixed.effectiveTierCode(5L)).isEqualTo("silver");
        assertThat(fixed.privileges(5L).get("priorityReply")).isEqualTo(true);

        // 第 8 天（到期瞬间之后）：回落到成长值推导的青铜，白银特权消失
        clock.advance(Duration.ofSeconds(2));
        assertThat(fixed.effectiveTierCode(5L)).isEqualTo("bronze");
        Map<String, Object> after = fixed.privileges(5L);
        assertThat(after.get("priorityReply")).isEqualTo(false);
        assertThat(after.get("postFastlane")).isEqualTo(false);
        assertThat(after.get("circleCreate")).isEqualTo(false);
        assertThat(after.get("trialExpiresAt")).isNull();
    }

    /** 设计稿用例 8 的「30 秒 TTL」：用假时钟快进，不真等 30 秒。 */
    @Test
    void configCacheExpiresAfterThirtySecondsOnInjectedClock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        TierService fixed = new TierService(mapper, flags, clock);

        fixed.forGrowth(0);
        fixed.forGrowth(60);
        verify(mapper, times(1)).tiers();

        // 29 秒：仍在缓存窗口内
        clock.advance(Duration.ofSeconds(29));
        fixed.forGrowth(200);
        verify(mapper, times(1)).tiers();

        // 31 秒：缓存过期，重新读配置（管理端改门槛后最多 30 秒生效）
        clock.advance(Duration.ofSeconds(2));
        fixed.forGrowth(200);
        verify(mapper, times(2)).tiers();
    }

    /** 可推进的测试时钟：只实现 Clock 的四个抽象方法，不依赖第三方库。 */
    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) { instant = instant.plus(duration); }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zoneId) { return new MutableClock(instant, zoneId); }
        @Override public Instant instant() { return instant; }
    }

    /** 进度按「当前段位门槛 → 下一段位门槛」算，向下取整并夹在 0..100。 */
    @Test
    void progressIsFlooredAndClampedWithinEachBand() {
        // 白银区间 60..200，跨度 140：61 → 0.71% → 0；150 → 64.28% → 64；199 → 99.28% → 99
        assertThat(growthOf(61).get("percent")).isEqualTo(0);
        assertThat(growthOf(150).get("percent")).isEqualTo(64);
        assertThat(growthOf(199).get("percent")).isEqualTo(99);
        // 青铜区间 0..60：59 → 98.33% → 98
        assertThat(growthOf(59).get("percent")).isEqualTo(98);
        // 刚到黄金门槛：已是最高开放段位，进度直接满
        assertThat(growthOf(200).get("percent")).isEqualTo(100);
        // 进白银的瞬间从 0 起算，不继承青铜的 100%
        assertThat(growthOf(60).get("percent")).isEqualTo(0);
    }

    /** 段位配置缓存 30 秒：连读不重复查库，evict() 后立刻回源（管理端改门槛的路径）。 */
    @Test
    void configIsCachedUntilEvicted() {
        service.forGrowth(0);
        service.forGrowth(60);
        service.forGrowth(200);
        verify(mapper, times(1)).tiers();

        service.evict();
        service.forGrowth(0);
        verify(mapper, times(2)).tiers();
    }

    @Test
    void adminConfigMarksOnlyThreeTiersOpen() {
        List<Map<String, Object>> config = service.configForAdmin();
        assertThat(config).hasSize(8);
        assertThat(config.stream().filter(c -> Boolean.TRUE.equals(c.get("enabled")))).hasSize(3);
        assertThat(config.stream().filter(c -> !Boolean.TRUE.equals(c.get("enabled"))))
            .allMatch(c -> "未开放".equals(c.get("privilegeSummary")));
    }
}