package com.influora.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.config.BrandSafetyServiceTokenProperties;
import com.influora.config.JwksSigningKeyProperties;
import com.influora.security.SpringJwksKeyService;
import com.influora.testsupport.TestEcKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wave C task C3 + Wave E task E-JWKS: proves the minted token carries exactly the claims
 * influora-ai's app/auth/service_token.py::verify_token requires (scope=service, aud, iss,
 * workspace_id), respects the TTL ceiling, AND (post E-JWKS) is signed asymmetrically (ES256)
 * with a `kid` header rather than the old HS256 shared secret.
 */
class BrandSafetyServiceTokenServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456789";

    private BrandSafetyServiceTokenProperties props;
    private SpringJwksKeyService jwksKeyService;
    private BrandSafetyServiceTokenService service;

    @BeforeEach
    void setUp() {
        props = new BrandSafetyServiceTokenProperties();
        props.setTtlSeconds(60);
        props.setAudience("influora-internal");
        props.setIssuer("influora-api");

        JwksSigningKeyProperties jwksProps = new JwksSigningKeyProperties();
        jwksProps.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        jwksProps.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        jwksProps.setKid("test-kid-brand-safety");
        jwksKeyService = new SpringJwksKeyService(jwksProps);

        service = new BrandSafetyServiceTokenService(props, jwksKeyService);
    }

    @Test
    @DisplayName("mint: token carries scope=service, correct aud/iss, and the given workspace_id")
    void testMintCarriesExpectedClaims() {
        String token = service.mint(WORKSPACE_ID);

        Claims claims = parse(token);
        assertEquals("service", claims.get("scope"));
        assertEquals(WORKSPACE_ID, claims.get("workspace_id"));
        assertEquals("influora-api", claims.getIssuer());
        assertTrue(claims.getAudience().contains("influora-internal"));
        assertNull(claims.getSubject(), "service token must not carry a user_id/sub claim");
        assertNotNull(claims.getExpiration());
        assertNotNull(claims.getIssuedAt());
    }

    @Test
    @DisplayName("mint: token is signed asymmetrically (ES256) with the configured kid header")
    void testMintSignsWithEs256AndKid() {
        String token = service.mint(WORKSPACE_ID);
        var header = Jwts.parser().verifyWith(jwksKeyService.publicKey()).build().parseSignedClaims(token).getHeader();
        assertEquals("ES256", header.getAlgorithm());
        assertEquals("test-kid-brand-safety", header.getKeyId());
    }

    @Test
    @DisplayName("mint: TTL is capped at MAX_TTL_SECONDS even if config requests a longer TTL")
    void testMintCapsTtlAtMax() {
        props.setTtlSeconds(3600); // way beyond the 60s ceiling

        Instant before = Instant.now();
        String token = service.mint(WORKSPACE_ID);
        Claims claims = parse(token);

        long actualTtl = claims.getExpiration().toInstant().getEpochSecond() - before.getEpochSecond();
        assertTrue(
                actualTtl <= BrandSafetyServiceTokenProperties.MAX_TTL_SECONDS + 1, // +1 clock slack
                "TTL must never exceed the hard ceiling regardless of config, was " + actualTtl);
    }

    @Test
    @DisplayName("mint: two tokens for the same workspace have distinct jti (no replay-friendly reuse)")
    void testMintProducesDistinctTokenIds() {
        String token1 = service.mint(WORKSPACE_ID);
        String token2 = service.mint(WORKSPACE_ID);

        assertNotEqualsJti(parse(token1).getId(), parse(token2).getId());
    }

    @Test
    @DisplayName("mint: different workspace ids produce tokens scoped to their own workspace_id")
    void testMintIsWorkspaceScoped() {
        String tokenA = service.mint("workspace-a");
        String tokenB = service.mint("workspace-b");

        assertEquals("workspace-a", parse(tokenA).get("workspace_id"));
        assertEquals("workspace-b", parse(tokenB).get("workspace_id"));
    }

    private void assertNotEqualsJti(String jti1, String jti2) {
        assertTrue(jti1 != null && jti2 != null && !jti1.equals(jti2));
    }

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(jwksKeyService.publicKey()).build().parseSignedClaims(token).getPayload();
    }
}
