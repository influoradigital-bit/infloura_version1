package com.influora.service.meera;

import com.influora.config.MeeraStreamProperties;
import com.influora.security.SpringJwksKeyService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;
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
 */
@Service
public class StreamTokenService {

    private static final long MAX_TTL_SECONDS = 60;
    public static final String STREAM_AUDIENCE = "meera-stream";

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
     */
    public String mint(String workspaceId, String conversationId, String messageId, String userId) {
        long ttl = Math.min(props.getStreamTokenTtlSeconds(), MAX_TTL_SECONDS);
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttl);
        PrivateKey signingKey = jwksKeyService.signingKey();
        return Jwts.builder()
                .header()
                .keyId(jwksKeyService.kid())
                .and()
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
