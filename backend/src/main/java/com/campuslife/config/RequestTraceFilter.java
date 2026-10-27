package com.campuslife.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 给每个请求打一个 traceId，并把耗时打进日志。
 *
 * 解决什么问题：
 *   之前用户说「我下单失败了」，日志里只有零散几行、也分不清是哪一次请求，
 *   排查只能靠猜。现在每个请求有独立的 traceId，同时通过响应头 X-Request-Id 返回给前端，
 *   用户报一个 id，就能 grep 出这一次请求打过的全部日志。
 *
 * 为什么用 OncePerRequestFilter：转发/异步场景下保证同一个请求只生成一次 id。
 * 为什么 finally 里 MDC.clear()：线程池复用线程，不清会串到下一个请求上。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    /** 超过这个耗时打 WARN，方便以后直接 grep 慢请求。 */
    private static final long SLOW_MS = 300L;

    private static final String TRACE_ID = "traceId";
    private static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 静态资源不打日志也不生成 id：它们量大、无排查价值
        String uri = request.getRequestURI();
        if (uri != null && (uri.startsWith("/uploads/") || uri.startsWith("/actuator/"))) {
            chain.doFilter(request, response);
            return;
        }

        // 上游（网关/Nginx）已经给了 id 就沿用，便于跨层串联
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }

        MDC.put(TRACE_ID, traceId);
        // 暴露给响应头，前端出错时可以把 id 一起报上来
        response.setHeader(HEADER, traceId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            String method = request.getMethod();
            int status = response.getStatus();
            String query = request.getQueryString();
            String target = query == null ? uri : uri + "?" + query;
            if (costMs >= SLOW_MS) {
                log.warn("慢请求 {} {} -> {} {}ms", method, target, status, costMs);
            } else {
                log.info("{} {} -> {} {}ms", method, target, status, costMs);
            }
            MDC.remove(TRACE_ID);
        }
    }
}
