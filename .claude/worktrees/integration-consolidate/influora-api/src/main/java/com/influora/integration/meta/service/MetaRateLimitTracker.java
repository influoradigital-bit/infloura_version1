package com.influora.integration.meta.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tracks the {@code X-Business-Use-Case-Usage} header Meta returns on every Graph API response,
 * per business account (Instagram business account id / Facebook Page id). Example header value:
 * {@code {"<business_id>":[{"type":"instagram","call_count":28,"total_cputime":25,"total_time":45}]}}.
 *
 * <p>In-memory only (per-instance) — matches the per-instance rate-limit note already used for
 * {@code influora.auth.rate-limit} in application.yml; move to a shared store (Redis) if/when
 * horizontally scaled.
 */
@Component
public class MetaRateLimitTracker {

    private static final Logger log = LoggerFactory.getLogger(MetaRateLimitTracker.class);
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private final ConcurrentHashMap<String, RateLimitState> accountLimits = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record RateLimitState(
            int callCount, // 0-100 percentage
            int totalCpuTime, // 0-100 percentage
            int totalTime, // 0-100 percentage
            Instant updatedAt,
            boolean isLimited) {

        public int maxUsage() {
            return Math.max(callCount, Math.max(totalCpuTime, totalTime));
        }
    }

    /** Parses and stores rate-limit state from the {@code X-Business-Use-Case-Usage} header. */
    public void update(String businessAccountId, String headerValue) {
        try {
            JsonNode root = objectMapper.readTree(headerValue);
            int maxCallCount = 0;
            int maxCpuTime = 0;
            int maxTotalTime = 0;

            // Header shape varies: either {"<id>":[{...}]} or a flat array of use-case objects.
            for (JsonNode entry : iterableUsageEntries(root)) {
                maxCallCount = Math.max(maxCallCount, entry.path("call_count").asInt(0));
                maxCpuTime = Math.max(maxCpuTime, entry.path("total_cputime").asInt(0));
                maxTotalTime = Math.max(maxTotalTime, entry.path("total_time").asInt(0));
            }

            accountLimits.put(
                    businessAccountId,
                    new RateLimitState(maxCallCount, maxCpuTime, maxTotalTime, Instant.now(), false));
        } catch (Exception e) {
            // Never fail the request on a header-parsing problem.
            log.warn("Failed to parse Meta rate-limit header for account {}: {}", businessAccountId, e.getMessage());
        }
    }

    private static Iterable<JsonNode> iterableUsageEntries(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        java.util.List<JsonNode> entries = new java.util.ArrayList<>();
        root.fields().forEachRemaining(entry -> entry.getValue().forEach(entries::add));
        return entries;
    }

    /** Returns the current max usage percentage (0-100) for an account; 0 if unknown or stale. */
    public int getCurrentUsage(String businessAccountId) {
        RateLimitState state = accountLimits.get(businessAccountId);
        if (state == null) {
            return 0;
        }
        if (state.updatedAt().isBefore(Instant.now().minus(STALE_AFTER))) {
            return 0;
        }
        return state.maxUsage();
    }

    /** Marks an account as fully rate-limited after Meta returns HTTP 429. */
    public void markLimited(String businessAccountId) {
        accountLimits.put(businessAccountId, new RateLimitState(100, 100, 100, Instant.now(), true));
    }

    public void resetAll() {
        accountLimits.clear();
    }
}
