package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CouponRedemption;
import com.influora.domain.entity.WooCommerceIntegration;
import com.influora.integration.woocommerce.WooCommerceIntegrationService;
import com.influora.integration.woocommerce.webhook.WooCommerceWebhookSignatureVerifier;
import com.influora.repository.WooCommerceIntegrationRepository;
import com.influora.service.IdempotencyService;
import com.influora.service.tracking.RedemptionService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * [SEC: Kabir load-bearing] Unit tests for {@link WooCommerceWebhookController} -- the task brief's
 * required webhook signature test coverage (valid/invalid/tampered/missing), workspace-scoped
 * redemption (hostile cross-tenant webhook test mirroring D1's), and idempotency/replay coverage.
 * Mirrors {@code ShopifyWebhookControllerTest}'s direct-Java-call style (no MockMvc/Spring context
 * in this codebase's test suite) and its exact hostile-cross-tenant regression-test shape.
 *
 * <p>{@link IdempotencyService} is mocked here (its own dedup/race-handling guarantees are already
 * proven by {@code IdempotencyServiceTest}) -- the {@code executeOnce} stub genuinely invokes the
 * supplied {@link Supplier} so the "does the controller actually call RedemptionService with the
 * right args" assertions stay meaningful.
 *
 * <p><b>[SEC: Wave D2 -- built workspace-scoped from the start, not a follow-up fix]</b> the
 * controller calls the WORKSPACE-SCOPED {@link RedemptionService#redeem(String, String, String,
 * BigDecimal, String, String)} overload (6 args, {@code workspaceId} first) from day one -- unlike
 * D1's Shopify integration, which shipped with the legacy global 5-arg overload and had to be
 * fixed after Kabir's red-team pass found the cross-tenant coupon HIGH (see {@code
 * wiki/errors/wave-d1-shopify-integration-D1-final-verdict.md}). See {@code
 * receive_hostileWebhook_crossTenantCouponCode_isRejected} below for the regression test proving
 * this integration never reaches the legacy overload.
 */
@ExtendWith(MockitoExtension.class)
class WooCommerceWebhookControllerTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String OTHER_WORKSPACE_ID = "01HOTHERWORKSPACE1234A";
    private static final String SITE_URL = "https://my-test-store.example.com";
    private static final String VALID_SIGNATURE = "valid-signature-stub";

    @Mock private WooCommerceWebhookSignatureVerifier signatureVerifier;
    @Mock private WooCommerceIntegrationRepository integrationRepository;
    @Mock private WooCommerceIntegrationService integrationService;
    @Mock private RedemptionService redemptionService;
    @Mock private IdempotencyService idempotencyService;

    private WooCommerceWebhookController controller;

    @BeforeEach
    void setUp() {
        controller =
                new WooCommerceWebhookController(
                        signatureVerifier,
                        integrationRepository,
                        integrationService,
                        redemptionService,
                        idempotencyService);
    }

    private WooCommerceIntegration activeIntegration() {
        return WooCommerceIntegration.builder()
                .id("01HWOOCOMMERCEINTEGR1")
                .workspaceId(WORKSPACE_ID)
                .siteUrl(SITE_URL)
                .encryptedWebhookSecret("encrypted")
                .build();
    }

    /** Stubs idempotencyService.executeOnce to genuinely invoke the supplied action, mirroring the real bean's behavior on the happy (non-duplicate) path. */
    @SuppressWarnings("unchecked")
    private void stubIdempotencyServiceRunsAction() {
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(3)).get());
    }

    private void stubResolvedIntegrationAndDecryptedSecret() {
        when(integrationRepository.findBySiteUrlAndRevokedFalse(SITE_URL))
                .thenReturn(Optional.of(activeIntegration()));
        when(integrationService.decryptSecret(any(WooCommerceIntegration.class))).thenReturn("the-secret");
    }

    // ------------------------------------------------------------------------------------------
    // Site resolution [must happen before signature verification -- see controller javadoc]
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("receive: rejects (404) a site url with no active connection, BEFORE any signature check")
    void receive_unknownSite_rejectedBeforeSignatureCheck() {
        when(integrationRepository.findBySiteUrlAndRevokedFalse(SITE_URL)).thenReturn(Optional.empty());

        String rawPayload = "{\"id\":123,\"total\":\"10.00\"}";

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.receive(VALID_SIGNATURE, SITE_URL, "order.updated", rawPayload));

        assertEquals("SITE_NOT_CONNECTED", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(signatureVerifier, never()).verify(anyString(), anyString(), anyString());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("receive: rejects a missing site url header before any lookup")
    void receive_missingSiteUrl_rejected() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.receive(VALID_SIGNATURE, "", "order.updated", "{\"id\":1,\"total\":\"1.00\"}"));

        assertEquals("MISSING_SITE_URL", ex.getCode());
        verify(integrationRepository, never()).findBySiteUrlAndRevokedFalse(anyString());
    }

    @Test
    @DisplayName("receive: a site url differing only by trailing slash / case still resolves the SAME stored integration")
    void receive_siteUrlNormalization_resolvesSameIntegration() {
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq("the-secret"))).thenReturn(true);

        controller.receive(VALID_SIGNATURE, "HTTPS://My-Test-Store.Example.com/", "order.updated", "{\"id\":1,\"total\":\"1.00\"}");

        verify(integrationRepository, times(1)).findBySiteUrlAndRevokedFalse(SITE_URL);
    }

    // ------------------------------------------------------------------------------------------
    // Signature verification [SEC: load-bearing]
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("receive: REJECTS the request (401) when signature verification fails — never reaches RedemptionService")
    void receive_invalidSignature_rejectedBeforeAnyProcessing() {
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq("bad-signature"), eq("the-secret"))).thenReturn(false);

        String rawPayload = "{\"id\":123,\"total\":\"10.00\"}";

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.receive("bad-signature", SITE_URL, "order.updated", rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("receive: REJECTS the request (401) when the signature header is missing/null")
    void receive_missingSignature_rejected() {
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq(null), eq("the-secret"))).thenReturn(false);

        String rawPayload = "{\"id\":123,\"total\":\"10.00\"}";

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> controller.receive(null, SITE_URL, "order.updated", rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("receive: REJECTS the request (401) when the signature was computed over a tampered payload")
    void receive_tamperedPayload_rejected() {
        stubResolvedIntegrationAndDecryptedSecret();
        // The verifier itself is responsible for detecting tampering (proven in
        // WooCommerceWebhookSignatureVerifierTest) -- here we prove the controller correctly
        // propagates a false verify() result into a 401, using the actual raw payload the
        // "attacker" tampered with as the argument the (mocked) verifier receives.
        String tamperedPayload = "{\"id\":123,\"total\":\"999999.99\"}";
        when(signatureVerifier.verify(eq(tamperedPayload), eq(VALID_SIGNATURE), eq("the-secret")))
                .thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.receive(VALID_SIGNATURE, SITE_URL, "order.updated", tamperedPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // Topic routing
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("receive: acknowledges (200) but does NOT process an unhandled topic")
    void receive_unhandledTopic_acknowledgedButNotProcessed() {
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq("the-secret"))).thenReturn(true);

        ResponseEntity<Void> response =
                controller.receive(VALID_SIGNATURE, SITE_URL, "order.deleted", "{\"id\":123,\"total\":\"10.00\"}");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("receive: acknowledges (200) but does not process an order with no coupon code")
    void receive_orderWithNoCouponCode_acknowledgedButNotProcessed() {
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq("the-secret"))).thenReturn(true);

        ResponseEntity<Void> response =
                controller.receive(VALID_SIGNATURE, SITE_URL, "order.updated", "{\"id\":123,\"total\":\"10.00\"}");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // Happy path -> RedemptionService routing
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("receive: order.updated with a coupon code routes to the WORKSPACE-SCOPED RedemptionService.redeem with the resolved workspaceId and derived idempotency key")
    void receive_orderUpdatedWithCouponCode_routesToRedemptionService() {
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq("the-secret"))).thenReturn(true);
        stubIdempotencyServiceRunsAction();

        CouponRedemption redemption =
                CouponRedemption.builder()
                        .id("01HREDEMPTION1234567")
                        .couponId("01HCOUPON1234567890AB")
                        .orderId("531")
                        .orderAmount(new BigDecimal("30.00"))
                        .discountApplied(new BigDecimal("10.00"))
                        .idempotencyKey("woocommerce:whatever")
                        .build();
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID),
                        eq("CREATOR10"),
                        eq("531"),
                        eq(new BigDecimal("30.00")),
                        eq(null),
                        anyString()))
                .thenReturn(redemption);

        String rawPayload = "{\"id\":531,\"total\":\"30.00\",\"coupon_lines\":[{\"code\":\"CREATOR10\"}]}";

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, SITE_URL, "order.updated", rawPayload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(redemptionService, times(1))
                .redeem(
                        eq(WORKSPACE_ID),
                        eq("CREATOR10"),
                        eq("531"),
                        eq(new BigDecimal("30.00")),
                        eq(null),
                        anyString());
        // The idempotency reservation is scoped to the RESOLVED workspace, never a caller-supplied one.
        verify(idempotencyService, times(1))
                .executeOnce(anyString(), eq(WORKSPACE_ID), eq("woocommerce.webhook"), any(Supplier.class));
    }

    @Test
    @DisplayName("receive: order.created with a coupon code also routes to the workspace-scoped RedemptionService.redeem")
    void receive_orderCreatedWithCouponCode_routesToRedemptionService() {
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq("the-secret"))).thenReturn(true);
        stubIdempotencyServiceRunsAction();

        CouponRedemption redemption =
                CouponRedemption.builder()
                        .id("01HREDEMPTION7654321")
                        .couponId("01HCOUPON1234567890AB")
                        .orderId("111")
                        .orderAmount(new BigDecimal("20.00"))
                        .discountApplied(new BigDecimal("5.00"))
                        .idempotencyKey("woocommerce:whatever")
                        .build();
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID), anyString(), anyString(), any(BigDecimal.class), eq(null), anyString()))
                .thenReturn(redemption);

        String rawPayload = "{\"id\":111,\"total\":\"20.00\",\"coupon_lines\":[{\"code\":\"SAVE5\"}]}";

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, SITE_URL, "order.created", rawPayload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(redemptionService, times(1))
                .redeem(eq(WORKSPACE_ID), eq("SAVE5"), eq("111"), eq(new BigDecimal("20.00")), eq(null), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // [SEC: Wave D2 -- built-in from the start, mirroring D1's HIGH regression test] Cross-tenant
    // coupon redemption via a hostile-but-legitimately-signed webhook. Brand A's own real
    // WooCommerce site connection (so their webhook signature is genuinely valid against their own
    // stored secret), sends a webhook whose coupon_lines[0].code happens to match a coupon that
    // actually belongs to Brand B's workspace. The controller MUST call the workspace-scoped
    // overload with Brand A's own resolved workspaceId; RedemptionService itself (proven in
    // RedemptionServiceTest) is responsible for rejecting the cross-workspace match as
    // INVALID_CODE. This test's job is narrower and controller-scoped: prove the controller passes
    // ITS OWN resolved workspaceId (never the coupon's, never none) into RedemptionService, and
    // prove that rejection propagates as a real error rather than being swallowed into a
    // false-success 200.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "receive: hostile webhook — signed by Brand A's own connected site, carrying a coupon"
                    + " code that belongs to Brand B's workspace — is REJECTED, not processed; the"
                    + " controller passes ITS OWN resolved workspaceId (Brand A's) into"
                    + " RedemptionService, never silently succeeding on a cross-tenant match")
    void receive_hostileWebhook_crossTenantCouponCode_isRejected() {
        // Brand A's own legitimately-connected site -- the signature IS genuinely valid for this
        // site (verified against Brand A's own stored secret), and siteUrl resolves to Brand A's
        // OWN workspace (WORKSPACE_ID), never Brand B's (OTHER_WORKSPACE_ID). The attacker does not
        // control this resolution at all.
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq("the-secret"))).thenReturn(true);
        stubIdempotencyServiceRunsAction();

        // Brand A crafts the order payload with a coupon code they know/guess belongs to Brand B's
        // campaign. RedemptionService (workspace-scoped lookup) is stubbed here to behave exactly
        // as the real service would: a code that exists only in a DIFFERENT workspace than the one
        // passed in is indistinguishable from "does not exist" -- INVALID_CODE, 404.
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID), // Brand A's OWN resolved workspace -- never Brand B's
                        eq("BRAND_B_SUMMER25"),
                        anyString(),
                        any(BigDecimal.class),
                        eq(null),
                        anyString()))
                .thenThrow(new ApiException("INVALID_CODE", "Coupon code not found", HttpStatus.NOT_FOUND));

        String hostilePayload =
                "{\"id\":999888777,\"total\":\"499.99\",\"coupon_lines\":[{\"code\":\"BRAND_B_SUMMER25\"}]}";

        // The forged webhook's processing fails loudly (ApiException bubbling out of the
        // idempotency-wrapped supplier) -- it does NOT come back as a quiet 200 that would let
        // Brand A's forged order silently increment Brand B's coupon usage count.
        assertThrows(
                ApiException.class,
                () -> controller.receive(VALID_SIGNATURE, SITE_URL, "order.updated", hostilePayload));

        // The load-bearing assertion: the controller called RedemptionService with BRAND A's OWN
        // resolved workspaceId (WORKSPACE_ID) -- it never substituted, guessed, or omitted a
        // workspace scope, and never called the legacy global overload for this webhook surface at
        // all.
        verify(redemptionService, times(1))
                .redeem(
                        eq(WORKSPACE_ID),
                        eq("BRAND_B_SUMMER25"),
                        anyString(),
                        any(BigDecimal.class),
                        eq(null),
                        anyString());
        verify(redemptionService, never())
                .redeem(eq(OTHER_WORKSPACE_ID), anyString(), anyString(), any(), any(), anyString());
        // Never falls back to the legacy global 5-arg overload either.
        verify(redemptionService, never()).redeem(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName(
            "receive: legitimate same-workspace redemption still works — a site redeeming its OWN"
                    + " workspace's coupon code succeeds")
    void receive_legitimateSameWorkspaceRedemption_stillWorks() {
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq("the-secret"))).thenReturn(true);
        stubIdempotencyServiceRunsAction();

        CouponRedemption redemption =
                CouponRedemption.builder()
                        .id("01HREDEMPTION7654321")
                        .couponId("01HCOUPONOWNEDBYSITEA")
                        .orderId("42")
                        .orderAmount(new BigDecimal("80.00"))
                        .discountApplied(new BigDecimal("16.00"))
                        .idempotencyKey("woocommerce:whatever")
                        .build();
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID),
                        eq("OWNCODE20"),
                        eq("42"),
                        eq(new BigDecimal("80.00")),
                        eq(null),
                        anyString()))
                .thenReturn(redemption);

        String rawPayload = "{\"id\":42,\"total\":\"80.00\",\"coupon_lines\":[{\"code\":\"OWNCODE20\"}]}";

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, SITE_URL, "order.updated", rawPayload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(redemptionService, times(1))
                .redeem(
                        eq(WORKSPACE_ID),
                        eq("OWNCODE20"),
                        eq("42"),
                        eq(new BigDecimal("80.00")),
                        eq(null),
                        anyString());
    }

    // ------------------------------------------------------------------------------------------
    // Idempotency / replay
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("receive: a replayed delivery (AlreadyCompletedException) is a clean 200 no-op, RedemptionService is never re-invoked by this path")
    @SuppressWarnings("unchecked")
    void receive_replayedDelivery_isCleanNoOp() {
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq("the-secret"))).thenReturn(true);
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenThrow(new IdempotencyService.AlreadyCompletedException("woocommerce:somekey"));

        String rawPayload = "{\"id\":531,\"total\":\"30.00\",\"coupon_lines\":[{\"code\":\"CREATOR10\"}]}";

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, SITE_URL, "order.updated", rawPayload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("receive: a concurrently in-progress delivery (AlreadyInProgressException) is also a clean 200 no-op")
    @SuppressWarnings("unchecked")
    void receive_concurrentInProgressDelivery_isCleanNoOp() {
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq("the-secret"))).thenReturn(true);
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenThrow(new IdempotencyService.AlreadyInProgressException("woocommerce:somekey"));

        String rawPayload = "{\"id\":123,\"total\":\"10.00\",\"coupon_lines\":[{\"code\":\"X\"}]}";

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, SITE_URL, "order.updated", rawPayload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("receive: the SAME order delivered twice derives the SAME idempotency key both times (proves replay dedup is even possible)")
    @SuppressWarnings("unchecked")
    void receive_sameOrderTwice_derivesSameIdempotencyKey() {
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq("the-secret"))).thenReturn(true);
        stubIdempotencyServiceRunsAction();

        CouponRedemption redemption =
                CouponRedemption.builder()
                        .id("id")
                        .couponId("coupon")
                        .orderId("555")
                        .orderAmount(new BigDecimal("15.00"))
                        .discountApplied(new BigDecimal("2.00"))
                        .idempotencyKey("woocommerce:whatever")
                        .build();
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID), anyString(), anyString(), any(BigDecimal.class), eq(null), anyString()))
                .thenReturn(redemption);

        String rawPayload = "{\"id\":555,\"total\":\"15.00\",\"coupon_lines\":[{\"code\":\"CODE555\"}]}";

        controller.receive(VALID_SIGNATURE, SITE_URL, "order.updated", rawPayload);
        controller.receive(VALID_SIGNATURE, SITE_URL, "order.updated", rawPayload);

        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, times(2))
                .executeOnce(keyCaptor.capture(), eq(WORKSPACE_ID), eq("woocommerce.webhook"), any(Supplier.class));
        assertEquals(keyCaptor.getAllValues().get(0), keyCaptor.getAllValues().get(1));

        org.mockito.ArgumentCaptor<String> redeemKeyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redemptionService, times(2))
                .redeem(
                        eq(WORKSPACE_ID),
                        eq("CODE555"),
                        eq("555"),
                        eq(new BigDecimal("15.00")),
                        eq(null),
                        redeemKeyCaptor.capture());
        assertEquals(redeemKeyCaptor.getAllValues().get(0), redeemKeyCaptor.getAllValues().get(1));
        assertEquals(keyCaptor.getAllValues().get(0), redeemKeyCaptor.getAllValues().get(0));
    }
}
