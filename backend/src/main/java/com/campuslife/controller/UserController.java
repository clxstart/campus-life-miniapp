package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.*;
import com.campuslife.entity.UserAddress;
import com.campuslife.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {
    private final UserService service;
    private final com.campuslife.service.UserStatusGuard statusGuard;
    private final com.campuslife.service.UserBlockService blockService;

    /** 只能操作自己的数据：路径里的 {id} 必须与登录身份一致。 */
    private static Long requireSelf(Long pathId, HttpServletRequest request) {
        Long authed = CurrentUser.optional(request);
        if (authed == null) throw new ForbiddenException("请先登录");
        if (pathId != null && !pathId.equals(authed)) throw new ForbiddenException("只能查看自己的数据");
        return authed;
    }

    // 公开：看别人的主页资料
    @GetMapping("/users/{id}/profile")
    public ApiResponse<Map<String, Object>> profile(@PathVariable Long id) {
        return ApiResponse.ok(service.profile(Math.max(id, 1)));
    }

    // 改自己的资料
    @PostMapping("/users/{id}/profile")
    public ApiResponse<Map<String, Object>> updateProfile(@PathVariable Long id,
                                                          @Valid @RequestBody ProfileUpdateRequest r,
                                                          HttpServletRequest request) {
        return ApiResponse.ok(service.updateProfile(requireSelf(id, request), r));
    }

    // 公开：看某人发布过的帖子
    @GetMapping("/users/{id}/posts")
    public ApiResponse<Map<String, Object>> posts(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "1") Long viewerId,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "10") int size,
                                                  HttpServletRequest request) {
        return ApiResponse.ok(service.postsByAuthor(Math.max(id, 1), Math.max(CurrentUser.resolve(request, viewerId), 1),
            Math.max(page, 1), Math.min(Math.max(size, 1), 50)));
    }

    // 公开：主页统计数字
    @GetMapping("/users/{id}/stats")
    public ApiResponse<Map<String, Object>> stats(@PathVariable Long id) {
        return ApiResponse.ok(service.stats(Math.max(id, 1)));
    }

    // ---- 以下都是「我的数据」，必须登录且只能是自己 ----
    @GetMapping("/users/{id}/favorites")
    public ApiResponse<List<Map<String, Object>>> favorites(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(service.favorites(requireSelf(id, request)));
    }

    @GetMapping("/users/{id}/views")
    public ApiResponse<List<Map<String, Object>>> views(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(service.views(requireSelf(id, request)));
    }

    @PostMapping("/users/{id}/favorites")
    public ApiResponse<Map<String, Object>> toggleFavorite(@PathVariable Long id,
                                                           @Valid @RequestBody FavoriteToggleRequest r,
                                                           HttpServletRequest request) {
        return ApiResponse.ok(service.toggleFavorite(requireSelf(id, request), r.getPostId()));
    }

    @GetMapping("/users/{id}/coupons")
    public ApiResponse<List<Map<String, Object>>> coupons(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(service.coupons(requireSelf(id, request)));
    }

    @GetMapping("/users/{id}/addresses")
    public ApiResponse<List<Map<String, Object>>> addresses(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(service.addresses(requireSelf(id, request)));
    }

    @PostMapping("/users/{id}/addresses")
    public ApiResponse<UserAddress> addAddress(@PathVariable Long id,
                                               @Valid @RequestBody AddressAddRequest r,
                                               HttpServletRequest request) {
        return ApiResponse.ok(service.addAddress(requireSelf(id, request), r));
    }

    /**
     * 拉黑 / 取消拉黑某个用户（切换语义）。
     *
     * 路径里的 {id} 是**被拉黑的人**，不是自己；操作人取 token 身份。
     * 双向不可见：拉黑后双方都看不到对方的帖子和评论，也无法互发私信。
     */
    @PostMapping("/users/{id}/block")
    public ApiResponse<Map<String, Object>> toggleBlock(@PathVariable Long id, HttpServletRequest request) {
        Long self = CurrentUser.optional(request);
        if (self == null) throw new ForbiddenException("请先登录");
        return ApiResponse.ok(blockService.toggle(self, id));
    }

    /** 我拉黑的人。只能看自己的，避免被人拿去看谁屏蔽了自己。 */
    @GetMapping("/users/{id}/blocks")
    public ApiResponse<java.util.List<Map<String, Object>>> myBlocks(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(blockService.myBlocks(requireSelf(id, request)));
    }

    /**
     * 注销账号。
     *
     * 只能注销自己：先要求登录，再校验路径 {id} 与 token 身份一致（requireSelf）。
     * 注销后旧 token 立即失效（AuthInterceptor 会查用户状态），需要重新登录。
     */
    @DeleteMapping("/users/{id}/account")
    public ApiResponse<Map<String, Object>> deleteAccount(@PathVariable Long id, HttpServletRequest request) {
        Map<String, Object> out = service.deleteAccount(requireSelf(id, request));
        // 注销后立刻失效缓存，下一次请求就会拿到 401
        if (statusGuard != null) statusGuard.invalidate(id);
        return ApiResponse.ok(out);
    }

    @PostMapping("/feedback")
    public ApiResponse<Map<String, Object>> feedback(@Valid @RequestBody FeedbackRequest r, HttpServletRequest request) {
        r.setUserId(CurrentUser.resolve(request, r.getUserId()));
        return ApiResponse.ok(service.feedback(r.getUserId(), r.getContent()));
    }
}


