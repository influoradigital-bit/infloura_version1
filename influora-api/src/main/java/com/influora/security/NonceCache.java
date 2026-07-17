package com.influora.security;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Replay-protection store for the internal request signature (nonce component of
 * {@code InternalRequestVerifier}). A seen nonce is rejected for its TTL window (60s, matching
 * the signature timestamp skew tolerance) — a captured-and-replayed signed internal call cannot
 * be re-accepted within that window.
 *
 * <p>In-memory and therefore <b>per-instance</b>, same documented caveat as
 * {@link AuthRateLimitFilter}: correct on a single node, but a horizontally-scaled deployment
 * must move this to a shared store (Redis) so replay protection is global, not per-instance.
 * Not silently assumed — flagged here exactly like the rate limiter is.
 */
@Component
public class NonceCache {

    private static final long TTL_SECONDS = 60;

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    /** Returns true and records the nonce if it has not been seen within the TTL window; false if it's a replay. */
    public boolean tryConsume(String nonce) {
        Instant now = Instant.now();
        evictExpired(now);
        Instant previous = seen.putIfAbsent(nonce, now);
        return previous == null;
    }

    private void evictExpired(Instant now) {
        // Opportunistic sweep — bounded cost, avoids an unbounded map under sustained traffic.
        seen.entrySet().removeIf(e -> now.getEpochSecond() - e.getValue().getEpochSecond() > TTL_SECONDS);
    }
}
