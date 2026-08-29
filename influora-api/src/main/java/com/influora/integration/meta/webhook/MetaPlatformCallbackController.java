package com.influora.integration.meta.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.config.MetaApiProperties;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.service.AuditLogService;
import com.influora.service.IdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Meta Platform callbacks: the <b>Deauthorize Callback</b> and the <b>Data Deletion Callback</b>
 * that Meta requires before App Review will pass (F-0392 / FIX-WAVE-0828 Track E).
 *
 * <p><b>Why this exists.</b> Before this controller a grep of the whole backend for {@code
 * deauthorize}, {@code data_deletion}, {@code signed_request} returned zero hits. If a creator
 * removed Influora from their Instagram/Facebook settings, nothing here ever learned about it: the
 * {@code meta_oauth_tokens} row stayed {@code revoked = false} and {@code MetaTokenRefreshService},
 * {@code MetricsPollingJob}, {@code AudienceDemographicsJob} and {@code CreatorCaptionSyncJob} all
 * kept calling Graph with a token the user had already withdrawn consent for, until Meta 190'd it.
 *
 * <p><b>[SEC] Trust boundary.</b> Both endpoints are {@code permitAll} in {@code SecurityConfig} —
 * Meta is not a logged-in user and cannot present a JWT — exactly like {@code /webhooks/razorpay}.
 * The trust boundary is therefore the {@code signed_request} HMAC, verified in full BEFORE any
 * field of the payload is parsed or trusted, mirroring {@code RazorpayWebhookController#receive}'s
 * verify-then-parse ordering and {@code WebhookSignatureVerifier}'s fail-closed-on-blank-secret and
 * constant-time-compare conventions.
 *
 * <p><b>Both app pairs.</b> {@code MetaApiProperties} carries two independent app credentials — the
 * Facebook pair ({@code appId}/{@code appSecret}) and the separate Instagram pair ({@code
 * instagramAppId}/{@code instagramAppSecret}, T-IGLOGIN-0820). A deauthorize/deletion callback can
 * arrive from either App Dashboard, so verification tries each configured secret and records WHICH
 * app authenticated the request (kept in the audit detail). Resolution back to a token row is
 * delegated to {@link MetaTokenStorage#revokeByMetaUserId} (C5), which keys each {@link
 * MetaAuthPath} key-space on its own column — FB app-scoped id in {@code meta_user_id}, Instagram
 * user id in {@code ig_business_account_id} — with the auth-path predicate applied inside each
 * query.
 *
 * <p><b>Consistency with the published policy.</b> {@code
 * src/content/legal/meta-platform-data-policy.md} §9 (served at {@code /meta-data-policy}) is
 * Influora's published Data Deletion Instructions and promises a manual, email-driven process with
 * a 48-hour acknowledgement and deletion within 30 days. This controller does not contradict that:
 * it does not itself erase anything. It durably RECORDS the request on the existing append-only
 * audit trail ({@link AuditLogService}, {@code audit_log} — the same mechanism {@code
 * MetaTokenStorage} already uses for every token issue/refresh/revocation, per §7 of that policy)
 * so the manual 30-day process can actually be honoured and audited, and it hands Meta a status URL
 * that resolves to that published page.
 *
 * @see MetaTokenStorage#revokeCreatorToken(String)
 */
@RestController
@RequestMapping("/webhooks/meta")
public class MetaPlatformCallbackController {

    private static final Logger log = LoggerFactory.getLogger(MetaPlatformCallbackController.class);

    /**
     * The ONLY algorithm Meta signs a {@code signed_request} with. Asserted explicitly rather than
     * assumed: historically the {@code signed_request} format also allowed {@code "AES-256-CBC
     * HMAC-SHA256"}, and an unchecked {@code algorithm} field is the classic algorithm-confusion
     * downgrade. Anything other than this exact value is rejected as unverified.
     */
    private static final String REQUIRED_ALGORITHM = "HMAC-SHA256";

    private static final String HMAC_ALGO = "HmacSHA256";

    /** [SEC] Same free-text tier literal {@code MetaTokenStorage} uses for Meta token events. */
    private static final String TOOL_TIER_SENSITIVE = "SENSITIVE";

    /**
     * {@link IdempotencyService} scope for Meta platform callbacks — distinct from every other scope
     * in this codebase so a colliding raw key can never shadow an unrelated caller (see {@code
     * IdempotencyService}'s composite-key javadoc, and {@code RazorpayWebhookController}'s
     * {@code razorpay.subscription.webhook} scope for the same discipline).
     */
    private static final String CALLBACK_IDEMPOTENCY_SCOPE = "meta.platform.callback";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MetaApiProperties props;
    private final MetaTokenStorage tokenStorage;
    private final AuditLogService auditLog;
    private final IdempotencyService idempotencyService;
    private final String webBaseUrl;

    public MetaPlatformCallbackController(
            MetaApiProperties props,
            MetaTokenStorage tokenStorage,
            AuditLogService auditLog,
            IdempotencyService idempotencyService,
            @Value("${influora.web-base-url}") String webBaseUrl) {
        this.props = props;
        this.tokenStorage = tokenStorage;
        this.auditLog = auditLog;
        this.idempotencyService = idempotencyService;
        this.webBaseUrl = stripTrailingSlash(webBaseUrl);
    }

    // ---------------------------------------------------------------------------------------
    // E1 — Deauthorize Callback
    // ---------------------------------------------------------------------------------------

    /**
     * Meta POSTs {@code application/x-www-form-urlencoded} with a single {@code signed_request}
     * field when a user removes the app from their Instagram/Facebook settings.
     *
     * <p>Returns 200 with an empty body on success — Meta expects a 2xx and retries on anything
     * else. A request whose signature does not verify is rejected 400 with a generic code and no
     * internal detail (never "wrong app", never "unknown user", never an echo of the payload).
     *
     * <p><b>Idempotent.</b> Two layers, matching this codebase's existing webhook discipline: the
     * effect itself is naturally idempotent (every revoke path keys on {@code ...RevokedFalse}, so a
     * second delivery matches no row and is a no-op), and the audit/observability side effect is
     * additionally reserved once per {@code (user, issued_at)} delivery through {@link
     * IdempotencyService}.
     */
    @PostMapping(
            path = "/deauthorize",
            consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<Void> deauthorize(
            @RequestParam(name = "signed_request", required = false) String signedRequest) {
        VerifiedSignedRequest verified = verifyOrReject(signedRequest);

        runOncePerDelivery(
                "deauth",
                verified,
                () -> {
                    int revoked = revokeTokensFor(verified);
                    auditLog.recordToolCall(
                            null,
                            "META_APP_DEAUTHORIZED",
                            TOOL_TIER_SENSITIVE,
                            AuditLogService.OUTCOME_ALLOWED,
                            revoked > 0 ? null : "USER_NOT_RESOLVED",
                            null,
                            null,
                            Map.of(
                                    "authPath", verified.authPath().name(),
                                    "metaUserRef", verified.userRef(),
                                    "tokensRevoked", revoked));
                    if (revoked == 0) {
                        // Not an error to Meta (a 4xx/5xx here just triggers an endless retry), but it
                        // IS an operational anomaly worth alerting on: consent was withdrawn and we
                        // could not find the row to stop polling for. See the class-level note on the
                        // Facebook-path id gap.
                        log.warn(
                                "Meta deauthorize callback verified for authPath={} but matched no"
                                        + " non-revoked token (metaUserRef={}) — token polling may continue"
                                        + " for a user who has withdrawn consent; needs manual follow-up",
                                verified.authPath(),
                                verified.userRef());
                    } else {
                        log.info(
                                "Meta deauthorize callback applied: authPath={}, metaUserRef={},"
                                        + " tokensRevoked={}",
                                verified.authPath(),
                                verified.userRef(),
                                revoked);
                    }
                    return null;
                });

        return ResponseEntity.ok().build();
    }

    // ---------------------------------------------------------------------------------------
    // E2 — Data Deletion Callback
    // ---------------------------------------------------------------------------------------

    /**
     * Meta's Data Deletion Callback. Same {@code signed_request} contract and the same
     * verify-before-parse ordering as {@link #deauthorize}; the response, however, is a JSON body
     * Meta parses and shows the user:
     *
     * <pre>{@code { "url": "<where the user can check progress>", "confirmation_code": "<code>" }}</pre>
     *
     * <p>{@code confirmation_code} is derived deterministically (keyed HMAC over the Meta user id,
     * see {@link #deriveConfirmationCode}) rather than randomly, so Meta's retry of the same
     * delivery returns the identical code instead of minting a second reference for one request.
     *
     * <p>{@code url} points at the already-published {@code /meta-data-policy} page, whose §9 IS
     * Influora's Data Deletion Instructions (48h acknowledgement, deletion within 30 days), with the
     * confirmation code carried so the user can quote it. Nothing is erased here — see the
     * class-level note on staying consistent with what that page promises.
     */
    @PostMapping(
            path = "/data-deletion",
            consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.ALL_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DataDeletionResponse dataDeletion(
            @RequestParam(name = "signed_request", required = false) String signedRequest) {
        VerifiedSignedRequest verified = verifyOrReject(signedRequest);
        String confirmationCode = deriveConfirmationCode(verified);

        runOncePerDelivery(
                "deletion",
                verified,
                () -> {
                    // Consent has been withdrawn wholesale, so stop calling Graph immediately —
                    // deletion of the stored data itself remains the manual 30-day process §9 of the
                    // published policy promises, but continuing to poll in the meantime would not be.
                    int revoked = revokeTokensFor(verified);
                    auditLog.recordToolCall(
                            null,
                            "META_DATA_DELETION_REQUESTED",
                            TOOL_TIER_SENSITIVE,
                            AuditLogService.OUTCOME_ALLOWED,
                            null,
                            confirmationCode,
                            null,
                            Map.of(
                                    "authPath", verified.authPath().name(),
                                    "metaUserRef", verified.userRef(),
                                    "confirmationCode", confirmationCode,
                                    "tokensRevoked", revoked));
                    log.info(
                            "Meta data-deletion callback recorded: authPath={}, metaUserRef={},"
                                    + " confirmationCode={}, tokensRevoked={} — manual erasure due within"
                                    + " 30 days per the published Data Deletion Instructions",
                            verified.authPath(),
                            verified.userRef(),
                            confirmationCode,
                            revoked);
                    return null;
                });

        return new DataDeletionResponse(
                webBaseUrl + "/meta-data-policy?confirmation_code=" + confirmationCode, confirmationCode);
    }

    /** Meta's required Data Deletion Callback response shape — snake_case is Meta's contract. */
    public record DataDeletionResponse(
            @JsonProperty("url") String url, @JsonProperty("confirmation_code") String confirmationCode) {}

    // ---------------------------------------------------------------------------------------
    // Verification
    // ---------------------------------------------------------------------------------------

    /**
     * The verified result. {@code metaUserId} never reaches a log line or an audit record — {@link
     * #userRef()} (a truncated SHA-256) is what gets recorded, per {@code AuditLogService}'s "no PII
     * in detail" contract.
     */
    record VerifiedSignedRequest(String metaUserId, Long issuedAt, MetaAuthPath authPath) {

        /** Non-reversible, stable handle for logs/audit — never the raw Meta user id. */
        String userRef() {
            return sha256Hex("meta-user:" + metaUserId).substring(0, 16);
        }
    }

    /**
     * [SEC] Verifies the {@code signed_request} against every configured app secret and returns the
     * parsed payload, or throws a generic 400. Every failure mode — absent parameter, malformed
     * shape, no secret configured, wrong algorithm, bad signature, missing {@code user_id} — maps to
     * the SAME response, so the endpoint cannot be used as an oracle for which app is configured or
     * which user exists.
     */
    private VerifiedSignedRequest verifyOrReject(String signedRequest) {
        return verify(signedRequest)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "INVALID_SIGNED_REQUEST",
                                        "signed_request verification failed",
                                        HttpStatus.BAD_REQUEST));
    }

    private Optional<VerifiedSignedRequest> verify(String signedRequest) {
        if (signedRequest == null || signedRequest.isBlank()) {
            return Optional.empty();
        }
        int dot = signedRequest.indexOf('.');
        // Exactly two segments: <base64url sig>.<base64url payload>. lastIndexOf != indexOf catches a
        // third segment, which is not this format and must not be silently tolerated.
        if (dot <= 0 || dot == signedRequest.length() - 1 || signedRequest.lastIndexOf('.') != dot) {
            return Optional.empty();
        }
        String encodedSignature = signedRequest.substring(0, dot);
        // The HMAC is computed over the ENCODED payload segment exactly as received, not over the
        // decoded JSON — re-encoding would not be byte-identical and would fail every valid request.
        String encodedPayload = signedRequest.substring(dot + 1);

        byte[] expectedSignature;
        byte[] payloadBytes;
        try {
            expectedSignature = Base64.getUrlDecoder().decode(encodedSignature);
            payloadBytes = Base64.getUrlDecoder().decode(encodedPayload);
        } catch (IllegalArgumentException notBase64Url) {
            return Optional.empty();
        }
        if (expectedSignature.length == 0 || payloadBytes.length == 0) {
            return Optional.empty();
        }

        MetaAuthPath authPath = resolveSigningApp(encodedPayload, expectedSignature);
        if (authPath == null) {
            return Optional.empty();
        }

        JsonNode payload;
        try {
            payload = MAPPER.readTree(new String(payloadBytes, StandardCharsets.UTF_8));
        } catch (Exception notJson) {
            return Optional.empty();
        }
        if (payload == null || !payload.isObject()) {
            return Optional.empty();
        }
        // Algorithm confusion guard — checked AFTER the HMAC so a forged payload can never steer it.
        JsonNode algorithm = payload.path("algorithm");
        if (algorithm.isMissingNode()
                || algorithm.isNull()
                || !REQUIRED_ALGORITHM.equalsIgnoreCase(algorithm.asText())) {
            return Optional.empty();
        }
        JsonNode userId = payload.path("user_id");
        if (userId.isMissingNode() || userId.isNull() || userId.asText().isBlank()) {
            return Optional.empty();
        }
        JsonNode issuedAt = payload.path("issued_at");

        return Optional.of(
                new VerifiedSignedRequest(
                        userId.asText(), issuedAt.isIntegralNumber() ? issuedAt.asLong() : null, authPath));
    }

    /**
     * Which of the two Meta apps signed this request, or {@code null} if neither did.
     *
     * <p>Fail-closed on a blank secret, mirroring {@code WebhookSignatureVerifier}: an unconfigured
     * app is simply not a candidate, so a deploy with no Meta credentials accepts nothing rather
     * than HMAC-ing against the empty string. Both candidates are evaluated (no early return on the
     * first match) so the work done is independent of which secret matched.
     */
    private MetaAuthPath resolveSigningApp(String encodedPayload, byte[] expectedSignature) {
        MetaAuthPath matched = null;
        if (props.isConfigured()
                && constantTimeEquals(
                        expectedSignature, computeHmac(encodedPayload, props.getAppSecret()))) {
            matched = MetaAuthPath.FACEBOOK_LOGIN;
        }
        if (props.isInstagramLoginConfigured()
                && constantTimeEquals(
                        expectedSignature, computeHmac(encodedPayload, props.getInstagramAppSecret()))) {
            matched = matched == null ? MetaAuthPath.INSTAGRAM_LOGIN : matched;
        }
        return matched;
    }

    private static byte[] computeHmac(String encodedPayload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            // Never surfaces the secret or the payload.
            throw new IllegalStateException("Failed to compute Meta signed_request HMAC", e);
        }
    }

    /**
     * Constant-time comparison to avoid timing side-channels on signature checks — same discipline
     * as {@code WebhookSignatureVerifier#constantTimeEquals}, over raw bytes rather than hex text
     * because Meta's signature is base64url-encoded binary, not hex.
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    // ---------------------------------------------------------------------------------------
    // Effects
    // ---------------------------------------------------------------------------------------

    /**
     * Marks the non-revoked token this callback identifies as revoked, so {@code
     * MetaTokenRefreshService} / {@code MetricsPollingJob} / {@code AudienceDemographicsJob} stop
     * calling Graph for it. Delegates entirely to {@link MetaTokenStorage#revokeByMetaUserId} (C5,
     * follow-up to this controller's original EntityManager seam): that method resolves the id
     * across both key-spaces — the FB app-scoped {@code meta_user_id} persisted at
     * FACEBOOK_LOGIN-connect time (V20260828130000), then the Instagram user id in {@code
     * ig_business_account_id} for INSTAGRAM_LOGIN rows — via CR-111-hardened queries (explicit
     * {@code IS NOT NULL}, so a null/blank id can never mass-match the pre-migration rows whose
     * {@code meta_user_id} is still NULL), emits the audit record itself, and never throws on a
     * no-match.
     *
     * @return how many token rows were revoked (0 or 1); 0 means the user could not be resolved —
     *     expected for rows connected before V20260828130000 until the creator reconnects
     */
    private int revokeTokensFor(VerifiedSignedRequest verified) {
        return tokenStorage.revokeByMetaUserId(verified.metaUserId()).isPresent() ? 1 : 0;
    }

    /**
     * Runs {@code action} at most once per Meta delivery. The key is {@code kind + userRef +
     * issued_at}: {@code issued_at} is the signed envelope's own timestamp, stable across Meta's
     * retries of the SAME delivery but different for a genuine second deauthorize/deletion by the
     * same user — the same per-delivery (not merely per-user) reservation {@code
     * RazorpayWebhookController#deriveSubscriptionIdempotencyKey} builds. A replay is logged and
     * swallowed so Meta still gets its 2xx.
     *
     * <p>{@link VerifiedSignedRequest#userRef()} (a hash), never the raw Meta user id, goes into the
     * key — the {@code idempotency_keys} table is not a place to accumulate Meta user identifiers.
     */
    private void runOncePerDelivery(String kind, VerifiedSignedRequest verified, java.util.function.Supplier<Void> action) {
        String key =
                "meta-"
                        + kind
                        + ":"
                        + verified.userRef()
                        + ":"
                        + (verified.issuedAt() != null ? verified.issuedAt() : "unknown");
        try {
            idempotencyService.executeOnce(key, null, CALLBACK_IDEMPOTENCY_SCOPE, action);
        } catch (IdempotencyService.AlreadyCompletedException | IdempotencyService.AlreadyInProgressException replay) {
            log.info(
                    "Meta {} callback replay/duplicate for metaUserRef={} — no-op, acknowledged",
                    kind,
                    verified.userRef());
        }
    }

    /**
     * Deterministic per-user confirmation code: {@code HMAC-SHA256(user_id)} keyed with the signing
     * app's secret, truncated to 16 uppercase hex chars.
     *
     * <p>Deterministic so Meta's retry of one delivery yields one code (a random code would mint a
     * second reference for the same request and make the manual process ambiguous). Keyed with the
     * app secret so the code is not something an outsider who knows or guesses a Meta user id can
     * compute and quote at support; and derived from the user id rather than being the user id, so
     * the code itself discloses nothing.
     */
    private String deriveConfirmationCode(VerifiedSignedRequest verified) {
        String secret =
                verified.authPath() == MetaAuthPath.INSTAGRAM_LOGIN
                        ? props.getInstagramAppSecret()
                        : props.getAppSecret();
        byte[] mac = computeHmac("meta-deletion:" + verified.metaUserId(), secret);
        return HexFormat.of().formatHex(mac).substring(0, 16).toUpperCase(java.util.Locale.ROOT);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
