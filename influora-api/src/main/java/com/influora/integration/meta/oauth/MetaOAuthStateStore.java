package com.influora.integration.meta.oauth;

import com.influora.common.Ulids;
import com.influora.domain.entity.MetaAuthPath;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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

    private record PendingState(String userId, Instant expiresAt, MetaAuthPath authPath) {}

    private final ConcurrentHashMap<String, PendingState> pending = new ConcurrentHashMap<>();

    /** Mints a fresh opaque state token bound to {@code userId}, valid for {@link #STATE_TTL}. */
    public String issue(String userId) {
        return issue(userId, MetaAuthPath.FACEBOOK_LOGIN);
    }

    /**
     * Mints a state token that also remembers WHICH login path started the handshake
     * (T-IGLOGIN-0820). Meta's redirect carries only {@code code} and {@code state}, so without
     * binding the path here the callback cannot tell an Instagram-Login return from a
     * Facebook-Login one — and the two require different token exchanges.
     */
    public String issue(String userId, MetaAuthPath authPath) {
        String state = Ulids.newUlid();
        pending.put(state, new PendingState(userId, Instant.now().plus(STATE_TTL), authPath));
        return state;
    }

    /**
     * Validates and single-use-consumes a state token. Returns {@code true} only if the token
     * exists, has not expired, and was issued for {@code userId}.
     */
    public boolean consume(String state, String userId) {
        return consumePath(state, userId).isPresent();
    }

    /**
     * Validates and single-use-consumes a state token, returning the login path it was issued for.
     * Empty means invalid, expired, already used, or issued for a different user — the caller must
     * treat all four identically and reject.
     */
    public Optional<MetaAuthPath> consumePath(String state, String userId) {
        if (state == null) {
            return Optional.empty();
        }
        PendingState found = pending.remove(state);
        if (found == null) {
            return Optional.empty();
        }
        if (found.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        if (!found.userId().equals(userId)) {
            return Optional.empty();
        }
        return Optional.of(found.authPath());
    }
}
