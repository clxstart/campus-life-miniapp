package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.dto.*;
import com.campuslife.entity.IdolContestant;
import com.campuslife.service.IdolService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class IdolController {
    private final IdolService service;

    @GetMapping("/idol/activity")
    public ApiResponse<Map<String, Object>> activity() { return ApiResponse.ok(service.activity()); }

    @GetMapping("/idol/contestants")
    public ApiResponse<List<Map<String, Object>>> contestants(@RequestParam(defaultValue = "2") Integer gender,
                                                              @RequestParam(defaultValue = "1") Long userId,
                                                              HttpServletRequest request) {
        return ApiResponse.ok(service.contestants(gender, Math.max(CurrentUser.resolve(request, userId), 1)));
    }

    // 投票：每人对同一位选手限投 1 票。投票人取登录身份，不接受客户端指定别人。
    @PostMapping("/idol/contestants/{id}/vote")
    public ApiResponse<Map<String, Object>> vote(@PathVariable Long id, @Valid @RequestBody LikeRequest r, HttpServletRequest request) {
        r.setUserId(CurrentUser.resolve(request, r.getUserId()));
        return ApiResponse.ok(service.vote(id, r.getUserId()));
    }

    @PostMapping("/idol/contestants")
    public ApiResponse<IdolContestant> join(@Valid @RequestBody IdolJoinRequest r, HttpServletRequest request) {
        r.setUserId(CurrentUser.resolve(request, r.getUserId()));
        return ApiResponse.ok(service.join(r));
    }
}
