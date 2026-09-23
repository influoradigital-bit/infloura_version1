package com.influora.service.integration;

import com.influora.config.BrandSafetyServiceTokenProperties;
import com.influora.security.SpringJwksKeyService;
import io.jsonwebtoken.JwtBuilder;
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

    /**
     * F-audit-A1 — the claim influora-ai's {@code app.auth.audience.derive_audience}
     * ({@code AUDIENCE_CLAIM_KEYS = ("audience", "userType", "user_type")}) reads to decide
     * whether a call is CREATOR- or BRAND-originated. Named to match {@code
     * StreamTokenService#USER_TYPE_CLAIM}/{@code OnBehalfTokenService}'s own {@code "userType"}
     * claim — this token family and that one are read by the same derivation function on the
     * influora-ai side, so they use the same claim name and the same value shape ({@link
     * com.influora.domain.enums.UserType#name()}).
     */
    public static final String USER_TYPE_CLAIM = "userType";

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
     *
     * <p>Byte-for-byte the same token shape this method has always produced — delegates to
     * {@link #mint(String, String)} with a {@code null} {@code userType}, which omits the claim
     * entirely. Every existing caller (this class's four OTHER callers besides {@code
     * MeeraVoiceAiClient} — brand safety, brand voice, trend spark, creator suggestion, analyze
     * site — keeps calling this overload and sees zero change.
     */
    public String mint(String workspaceId) {
        return mint(workspaceId, null);
    }

    /**
     * F-audit-A1 — as {@link #mint(String)}, but additionally carries a {@link #USER_TYPE_CLAIM}
     * naming the audience this ONE call is for, when (and only when) the caller is acting on
     * behalf of a creator rather than a workspace-scoped brand/service call. {@code userType}
     * {@code null} or blank omits the claim entirely, producing the EXACT SAME token {@link
     * #mint(String)} always has — this is what keeps every non-creator caller of this service
     * (which all still call the single-argument overload) byte-for-byte unaffected by this fix.
     *
     * <p>Adding ONLY the audience claim here does not, on its own, make influora-ai's per-creator
     * spend cap and DPDP consent re-check reach the truth: {@code resolve_frame_check_prefs}/
     * {@code resolve_voice_prefs} still need a way to authenticate the Spring context fetch they
     * make once they see CREATOR. This service token is not it (see {@code OnBehalfAuthResolver}
     * — it verifies the on-behalf JWT against a DIFFERENT audience/contract than this token
     * carries). The other half of that fix is {@code CreatorMeeraController} minting a real,
     * creator-scoped {@code OnBehalfTokenService} token per call and {@code MeeraVoiceAiClient}
     * forwarding it as the request's {@code onbehalf_jwt} — see both classes' javadoc.
     */
    public String mint(String workspaceId, String userType) {
        long ttl = Math.min(props.getTtlSeconds(), BrandSafetyServiceTokenProperties.MAX_TTL_SECONDS);
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttl);
        PrivateKey signingKey = jwksKeyService.signingKey();
        JwtBuilder builder =
                Jwts.builder()
                        .header()
                        .keyId(jwksKeyService.kid())
                        .and()
                        .id(UUID.randomUUID().toString())
                        .issuer(props.getIssuer())
                        .audience()
                        .add(props.getAudience())
                        .and()
                        .claim("workspace_id", workspaceId)
                        .claim("scope", SCOPE_SERVICE);
        if (userType != null && !userType.isBlank()) {
            builder = builder.claim(USER_TYPE_CLAIM, userType);
        }
        return builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.ES256)
                .compact();
    }
}
