package com.influora.integration.shopify.oauth;

import com.influora.common.Ulids;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Short-lived, in-memory CSRF-state store for the Shopify OAuth handshake -- mints an opaque state
 * token bound to both the initiating user AND the shop domain they are connecting, validates +
 * single-use-consumes it on callback. Mirrors {@code MetaOAuthStateStore} exactly, with one
 * addition: Shopify's callback carries its own {@code shop} query parameter, and the state token
 * must be validated against the SAME shop the authorize request was issued for -- otherwise a
 * malicious redirect could swap in a different shop domain while reusing a state token minted for
 * a shop the user actually approved, associating the wrong store with the user's workspace.
 *
 * <p>Same per-instance {@code ConcurrentHashMap} pattern as {@code MetaOAuthStateStore}/{@code
 * MetaRateLimitTracker}; move to a shared store (Redis) if/when horizontally scaled, same note as
 * those classes.
 */
@Component
public class ShopifyOAuthStateStore {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private record PendingState(String userId, String shopDomain, Instant expiresAt) {}

    private final ConcurrentHashMap<String, PendingState> pending = new ConcurrentHashMap<>();

    /** Mints a fresh opaque state token bound to {@code userId} and {@code shopDomain}, valid for {@link #STATE_TTL}. */
    public String issue(String userId, String shopDomain) {
        String state = Ulids.newUlid();
        pending.put(state, new PendingState(userId, shopDomain, Instant.now().plus(STATE_TTL)));
        return state;
    }

    /**
     * Validates and single-use-consumes a state token. Returns {@code true} only if the token
     * exists, has not expired, and was issued for both {@code userId} AND {@code shopDomain}.
     */
    public boolean consume(String state, String userId, String shopDomain) {
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
        return found.userId().equals(userId) && found.shopDomain().equalsIgnoreCase(shopDomain);
    }
}
