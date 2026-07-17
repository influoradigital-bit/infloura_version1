package com.influora.integration.razorpay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.config.RazorpayProperties;
import com.razorpay.RazorpayException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * RazorpayX Payouts API client — moves funds OUT to a creator's linked account once a milestone
 * is released from escrow.
 *
 * <p><b>P8:</b> The official Razorpay Java SDK (1.4.6) does not include the RazorpayX Payouts API
 * (the SDK covers Orders/Payments but not the X platform). This implementation uses the documented
 * RazorpayX REST API directly via HttpClient, matching the official API spec.
 *
 * <p>[SEC: PayoutStateMachine] This client only ever *initiates* a payout for an amount the
 * caller has already re-derived from {@code payment_milestones.amount} (server-authoritative) —
 * it never accepts a raw client amount.
 */
@Component
public class RazorpayXClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayXClient.class);

    private final RazorpayProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RazorpayXClient(RazorpayProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isConfigured() {
        return props.isConfigured() && !props.getPayoutAccountNumber().isBlank();
    }

    /**
     * Initiates a payout. Returns a stub payout id in QUEUED status when RazorpayX is not
     * configured, matching {@code PayoutService.queuePayout}'s out-of-band-confirm design —
     * nothing here marks a payout PROCESSED without a webhook confirming it.
     */
    public PayoutResult initiatePayout(
            String fundAccountId, BigDecimal amountInRupees, String currency, String idempotencyKey) {
        if (!isConfigured()) {
            log.info("[MOCK] RazorpayX payout would be initiated: fundAccount={}, amount={}, currency={}",
                    fundAccountId, amountInRupees, currency);
            return new PayoutResult("payout_stub_" + idempotencyKey, "queued");
        }

        long amountInPaise = amountInRupees.movePointRight(2).longValueExact();
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("account_number", props.getPayoutAccountNumber());
        requestBody.put("fund_account_id", fundAccountId);
        requestBody.put("amount", amountInPaise);
        requestBody.put("currency", currency);
        requestBody.put("mode", "IMPS");
        requestBody.put("purpose", "payout");
        requestBody.put("queue_if_low_balance", true);
        requestBody.put("reference_id", idempotencyKey);
        String body = writeJson(requestBody);

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(props.getPayoutApiBaseUrl() + "/payouts"))
                        .header("Authorization", basicAuthHeader())
                        .header("Content-Type", "application/json")
                        .header("X-Payout-Idempotency", idempotencyKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String payoutId = extractPayoutId(response.body());
            log.debug("RazorpayX payout initiated: payoutId={}, amount={}", payoutId, amountInPaise);

            return new PayoutResult(payoutId, "queued", response.body());
        } catch (Exception e) {
            log.error("RazorpayX payout initiation failed: fundAccount={}, amount={}, error={}",
                    fundAccountId, amountInRupees, e.getMessage());
            throw new RazorpayIntegrationException("Failed to initiate RazorpayX payout", e);
        }
    }

    /**
     * Fetches an existing payout by ID. Useful for status verification.
     */
    public PayoutResult fetchPayout(String payoutId) {
        if (!isConfigured()) {
            return new PayoutResult(payoutId, "stub_fetched");
        }

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(props.getPayoutApiBaseUrl() + "/payouts/" + payoutId))
                        .header("Authorization", basicAuthHeader())
                        .GET()
                        .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new PayoutResult(payoutId, extractStatus(response.body()), response.body());
        } catch (Exception e) {
            log.error("RazorpayX payout fetch failed: payoutId={}, error={}", payoutId, e.getMessage());
            throw new RazorpayIntegrationException("Failed to fetch RazorpayX payout", e);
        }
    }

    private String basicAuthHeader() {
        String credentials = props.getKeyId() + ":" + props.getKeySecret();
        return "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RazorpayIntegrationException("Failed to serialize RazorpayX payout request", e);
        }
    }

    private String extractPayoutId(String rawJson) {
        try {
            return objectMapper.readTree(rawJson).path("id").asText("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String extractStatus(String rawJson) {
        try {
            return objectMapper.readTree(rawJson).path("status").asText("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    public record PayoutResult(String payoutId, String status, String rawResponse) {
        public PayoutResult(String payoutId, String status) {
            this(payoutId, status, null);
        }
    }
}
