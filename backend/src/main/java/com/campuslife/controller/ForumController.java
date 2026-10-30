package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.dto.*;
import com.campuslife.service.ForumService;
import com.campuslife.service.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 论坛 / 搜索相关接口。
 *
 * 身份约定：所有 userId 都经 {@link CurrentUser#resolve} 取，
 * 登录态下永远等于 token 里的用户，客户端上报的 userId 只在匿名读接口里当个性化参数。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ForumController {
    private final ForumService service;
    private final SearchService searchService;
    private final com.campuslife.service.FeatureFlags flags;
    private final com.campuslife.service.RateLimiter limiter;

    @GetMapping("/learn/topics")
    public ApiResponse<Map<String, Object>> learnTopics(@RequestParam(required = false) String category,
                                                        @RequestParam(required = false) Long schoolId,
                                                        @RequestParam(defaultValue = "1") Long userId,
                                                        @RequestParam(required = false) Long beforeId,
                                                        @RequestParam(defaultValue = "10") int size,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(service.learnTopics(category, schoolId, Math.max(CurrentUser.resolve(request, userId), 1), beforeId, size));
    }

    @GetMapping("/learn/counts")
    public ApiResponse<Map<String, Long>> learnCounts(@RequestParam(required = false) Long schoolId,
                                                      @RequestParam(defaultValue = "1") Long userId,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(service.learnCounts(schoolId, Math.max(CurrentUser.resolve(request, userId), 1)));
    }

    @GetMapping("/posts")
    public ApiResponse<Map<String, Object>> posts(@RequestParam(defaultValue = "") String tag,
                                                 @RequestParam(defaultValue = "") String scope,
                                                 @RequestParam(defaultValue = "local") String postScope,
                                                 @RequestParam(defaultValue = "1") Long userId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int size,
                                                 @RequestParam(required = false) Long schoolId,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(service.page(tag, scope, postScope, Math.max(CurrentUser.resolve(request, userId), 1),
            Math.max(page, 1), Math.min(Math.max(size, 1), 50), schoolId));
    }

    // 只搜帖子；保留给既有调用方（搜索结果页已改用 /search/all 全站搜索）
    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> search(@RequestParam String keyword,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int size,
                                                   @RequestParam(defaultValue = "1") Long userId,
                                                   HttpServletRequest request) {
        String text = keyword.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("请输入搜索关键词");
        if (text.length() > 64) throw new IllegalArgumentException("关键词不能超过64字");
        return ApiResponse.ok(service.search(text, Math.max(CurrentUser.resolve(request, userId), 1),
            Math.max(page, 1), Math.min(Math.max(size, 1), 50)));
    }

    // 全站搜索：帖子/圈子/二手/活动/兼职/外卖；type=all 时混排并返回各类计数
    @GetMapping("/search/all")
    public ApiResponse<Map<String, Object>> searchAll(@RequestParam String keyword,
                                                      @RequestParam(defaultValue = "all") String type,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int size,
                                                      @RequestParam(defaultValue = "1") Long userId,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(searchService.search(keyword, Math.max(CurrentUser.resolve(request, userId), 1), type,
            Math.max(page, 1), Math.min(Math.max(size, 1), 50)));
    }

    @PostMapping("/posts")
    public ApiResponse<Object> create(@Valid @RequestBody CreatePostRequest r, HttpServletRequest request) {
        requirePublishEnabled();
        r.setUserId(CurrentUser.resolve(request, r.getUserId()));
        limiter.acquire(com.campuslife.service.RateLimiter.PUBLISH, "post:" + r.getUserId());
        return ApiResponse.ok(service.create(r));
    }

    @GetMapping("/posts/{id}")
    public ApiResponse<ForumPostDTO> detail(@PathVariable Long id,
                                            @RequestParam(defaultValue = "1") Long userId,
                                            HttpServletRequest request) {
        return ApiResponse.ok(service.detail(id, Math.max(CurrentUser.resolve(request, userId), 1)));
    }

    @PostMapping("/posts/{id}/like")
    public ApiResponse<Map<String, Object>> like(@PathVariable Long id, @Valid @RequestBody LikeRequest r, HttpServletRequest request) {
        r.setUserId(CurrentUser.resolve(request, r.getUserId()));
        return ApiResponse.ok(service.toggleLike(id, r.getUserId()));
    }

    @GetMapping("/posts/{id}/comments")
    public ApiResponse<List<?>> comments(@PathVariable Long id,
                                        @RequestParam(required = false) Long userId,
                                        HttpServletRequest request) {
        // 带上当前用户，列表才会返回 liked，重进页面点赞状态不丢。
        // 未登录时不能回落到默认用户，否则会把别人的点赞状态当成自己的。
        Long viewer = CurrentUser.optional(request);
        if (viewer == null) viewer = userId;
        return ApiResponse.ok(service.comments(id, viewer));
    }

    @PostMapping("/posts/{id}/comments")
    public ApiResponse<Object> comment(@PathVariable Long id, @Valid @RequestBody CommentRequest r, HttpServletRequest request) {
        requirePublishEnabled();
        r.setUserId(CurrentUser.resolve(request, r.getUserId()));
        limiter.acquire(com.campuslife.service.RateLimiter.PUBLISH, "comment:" + r.getUserId());
        return ApiResponse.ok(service.addComment(id, r));
    }

    /**
     * 评论点赞 / 取消点赞
     * body: {liked: true/false}
     */
    @PostMapping("/comments/{id}/like")
    public ApiResponse<Map<String, Object>> likeComment(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        Object raw = body == null ? null : body.get("liked");
        boolean liked = raw instanceof Boolean b ? b : !"false".equalsIgnoreCase(String.valueOf(raw));
        // 点赞是写操作，必须拿到真实登录用户；不能用 resolve 的默认值兜底
        Long uid = CurrentUser.optional(request);
        if (uid == null) throw new IllegalStateException("请先登录后再点赞");
        return ApiResponse.ok(service.toggleCommentLike(id, uid, liked));
    }

    @PostMapping("/posts/{id}/view")
    public ApiResponse<Object> view(@PathVariable Long id,
                                    @RequestParam(defaultValue = "1") Long userId,
                                    HttpServletRequest request) {
        service.view(id, Math.max(CurrentUser.resolve(request, userId), 1));
        return ApiResponse.ok(null);
    }

    @PostMapping("/users/{id}/follow")
    public ApiResponse<Map<String, Object>> follow(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "1") Long userId,
                                                   @RequestBody(required = false) Map<String, Long> body,
                                                   HttpServletRequest request) {
        Long claimed = body != null && body.get("userId") != null ? body.get("userId") : userId;
        return ApiResponse.ok(service.toggleFollow(id, CurrentUser.resolve(request, claimed)));
    }

    @GetMapping("/topics/hot")
    public ApiResponse<List<Map<String, Object>>> hot() { return ApiResponse.ok(service.hot()); }

    @GetMapping("/banners")
    public ApiResponse<List<com.campuslife.entity.Banner>> banners(@RequestParam(defaultValue = "discover") String slot) {
        return ApiResponse.ok(service.banners(slot));
    }

    /**
     * 发帖/评论总开关。
     * 关闭后写入入口统一返回可读提示（读帖、点赞不受影响），
     * 用于内容出现集中违规、或需要临时禁言时快速止血。
     */
    private void requirePublishEnabled() {
        if (!flags.isEnabled("post.publish.enabled")) {
            throw new IllegalArgumentException(flags.disabledMessage("post.publish.enabled", "发帖功能维护中，请稍后再试"));
        }
    }
}

