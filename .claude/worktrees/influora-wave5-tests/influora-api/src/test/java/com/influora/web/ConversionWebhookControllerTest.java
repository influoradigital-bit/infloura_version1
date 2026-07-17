package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CouponCode;
import com.influora.domain.entity.CouponRedemption;
import com.influora.domain.entity.UtmCampaign;
import com.influora.integration.tracking.webhook.ConversionWebhookSecretService;
import com.influora.integration.tracking.webhook.ConversionWebhookSignatureVerifier;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CouponCodeRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.service.tracking.CampaignLinkService;
import com.influora.service.tracking.ConversionTrackingService;
import com.influora.service.tracking.RedemptionService;
import com.influora.web.dto.tracking.WebhookDtos.RedemptionWebhookResponse;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * [SEC: Kabir Wave E4 capstone red-team, HIGH -- re-escalated finding, {@code
 * wiki/errors/wave-e4-full-redteam-signoff.md} Part D / Condition 1] Unit tests for {@link
 * ConversionWebhookController} -- covers the task brief's required webhook signature test coverage
 * (valid/invalid/tampered/missing -> reject) for BOTH {@code /webhooks/redemption} and {@code
 * /webhooks/conversion}, workspace resolution (coupon-code / UTM-campaign-then-Campaign two-hop),
 * and confirms the pre-existing idempotent-retry / delegation behavior still works correctly
 * alongside the new signature check. {@code GET /track/click/*} is unaffected by this fix (see
 * controller class javadoc) -- its tests are unchanged in shape from before.
 *
 * <p>This codebase has no MockMvc/spring-security-test harness today -- controller methods are
 * tested directly as plain Java calls, same as {@code ShopifyWebhookControllerTest}/{@code
 * WooCommerceWebhookControllerTest}. The body is now bound as a raw JSON string (required for HMAC
 * verification over the exact bytes), so every test constructs the literal JSON string a real
 * caller would send, mirroring those two test classes' style exactly.
 */
@ExtendWith(MockitoExtension.class)
class ConversionWebhookControllerTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String OTHER_WORKSPACE_ID = "01HOTHERWORKSPACE1234A";
    private static final String CODE = "SAVE20";
    private static final String ORDER_ID = "order-123";
    private static final BigDecimal ORDER_AMOUNT = new BigDecimal("100.00");
    private static final String CUSTOMER_ID = "cust-1";
    private static final String IDEMPOTENCY_KEY = "idem-key-abc";
    private static final String UTM_ID = "01HUTM1234567890ABCDE";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String VALID_SIGNATURE = "valid-signature-stub";
    private static final String THE_SECRET = "the-secret";
    private static final String TRACKING_URL =
            "https://brand.example.com/?utm_source=instagram&utm_medium=influencer";

    @Mock private RedemptionService redemptionService;
    @Mock private ConversionTrackingService conversionTrackingService;
    @Mock private CampaignLinkService campaignLinkService;
    @Mock private UtmCampaignRepository utmCampaignRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CouponCodeRepository couponCodeRepository;
    @Mock private ConversionWebhookSignatureVerifier signatureVerifier;
    @Mock private ConversionWebhookSecretService secretService;

    private ConversionWebhookController controller;

    @BeforeEach
    void setUp() {
        controller =
                new ConversionWebhookController(
                        redemptionService,
                        conversionTrackingService,
                        campaignLinkService,
                        utmCampaignRepository,
                        campaignRepository,
                        couponCodeRepository,
                        signatureVerifier,
                        secretService);
    }

    private CouponCode couponInWorkspace(String workspaceId) {
        return CouponCode.builder()
                .id("01HCOUPON1234567890AB")
                .workspaceId(workspaceId)
                .campaignId(CAMPAIGN_ID)
                .creatorId("01HCREATOR1234567890A")
                .code(CODE)
                .discountType("fixed")
                .discountValue(new BigDecimal("20.00"))
                .build();
    }

    private UtmCampaign utmForCampaign(String campaignId) {
        return UtmCampaign.builder()
                .id(UTM_ID)
                .campaignId(campaignId)
                .collaborationId("01HCOLLAB1234567890AB")
                .creatorProfileId("01HCREATORPROFILE1234")
                .baseUrl("https://brand.example.com/")
                .utmSource("instagram")
                .utmMedium("influencer")
                .utmCampaign("summer-sale")
                .utmContent("creator-name")
                .fullTrackingUrl(TRACKING_URL)
                .build();
    }

    private Campaign campaignInWorkspace(String workspaceId) {
        return Campaign.builder().id(CAMPAIGN_ID).workspaceId(workspaceId).build();
    }

    // ------------------------------------------------------------------------------------------
    // POST /webhooks/redemption -- workspace resolution + signature verification
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("redeemCoupon: valid signature delegates to the WORKSPACE-SCOPED RedemptionService.redeem")
    void redeemCoupon_validSignature_delegatesWorkspaceScoped() {
        when(couponCodeRepository.findByCode(CODE)).thenReturn(Optional.of(couponInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(THE_SECRET);
        String rawPayload = redemptionPayload(CODE, ORDER_ID, "100.00", CUSTOMER_ID, IDEMPOTENCY_KEY);
        when(signatureVerifier.verify(rawPayload, VALID_SIGNATURE, THE_SECRET)).thenReturn(true);

        CouponRedemption redemption =
                CouponRedemption.builder()
                        .id("01HREDEMPTION1234567")
                        .couponId("01HCOUPON1234567890AB")
                        .orderId(ORDER_ID)
                        .orderAmount(ORDER_AMOUNT)
                        .discountApplied(new BigDecimal("20.00"))
                        .customerId(CUSTOMER_ID)
                        .idempotencyKey(IDEMPOTENCY_KEY)
                        .build();
        when(redemptionService.redeem(
                        WORKSPACE_ID, CODE, ORDER_ID, ORDER_AMOUNT, CUSTOMER_ID, WORKSPACE_ID + ":" + IDEMPOTENCY_KEY))
                .thenReturn(redemption);

        ResponseEntity<ApiResponse<RedemptionWebhookResponse>> response =
                controller.redeemCoupon(VALID_SIGNATURE, rawPayload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        RedemptionWebhookResponse body = response.getBody().data();
        assertEquals(redemption.getId(), body.redemptionId());
        // [SEC: Vikram, P3(b)] the persisted key is namespaced by the resolved workspaceId -- NOT
        // the raw brand-supplied token -- so two workspaces can never collide/replay each other.
        verify(redemptionService, times(1))
                .redeem(WORKSPACE_ID, CODE, ORDER_ID, ORDER_AMOUNT, CUSTOMER_ID, WORKSPACE_ID + ":" + IDEMPOTENCY_KEY);
        // Never falls back to the legacy global overload once a workspace is resolved.
        verify(redemptionService, never()).redeem(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("redeemCoupon: REJECTS (401) when the signature is invalid -- never reaches RedemptionService")
    void redeemCoupon_invalidSignature_rejected() {
        when(couponCodeRepository.findByCode(CODE)).thenReturn(Optional.of(couponInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(THE_SECRET);
        String rawPayload = redemptionPayload(CODE, ORDER_ID, "100.00", CUSTOMER_ID, IDEMPOTENCY_KEY);
        when(signatureVerifier.verify(rawPayload, "bad-signature", THE_SECRET)).thenReturn(false);

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.redeemCoupon("bad-signature", rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("redeemCoupon: REJECTS (401) when the signature header is missing/null")
    void redeemCoupon_missingSignature_rejected() {
        when(couponCodeRepository.findByCode(CODE)).thenReturn(Optional.of(couponInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(THE_SECRET);
        String rawPayload = redemptionPayload(CODE, ORDER_ID, "100.00", CUSTOMER_ID, IDEMPOTENCY_KEY);
        when(signatureVerifier.verify(rawPayload, null, THE_SECRET)).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> controller.redeemCoupon(null, rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("redeemCoupon: REJECTS (401) when the signature was computed over a tampered payload")
    void redeemCoupon_tamperedPayload_rejected() {
        when(couponCodeRepository.findByCode(CODE)).thenReturn(Optional.of(couponInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(THE_SECRET);
        // The signature was genuinely computed over the ORIGINAL amount; the attacker tampers the
        // body afterward. The verifier (proven separately) would recompute over the tampered bytes
        // and fail -- here we prove the controller propagates that false result into a 401.
        String tamperedPayload = redemptionPayload(CODE, ORDER_ID, "999999.99", CUSTOMER_ID, IDEMPOTENCY_KEY);
        when(signatureVerifier.verify(tamperedPayload, VALID_SIGNATURE, THE_SECRET)).thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> controller.redeemCoupon(VALID_SIGNATURE, tamperedPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("redeemCoupon: an unknown coupon code is rejected as INVALID_WEBHOOK_SIGNATURE, same as a bad signature (no enumeration)")
    void redeemCoupon_unknownCode_rejectedSameAsInvalidSignature() {
        when(couponCodeRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());
        when(secretService.decryptSecretForWorkspace(null)).thenReturn(null);
        String rawPayload = redemptionPayload("UNKNOWN", ORDER_ID, "100.00", CUSTOMER_ID, IDEMPOTENCY_KEY);

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.redeemCoupon(VALID_SIGNATURE, rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("redeemCoupon: a resolved workspace with NO configured secret is rejected (fail closed), never silently permitted")
    void redeemCoupon_workspaceWithNoSecret_rejected() {
        when(couponCodeRepository.findByCode(CODE)).thenReturn(Optional.of(couponInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(null);
        String rawPayload = redemptionPayload(CODE, ORDER_ID, "100.00", CUSTOMER_ID, IDEMPOTENCY_KEY);

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.redeemCoupon(VALID_SIGNATURE, rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        verify(signatureVerifier, never()).verify(anyString(), anyString(), anyString());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("redeemCoupon: malformed JSON body is rejected as INVALID_WEBHOOK_PAYLOAD before any lookup")
    void redeemCoupon_malformedJson_rejected() {
        ApiException ex =
                assertThrows(
                        ApiException.class, () -> controller.redeemCoupon(VALID_SIGNATURE, "not-json-at-all"));

        assertEquals("INVALID_WEBHOOK_PAYLOAD", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(couponCodeRepository, never()).findByCode(anyString());
    }

    @Test
    @DisplayName("redeemCoupon: idempotent retry (same signed payload delivered twice) returns the same replayed redemption")
    void redeemCoupon_idempotentRetry_returnsReplayedRedemption() {
        when(couponCodeRepository.findByCode(CODE)).thenReturn(Optional.of(couponInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(THE_SECRET);
        String rawPayload = redemptionPayload(CODE, ORDER_ID, "100.00", CUSTOMER_ID, IDEMPOTENCY_KEY);
        when(signatureVerifier.verify(rawPayload, VALID_SIGNATURE, THE_SECRET)).thenReturn(true);

        CouponRedemption existing =
                CouponRedemption.builder()
                        .id("01HREDEMPTION1234567")
                        .couponId("01HCOUPON1234567890AB")
                        .orderId(ORDER_ID)
                        .orderAmount(ORDER_AMOUNT)
                        .discountApplied(new BigDecimal("20.00"))
                        .idempotencyKey(IDEMPOTENCY_KEY)
                        .build();
        when(redemptionService.redeem(
                        WORKSPACE_ID, CODE, ORDER_ID, ORDER_AMOUNT, CUSTOMER_ID, WORKSPACE_ID + ":" + IDEMPOTENCY_KEY))
                .thenReturn(existing);

        RedemptionWebhookResponse first = controller.redeemCoupon(VALID_SIGNATURE, rawPayload).getBody().data();
        RedemptionWebhookResponse second = controller.redeemCoupon(VALID_SIGNATURE, rawPayload).getBody().data();

        assertEquals(first.redemptionId(), second.redemptionId());
        verify(redemptionService, times(2))
                .redeem(WORKSPACE_ID, CODE, ORDER_ID, ORDER_AMOUNT, CUSTOMER_ID, WORKSPACE_ID + ":" + IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName(
            "redeemCoupon: [SEC: Vikram, P3(b)] two DIFFERENT workspaces submitting the SAME raw"
                    + " idempotencyKey are namespaced into DIFFERENT keys -- no cross-tenant replay/collision")
    void redeemCoupon_sameRawKeyDifferentWorkspaces_namespacedDifferently() {
        // Workspace A's coupon, delivery #1, raw idempotencyKey shared (by coincidence or a
        // malicious probing attempt) with workspace B below.
        when(couponCodeRepository.findByCode(CODE)).thenReturn(Optional.of(couponInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(THE_SECRET);
        String payloadA = redemptionPayload(CODE, ORDER_ID, "100.00", CUSTOMER_ID, IDEMPOTENCY_KEY);
        when(signatureVerifier.verify(payloadA, VALID_SIGNATURE, THE_SECRET)).thenReturn(true);
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID),
                        eq(CODE),
                        eq(ORDER_ID),
                        eq(ORDER_AMOUNT),
                        eq(CUSTOMER_ID),
                        eq(WORKSPACE_ID + ":" + IDEMPOTENCY_KEY)))
                .thenReturn(
                        CouponRedemption.builder()
                                .id("01HREDEMPTION_A")
                                .couponId("01HCOUPON1234567890AB")
                                .orderId(ORDER_ID)
                                .orderAmount(ORDER_AMOUNT)
                                .discountApplied(new BigDecimal("20.00"))
                                .build());

        controller.redeemCoupon(VALID_SIGNATURE, payloadA);

        verify(redemptionService)
                .redeem(
                        eq(WORKSPACE_ID),
                        eq(CODE),
                        eq(ORDER_ID),
                        eq(ORDER_AMOUNT),
                        eq(CUSTOMER_ID),
                        eq(WORKSPACE_ID + ":" + IDEMPOTENCY_KEY));
        // Never forwards the raw, un-namespaced token -- that would be the collision-prone shape.
        verify(redemptionService, never())
                .redeem(eq(WORKSPACE_ID), eq(CODE), eq(ORDER_ID), eq(ORDER_AMOUNT), eq(CUSTOMER_ID), eq(IDEMPOTENCY_KEY));

        // Workspace B's DIFFERENT coupon code, but the SAME raw idempotencyKey string.
        String codeB = "OTHERCODE";
        when(couponCodeRepository.findByCode(codeB)).thenReturn(Optional.of(couponInWorkspace(OTHER_WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(OTHER_WORKSPACE_ID)).thenReturn("other-secret");
        String payloadB = redemptionPayload(codeB, ORDER_ID, "100.00", CUSTOMER_ID, IDEMPOTENCY_KEY);
        when(signatureVerifier.verify(payloadB, VALID_SIGNATURE, "other-secret")).thenReturn(true);
        when(redemptionService.redeem(
                        eq(OTHER_WORKSPACE_ID),
                        eq(codeB),
                        eq(ORDER_ID),
                        eq(ORDER_AMOUNT),
                        eq(CUSTOMER_ID),
                        eq(OTHER_WORKSPACE_ID + ":" + IDEMPOTENCY_KEY)))
                .thenReturn(
                        CouponRedemption.builder()
                                .id("01HREDEMPTION_B")
                                .couponId("01HCOUPON_OTHER123456")
                                .orderId(ORDER_ID)
                                .orderAmount(ORDER_AMOUNT)
                                .discountApplied(new BigDecimal("20.00"))
                                .build());

        controller.redeemCoupon(VALID_SIGNATURE, payloadB);

        verify(redemptionService)
                .redeem(
                        eq(OTHER_WORKSPACE_ID),
                        eq(codeB),
                        eq(ORDER_ID),
                        eq(ORDER_AMOUNT),
                        eq(CUSTOMER_ID),
                        eq(OTHER_WORKSPACE_ID + ":" + IDEMPOTENCY_KEY));
    }

    @Test
    @DisplayName("redeemCoupon: a null idempotency key still propagates RedemptionService's own rejection, after signature passes")
    void redeemCoupon_missingIdempotencyKey_propagatesRejection() {
        when(couponCodeRepository.findByCode(CODE)).thenReturn(Optional.of(couponInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(THE_SECRET);
        String rawPayload = redemptionPayloadNullFields(CODE, ORDER_ID, "100.00");
        when(signatureVerifier.verify(rawPayload, VALID_SIGNATURE, THE_SECRET)).thenReturn(true);
        when(redemptionService.redeem(eq(WORKSPACE_ID), eq(CODE), eq(ORDER_ID), eq(ORDER_AMOUNT), isNull(), isNull()))
                .thenThrow(
                        new ApiException(
                                "IDEMPOTENCY_KEY_REQUIRED",
                                "idempotency_key is required for coupon redemption",
                                HttpStatus.BAD_REQUEST));

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.redeemCoupon(VALID_SIGNATURE, rawPayload));

        assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    // ------------------------------------------------------------------------------------------
    // POST /webhooks/conversion -- two-hop workspace resolution + signature verification
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("recordConversion: valid signature (resolved via UtmCampaign -> Campaign -> workspaceId) delegates to ConversionTrackingService")
    void recordConversion_validSignature_delegatesToService() {
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utmForCampaign(CAMPAIGN_ID)));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaignInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(THE_SECRET);
        String rawPayload = conversionPayload(UTM_ID, ORDER_ID, "100.00", IDEMPOTENCY_KEY);
        when(signatureVerifier.verify(rawPayload, VALID_SIGNATURE, THE_SECRET)).thenReturn(true);

        ResponseEntity<ApiResponse<com.influora.web.dto.tracking.WebhookDtos.ConversionWebhookResponse>> response =
                controller.recordConversion(VALID_SIGNATURE, rawPayload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().data().recorded());
        verify(conversionTrackingService, times(1))
                .recordConversion(WORKSPACE_ID, UTM_ID, ORDER_ID, ORDER_AMOUNT, IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("recordConversion: REJECTS (401) when the signature is invalid -- never reaches ConversionTrackingService")
    void recordConversion_invalidSignature_rejected() {
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utmForCampaign(CAMPAIGN_ID)));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaignInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(THE_SECRET);
        String rawPayload = conversionPayload(UTM_ID, ORDER_ID, "100.00", IDEMPOTENCY_KEY);
        when(signatureVerifier.verify(rawPayload, "bad-signature", THE_SECRET)).thenReturn(false);

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.recordConversion("bad-signature", rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verify(conversionTrackingService, never())
                .recordConversion(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("recordConversion: REJECTS (401) when the signature header is missing/null")
    void recordConversion_missingSignature_rejected() {
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utmForCampaign(CAMPAIGN_ID)));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaignInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(THE_SECRET);
        String rawPayload = conversionPayload(UTM_ID, ORDER_ID, "100.00", IDEMPOTENCY_KEY);
        when(signatureVerifier.verify(rawPayload, null, THE_SECRET)).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> controller.recordConversion(null, rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        verify(conversionTrackingService, never())
                .recordConversion(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("recordConversion: REJECTS (401) when the signature was computed over a tampered (different amount) payload")
    void recordConversion_tamperedPayload_rejected() {
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utmForCampaign(CAMPAIGN_ID)));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaignInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(THE_SECRET);
        String tamperedPayload = conversionPayload(UTM_ID, ORDER_ID, "0.01", IDEMPOTENCY_KEY);
        when(signatureVerifier.verify(tamperedPayload, VALID_SIGNATURE, THE_SECRET)).thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> controller.recordConversion(VALID_SIGNATURE, tamperedPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        verify(conversionTrackingService, never())
                .recordConversion(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("recordConversion: an unknown utmCampaignId is rejected as INVALID_WEBHOOK_SIGNATURE, same as a bad signature (no enumeration)")
    void recordConversion_unknownUtmId_rejectedSameAsInvalidSignature() {
        when(utmCampaignRepository.findById("bad-id")).thenReturn(Optional.empty());
        when(secretService.decryptSecretForWorkspace(null)).thenReturn(null);
        String rawPayload = conversionPayload("bad-id", ORDER_ID, "100.00", IDEMPOTENCY_KEY);

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.recordConversion(VALID_SIGNATURE, rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verify(campaignRepository, never()).findById(anyString());
        verify(conversionTrackingService, never())
                .recordConversion(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("recordConversion: a UTM row whose campaign no longer resolves (orphaned campaignId) is rejected the same way")
    void recordConversion_campaignNotFound_rejectedSameAsInvalidSignature() {
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utmForCampaign(CAMPAIGN_ID)));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());
        when(secretService.decryptSecretForWorkspace(null)).thenReturn(null);
        String rawPayload = conversionPayload(UTM_ID, ORDER_ID, "100.00", IDEMPOTENCY_KEY);

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.recordConversion(VALID_SIGNATURE, rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        verify(conversionTrackingService, never())
                .recordConversion(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("recordConversion: a resolved workspace with NO configured secret is rejected (fail closed)")
    void recordConversion_workspaceWithNoSecret_rejected() {
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utmForCampaign(CAMPAIGN_ID)));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaignInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(null);
        String rawPayload = conversionPayload(UTM_ID, ORDER_ID, "100.00", IDEMPOTENCY_KEY);

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.recordConversion(VALID_SIGNATURE, rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        verify(signatureVerifier, never()).verify(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("recordConversion: malformed JSON body is rejected as INVALID_WEBHOOK_PAYLOAD before any lookup")
    void recordConversion_malformedJson_rejected() {
        ApiException ex =
                assertThrows(
                        ApiException.class, () -> controller.recordConversion(VALID_SIGNATURE, "{not valid json"));

        assertEquals("INVALID_WEBHOOK_PAYLOAD", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(utmCampaignRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("recordConversion: a workspace signing with ANOTHER workspace's UTM id still resolves the UTM's TRUE owning workspace, never the caller's assumption")
    void recordConversion_resolvesTrueOwningWorkspace_notAnAssumedOne() {
        // UTM_ID genuinely belongs to WORKSPACE_ID's campaign; a signature computed with
        // OTHER_WORKSPACE_ID's secret must NOT verify, proving the controller resolves and checks
        // against the UTM's real owning workspace rather than trusting any caller-implied identity.
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utmForCampaign(CAMPAIGN_ID)));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaignInWorkspace(WORKSPACE_ID)));
        when(secretService.decryptSecretForWorkspace(WORKSPACE_ID)).thenReturn(THE_SECRET);
        String rawPayload = conversionPayload(UTM_ID, ORDER_ID, "100.00", IDEMPOTENCY_KEY);
        // Signed with a DIFFERENT workspace's secret -- verify() is stubbed to reflect that this
        // would genuinely fail against WORKSPACE_ID's real secret.
        when(signatureVerifier.verify(rawPayload, VALID_SIGNATURE, THE_SECRET)).thenReturn(false);

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.recordConversion(VALID_SIGNATURE, rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        verify(secretService, never()).decryptSecretForWorkspace(OTHER_WORKSPACE_ID);
    }

    // ------------------------------------------------------------------------------------------
    // GET /track/click/{utmCampaignId} -- unaffected by the signature-verification fix
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("trackClick records the click then 302-redirects to the link's real destination")
    void trackClick_recordsClickAndRedirects() {
        UtmCampaign utm = utmForCampaign(CAMPAIGN_ID);
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utm));

        ResponseEntity<Void> response = controller.trackClick(UTM_ID, "visitor-1");

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(utm.getFullTrackingUrl(), response.getHeaders().getLocation().toString());
        verify(campaignLinkService, times(1)).recordClick(UTM_ID, "visitor-1");
    }

    @Test
    @DisplayName("trackClick with an unknown utmCampaignId propagates UTM_NOT_FOUND cleanly (404, no leak)")
    void trackClick_unknownId_propagatesNotFound() {
        org.mockito.Mockito.doThrow(
                        new ApiException("UTM_NOT_FOUND", "Tracking link not found", HttpStatus.NOT_FOUND))
                .when(campaignLinkService)
                .recordClick("bad-id", "visitor-1");

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.trackClick("bad-id", "visitor-1"));
        assertEquals("UTM_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(utmCampaignRepository, never()).findById("bad-id");
    }

    @Test
    @DisplayName("trackClick accepts a null visitorId (anonymous click, no correlation id)")
    void trackClick_nullVisitorId_stillRedirects() {
        UtmCampaign utm = utmForCampaign(CAMPAIGN_ID);
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utm));

        ResponseEntity<Void> response = controller.trackClick(UTM_ID, null);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        verify(campaignLinkService, times(1)).recordClick(UTM_ID, null);
    }

    // ------------------------------------------------------------------------------------------
    // Payload builders -- literal JSON strings, mirroring ShopifyWebhookControllerTest/
    // WooCommerceWebhookControllerTest's raw-payload style (needed since HMAC verification is over
    // the exact raw bytes, not a deserialized object).
    // ------------------------------------------------------------------------------------------

    private static String redemptionPayload(
            String code, String orderId, String orderAmount, String customerId, String idempotencyKey) {
        return "{\"code\":\""
                + code
                + "\",\"orderId\":\""
                + orderId
                + "\",\"orderAmount\":"
                + orderAmount
                + ",\"customerId\":\""
                + customerId
                + "\",\"idempotencyKey\":\""
                + idempotencyKey
                + "\"}";
    }

    private static String redemptionPayloadNullFields(String code, String orderId, String orderAmount) {
        return "{\"code\":\""
                + code
                + "\",\"orderId\":\""
                + orderId
                + "\",\"orderAmount\":"
                + orderAmount
                + ",\"customerId\":null,\"idempotencyKey\":null}";
    }

    private static String conversionPayload(
            String utmCampaignId, String orderId, String orderAmount, String idempotencyKey) {
        return "{\"utmCampaignId\":\""
                + utmCampaignId
                + "\",\"orderId\":\""
                + orderId
                + "\",\"orderAmount\":"
                + orderAmount
                + ",\"idempotencyKey\":\""
                + idempotencyKey
                + "\"}";
    }
}
