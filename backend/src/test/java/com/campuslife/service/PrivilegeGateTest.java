package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.AuditResult;
import com.campuslife.dto.CreatePostRequest;
import com.campuslife.entity.Post;
import com.campuslife.entity.User;
import com.campuslife.mapper.CircleMapper;
import com.campuslife.mapper.JobApplicationMapper;
import com.campuslife.mapper.JobContractMapper;
import com.campuslife.mapper.JobDisputeMapper;
import com.campuslife.mapper.JobMapper;
import com.campuslife.mapper.EarningsLedgerMapper;
import com.campuslife.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 段位特权的服务层闸门（不连库、不起 HTTP）。
 *
 * 设计稿第六节里「快速应答 / 发帖免审 / 建圈」三类用例都依赖登录态与真实数据，
 * 这里把它们收敛到 Service 的决策点：给出段位与开关，断言「给不给」，以及
 * 不该落库时确实没有落库。HTTP 层（401/403 状态码）仍需 curl 验证。
 */
class PrivilegeGateTest {

    private static final long UID = 1L;

    private UserMapper users;
    private TierService tier;
    private FeatureFlags flags;
    private AuditService audit;
    private CircleMapper circleMapper;
    private CircleService circleService;
    private JobMapper jobMapper;
    private JobService jobService;
    private PostMapperHolder forum;
    private com.campuslife.mapper.PostMapper postMapper;

    /** PostMapper 在 mapper 包，单独起个别名只是为了少写全限定名。 */
    private record PostMapperHolder(ForumService service) {}

    @BeforeEach
    void setUp() {
        users = mock(UserMapper.class);
        User user = new User();
        user.setId(UID);
        user.setSchoolId(5L);
        when(users.selectById(UID)).thenReturn(user);

        tier = mock(TierService.class);
        when(tier.rankOf(anyString())).thenAnswer(inv -> switch (String.valueOf((Object) inv.getArgument(0))) {
            case "bronze" -> 1;
            case "silver" -> 2;
            case "gold" -> 3;
            default -> 0;
        });
        flags = mock(FeatureFlags.class);
        when(flags.isEnabled(anyString())).thenReturn(true);

        audit = mock(AuditService.class);
        when(audit.checkText(anyString(), anyString(), any())).thenReturn(AuditResult.passed());
        when(audit.initialStatus(any(), anyBoolean())).thenAnswer(inv -> {
            AuditResult r = inv.getArgument(0);
            if (r.reviewing()) return "reviewing";
            return ((boolean) inv.getArgument(1)) ? "pending" : "passed";
        });
        // 与真实实现同语义：被拒内容抛异常，调用方因此不会继续落库
        doAnswer(inv -> {
            AuditResult r = inv.getArgument(0);
            if (!r.pass()) throw new IllegalArgumentException(r.reason());
            return null;
        }).when(audit).rejectWithLog(any(), anyString(), any(), anyString());

        circleMapper = mock(CircleMapper.class);
        circleService = new CircleService(circleMapper, users, mock(CircleStreakService.class), tier, flags, audit);

        jobMapper = mock(JobMapper.class);
        jobService = new JobService(jobMapper,
            mock(JobApplicationMapper.class), mock(JobContractMapper.class),
            mock(JobDisputeMapper.class), mock(EarningsLedgerMapper.class),
            flags, mock(VerificationService.class), users, audit, tier,
            mock(AdminAuthService.class), mock(AfterCommitPointsAwarder.class));

        postMapper = mock(com.campuslife.mapper.PostMapper.class);
        forum = new PostMapperHolder(new ForumService(
            mock(com.campuslife.mapper.CampusMapper.class), mock(CircleService.class), postMapper, users,
            mock(com.campuslife.mapper.PostLikeMapper.class), mock(com.campuslife.mapper.CommentMapper.class),
            mock(com.campuslife.mapper.CommentLikeMapper.class), mock(com.campuslife.mapper.FollowMapper.class),
            mock(com.campuslife.mapper.PostViewMapper.class), mock(com.campuslife.mapper.FavoriteMapper.class),
            mock(com.campuslife.mapper.BannerMapper.class), audit,
            mock(UserBlockService.class), tier));
    }

    // ==================== 快速应答（设计稿 5-1 / 5-2 / 5-3）====================

    private List<Map<String, Object>> jobRows() {
        long[][] data = {{1, 2}, {2, 5}, {3, 2}, {4, 5}, {5, 5}};
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long[] d : data) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d[0]);
            row.put("schoolId", d[1]);
            rows.add(row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(long uid, int page, boolean priorityReply) {
        when(tier.privileges(uid)).thenReturn(Map.<String, Object>of("priorityReply", priorityReply));
        when(jobMapper.list(anyString(), anyString(), any(), anyString(), anyInt(), anyInt()))
            .thenAnswer(inv -> jobRows());
        when(jobMapper.count(anyString(), anyString(), any(), anyString())).thenReturn(5L);
        Map<String, Object> out = (Map<String, Object>) jobService.list(
            null, null, null, null, uid, null, page, 10);
        return (List<Map<String, Object>>) out.get("list");
    }

    @Test
    void silverSeesTwoPrioritySameSchoolJobsOnFirstPage() {
        List<Map<String, Object>> rows = list(UID, 1, true);
        assertThat(rows).extracting(r -> r.get("id")).containsExactly(2L, 4L, 1L, 3L, 5L);
        assertThat(rows.get(0).get("priority")).isEqualTo(true);
        assertThat(rows.get(1).get("priority")).isEqualTo(true);
        assertThat(rows.get(2).containsKey("priority")).isFalse();
        assertThat(rows.get(3).containsKey("priority")).isFalse();
        assertThat(rows.get(4).containsKey("priority")).isFalse();
    }

    @Test
    void bronzeSeesOriginalOrderWithoutPriorityMark() {
        List<Map<String, Object>> rows = list(UID, 1, false);
        assertThat(rows).extracting(r -> r.get("id")).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(rows).allMatch(r -> !r.containsKey("priority"));
    }

    @Test
    void secondPageIsNotInjected() {
        List<Map<String, Object>> rows = list(UID, 2, true);
        assertThat(rows).extracting(r -> r.get("id")).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(rows).allMatch(r -> !r.containsKey("priority"));
    }

    // ==================== 发帖免审（设计稿 5-4 / 5-5 / 5-6）====================

    private CreatePostRequest post(String image) {
        CreatePostRequest r = new CreatePostRequest();
        r.setUserId(UID);
        r.setTitle("标题");
        r.setContent("正文");
        r.setImage(image);
        return r;
    }

    private void fastlane(boolean on) {
        when(tier.privileges(UID)).thenReturn(Map.<String, Object>of("postFastlane", on));
    }

    @Test
    void goldFastlanePublishesImagePostImmediatelyAndLogsSource() {
        fastlane(true);
        Post created = forum.service().create(post("/uploads/a.jpg"));
        assertThat(created.getStatus()).isEqualTo(1);        // 立即可见
        assertThat(created.getAuditStatus()).isEqualTo("pending"); // 仍异步过图片审核
        verify(audit).logFastlane(eq("post"), isNull(), eq(UID), anyString());
        verify(audit).scheduleImagesAfterCommit(any(), eq("post"), any(), eq(UID));
    }

    @Test
    void nonGoldWithImageStillWaitsForReview() {
        fastlane(false);
        Post created = forum.service().create(post("/uploads/a.jpg"));
        assertThat(created.getStatus()).isEqualTo(0);
        assertThat(created.getAuditStatus()).isEqualTo("pending");
        verify(audit, never()).logFastlane(anyString(), any(), any(), anyString());
    }

    @Test
    void fastlaneSwitchOffFallsBackToNormalReview() {
        fastlane(false);
        Post created = forum.service().create(post(null));
        assertThat(created.getStatus()).isEqualTo(1); // 纯文本本来就 passed
        verify(audit, never()).logFastlane(anyString(), any(), any(), anyString());
    }

    @Test
    void rejectedTextIsNeverInsertedEvenForGold() {
        fastlane(true);
        when(audit.checkText(anyString(), anyString(), any()))
            .thenReturn(new AuditResult(false, false, "内容包含违规词：测试", List.of("测试")));
        assertThatThrownBy(() -> forum.service().create(post(null)))
            .isInstanceOf(IllegalArgumentException.class);
        verify(postMapper, never()).insert(any());
    }

    // ==================== 建圈（设计稿 5-7 / 5-8 / 5-9 / 5-11）====================

    private Map<String, Object> circleBody() {
        return new LinkedHashMap<>(Map.of(
            "name", "夜跑团", "category", "运动", "description", "晚上一起跑步"));
    }

    private void goldCircleReady() {
        when(tier.effectiveTierCode(UID)).thenReturn("gold");
        when(circleMapper.countByCreator(UID)).thenReturn(0L);
        when(circleMapper.insertUserCircle(any())).thenAnswer(inv -> {
            Map<String, Object> row = inv.getArgument(0);
            row.put("id", 77L);
            return 1;
        });
        when(circleMapper.addOwner(77L, UID)).thenReturn(1);
        when(circleMapper.syncMemberCount(77L)).thenReturn(1);
    }

    @Test
    void silverCannotCreateCircle() {
        when(tier.effectiveTierCode(UID)).thenReturn("silver");
        assertThatThrownBy(() -> circleService.createForUser(UID, circleBody()))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("黄金段位");
    }

    @Test
    void goldCreatesPendingCircleAndBecomesOwner() {
        goldCircleReady();
        Map<String, Object> out = circleService.createForUser(UID, circleBody());
        assertThat(out.get("status")).isEqualTo("pending");
        assertThat(out.get("circleId")).isEqualTo(77L);
        verify(circleMapper).addOwner(77L, UID);
        verify(circleMapper).syncMemberCount(77L);
    }

    @Test
    void secondCircleIsRejectedByQuota() {
        goldCircleReady();
        when(circleMapper.countByCreator(UID)).thenReturn(1L);
        assertThatThrownBy(() -> circleService.createForUser(UID, circleBody()))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("最多创建 1 个圈子");
        verify(circleMapper, never()).insertUserCircle(any());
    }

    @Test
    void circleCreateSwitchOffBlocksEvenGold() {
        goldCircleReady();
        when(flags.isEnabled("circle.create.enabled")).thenReturn(false);
        assertThatThrownBy(() -> circleService.createForUser(UID, circleBody()))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("暂未开放");
    }

    @Test
    void sensitiveCircleNameIsNotPersisted() {
        goldCircleReady();
        when(audit.checkText(anyString(), eq("circle"), any()))
            .thenReturn(new AuditResult(false, false, "内容包含违规词：测试", List.of("测试")));
        assertThatThrownBy(() -> circleService.createForUser(UID, circleBody()))
            .isInstanceOf(IllegalArgumentException.class);
        verify(circleMapper, never()).insertUserCircle(any());
    }
}