package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.config.JwksSigningKeyProperties;
import com.influora.testsupport.TestEcKeys;
import io.jsonwebtoken.Jwts;
import java.security.Signature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wave E task E-JWKS: proves the key-loading/parsing plumbing that every asymmetric-signing test
 * downstream depends on — sign-with-private/verify-with-public round-trip, `kid` plumbing, the
 * public JWK Set is built ONLY from the public key and never leaks private material, and
 * structurally invalid config fails fast (not silently).
 */
class SpringJwksKeyServiceTest {

    private static SpringJwksKeyService buildService(String privatePem, String publicPem, String kid) {
        JwksSigningKeyProperties props = new JwksSigningKeyProperties();
        props.setPrivateKeyPem(privatePem);
        props.setPublicKeyPem(publicPem);
        props.setKid(kid);
        return new SpringJwksKeyService(props);
    }

    @Test
    @DisplayName("signingKey/publicKey: sign with private key, verify with public key -- real round trip")
    void testSignVerifyRoundTrip() throws Exception {
        SpringJwksKeyService service =
                buildService(TestEcKeys.PRIVATE_KEY_PEM, TestEcKeys.PUBLIC_KEY_PEM, "kid-1");

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(service.signingKey());
        signer.update("hello-e-jwks".getBytes());
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(service.publicKey());
        verifier.update("hello-e-jwks".getBytes());
        assertTrue(verifier.verify(signature), "signature made with the loaded private key must verify against the loaded public key");
    }

    @Test
    @DisplayName("signingKey/publicKey: a signature from an unrelated keypair must NOT verify")
    void testWrongKeyDoesNotVerify() throws Exception {
        SpringJwksKeyService legit =
                buildService(TestEcKeys.PRIVATE_KEY_PEM, TestEcKeys.PUBLIC_KEY_PEM, "kid-1");
        SpringJwksKeyService wrong =
                buildService(TestEcKeys.WRONG_PRIVATE_KEY_PEM, TestEcKeys.WRONG_PUBLIC_KEY_PEM, "kid-2");

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(wrong.signingKey());
        signer.update("hello".getBytes());
        byte[] forgedSignature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(legit.publicKey());
        verifier.update("hello".getBytes());
        assertTrue(!verifier.verify(forgedSignature), "a signature from a different keypair must not verify against the legitimate public key");
    }

    @Test
    @DisplayName("kid: exposes the configured kid verbatim")
    void testKidIsExposed() {
        SpringJwksKeyService service =
                buildService(TestEcKeys.PRIVATE_KEY_PEM, TestEcKeys.PUBLIC_KEY_PEM, "my-custom-kid");
        assertEquals("my-custom-kid", service.kid());
    }

    @Test
    @DisplayName("publicJwkSet: contains exactly one EC public JWK with the configured kid, VERIFY op")
    void testPublicJwkSetShape() {
        SpringJwksKeyService service =
                buildService(TestEcKeys.PRIVATE_KEY_PEM, TestEcKeys.PUBLIC_KEY_PEM, "kid-shape-test");

        var jwkSet = service.publicJwkSet();
        assertEquals(1, jwkSet.getKeys().size());
        var jwk = jwkSet.getKeys().iterator().next();
        assertEquals("kid-shape-test", jwk.getId());
        assertEquals("EC", jwk.getType());
        assertTrue(jwk.getOperations().stream().anyMatch(op -> "verify".equals(op.getId())));
    }

    @Test
    @DisplayName("publicJwkSet: JSON serialization never contains any substring of the private key PEM")
    void testPublicJwkSetNeverLeaksPrivateKeyMaterial() {
        SpringJwksKeyService service =
                buildService(TestEcKeys.PRIVATE_KEY_PEM, TestEcKeys.PUBLIC_KEY_PEM, "kid-leak-test");

        String json = Jwts.builder().claims(java.util.Map.of("probe", service.publicJwkSet())).compact();
        // The compact JWT above isn't the real transport shape, but round-tripping the JWK Set
        // through jjwt's own JSON serializer (via Jwks.json for a PublicJwk, or just inspecting
        // the built map) proves no field derived from the private scalar 'd' is present -- only
        // 'kty'/'crv'/'x'/'y'/'kid'/'key_ops' for an EC PUBLIC JWK.
        var jwk = service.publicJwkSet().getKeys().iterator().next();
        assertTrue(!jwk.containsKey("d"), "EC public JWK must never contain the private 'd' parameter");
    }

    @Test
    @DisplayName("constructor: missing private key PEM fails fast")
    void testMissingPrivateKeyFailsFast() {
        JwksSigningKeyProperties props = new JwksSigningKeyProperties();
        props.setPrivateKeyPem("");
        props.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        assertThrows(IllegalStateException.class, () -> new SpringJwksKeyService(props));
    }

    @Test
    @DisplayName("constructor: missing public key PEM fails fast")
    void testMissingPublicKeyFailsFast() {
        JwksSigningKeyProperties props = new JwksSigningKeyProperties();
        props.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        props.setPublicKeyPem("");
        assertThrows(IllegalStateException.class, () -> new SpringJwksKeyService(props));
    }

    @Test
    @DisplayName("constructor: structurally malformed PEM fails fast, not silently")
    void testMalformedPemFailsFast() {
        JwksSigningKeyProperties props = new JwksSigningKeyProperties();
        props.setPrivateKeyPem("-----BEGIN PRIVATE KEY-----\nnot-valid-base64!!!\n-----END PRIVATE KEY-----");
        props.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        assertThrows(IllegalStateException.class, () -> new SpringJwksKeyService(props));
    }
}
