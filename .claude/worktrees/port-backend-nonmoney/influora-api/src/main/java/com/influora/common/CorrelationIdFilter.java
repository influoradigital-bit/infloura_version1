package com.influora.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * H-28 (INFLUORA-PRODUCTION-READINESS-AUDIT-2026-07-14.md) — the API had no correlation id /
 * request logging: a production 500 gave the client "An unexpected error occurred" and the
 * server log gave nothing to grep for (see {@code GlobalExceptionHandler#handleGeneric}, which
 * had no logger at all before this pass).
 *
 * <p>Stamps every request with a correlation id (reuses an inbound {@code X-Correlation-Id} /
 * {@code X-Request-Id} header when a caller — e.g. an LB, or a browser session that wants to
 * thread its own trace — already set one; otherwise mints a random UUID), pushes it into SLF4J's
 * MDC so every log line emitted while handling the request carries it (see
 * {@code logback-spring.xml}'s {@code %X{correlationId}} pattern), and echoes it back on the
 * response so a client can report the id from a support ticket. Runs first in the chain (before
 * the rate limiter / JWT filters) so auth-rejection and rate-limit log lines are correlated too.
 *
 * <p>MDC is thread-local and this app runs {@code @Async} listener work on a separate thread pool
 * (see {@code NotificationListener}, {@code @EnableAsync} on {@code InfluoraApiApplication}) — the
 * correlation id intentionally does NOT propagate onto that async thread; it identifies the
 * synchronous request/response cycle, not everything the request eventually triggers.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    public static final String MDC_KEY = "correlationId";
    public static final String REQUEST_HEADER = "X-Correlation-Id";
    public static final String RESPONSE_HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request);
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(RESPONSE_HEADER, correlationId);
        long startedAt = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.info(
                    "{} {} -> {} ({}ms)",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    System.currentTimeMillis() - startedAt);
            // Always cleared, even on exception — MDC is thread-local and threads are pooled
            // (Tomcat request threads are reused), so a leaked key would bleed into an unrelated
            // later request handled by the same worker thread.
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String inbound = request.getHeader(REQUEST_HEADER);
        if (inbound != null && !inbound.isBlank() && inbound.length() <= 128) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }
}
