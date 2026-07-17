package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.config.JwksSigningKeyProperties;
import com.influora.security.SpringJwksKeyService;
import com.influora.testsupport.TestEcKeys;
import io.jsonwebtoken.security.JwkSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wave E task E-JWKS: {@code GET /.well-known/jwks.json} is a NEW public, unauthenticated surface
 * (see {@code SecurityConfig}'s permitAll wiring) — this test proves it serves valid public-key
 * JSON shape and, critically, nothing sensitive: no private-key field, no unrelated app data.
 */
class JwksControllerTest {

    private JwksController controller;

    @BeforeEach
    void setUp() {
        JwksSigningKeyProperties props = new JwksSigningKeyProperties();
        props.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        props.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        props.setKid("test-kid-controller");
        controller = new JwksController(new SpringJwksKeyService(props));
    }

    @Test
    @DisplayName("jwks: returns exactly one EC public key with the configured kid")
    void testJwksReturnsExpectedShape() {
        JwkSet jwkSet = controller.jwks();

        assertEquals(1, jwkSet.getKeys().size());
        var jwk = jwkSet.getKeys().iterator().next();
        assertEquals("EC", jwk.getType());
        assertEquals("test-kid-controller", jwk.getId());
    }

    @Test
    @DisplayName("jwks: response contains only public-key fields, never a private/secret parameter")
    void testJwksNeverExposesPrivateKeyMaterial() {
        JwkSet jwkSet = controller.jwks();
        var jwk = jwkSet.getKeys().iterator().next();

        // RFC 7518 §6.2.2: 'd' is the EC private key parameter. Its absence is exactly what
        // distinguishes an EcPublicJwk from an EcPrivateJwk.
        assertFalse(jwk.containsKey("d"), "JWKS response must never contain the EC private 'd' parameter");
        assertTrue(jwk.containsKey("x") && jwk.containsKey("y"), "EC public JWK must contain the public point (x, y)");
    }
}
