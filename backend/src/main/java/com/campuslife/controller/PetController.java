package com.campuslife.controller;

import com.campuslife.dto.ApiResponse;
import com.campuslife.service.PetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController @RequestMapping("/api/pet") @RequiredArgsConstructor public class PetController {
    private final PetService service;

    @GetMapping public ApiResponse<Map<String, Object>> pet(@RequestParam Long userId) {
        return ApiResponse.ok(service.petOf(userId));
    }

    @PostMapping("/feed") public ApiResponse<Map<String, Object>> feed(@RequestBody Map<String, Long> body) {
        return ApiResponse.ok(service.interact(body.get("userId"), "feed"));
    }

    @PostMapping("/touch") public ApiResponse<Map<String, Object>> touch(@RequestBody Map<String, Long> body) {
        return ApiResponse.ok(service.interact(body.get("userId"), "touch"));
    }

    @PostMapping("/checkin") public ApiResponse<Map<String, Object>> checkin(@RequestBody Map<String, Long> body) {
        return ApiResponse.ok(service.interact(body.get("userId"), "checkin"));
    }

    @PutMapping("/name") public ApiResponse<Object> rename(@RequestBody Map<String, Object> body) {
        Long userId = ((Number) body.get("userId")).longValue();
        service.rename(userId, (String) body.get("name"));
        return ApiResponse.ok(null);
    }
}
