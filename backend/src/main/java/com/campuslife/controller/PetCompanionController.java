package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.config.UnauthorizedException;
import com.campuslife.dto.ApiResponse;
import com.campuslife.service.PetCompanionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 校园搭子：外观 / 衣柜 / 派遣
 *
 * 路径统一在 /api/pet 下，与既有 /api/pet（喂食/摸头/签到/改名）共存。
 * /api/pet/** 由 AuthInterceptor 强制校验 token，因此这里的 userId 一律取登录身份；
 * body 里的 userId 只作为 enforce=false 时的回落值。
 */
@RestController
@RequestMapping("/api/pet")
@RequiredArgsConstructor
public class PetCompanionController {

    private final PetCompanionService service;

    private static Long me(HttpServletRequest request, Object claimed) {
        Long parsed = null;
        if (claimed instanceof Number n) parsed = n.longValue();
        else if (claimed != null) {
            try { parsed = Long.parseLong(String.valueOf(claimed).trim()); }
            catch (NumberFormatException ignored) { parsed = null; }
        }
        Long id = CurrentUser.resolve(request, parsed, null);
        if (id == null || id < 1) throw new UnauthorizedException("请先登录");
        return id;
    }

    private static String code(Map<String, Object> body) {
        if (body == null) return null;
        Object v = body.get("code");
        return v == null ? null : String.valueOf(v);
    }

    // ==================== 外观 ====================

    @GetMapping("/appearance")
    public ApiResponse<Object> appearance(@RequestParam(required = false) Long userId, HttpServletRequest request) {
        return ApiResponse.ok(service.appearance(me(request, userId)));
    }

    @PutMapping("/appearance")
    public ApiResponse<Object> saveAppearance(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponse.ok(service.saveAppearance(me(request, body.get("userId")), body));
    }

    @PostMapping("/appearance/reset")
    public ApiResponse<Object> resetAppearance(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponse.ok(service.resetAppearance(me(request, body.get("userId"))));
    }

    // ==================== 衣柜 ====================

    @GetMapping("/wardrobe")
    public ApiResponse<Object> wardrobe(@RequestParam(required = false) Long userId, HttpServletRequest request) {
        return ApiResponse.ok(service.wardrobe(me(request, userId)));
    }

    @PostMapping("/wardrobe/purchase")
    public ApiResponse<Object> purchase(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponse.ok(service.purchase(me(request, body.get("userId")), code(body)));
    }

    @PostMapping("/wardrobe/equip")
    public ApiResponse<Object> equip(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponse.ok(service.equip(me(request, body.get("userId")), code(body)));
    }

    @PostMapping("/wardrobe/unequip")
    public ApiResponse<Object> unequip(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponse.ok(service.unequip(me(request, body.get("userId"))));
    }

    // ==================== 派遣 ====================

    @GetMapping("/dispatch")
    public ApiResponse<Object> dispatch(@RequestParam(required = false) Long userId, HttpServletRequest request) {
        return ApiResponse.ok(service.dispatchStatus(me(request, userId)));
    }

    @PostMapping("/dispatch/start")
    public ApiResponse<Object> startDispatch(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResponse.ok(service.startDispatch(me(request, body.get("userId")), code(body)));
    }

    @PostMapping("/dispatch/claim")
    public ApiResponse<Object> claimDispatch(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (body == null || body.get("dispatchId") == null) throw new IllegalArgumentException("派遣编号不能为空");
        Object v = body.get("dispatchId");
        Long dispatchId = v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
        return ApiResponse.ok(service.claimDispatch(me(request, body.get("userId")), dispatchId));
    }
}
