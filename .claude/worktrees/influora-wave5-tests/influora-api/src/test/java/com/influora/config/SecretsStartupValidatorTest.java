package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.testsupport.TestEcKeys;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wave E task E-JWKS: proves the ADR's binding condition #2 ("key gets boot-time protection ...
 * consistent with every other credential surface") actually holds for {@code
 * influora.jwks.private-key-pem} — missing, still-the-committed-dev-default, and structurally
 * malformed private keys all fail closed in non-dev, exactly like every other secret this
 * validator already covers. "dev" env only warns (existing behavior, unchanged).
 */
class SecretsStartupValidatorTest {

    private JwtProperties jwtProperties;
    private MeeraStreamProperties meeraStreamProperties;
    private InternalServiceTokenProperties internalServiceTokenProperties;
    private BrandSafetyServiceTokenProperties brandSafetyServiceTokenProperties;
    private JwksSigningKeyProperties jwksSigningKeyProperties;
    private AdminMfaProperties adminMfaProperties;
    private RazorpayProperties razorpayProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setAccessSecret("real-access-secret-that-is-at-least-32-bytes-long!!");
        jwtProperties.setRefreshSecret("real-refresh-secret-that-is-at-least-32-bytes-long!");

        meeraStreamProperties = new MeeraStreamProperties();
        meeraStreamProperties.setSigningSecret("real-meera-stream-secret-at-least-32-bytes-long!!!!");

        internalServiceTokenProperties = new InternalServiceTokenProperties();
        internalServiceTokenProperties.setSigningSecret("real-internal-service-token-secret-32-bytes-min!!!!");
        internalServiceTokenProperties.setHmacSigningSecret("real-internal-hmac-secret-at-least-32-bytes-long!!!");

        brandSafetyServiceTokenProperties = new BrandSafetyServiceTokenProperties();
        brandSafetyServiceTokenProperties.setSigningSecret("real-brand-safety-token-secret-at-least-32-bytes!!!");

        jwksSigningKeyProperties = new JwksSigningKeyProperties();
        jwksSigningKeyProperties.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        jwksSigningKeyProperties.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);

        adminMfaProperties = new AdminMfaProperties();
        // Real random 32-byte base64 key, distinct from the committed dev default.
        adminMfaProperties.setMfaSecretEncryptionKey("9k3ZqV0nT7pXwL2sYhC4Rr6bA1eD8mFj5uGz0iQnKtM=");

        razorpayProperties = new RazorpayProperties();
        razorpayProperties.setWebhookSecret("real-razorpay-webhook-secret-distinct-from-placeholder");
    }

    /** Defaults both refresh-cookie {@code secure} flags to {@code true} (the "everything is fine" state) — override via the 3-arg overload for the dedicated secure-flag tests. */
    private SecretsStartupValidator buildValidator(String env) throws Exception {
        return buildValidator(env, true, true);
    }

    private SecretsStartupValidator buildValidator(
            String env, boolean refreshCookieSecure, boolean adminRefreshCookieSecure) throws Exception {
        SecretsStartupValidator validator =
                new SecretsStartupValidator(
                        jwtProperties,
                        meeraStreamProperties,
                        internalServiceTokenProperties,
                        brandSafetyServiceTokenProperties,
                        jwksSigningKeyProperties,
                        adminMfaProperties,
                        razorpayProperties);
        setField(validator, "env", env);
        setField(validator, "refreshCookieSecure", refreshCookieSecure);
        setField(validator, "adminRefreshCookieSecure", adminRefreshCookieSecure);
        return validator;
    }

    private static void setField(SecretsStartupValidator validator, String fieldName, Object value)
            throws Exception {
        Field field = SecretsStartupValidator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(validator, value);
    }

    @Test
    @DisplayName("validate: all real, distinct, valid secrets (including a real EC key) boots clean in prod")
    void testAllRealSecretsBootsCleanInProd() throws Exception {
        SecretsStartupValidator validator = buildValidator("prod");
        assertDoesNotThrow(validator::validate);
    }

    @Test
    @DisplayName("validate: committed dev-default JWKS private key fails closed in prod")
    void testDevDefaultJwksPrivateKeyFailsClosedInProd() throws Exception {
        jwksSigningKeyProperties.setPrivateKeyPem(
                "-----BEGIN PRIVATE KEY-----\n"
                        + "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgwXuGpQD24hSMl9Z9\n"
                        + "y2xqGdwJuSzhthckHYNjZp2IkYihRANCAAQutddG3wkGOEa6up6aIhJH8n2XLrpT\n"
                        + "iPxy7qwjmnc6jX+e/NWmlvY1wmnqbMenKssHuJ5i9BuEE4GQLH4AA1FV\n"
                        + "-----END PRIVATE KEY-----");
        SecretsStartupValidator validator = buildValidator("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.jwks.private-key-pem"));
        assertTrue(ex.getMessage().contains("dev default"));
    }

    @Test
    @DisplayName("validate: missing JWKS private key fails closed in prod")
    void testMissingJwksPrivateKeyFailsClosedInProd() throws Exception {
        jwksSigningKeyProperties.setPrivateKeyPem("");
        SecretsStartupValidator validator = buildValidator("staging");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.jwks.private-key-pem"));
        assertTrue(ex.getMessage().contains("missing"));
    }

    @Test
    @DisplayName("validate: structurally malformed JWKS private key fails closed in prod")
    void testMalformedJwksPrivateKeyFailsClosedInProd() throws Exception {
        jwksSigningKeyProperties.setPrivateKeyPem("-----BEGIN PRIVATE KEY-----\nnot-base64!!!\n-----END PRIVATE KEY-----");
        SecretsStartupValidator validator = buildValidator("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.jwks.private-key-pem"));
    }

    @Test
    @DisplayName("validate: dev-default JWKS key only WARNS (does not throw) in env=dev")
    void testDevDefaultJwksPrivateKeyOnlyWarnsInDev() throws Exception {
        jwksSigningKeyProperties.setPrivateKeyPem(
                "-----BEGIN PRIVATE KEY-----\n"
                        + "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgwXuGpQD24hSMl9Z9\n"
                        + "y2xqGdwJuSzhthckHYNjZp2IkYihRANCAAQutddG3wkGOEa6up6aIhJH8n2XLrpT\n"
                        + "iPxy7qwjmnc6jX+e/NWmlvY1wmnqbMenKssHuJ5i9BuEE4GQLH4AA1FV\n"
                        + "-----END PRIVATE KEY-----");
        SecretsStartupValidator validator = buildValidator("dev");
        assertDoesNotThrow(validator::validate);
    }

    @Test
    @DisplayName("validate: committed dev-default admin MFA encryption key fails closed in prod")
    void testDevDefaultAdminMfaEncryptionKeyFailsClosedInProd() throws Exception {
        adminMfaProperties.setMfaSecretEncryptionKey("1FTwBvGuJmF6Q07xw3sMPX0CZEdRWxZx9cIC54HVfUU=");
        SecretsStartupValidator validator = buildValidator("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.admin.mfa-secret-encryption-key"));
        assertTrue(ex.getMessage().contains("dev default"));
    }

    @Test
    @DisplayName("validate: missing admin MFA encryption key fails closed in prod")
    void testMissingAdminMfaEncryptionKeyFailsClosedInProd() throws Exception {
        adminMfaProperties.setMfaSecretEncryptionKey("");
        SecretsStartupValidator validator = buildValidator("staging");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.admin.mfa-secret-encryption-key"));
        assertTrue(ex.getMessage().contains("missing"));
    }

    @Test
    @DisplayName("validate: admin MFA encryption key that doesn't decode to 32 bytes fails closed in prod")
    void testWrongLengthAdminMfaEncryptionKeyFailsClosedInProd() throws Exception {
        adminMfaProperties.setMfaSecretEncryptionKey("dG9vLXNob3J0LWtleQ=="); // "too-short-key", not 32 bytes
        SecretsStartupValidator validator = buildValidator("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.admin.mfa-secret-encryption-key"));
        assertTrue(ex.getMessage().contains("32 bytes"));
    }

    @Test
    @DisplayName("validate: non-base64 admin MFA encryption key fails closed in prod")
    void testNonBase64AdminMfaEncryptionKeyFailsClosedInProd() throws Exception {
        adminMfaProperties.setMfaSecretEncryptionKey("not-valid-base64!!!");
        SecretsStartupValidator validator = buildValidator("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.admin.mfa-secret-encryption-key"));
    }

    @Test
    @DisplayName("validate: dev-default admin MFA encryption key only WARNS (does not throw) in env=dev")
    void testDevDefaultAdminMfaEncryptionKeyOnlyWarnsInDev() throws Exception {
        adminMfaProperties.setMfaSecretEncryptionKey("1FTwBvGuJmF6Q07xw3sMPX0CZEdRWxZx9cIC54HVfUU=");
        SecretsStartupValidator validator = buildValidator("dev");
        assertDoesNotThrow(validator::validate);
    }

    // ------------------------------------------------------------------------------------------
    // P1 security hardening (2026-07-12, Kabir §8): Razorpay webhook secret + refresh-cookie
    // secure-flag boot validation.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("validate: committed placeholder Razorpay webhook secret fails closed in prod")
    void testPlaceholderRazorpayWebhookSecretFailsClosedInProd() throws Exception {
        razorpayProperties.setWebhookSecret("REPLACE_WITH_YOUR_WEBHOOK_SECRET");
        SecretsStartupValidator validator = buildValidator("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.razorpay.webhook-secret"));
        assertTrue(ex.getMessage().contains("placeholder"));
    }

    @Test
    @DisplayName("validate: missing Razorpay webhook secret fails closed in prod")
    void testMissingRazorpayWebhookSecretFailsClosedInProd() throws Exception {
        razorpayProperties.setWebhookSecret("");
        SecretsStartupValidator validator = buildValidator("staging");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.razorpay.webhook-secret"));
        assertTrue(ex.getMessage().contains("missing"));
    }

    @Test
    @DisplayName("validate: placeholder Razorpay webhook secret only WARNS (does not throw) in env=dev")
    void testPlaceholderRazorpayWebhookSecretOnlyWarnsInDev() throws Exception {
        razorpayProperties.setWebhookSecret("REPLACE_WITH_YOUR_WEBHOOK_SECRET");
        SecretsStartupValidator validator = buildValidator("dev");
        assertDoesNotThrow(validator::validate);
    }

    @Test
    @DisplayName("validate: brand/creator refresh-cookie secure=false fails closed in prod")
    void testRefreshCookieInsecureFailsClosedInProd() throws Exception {
        SecretsStartupValidator validator = buildValidator("prod", false, true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.auth.refresh-cookie.secure"));
    }

    @Test
    @DisplayName("validate: admin refresh-cookie secure=false fails closed in prod")
    void testAdminRefreshCookieInsecureFailsClosedInProd() throws Exception {
        SecretsStartupValidator validator = buildValidator("prod", true, false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.auth.admin-refresh-cookie.secure"));
    }

    @Test
    @DisplayName("validate: insecure refresh cookies only WARN (do not throw) in env=dev")
    void testInsecureRefreshCookiesOnlyWarnInDev() throws Exception {
        SecretsStartupValidator validator = buildValidator("dev", false, false);
        assertDoesNotThrow(validator::validate);
    }
}
