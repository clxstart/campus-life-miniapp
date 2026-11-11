package com.campuslife.config;

/** 共享依赖（Redis/对象存储）暂时不可用：返回 503，与限流 429、权限 403 区分，便于监控告警。 */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) { super(message); }
    public ServiceUnavailableException(String message, Throwable cause) { super(message, cause); }
}
