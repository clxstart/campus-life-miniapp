package com.campuslife.config;

import com.campuslife.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> valid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldError() != null
            ? e.getBindingResult().getFieldError().getDefaultMessage() : "参数错误";
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, msg, null));
    }

    // 权限不足：403（与参数错误 400 区分开，前端据此跳登录或提示无权限）
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Object>> forbidden(ForbiddenException e) {
        return ResponseEntity.status(403).body(new ApiResponse<>(403, e.getMessage() == null ? "无权限" : e.getMessage(), null));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Object>> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(401).body(new ApiResponse<>(401, e.getMessage() == null ? "请先登录" : e.getMessage(), null));
    }

    // 请求过于频繁：429（上传频率限制等）
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiResponse<Object>> tooMany(TooManyRequestsException e) {
        return ResponseEntity.status(429).body(new ApiResponse<>(429, e.getMessage() == null ? "请求过于频繁" : e.getMessage(), null));
    }

    // 业务冲突：409（如重复报名、状态不允许）
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(new ApiResponse<>(409, e.getMessage() == null ? "操作冲突" : e.getMessage(), null));    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Object>> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, e.getMessage() == null ? "参数错误" : e.getMessage(), null));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "图片不能超过 5MB", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> error(Exception e) {
        return ResponseEntity.internalServerError()
            .body(new ApiResponse<>(500, e.getMessage() == null ? "服务器错误" : e.getMessage(), null));
    }
}
