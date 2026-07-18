package com.influora.config;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Kabir finding (Auth audit, HIGH #1): fails fast when signing secrets are still the
 * committed dev defaults, too short, or reused across credential surfaces. Blast-radius
 * separation (JWT access/refresh, Meera stream, internal service-token, internal HMAC)
 * only holds if every secret is actually distinct and non-default in real environments.
 *
 * <p>Gated on {@code influora.env} (APP_ENV) cross-checked against the active Spring profile via
 * {@link InfluoraEnvironment} (see its javadoc — {@code environment.matchesProfiles("dev")}). Any
 * non-"dev" env throws and aborts startup; "dev" only warns, so committed defaults still boot
 * locally.
 *
 * <p><b>I8 fix (2026-07-18, APP_ENV production footgun):</b> previously {@code isDev} here was
 * derived from {@code influora.env} alone, which defaults to {@code "dev"} when {@code APP_ENV} is
 * unset ({@code @Value("${influora.env:dev}")} below). {@code application-prod.yml} binds {@code
 * influora.env: ${APP_ENV}} with NO default, so a deploy that correctly sets {@code
 * SPRING_PROFILES_ACTIVE=prod} but forgets {@code APP_ENV} already fails hard at context refresh
 * (unresolved placeholder) — that part was already fail-closed. The gap was the OTHER, easier
 * mistake: forgetting {@code SPRING_PROFILES_ACTIVE=prod} entirely. Then {@code
 * application-prod.yml} never activates, {@code influora.env} is never bound to {@code APP_ENV} by
 * anything (no relaxed-binding relationship between the two names), and {@code influora.env} quietly
 * falls back to its {@code "dev"} default regardless of what the box's real active profile is or
 * what {@code APP_ENV} was independently set to — every check below would then only WARN on a real,
 * possibly internet-facing box. {@link #validateEnvConsistency} closes this by requiring the {@code
 * dev} Spring profile to ALSO be active (via {@link InfluoraEnvironment}, which is itself keyed on
 * {@code spring.profiles.active}, not a hand-set var) before this validator treats itself as running
 * in dev — deriving from the profile instead of trusting {@code influora.env} in isolation.
 *
 * <p><b>Wave E task E-JWKS addition:</b> {@link JwksSigningKeyProperties#getPrivateKeyPem()} gets
 * the same fail-closed-in-non-dev treatment as every symmetric secret above — the ADR's binding
 * condition #2 ("key gets boot-time protection ... consistent with every other credential surface
 * in this codebase") requires it explicitly. A PEM key isn't a plain byte-length-checked string
 * like the HMAC secrets, so it gets its own dedicated check ({@link #validateJwksPrivateKey}):
 * still-the-committed-dev-default (exact PEM match) and structurally-invalid-or-not-EC both fail
 * closed in non-dev, same as a missing/weak HMAC secret.
 *
 * <p><b>Meera staging-readiness audit addition:</b> {@link AdminMfaProperties#getMfaSecretEncryptionKey()}
 * ({@code ADMIN_MFA_SECRET_ENCRYPTION_KEY}, cycle 3 AES-256-GCM admin MFA secret encryption fix) was
 * missing from this validator entirely — {@code AdminMfaSecretCipher} only checks that the key decodes
 * to 32 bytes, never that it's still the committed dev default, so staging/prod could silently boot
 * encrypting admin TOTP secrets with the value checked into {@code application.yml}. It's base64
 * rather than a plain byte-length-checked string like the HMAC secrets, so it gets its own dedicated
 * check ({@link #validateAdminMfaEncryptionKey}), same fail-closed-in-non-dev treatment as the JWKS PEM.
 *
 * <p><b>P1 security hardening additions (2026-07-12, Kabir §8):</b>
 * <ul>
 *   <li>{@link #validateRazorpayWebhookSecret} — {@code influora.razorpay.webhook-secret}
 *       ({@code application.yml}) previously had no boot-time check at all: a prod deploy left on
 *       the committed {@code REPLACE_WITH_YOUR_WEBHOOK_SECRET} placeholder would silently accept
 *       webhook requests signed with that PUBLIC (checked into git) string — a forgeable-payment-
 *       webhook hole (MEDIUM-HIGH: escrow funding/payout-related state can be driven by these
 *       webhooks). Fails closed in non-dev, same treatment as every secret above.
 *   <li>{@link #validateRefreshCookieSecureFlags} — {@code influora.auth.refresh-cookie.secure}
 *       and {@code influora.auth.admin-refresh-cookie.secure} were env-dependent-and-silent: if
 *       left at their {@code application.yml} defaults ({@code false} for brand/creator, {@code
 *       true} for admin) in a real HTTPS deploy, the brand/creator refresh cookie would be sent
 *       over plain HTTP with no boot-time signal that anything was wrong. Both must be {@code
 *       true} in non-dev now.
 * </ul>
 *
 * <p><b>Wave-1 S1-DB/D7 addition:</b> {@link #validateDatabaseCredentials} — {@code
 * spring.datasource.{username,password}} default to the committed {@code root}/{@code root} and
 * the URL defaults to {@code useSSL=false} (application.yml; a zero-config convenience for the
 * local docker-compose MySQL only). {@code application-prod.yml} now overrides all three with NO
 * default so a real {@code spring.profiles.active=prod} boot fails outright without real env vars
 * — this check is the independent belt-and-suspenders layer for the case where {@code
 * spring.profiles.active} itself was left unset. (Before the I8 fix above, this belt-and-suspenders
 * claim didn't actually hold: the layer was gated on {@code influora.env} alone, which silently
 * defaults to {@code "dev"} precisely when {@code spring.profiles.active} is left unset — the one
 * case it was supposed to catch. {@link #validateEnvConsistency} is what makes this true now.)
 *
 * <p><b>W0-5 addition:</b> {@link #validateAiServiceUrls} — {@link BrandSafetyAiProperties},
 * {@link TrendSparkAiProperties}, and {@link MeeraChatAiProperties} all default {@code base-url}
 * to {@code http://localhost:8000} and (before this task) had no {@code application-prod.yml}
 * override at all, so a prod boot would silently call the influora-ai (Python) service on
 * localhost — brand-safety scoring, TrendSpark nudges, and Meera's chat turn would all either
 * connect-refuse or, worse on a shared host, hit whatever happens to be listening on 8000. Fails
 * closed (non-dev) if any of the three still resolves to a loopback host.
 */
@Configuration
public class SecretsStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(SecretsStartupValidator.class);

    private static final int MIN_SECRET_BYTES = 32;

    // Must match the literal defaults in application.yml exactly.
    private static final Set<String> KNOWN_DEV_DEFAULTS =
            Set.of(
                    "dev-access-secret-change-in-production-min-32-chars",
                    "dev-refresh-secret-change-in-production-min-32-chars",
                    "dev-meera-stream-secret-change-in-production-min-32-chars",
                    "dev-internal-service-token-secret-change-in-production-min-32-chars",
                    "dev-internal-request-hmac-secret-change-in-production-min-32-chars",
                    "dev-brand-safety-service-token-secret-change-in-production-min-32-chars");

    // Must match the literal influora.jwks.private-key-pem dev default in application.yml exactly
    // (with its \n escapes intact, matching the raw config-bound string, not the parsed PEM).
    private static final String KNOWN_DEV_DEFAULT_JWKS_PRIVATE_KEY_PEM =
            "-----BEGIN PRIVATE KEY-----\n"
                    + "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgwXuGpQD24hSMl9Z9\n"
                    + "y2xqGdwJuSzhthckHYNjZp2IkYihRANCAAQutddG3wkGOEa6up6aIhJH8n2XLrpT\n"
                    + "iPxy7qwjmnc6jX+e/NWmlvY1wmnqbMenKssHuJ5i9BuEE4GQLH4AA1FV\n"
                    + "-----END PRIVATE KEY-----";

    // Must match the literal influora.admin.mfa-secret-encryption-key dev default in
    // application.yml exactly (base64, decodes to 32 bytes).
    private static final String KNOWN_DEV_DEFAULT_ADMIN_MFA_ENCRYPTION_KEY =
            "1FTwBvGuJmF6Q07xw3sMPX0CZEdRWxZx9cIC54HVfUU=";

    private static final int ADMIN_MFA_KEY_LENGTH_BYTES = 32;

    // Must match the literal influora.razorpay.webhook-secret dev/placeholder default in
    // application.yml exactly.
    private static final String KNOWN_PLACEHOLDER_RAZORPAY_WEBHOOK_SECRET =
            "REPLACE_WITH_YOUR_WEBHOOK_SECRET";

    // Must match the literal spring.datasource.{username,password} dev defaults in
    // application.yml exactly (Wave-1 S1-DB/D7).
    private static final String KNOWN_DEV_DEFAULT_DB_USERNAME = "root";
    private static final String KNOWN_DEV_DEFAULT_DB_PASSWORD = "root";

    private final JwtProperties jwtProperties;
    private final MeeraStreamProperties meeraStreamProperties;
    private final InternalServiceTokenProperties internalServiceTokenProperties;
    private final BrandSafetyServiceTokenProperties brandSafetyServiceTokenProperties;
    private final JwksSigningKeyProperties jwksSigningKeyProperties;
    private final AdminMfaProperties adminMfaProperties;
    private final RazorpayProperties razorpayProperties;
    private final DataSourceProperties dataSourceProperties;
    private final BrandSafetyAiProperties brandSafetyAiProperties;
    private final TrendSparkAiProperties trendSparkAiProperties;
    private final MeeraChatAiProperties meeraChatAiProperties;
    private final AnalyzeSiteAiProperties analyzeSiteAiProperties;
    private final InfluoraEnvironment influoraEnvironment;

    @Value("${influora.env:dev}")
    private String env;

    // Bound directly (not via a properties class) because AuthCookieService/AdminAuthCookieService
    // themselves bind these two flags via bare @Value fields, not a shared @ConfigurationProperties
    // bean — same key, same default, read here purely to validate at boot.
    @Value("${influora.auth.refresh-cookie.secure:false}")
    private boolean refreshCookieSecure;

    @Value("${influora.auth.admin-refresh-cookie.secure:true}")
    private boolean adminRefreshCookieSecure;

    public SecretsStartupValidator(
            JwtProperties jwtProperties,
            MeeraStreamProperties meeraStreamProperties,
            InternalServiceTokenProperties internalServiceTokenProperties,
            BrandSafetyServiceTokenProperties brandSafetyServiceTokenProperties,
            JwksSigningKeyProperties jwksSigningKeyProperties,
            AdminMfaProperties adminMfaProperties,
            RazorpayProperties razorpayProperties,
            DataSourceProperties dataSourceProperties,
            BrandSafetyAiProperties brandSafetyAiProperties,
            TrendSparkAiProperties trendSparkAiProperties,
            MeeraChatAiProperties meeraChatAiProperties,
            AnalyzeSiteAiProperties analyzeSiteAiProperties,
            InfluoraEnvironment influoraEnvironment) {
        this.jwtProperties = jwtProperties;
        this.meeraStreamProperties = meeraStreamProperties;
        this.internalServiceTokenProperties = internalServiceTokenProperties;
        this.brandSafetyServiceTokenProperties = brandSafetyServiceTokenProperties;
        this.jwksSigningKeyProperties = jwksSigningKeyProperties;
        this.adminMfaProperties = adminMfaProperties;
        this.razorpayProperties = razorpayProperties;
        this.dataSourceProperties = dataSourceProperties;
        this.brandSafetyAiProperties = brandSafetyAiProperties;
        this.trendSparkAiProperties = trendSparkAiProperties;
        this.meeraChatAiProperties = meeraChatAiProperties;
        this.analyzeSiteAiProperties = analyzeSiteAiProperties;
        this.influoraEnvironment = influoraEnvironment;
    }

    @PostConstruct
    void validate() {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("influora.jwt.access-secret", jwtProperties.getAccessSecret());
        secrets.put("influora.jwt.refresh-secret", jwtProperties.getRefreshSecret());
        secrets.put("influora.meera.stream.signing-secret", meeraStreamProperties.getSigningSecret());
        secrets.put(
                "influora.internal-service-token.signing-secret",
                internalServiceTokenProperties.getSigningSecret());
        secrets.put(
                "influora.internal-service-token.hmac-signing-secret",
                internalServiceTokenProperties.getHmacSigningSecret());
        secrets.put(
                "influora.brand-safety-service-token.signing-secret",
                brandSafetyServiceTokenProperties.getSigningSecret());

        // [I8] isDev now requires the 'dev' Spring profile to ALSO be active (InfluoraEnvironment,
        // profile-keyed), not just influora.env in isolation — see class javadoc "I8 fix".
        boolean envPropertySaysDev = "dev".equalsIgnoreCase(env);
        boolean profileSaysDev = influoraEnvironment.isDev();
        boolean isDev = envPropertySaysDev && profileSaysDev;
        StringBuilder problems = new StringBuilder();

        validateEnvConsistency(problems, profileSaysDev, envPropertySaysDev);

        secrets.forEach(
                (name, value) -> {
                    if (value == null || value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                        problems.append("  - ").append(name).append(" is missing or shorter than ")
                                .append(MIN_SECRET_BYTES).append(" bytes\n");
                    } else if (KNOWN_DEV_DEFAULTS.contains(value)) {
                        problems.append("  - ").append(name).append(" is still the committed dev default\n");
                    }
                });

        Set<String> seen = new LinkedHashSet<>();
        secrets.forEach(
                (name, value) -> {
                    if (value != null && !seen.add(value)) {
                        problems.append("  - ").append(name)
                                .append(" duplicates the value of another signing secret\n");
                    }
                });

        validateJwksPrivateKey(problems);
        validateAdminMfaEncryptionKey(problems);
        validateRazorpayWebhookSecret(problems);
        validateRefreshCookieSecureFlags(problems);
        validateDatabaseCredentials(problems);
        validateAiServiceUrls(problems);
        validateMeeraPublicStreamUrl(problems);

        if (problems.length() == 0) {
            return;
        }

        String message = "Secrets startup validation failed (env=" + env + "):\n" + problems;

        if (isDev) {
            log.warn(message);
        } else {
            throw new IllegalStateException(message);
        }
    }

    /**
     * [I8] Fails closed whenever the active Spring profile is NOT {@code dev} (prod, staging, or —
     * critically — no profile activated at all, i.e. {@code SPRING_PROFILES_ACTIVE} was left unset)
     * while {@code influora.env} (APP_ENV) resolves to {@code "dev"}. This is the exact
     * "APP_ENV production footgun": {@code influora.env} only ever gets bound to {@code APP_ENV} via
     * {@code application-prod.yml}'s {@code env: ${APP_ENV}}, which requires the {@code prod} profile
     * to be active in the first place. Forget {@code SPRING_PROFILES_ACTIVE=prod} and {@code
     * influora.env} silently falls back to its {@code "dev"} default (see the {@code @Value} below)
     * no matter what {@code APP_ENV} was independently set to — so a real, possibly internet-facing
     * deploy would otherwise sail through every check above in warn-only mode. This check appends a
     * problem (and therefore always aborts startup via the throw above, since {@code isDev} is also
     * false in this branch) purely on the mismatch itself, independent of whether any individual
     * secret happens to already be a real value — the inconsistency is the signal.
     */
    private void validateEnvConsistency(
            StringBuilder problems, boolean profileSaysDev, boolean envPropertySaysDev) {
        if (!profileSaysDev && envPropertySaysDev) {
            problems
                    .append("  - active Spring profile is not 'dev' but influora.env (APP_ENV) resolved to '")
                    .append(env)
                    .append("' — refusing to start. Either APP_ENV was left unset (it defaults to \"dev\")")
                    .append(" or was mis-set on a non-dev box. Set APP_ENV to the real environment name, or")
                    .append(" activate the 'dev' Spring profile if this really is a dev box.\n");
        }
    }

    /**
     * Fails closed (non-dev) if {@code influora.jwks.private-key-pem} is missing, still the
     * committed dev default, or not a structurally valid EC private key. Distinct from the
     * byte-length checks above since a PEM string's raw length isn't a meaningful strength proxy
     * the way it is for an HMAC secret — what matters is "is this the shipped placeholder" and
     * "does this actually parse as the asymmetric key type we sign with" (ES256/EC).
     */
    private void validateJwksPrivateKey(StringBuilder problems) {
        String pem = jwksSigningKeyProperties.getPrivateKeyPem();
        String name = "influora.jwks.private-key-pem";

        if (pem == null || pem.isBlank()) {
            problems.append("  - ").append(name).append(" is missing\n");
            return;
        }
        if (KNOWN_DEV_DEFAULT_JWKS_PRIVATE_KEY_PEM.equals(pem.trim())) {
            problems.append("  - ").append(name).append(" is still the committed dev default\n");
            return;
        }
        try {
            String base64 =
                    pem.trim()
                            .replace("\\n", "\n")
                            .lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.startsWith("-----"))
                            .reduce("", String::concat);
            byte[] der = Base64.getDecoder().decode(base64);
            var key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(key instanceof java.security.interfaces.ECPrivateKey)) {
                problems.append("  - ").append(name).append(" does not decode to an EC private key\n");
            }
        } catch (Exception e) {
            problems
                    .append("  - ")
                    .append(name)
                    .append(" is not a structurally valid PKCS#8 EC private key PEM: ")
                    .append(e.getMessage())
                    .append("\n");
        }
    }

    /**
     * Fails closed (non-dev) if {@code influora.admin.mfa-secret-encryption-key} is missing,
     * still the committed dev default, or does not base64-decode to exactly 32 bytes (AES-256).
     * {@link com.influora.service.admin.AdminMfaSecretCipher} already enforces the 32-byte decode
     * at bean-construction time (in every env, including dev), but it never checks "is this the
     * shipped placeholder" — so without this check, staging/prod could boot successfully while
     * silently encrypting admin TOTP secrets with the dev-default key committed to
     * application.yml. Base64-encoded like the JWKS PEM rather than a plain byte-length-checked
     * string like the HMAC secrets above, so it gets its own dedicated check.
     */
    private void validateAdminMfaEncryptionKey(StringBuilder problems) {
        String key = adminMfaProperties.getMfaSecretEncryptionKey();
        String name = "influora.admin.mfa-secret-encryption-key";

        if (key == null || key.isBlank()) {
            problems.append("  - ").append(name).append(" is missing\n");
            return;
        }
        if (KNOWN_DEV_DEFAULT_ADMIN_MFA_ENCRYPTION_KEY.equals(key.trim())) {
            problems.append("  - ").append(name).append(" is still the committed dev default\n");
            return;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(key.trim());
            if (decoded.length != ADMIN_MFA_KEY_LENGTH_BYTES) {
                problems
                        .append("  - ")
                        .append(name)
                        .append(" must decode to exactly ")
                        .append(ADMIN_MFA_KEY_LENGTH_BYTES)
                        .append(" bytes (AES-256), got ")
                        .append(decoded.length)
                        .append("\n");
            }
        } catch (IllegalArgumentException e) {
            problems.append("  - ").append(name).append(" is not valid base64\n");
        }
    }

    /**
     * Fails closed (non-dev) if {@code influora.razorpay.webhook-secret} is missing or still the
     * committed placeholder. {@code RazorpayWebhookController} (or its equivalent signature-check
     * path) uses this value to verify incoming webhook signatures; the placeholder is PUBLIC
     * (checked into {@code application.yml}), so leaving it in place in a real deploy means anyone
     * can forge a validly-signed webhook call.
     */
    private void validateRazorpayWebhookSecret(StringBuilder problems) {
        String secret = razorpayProperties.getWebhookSecret();
        String name = "influora.razorpay.webhook-secret";

        if (secret == null || secret.isBlank()) {
            problems.append("  - ").append(name).append(" is missing\n");
        } else if (KNOWN_PLACEHOLDER_RAZORPAY_WEBHOOK_SECRET.equals(secret.trim())) {
            problems.append("  - ").append(name).append(" is still the committed placeholder\n");
        }
    }

    /**
     * Fails closed (non-dev) if either refresh-cookie's {@code secure} flag is {@code false}.
     * Both {@code AuthCookieService} (brand/creator) and {@code AdminAuthCookieService} (admin)
     * default this per-environment via a bare {@code @Value}, so nothing previously stopped a real
     * HTTPS deploy from silently shipping with {@code secure=false} (the brand/creator default) —
     * the refresh cookie would then be sent in the clear over any accidental plain-HTTP path.
     */
    private void validateRefreshCookieSecureFlags(StringBuilder problems) {
        if (!refreshCookieSecure) {
            problems
                    .append("  - influora.auth.refresh-cookie.secure must be true outside dev\n");
        }
        if (!adminRefreshCookieSecure) {
            problems
                    .append(
                            "  - influora.auth.admin-refresh-cookie.secure must be true outside dev\n");
        }
    }

    /**
     * [SEC: Wave-1 S1-DB/D7] Fails closed (non-dev) if the resolved {@code spring.datasource}
     * credentials are missing, still the committed {@code root}/{@code root} dev default, or the
     * JDBC URL still disables TLS ({@code useSSL=false}). {@code application-prod.yml} already
     * removes the reachable {@code root}/{@code root} fallback and requires the three
     * {@code SPRING_DATASOURCE_*} env vars outright when {@code spring.profiles.active=prod} — this
     * check is the independent safety net for the case where that Spring profile was never
     * actually activated (matching this validator's own {@code influora.env}-gated design, see
     * class javadoc): a real deploy that forgot {@code SPRING_PROFILES_ACTIVE=prod} but did set
     * {@code APP_ENV=prod}/{@code influora.env=prod} would otherwise still silently boot against
     * {@code application.yml}'s root:root, unencrypted-transport default.
     */
    private void validateDatabaseCredentials(StringBuilder problems) {
        String username = dataSourceProperties.getUsername();
        String password = dataSourceProperties.getPassword();
        String url = dataSourceProperties.getUrl();

        if (username == null || username.isBlank()) {
            problems.append("  - spring.datasource.username is missing\n");
        } else if (KNOWN_DEV_DEFAULT_DB_USERNAME.equals(username)) {
            problems.append("  - spring.datasource.username is still the committed dev default (root)\n");
        }

        if (password == null || password.isBlank()) {
            problems.append("  - spring.datasource.password is missing\n");
        } else if (KNOWN_DEV_DEFAULT_DB_PASSWORD.equals(password)) {
            problems.append("  - spring.datasource.password is still the committed dev default (root)\n");
        }

        if (url == null || url.isBlank()) {
            problems.append("  - spring.datasource.url is missing\n");
        } else if (url.contains("useSSL=false")) {
            problems.append(
                    "  - spring.datasource.url disables TLS (useSSL=false); SSL is required outside dev\n");
        }
    }

    /**
     * [W0-5] Fails closed (non-dev) if any influora-ai (Python) client base URL still resolves to
     * a loopback host. Each of these three defaults to {@code http://localhost:8000} in its
     * {@code @ConfigurationProperties} class — a fine zero-config value for a local Python
     * service running alongside Spring on the same box, but a prod deploy that forgot to set
     * {@code BRAND_SAFETY_AI_BASE_URL}/{@code TRENDSPARK_AI_BASE_URL}/{@code
     * MEERA_CHAT_AI_BASE_URL} (or whose {@code application-prod.yml} override regressed) would
     * otherwise silently try to reach brand-safety scoring, TrendSpark nudges, or Meera's chat
     * turn on localhost instead of the real influora-ai deployment.
     */
    private void validateAiServiceUrls(StringBuilder problems) {
        checkNotLocalhost(problems, "influora.brand-safety-ai.base-url", brandSafetyAiProperties.getBaseUrl());
        checkNotLocalhost(problems, "influora.trendspark-ai.base-url", trendSparkAiProperties.getBaseUrl());
        checkNotLocalhost(problems, "influora.meera-chat-ai.base-url", meeraChatAiProperties.getBaseUrl());
        // analyze-site is an AI service too: AnalyzeSiteAiProperties.baseUrl defaults to
        // http://localhost:8000 exactly like its three siblings, so leaving it out would let a prod
        // deploy silently point site analysis at localhost — the precise failure W0-5 exists to stop.
        checkNotLocalhost(problems, "influora.analyze-site-ai.base-url", analyzeSiteAiProperties.getBaseUrl());
    }

    /**
     * Fails closed (non-dev) if {@code influora.meera.stream.public-chat-url} still resolves to a
     * loopback host or is not HTTPS. Unlike the {@link #validateAiServiceUrls} URLs — which are
     * Spring→Python calls on a private network where plain HTTP can be a legitimate choice — this
     * URL is handed to the BROWSER ({@code SendTurnResponse.streamUrl}): the SPA is served over
     * HTTPS, so an {@code http://} value would be blocked as mixed content at best, and at worst
     * would send the scoped stream token in cleartext. {@code application-prod.yml} already binds
     * it to the required {@code ${MEERA_PUBLIC_CHAT_URL}} placeholder (fails loud if unset); this
     * is the belt-and-suspenders layer for a boot where the prod profile was never activated (the
     * Java default is {@code http://localhost:8000/chat}) or where the env var was set to a
     * localhost/http value.
     */
    private void validateMeeraPublicStreamUrl(StringBuilder problems) {
        String name = "influora.meera.stream.public-chat-url";
        String url = meeraStreamProperties.getPublicChatUrl();

        int before = problems.length();
        checkNotLocalhost(problems, name, url);
        if (problems.length() > before) {
            // Missing/invalid/loopback already reported — the scheme check below would be noise.
            return;
        }
        String scheme = java.net.URI.create(url).getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            problems
                    .append("  - ")
                    .append(name)
                    .append(" must be HTTPS (the browser connects here with the stream token): ")
                    .append(url)
                    .append("\n");
        }
    }

    private void checkNotLocalhost(StringBuilder problems, String name, String url) {
        if (url == null || url.isBlank()) {
            problems.append("  - ").append(name).append(" is missing\n");
            return;
        }
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            problems.append("  - ").append(name).append(" is not a valid URL: ").append(url).append("\n");
            return;
        }
        if (host == null) {
            problems.append("  - ").append(name).append(" is not a valid absolute URL: ").append(url).append("\n");
            return;
        }
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1")) {
            problems.append("  - ").append(name).append(" still points at localhost (").append(url).append(")\n");
        }
    }
}
