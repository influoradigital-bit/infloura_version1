package com.influora.service.meera;

import com.influora.config.MeeraStreamProperties;
import com.influora.domain.enums.UserType;
import com.influora.security.SpringJwksKeyService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Mints the short-lived, single-use, scoped SSE stream token per Guardrail 2 (§G2) and the API
 * contract's streaming design (02-API-CONTRACT-BRAND.md §4).
 *
 * <p><b>Wave E task E-JWKS update ({@code
 * wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md}):</b> this service now signs
 * with Spring's asymmetric EC/ES256 keypair ({@link SpringJwksKeyService}) instead of the HS256
 * shared secret ({@link MeeraStreamProperties#getSigningSecret()}) it originally used — the ADR's
 * binding condition #2 ("both flows, one fix") requires this token AND {@code
 * BrandSafetyServiceTokenService} to move together, since both are the same Direction-2
 * (Spring-&gt;Python) auth gap and share the SAME published JWKS/keypair (one Spring identity).
 * influora-ai's {@code app/auth/service_token.py::verify_token} verifies this against Spring's
 * {@code GET /.well-known/jwks.json} (RS256/ES256 only — never HS256 on that path); {@link
 * MeeraStreamProperties#getSigningSecret()} is no longer used for signing and is retained only as
 * dead config the next cleanup pass can remove (kept now to avoid a mid-task unrelated deletion).
 *
 * <p>TTL is hard-capped at 60s regardless of config (the contract ceiling) so a misconfigured
 * environment variable cannot silently widen the replay window.
 *
 * <p><b>A5 fix (Wave 3):</b> {@code mint} previously set {@code aud}/{@code sub}/{@code jti} but
 * carried NO {@code iss} and NO {@code scope} claim. influora-ai's {@code
 * app/auth/service_token.py::_decode_and_verify} requires {@code iss} (via {@code
 * options={"require": [..., "iss"]}} and {@code issuer=settings.spring_expected_iss}), and {@code
 * verify_token} separately requires a {@code scope} claim present in {@code
 * ENDPOINT_SCOPES["chat"]} (that route accepts {@code SCOPE_SERVICE} ("service") OR {@code
 * SCOPE_CHAT_STREAM} ("chat:stream") — this is the scoped stream token, so it must carry {@code
 * SCOPE_CHAT_STREAM}). Without both claims every minted stream token was rejected 401 by Python
 * before this fix, silently breaking the browser's direct SSE connection. {@link #ISSUER} mirrors
 * {@code settings.spring_expected_iss}'s default ({@code SPRING_JWT_ISSUER} env, default {@code
 * "influora-api"}) — the SAME issuer value {@code BrandSafetyServiceTokenProperties} already uses,
 * since both directions share one Spring identity / one published JWKS (ADR binding condition #2).
 *
 * <p><b>T-MEERA-CREATOR-PHASE-A fix round 1 (BLOCKING):</b> {@code mint} now also writes a {@code
 * userType} claim ({@link #USER_TYPE_CLAIM}, value {@link UserType#name()}). influora-ai's {@code
 * app/auth/audience.py::derive_audience} reads the Meera audience (BRAND vs CREATOR -- which
 * persona, which Block B, which tool set, whether the DPDP consent gate and the per-creator
 * monthly cap apply) from the VERIFIED stream token's claims ONLY. It previously fell back to the
 * on-behalf JWT's {@code userType}, which Python cannot verify (HS256, Spring-only secret) and
 * which a creator could simply omit from the body to be downgraded to the brand persona with the
 * brand tool set. A stream token WITHOUT this claim is now refused 403 {@code audience_unverified}
 * by {@code app/routes/chat.py}, so the claim is mandatory here, not optional.
 */
@Service
public class StreamTokenService {

    private static final long MAX_TTL_SECONDS = 60;
    public static final String STREAM_AUDIENCE = "meera-stream";

    /** Must equal influora-ai's {@code SPRING_JWT_ISSUER} env default ({@code service_token.py}'s
     * {@code settings.spring_expected_iss}, default {@code "influora-api"}) — see class javadoc. */
    public static final String ISSUER = "influora-api";

    /** Must equal influora-ai's {@code SCOPE_CHAT_STREAM} ({@code service_token.py}, {@code "chat:stream"}). */
    public static final String SCOPE_CHAT_STREAM = "chat:stream";

    /**
     * Must equal one of influora-ai's {@code AUDIENCE_CLAIM_KEYS} ({@code app/auth/audience.py}) --
     * the same claim name {@code OnBehalfTokenService} already uses, so both per-turn tokens agree.
     */
    public static final String USER_TYPE_CLAIM = "userType";

    private final MeeraStreamProperties props;
    private final SpringJwksKeyService jwksKeyService;

    public StreamTokenService(MeeraStreamProperties props, SpringJwksKeyService jwksKeyService) {
        this.props = props;
        this.jwksKeyService = jwksKeyService;
    }

    /**
     * Mints a token scoped to exactly one workspace + conversation + message. Single-use is
     * enforced by the caller binding the token to {@code messageId} and Python not accepting
     * replays for an already-streamed message (tracked server-side per the contract) — this
     * method only mints; it does not track consumption. Signed with Spring's asymmetric EC/ES256
     * private key — see {@link SpringJwksKeyService}.
     *
     * @param userType the caller's verified principal type, minted as the {@link #USER_TYPE_CLAIM}
     *     claim; influora-ai derives the Meera audience from it (see class javadoc). Never null --
     *     there is no safe default, because a missing claim is exactly the downgrade this closes.
     */
    public String mint(
            String workspaceId, String conversationId, String messageId, String userId, UserType userType) {
        Objects.requireNonNull(userType, "userType is required on a stream token (audience claim)");
        long ttl = Math.min(props.getStreamTokenTtlSeconds(), MAX_TTL_SECONDS);
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttl);
        PrivateKey signingKey = jwksKeyService.signingKey();
        return Jwts.builder()
                .header()
                .keyId(jwksKeyService.kid())
                .and()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .subject(userId)
                .audience()
                .add(STREAM_AUDIENCE)
                .and()
                .claim("workspaceId", workspaceId)
                .claim("conversationId", conversationId)
                .claim("messageId", messageId)
                .claim("scope", SCOPE_CHAT_STREAM)
                .claim(USER_TYPE_CLAIM, userType.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.ES256)
                .compact();
    }

    /**
     * Verifies a stream token (used server-side only if Spring ever needs to validate one).
     * Verifies against Spring's OWN public key ({@link SpringJwksKeyService}) — Spring
     * self-verifying its own asymmetric signature, not a trust boundary against Python.
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .requireAudience(STREAM_AUDIENCE)
                .verifyWith(jwksKeyService.publicKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
