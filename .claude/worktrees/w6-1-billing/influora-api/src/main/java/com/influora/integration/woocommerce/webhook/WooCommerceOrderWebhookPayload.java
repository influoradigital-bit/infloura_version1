package com.influora.integration.woocommerce.webhook;

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
 * Domain shape parsed from a real WooCommerce {@code order.created}/{@code order.updated} webhook
 * body. Field names verified against WooCommerce's public REST API order-object documentation
 * before writing this class, not assumed by analogy to {@code ShopifyOrderWebhookPayload} -- same
 * "[Wave C4 lesson applied]" discipline that class's javadoc documents.
 *
 * <p>Only the fields {@code WooCommerceWebhookController} actually needs are extracted: {@code id}
 * (WooCommerce's numeric order id, stringified -- this codebase's {@code orderId} fields are all
 * {@code String}, e.g. {@code CouponRedemption.orderId}), {@code total} (order total -- confirmed
 * this is WooCommerce's real field name, NOT Shopify's {@code total_price}), and the FIRST entry
 * of {@code coupon_lines} (confirmed real field name {@code code} inside each {@code coupon_lines}
 * entry -- this integration's only supported attribution mechanism, same documented-cut scope as
 * {@code ShopifyOrderWebhookPayload} for UTM click-based attribution; see {@code
 * WooCommerceWebhookController} class javadoc).
 *
 * <p>Both {@code id} and {@code total} are documented as always present on a real WooCommerce order
 * object; {@code coupon_lines} is an array that is empty (not absent) when no coupon was applied.
 */
public record WooCommerceOrderWebhookPayload(String orderId, BigDecimal total, String couponCode) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawOrder(
            @JsonProperty("id") Object id,
            @JsonProperty("total") String total,
            @JsonProperty("coupon_lines") List<RawCouponLine> couponLines) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawCouponLine(@JsonProperty("code") String code) {}

    /**
     * Parses a raw WooCommerce order webhook JSON body.
     *
     * @throws ApiException {@code INVALID_WEBHOOK_PAYLOAD} (400) if the body is not valid JSON, is
     *     empty, or is missing the required {@code id}/{@code total} fields
     */
    public static WooCommerceOrderWebhookPayload parse(String rawJson) {
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
        // WooCommerce's order.id is a JSON number (not a string) -- stringify it exactly as Jackson
        // rendered the numeric node, same defensive reasoning as ShopifyOrderWebhookPayload#parse
        // (avoids Object.toString() reformatting a Long/Double).
        String orderId = root.path("id").asText();

        if (raw.total() == null || raw.total().isBlank()) {
            throw new ApiException("INVALID_WEBHOOK_PAYLOAD", "Order total is missing", HttpStatus.BAD_REQUEST);
        }
        BigDecimal total;
        try {
            total = new BigDecimal(raw.total());
        } catch (NumberFormatException e) {
            throw new ApiException(
                    "INVALID_WEBHOOK_PAYLOAD", "Order total is not a valid number", HttpStatus.BAD_REQUEST);
        }

        List<RawCouponLine> couponLines = raw.couponLines() == null ? new ArrayList<>() : raw.couponLines();
        String couponCode = couponLines.isEmpty() ? null : couponLines.get(0).code();

        return new WooCommerceOrderWebhookPayload(orderId, total, couponCode);
    }
}
