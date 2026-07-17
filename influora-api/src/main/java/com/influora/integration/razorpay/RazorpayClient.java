package com.influora.integration.razorpay;

import com.influora.config.InfluoraEnvironment;
import com.influora.config.RazorpayProperties;
import com.razorpay.Order;
import com.razorpay.Plan;
import com.razorpay.RazorpayException;
import com.razorpay.Subscription;
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
 *
 * <p><b>W0-4 (2026-07-15):</b> every {@code !isConfigured()} branch below used to return a mock
 * {@code *_stub_*} result unconditionally, in every environment. That mirrors {@link
 * com.influora.integration.msg91.Msg91EmailClient}'s pre-D4 bug: a prod/staging deploy with a
 * missing/placeholder Razorpay key would silently create fake escrow orders / plans /
 * subscriptions instead of failing — money-path state built entirely on stub ids. Mirrors the
 * Msg91 fix's {@link InfluoraEnvironment#isDev()} gate: dev keeps the stub-mock convenience
 * (local/test runs without live credentials), every other environment now fails fast via {@link
 * #requireConfiguredOutsideDev(String)} instead of fabricating a result.
 */
@Component
public class RazorpayClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayClient.class);

    private final RazorpayProperties props;
    private final InfluoraEnvironment environment;
    private volatile com.razorpay.RazorpayClient sdkClient;

    public RazorpayClient(RazorpayProperties props, InfluoraEnvironment environment) {
        this.props = props;
        this.environment = environment;
    }

    /**
     * W0-4: fails fast (instead of silently returning a stub) when Razorpay is unconfigured
     * outside dev. Mirrors {@code Msg91EmailClient}'s {@code environment.isDev()} gate.
     */
    private void requireConfiguredOutsideDev(String operation) {
        if (!environment.isDev()) {
            throw new RazorpayIntegrationException(
                    "Razorpay is not configured (key/secret missing or placeholder) outside dev —"
                            + " refusing to fabricate a result for: "
                            + operation,
                    null);
        }
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    /**
     * True only when this client can both TAKE a payment (keyId/keySecret, see {@link
     * #isConfigured()}) AND CONFIRM it — {@code webhookSecret} is what lets {@code
     * WebhookSignatureVerifier} accept the subsequent {@code subscription.activated}/{@code
     * .charged} webhook. The two are provisioned independently in Razorpay's dashboard (see {@code
     * SubscriptionService#initiateCheckout} call site javadoc for the exact incident this guards
     * against), so this is deliberately a stricter check than {@link #isConfigured()}, not a
     * synonym for it.
     */
    public boolean isFullyConfigured() {
        return isConfigured() && props.getWebhookSecret() != null && !props.getWebhookSecret().isBlank();
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
            requireConfiguredOutsideDev("create order");
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
            requireConfiguredOutsideDev("fetch order");
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

    /**
     * Creates a Razorpay Plan (subscriptions billing template) for the given name/amount/period.
     * Returns a stub plan id when Razorpay credentials are not configured (local/dev), matching
     * {@link #createOrder}'s mock-mode convention.
     *
     * @param amountInPaise already in paise — callers must not multiply by 100 again (see {@code
     *     SubscriptionService#ensureRazorpayPlanId} call site).
     * @param period Razorpay billing period (e.g. {@code "monthly"}, {@code "yearly"}).
     */
    public String createPlan(String name, int amountInPaise, String period) {
        if (!isConfigured()) {
            requireConfiguredOutsideDev("create plan");
            log.info(
                    "[MOCK] Razorpay plan would be created: name={}, amount={}, period={}",
                    name, amountInPaise, period);
            return "plan_stub_" + name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        }

        try {
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("amount", amountInPaise);
            item.put("currency", "INR");

            JSONObject planRequest = new JSONObject();
            planRequest.put("period", period);
            planRequest.put("interval", 1);
            planRequest.put("item", item);

            Plan plan = getSdkClient().plans.create(planRequest);
            String planId = plan.get("id");

            log.debug("Razorpay plan created: planId={}, name={}, amount={}", planId, name, amountInPaise);

            return planId;
        } catch (RazorpayException e) {
            log.error(
                    "Razorpay plan creation failed: name={}, amount={}, period={}, error={}",
                    name, amountInPaise, period, e.getMessage());
            throw new RazorpayIntegrationException("Failed to create Razorpay plan", e);
        }
    }

    /**
     * Creates a Razorpay Subscription against an existing plan and returns its hosted-checkout
     * {@code short_url}. Returns a stub result when Razorpay credentials are not configured
     * (local/dev), matching {@link #createOrder}'s mock-mode convention.
     */
    public SubscriptionResult createSubscription(String razorpayPlanId, int totalCount, JSONObject notes) {
        if (!isConfigured()) {
            requireConfiguredOutsideDev("create subscription");
            log.info(
                    "[MOCK] Razorpay subscription would be created: planId={}, totalCount={}",
                    razorpayPlanId, totalCount);
            String stubId = "sub_stub_" + razorpayPlanId;
            return new SubscriptionResult(stubId, "created", "https://rzp.io/i/" + stubId);
        }

        try {
            JSONObject request = new JSONObject();
            request.put("plan_id", razorpayPlanId);
            request.put("total_count", totalCount);
            request.put("customer_notify", 1);
            if (notes != null) {
                request.put("notes", notes);
            }

            Subscription subscription = getSdkClient().subscriptions.create(request);
            String subscriptionId = subscription.get("id");
            String status = subscription.get("status");
            String shortUrl = subscription.get("short_url");

            log.debug(
                    "Razorpay subscription created: subscriptionId={}, status={}, planId={}",
                    subscriptionId, status, razorpayPlanId);

            return new SubscriptionResult(subscriptionId, status, shortUrl);
        } catch (RazorpayException e) {
            log.error(
                    "Razorpay subscription creation failed: planId={}, totalCount={}, error={}",
                    razorpayPlanId, totalCount, e.getMessage());
            throw new RazorpayIntegrationException("Failed to create Razorpay subscription", e);
        }
    }

    /**
     * Cancels a Razorpay subscription. {@code cancelAtCycleEnd=true} lets the subscription keep
     * working until the current paid period elapses (the only mode {@code
     * SubscriptionService#cancel} uses — never a hard mid-cycle cancel, plan §1.1). No-ops (logs
     * only) when Razorpay credentials are not configured (local/dev).
     */
    public void cancelSubscription(String razorpaySubscriptionId, boolean cancelAtCycleEnd) {
        if (!isConfigured()) {
            requireConfiguredOutsideDev("cancel subscription");
            log.info(
                    "[MOCK] Razorpay subscription would be cancelled: id={}, cancelAtCycleEnd={}",
                    razorpaySubscriptionId, cancelAtCycleEnd);
            return;
        }

        try {
            JSONObject request = new JSONObject();
            request.put("cancel_at_cycle_end", cancelAtCycleEnd ? 1 : 0);
            getSdkClient().subscriptions.cancel(razorpaySubscriptionId, request);
            log.debug(
                    "Razorpay subscription cancelled: id={}, cancelAtCycleEnd={}",
                    razorpaySubscriptionId, cancelAtCycleEnd);
        } catch (RazorpayException e) {
            log.error(
                    "Razorpay subscription cancellation failed: id={}, error={}",
                    razorpaySubscriptionId, e.getMessage());
            throw new RazorpayIntegrationException("Failed to cancel Razorpay subscription", e);
        }
    }

    public record OrderResult(String orderId, String status, String rawResponse) {
        public OrderResult(String orderId, String status) {
            this(orderId, status, null);
        }
    }

    public record SubscriptionResult(String subscriptionId, String status, String shortUrl) {}
}
