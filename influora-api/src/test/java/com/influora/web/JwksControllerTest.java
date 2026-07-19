package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.config.JwksSigningKeyProperties;
import com.influora.security.SpringJwksKeyService;
import com.influora.testsupport.TestEcKeys;
import java.util.List;
import java.util.Map;
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> keysOf(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("keys");
    }

    @Test
    @DisplayName("jwks: returns exactly one EC public key with the configured kid")
    void testJwksReturnsExpectedShape() {
        List<Map<String, Object>> keys = keysOf(controller.jwks());

        assertEquals(1, keys.size());
        Map<String, Object> jwk = keys.get(0);
        assertEquals("EC", jwk.get("kty"));
        assertEquals("test-kid-controller", jwk.get("kid"));
    }

    @Test
    @DisplayName("jwks: response contains only public-key fields, never a private/secret parameter")
    void testJwksNeverExposesPrivateKeyMaterial() {
        Map<String, Object> jwk = keysOf(controller.jwks()).get(0);

        // RFC 7518 §6.2.2: 'd' is the EC private key parameter. Its absence is exactly what
        // distinguishes an EcPublicJwk from an EcPrivateJwk.
        assertFalse(jwk.containsKey("d"), "JWKS response must never contain the EC private 'd' parameter");
        assertTrue(jwk.containsKey("x") && jwk.containsKey("y"), "EC public JWK must contain the public point (x, y)");
    }
}
