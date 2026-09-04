package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.ShopifyIntegration;
import com.influora.integration.shopify.webhook.ShopifyWebhookSignatureVerifier;
import com.influora.repository.ShopifyIntegrationRepository;
import com.influora.service.IdempotencyService;
import com.influora.service.tracking.RedemptionService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-0619 — Shopify carried the same double-redeem defect fixed in WooCommerce under F-0521: the
 * idempotency key was derived from shop domain PLUS TOPIC plus order id, and both {@code
 * orders/paid} and {@code orders/create} are acted on. Shopify sends both for one order, so one
 * commercial event produced two distinct keys, reserved independently, and the creator's discount
 * code was redeemed twice.
 *
 * <p><b>Why this class stubs {@link IdempotencyService} differently to {@code
 * WooCommerceWebhookIdempotencyTest}, and why that matters (F-0620).</b> That class stubs {@code
 * executeOnce} to invoke the supplied action unconditionally. Once both deliveries derive the SAME
 * key that stops being a faithful simulation — the real bean short-circuits the second call — so
 * the assertion there passes only because of an in-process memo map, and a probe that removed the
 * memo while KEEPING the key fix still saw the test fail. In other words that test proves the
 * memo, not the key, while in production it is the key that stops the second redeem.
 *
 * <p>So the stub below <b>dedupes by key</b>, exactly as {@code IdempotencyService} does through
 * its unique constraint: the first call for a key runs the action, any later call for the same key
 * short-circuits and returns the first result. Under that stub the ONLY thing that can make {@code
 * redeem} fire once is the key itself being equal across the two topics — which is the behaviour
 * this ticket is about. Revert the production change and this test fails.
 */
@ExtendWith(MockitoExtension.class)
class ShopifyWebhookIdempotencyTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String SHOP_DOMAIN = "my-test-store.myshopify.com";
    private static final String VALID_SIGNATURE = "valid-signature-stub";
    private static final String ORDER_ID = "820982911946154508";
    private static final String DISCOUNT_CODE = "CREATOR10";
    private static final BigDecimal ORDER_TOTAL = new BigDecimal("30.00");

    private static final String TOPIC_PAID = "orders/paid";
    private static final String TOPIC_CREATE = "orders/create";

    @Mock private ShopifyWebhookSignatureVerifier signatureVerifier;
    @Mock private ShopifyIntegrationRepository shopifyIntegrationRepository;
    @Mock private RedemptionService redemptionService;
    @Mock private IdempotencyService idempotencyService;

    private ShopifyWebhookController controller;

    /** Keys this run has already reserved — the stub's stand-in for the unique constraint. */
    private final Map<String, Object> reserved = new HashMap<>();

    @BeforeEach
    void setUp() {
        controller =
                new ShopifyWebhookController(
                        signatureVerifier, shopifyIntegrationRepository, redemptionService, idempotencyService);
    }

    private static ShopifyIntegration activeIntegration() {
        return ShopifyIntegration.builder()
                .id("01HSHOPIFYINTEGRATN1")
                .workspaceId(WORKSPACE_ID)
                .shopDomain(SHOP_DOMAIN)
                .encryptedAccessToken("encrypted")
                .build();
    }

    private static String orderJson() {
        return "{\"id\":"
                + ORDER_ID
                + ",\"total_price\":\"30.00\",\"discount_codes\":[{\"code\":\""
                + DISCOUNT_CODE
                + "\",\"amount\":\"3.00\"}]}";
    }

    private void stubVerifiedAndResolved() {
        when(signatureVerifier.verify(anyString(), eq(VALID_SIGNATURE), any())).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(Optional.of(activeIntegration()));
    }

    /**
     * Dedupes by key, the way the real bean does. NOT the unconditional run-the-action stub — see
     * the class javadoc and F-0620 for why that distinction decides what this test proves.
     */
    @SuppressWarnings("unchecked")
    private void stubIdempotencyServiceDedupingByKey() {
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(
                        invocation -> {
                            String key = invocation.getArgument(0);
                            if (reserved.containsKey(key)) {
                                return reserved.get(key);
                            }
                            Object result = ((Supplier<Object>) invocation.getArgument(3)).get();
                            reserved.put(key, result);
                            return result;
                        });
    }

    @Test
    @DisplayName(
            "F-0619: one order delivered as orders/paid then orders/create redeems EXACTLY once")
    void receive_sameOrder_paidThenCreate_redeemsExactlyOnce() {
        stubVerifiedAndResolved();
        stubIdempotencyServiceDedupingByKey();

        controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, TOPIC_PAID, orderJson());
        controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, TOPIC_CREATE, orderJson());

        verify(redemptionService, times(1))
                .redeem(
                        eq(WORKSPACE_ID),
                        eq(DISCOUNT_CODE),
                        eq(ORDER_ID),
                        eq(ORDER_TOTAL),
                        eq(null),
                        anyString());
    }

    @Test
    @DisplayName(
            "F-0619: the two topics derive the SAME idempotency key — the topic is not part of it")
    void receive_sameOrder_paidThenCreate_derivesOneKey() {
        stubVerifiedAndResolved();
        stubIdempotencyServiceDedupingByKey();

        controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, TOPIC_PAID, orderJson());
        controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, TOPIC_CREATE, orderJson());

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, times(2))
                .executeOnce(keys.capture(), anyString(), anyString(), any(Supplier.class));

        assertEquals(
                keys.getAllValues().get(0),
                keys.getAllValues().get(1),
                "both deliveries of one order must derive the same key; a topic-bearing key is the defect");
        assertEquals(1, reserved.size(), "one commercial event must occupy exactly one reservation");
    }

    @Test
    @DisplayName("F-0619: a DIFFERENT order still gets its own key and is redeemed on its own")
    void receive_differentOrders_areNotCollapsed() {
        stubVerifiedAndResolved();
        stubIdempotencyServiceDedupingByKey();

        String otherOrderJson =
                "{\"id\":999111,\"total_price\":\"12.00\",\"discount_codes\":[{\"code\":\""
                        + DISCOUNT_CODE
                        + "\",\"amount\":\"1.00\"}]}";

        controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, TOPIC_PAID, orderJson());
        controller.receive(VALID_SIGNATURE, SHOP_DOMAIN, TOPIC_PAID, otherOrderJson);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, times(2))
                .executeOnce(keys.capture(), anyString(), anyString(), any(Supplier.class));

        assertNotEquals(
                keys.getAllValues().get(0),
                keys.getAllValues().get(1),
                "dropping the topic must not collapse two genuinely different orders");
        verify(redemptionService, times(2))
                .redeem(anyString(), anyString(), anyString(), any(BigDecimal.class), eq(null), anyString());
    }
}
