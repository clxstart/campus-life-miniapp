package com.campuslife.controller;
import com.campuslife.dto.*; import com.campuslife.service.AuthService; import lombok.RequiredArgsConstructor; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequestMapping("/api/auth") @RequiredArgsConstructor public class AuthController { private final AuthService service;
 // 微信登录：前端 wx.login() 拿 code 换取 token
 @PostMapping("/login") public ApiResponse<Map<String,Object>> login(@RequestBody Map<String,String> body){return ApiResponse.ok(service.login(body==null?null:body.get("code")));}
 // 开发登录：未配置微信 AppID 时启用，便于 touristappid 下跑通流程
 @PostMapping("/dev-login") public ApiResponse<Map<String,Object>> devLogin(@RequestBody(required=false) Map<String,Long> body){return ApiResponse.ok(service.devLogin(body==null?null:body.get("userId")));}
 @GetMapping("/status") public ApiResponse<Map<String,Object>> status(){return ApiResponse.ok(Map.of("devLogin",service.devLoginEnabled()));}
}
