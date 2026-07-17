package com.influora.integration.woocommerce.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.influora.common.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WooCommerceOrderWebhookPayload#parse} -- mirrors {@code
 * ShopifyOrderWebhookPayloadTest}'s round-trip discipline: feeds realistic raw WooCommerce order
 * JSON (field names confirmed against WooCommerce's real REST API documentation, not hand-invented)
 * through the actual parser, catching the exact bug class (a writer and reader that individually
 * look correct but never agree on the real shape) that discipline exists to guard against.
 */
class WooCommerceOrderWebhookPayloadTest {

    @Test
    @DisplayName("parse: extracts id, total, and the first coupon_lines[].code from a realistic order payload")
    void parse_realisticOrderWithCoupon() {
        String json =
                "{\"id\":531,\"total\":\"30.00\",\"status\":\"processing\","
                        + "\"coupon_lines\":[{\"code\":\"CREATOR10\"}]}";

        WooCommerceOrderWebhookPayload payload = WooCommerceOrderWebhookPayload.parse(json);

        assertEquals("531", payload.orderId());
        assertEquals(new BigDecimal("30.00"), payload.total());
        assertEquals("CREATOR10", payload.couponCode());
    }

    @Test
    @DisplayName("parse: an order with an empty coupon_lines array has a null couponCode, not an error")
    void parse_emptyCouponLines_couponCodeIsNull() {
        String json = "{\"id\":42,\"total\":\"15.50\",\"coupon_lines\":[]}";

        WooCommerceOrderWebhookPayload payload = WooCommerceOrderWebhookPayload.parse(json);

        assertEquals("42", payload.orderId());
        assertNull(payload.couponCode());
    }

    @Test
    @DisplayName("parse: an order with coupon_lines entirely absent has a null couponCode, not an error")
    void parse_missingCouponLines_couponCodeIsNull() {
        String json = "{\"id\":7,\"total\":\"5.00\"}";

        WooCommerceOrderWebhookPayload payload = WooCommerceOrderWebhookPayload.parse(json);

        assertNull(payload.couponCode());
    }

    @Test
    @DisplayName("parse: only the FIRST coupon_lines entry is used when multiple coupons are stacked")
    void parse_multipleCoupons_usesFirstOnly() {
        String json =
                "{\"id\":99,\"total\":\"100.00\",\"coupon_lines\":["
                        + "{\"code\":\"FIRST10\"},{\"code\":\"SECOND5\"}]}";

        WooCommerceOrderWebhookPayload payload = WooCommerceOrderWebhookPayload.parse(json);

        assertEquals("FIRST10", payload.couponCode());
    }

    @Test
    @DisplayName("parse: a large numeric order id round-trips exactly, not in scientific notation")
    void parse_largeNumericOrderId_roundTripsExactly() {
        String json = "{\"id\":820982911946154508,\"total\":\"49.99\"}";

        WooCommerceOrderWebhookPayload payload = WooCommerceOrderWebhookPayload.parse(json);

        assertEquals("820982911946154508", payload.orderId());
    }

    @Test
    @DisplayName("parse: rejects invalid (non-JSON) payload")
    void parse_invalidJson_rejected() {
        ApiException ex = assertThrows(ApiException.class, () -> WooCommerceOrderWebhookPayload.parse("not json"));
        assertEquals("INVALID_WEBHOOK_PAYLOAD", ex.getCode());
    }

    @Test
    @DisplayName("parse: rejects an empty payload")
    void parse_emptyPayload_rejected() {
        assertThrows(ApiException.class, () -> WooCommerceOrderWebhookPayload.parse(""));
    }

    @Test
    @DisplayName("parse: rejects a payload missing the required id field")
    void parse_missingId_rejected() {
        ApiException ex =
                assertThrows(
                        ApiException.class, () -> WooCommerceOrderWebhookPayload.parse("{\"total\":\"10.00\"}"));
        assertEquals("INVALID_WEBHOOK_PAYLOAD", ex.getCode());
    }

    @Test
    @DisplayName("parse: rejects a payload missing the required total field")
    void parse_missingTotal_rejected() {
        ApiException ex =
                assertThrows(ApiException.class, () -> WooCommerceOrderWebhookPayload.parse("{\"id\":1}"));
        assertEquals("INVALID_WEBHOOK_PAYLOAD", ex.getCode());
    }

    @Test
    @DisplayName("parse: rejects a payload whose total is not a valid number")
    void parse_nonNumericTotal_rejected() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> WooCommerceOrderWebhookPayload.parse("{\"id\":1,\"total\":\"not-a-number\"}"));
        assertEquals("INVALID_WEBHOOK_PAYLOAD", ex.getCode());
    }

    @Test
    @DisplayName("parse: unknown/extra fields on the real payload shape are ignored, not rejected")
    void parse_unknownFieldsIgnored() {
        String json =
                "{\"id\":1,\"total\":\"10.00\",\"status\":\"completed\",\"currency\":\"USD\","
                        + "\"line_items\":[{\"name\":\"Widget\"}],\"coupon_lines\":[{\"code\":\"X\",\"discount\":\"1.00\",\"discount_tax\":\"0.00\"}]}";

        WooCommerceOrderWebhookPayload payload = WooCommerceOrderWebhookPayload.parse(json);

        assertEquals("1", payload.orderId());
        assertEquals("X", payload.couponCode());
    }
}
