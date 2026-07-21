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
 * Mints the short-lived, creator-scoped token Spring presents to influora-ai's {@code POST
 * /internal/creator-suggestion} (Creator AI Co-pilot Tier-1, the Spring -&gt; Python direction).
 *
 * <p><b>[SEC: Kabir gate finding F-5, Medium — manifest gap, not a live vuln]</b> the 34-file
 * change-set manifest specified {@code app/auth/service_token.py::verify_creator_token()} on the
 * Python side (expecting a {@code creator_profile_id} claim + {@code scope=creator}) but enumerated
 * no Java-side minter to produce that token — this class IS that minter, added specifically to
 * close the gap Kabir flagged. It fails closed if never built (no token → Python 401) or misbuilt
 * (wrong scope → Python 403 {@code scope_mismatch}), so the gap was never exploitable, but the
 * segregation control needs an explicit owner, which this class now is.
 *
 * <p><b>Deliberately mints NO {@code workspace_id} claim</b> — a creator has no workspace (see
 * {@code security/AnalyticsUsageCapInterceptor.java}'s own comment on this). {@code
 * creator_profile_id} is the tenant key here, mirroring {@link BrandSafetyServiceTokenService}'s
 * {@code workspace_id} claim exactly but on a different claim name, and {@code scope="creator"}
 * (never {@code "service"}) so a brand-side {@code SCOPE_SERVICE} token can never satisfy {@code
 * creator_suggestion}'s scope requirement even if replayed against this route, and vice versa —
 * segregation is bidirectional (Kabir gate, IDOR threat-1: PASS).
 *
 * <p><b>Reuses {@link BrandSafetyServiceTokenProperties} and {@link SpringJwksKeyService}
 * verbatim</b> — same Spring -&gt; influora-ai signing direction, same asymmetric EC/ES256 keypair,
 * same {@code aud}/{@code iss}/TTL-ceiling shape config. A brand-new properties class (and a new
 * signing secret) would add a config surface with zero actual isolation benefit: the token's
 * {@code scope} claim, not a distinct signing key, is what separates this token's authority from
 * {@link BrandSafetyServiceTokenService}'s — see {@code app/auth/service_token.py}'s {@code
 * ENDPOINT_SCOPES} table, which is what actually enforces "a service-scope token cannot call
 * creator_suggestion" and vice versa.
 */
@Service
public class CreatorSuggestionServiceTokenService {

    public static final String SCOPE_CREATOR = "creator";

    private final BrandSafetyServiceTokenProperties props;
    private final SpringJwksKeyService jwksKeyService;

    public CreatorSuggestionServiceTokenService(
            BrandSafetyServiceTokenProperties props, SpringJwksKeyService jwksKeyService) {
        this.props = props;
        this.jwksKeyService = jwksKeyService;
    }

    /**
     * Mints a token scoped to exactly one creator, no {@code workspace_id}/{@code sub} claim.
     * Signed with Spring's asymmetric EC/ES256 private key — see {@link SpringJwksKeyService}.
     */
    public String mint(String creatorProfileId) {
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
                .claim("creator_profile_id", creatorProfileId)
                .claim("scope", SCOPE_CREATOR)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.ES256)
                .compact();
    }
}
