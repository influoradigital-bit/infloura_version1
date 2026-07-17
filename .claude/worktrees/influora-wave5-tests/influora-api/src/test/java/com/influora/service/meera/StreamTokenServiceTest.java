package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.config.JwksSigningKeyProperties;
import com.influora.config.MeeraStreamProperties;
import com.influora.security.SpringJwksKeyService;
import com.influora.testsupport.TestEcKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wave E task E-JWKS: proves {@code StreamTokenService} was actually switched to asymmetric
 * ES256 signing (ADR binding condition #2 — "both flows, one fix"), not just
 * {@code BrandSafetyServiceTokenService}. Mirrors {@code BrandSafetyServiceTokenServiceTest}.
 */
class StreamTokenServiceTest {

    private static final String WORKSPACE_ID = "ws-001";
    private static final String CONVERSATION_ID = "conv-001";
    private static final String MESSAGE_ID = "msg-001";
    private static final String USER_ID = "user-001";

    private MeeraStreamProperties props;
    private SpringJwksKeyService jwksKeyService;
    private StreamTokenService service;

    @BeforeEach
    void setUp() {
        props = new MeeraStreamProperties();
        props.setSigningSecret("unused-legacy-hs256-secret-kept-for-config-shape-only-32-bytes");
        props.setStreamTokenTtlSeconds(60);

        JwksSigningKeyProperties jwksProps = new JwksSigningKeyProperties();
        jwksProps.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        jwksProps.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        jwksProps.setKid("test-kid-stream");
        jwksKeyService = new SpringJwksKeyService(jwksProps);

        service = new StreamTokenService(props, jwksKeyService);
    }

    @Test
    @DisplayName("mint: token carries workspace/conversation/message claims and the user as subject")
    void testMintCarriesExpectedClaims() {
        String token = service.mint(WORKSPACE_ID, CONVERSATION_ID, MESSAGE_ID, USER_ID);

        Claims claims = service.parse(token);
        assertEquals(WORKSPACE_ID, claims.get("workspaceId"));
        assertEquals(CONVERSATION_ID, claims.get("conversationId"));
        assertEquals(MESSAGE_ID, claims.get("messageId"));
        assertEquals(USER_ID, claims.getSubject());
        assertTrue(claims.getAudience().contains(StreamTokenService.STREAM_AUDIENCE));
    }

    @Test
    @DisplayName("mint: signed asymmetrically (ES256) with the configured kid header, not HS256")
    void testMintSignsWithEs256AndKid() {
        String token = service.mint(WORKSPACE_ID, CONVERSATION_ID, MESSAGE_ID, USER_ID);
        var header =
                Jwts.parser().verifyWith(jwksKeyService.publicKey()).build().parseSignedClaims(token).getHeader();
        assertEquals("ES256", header.getAlgorithm());
        assertEquals("test-kid-stream", header.getKeyId());
    }

    @Test
    @DisplayName("parse: verifies against Spring's own public key -- a token signed by a different key must fail")
    void testParseRejectsTokenSignedByDifferentKey() {
        JwksSigningKeyProperties wrongProps = new JwksSigningKeyProperties();
        wrongProps.setPrivateKeyPem(TestEcKeys.WRONG_PRIVATE_KEY_PEM);
        wrongProps.setPublicKeyPem(TestEcKeys.WRONG_PUBLIC_KEY_PEM);
        wrongProps.setKid("wrong-kid");
        SpringJwksKeyService wrongKeyService = new SpringJwksKeyService(wrongProps);
        StreamTokenService serviceWithWrongKey = new StreamTokenService(props, wrongKeyService);

        String forgedToken = serviceWithWrongKey.mint(WORKSPACE_ID, CONVERSATION_ID, MESSAGE_ID, USER_ID);

        assertThrows(SignatureException.class, () -> service.parse(forgedToken));
    }

    @Test
    @DisplayName("mint: TTL is capped at 60s even if config requests a longer TTL")
    void testMintCapsTtlAtMax() {
        props.setStreamTokenTtlSeconds(3600);
        java.time.Instant before = java.time.Instant.now();

        String token = service.mint(WORKSPACE_ID, CONVERSATION_ID, MESSAGE_ID, USER_ID);
        Claims claims = service.parse(token);

        long actualTtl = claims.getExpiration().toInstant().getEpochSecond() - before.getEpochSecond();
        assertTrue(actualTtl <= 61, "TTL must never exceed the 60s contract ceiling, was " + actualTtl);
    }
}
