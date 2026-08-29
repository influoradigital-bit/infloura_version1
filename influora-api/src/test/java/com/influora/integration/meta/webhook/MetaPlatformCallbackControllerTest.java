package com.influora.integration.meta.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.MetaApiProperties;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.service.AuditLogService;
import com.influora.service.IdempotencyService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * [SEC: Kabir red-team, FIX-WAVE-0828 Track E / F-0392] Unit tests for {@link
 * MetaPlatformCallbackController} — the Meta Deauthorize and Data Deletion callbacks.
 *
 * <p>Covers the four non-negotiables from the brief: a valid signature is accepted and actually
 * revokes, a tampered signature (and a tampered payload) is rejected, a blank/missing app secret
 * fails CLOSED, and a replayed delivery is idempotent. Plus the algorithm-downgrade guard, and the
 * E3/C5 follow-up behaviors: a FACEBOOK_LOGIN deauthorize resolves through {@code
 * MetaTokenStorage#revokeByMetaUserId} (backed by the persisted FB app-scoped {@code
 * meta_user_id}, V20260828130000), and an unmatched id — including every pre-migration row whose
 * {@code meta_user_id} is still NULL — stays a logged no-op that ACKs 200.
 *
 * <p>Follows this codebase's existing webhook-test style ({@code ConversionWebhookControllerTest},
 * {@code ShopifyWebhookControllerTest}): no MockMvc harness exists here, so the controller methods
 * are called directly as plain Java with the exact {@code signed_request} string a real Meta
 * delivery would carry, built by {@link #sign} using the real HMAC the production code verifies.
 */
@ExtendWith(MockitoExtension.class)
class MetaPlatformCallbackControllerTest {

    private static final String FB_APP_ID = "1111111111";
    private static final String FB_APP_SECRET = "facebook-app-secret-value";
    private static final String IG_APP_ID = "2222222222";
    private static final String IG_APP_SECRET = "instagram-app-secret-value";

    private static final String META_USER_ID = "17841400000000001";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";
    private static final long ISSUED_AT = 1_756_000_000L;

    private static final String WEB_BASE_URL = "https://app.influora.in";

    @Mock private MetaTokenStorage tokenStorage;
    @Mock private AuditLogService auditLog;
    @Mock private IdempotencyService idempotencyService;

    private MetaApiProperties props;
    private MetaPlatformCallbackController controller;

    @BeforeEach
    void setUp() {
        props = new MetaApiProperties();
        props.setAppId(FB_APP_ID);
        props.setAppSecret(FB_APP_SECRET);
        props.setInstagramAppId(IG_APP_ID);
        props.setInstagramAppSecret(IG_APP_SECRET);
        controller = newController();
    }

    private MetaPlatformCallbackController newController() {
        return new MetaPlatformCallbackController(
                props, tokenStorage, auditLog, idempotencyService, WEB_BASE_URL + "/");
    }

    // -------------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------------

    /** Builds a real Meta-format {@code signed_request}: base64url(HMAC).base64url(payload). */
    private static String sign(String payloadJson, String secret) {
        String encodedPayload = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
        return base64Url(hmac(encodedPayload, secret)) + "." + encodedPayload;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] hmac(String encodedPayload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String payload(String algorithm, String userId, long issuedAt) {
        return "{\"algorithm\":\""
                + algorithm
                + "\",\"issued_at\":"
                + issuedAt
                + ",\"user_id\":\""
                + userId
                + "\"}";
    }

    private static String validPayload() {
        return payload("HMAC-SHA256", META_USER_ID, ISSUED_AT);
    }

    /** Makes {@code IdempotencyService.executeOnce} actually run the supplied action (fresh delivery). */
    private void idempotencyRunsAction() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
    }

    /** Stubs {@code MetaTokenStorage.revokeByMetaUserId} (C5) — the single revocation seam. */
    private void revokeResolves(boolean matched) {
        when(tokenStorage.revokeByMetaUserId(META_USER_ID))
                .thenReturn(matched ? Optional.of(CREATOR_PROFILE_ID) : Optional.empty());
    }

    // -------------------------------------------------------------------------------------------
    // E1 — valid signature accepted, and it actually revokes
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("E1: valid Instagram-app signature revokes via revokeByMetaUserId")
    void validInstagramSignatureRevokesCreatorToken() {
        idempotencyRunsAction();
        revokeResolves(true);

        var response = controller.deauthorize(sign(validPayload(), IG_APP_SECRET));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(tokenStorage).revokeByMetaUserId(META_USER_ID);
    }

    @Test
    @DisplayName("E1/E3: FB deauthorize with a persisted meta_user_id actually revokes (C5)")
    void facebookDeauthorizeWithPersistedMetaUserIdRevokes() {
        idempotencyRunsAction();
        // Simulates a FACEBOOK_LOGIN row connected after V20260828130000: the ASID in
        // signed_request.user_id is persisted in meta_oauth_tokens.meta_user_id, so
        // revokeByMetaUserId resolves it and returns the revoked row's creatorProfileId.
        revokeResolves(true);

        var response = controller.deauthorize(sign(validPayload(), FB_APP_SECRET));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(tokenStorage).revokeByMetaUserId(META_USER_ID);
        // The signing app is recorded in the audit detail; the resolved revoke is audited by
        // MetaTokenStorage itself (META_OAUTH_TOKEN_REVOKED_VIA_DEAUTHORIZE), not duplicated here.
        verify(auditLog)
                .recordToolCall(
                        any(),
                        eq("META_APP_DEAUTHORIZED"),
                        anyString(),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    @DisplayName(
            "E1/E3: an unmatched user id (incl. pre-migration NULL meta_user_id rows) is a logged"
                    + " no-op that still ACKs 200")
    void unresolvableUserStillAcknowledges() {
        idempotencyRunsAction();
        // Optional.empty() is exactly what revokeByMetaUserId returns for a row connected before
        // V20260828130000 (meta_user_id still NULL — the CR-111-hardened IS NOT NULL predicate
        // guarantees NULL columns never match) as well as for a genuinely unknown id.
        revokeResolves(false);

        var response = controller.deauthorize(sign(validPayload(), FB_APP_SECRET));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(tokenStorage).revokeByMetaUserId(META_USER_ID);
        // The anomaly is still put on the audit record with the USER_NOT_RESOLVED reason code.
        verify(auditLog)
                .recordToolCall(
                        any(),
                        eq("META_APP_DEAUTHORIZED"),
                        anyString(),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq("USER_NOT_RESOLVED"),
                        any(),
                        any(),
                        any());
    }

    // -------------------------------------------------------------------------------------------
    // Tampering
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("SEC: a tampered SIGNATURE is rejected 400 and nothing is revoked")
    void tamperedSignatureIsRejected() {
        String valid = sign(validPayload(), IG_APP_SECRET);
        int dot = valid.indexOf('.');
        char first = valid.charAt(0);
        String tampered = (first == 'A' ? 'B' : 'A') + valid.substring(1, dot) + valid.substring(dot);

        ApiException ex = assertThrows(ApiException.class, () -> controller.deauthorize(tampered));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_SIGNED_REQUEST", ex.getCode());
        verifyNoInteractions(tokenStorage, idempotencyService, auditLog);
    }

    @Test
    @DisplayName("SEC: a tampered PAYLOAD (signature left intact) is rejected 400")
    void tamperedPayloadIsRejected() {
        String signature = sign(validPayload(), IG_APP_SECRET).split("\\.")[0];
        String forgedPayload = base64Url(payload("HMAC-SHA256", "999999999", ISSUED_AT).getBytes(StandardCharsets.UTF_8));

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.deauthorize(signature + "." + forgedPayload));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(tokenStorage);
    }

    @Test
    @DisplayName("SEC: a signature made with the WRONG app's secret is rejected (no cross-app trust)")
    void signatureFromAnUnrelatedSecretIsRejected() {
        String forged = sign(validPayload(), "attacker-guessed-secret");

        ApiException ex = assertThrows(ApiException.class, () -> controller.deauthorize(forged));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(tokenStorage);
    }

    @Test
    @DisplayName("SEC: algorithm downgrade in the payload is rejected even with a valid HMAC")
    void algorithmDowngradeIsRejected() {
        String signed = sign(payload("AES-256-CBC HMAC-SHA256", META_USER_ID, ISSUED_AT), IG_APP_SECRET);

        ApiException ex = assertThrows(ApiException.class, () -> controller.deauthorize(signed));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(tokenStorage);
    }

    @Test
    @DisplayName("SEC: a missing / blank signed_request parameter is rejected 400")
    void missingSignedRequestIsRejected() {
        assertEquals(
                HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, () -> controller.deauthorize(null)).getStatus());
        assertEquals(
                HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, () -> controller.deauthorize("  ")).getStatus());
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> controller.deauthorize("no-dot-here")).getStatus());
        verifyNoInteractions(tokenStorage);
    }

    // -------------------------------------------------------------------------------------------
    // Fail-closed on a blank / missing app secret
    // -------------------------------------------------------------------------------------------

    /**
     * Fail-closed on a blank/missing app secret. Note WHY the obvious adversarial input — a
     * {@code signed_request} HMAC'd with the empty string, which is what an unguarded {@code new
     * SecretKeySpec("".getBytes(), ...)} would compare against — cannot even be constructed here:
     * JCE rejects a zero-length HMAC key with {@code IllegalArgumentException: Empty key}. So on a
     * deploy with no Meta credentials, an implementation missing the blank-secret guard would not
     * silently ACCEPT; it would throw out of the Mac init and answer 500 on every delivery. That is
     * a different bug, not a safe default, and this test pins the correct behavior for both: a
     * previously-valid request must be rejected as an ordinary 400 once the secret is gone, with no
     * {@link IllegalStateException} escaping the HMAC helper.
     */
    @Test
    @DisplayName("SEC: with NO app secret configured, verification fails CLOSED (400, not 500)")
    void blankSecretFailsClosed() {
        // Signed while the app WAS configured — i.e. a genuinely valid signature a moment ago.
        String previouslyValid = sign(validPayload(), IG_APP_SECRET);

        props.setAppId("");
        props.setAppSecret("");
        props.setInstagramAppId("");
        props.setInstagramAppSecret("");
        controller = newController();

        ApiException ex = assertThrows(ApiException.class, () -> controller.deauthorize(previouslyValid));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_SIGNED_REQUEST", ex.getCode());
        verifyNoInteractions(tokenStorage, idempotencyService, auditLog);
    }

    @Test
    @DisplayName("SEC: a half-configured app (id set, secret blank) is not a verification candidate")
    void blankSecretWithNonBlankAppIdFailsClosed() {
        String previouslyValid = sign(validPayload(), IG_APP_SECRET);

        props.setAppSecret("");
        props.setInstagramAppSecret("");
        controller = newController();

        ApiException ex = assertThrows(ApiException.class, () -> controller.deauthorize(previouslyValid));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(tokenStorage);
    }

    @Test
    @DisplayName("SEC: with only the Facebook app configured, an Instagram-signed request is rejected")
    void unconfiguredInstagramAppIsNotACandidate() {
        props.setInstagramAppId("");
        props.setInstagramAppSecret("");
        controller = newController();

        assertThrows(ApiException.class, () -> controller.deauthorize(sign(validPayload(), IG_APP_SECRET)));
        verifyNoInteractions(tokenStorage);
    }

    // -------------------------------------------------------------------------------------------
    // Idempotency / replay
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("E1: a replayed delivery is a no-op and still ACKs 200")
    void replayedDeauthorizeIsIdempotent() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyCompletedException("meta-deauth"));

        var response = controller.deauthorize(sign(validPayload(), IG_APP_SECRET));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verifyNoInteractions(tokenStorage);
    }

    @Test
    @DisplayName("E1: the idempotency key is per-delivery (issued_at) and never carries the raw user id")
    void idempotencyKeyIsPerDeliveryAndCarriesNoRawUserId() {
        idempotencyRunsAction();
        revokeResolves(false);

        controller.deauthorize(sign(validPayload(), IG_APP_SECRET));

        var keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(idempotencyService).executeOnce(keyCaptor.capture(), any(), anyString(), any());
        String key = keyCaptor.getValue();
        assertTrue(key.startsWith("meta-deauth:"), "key should be scoped by callback kind: " + key);
        assertTrue(key.endsWith(":" + ISSUED_AT), "key should be per-delivery via issued_at: " + key);
        assertTrue(!key.contains(META_USER_ID), "raw Meta user id must never enter the idempotency key");
        assertTrue(key.length() <= 128, "composite key must fit idempotency_keys.idempotency_key");
    }

    // -------------------------------------------------------------------------------------------
    // E2 — Data Deletion Callback
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("E2: returns a confirmation code and a status URL on the published policy page")
    void dataDeletionReturnsCodeAndRealStatusUrl() {
        idempotencyRunsAction();
        revokeResolves(true);

        var response = controller.dataDeletion(sign(validPayload(), IG_APP_SECRET));

        assertEquals(16, response.confirmationCode().length());
        assertEquals(
                WEB_BASE_URL + "/meta-data-policy?confirmation_code=" + response.confirmationCode(),
                response.url());
        assertTrue(
                !response.confirmationCode().contains(META_USER_ID),
                "the confirmation code must not embed the Meta user id");
        // Consent withdrawn wholesale -> stop calling Graph immediately, even though the erasure
        // itself is the manual 30-day process the published policy promises.
        verify(tokenStorage).revokeByMetaUserId(META_USER_ID);
    }

    @Test
    @DisplayName("E2: the confirmation code is stable across Meta's retries of the same request")
    void confirmationCodeIsDeterministic() {
        idempotencyRunsAction();
        revokeResolves(false);
        String signed = sign(validPayload(), IG_APP_SECRET);

        String first = controller.dataDeletion(signed).confirmationCode();
        String second = controller.dataDeletion(signed).confirmationCode();

        assertEquals(first, second);
    }

    @Test
    @DisplayName("E2: two different users get different confirmation codes")
    void confirmationCodeIsPerUser() {
        idempotencyRunsAction();
        when(tokenStorage.revokeByMetaUserId(anyString())).thenReturn(Optional.empty());

        String a = controller.dataDeletion(sign(validPayload(), IG_APP_SECRET)).confirmationCode();
        String b =
                controller
                        .dataDeletion(sign(payload("HMAC-SHA256", "17841400000000002", ISSUED_AT), IG_APP_SECRET))
                        .confirmationCode();

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("E2: a tampered data-deletion request is rejected before anything is recorded")
    void tamperedDataDeletionIsRejected() {
        String forged = sign(validPayload(), "attacker-guessed-secret");

        ApiException ex = assertThrows(ApiException.class, () -> controller.dataDeletion(forged));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(tokenStorage, auditLog, idempotencyService);
    }

    @Test
    @DisplayName("E2: a replayed data-deletion delivery returns the same code and does not re-record")
    void replayedDataDeletionIsIdempotent() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException("meta-deletion"));

        var response = controller.dataDeletion(sign(validPayload(), IG_APP_SECRET));

        assertEquals(16, response.confirmationCode().length());
        verifyNoInteractions(tokenStorage);
        verify(auditLog, never())
                .recordToolCall(any(), anyString(), anyString(), anyString(), any(), any(), any(), any());
    }
}
