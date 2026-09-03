package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * [Ledger F-0521, REGRESSION TEST ONLY -- see {@code .proof-os/} for the ledger entry] Reproduces
 * the double-counting defect in {@link WooCommerceWebhookController#deriveIdempotencyKey}: the key
 * is derived from {@code siteUrl|topic|orderId}, and BOTH {@code order.created} and {@code
 * order.updated} are in the controller's own acted-on topic set ({@code TOPIC_ORDER_CREATED} /
 * {@code TOPIC_ORDER_UPDATED} -- see the controller's {@code receive} topic-routing check). So the
 * SAME order, delivered once under each topic (a routine WooCommerce sequence: a store fires {@code
 * order.created} on checkout, then {@code order.updated} moments later on payment-status change),
 * derives two DIFFERENT idempotency keys for what is really one commercial event, passes BOTH
 * reservations, and reaches {@link RedemptionService#redeem} twice for one order.
 *
 * <p>Mirrors {@code WooCommerceWebhookControllerTest}'s direct-Java-call, Mockito-only style (no
 * MockMvc/Spring context in this codebase's test suite) exactly, including its {@code
 * stubIdempotencyServiceRunsAction} pattern: {@link IdempotencyService#executeOnce} is stubbed to
 * genuinely invoke the supplied {@link Supplier}, which is the FAITHFUL simulation of the real
 * bean's behavior here -- since the two derived keys are different, the real {@code
 * IdempotencyService} would not dedup them against each other either (it only dedups a SECOND
 * delivery under the SAME key), so a mock that always runs the action does not manufacture the
 * failure, it reproduces it precisely at the layer this defect actually lives in: key derivation,
 * not the dedup mechanism itself (proven sound in isolation by {@code IdempotencyServiceTest}).
 *
 * <p><b>Do not "fix" this test to pass.</b> Per ledger F-0521 this class is regression-test-only;
 * the fix (deriving the idempotency key from {@code siteUrl|orderId} alone, or from a topic-agnostic
 * commercial-event identity) is out of scope here and belongs to whoever picks up F-0521's fix task.
 */
@ExtendWith(MockitoExtension.class)
class WooCommerceWebhookIdempotencyTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String SITE_URL = "https://my-test-store.example.com";
    private static final String VALID_SIGNATURE = "valid-signature-stub";
    private static final String ORDER_ID = "531";
    private static final String COUPON_CODE = "CREATOR10";
    private static final String ORDER_TOTAL_JSON = "30.00";
    private static final BigDecimal ORDER_TOTAL = new BigDecimal("30.00");

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

    private void stubResolvedIntegrationAndDecryptedSecret() {
        when(integrationRepository.findBySiteUrlAndRevokedFalse(SITE_URL))
                .thenReturn(Optional.of(activeIntegration()));
        when(integrationService.decryptSecret(any(WooCommerceIntegration.class))).thenReturn("the-secret");
    }

    /** Faithful simulation of the real bean: genuinely invokes the supplied action, same as {@code WooCommerceWebhookControllerTest#stubIdempotencyServiceRunsAction}. */
    @SuppressWarnings("unchecked")
    private void stubIdempotencyServiceRunsAction() {
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(3)).get());
    }

    @Test
    @DisplayName(
            "F-0521: the SAME order delivered under order.created then order.updated must produce"
                    + " the commercial effect (RedemptionService.redeem) exactly once, not twice")
    @SuppressWarnings("unchecked")
    void receive_sameOrder_createdThenUpdated_redeemsExactlyOnce() {
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq("the-secret"))).thenReturn(true);
        stubIdempotencyServiceRunsAction();

        CouponRedemption redemption =
                CouponRedemption.builder()
                        .id("01HREDEMPTION1234567")
                        .couponId("01HCOUPON1234567890AB")
                        .orderId(ORDER_ID)
                        .orderAmount(ORDER_TOTAL)
                        .discountApplied(new BigDecimal("10.00"))
                        .idempotencyKey("woocommerce:whatever")
                        .build();
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID), eq(COUPON_CODE), eq(ORDER_ID), eq(ORDER_TOTAL), eq(null), anyString()))
                .thenReturn(redemption);

        String rawPayload =
                "{\"id\":"
                        + ORDER_ID
                        + ",\"total\":\""
                        + ORDER_TOTAL_JSON
                        + "\",\"coupon_lines\":[{\"code\":\""
                        + COUPON_CODE
                        + "\"}]}";

        // WooCommerce's real delivery sequence for one checkout: order.created fires first, then
        // order.updated fires moments later on the SAME order (e.g. a payment-status transition).
        // Both are in the controller's own acted-on topic set.
        ResponseEntity<Void> createdResponse = controller.receive(VALID_SIGNATURE, SITE_URL, "order.created", rawPayload);
        ResponseEntity<Void> updatedResponse = controller.receive(VALID_SIGNATURE, SITE_URL, "order.updated", rawPayload);

        assertEquals(200, createdResponse.getStatusCodeValue());
        assertEquals(200, updatedResponse.getStatusCodeValue());

        // THE LOAD-BEARING ASSERTION: one commercial order must produce exactly one redemption,
        // regardless of how many topics WooCommerce happens to deliver it under. This is the
        // assertion that is RED today -- the controller currently calls redeem TWICE for this order.
        verify(redemptionService, times(1))
                .redeem(eq(WORKSPACE_ID), eq(COUPON_CODE), eq(ORDER_ID), eq(ORDER_TOTAL), eq(null), anyString());
    }

    @Test
    @DisplayName(
            "F-0521: deriveIdempotencyKey produces the SAME key for order.created and order.updated"
                    + " of the SAME order (it must, since both topics act on the same commercial"
                    + " event) -- currently it does not, because topic is hashed into the key")
    void receive_sameOrder_createdThenUpdated_derivesDifferentKeys_exposingRootCause() {
        stubResolvedIntegrationAndDecryptedSecret();
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), eq("the-secret"))).thenReturn(true);
        stubIdempotencyServiceRunsAction();

        CouponRedemption redemption =
                CouponRedemption.builder()
                        .id("01HREDEMPTION1234567")
                        .couponId("01HCOUPON1234567890AB")
                        .orderId(ORDER_ID)
                        .orderAmount(ORDER_TOTAL)
                        .discountApplied(new BigDecimal("10.00"))
                        .idempotencyKey("woocommerce:whatever")
                        .build();
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID), eq(COUPON_CODE), eq(ORDER_ID), eq(ORDER_TOTAL), eq(null), anyString()))
                .thenReturn(redemption);

        String rawPayload =
                "{\"id\":"
                        + ORDER_ID
                        + ",\"total\":\""
                        + ORDER_TOTAL_JSON
                        + "\",\"coupon_lines\":[{\"code\":\""
                        + COUPON_CODE
                        + "\"}]}";

        controller.receive(VALID_SIGNATURE, SITE_URL, "order.created", rawPayload);
        controller.receive(VALID_SIGNATURE, SITE_URL, "order.updated", rawPayload);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, times(2))
                .executeOnce(keyCaptor.capture(), eq(WORKSPACE_ID), eq("woocommerce.webhook"), any(Supplier.class));

        String createdKey = keyCaptor.getAllValues().get(0);
        String updatedKey = keyCaptor.getAllValues().get(1);

        // ROOT CAUSE, RED today: these are DIFFERENT because topic is part of the hashed input
        // (siteUrl|topic|orderId), so this reservation cannot dedup across the two topics of the
        // same order.
        assertEquals(createdKey, updatedKey);
    }
}
