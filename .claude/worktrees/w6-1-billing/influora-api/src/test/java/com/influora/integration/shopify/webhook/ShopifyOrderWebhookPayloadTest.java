package com.influora.integration.shopify.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.influora.common.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [Wave C4 lesson applied] Genuine round-trip / realistic-fixture tests for {@link
 * ShopifyOrderWebhookPayload#parse} — feeds REAL raw Shopify order JSON shapes (verified against
 * Shopify's public Admin API order-object documentation, not a hand-invented shape one layer wrote
 * and the same layer's own test re-reads) through the actual parser, proving the field
 * names/types/nesting this class depends on genuinely exist in Shopify's real payload, not just
 * that {@code parse} is internally self-consistent against a fixture this test file made up to
 * match the implementation.
 */
class ShopifyOrderWebhookPayloadTest {

    @Test
    @DisplayName("parse: extracts id, total_price, and the first discount code from a realistic full order payload")
    void parse_realisticFullOrderPayload() {
        // Trimmed but field-accurate shape of a real Shopify orders/paid webhook body (unused
        // fields omitted for fixture brevity, but every field this class reads is present with
        // Shopify's real type/nesting — id as a JSON NUMBER, total_price as a JSON STRING,
        // discount_codes as an array of {code, amount, type} objects).
        String rawJson =
                """
                {
                  "id": 820982911946154508,
                  "email": "jon@example.com",
                  "created_at": "2026-07-07T10:00:00-04:00",
                  "total_price": "49.99",
                  "subtotal_price": "59.99",
                  "currency": "USD",
                  "financial_status": "paid",
                  "discount_codes": [
                    { "code": "PRIYA_SUMMER25", "amount": "10.00", "type": "fixed_amount" }
                  ],
                  "line_items": [
                    { "id": 466157049, "title": "IPod Nano - 8GB", "price": "199.00" }
                  ]
                }
                """;

        ShopifyOrderWebhookPayload payload = ShopifyOrderWebhookPayload.parse(rawJson);

        // id is a JSON number in Shopify's real payload -- must stringify exactly, no scientific
        // notation / precision loss for a large integer id.
        assertEquals("820982911946154508", payload.orderId());
        assertEquals(new BigDecimal("49.99"), payload.totalPrice());
        assertEquals("PRIYA_SUMMER25", payload.discountCode());
    }

    @Test
    @DisplayName("parse: an order with an EMPTY discount_codes array has a null discountCode, not an error")
    void parse_emptyDiscountCodesArray() {
        String rawJson =
                """
                {
                  "id": 123456789,
                  "total_price": "25.00",
                  "discount_codes": []
                }
                """;

        ShopifyOrderWebhookPayload payload = ShopifyOrderWebhookPayload.parse(rawJson);

        assertEquals("123456789", payload.orderId());
        assertEquals(new BigDecimal("25.00"), payload.totalPrice());
        assertNull(payload.discountCode());
    }

    @Test
    @DisplayName("parse: an order with NO discount_codes field at all (not just empty) has a null discountCode")
    void parse_missingDiscountCodesField() {
        String rawJson = "{\"id\": 123456789, \"total_price\": \"25.00\"}";

        ShopifyOrderWebhookPayload payload = ShopifyOrderWebhookPayload.parse(rawJson);

        assertNull(payload.discountCode());
    }

    @Test
    @DisplayName("parse: multiple discount codes on one order -- only the FIRST is used for attribution")
    void parse_multipleDiscountCodesUsesFirst() {
        String rawJson =
                """
                {
                  "id": 123456789,
                  "total_price": "40.00",
                  "discount_codes": [
                    { "code": "FIRST_CODE", "amount": "5.00", "type": "fixed_amount" },
                    { "code": "SECOND_CODE", "amount": "5.00", "type": "fixed_amount" }
                  ]
                }
                """;

        ShopifyOrderWebhookPayload payload = ShopifyOrderWebhookPayload.parse(rawJson);

        assertEquals("FIRST_CODE", payload.discountCode());
    }

    @Test
    @DisplayName("parse: rejects malformed (non-JSON) payload")
    void parse_rejectsMalformedJson() {
        ApiException ex = assertThrows(ApiException.class, () -> ShopifyOrderWebhookPayload.parse("not json"));
        assertEquals("INVALID_WEBHOOK_PAYLOAD", ex.getCode());
    }

    @Test
    @DisplayName("parse: rejects an empty payload")
    void parse_rejectsEmptyPayload() {
        assertThrows(ApiException.class, () -> ShopifyOrderWebhookPayload.parse(""));
        assertThrows(ApiException.class, () -> ShopifyOrderWebhookPayload.parse("null"));
    }

    @Test
    @DisplayName("parse: rejects a payload missing the required id field")
    void parse_rejectsMissingId() {
        String rawJson = "{\"total_price\": \"25.00\"}";
        ApiException ex = assertThrows(ApiException.class, () -> ShopifyOrderWebhookPayload.parse(rawJson));
        assertEquals("INVALID_WEBHOOK_PAYLOAD", ex.getCode());
    }

    @Test
    @DisplayName("parse: rejects a payload missing the required total_price field")
    void parse_rejectsMissingTotalPrice() {
        String rawJson = "{\"id\": 123456789}";
        ApiException ex = assertThrows(ApiException.class, () -> ShopifyOrderWebhookPayload.parse(rawJson));
        assertEquals("INVALID_WEBHOOK_PAYLOAD", ex.getCode());
    }

    @Test
    @DisplayName("parse: rejects a payload whose total_price is not a valid number")
    void parse_rejectsNonNumericTotalPrice() {
        String rawJson = "{\"id\": 123456789, \"total_price\": \"not-a-number\"}";
        ApiException ex = assertThrows(ApiException.class, () -> ShopifyOrderWebhookPayload.parse(rawJson));
        assertEquals("INVALID_WEBHOOK_PAYLOAD", ex.getCode());
    }

    @Test
    @DisplayName("parse: large numeric order id round-trips exactly, no scientific notation or precision loss")
    void parse_largeOrderIdRoundTripsExactly() {
        // Shopify order ids are large longs (18-19 digits) -- this is the exact bug class a naive
        // Object.toString() on a boxed Double/Long can introduce (e.g. "8.2098291E17").
        String rawJson = "{\"id\": 999999999999999999, \"total_price\": \"10.00\"}";
        ShopifyOrderWebhookPayload payload = ShopifyOrderWebhookPayload.parse(rawJson);
        assertEquals("999999999999999999", payload.orderId());
    }
}
