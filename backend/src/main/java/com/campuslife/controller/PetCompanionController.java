package com.campuslife.controller;

import com.campuslife.dto.ApiResponse;
import com.campuslife.service.PetCompanionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 校园搭子：外观 / 衣柜 / 派遣
 *
 * 路径统一在 /api/pet 下，与既有 /api/pet（喂食/摸头/签到/改名）共存。
 */
@RestController
@RequestMapping("/api/pet")
@RequiredArgsConstructor
public class PetCompanionController {

    private final PetCompanionService service;

    private static Long userId(Map<String, Object> body) {
        if (body == null || body.get("userId") == null) throw new IllegalArgumentException("用户不能为空");
        Object v = body.get("userId");
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("用户编号无效"); }
    }

    private static String code(Map<String, Object> body) {
        if (body == null) return null;
        Object v = body.get("code");
        return v == null ? null : String.valueOf(v);
    }

    // ==================== 外观 ====================

    @GetMapping("/appearance")
    public ApiResponse<Object> appearance(@RequestParam Long userId) {
        return ApiResponse.ok(service.appearance(userId));
    }

    @PutMapping("/appearance")
    public ApiResponse<Object> saveAppearance(@RequestBody Map<String, Object> body) {
        Long uid = userId(body);
        return ApiResponse.ok(service.saveAppearance(uid, body));
    }

    @PostMapping("/appearance/reset")
    public ApiResponse<Object> resetAppearance(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.resetAppearance(userId(body)));
    }

    // ==================== 衣柜 ====================

    @GetMapping("/wardrobe")
    public ApiResponse<Object> wardrobe(@RequestParam Long userId) {
        return ApiResponse.ok(service.wardrobe(userId));
    }

    @PostMapping("/wardrobe/purchase")
    public ApiResponse<Object> purchase(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.purchase(userId(body), code(body)));
    }

    @PostMapping("/wardrobe/equip")
    public ApiResponse<Object> equip(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.equip(userId(body), code(body)));
    }

    @PostMapping("/wardrobe/unequip")
    public ApiResponse<Object> unequip(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.unequip(userId(body)));
    }

    // ==================== 派遣 ====================

    @GetMapping("/dispatch")
    public ApiResponse<Object> dispatch(@RequestParam Long userId) {
        return ApiResponse.ok(service.dispatchStatus(userId));
    }

    @PostMapping("/dispatch/start")
    public ApiResponse<Object> startDispatch(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.startDispatch(userId(body), code(body)));
    }

    @PostMapping("/dispatch/claim")
    public ApiResponse<Object> claimDispatch(@RequestBody Map<String, Object> body) {
        if (body == null || body.get("dispatchId") == null) throw new IllegalArgumentException("派遣编号不能为空");
        Object v = body.get("dispatchId");
        Long dispatchId = v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
        return ApiResponse.ok(service.claimDispatch(userId(body), dispatchId));
    }
}
