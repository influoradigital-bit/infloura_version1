package com.influora.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window rate limiter for the unauthenticated auth surface (Kabir audit B3): login, register,
 * OTP send/verify, forgot/reset-password and refresh are all enumeration / brute-force / abuse
 * targets and today have no throttle.
 *
 * <p>Also covers the Meta OAuth connect surface (Kabir Meta OAuth Phase 1 review, P1 #3):
 * {@code GET /meta/oauth/authorize} and {@code GET /meta/oauth/callback} are both auth-required but
 * public-facing-shaped — {@code /callback} in particular triggers two outbound Meta token-exchange
 * HTTP calls per hit, making it a real abuse/cost vector for a hammering authenticated client.
 *
 * <p>Keyed by client IP + coarse endpoint bucket. In-memory and therefore <b>per-instance</b> — this
 * is a correct single-node defense and a meaningful speed bump behind a single load balancer, but a
 * horizontally-scaled deploy MUST move this to a shared store (Redis/bucket4j) or enforce it at the
 * edge (WAF / API gateway) so the limit is global. Documented, not silently assumed. Escrow/payout
 * mutations, once those endpoints exist, must be added to {@link #bucketFor} with a stricter limit.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String CTX = "/api/v1";

    @Value("${influora.auth.rate-limit.enabled:true}")
    private boolean enabled;

    /** Requests per window for sensitive credential endpoints (login/register/reset). */
    @Value("${influora.auth.rate-limit.sensitive-per-window:10}")
    private int sensitiveLimit;

    /** Requests per window for the OTP/email surface (send/verify). */
    @Value("${influora.auth.rate-limit.otp-per-window:5}")
    private int otpLimit;

    /** Requests per window for token refresh. */
    @Value("${influora.auth.rate-limit.refresh-per-window:30}")
    private int refreshLimit;

    /** Requests per window for the Meta OAuth connect surface (authorize + callback combined). */
    @Value("${influora.meta.oauth-rate-limit-per-window:20}")
    private int metaOAuthLimit;

    @Value("${influora.auth.rate-limit.window-seconds:60}")
    private long windowSeconds;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!enabled || !isThrottledMethod(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String bucket = bucketFor(request);
        if (bucket == null) {
            chain.doFilter(request, response);
            return;
        }

        int limit = limitFor(bucket);
        String key = clientIp(request) + "|" + bucket;
        long nowSeconds = System.currentTimeMillis() / 1000L;
        Window window =
                windows.compute(
                        key,
                        (k, existing) -> {
                            if (existing == null || nowSeconds - existing.startSecond >= windowSeconds) {
                                return new Window(nowSeconds);
                            }
                            return existing;
                        });

        int used = window.count.incrementAndGet();
        int remaining = Math.max(0, limit - used);
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));

        if (used > limit) {
            long retryAfter = Math.max(1, windowSeconds - (nowSeconds - window.startSecond));
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter()
                    .write(
                            "{\"success\":false,\"error\":{\"code\":\"RATE_LIMITED\","
                                    + "\"message\":\"Too many requests. Please try again shortly.\"}}");
            return;
        }

        chain.doFilter(request, response);
    }

    /** POST covers the auth surface; GET is needed for the Meta OAuth connect surface. */
    private static boolean isThrottledMethod(String method) {
        return "POST".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method);
    }

    /** Returns the rate-limit bucket for the request path, or null if the path is not throttled. */
    private String bucketFor(HttpServletRequest request) {
        String path = stripContext(request.getRequestURI());
        boolean isGet = "GET".equalsIgnoreCase(request.getMethod());

        if (isGet) {
            if (path.equals("/meta/oauth/authorize") || path.equals("/meta/oauth/callback")) {
                return "meta-oauth";
            }
            return null;
        }

        if (path.equals("/auth/brand/send-email-otp") || path.equals("/auth/brand/verify-email")) {
            return "otp";
        }
        if (path.equals("/auth/refresh")) {
            return "refresh";
        }
        if (path.startsWith("/auth/")) {
            // login, brand/login, brand/register, forgot-password, reset-password, logout
            return "sensitive";
        }
        return null;
    }

    private int limitFor(String bucket) {
        return switch (bucket) {
            case "otp" -> otpLimit;
            case "refresh" -> refreshLimit;
            case "meta-oauth" -> metaOAuthLimit;
            default -> sensitiveLimit;
        };
    }

    private static String stripContext(String uri) {
        if (uri.startsWith(CTX)) {
            return uri.substring(CTX.length());
        }
        return uri;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static final class Window {
        final long startSecond;
        final AtomicInteger count = new AtomicInteger(0);

        Window(long startSecond) {
            this.startSecond = startSecond;
        }
    }
}
