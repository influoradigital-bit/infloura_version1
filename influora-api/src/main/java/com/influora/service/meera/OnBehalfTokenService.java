package com.influora.service.meera;

import com.influora.domain.enums.UserType;
import com.influora.security.SpringJwksKeyService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Mints and verifies the short-lived, per-turn on-behalf JWT — the SECURITY FIX #1 credential
 * from {@code docs/security/meera-onbehalf-auth-security-design.md} §2 (Kabir's design, §9
 * finding #1). Replaces the previous mechanism of forwarding the user's full-lifetime public-API
 * access token (read out of {@code localStorage.getItem('brand_token')} — {@code
 * MeeraChatPanel.tsx}, pre-fix) as the {@code onbehalf_jwt} Python forwards to Spring's {@code
 * /internal/meera/*} tool routes ({@code OnBehalfAuthResolver}).
 *
 * <p>Minted alongside the SSE {@link StreamTokenService} token, at the same call site
 * ({@code MeeraSessionService#doSendTurn}), and signed with the SAME asymmetric ES256 JWKS
 * keypair ({@link SpringJwksKeyService}) — deliberately NOT the public-API access-token secret
 * ({@code JwtService}/{@code JwtProperties}), so a compromise of one credential family can never
 * forge the other, and so a full access token structurally cannot verify against this service's
 * {@link #ONBEHALF_AUDIENCE}-gated parser (different key material, different algorithm family —
 * see {@link #verify}).
 *
 * <p>Claims (design doc §2 table): {@code sub}=userId, {@code workspaceId}, {@code userType},
 * {@code conversationId}, {@code turnId} (the USER message id — binds the token to one turn),
 * {@code scope} (space-delimited allowed tool names — currently the READ-ONLY set only, see
 * {@link #SCOPE_READ_ONLY}; enforcing {@code scope} against the tool tier at the resolver is a
 * separate, not-yet-implemented item — checklist item 9 in the design doc), {@code aud}={@link
 * #ONBEHALF_AUDIENCE} (distinct from {@link StreamTokenService#STREAM_AUDIENCE} and from the
 * public access-token audience), {@code iss}={@link #ISSUER}, {@code iat}/{@code exp} (TTL
 * hard-capped at {@link #MAX_TTL_SECONDS}), {@code jti} (random — single-use tracking key; the
 * consumed-{@code jti} replay store itself is a separate fix, design doc §2/§5, NOT implemented
 * here).
 */
@Service
public class OnBehalfTokenService {

    private static final long MAX_TTL_SECONDS = 120;

    /** Distinct from {@link StreamTokenService#STREAM_AUDIENCE} and the public access-token audience. */
    public static final String ONBEHALF_AUDIENCE = "meera-onbehalf";

    /** Mirrors {@link StreamTokenService#ISSUER} — both directions share one Spring identity / one published JWKS. */
    public static final String ISSUER = StreamTokenService.ISSUER;

    /**
     * Read-only tool scope — the only tools an on-behalf token authorizes today ({@code
     * show_creators}, {@code calculate_budget}), space-delimited to match OAuth-style scope
     * convention. Extend this (or add a per-tier scope) when a write tool (e.g. {@code
     * create_campaign}) is enabled per the design doc's phased rollout (§8) — that also requires
     * the resolver to actually enforce {@code scope} against the called tool, which this pass does
     * NOT add (see class javadoc).
     */
    public static final String SCOPE_READ_ONLY = "show_creators calculate_budget";

    private final SpringJwksKeyService jwksKeyService;

    public OnBehalfTokenService(SpringJwksKeyService jwksKeyService) {
        this.jwksKeyService = jwksKeyService;
    }

    /**
     * Mints a token scoped to exactly one user + workspace + conversation + turn. {@code turnId}
     * is the USER message id minted in the same {@code doSendTurn} call as the stream token (same
     * value {@link StreamTokenService#mint} receives as {@code messageId}).
     */
    public String mint(
            String workspaceId,
            String conversationId,
            String turnId,
            String userId,
            UserType userType) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(MAX_TTL_SECONDS);
        PrivateKey signingKey = jwksKeyService.signingKey();
        return Jwts.builder()
                .header()
                .keyId(jwksKeyService.kid())
                .and()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .subject(userId)
                .audience()
                .add(ONBEHALF_AUDIENCE)
                .and()
                .claim("workspaceId", workspaceId)
                .claim("userType", userType.name())
                .claim("conversationId", conversationId)
                .claim("turnId", turnId)
                .claim("scope", SCOPE_READ_ONLY)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.ES256)
                .compact();
    }

    /**
     * Verifies an on-behalf token: signature (against Spring's own ES256 JWKS public key —
     * {@link SpringJwksKeyService}, never the public-API {@code JwtService} parser), {@code
     * iss}=={@link #ISSUER}, {@code aud}=={@link #ONBEHALF_AUDIENCE}, and {@code exp} (checked by
     * the parser against wall-clock time by default). Throws {@code io.jsonwebtoken.JwtException}
     * (or a subtype) on any failure — callers (see {@code OnBehalfAuthResolver}) must treat every
     * exception here as "reject, do not execute."
     *
     * <p>A full public-API access token presented here is rejected structurally, not by a
     * denylist: it is signed with an HMAC secret ({@code JwtService#accessKey}), not this
     * service's EC keypair, so {@code verifyWith(jwksKeyService.publicKey())} fails on the
     * algorithm/key mismatch before any claim is even inspected; and even in the hypothetical
     * where a signature check were bypassed, access tokens carry no {@code aud} claim at all, so
     * {@code requireAudience} would still reject them.
     */
    public Claims verify(String token) {
        return Jwts.parser()
                .requireIssuer(ISSUER)
                .requireAudience(ONBEHALF_AUDIENCE)
                .verifyWith(jwksKeyService.publicKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
