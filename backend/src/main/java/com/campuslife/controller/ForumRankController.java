package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.ForumRankService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 论坛榜单（日榜 / 周榜）用户侧接口。
 *
 * 权限：榜单内容本身公开可见（未登录也能看，这是论坛的展示位）；
 * 只有底部的「我的排名」需要登录 —— 未登录时 me 返回 null，前端显示「登录后看我的排名」。
 *
 * 设计稿第六节测试用例 12 写的是「不带头调 → 401」，但同一份设计稿 2.2 的状态表写的是
 * 「未登录：榜单可看，底部不显示我的排名」。两处冲突，这里取后者：榜单是公开内容，
 * 因为看不了「我的排名」就把整页 401 掉，等于把一个展示页变成登录墙，与设计意图相反。
 */
@RestController
@RequestMapping("/api/forum")
@RequiredArgsConstructor
public class ForumRankController {

    private final ForumRankService service;

    /**
     * @param type daily | weekly，默认 daily（榜单页默认打开日榜）
     */
    @GetMapping("/rank")
    public ApiResponse<Map<String, Object>> rank(@RequestParam(defaultValue = "daily") String type,
                                                 HttpServletRequest request) {
        // 登录态优先；匿名时传 null，服务层不会去查「我的排名」
        Long viewerId = CurrentUser.optional(request);
        return ApiResponse.ok(service.board(type, viewerId));
    }
}