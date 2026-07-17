package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for Spring's asymmetric service-token signing key (Wave E task E-JWKS,
 * {@code wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md}).
 *
 * <p>This is the fix for the Direction-2 (Spring -&gt; Python) auth gap documented in the ADR
 * above: Spring previously signed {@code BrandSafetyServiceTokenService} and {@code
 * StreamTokenService} tokens with HS256 shared secrets, which influora-ai's {@code
 * app/auth/service_token.py} deliberately refuses to verify in prod (JWKS path only accepts
 * RS256/ES256 — see {@code ALLOWED_ALGS} there). Giving Python a shared HS256 secret was
 * explicitly rejected by the ADR's hard condition: Python is the lower-trust, larger-attack-
 * surface service, so it must only ever hold Spring's PUBLIC key, never key material capable of
 * forging Spring's identity.
 *
 * <p><b>Algorithm choice: EC / ES256 (P-256), not RSA / RS256.</b> Documented reasoning (per the
 * task's instruction to pick one and document why):
 *
 * <ul>
 *   <li>This keypair signs exactly two short-lived (&lt;=60s), narrowly-scoped internal service
 *       tokens consumed by exactly one internal caller (influora-ai) over a mesh-internal call —
 *       it is never validated by third-party/legacy tooling with poor ES256 support, which is the
 *       usual argument for preferring RS256's universal compatibility. That argument does not
 *       apply here.
 *   <li>influora-ai's verifier already treats RS256 and ES256 identically ({@code ALLOWED_ALGS =
 *       ("RS256", "ES256")} in {@code service_token.py}) and PyJWT/{@code cryptography} (already a
 *       transitive dependency, used in this repo's own JWKS test fixtures — see {@code
 *       tests/security/test_service_token.py}) supports both natively, so there is no consumer-side
 *       compatibility reason to prefer RSA.
 *   <li>EC P-256 keys and signatures are far smaller (a public JWK is ~2 small base64url
 *       coordinates vs. an RSA-2048 modulus) — smaller {@code /.well-known/jwks.json} payload,
 *       smaller token overhead, and a smaller boot-time config value to manage/rotate as a single
 *       PEM string. Modern best practice (NIST, OWASP) for new asymmetric-signing designs prefers
 *       EC over RSA at equivalent security strength (P-256 ≈ RSA-3072 strength at a fraction of the
 *       key/signature size) unless legacy interop forces RSA.
 *   <li>jjwt-api 0.12.6 (already a dependency — no new Maven dependency needed for this task) has
 *       first-class {@code Jwks}/{@code JwkSetBuilder}/{@code EcPrivateJwk} support, so EC costs
 *       nothing extra to implement over RSA.
 * </ul>
 *
 * <p>The private key is PEM-encoded PKCS#8 (unencrypted — this is a mesh-internal signing key
 * behind the same secrets-manager-injected-env-var boundary as every other secret in this file,
 * consistent with how every other credential here is handled) and boot-validated by {@link
 * SecretsStartupValidator} — see that class for the fail-closed non-dev check. The corresponding
 * public key is served, and ONLY the public key is ever served, at {@code GET
 * /.well-known/jwks.json} ({@code com.influora.web.JwksController}) via {@link
 * com.influora.security.SpringJwksKeyService}.
 */
@ConfigurationProperties(prefix = "influora.jwks")
public class JwksSigningKeyProperties {

    /** PEM-encoded (PKCS#8, unencrypted) EC (P-256) private key. Never logged, never returned by any endpoint. */
    private String privateKeyPem = "";

    /**
     * PEM-encoded (X.509 SubjectPublicKeyInfo) EC (P-256) public key — the counterpart of {@link
     * #privateKeyPem}, generated together as one keypair (e.g. {@code openssl ecparam -name
     * prime256v1 -genkey -noout | openssl ec -pubout}). Stored alongside the private key,
     * deliberately NOT derived from it at runtime: Java's {@code KeyFactory} cannot reconstruct an
     * EC public point from a PKCS#8 private key without pulling in a third-party EC math library
     * (e.g. BouncyCastle), which would be a new Maven dependency for something a one-line `openssl`
     * command already gives us for free at key-generation time. This is the ONLY value {@code
     * GET /.well-known/jwks.json} is built from.
     */
    private String publicKeyPem = "";

    /**
     * Key id advertised in both the JWT {@code kid} header and the published JWK — lets
     * influora-ai select the right key during rotation (old signature still verifies against the
     * previous {@code kid} entry until it is removed from the published set).
     */
    private String kid = "spring-dev-es256-1";

    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    public void setPrivateKeyPem(String privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
    }

    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    public void setPublicKeyPem(String publicKeyPem) {
        this.publicKeyPem = publicKeyPem;
    }

    public String getKid() {
        return kid;
    }

    public void setKid(String kid) {
        this.kid = (kid == null || kid.isBlank()) ? "spring-dev-es256-1" : kid;
    }

    public boolean isConfigured() {
        return privateKeyPem != null
                && !privateKeyPem.isBlank()
                && publicKeyPem != null
                && !publicKeyPem.isBlank();
    }
}
