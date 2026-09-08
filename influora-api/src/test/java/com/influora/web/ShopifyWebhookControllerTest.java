package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CouponRedemption;
import com.influora.domain.entity.ShopifyIntegration;
import com.influora.integration.shopify.ShopifyOrderOwnershipVerifier;
import com.influora.integration.shopify.exception.ShopifyApiException;
import com.influora.integration.shopify.webhook.ShopifyWebhookSignatureVerifier;
import com.influora.repository.ShopifyIntegrationRepository;
import com.influora.service.IdempotencyService;
import com.influora.service.tracking.RedemptionService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * [SEC: Kabir load-bearing] Unit tests for {@link ShopifyWebhookController} — the task brief's
 * required webhook signature test coverage (valid accepted, invalid/missing rejected,
 * replay/idempotency proven) at the controller level, on top of {@code
 * ShopifyWebhookSignatureVerifierTest}'s lower-level HMAC coverage. Mirrors {@code
 * ConversionWebhookControllerTest}'s direct-Java-call style (no MockMvc/Spring context in this
 * codebase's test suite — see that test's javadoc).
 *
 * <p>{@link IdempotencyService} is mocked here (its own dedup/race-handling guarantees are already
 * proven by {@code IdempotencyServiceTest}, not re-proven here) — the {@code executeOnce} stub
 * genuinely invokes the supplied {@link Supplier} so the "does the controller actually call
 * RedemptionService with the right args" assertions stay meaningful, mirroring how {@code
 * ConversionTrackingServiceTest}/{@code RedemptionServiceTest} stub the real {@code
 * IdempotencyService} bean directly rather than re-implementing its logic in a mock.
 *
 * <p><b>[SEC: Kabir, Wave D1 HIGH — FIXED]</b> the controller now calls the WORKSPACE-SCOPED
 * {@link RedemptionService#redeem(String, String, String, BigDecimal, String, String)} overload
 * (6 args, {@code workspaceId} first), passing the resolved {@link ShopifyIntegration}'s {@code
 * workspaceId} — never the legacy global 5-arg overload. All {@code redeem(...)} stubs/verifies
 * below were updated to the 6-arg signature accordingly; see {@code
 * receive_hostileWebhook_crossTenantCouponCode_isRejected} for the regression test proving the
 * fix itself (a signed webhook from Brand A's store cannot redeem Brand B's coupon).
 */
@ExtendWith(MockitoExtension.class)
class ShopifyWebhookControllerTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String OTHER_WORKSPACE_ID = "01HOTHERWORKSPACE1234A";
    private static final String SHOP_DOMAIN = "my-test-store.myshopify.com";
    private static final String VALID_SIGNATURE = "valid-signature-stub";

    @Mock private ShopifyWebhookSignatureVerifier signatureVerifier;
    @Mock private ShopifyIntegrationRepository shopifyIntegrationRepository;
    @Mock private RedemptionService redemptionService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private ShopifyOrderOwnershipVerifier orderOwnershipVerifier;

    private ShopifyWebhookController controller;

    @BeforeEach
    void setUp() {
        controller =
                new ShopifyWebhookController(
                        signatureVerifier,
                        shopifyIntegrationRepository,
                        redemptionService,
                        idempotencyService,
                        orderOwnershipVerifier);

        // [F-0726] Default: the order genuinely belongs to the shop that sent the delivery, so
        // every pre-existing test keeps exercising the path it was written for. lenient() because
        // the tests that stop earlier (bad signature, unknown shop, no coupon) never reach it.
        lenient()
                .when(orderOwnershipVerifier.orderBelongsToShop(anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    private ShopifyIntegration activeIntegration() {
        return ShopifyIntegration.builder()
                .id("01HSHOPIFYINTEGRATION1")
                .workspaceId(WORKSPACE_ID)
                .shopDomain(SHOP_DOMAIN)
                .encryptedAccessToken("encrypted")
                .build();
    }

    /** Stubs idempotencyService.executeOnce to genuinely invoke the supplied action, mirroring the real bean's behavior on the happy (non-duplicate) path. */
    @SuppressWarnings("unchecked")
    private void stubIdempotencyServiceRunsAction() {
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(3)).get());
    }

    // ------------------------------------------------------------------------------------------
    // Signature verification [SEC: load-bearing]
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("receive: REJECTS the request (401) when signature verification fails — never reaches RedemptionService")
    void receive_invalidSignature_rejectedBeforeAnyProcessing() {
        when(signatureVerifier.verify(anyString(), eq("bad-signature"), eq(null))).thenReturn(false);

        String rawPayload = "{\"id\":123,\"total_price\":\"10.00\"}";

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.receive("bad-signature", SHOP_DOMAIN, "orders/paid", rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verify(shopifyIntegrationRepository, never()).findByShopDomainAndRevokedFalse(anyString());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("receive: REJECTS the request (401) when the signature header is missing/null")
    void receive_missingSignature_rejected() {
        when(signatureVerifier.verify(anyString(), eq(null), eq(null))).thenReturn(false);

        String rawPayload = "{\"id\":123,\"total_price\":\"10.00\"}";

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> controller.receive(null, SHOP_DOMAIN, "orders/paid", rawPayload));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // Shop resolution
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("receive: rejects (404) a shop domain with no active connection, even with a valid signature")
    void receive_unknownShop_rejected() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.empty());

        String rawPayload = "{\"id\":123,\"total_price\":\"10.00\"}";

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", rawPayload));

        assertEquals("SHOP_NOT_CONNECTED", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("receive: rejects a missing shop domain header even with a valid signature")
    void receive_missingShopDomain_rejected() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.receive(VALID_SIGNATURE, "", "orders/paid", "{\"id\":1,\"total_price\":\"1.00\"}"));

        assertEquals("MISSING_SHOP_DOMAIN", ex.getCode());
    }

    // ------------------------------------------------------------------------------------------
    // Topic routing
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("receive: acknowledges (200) but does NOT process an unhandled topic")
    void receive_unhandledTopic_acknowledgedButNotProcessed() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));

        ResponseEntity<Void> response =
                controller.receive(
                        VALID_SIGNATURE, SHOP_DOMAIN, "app/uninstalled", "{\"id\":123,\"total_price\":\"10.00\"}");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("receive: acknowledges (200) but does not process an order with no discount code")
    void receive_orderWithNoDiscountCode_acknowledgedButNotProcessed() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));

        ResponseEntity<Void> response =
                controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", "{\"id\":123,\"total_price\":\"10.00\"}");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // Happy path -> RedemptionService routing
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("receive: orders/paid with a discount code routes to the WORKSPACE-SCOPED RedemptionService.redeem with the resolved workspaceId and derived idempotency key")
    void receive_ordersPaidWithDiscountCode_routesToRedemptionService() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));
        stubIdempotencyServiceRunsAction();

        CouponRedemption redemption =
                CouponRedemption.builder()
                        .id("01HREDEMPTION1234567")
                        .couponId("01HCOUPON1234567890AB")
                        .orderId("820982911946154508")
                        .orderAmount(new BigDecimal("49.99"))
                        .discountApplied(new BigDecimal("10.00"))
                        .idempotencyKey("shopify:whatever")
                        .build();
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID),
                        eq("PRIYA_SUMMER25"),
                        eq("820982911946154508"),
                        eq(new BigDecimal("49.99")),
                        eq(null),
                        anyString()))
                .thenReturn(redemption);

        String rawPayload =
                "{\"id\":820982911946154508,\"total_price\":\"49.99\",\"discount_codes\":[{\"code\":\"PRIYA_SUMMER25\",\"amount\":\"10.00\"}]}";

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", rawPayload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(redemptionService, times(1))
                .redeem(
                        eq(WORKSPACE_ID),
                        eq("PRIYA_SUMMER25"),
                        eq("820982911946154508"),
                        eq(new BigDecimal("49.99")),
                        eq(null),
                        anyString());
        // The idempotency reservation is scoped to the RESOLVED workspace, never a caller-supplied one.
        verify(idempotencyService, times(1))
                .executeOnce(anyString(), eq(WORKSPACE_ID), eq("shopify.webhook"), any(Supplier.class));
    }

    @Test
    @DisplayName("receive: orders/create with a discount code also routes to the workspace-scoped RedemptionService.redeem")
    void receive_ordersCreateWithDiscountCode_routesToRedemptionService() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));
        stubIdempotencyServiceRunsAction();

        CouponRedemption redemption =
                CouponRedemption.builder()
                        .id("01HREDEMPTION1234567")
                        .couponId("01HCOUPON1234567890AB")
                        .orderId("111")
                        .orderAmount(new BigDecimal("20.00"))
                        .discountApplied(new BigDecimal("5.00"))
                        .idempotencyKey("shopify:whatever")
                        .build();
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID), anyString(), anyString(), any(BigDecimal.class), eq(null), anyString()))
                .thenReturn(redemption);

        String rawPayload = "{\"id\":111,\"total_price\":\"20.00\",\"discount_codes\":[{\"code\":\"SAVE5\",\"amount\":\"5.00\"}]}";

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/create", rawPayload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(redemptionService, times(1))
                .redeem(eq(WORKSPACE_ID), eq("SAVE5"), eq("111"), eq(new BigDecimal("20.00")), eq(null), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // [SEC: Kabir, Wave D1 HIGH — regression test for the FIX] Cross-tenant coupon redemption via
    // a hostile-but-legitimately-signed webhook. Brand A completes their own real Shopify OAuth
    // connect (so their webhook signature is genuinely valid), then sends a webhook whose
    // discount_codes[0].code happens to match a coupon that actually belongs to Brand B's
    // workspace. Before the fix, the controller discarded the resolved workspaceId and called the
    // GLOBAL RedemptionService#redeem overload, so this forged order could redeem Brand B's coupon
    // and trigger an incorrect affiliate-commission accrual. After the fix, the controller MUST
    // call the workspace-scoped overload with Brand A's own resolved workspaceId; RedemptionService
    // itself (proven in RedemptionServiceTest) is responsible for rejecting the cross-workspace
    // match as INVALID_CODE. This test's job is narrower and controller-scoped: prove the
    // controller passes ITS OWN resolved workspaceId (never the coupon's, never none) into
    // RedemptionService, and prove that when RedemptionService legitimately rejects the code (as it
    // would for a real cross-tenant mismatch), that rejection propagates as a real error rather
    // than being swallowed into a false-success 200.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "receive: hostile webhook — signed by Brand A's own connected store, carrying a discount"
                    + " code that belongs to Brand B's workspace — is REJECTED, not processed; the"
                    + " controller passes ITS OWN resolved workspaceId (Brand A's) into"
                    + " RedemptionService, never silently succeeding on a cross-tenant match")
    void receive_hostileWebhook_crossTenantCouponCode_isRejected() {
        // Brand A's own legitimately-connected store — the signature IS genuinely valid for this
        // shop, and shopDomain resolves to Brand A's OWN workspace (WORKSPACE_ID), never Brand B's
        // (OTHER_WORKSPACE_ID). The attacker does not control this resolution at all.
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));
        stubIdempotencyServiceRunsAction();

        // Brand A crafts the order payload with a discount code they know/guess belongs to Brand
        // B's campaign (e.g. a common promo-style slug). RedemptionService (workspace-scoped
        // lookup) is stubbed here to behave exactly as the real, fixed service would: a code that
        // exists only in a DIFFERENT workspace than the one passed in is indistinguishable from
        // "does not exist" -- INVALID_CODE, 404.
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID), // Brand A's OWN resolved workspace -- never Brand B's
                        eq("BRAND_B_SUMMER25"),
                        anyString(),
                        any(BigDecimal.class),
                        eq(null),
                        anyString()))
                .thenThrow(new ApiException("INVALID_CODE", "Coupon code not found", HttpStatus.NOT_FOUND));

        String hostilePayload =
                "{\"id\":999888777,\"total_price\":\"499.99\","
                        + "\"discount_codes\":[{\"code\":\"BRAND_B_SUMMER25\",\"amount\":\"100.00\"}]}";

        // [F-0725] This assertion CHANGED from assertThrows(ApiException) to a 200, and the change
        // does not weaken what this test protects. The security property here has never been the
        // HTTP status -- it is the two verify() blocks below: RedemptionService was called with
        // Brand A's OWN resolved workspaceId, so the cross-workspace coupon was rejected INSIDE the
        // service and neither Brand B's usage count nor any affiliate accrual was ever touched.
        // That is still true, and still asserted.
        //
        // The old comment argued the 200 itself was the danger. It is not: the increment and the
        // commission accrual are prevented by the workspace-scoped rejection, not by the status
        // code, and nothing runs after redeem() has thrown. The non-2xx had a real cost the old test
        // could not see -- Shopify retries it and removes the subscription after ~48h of failures
        // (F-0725), so the loudest possible failure was the one that disconnected the store.
        ResponseEntity<Void> response =
                controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", hostilePayload);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        // The load-bearing assertion: the controller called RedemptionService with BRAND A's OWN
        // resolved workspaceId (WORKSPACE_ID) -- it never substituted, guessed, or omitted a
        // workspace scope, and never called the legacy global-lookup overload for this webhook
        // surface at all.
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
    }

    @Test
    @DisplayName(
            "receive: legitimate same-workspace redemption still works — a shop redeeming its OWN"
                    + " workspace's coupon code succeeds exactly as before the fix")
    void receive_legitimateSameWorkspaceRedemption_stillWorks() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));
        stubIdempotencyServiceRunsAction();

        CouponRedemption redemption =
                CouponRedemption.builder()
                        .id("01HREDEMPTION7654321")
                        .couponId("01HCOUPONOWNEDBYSHOPA")
                        .orderId("42")
                        .orderAmount(new BigDecimal("80.00"))
                        .discountApplied(new BigDecimal("16.00"))
                        .idempotencyKey("shopify:whatever")
                        .build();
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID),
                        eq("OWNCODE20"),
                        eq("42"),
                        eq(new BigDecimal("80.00")),
                        eq(null),
                        anyString()))
                .thenReturn(redemption);

        String rawPayload =
                "{\"id\":42,\"total_price\":\"80.00\",\"discount_codes\":[{\"code\":\"OWNCODE20\",\"amount\":\"16.00\"}]}";

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", rawPayload);

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
    // Idempotency / replay [task brief requirement]
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("receive: a replayed delivery (AlreadyCompletedException) is a clean 200 no-op, RedemptionService is never re-invoked by this path")
    @SuppressWarnings("unchecked")
    void receive_replayedDelivery_isCleanNoOp() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));
        // Simulate IdempotencyService recognizing this exact key was already completed -- the
        // Supplier/action is deliberately NEVER invoked in this branch, same as the real
        // IdempotencyService#executeOnce contract for a COMPLETED key.
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenThrow(new IdempotencyService.AlreadyCompletedException("shopify:somekey"));

        String rawPayload =
                "{\"id\":820982911946154508,\"total_price\":\"49.99\",\"discount_codes\":[{\"code\":\"PRIYA_SUMMER25\",\"amount\":\"10.00\"}]}";

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", rawPayload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        // RedemptionService.redeem is only ever called FROM INSIDE the Supplier IdempotencyService
        // is responsible for invoking -- since executeOnce threw before/instead of invoking it,
        // RedemptionService must not have been called via this webhook delivery at all.
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("receive: a concurrently in-progress delivery (AlreadyInProgressException) is also a clean 200 no-op")
    @SuppressWarnings("unchecked")
    void receive_concurrentInProgressDelivery_isCleanNoOp() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenThrow(new IdempotencyService.AlreadyInProgressException("shopify:somekey"));

        String rawPayload = "{\"id\":123,\"total_price\":\"10.00\",\"discount_codes\":[{\"code\":\"X\",\"amount\":\"1.00\"}]}";

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", rawPayload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("receive: the SAME order delivered twice derives the SAME idempotency key both times (proves replay dedup is even possible)")
    @SuppressWarnings("unchecked")
    void receive_sameOrderTwice_derivesSameIdempotencyKey() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));
        stubIdempotencyServiceRunsAction();

        CouponRedemption redemption =
                CouponRedemption.builder()
                        .id("id")
                        .couponId("coupon")
                        .orderId("555")
                        .orderAmount(new BigDecimal("15.00"))
                        .discountApplied(new BigDecimal("2.00"))
                        .idempotencyKey("shopify:whatever")
                        .build();
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID), anyString(), anyString(), any(BigDecimal.class), eq(null), anyString()))
                .thenReturn(redemption);

        String rawPayload = "{\"id\":555,\"total_price\":\"15.00\",\"discount_codes\":[{\"code\":\"CODE555\",\"amount\":\"2.00\"}]}";

        controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", rawPayload);
        controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", rawPayload);

        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, times(2))
                .executeOnce(keyCaptor.capture(), eq(WORKSPACE_ID), eq("shopify.webhook"), any(Supplier.class));
        assertEquals(keyCaptor.getAllValues().get(0), keyCaptor.getAllValues().get(1));

        // And the SAME key was forwarded into RedemptionService.redeem both times too, so its OWN
        // internal idempotency guarantee (proven separately by RedemptionServiceTest) is engaged
        // against a stable key, not a fresh random one per delivery.
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

    // ---------------------------------------------------------------------------------------
    // [F-0725] webhook-retry-storm regression coverage.
    //
    // The defect: the try/catch around the redemption caught only AlreadyCompleted/
    // AlreadyInProgress, so every ordinary business outcome escaped as a non-2xx. Shopify retries
    // any non-2xx delivery and REMOVES the webhook subscription after roughly 48h of continuous
    // failures, while IdempotencyService#reclaimFailedForRetry handed the reservation back on each
    // attempt so the retry genuinely re-ran and re-failed. The controller reaches this code
    // whenever an order carries ANY discount code -- and on a real store most discount codes are
    // the merchant's own, not an Influora coupon. The integration disconnected itself.
    //
    // These two tests are a matched pair and must be read together: the first proves terminal
    // outcomes are acknowledged, the second proves the fix is NOT a blanket catch that would also
    // swallow a real outage. Deleting either one leaves the other able to pass a wrong fix.
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "terminal outcome {0} is acknowledged 200, not retried")
    @ValueSource(strings = {"INVALID_CODE", "CODE_EXPIRED", "CODE_LIMIT_REACHED", "UNSUPPORTED_DISCOUNT_TYPE"})
    @DisplayName(
            "receive: an order whose discount code is not a redeemable Influora coupon is"
                    + " acknowledged 200 — the delivery must not be retried until Shopify drops the"
                    + " subscription (F-0725)")
    void receive_terminalRedemptionOutcome_isAcknowledgedNotRetried(String terminalCode) {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));
        stubIdempotencyServiceRunsAction();

        // The merchant's OWN store coupon, of the kind that appears on ordinary orders every day.
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID), eq("FREESHIP"), anyString(), any(BigDecimal.class), eq(null), anyString()))
                .thenThrow(new ApiException(terminalCode, "terminal outcome", HttpStatus.BAD_REQUEST));

        String payload =
                "{\"id\":424242,\"total_price\":\"75.00\","
                        + "\"discount_codes\":[{\"code\":\"FREESHIP\",\"amount\":\"5.00\"}]}";

        ResponseEntity<Void> response =
                controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", payload);

        // 200 is the whole point: Shopify stops retrying and keeps the subscription alive.
        assertEquals(
                HttpStatus.OK,
                response.getStatusCode(),
                "a discount code that is not a redeemable Influora coupon must not drive a retry loop");

        // The redemption was still genuinely attempted and still workspace-scoped -- acknowledging
        // the delivery must not mean skipping the attempt.
        verify(redemptionService, times(1))
                .redeem(
                        eq(WORKSPACE_ID), eq("FREESHIP"), anyString(), any(BigDecimal.class), eq(null), anyString());
    }

    @Test
    @DisplayName(
            "receive: a TRANSIENT redemption failure still returns non-2xx so Shopify's retry can"
                    + " recover it — the F-0725 fix must not be a blanket catch")
    void receive_transientRedemptionFailure_stillSurfacesNon2xx() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));
        stubIdempotencyServiceRunsAction();

        // IDEMPOTENCY_KEY_IN_PROGRESS (RedemptionService.java:203-206) resolves as soon as the
        // concurrent attempt commits -- retrying is exactly the right recovery, so it must NOT be
        // converted to 200.
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID), eq("REALCODE"), anyString(), any(BigDecimal.class), eq(null), anyString()))
                .thenThrow(
                        new ApiException(
                                "IDEMPOTENCY_KEY_IN_PROGRESS",
                                "This redemption is already being processed -- retry shortly",
                                HttpStatus.CONFLICT));

        String payload =
                "{\"id\":515151,\"total_price\":\"20.00\","
                        + "\"discount_codes\":[{\"code\":\"REALCODE\",\"amount\":\"2.00\"}]}";

        ApiException thrown =
                assertThrows(
                        ApiException.class,
                        () -> controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", payload),
                        "a transient failure must keep its non-2xx so Shopify retries it");
        assertEquals("IDEMPOTENCY_KEY_IN_PROGRESS", thrown.getCode());
    }

    // ---------------------------------------------------------------------------------------
    // [F-0726] unsigned-tenant-selector regression coverage.
    //
    // X-Shopify-Hmac-Sha256 covers the BODY only, against the single app-level client secret. The
    // shop is named exclusively in X-Shopify-Shop-Domain, which nothing signs — so a valid
    // signature does not establish WHICH shop sent the delivery, even though the resolved shop
    // decides the workspace every downstream write is scoped to.
    //
    // The attack: install the app on a store you control, add a second webhook subscription to your
    // own collector (Shopify signs it with the same app secret), capture a body + valid HMAC for an
    // order whose discount code you chose, then replay it to us with the victim's shop domain in
    // the header.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "receive: REJECTS a validly-signed delivery whose order does not exist in the shop the"
                    + " header claims — the replayed-body cross-tenant attack (F-0726)")
    void receive_orderNotInClaimedShop_isRejected() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));

        // The victim's shop's own Admin API, asked with the victim's own token, does not have this
        // order — because the attacker created it in their own store.
        when(orderOwnershipVerifier.orderBelongsToShop(eq(SHOP_DOMAIN), eq(WORKSPACE_ID), eq("999888777")))
                .thenReturn(false);

        String replayedPayload =
                "{\"id\":999888777,\"total_price\":\"499.99\","
                        + "\"discount_codes\":[{\"code\":\"VICTIM_CODE\",\"amount\":\"100.00\"}]}";

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", replayedPayload));

        assertEquals("SHOP_ORDER_MISMATCH", ex.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());

        // The load-bearing assertion: nothing was written. No redemption, no usage-count increment,
        // no affiliate accrual — the delivery never reached RedemptionService at all.
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
        verify(idempotencyService, never()).executeOnce(anyString(), anyString(), anyString(), any(Supplier.class));
    }

    @Test
    @DisplayName(
            "receive: does NOT spend an Admin API call on an order with no discount code — the"
                    + " ownership check guards writes, not every delivery (F-0726)")
    void receive_orderWithNoCoupon_skipsOwnershipCheck() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));

        String noCouponPayload = "{\"id\":777,\"total_price\":\"12.00\",\"discount_codes\":[]}";

        ResponseEntity<Void> response =
                controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", noCouponPayload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        // Most orders on a real store carry no Influora coupon; paying for an outbound Shopify call
        // on every one of them would be a self-inflicted rate limit.
        verify(orderOwnershipVerifier, never()).orderBelongsToShop(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName(
            "receive: an UNREACHABLE Admin API is not treated as 'not your order' — it surfaces"
                    + " non-2xx so Shopify retries, and F-0725 must not swallow it")
    void receive_ownershipCheckTransientFailure_surfacesNon2xx() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));

        // A Shopify incident, not a forgery. Reading this as a rejection would silently drop real
        // redemptions for the duration of the outage.
        when(orderOwnershipVerifier.orderBelongsToShop(eq(SHOP_DOMAIN), eq(WORKSPACE_ID), eq("424242")))
                .thenThrow(new ShopifyApiException("Shopify order ownership check could not reach the shop"));

        String payload =
                "{\"id\":424242,\"total_price\":\"75.00\","
                        + "\"discount_codes\":[{\"code\":\"REALCODE\",\"amount\":\"5.00\"}]}";

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, "orders/paid", payload),
                        "a transient ownership-check failure must keep its non-2xx so Shopify retries");
        assertEquals("SHOPIFY_API_ERROR", ex.getCode());
        verify(redemptionService, never())
                .redeem(anyString(), anyString(), anyString(), any(), any(), anyString());
    }
}
