package com.campuslife.controller;

import com.campuslife.dto.ApiResponse;
import com.campuslife.config.AuthInterceptor;
import com.campuslife.config.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import com.campuslife.service.RiderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class StudentOrderController {
    private final RiderService riderService;

    @GetMapping("/{id}")
    public ApiResponse<Map<String,Object>> detail(@PathVariable Long id, @RequestParam Long userId, HttpServletRequest request) {
        Object authenticatedId = request.getAttribute(AuthInterceptor.USER_ID);
        if (authenticatedId instanceof Long idFromToken && !idFromToken.equals(userId)) throw new ForbiddenException("不能查看其他用户的订单");
        return ApiResponse.ok(riderService.studentOrder(id, userId));
    }
}
