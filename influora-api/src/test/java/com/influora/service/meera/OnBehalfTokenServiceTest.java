package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.config.JwksSigningKeyProperties;
import com.influora.domain.enums.UserType;
import com.influora.security.SpringJwksKeyService;
import com.influora.testsupport.TestEcKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SECURITY FIX #1 ({@code docs/security/meera-onbehalf-auth-security-design.md} §2): unit tests
 * for the dedicated per-turn on-behalf token mint/verify contract that replaces forwarding the
 * user's full public-API access token as {@code onbehalf_jwt}. Mirrors {@code
 * StreamTokenServiceTest}'s style/setup.
 */
class OnBehalfTokenServiceTest {

    private static final String WORKSPACE_ID = "ws-001";
    private static final String CONVERSATION_ID = "conv-001";
    private static final String TURN_ID = "msg-001";
    private static final String USER_ID = "user-001";

    private SpringJwksKeyService jwksKeyService;
    private OnBehalfTokenService service;

    @BeforeEach
    void setUp() {
        JwksSigningKeyProperties jwksProps = new JwksSigningKeyProperties();
        jwksProps.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        jwksProps.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        jwksProps.setKid("test-kid-onbehalf");
        jwksKeyService = new SpringJwksKeyService(jwksProps);

        service = new OnBehalfTokenService(jwksKeyService);
    }

    @Test
    @DisplayName("mint: token carries sub/workspaceId/userType/conversationId/turnId/scope claims")
    void testMintCarriesExpectedClaims() {
        String token = service.mint(WORKSPACE_ID, CONVERSATION_ID, TURN_ID, USER_ID, UserType.BRAND);

        Claims claims = service.verify(token);
        assertEquals(USER_ID, claims.getSubject());
        assertEquals(WORKSPACE_ID, claims.get("workspaceId"));
        assertEquals("BRAND", claims.get("userType"));
        assertEquals(CONVERSATION_ID, claims.get("conversationId"));
        assertEquals(TURN_ID, claims.get("turnId"));
        assertEquals(OnBehalfTokenService.SCOPE_READ_ONLY, claims.get("scope"));
        assertTrue(claims.getAudience().contains(OnBehalfTokenService.ONBEHALF_AUDIENCE));
        assertEquals(OnBehalfTokenService.ISSUER, claims.getIssuer());
        assertNotEquals(null, claims.getId(), "jti must be present for single-use tracking");
    }

    @Test
    @DisplayName("mint: aud is meera-onbehalf, distinct from the stream token's meera-stream audience")
    void testAudienceIsDistinctFromStreamAudience() {
        String token = service.mint(WORKSPACE_ID, CONVERSATION_ID, TURN_ID, USER_ID, UserType.BRAND);
        Claims claims = service.verify(token);

        assertEquals("meera-onbehalf", OnBehalfTokenService.ONBEHALF_AUDIENCE);
        assertNotEquals(StreamTokenService.STREAM_AUDIENCE, OnBehalfTokenService.ONBEHALF_AUDIENCE);
        assertTrue(claims.getAudience().contains("meera-onbehalf"));
    }

    @Test
    @DisplayName("mint: TTL is hard-capped at <=120s")
    void testMintCapsTtlAt120Seconds() {
        Instant before = Instant.now();

        String token = service.mint(WORKSPACE_ID, CONVERSATION_ID, TURN_ID, USER_ID, UserType.BRAND);
        Claims claims = service.verify(token);

        long actualTtl = claims.getExpiration().toInstant().getEpochSecond() - before.getEpochSecond();
        assertTrue(actualTtl <= 121, "on-behalf token TTL must never exceed 120s, was " + actualTtl);
    }

    @Test
    @DisplayName("mint: signed with ES256 + the configured kid, on Spring's dedicated JWKS keypair")
    void testMintSignsWithEs256AndKid() {
        String token = service.mint(WORKSPACE_ID, CONVERSATION_ID, TURN_ID, USER_ID, UserType.BRAND);
        var header =
                Jwts.parser().verifyWith(jwksKeyService.publicKey()).build().parseSignedClaims(token).getHeader();
        assertEquals("ES256", header.getAlgorithm());
        assertEquals("test-kid-onbehalf", header.getKeyId());
    }

    @Test
    @DisplayName("verify: rejects a token signed with a different keypair")
    void testVerifyRejectsTokenSignedByDifferentKey() {
        JwksSigningKeyProperties wrongProps = new JwksSigningKeyProperties();
        wrongProps.setPrivateKeyPem(TestEcKeys.WRONG_PRIVATE_KEY_PEM);
        wrongProps.setPublicKeyPem(TestEcKeys.WRONG_PUBLIC_KEY_PEM);
        wrongProps.setKid("wrong-kid");
        SpringJwksKeyService wrongKeyService = new SpringJwksKeyService(wrongProps);
        OnBehalfTokenService serviceWithWrongKey = new OnBehalfTokenService(wrongKeyService);

        String forgedToken =
                serviceWithWrongKey.mint(WORKSPACE_ID, CONVERSATION_ID, TURN_ID, USER_ID, UserType.BRAND);

        assertThrows(SignatureException.class, () -> service.verify(forgedToken));
    }

    @Test
    @DisplayName("verify: rejects an expired token")
    void testVerifyRejectsExpiredToken() {
        String expiredToken =
                Jwts.builder()
                        .header()
                        .keyId(jwksKeyService.kid())
                        .and()
                        .id("expired-jti")
                        .issuer(OnBehalfTokenService.ISSUER)
                        .subject(USER_ID)
                        .audience()
                        .add(OnBehalfTokenService.ONBEHALF_AUDIENCE)
                        .and()
                        .claim("workspaceId", WORKSPACE_ID)
                        .claim("userType", "BRAND")
                        .issuedAt(Date.from(Instant.now().minusSeconds(300)))
                        .expiration(Date.from(Instant.now().minusSeconds(180)))
                        .signWith(jwksKeyService.signingKey(), Jwts.SIG.ES256)
                        .compact();

        assertThrows(ExpiredJwtException.class, () -> service.verify(expiredToken));
    }

    @Test
    @DisplayName("verify: rejects a token with the wrong audience (e.g. a stream token)")
    void testVerifyRejectsWrongAudience() {
        StreamTokenService streamTokenService =
                new StreamTokenService(new com.influora.config.MeeraStreamProperties(), jwksKeyService);
        String streamToken = streamTokenService.mint(WORKSPACE_ID, CONVERSATION_ID, TURN_ID, USER_ID);

        // aud is present (meera-stream) but wrong -- jjwt's requireAudience rejects it
        // (IncorrectClaimException, a JwtException subtype); asserting the JwtException supertype
        // keeps this test stable across jjwt point releases that may adjust the exact subtype.
        assertThrows(JwtException.class, () -> service.verify(streamToken));
    }

    @Test
    @DisplayName("verify: rejects a token with the wrong issuer")
    void testVerifyRejectsWrongIssuer() {
        String wrongIssuerToken =
                Jwts.builder()
                        .header()
                        .keyId(jwksKeyService.kid())
                        .and()
                        .id("wrong-iss-jti")
                        .issuer("someone-else")
                        .subject(USER_ID)
                        .audience()
                        .add(OnBehalfTokenService.ONBEHALF_AUDIENCE)
                        .and()
                        .claim("workspaceId", WORKSPACE_ID)
                        .claim("userType", "BRAND")
                        .issuedAt(Date.from(Instant.now()))
                        .expiration(Date.from(Instant.now().plusSeconds(60)))
                        .signWith(jwksKeyService.signingKey(), Jwts.SIG.ES256)
                        .compact();

        assertThrows(JwtException.class, () -> service.verify(wrongIssuerToken));
    }

    @Test
    @DisplayName(
            "cross-family: the public-API access-token parser (JwtService, HMAC) rejects an"
                    + " on-behalf token — a delegation token can never authenticate on public /api routes")
    void testPublicAccessTokenParserRejectsOnBehalfToken() {
        // The reverse of OnBehalfAuthResolverTest's headline case. The separation is structural
        // today (ES256/JWKS vs HS256/JwtProperties), but this test pins it: if a future refactor
        // ever moves public access tokens onto the JWKS keypair, this fails and forces an explicit
        // aud-based rejection in JwtAuthenticationFilter instead of a silent privilege merge
        // (design doc §3 — the delegation token must be useless outside /internal/meera/*).
        com.influora.config.JwtProperties jwtProps = new com.influora.config.JwtProperties();
        jwtProps.setAccessSecret("real-access-secret-that-is-at-least-32-bytes-long!!");
        jwtProps.setRefreshSecret("real-refresh-secret-that-is-at-least-32-bytes-long!");
        com.influora.security.JwtService jwtService = new com.influora.security.JwtService(jwtProps);

        String onBehalfToken =
                service.mint(WORKSPACE_ID, CONVERSATION_ID, TURN_ID, USER_ID, UserType.BRAND);

        assertThrows(JwtException.class, () -> jwtService.parseAccessToken(onBehalfToken));
    }
}
