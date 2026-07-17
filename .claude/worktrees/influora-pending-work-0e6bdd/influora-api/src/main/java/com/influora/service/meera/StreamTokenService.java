package com.influora.service.meera;

import com.influora.config.MeeraStreamProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Mints the short-lived, single-use, scoped SSE stream token per Guardrail 2 (§G2) and the API
 * contract's streaming design (02-API-CONTRACT-BRAND.md §4). Signed with a key distinct from the
 * user-JWT access/refresh keys (Guardrail 6 — secrets segregated by blast radius): see
 * {@link MeeraStreamProperties}, never {@code JwtProperties}.
 *
 * <p>TTL is hard-capped at 60s regardless of config (the contract ceiling) so a misconfigured
 * environment variable cannot silently widen the replay window.
 */
@Service
public class StreamTokenService {

    private static final long MAX_TTL_SECONDS = 60;
    public static final String STREAM_AUDIENCE = "meera-stream";

    private final MeeraStreamProperties props;

    public StreamTokenService(MeeraStreamProperties props) {
        this.props = props;
    }

    /**
     * Mints a token scoped to exactly one workspace + conversation + message. Single-use is
     * enforced by the caller binding the token to {@code messageId} and Python not accepting
     * replays for an already-streamed message (tracked server-side per the contract) — this
     * method only mints; it does not track consumption.
     */
    public String mint(String workspaceId, String conversationId, String messageId, String userId) {
        long ttl = Math.min(props.getStreamTokenTtlSeconds(), MAX_TTL_SECONDS);
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttl);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId)
                .audience()
                .add(STREAM_AUDIENCE)
                .and()
                .claim("workspaceId", workspaceId)
                .claim("conversationId", conversationId)
                .claim("messageId", messageId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey())
                .compact();
    }

    /** Verifies a stream token (used server-side only if Spring ever needs to validate one). */
    public Claims parse(String token) {
        return Jwts.parser()
                .requireAudience(STREAM_AUDIENCE)
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(props.getSigningSecret().getBytes(StandardCharsets.UTF_8));
    }
}
