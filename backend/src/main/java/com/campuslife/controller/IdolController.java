package com.campuslife.controller;
import com.campuslife.dto.*; import com.campuslife.entity.IdolContestant; import com.campuslife.service.IdolService; import jakarta.validation.Valid; import lombok.RequiredArgsConstructor; import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api") @RequiredArgsConstructor public class IdolController { private final IdolService service;
 @GetMapping("/idol/activity") public ApiResponse<Map<String,Object>> activity(){return ApiResponse.ok(service.activity());}
 @GetMapping("/idol/contestants") public ApiResponse<List<Map<String,Object>>> contestants(@RequestParam(defaultValue="2") Integer gender,@RequestParam(defaultValue="1") Long userId){return ApiResponse.ok(service.contestants(gender,Math.max(userId,1)));}
 @PostMapping("/idol/contestants/{id}/vote") public ApiResponse<Map<String,Object>> vote(@PathVariable Long id,@Valid @RequestBody LikeRequest r){return ApiResponse.ok(service.vote(id,r.getUserId()));}
 @PostMapping("/idol/contestants") public ApiResponse<IdolContestant> join(@Valid @RequestBody IdolJoinRequest r){return ApiResponse.ok(service.join(r));}
}
