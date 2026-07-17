package com.influora.integration.shopify.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Domain shape parsed from a real Shopify {@code orders/create}/{@code orders/paid} webhook body.
 *
 * <p><b>[Wave C4 lesson applied]</b> the exact field names/shapes below were verified against
 * Shopify's public Admin API order-object documentation before writing this class, not assumed —
 * same discipline {@code RedemptionService}/{@code ConversionTrackingService} document for
 * adapting spec pseudocode to real entity shapes. {@code
 * ShopifyOrderWebhookPayloadRoundTripTest} feeds a realistic raw Shopify JSON fixture through
 * {@link #parse} end-to-end (not a hand-built mocked shape on each side) — the exact bug class
 * that slipped past review in Wave C4 (a writer and reader that individually looked correct in
 * isolation, but never agreed on the real shape) is what that test is there to catch here.
 *
 * <p>Only the fields {@code ShopifyWebhookController} actually needs are extracted: {@code id}
 * (Shopify's numeric order id, stringified — this codebase's {@code orderId} fields are all
 * {@code String}, e.g. {@code CouponRedemption.orderId}), {@code total_price} (order total, the
 * same "pre-discount... actually POST-discount total" caveat Shopify's own docs note — see {@link
 * #totalPrice} javadoc), and the FIRST entry of {@code discount_codes} (this integration's only
 * supported attribution mechanism — see {@code ShopifyWebhookController} class javadoc for why UTM
 * click-based attribution is explicitly out of scope for this slice).
 */
public record ShopifyOrderWebhookPayload(String orderId, BigDecimal totalPrice, String discountCode) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawOrder(
            @JsonProperty("id") Object id,
            @JsonProperty("total_price") String totalPrice,
            @JsonProperty("discount_codes") List<RawDiscountCode> discountCodes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawDiscountCode(@JsonProperty("code") String code, @JsonProperty("amount") String amount) {}

    /**
     * Parses a raw Shopify order webhook JSON body.
     *
     * @throws ApiException {@code INVALID_WEBHOOK_PAYLOAD} (400) if the body is not valid JSON, is
     *     empty, or is missing the required {@code id}/{@code total_price} fields
     */
    public static ShopifyOrderWebhookPayload parse(String rawJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(rawJson);
        } catch (Exception e) {
            throw new ApiException(
                    "INVALID_WEBHOOK_PAYLOAD", "Webhook payload is not valid JSON", HttpStatus.BAD_REQUEST);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new ApiException("INVALID_WEBHOOK_PAYLOAD", "Webhook payload is empty", HttpStatus.BAD_REQUEST);
        }

        RawOrder raw;
        try {
            raw = MAPPER.treeToValue(root, RawOrder.class);
        } catch (Exception e) {
            throw new ApiException(
                    "INVALID_WEBHOOK_PAYLOAD", "Webhook payload does not match the expected order shape", HttpStatus.BAD_REQUEST);
        }

        if (raw.id() == null) {
            throw new ApiException("INVALID_WEBHOOK_PAYLOAD", "Order id is missing", HttpStatus.BAD_REQUEST);
        }
        // Shopify's order.id is a JSON number (not a string) — stringify it exactly as Jackson
        // rendered the numeric node, so an integer-valued order id round-trips as "123456789", not
        // "1.23456789E8" or similar. Using JsonNode.asText() on the ORIGINAL node (not raw.id()'s
        // Object form) avoids Java's Object.toString() reformatting a Long/Double.
        String orderId = root.path("id").asText();

        if (raw.totalPrice() == null || raw.totalPrice().isBlank()) {
            throw new ApiException("INVALID_WEBHOOK_PAYLOAD", "Order total_price is missing", HttpStatus.BAD_REQUEST);
        }
        BigDecimal totalPrice;
        try {
            totalPrice = new BigDecimal(raw.totalPrice());
        } catch (NumberFormatException e) {
            throw new ApiException(
                    "INVALID_WEBHOOK_PAYLOAD", "Order total_price is not a valid number", HttpStatus.BAD_REQUEST);
        }

        List<RawDiscountCode> discountCodes = raw.discountCodes() == null ? new ArrayList<>() : raw.discountCodes();
        String discountCode =
                discountCodes.isEmpty() ? null : discountCodes.get(0).code();

        return new ShopifyOrderWebhookPayload(orderId, totalPrice, discountCode);
    }
}
