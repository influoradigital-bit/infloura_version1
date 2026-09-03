package com.influora.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CouponRedemption;
import com.influora.domain.entity.ShopifyIntegration;
import com.influora.integration.shopify.webhook.ShopifyWebhookSignatureVerifier;
import com.influora.repository.ShopifyIntegrationRepository;
import com.influora.service.IdempotencyService;
import com.influora.service.tracking.RedemptionService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** THROWAWAY probe (delete after run): does the Shopify sibling double-count one order? */
@ExtendWith(MockitoExtension.class)
class KabirShopifySiblingProbeTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String SHOP_DOMAIN = "my-test-store.myshopify.com";
    private static final String SIG = "valid-signature-stub";

    @Mock private ShopifyWebhookSignatureVerifier signatureVerifier;
    @Mock private ShopifyIntegrationRepository shopifyIntegrationRepository;
    @Mock private RedemptionService redemptionService;
    @Mock private IdempotencyService idempotencyService;

    private ShopifyWebhookController controller;

    @BeforeEach
    void setUp() {
        controller =
                new ShopifyWebhookController(
                        signatureVerifier, shopifyIntegrationRepository, redemptionService, idempotencyService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sameOrder_createThenPaid_redeemsExactlyOnce() {
        when(signatureVerifier.verify(anyString(), eq(SIG), eq(null))).thenReturn(true);
        when(shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(SHOP_DOMAIN))
                .thenReturn(
                        Optional.of(
                                ShopifyIntegration.builder()
                                        .id("01HSHOPIFYINTEGRATION1")
                                        .workspaceId(WORKSPACE_ID)
                                        .shopDomain(SHOP_DOMAIN)
                                        .encryptedAccessToken("encrypted")
                                        .build()));
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(3)).get());
        when(redemptionService.redeem(
                        eq(WORKSPACE_ID), anyString(), anyString(), any(BigDecimal.class), eq(null), anyString()))
                .thenReturn(CouponRedemption.builder().id("r").couponId("c").orderId("777").build());

        String raw =
                "{\"id\":777,\"total_price\":\"20.00\",\"discount_codes\":[{\"code\":\"CREATOR10\"}]}";

        controller.receive(SIG, SHOP_DOMAIN, "orders/create", raw);
        controller.receive(SIG, SHOP_DOMAIN, "orders/paid", raw);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, times(2))
                .executeOnce(keys.capture(), eq(WORKSPACE_ID), eq("shopify.webhook"), any(Supplier.class));
        System.out.println("PROBE createKey=" + keys.getAllValues().get(0));
        System.out.println("PROBE paidKey  =" + keys.getAllValues().get(1));
        System.out.println(
                "PROBE keysEqual=" + keys.getAllValues().get(0).equals(keys.getAllValues().get(1)));

        verify(redemptionService, times(1))
                .redeem(eq(WORKSPACE_ID), eq("CREATOR10"), eq("777"), any(BigDecimal.class), eq(null), anyString());
    }
}
