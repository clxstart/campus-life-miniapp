package com.campuslife.controller;

import com.campuslife.config.AdminInterceptor;
import com.campuslife.config.ForbiddenException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.entity.Building;
import com.campuslife.mapper.BuildingMapper;
import com.campuslife.mapper.DispatchMapper;
import com.campuslife.service.DispatchService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端配送调度：楼栋责任区、异常队列、改派/接管。
 *
 * 权限：super 可管全校；school 只能管自己学校（学校范围从登录态取，不接受前端传入）。
 */
@RestController
@RequestMapping("/api/admin/dispatch")
@RequiredArgsConstructor
public class DispatchAdminController {

    private final DispatchService dispatch;
    private final BuildingMapper buildings;
    private final DispatchMapper mapper;

    // ---------- 异常待处理队列 ----------

    @GetMapping("/queue")
    public ApiResponse<List<Map<String, Object>>> queue(HttpServletRequest request) {
        requireManager(request);
        return ApiResponse.ok(dispatch.queue(scopeSchool(request)));
    }

    // ---------- 改派 / 接管 ----------

    @PostMapping("/orders/{id}/assign")
    public ApiResponse<Map<String, Object>> assign(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        requireManager(request);
        if (body == null) throw new IllegalArgumentException("请选择负责配送的骑手");
        Long riderId = number(body.get("riderId"));
        String action = text(body.get("action"));
        String reason = text(body.get("reason"));
        // countPay 默认 true：默认整笔配送费归实际配送人（方案 §7.2）
        boolean countPay = body.get("countPay") == null || Boolean.parseBoolean(String.valueOf(body.get("countPay")));
        return ApiResponse.ok(dispatch.assign(id, riderId, action, reason, adminId(request), countPay));
    }

    @GetMapping("/orders/{id}/log")
    public ApiResponse<List<Map<String, Object>>> log(@PathVariable Long id, HttpServletRequest request) {
        requireManager(request);
        return ApiResponse.ok(dispatch.log(id));
    }

    /** 补派：把历史上已接单但没有责任骑手的订单再派一次。 */
    @PostMapping("/redispatch")
    public ApiResponse<Map<String, Object>> redispatch(HttpServletRequest request) {
        requireManager(request);
        int assigned = dispatch.dispatchAwaiting();
        return ApiResponse.ok(Map.of("assigned", assigned));
    }

    // ---------- 楼栋责任区 ----------

    @GetMapping("/buildings")
    public ApiResponse<List<Building>> listBuildings(HttpServletRequest request) {
        requireManager(request);
        Long schoolId = scopeSchool(request);
        if (schoolId != null) return ApiResponse.ok(buildings.listBySchool(schoolId));
        return ApiResponse.ok(buildings.selectList(null));
    }

    @PostMapping("/buildings")
    public ApiResponse<Building> createBuilding(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireManager(request);
        Building b = build(body, request);
        buildings.insert(b);
        return ApiResponse.ok(b);
    }

    @PutMapping("/buildings/{id}")
    public ApiResponse<Building> updateBuilding(@PathVariable Long id,
                                                @RequestBody Map<String, Object> body,
                                                HttpServletRequest request) {
        requireManager(request);
        Building existing = buildings.byId(id);
        if (existing == null) throw new IllegalArgumentException("楼栋不存在");
        Long school = scopeSchool(request);
        if (school != null && !school.equals(existing.getSchoolId())) throw new ForbiddenException("只能管理本校楼栋");
        Building b = build(body, request);
        b.setId(id);
        b.setSchoolId(existing.getSchoolId());
        buildings.updateById(b);
        return ApiResponse.ok(buildings.byId(id));
    }

    @DeleteMapping("/buildings/{id}")
    public ApiResponse<Map<String, Object>> deleteBuilding(@PathVariable Long id, HttpServletRequest request) {
        requireManager(request);
        Building existing = buildings.byId(id);
        if (existing == null) throw new IllegalArgumentException("楼栋不存在");
        Long school = scopeSchool(request);
        if (school != null && !school.equals(existing.getSchoolId())) throw new ForbiddenException("只能管理本校楼栋");
        // 软删：保留历史订单里的 building_id 引用
        Building patch = new Building();
        patch.setId(id);
        patch.setStatus(0);
        buildings.updateById(patch);
        return ApiResponse.ok(Map.of("id", id, "status", 0));
    }

    private Building build(Map<String, Object> body, HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("请填写楼栋信息");
        String name = text(body.get("name")).trim();
        if (name.isEmpty() || name.length() > 64) throw new IllegalArgumentException("请填写1至64字的楼栋名称");
        Long schoolId = scopeSchool(request);
        if (schoolId == null) schoolId = number(body.get("schoolId"));
        if (schoolId == null || schoolId < 1) throw new IllegalArgumentException("请选择所属学校");
        Building b = new Building();
        b.setSchoolId(schoolId);
        b.setName(name);
        String keywords = text(body.get("matchKeywords")).trim();
        b.setMatchKeywords(keywords.length() > 255 ? keywords.substring(0, 255) : keywords);
        Long riderId = number(body.get("defaultRiderId"));
        b.setDefaultRiderId(riderId == null || riderId < 1 ? null : riderId);
        b.setStatus(1);
        return b;
    }

    // ---------- 鉴权 helper ----------

    private static void requireManager(HttpServletRequest request) {
        String role = role(request);
        if (!"super".equals(role) && !"school".equals(role)) throw new ForbiddenException("仅校级管理员可管理配送");
    }

    /** super 返回 null（表示不限学校），school 返回自己的学校。 */
    private static Long scopeSchool(HttpServletRequest request) {
        if ("super".equals(role(request))) return null;
        Object v = request.getAttribute(AdminInterceptor.ADMIN_SCHOOL);
        Long id = v instanceof Number n ? n.longValue() : null;
        if (id == null) throw new ForbiddenException("当前账号未绑定学校");
        return id;
    }

    private static String role(HttpServletRequest request) { return String.valueOf(request.getAttribute(AdminInterceptor.ADMIN_ROLE)); }
    private static Long adminId(HttpServletRequest request) { return (Long) request.getAttribute(AdminInterceptor.ADMIN_ID); }
    private static String text(Object v) { return v == null ? "" : String.valueOf(v); }
    private static Long number(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return null;
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }
}
