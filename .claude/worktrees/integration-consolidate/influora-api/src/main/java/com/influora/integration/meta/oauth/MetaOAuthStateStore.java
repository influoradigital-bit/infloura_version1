package com.influora.integration.meta.oauth;

import com.influora.common.Ulids;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Short-lived, in-memory CSRF-state store for the Meta OAuth handshake — mints an opaque state
 * token bound to the initiating user, and validates + single-use-consumes it on callback. Same
 * per-instance/{@code ConcurrentHashMap} pattern as {@code MetaRateLimitTracker}; move to a shared
 * store (Redis) if/when horizontally scaled, same as noted there.
 */
@Component
public class MetaOAuthStateStore {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private record PendingState(String userId, Instant expiresAt) {}

    private final ConcurrentHashMap<String, PendingState> pending = new ConcurrentHashMap<>();

    /** Mints a fresh opaque state token bound to {@code userId}, valid for {@link #STATE_TTL}. */
    public String issue(String userId) {
        String state = Ulids.newUlid();
        pending.put(state, new PendingState(userId, Instant.now().plus(STATE_TTL)));
        return state;
    }

    /**
     * Validates and single-use-consumes a state token. Returns {@code true} only if the token
     * exists, has not expired, and was issued for {@code userId}.
     */
    public boolean consume(String state, String userId) {
        if (state == null) {
            return false;
        }
        PendingState found = pending.remove(state);
        if (found == null) {
            return false;
        }
        if (found.expiresAt().isBefore(Instant.now())) {
            return false;
        }
        return found.userId().equals(userId);
    }
}
