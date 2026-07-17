package com.influora.service.integration;

import com.influora.config.BrandSafetyServiceTokenProperties;
import com.influora.security.SpringJwksKeyService;
import io.jsonwebtoken.Jwts;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Mints the short-lived, service-scoped token Spring presents to influora-ai's
 * {@code POST /internal/brand-safety} (Wave C task C3, the Spring -&gt; Python direction).
 *
 * <p><b>Wave E task E-JWKS update ({@code
 * wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md}):</b> this service now signs
 * with Spring's asymmetric EC/ES256 keypair ({@link SpringJwksKeyService}) instead of the HS256
 * shared secret it originally used. influora-ai's {@code app/auth/service_token.py::verify_token}
 * expects a JWT with {@code iss=<settings.spring_expected_iss>} (default {@code influora-api}),
 * {@code aud=<settings.service_token_aud>} (default {@code influora-internal}), a {@code
 * workspace_id} claim equal to the request body's {@code workspace_id}, and a {@code scope} claim
 * covering {@code SCOPE_SERVICE} ({@code "service"}) — verified against Spring's published {@code
 * GET /.well-known/jwks.json} ({@code ALLOWED_ALGS} in {@code service_token.py} accepts RS256/
 * ES256 on that path, never HS256). The token's {@code kid} header (see {@link
 * SpringJwksKeyService#kid()}) lets Python's {@code PyJWKClient} select the matching public key.
 *
 * <p><b>Why not reuse {@link com.influora.security.JwtService}:</b> that service is purpose-built
 * for end-user sessions (always sets {@code sub=userId}, no {@code scope}/{@code aud} mechanism,
 * still HS256/symmetric) — mixing a service-scoped, no-{@code user_id} token into it, or signing
 * with its secret, would violate this codebase's blast-radius-per-surface discipline (see {@code
 * SecretsStartupValidator}). {@link BrandSafetyServiceTokenProperties} still owns the non-secret
 * shape config (TTL/aud/iss); the signing material itself now lives in {@link
 * SpringJwksKeyService}, shared with {@code StreamTokenService} since both are the SAME
 * Direction-2 keypair (one Spring identity, one published JWKS) — see the ADR's binding condition
 * #2 ("both flows, one fix").
 *
 * <p>TTL is hard-capped at {@link BrandSafetyServiceTokenProperties#MAX_TTL_SECONDS} (60s)
 * regardless of config, mirroring {@code InternalServiceTokenProperties.MAX_TTL_SECONDS}'s
 * ceiling convention for internal service tokens in this codebase (that specific class governs
 * the opposite direction, but the "internal tokens are short-lived" ceiling is the shared
 * principle being mirrored here).
 */
@Service
public class BrandSafetyServiceTokenService {

    public static final String SCOPE_SERVICE = "service";

    private final BrandSafetyServiceTokenProperties props;
    private final SpringJwksKeyService jwksKeyService;

    public BrandSafetyServiceTokenService(
            BrandSafetyServiceTokenProperties props, SpringJwksKeyService jwksKeyService) {
        this.props = props;
        this.jwksKeyService = jwksKeyService;
    }

    /**
     * Mints a token scoped to exactly one workspace, no {@code user_id}/{@code sub} claim (this
     * is a service-to-service call, not acting on behalf of a specific user). Signed with
     * Spring's asymmetric EC/ES256 private key — see {@link SpringJwksKeyService}.
     */
    public String mint(String workspaceId) {
        long ttl = Math.min(props.getTtlSeconds(), BrandSafetyServiceTokenProperties.MAX_TTL_SECONDS);
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttl);
        PrivateKey signingKey = jwksKeyService.signingKey();
        return Jwts.builder()
                .header()
                .keyId(jwksKeyService.kid())
                .and()
                .id(UUID.randomUUID().toString())
                .issuer(props.getIssuer())
                .audience()
                .add(props.getAudience())
                .and()
                .claim("workspace_id", workspaceId)
                .claim("scope", SCOPE_SERVICE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.ES256)
                .compact();
    }
}
