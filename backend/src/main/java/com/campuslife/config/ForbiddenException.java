package com.campuslife.config;

// 权限不足：由全局处理器映射为 403，避免与业务冲突（409）混在一起。
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
