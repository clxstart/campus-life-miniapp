package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.AdminAuthService;
import com.campuslife.service.AdminModuleService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// 管理端业务接口。所有写操作都会留操作日志。
@RestController @RequestMapping("/api/admin") @RequiredArgsConstructor
public class AdminModuleController {
    private final AdminModuleService service;
    private final AdminAuthService auth;

    // ================= 看板 =================
    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(HttpServletRequest req) {
        return ApiResponse.ok(service.dashboard(scope(req)));
    }

    // ================= 学校（仅超管）=================
    @GetMapping("/schools")
    public ApiResponse<List<Map<String, Object>>> schools(HttpServletRequest req) {
        requireSuper(req);
        return ApiResponse.ok(service.schools());
    }

    @PostMapping("/schools")
    public ApiResponse<Map<String, Object>> createSchool(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        requireSuper(req);
        Map<String, Object> out = service.createSchool(body);
        log(req, "create", "school", String.valueOf(out.get("id")), "新增学校 " + body.get("name"));
        return ApiResponse.ok(out);
    }

    @PutMapping("/schools/{id}")
    public ApiResponse<Object> updateSchool(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        requireSuper(req);
        service.updateSchool(id, body);
        log(req, "update", "school", String.valueOf(id), "编辑学校 " + body.get("name"));
        return ApiResponse.ok(null);
    }

    // ================= 校园评选：选手 =================
    // 全平台活动，不按校隔离；仅超管可操作（校管没有评选权限）。

    @GetMapping("/idol-contestants")
    public ApiResponse<List<Map<String, Object>>> idolContestants(@RequestParam(required = false) Integer gender,
                                                                  HttpServletRequest req) {
        requireSuper(req);
        return ApiResponse.ok(service.idolContestants(gender));
    }

    @PostMapping("/idol-contestants")
    public ApiResponse<Map<String, Object>> createIdolContestant(@RequestBody Map<String, Object> body,
                                                                 HttpServletRequest req) {
        requireSuper(req);
        Map<String, Object> out = service.createIdolContestant(body);
        log(req, "create", "idolContestant", String.valueOf(out.get("id")), "新增选手 " + body.get("name"));
        return ApiResponse.ok(out);
    }

    @PutMapping("/idol-contestants/{id}")
    public ApiResponse<Object> updateIdolContestant(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                    HttpServletRequest req) {
        requireSuper(req);
        service.updateIdolContestant(id, body);
        log(req, "update", "idolContestant", String.valueOf(id), "编辑选手 " + body.get("name"));
        return ApiResponse.ok(null);
    }

    @PostMapping("/idol-contestants/{id}/status")
    public ApiResponse<Object> setIdolContestantStatus(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                       HttpServletRequest req) {
        requireSuper(req);
        int status = intOr(body.get("status"), 1);
        service.setIdolContestantStatus(id, status);
        log(req, "update", "idolContestant", String.valueOf(id), "设置选手状态 " + status);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/idol-contestants/{id}")
    public ApiResponse<Object> deleteIdolContestant(@PathVariable Long id, HttpServletRequest req) {
        requireSuper(req);
        service.deleteIdolContestant(id);
        log(req, "delete", "idolContestant", String.valueOf(id), "删除选手");
        return ApiResponse.ok(null);
    }

    // ================= 外卖：店铺 + 菜品 =================
    // 小程序有完整外卖功能，管理端原来没有页面。

    @GetMapping("/shops")
    public ApiResponse<Map<String, Object>> shops(@RequestParam(defaultValue = "") String keyword,
                                                @RequestParam(required = false) Long schoolId,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  HttpServletRequest req) {
        return ApiResponse.ok(service.shops(effectiveSchool(req, schoolId), keyword, page, size));
    }

    @PostMapping("/shops")
    public ApiResponse<Map<String, Object>> createShop(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> out = service.createShop(scope(req), body);
        log(req, "create", "shop", String.valueOf(out.get("id")), "新增店铺 " + body.get("name"));
        return ApiResponse.ok(out);
    }

    @PutMapping("/shops/{id}")
    public ApiResponse<Object> updateShop(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        service.updateShop(scope(req), id, body);
        log(req, "update", "shop", String.valueOf(id), "编辑店铺 " + body.get("name"));
        return ApiResponse.ok(null);
    }

    @PostMapping("/shops/{id}/status")
    public ApiResponse<Object> setShopStatus(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        int status = intOr(body.get("status"), 1);
        service.setShopStatus(scope(req), id, status);
        log(req, "update", "shop", String.valueOf(id), "设置店铺状态 " + status);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/shops/{id}")
    public ApiResponse<Object> deleteShop(@PathVariable Long id, HttpServletRequest req) {
        service.deleteShop(scope(req), id);
        log(req, "delete", "shop", String.valueOf(id), "删除店铺");
        return ApiResponse.ok(null);
    }

    @GetMapping("/shops/{id}/goods")
    public ApiResponse<List<Map<String, Object>>> shopGoods(@PathVariable Long id, HttpServletRequest req) {
        return ApiResponse.ok(service.shopGoods(scope(req), id));
    }

    @PostMapping("/shop-goods")
    public ApiResponse<Map<String, Object>> createShopGoods(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> out = service.createShopGoods(scope(req), body);
        log(req, "create", "shopGoods", String.valueOf(out.get("id")), "新增菜品 " + body.get("name"));
        return ApiResponse.ok(out);
    }

    @PutMapping("/shop-goods/{id}")
    public ApiResponse<Object> updateShopGoods(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        service.updateShopGoods(scope(req), id, body);
        log(req, "update", "shopGoods", String.valueOf(id), "编辑菜品 " + body.get("name"));
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/shop-goods/{id}")
    public ApiResponse<Object> deleteShopGoods(@PathVariable Long id, HttpServletRequest req) {
        service.deleteShopGoods(scope(req), id);
        log(req, "delete", "shopGoods", String.valueOf(id), "删除菜品");
        return ApiResponse.ok(null);
    }

    // ================= 每校自定义地点 =================
    // 发帖/发兼职选的地点。校管只能动本校；超管必须传 schoolId。

    @GetMapping("/school-locations")
    public ApiResponse<List<Map<String, Object>>> schoolLocations(@RequestParam(required = false) Long schoolId,
                                                                  HttpServletRequest req) {
        return ApiResponse.ok(service.schoolLocations(scope(req), schoolId));
    }

    @PostMapping("/school-locations")
    public ApiResponse<Map<String, Object>> createSchoolLocation(@RequestBody Map<String, Object> body,
                                                                 HttpServletRequest req) {
        Map<String, Object> out = service.createSchoolLocation(scope(req), body);
        log(req, "create", "schoolLocation", String.valueOf(out.get("id")), "新增地点 " + body.get("name"));
        return ApiResponse.ok(out);
    }

    @PutMapping("/school-locations/{id}")
    public ApiResponse<Object> updateSchoolLocation(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                    HttpServletRequest req) {
        service.updateSchoolLocation(scope(req), id, body);
        log(req, "update", "schoolLocation", String.valueOf(id), "编辑地点 " + body.get("name"));
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/school-locations/{id}")
    public ApiResponse<Object> deleteSchoolLocation(@PathVariable Long id, HttpServletRequest req) {
        service.deleteSchoolLocation(scope(req), id);
        log(req, "delete", "schoolLocation", String.valueOf(id), "删除地点");
        return ApiResponse.ok(null);
    }

    // ================= 用户 =================
    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> users(@RequestParam(defaultValue = "") String keyword,
                                                @RequestParam(required = false) Long schoolId,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  HttpServletRequest req) {
        return ApiResponse.ok(service.users(effectiveSchool(req, schoolId), keyword, page, size));
    }

    // ================= 内容 =================
    @GetMapping("/posts")
    public ApiResponse<Map<String, Object>> posts(@RequestParam(defaultValue = "") String keyword,
                                                @RequestParam(required = false) Long schoolId,
                                                  @RequestParam(defaultValue = "false") boolean offlineOnly,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  HttpServletRequest req) {
        return ApiResponse.ok(service.posts(effectiveSchool(req, schoolId), keyword, offlineOnly, page, size));
    }

    // 下架 / 恢复
    @PostMapping("/posts/{id}/status")
    public ApiResponse<Object> setPostStatus(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        int status = intOr(body.get("status"), 0);
        service.setPostStatus(scope(req), id, status);
        log(req, status == 0 ? "offline" : "online", "post", String.valueOf(id), status == 0 ? "下架帖子" : "恢复帖子");
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/posts/{id}")
    public ApiResponse<Object> deletePost(@PathVariable Long id, HttpServletRequest req) {
        service.deletePost(scope(req), id);
        log(req, "delete", "post", String.valueOf(id), "删除帖子");
        return ApiResponse.ok(null);
    }

    // 编辑帖子内容（标题/正文/配图/标签）
    @PutMapping("/posts/{id}")
    public ApiResponse<Object> updatePost(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        service.updatePostContent(scope(req), id, body);
        log(req, "update", "post", String.valueOf(id), "编辑帖子 " + body.get("title"));
        return ApiResponse.ok(null);
    }

    // 设置用户头像
    @PutMapping("/users/{id}/avatar")
    public ApiResponse<Object> setUserAvatar(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        service.setUserAvatar(scope(req), id, body);
        log(req, "update", "user", String.valueOf(id), "更新用户头像");
        return ApiResponse.ok(null);
    }

    // ================= 圈子 =================
    @GetMapping("/circles")
    public ApiResponse<Map<String, Object>> circles(@RequestParam(defaultValue = "") String keyword,
                                                  @RequestParam(required = false) Long schoolId,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    HttpServletRequest req) {
        return ApiResponse.ok(service.circles(effectiveSchool(req, schoolId), keyword, page, size));
    }

    @PostMapping("/circles")
    public ApiResponse<Map<String, Object>> createCircle(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        // 超管建圈必须指定学校，否则圈子会成为跨校无主数据
        Long schoolId = scope(req);
        if (schoolId == null) {
            schoolId = body.get("schoolId") == null ? null : longOr(body.get("schoolId"), 0);
            if (schoolId == null || schoolId < 1) throw new IllegalArgumentException("请指定所属学校");
        }
        Map<String, Object> out = service.createCircle(schoolId, body);
        log(req, "create", "circle", String.valueOf(out.get("id")), "新建圈子 " + body.get("name"));
        return ApiResponse.ok(out);
    }

    @PutMapping("/circles/{id}")
    public ApiResponse<Object> updateCircle(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        service.updateCircle(scope(req), id, body);
        log(req, "update", "circle", String.valueOf(id), "编辑圈子 " + body.get("name"));
        return ApiResponse.ok(null);
    }

    // ================= 学期 =================
    @GetMapping("/terms")
    public ApiResponse<List<Map<String, Object>>> terms(@RequestParam(required = false) Long schoolId,
                                                        HttpServletRequest req) {
        return ApiResponse.ok(service.terms(effectiveSchool(req, schoolId)));
    }

    @PostMapping("/terms")
    public ApiResponse<Map<String, Object>> createTerm(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> out = service.createTerm(scope(req), body);
        log(req, "create", "term", String.valueOf(out.get("id")), "新增学期 " + body.get("termLabel"));
        return ApiResponse.ok(out);
    }

    @PutMapping("/terms/{id}")
    public ApiResponse<Object> updateTerm(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        service.updateTerm(scope(req), id, body);
        log(req, "update", "term", String.valueOf(id), "编辑学期 " + body.get("termLabel"));
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/terms/{id}")
    public ApiResponse<Object> deleteTerm(@PathVariable Long id, HttpServletRequest req) {
        service.deleteTerm(scope(req), id);
        log(req, "delete", "term", String.valueOf(id), "删除学期");
        return ApiResponse.ok(null);
    }

    // ================= 校历事件：已随「校历」功能下线移除 =================


    // ================= 业务：兼职 / 活动 / 二手 =================
    @GetMapping("/jobs")
    public ApiResponse<Map<String, Object>> jobs(@RequestParam(defaultValue = "") String keyword,
                                               @RequestParam(required = false) Long schoolId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 HttpServletRequest req) {
        return ApiResponse.ok(service.jobs(effectiveSchool(req, schoolId), keyword, page, size));
    }

    @DeleteMapping("/jobs/{id}")
    public ApiResponse<Object> deleteJob(@PathVariable Long id, HttpServletRequest req) {
        service.deleteJob(id);
        log(req, "delete", "job", String.valueOf(id), "删除兼职");
        return ApiResponse.ok(null);
    }

    @GetMapping("/activities")
    public ApiResponse<Map<String, Object>> activities(@RequestParam(defaultValue = "") String keyword,
                                                     @RequestParam(required = false) Long schoolId,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       HttpServletRequest req) {
        return ApiResponse.ok(service.activities(effectiveSchool(req, schoolId), keyword, page, size));
    }

    @PostMapping("/activities/{id}/status")
    public ApiResponse<Object> setActivityStatus(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        int status = intOr(body.get("status"), 1);
        service.setActivityStatus(id, status);
        log(req, "update", "activity", String.valueOf(id), "设置活动状态 " + status);
        return ApiResponse.ok(null);
    }

    // 编辑活动内容（标题/海报/时间/地点/描述）
    @PutMapping("/activities/{id}")
    public ApiResponse<Object> updateActivity(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        service.updateActivityContent(scope(req), id, body);
        log(req, "update", "activity", String.valueOf(id), "编辑活动 " + body.get("title"));
        return ApiResponse.ok(null);
    }

    @GetMapping("/goods")
    public ApiResponse<Map<String, Object>> goods(@RequestParam(defaultValue = "") String keyword,
                                                @RequestParam(required = false) Long schoolId,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  HttpServletRequest req) {
        return ApiResponse.ok(service.goods(effectiveSchool(req, schoolId), keyword, page, size));
    }

    @PostMapping("/goods/{id}/status")
    public ApiResponse<Object> setGoodsStatus(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        int status = intOr(body.get("status"), 1);
        service.setGoodsStatus(id, status);
        log(req, "update", "goods", String.valueOf(id), "设置商品状态 " + status);
        return ApiResponse.ok(null);
    }

    // 编辑二手商品内容（标题/价格/分类/成色/图片/描述）
    @PutMapping("/goods/{id}")
    public ApiResponse<Object> updateGoods(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        service.updateGoodsContent(scope(req), id, body);
        log(req, "update", "goods", String.valueOf(id), "编辑商品 " + body.get("title"));
        return ApiResponse.ok(null);
    }

    // ================= 运营：Banner =================
    @GetMapping("/banners")
    public ApiResponse<Map<String, Object>> banners(@RequestParam(required = false) Long schoolId,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    HttpServletRequest req) {
        return ApiResponse.ok(service.banners(effectiveSchool(req, schoolId), page, size));
    }

    @PostMapping("/banners")
    public ApiResponse<Map<String, Object>> createBanner(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> out = service.createBanner(scope(req), body);
        log(req, "create", "banner", String.valueOf(out.get("id")), "新增 Banner " + body.get("title"));
        return ApiResponse.ok(out);
    }

    @PutMapping("/banners/{id}")
    public ApiResponse<Object> updateBanner(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        service.updateBanner(id, body);
        log(req, "update", "banner", String.valueOf(id), "编辑 Banner " + body.get("title"));
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/banners/{id}")
    public ApiResponse<Object> deleteBanner(@PathVariable Long id, HttpServletRequest req) {
        service.deleteBanner(id);
        log(req, "delete", "banner", String.valueOf(id), "删除 Banner");
        return ApiResponse.ok(null);
    }

    // ================= 内部工具 =================
    /**
     * 当前账号能管哪些学校的数据。
     * 校管固定为自己绑定的学校；超管返回 null，表示「不限定」，由调用方决定是否再细分。
     */
    private static Long scope(HttpServletRequest req) {
        Object role = req.getAttribute(AdminInterceptor.ADMIN_ROLE);
        if ("super".equals(String.valueOf(role))) return null;  // 超管看全部
        Object schoolId = req.getAttribute(AdminInterceptor.ADMIN_SCHOOL);
        if (!(schoolId instanceof Long id) || id < 1) throw new ForbiddenException("当前账号未绑定学校，请联系超级管理员");
        return id;
    }

    /**
     * 多校 SaaS 的取数口径：校管只能用自己学校，超管可以用前端传来的 schoolId 切到某一所学校。
     *
     * 这是个平台方管理多所学校的产品，超管如果只能看「所有学校混在一起」的列表，
     * 学期、圈子、商品这些东西就分不清是哪所学校的。所以凡是有学校维度的列表接口，
     * 都改成走这里：前端传了 schoolId 就按它筛，没传才退回「全部」。
     */
    private static Long effectiveSchool(HttpServletRequest req, Long requestedSchoolId) {
        Long own = scope(req);
        if (own != null) {
            // 校管：只能看本校，忽略前端传值（防止改 URL 越权看别的学校）
            if (requestedSchoolId != null && !own.equals(requestedSchoolId)) {
                throw new ForbiddenException("只能查看本校数据");
            }
            return own;
        }
        return (requestedSchoolId != null && requestedSchoolId > 0) ? requestedSchoolId : null;
    }

    private static void requireSuper(HttpServletRequest req) {
        if (!"super".equals(String.valueOf(req.getAttribute(AdminInterceptor.ADMIN_ROLE)))) {
            throw new ForbiddenException("仅超级管理员可操作");
        }
    }

    private void log(HttpServletRequest req, String action, String targetType, String targetId, String detail) {
        auth.log((Long) req.getAttribute(AdminInterceptor.ADMIN_ID),
                 String.valueOf(req.getAttribute(AdminInterceptor.ADMIN_NAME)),
                 action, targetType, targetId, detail, ip(req));
    }

    private static String ip(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
    }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value).trim()); } catch (Exception e) { return fallback; }
    }

    private static long longOr(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(value).trim()); } catch (Exception e) { return fallback; }
    }
}

