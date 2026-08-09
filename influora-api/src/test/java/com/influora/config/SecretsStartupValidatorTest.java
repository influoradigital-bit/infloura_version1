package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.testsupport.TestEcKeys;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.mock.env.MockEnvironment;

/**
 * Wave E task E-JWKS: proves the ADR's binding condition #2 ("key gets boot-time protection ...
 * consistent with every other credential surface") actually holds for {@code
 * influora.jwks.private-key-pem} — missing, still-the-committed-dev-default, and structurally
 * malformed private keys all fail closed in non-dev, exactly like every other secret this
 * validator already covers. "dev" env only warns (existing behavior, unchanged).
 *
 * <p><b>I8 (APP_ENV production footgun):</b> {@code buildValidator} now wires an {@link
 * InfluoraEnvironment} backed by a {@link MockEnvironment} with the "dev" Spring profile active
 * whenever the test's {@code env} string is {@code "dev"} (and no profile active otherwise) — this
 * keeps every existing test's "dev warns / non-dev throws" behavior intact (profile and
 * influora.env agree in all of them) while letting the new tests at the bottom of this file
 * exercise the case that used to slip through: profile says non-dev, influora.env still says dev.
 */
class SecretsStartupValidatorTest {

    private JwtProperties jwtProperties;
    private MeeraStreamProperties meeraStreamProperties;
    private InternalServiceTokenProperties internalServiceTokenProperties;
    private BrandSafetyServiceTokenProperties brandSafetyServiceTokenProperties;
    private JwksSigningKeyProperties jwksSigningKeyProperties;
    private AdminMfaProperties adminMfaProperties;
    private RazorpayProperties razorpayProperties;
    private DataSourceProperties dataSourceProperties;
    private BrandSafetyAiProperties brandSafetyAiProperties;
    private TrendSparkAiProperties trendSparkAiProperties;
    private MeeraChatAiProperties meeraChatAiProperties;
    private AnalyzeSiteAiProperties analyzeSiteAiProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setAccessSecret("real-access-secret-that-is-at-least-32-bytes-long!!");
        jwtProperties.setRefreshSecret("real-refresh-secret-that-is-at-least-32-bytes-long!");

        meeraStreamProperties = new MeeraStreamProperties();
        meeraStreamProperties.setSigningSecret("real-meera-stream-secret-at-least-32-bytes-long!!!!");
        // The Java default is http://localhost:8000/chat, which now fails closed in non-dev —
        // the "everything is fine" baseline needs a real browser-reachable HTTPS URL.
        meeraStreamProperties.setPublicChatUrl("https://ai.influora.example/chat");

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

        dataSourceProperties = new DataSourceProperties();
        dataSourceProperties.setUrl(
                "jdbc:mysql://prod-db.internal:3306/influora?useSSL=true&serverTimezone=UTC");
        dataSourceProperties.setUsername("influora_prod_svc");
        dataSourceProperties.setPassword("real-prod-db-password-distinct-from-dev-default");

        brandSafetyAiProperties = new BrandSafetyAiProperties();
        brandSafetyAiProperties.setBaseUrl("https://ai.influora.internal");

        trendSparkAiProperties = new TrendSparkAiProperties();
        trendSparkAiProperties.setBaseUrl("https://ai.influora.internal");

        meeraChatAiProperties = new MeeraChatAiProperties();
        meeraChatAiProperties.setBaseUrl("https://ai.influora.internal");

        analyzeSiteAiProperties = new AnalyzeSiteAiProperties();
        analyzeSiteAiProperties.setBaseUrl("https://ai.influora.internal");
    }

    /** Defaults both refresh-cookie {@code secure} flags to {@code true} (the "everything is fine" state) — override via the 3-arg overload for the dedicated secure-flag tests. */
    private SecretsStartupValidator buildValidator(String env) throws Exception {
        return buildValidator(env, true, true);
    }

    /**
     * Keeps the active Spring profile in agreement with {@code env} ("dev" profile active iff
     * {@code env} is "dev") — every pre-I8 test cares only about the influora.env/warn-vs-throw
     * behavior, not the new profile cross-check, so this preserves their original semantics exactly.
     * Use {@link #buildValidator(String, boolean, boolean, boolean)} to control the profile
     * independently (the I8 mismatch tests below).
     */
    private SecretsStartupValidator buildValidator(
            String env, boolean refreshCookieSecure, boolean adminRefreshCookieSecure) throws Exception {
        return buildValidator(
                env, refreshCookieSecure, adminRefreshCookieSecure, "dev".equalsIgnoreCase(env));
    }

    private SecretsStartupValidator buildValidator(
            String env,
            boolean refreshCookieSecure,
            boolean adminRefreshCookieSecure,
            boolean devProfileActive)
            throws Exception {
        MockEnvironment mockEnvironment = new MockEnvironment();
        if (devProfileActive) {
            mockEnvironment.setActiveProfiles("dev");
        }
        SecretsStartupValidator validator =
                new SecretsStartupValidator(
                        jwtProperties,
                        meeraStreamProperties,
                        internalServiceTokenProperties,
                        brandSafetyServiceTokenProperties,
                        jwksSigningKeyProperties,
                        adminMfaProperties,
                        razorpayProperties,
                        dataSourceProperties,
                        brandSafetyAiProperties,
                        trendSparkAiProperties,
                        meeraChatAiProperties,
                        analyzeSiteAiProperties,
                        new InfluoraEnvironment(mockEnvironment));
        setField(validator, "env", env);
        setField(validator, "refreshCookieSecure", refreshCookieSecure);
        setField(validator, "adminRefreshCookieSecure", adminRefreshCookieSecure);
        // [SEC: Kabir CR-11 XFF re-review, M-3] The value a correctly-configured deploy ships with
        // (application.yml's default). Set here so the existing "boots clean in prod" cases stay
        // about what they are about; the misconfiguration is asserted by its own test below.
        setField(validator, "forwardHeadersStrategy", "native");
        // [CR-81] Real, non-loopback value for the "everything is fine" baseline — the localhost
        // default is asserted by its own tests below, same convention as forwardHeadersStrategy.
        setField(validator, "apiPublicUrl", "https://api.influora.example");
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
    @DisplayName(
            "[SEC: Kabir CR-11 XFF re-review, M-3] forward-headers-strategy=framework fails closed"
                    + " in prod — it was one word in a compose file and it defeated every IP limit")
    void testFrameworkForwardHeadersStrategyFailsClosedInProd() throws Exception {
        SecretsStartupValidator validator = buildValidator("prod");
        setField(validator, "forwardHeadersStrategy", "framework");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("server.forward-headers-strategy"));
    }

    @Test
    @DisplayName(
            "[SEC: Kabir CR-11 XFF re-review, M-3] forward-headers-strategy=none ALSO fails closed"
                    + " — it reads as the safe value and now collapses every client into one bucket")
    void testNoneForwardHeadersStrategyFailsClosedInProd() throws Exception {
        // `none` deserves its own case rather than riding on the `framework` one. It was this
        // file's own former fail-safe default, so it is the value someone reaches for when
        // "hardening" — and since the hand-rolled trusted-proxy list is deliberately gone, it no
        // longer degrades quietly: every request behind Caddy keys on Caddy's container address.
        SecretsStartupValidator validator = buildValidator("prod");
        setField(validator, "forwardHeadersStrategy", "none");

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    @DisplayName("dev is exempt — a local run has no reverse proxy in front of it")
    void testForwardHeadersStrategyNotEnforcedInDev() throws Exception {
        SecretsStartupValidator validator = buildValidator("dev");
        setField(validator, "forwardHeadersStrategy", "none");

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

    // ------------------------------------------------------------------------------------------
    // Wave-1 S1-DB/D7: DB credential + TLS boot validation.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("validate: committed root/root DB username+password fails closed in prod")
    void testDevDefaultDbCredentialsFailClosedInProd() throws Exception {
        dataSourceProperties.setUsername("root");
        dataSourceProperties.setPassword("root");
        SecretsStartupValidator validator = buildValidator("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("spring.datasource.username"));
        assertTrue(ex.getMessage().contains("spring.datasource.password"));
        assertTrue(ex.getMessage().contains("dev default"));
    }

    @Test
    @DisplayName("validate: missing DB username/password fails closed in prod")
    void testMissingDbCredentialsFailClosedInProd() throws Exception {
        dataSourceProperties.setUsername("");
        dataSourceProperties.setPassword(null);
        SecretsStartupValidator validator = buildValidator("staging");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("spring.datasource.username"));
        assertTrue(ex.getMessage().contains("spring.datasource.password"));
        assertTrue(ex.getMessage().contains("missing"));
    }

    @Test
    @DisplayName("validate: DB URL with useSSL=false fails closed in prod")
    void testDbUrlWithoutSslFailsClosedInProd() throws Exception {
        dataSourceProperties.setUrl(
                "jdbc:mysql://prod-db.internal:3306/influora?useSSL=false&serverTimezone=UTC");
        SecretsStartupValidator validator = buildValidator("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("spring.datasource.url"));
        assertTrue(ex.getMessage().contains("useSSL=false"));
    }

    @Test
    @DisplayName("validate: real, distinct DB credentials + useSSL=true boot clean in prod")
    void testRealDbCredentialsBootCleanInProd() throws Exception {
        SecretsStartupValidator validator = buildValidator("prod");
        assertDoesNotThrow(validator::validate);
    }

    @Test
    @DisplayName("validate: root/root DB credentials only WARN (do not throw) in env=dev")
    void testDevDefaultDbCredentialsOnlyWarnInDev() throws Exception {
        dataSourceProperties.setUsername("root");
        dataSourceProperties.setPassword("root");
        dataSourceProperties.setUrl(
                "jdbc:mysql://localhost:3306/Influora_AI?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        SecretsStartupValidator validator = buildValidator("dev");
        assertDoesNotThrow(validator::validate);
    }

    // ------------------------------------------------------------------------------------------
    // W0-5: influora-ai (brand-safety / trendspark / meera-chat) base URL boot validation.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("validate: localhost brand-safety-ai base-url fails closed in prod")
    void testLocalhostBrandSafetyAiUrlFailsClosedInProd() throws Exception {
        brandSafetyAiProperties.setBaseUrl("http://localhost:8000");
        SecretsStartupValidator validator = buildValidator("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.brand-safety-ai.base-url"));
        assertTrue(ex.getMessage().contains("localhost"));
    }

    @Test
    @DisplayName("validate: localhost analyze-site-ai base-url fails closed in prod")
    void testLocalhostAnalyzeSiteAiUrlFailsClosedInProd() throws Exception {
        // analyze-site defaults to http://localhost:8000 exactly like its three siblings; it was
        // originally left out of validateAiServiceUrls, which would have let a prod deploy silently
        // point site analysis at localhost.
        analyzeSiteAiProperties.setBaseUrl("http://localhost:8000");
        SecretsStartupValidator validator = buildValidator("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.analyze-site-ai.base-url"));
        assertTrue(ex.getMessage().contains("localhost"));
    }

    @Test
    @DisplayName("validate: 127.0.0.1 trendspark-ai base-url fails closed in prod")
    void testLoopbackTrendSparkAiUrlFailsClosedInProd() throws Exception {
        trendSparkAiProperties.setBaseUrl("http://127.0.0.1:8000");
        SecretsStartupValidator validator = buildValidator("staging");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.trendspark-ai.base-url"));
        assertTrue(ex.getMessage().contains("localhost"));
    }

    @Test
    @DisplayName("validate: localhost meera-chat-ai base-url fails closed in prod")
    void testLocalhostMeeraChatAiUrlFailsClosedInProd() throws Exception {
        meeraChatAiProperties.setBaseUrl("http://localhost:8000");
        SecretsStartupValidator validator = buildValidator("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.meera-chat-ai.base-url"));
        assertTrue(ex.getMessage().contains("localhost"));
    }

    @Test
    @DisplayName("validate: real ai.influora.internal URLs boot clean in prod")
    void testRealAiServiceUrlsBootCleanInProd() throws Exception {
        SecretsStartupValidator validator = buildValidator("prod");
        assertDoesNotThrow(validator::validate);
    }

    @Test
    @DisplayName("validate: localhost AI service URLs only WARN (do not throw) in env=dev")
    void testLocalhostAiServiceUrlsOnlyWarnInDev() throws Exception {
        brandSafetyAiProperties.setBaseUrl("http://localhost:8000");
        trendSparkAiProperties.setBaseUrl("http://localhost:8000");
        meeraChatAiProperties.setBaseUrl("http://localhost:8000");
        SecretsStartupValidator validator = buildValidator("dev");
        assertDoesNotThrow(validator::validate);
    }

    // ------------------------------------------------------------------------------------------
    // I8: APP_ENV production footgun. influora.env (APP_ENV) defaults to "dev" whenever
    // SPRING_PROFILES_ACTIVE=prod was never actually set (application-prod.yml is the only place
    // influora.env gets bound to APP_ENV at all) — so a deploy that forgot the Spring profile used
    // to sail through with every secret check in warn-only mode. All real secrets from setUp() are
    // used here on purpose: the point is that the profile/env mismatch alone must abort startup,
    // independent of whether the individual secrets already happen to be fine.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("validate: influora.env='dev' but no Spring profile active fails closed even with real secrets")
    void testDevEnvWithNoActiveProfileFailsClosedEvenWithRealSecrets() throws Exception {
        // devProfileActive=false: simulates SPRING_PROFILES_ACTIVE left unset entirely, while
        // influora.env still resolves "dev" (its @Value default) because application-prod.yml
        // never activated to override it.
        SecretsStartupValidator validator = buildValidator("dev", true, true, false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("active Spring profile is not 'dev'"));
        assertTrue(ex.getMessage().contains("influora.env"));
    }

    @Test
    @DisplayName("validate: influora.env='dev' but 'prod' Spring profile active fails closed even with real secrets")
    void testDevEnvWithProdProfileActiveFailsClosedEvenWithRealSecrets() throws Exception {
        // A real APP_ENV=dev mis-set on an actual prod box (prod profile active) — not just the
        // unset-profile case above.
        SecretsStartupValidator validator = buildValidator("dev", true, true, false);
        MockEnvironment prodProfile = new MockEnvironment();
        prodProfile.setActiveProfiles("prod");
        setField(validator, "influoraEnvironment", new InfluoraEnvironment(prodProfile));

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("active Spring profile is not 'dev'"));
    }

    @Test
    @DisplayName("validate: influora.env='prod' with 'dev' Spring profile active still fails closed on dev-default secrets (profile alone doesn't launder bad secrets)")
    void testProdEnvWithDevProfileActiveStillFailsOnDevDefaults() throws Exception {
        dataSourceProperties.setUsername("root");
        dataSourceProperties.setPassword("root");
        // devProfileActive=true but env="prod": isDev requires BOTH to agree, so this still throws
        // on the real dev-default DB creds below (env alone doesn't get a free pass either).
        SecretsStartupValidator validator = buildValidator("prod", true, true, true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("spring.datasource.username"));
    }

    @Test
    @DisplayName("validate: influora.env='dev' with 'dev' Spring profile active (both agree) only WARNS, unchanged")
    void testDevEnvWithDevProfileActiveOnlyWarns() throws Exception {
        SecretsStartupValidator validator = buildValidator("dev", true, true, true);
        assertDoesNotThrow(validator::validate);
    }

    // ------------------------------------------------------------------------------------------
    // Meera public stream URL (browser-facing SendTurnResponse.streamUrl): the Java default is
    // http://localhost:8000/chat, so a non-dev boot that never overrode it would hand browsers a
    // localhost URL — and an http:// override would send the stream token in cleartext / trip
    // mixed-content. Same fail-closed-in-non-dev, warn-only-in-dev treatment as everything above.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("validate: default localhost public-chat-url fails closed in prod")
    void testDefaultLocalhostPublicChatUrlFailsClosedInProd() throws Exception {
        // Restore the class default (setUp overrides it for the clean-boot baseline).
        meeraStreamProperties.setPublicChatUrl("http://localhost:8000/chat");
        SecretsStartupValidator validator = buildValidator("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.meera.stream.public-chat-url"));
        assertTrue(ex.getMessage().contains("localhost"));
    }

    @Test
    @DisplayName("validate: 127.0.0.1 public-chat-url fails closed in prod")
    void testLoopbackIpPublicChatUrlFailsClosedInProd() throws Exception {
        meeraStreamProperties.setPublicChatUrl("https://127.0.0.1/chat");
        SecretsStartupValidator validator = buildValidator("staging");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.meera.stream.public-chat-url"));
    }

    @Test
    @DisplayName("validate: plain-http public-chat-url on a real host fails closed in prod")
    void testHttpPublicChatUrlFailsClosedInProd() throws Exception {
        meeraStreamProperties.setPublicChatUrl("http://ai.influora.example/chat");
        SecretsStartupValidator validator = buildValidator("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.meera.stream.public-chat-url"));
        assertTrue(ex.getMessage().contains("HTTPS"));
    }

    @Test
    @DisplayName("validate: default localhost public-chat-url only WARNS (does not throw) in env=dev")
    void testDefaultLocalhostPublicChatUrlOnlyWarnsInDev() throws Exception {
        meeraStreamProperties.setPublicChatUrl("http://localhost:8000/chat");
        SecretsStartupValidator validator = buildValidator("dev");
        assertDoesNotThrow(validator::validate);
    }

    // ------------------------------------------------------------------------------------------
    // CR-81: influora.api.public-url (CreatorCouponService creator-facing tracking links) boot
    // validation. The Java/application.yml default is http://localhost:8080, so a non-dev boot
    // that never overrode it would ship every coupon share/click-tracking link dead. Same
    // fail-closed-outside-dev, warn-only-in-dev treatment as the AI service URLs above.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("validate: default localhost api public-url fails closed in prod")
    void testDefaultLocalhostApiPublicUrlFailsClosedInProd() throws Exception {
        SecretsStartupValidator validator = buildValidator("prod");
        setField(validator, "apiPublicUrl", "http://localhost:8080");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.api.public-url"));
        assertTrue(ex.getMessage().contains("localhost"));
    }

    @Test
    @DisplayName("validate: 127.0.0.1 api public-url fails closed in prod")
    void testLoopbackIpApiPublicUrlFailsClosedInProd() throws Exception {
        SecretsStartupValidator validator = buildValidator("staging");
        setField(validator, "apiPublicUrl", "http://127.0.0.1:8080");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.api.public-url"));
        assertTrue(ex.getMessage().contains("localhost"));
    }

    @Test
    @DisplayName("validate: missing api public-url fails closed in prod")
    void testMissingApiPublicUrlFailsClosedInProd() throws Exception {
        SecretsStartupValidator validator = buildValidator("prod");
        setField(validator, "apiPublicUrl", "");

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("influora.api.public-url"));
        assertTrue(ex.getMessage().contains("missing"));
    }

    @Test
    @DisplayName("validate: real api public-url boots clean in prod")
    void testRealApiPublicUrlBootsCleanInProd() throws Exception {
        SecretsStartupValidator validator = buildValidator("prod");
        assertDoesNotThrow(validator::validate);
    }

    @Test
    @DisplayName("validate: default localhost api public-url only WARNS (does not throw) in env=dev")
    void testDefaultLocalhostApiPublicUrlOnlyWarnsInDev() throws Exception {
        SecretsStartupValidator validator = buildValidator("dev");
        setField(validator, "apiPublicUrl", "http://localhost:8080");
        assertDoesNotThrow(validator::validate);
    }
}
