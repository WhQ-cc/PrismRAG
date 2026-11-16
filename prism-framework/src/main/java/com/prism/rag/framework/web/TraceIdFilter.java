package com.prism.rag.framework.web;

import com.prism.rag.framework.context.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 链路 ID 过滤器。
 *
 * <p>在请求最外层生成 traceId 并放入 {@link TraceContext}，
 * 请求结束时清理（线程池里的线程会被复用，不清理会串台）。
 * 同时把 traceId 写进响应头，前端可以展示给用户用于报障。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 支持上游网关透传 traceId，便于全链路串联
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = TraceContext.newTraceId();
        }
        TraceContext.setTraceId(traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 必须在 finally 里清理：Tomcat 线程会被复用
            TraceContext.clear();
        }
    }
}
