package com.influora.integration.razorpay;

import com.influora.config.RazorpayProperties;
import com.razorpay.Order;
import com.razorpay.RazorpayException;
import java.math.BigDecimal;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Razorpay Orders API client (escrow fund order creation).
 *
 * <p><b>P8:</b> Now uses the official {@code com.razorpay:razorpay-java} SDK (1.4.6) instead of
 * hand-rolled HTTP calls. The SDK handles authentication, request signing, and response parsing.
 *
 * <p>[SEC: Guardrail 1] Callers must pass an already server-derived {@code amount} — this class
 * never accepts or trusts a client-supplied value; that contract is enforced by
 * {@code EscrowService}, not here.
 */
@Component
public class RazorpayClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayClient.class);

    private final RazorpayProperties props;
    private volatile com.razorpay.RazorpayClient sdkClient;

    public RazorpayClient(RazorpayProperties props) {
        this.props = props;
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    /**
     * Lazily initializes the Razorpay SDK client. Thread-safe via double-checked locking.
     */
    private com.razorpay.RazorpayClient getSdkClient() throws RazorpayException {
        if (sdkClient == null) {
            synchronized (this) {
                if (sdkClient == null) {
                    sdkClient = new com.razorpay.RazorpayClient(props.getKeyId(), props.getKeySecret());
                }
            }
        }
        return sdkClient;
    }

    /**
     * Creates a Razorpay order for a server-derived amount (paise) and idempotency-scoped
     * receipt. Returns a stub order id when Razorpay credentials are not configured (local/dev),
     * so escrow flows remain testable without live credentials.
     */
    public OrderResult createOrder(BigDecimal amountInRupees, String currency, String receiptId) {
        if (!isConfigured()) {
            log.info("[MOCK] Razorpay order would be created: amount={}, currency={}, receipt={}",
                    amountInRupees, currency, receiptId);
            return new OrderResult("order_stub_" + receiptId, "created");
        }

        try {
            long amountInPaise = amountInRupees.movePointRight(2).longValueExact();

            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", amountInPaise);
            orderRequest.put("currency", currency);
            orderRequest.put("receipt", receiptId);

            Order order = getSdkClient().orders.create(orderRequest);

            String orderId = order.get("id");
            String status = order.get("status");

            log.debug("Razorpay order created: orderId={}, status={}, amount={}",
                    orderId, status, amountInPaise);

            return new OrderResult(orderId, status, order.toString());
        } catch (RazorpayException e) {
            log.error("Razorpay order creation failed: amount={}, currency={}, receipt={}, error={}",
                    amountInRupees, currency, receiptId, e.getMessage());
            throw new RazorpayIntegrationException("Failed to create Razorpay order", e);
        }
    }

    /**
     * Fetches an existing order by ID. Useful for webhook verification.
     */
    public OrderResult fetchOrder(String orderId) {
        if (!isConfigured()) {
            return new OrderResult(orderId, "stub_fetched");
        }

        try {
            Order order = getSdkClient().orders.fetch(orderId);
            return new OrderResult(
                    order.get("id"),
                    order.get("status"),
                    order.toString());
        } catch (RazorpayException e) {
            log.error("Razorpay order fetch failed: orderId={}, error={}", orderId, e.getMessage());
            throw new RazorpayIntegrationException("Failed to fetch Razorpay order", e);
        }
    }

    public record OrderResult(String orderId, String status, String rawResponse) {
        public OrderResult(String orderId, String status) {
            this(orderId, status, null);
        }
    }
}
