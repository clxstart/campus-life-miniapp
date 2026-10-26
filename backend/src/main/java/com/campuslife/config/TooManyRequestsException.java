package com.campuslife.config;

/**
 * 请求过于频繁：映射成 HTTP 429。
 *
 * 目前用于上传频率限制 —— 超过窗口内允许次数时抛出，
 * 前端据此提示「稍后再试」而不是当成普通业务错误。
 */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
