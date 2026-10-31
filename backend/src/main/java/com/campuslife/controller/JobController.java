package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.config.UnauthorizedException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.JobService;
import com.campuslife.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户端兼职接口。
 *
 * 身份一律取 token（{@link CurrentUser#optional}）：**匿名不回落到默认用户**，
 * 写操作没有有效 token 直接 401。列表允许匿名（只做展示），但只用来解析「看哪个学校」，
 * 拿不到就返回全部学校，不会因为匿名而看到别人的个人数据。
 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobs;
    private final RateLimiter limiter;

    /** 列表：传 page 返回分页对象，不传返回扁平数组（兼容小程序老调用）。 */
    @GetMapping
    public ApiResponse<Object> list(@RequestParam(defaultValue = "") String settle,
                                    @RequestParam(defaultValue = "") String keyword,
                                    @RequestParam(defaultValue = "local") String schoolScope,
                                    @RequestParam(required = false) Long schoolId,
                                    @RequestParam(defaultValue = "") String scope,
                                    @RequestParam(required = false) Long userId,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(defaultValue = "20") int size,
                                    HttpServletRequest request) {
        Long self = CurrentUser.optional(request);
        return ApiResponse.ok(jobs.list(settle, keyword, schoolScope, schoolId, self, scope, page, size));
    }

    /** 我发布的 + 我承接的。 */
    @GetMapping("/mine")
    public ApiResponse<Map<String, Object>> mine(HttpServletRequest request) {
        return ApiResponse.ok(jobs.mine(requireUser(request)));
    }

    /**
     * 岗位开关状态（只读，公开）。
     * 前端据此如实显示「校外岗位暂未开放」，不用等一次 403 才知道。
     */
    @GetMapping("/gates")
    public ApiResponse<Map<String, Object>> gates() {
        return ApiResponse.ok(jobs.gates());
    }

    /** 岗位详情（含我的申请/合约状态）。 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(jobs.detail(id, CurrentUser.optional(request)));
    }

    /** 发布岗位：校外 / 替课 / 有偿三道闸门在服务端判定。 */
    @PostMapping
    public ApiResponse<Map<String, Object>> publish(@RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest request) {
        Long self = requireUser(request);
        // 同用户每分钟 10 次：发布是低频动作，限流挡脚本刷屏
        limiter.acquire(RateLimiter.PUBLISH, "job-publish:" + self);
        return ApiResponse.ok(jobs.publish(self, body));
    }

    /** 校内岗位先到先接（并发安全由一条条件 UPDATE 保证）。 */
    @PostMapping("/{id}/take")
    public ApiResponse<Map<String, Object>> take(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(jobs.take(id, requireUser(request)));
    }

    /** 校外岗位报名（重复报名幂等）。 */
    @PostMapping("/{id}/apply")
    public ApiResponse<Map<String, Object>> apply(@PathVariable Long id,
                                                  @RequestBody(required = false) Map<String, Object> body,
                                                  HttpServletRequest request) {
        String message = body == null || body.get("message") == null ? "" : String.valueOf(body.get("message"));
        return ApiResponse.ok(jobs.apply(id, requireUser(request), message));
    }

    // ---------- 校外报名筛选（只有发布者） ----------

    /** 发布者看自己岗位的报名列表。 */
    @GetMapping("/{id}/applications")
    public ApiResponse<Map<String, Object>> applications(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(jobs.applications(id, requireUser(request)));
    }

    /** 发布者录用 / 拒绝某个报名。 */
    @PostMapping("/{id}/applications/{applicationId}/decide")
    public ApiResponse<Map<String, Object>> decide(@PathVariable Long id,
                                                   @PathVariable Long applicationId,
                                                   @RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest request) {
        Map<String, Object> input = body == null ? Map.of() : body;
        boolean approve = input.get("approve") != null && Boolean.parseBoolean(String.valueOf(input.get("approve")));
        String reason = input.get("reason") == null ? "" : String.valueOf(input.get("reason"));
        return ApiResponse.ok(jobs.decide(id, applicationId, requireUser(request), approve, reason));
    }

    // ---------- 履约 ----------

    @GetMapping("/contracts/{id}")
    public ApiResponse<Map<String, Object>> contract(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(jobs.contractDetail(id, requireUser(request)));
    }

    @PostMapping("/contracts/{id}/start")
    public ApiResponse<Map<String, Object>> start(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(jobs.start(id, requireUser(request)));
    }

    @PostMapping("/contracts/{id}/submit-hours")
    public ApiResponse<Map<String, Object>> submitHours(@PathVariable Long id,
                                                        @RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(jobs.submitHours(id, requireUser(request), hoursOf(body)));
    }

    @PostMapping("/contracts/{id}/confirm-hours")
    public ApiResponse<Map<String, Object>> confirmHours(@PathVariable Long id,
                                                         @RequestBody(required = false) Map<String, Object> body,
                                                         HttpServletRequest request) {
        return ApiResponse.ok(jobs.confirmHours(id, requireUser(request), hoursOf(body)));
    }

    @PostMapping("/contracts/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id,
                                                   @RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest request) {
        String reason = body == null || body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        return ApiResponse.ok(jobs.cancel(id, requireUser(request), reason));
    }

    @PostMapping("/contracts/{id}/dispute")
    public ApiResponse<Map<String, Object>> dispute(@PathVariable Long id,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest request) {
        String reason = body == null || body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        return ApiResponse.ok(jobs.dispute(id, requireUser(request), reason));
    }

    private static Object hoursOf(Map<String, Object> body) {
        return body == null ? null : body.get("hours");
    }

    private static Long requireUser(HttpServletRequest request) {
        Long id = CurrentUser.optional(request);
        if (id == null || id < 1) throw new UnauthorizedException("请先登录");
        return id;
    }
}
