package com.campuslife.controller;
import com.campuslife.dto.*; import com.campuslife.entity.UserAddress; import com.campuslife.service.UserService; import jakarta.validation.Valid; import lombok.RequiredArgsConstructor; import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api") @RequiredArgsConstructor public class UserController { private final UserService service;
 @GetMapping("/users/{id}/profile") public ApiResponse<Map<String,Object>> profile(@PathVariable Long id){return ApiResponse.ok(service.profile(Math.max(id,1)));}
 @PostMapping("/users/{id}/profile") public ApiResponse<Map<String,Object>> updateProfile(@PathVariable Long id,@Valid @RequestBody ProfileUpdateRequest r){return ApiResponse.ok(service.updateProfile(Math.max(id,1),r));}
 @GetMapping("/users/{id}/posts") public ApiResponse<Map<String,Object>> posts(@PathVariable Long id,@RequestParam(defaultValue="1") Long viewerId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size){return ApiResponse.ok(service.postsByAuthor(Math.max(id,1),Math.max(viewerId,1),Math.max(page,1),Math.min(Math.max(size,1),50)));}
 @GetMapping("/users/{id}/stats") public ApiResponse<Map<String,Object>> stats(@PathVariable Long id){return ApiResponse.ok(service.stats(Math.max(id,1)));}
 @GetMapping("/users/{id}/favorites") public ApiResponse<List<Map<String,Object>>> favorites(@PathVariable Long id){return ApiResponse.ok(service.favorites(Math.max(id,1)));}
 @GetMapping("/users/{id}/views") public ApiResponse<List<Map<String,Object>>> views(@PathVariable Long id){return ApiResponse.ok(service.views(Math.max(id,1)));}
 @PostMapping("/users/{id}/favorites") public ApiResponse<Map<String,Object>> toggleFavorite(@PathVariable Long id,@Valid @RequestBody FavoriteToggleRequest r){return ApiResponse.ok(service.toggleFavorite(Math.max(id,1),r.getPostId()));}
 @GetMapping("/users/{id}/coupons") public ApiResponse<List<Map<String,Object>>> coupons(@PathVariable Long id){return ApiResponse.ok(service.coupons(Math.max(id,1)));}
 @GetMapping("/users/{id}/addresses") public ApiResponse<List<Map<String,Object>>> addresses(@PathVariable Long id){return ApiResponse.ok(service.addresses(Math.max(id,1)));}
 @PostMapping("/users/{id}/addresses") public ApiResponse<UserAddress> addAddress(@PathVariable Long id,@Valid @RequestBody AddressAddRequest r){return ApiResponse.ok(service.addAddress(Math.max(id,1),r));}
 @PostMapping("/feedback") public ApiResponse<Map<String,Object>> feedback(@Valid @RequestBody FeedbackRequest r){return ApiResponse.ok(service.feedback(r.getUserId(),r.getContent()));}
}
