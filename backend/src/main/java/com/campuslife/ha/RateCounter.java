package com.campuslife.ha;

/**
 * 固定窗口计数器（finding-10）：单机用进程内实现，集群用 Redis，所有节点共享同一计数。
 */
public interface RateCounter {
    /** 本窗口内的计数与窗口剩余毫秒 */
    record Hit(long count, long remainMs) {}

    /** 计数 +1 并返回当前窗口内的累计次数；共享存储不可用时抛 {@link RateCounterUnavailableException} */
    Hit increment(String key, long windowMs);

    /** 只读当前计数（运维/测试用） */
    long current(String key, long windowMs);

    class RateCounterUnavailableException extends RuntimeException {
        public RateCounterUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
