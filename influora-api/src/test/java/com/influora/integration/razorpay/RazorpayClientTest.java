package com.influora.integration.razorpay;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.config.InfluoraEnvironment;
import com.influora.config.RazorpayProperties;
import java.math.BigDecimal;
import java.time.Instant;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * W0-4 — an unconfigured {@link RazorpayClient} (blank/placeholder key-id/key-secret) used to
 * return a mock {@code *_stub_*} result unconditionally, in every environment. Mirrors {@code
 * Msg91EmailClientTest}'s dev-vs-non-dev split: dev keeps the stub-mock convenience for local/test
 * runs without live Razorpay credentials; every other environment must fail fast instead of
 * fabricating escrow-order/plan/subscription state.
 *
 * <p>Only the unconfigured path is exercised here — the configured/SDK path would require a real
 * network call to the Razorpay SDK and is out of scope for a Mockito unit test (Testcontainers/a
 * live boot is not available in this environment; see {@code TASK-PRODUCTION-READINESS-FIXES.md}
 * Wave 0 rules of engagement).
 */
class RazorpayClientTest {

    private RazorpayProperties unconfiguredProps;
    private RazorpayClient devClient;
    private RazorpayClient prodClient;

    @BeforeEach
    void setUp() {
        unconfiguredProps = new RazorpayProperties();
        // Defaults are already blank ("") — RazorpayProperties#isConfigured() is false.

        devClient = new RazorpayClient(unconfiguredProps, new InfluoraEnvironment(withProfiles("dev")));
        prodClient = new RazorpayClient(unconfiguredProps, new InfluoraEnvironment(withProfiles("prod")));
    }

    @Test
    @DisplayName("createOrder: unconfigured + dev returns a mock stub, does not throw")
    void createOrder_unconfiguredDev_returnsStub() {
        RazorpayClient.OrderResult result =
                devClient.createOrder(new BigDecimal("100.00"), "INR", "receipt-1");
        assertTrue(result.orderId().startsWith("order_stub_"));
    }

    @Test
    @DisplayName("createOrder: unconfigured + prod fails fast instead of stubbing")
    void createOrder_unconfiguredProd_throws() {
        RazorpayIntegrationException ex =
                assertThrows(
                        RazorpayIntegrationException.class,
                        () -> prodClient.createOrder(new BigDecimal("100.00"), "INR", "receipt-1"));
        assertTrue(ex.getMessage().contains("not configured"));
    }

    @Test
    @DisplayName("fetchOrder: unconfigured + dev returns a mock stub, does not throw")
    void fetchOrder_unconfiguredDev_returnsStub() {
        assertDoesNotThrow(() -> devClient.fetchOrder("order_123"));
    }

    @Test
    @DisplayName("fetchOrder: unconfigured + prod fails fast")
    void fetchOrder_unconfiguredProd_throws() {
        assertThrows(RazorpayIntegrationException.class, () -> prodClient.fetchOrder("order_123"));
    }

    @Test
    @DisplayName("createPlan: unconfigured + dev returns a mock stub, does not throw")
    void createPlan_unconfiguredDev_returnsStub() {
        String planId = devClient.createPlan("Pro Plan", 99900, "monthly");
        assertTrue(planId.startsWith("plan_stub_"));
    }

    @Test
    @DisplayName("createPlan: unconfigured + prod fails fast instead of stubbing")
    void createPlan_unconfiguredProd_throws() {
        assertThrows(
                RazorpayIntegrationException.class,
                () -> prodClient.createPlan("Pro Plan", 99900, "monthly"));
    }

    @Test
    @DisplayName("createSubscription: unconfigured + dev returns a mock stub, does not throw")
    void createSubscription_unconfiguredDev_returnsStub() {
        RazorpayClient.SubscriptionResult result =
                devClient.createSubscription("plan_stub_pro", 12, new JSONObject());
        assertTrue(result.subscriptionId().startsWith("sub_stub_"));
    }

    @Test
    @DisplayName("createSubscription: unconfigured + prod fails fast instead of stubbing")
    void createSubscription_unconfiguredProd_throws() {
        assertThrows(
                RazorpayIntegrationException.class,
                () -> prodClient.createSubscription("plan_stub_pro", 12, new JSONObject()));
    }

    @Test
    @DisplayName("cancelSubscription: unconfigured + dev no-ops, does not throw")
    void cancelSubscription_unconfiguredDev_noOps() {
        assertDoesNotThrow(() -> devClient.cancelSubscription("sub_stub_pro", true));
    }

    @Test
    @DisplayName("cancelSubscription: unconfigured + prod fails fast instead of silently no-op'ing")
    void cancelSubscription_unconfiguredProd_throws() {
        assertThrows(
                RazorpayIntegrationException.class,
                () -> prodClient.cancelSubscription("sub_stub_pro", true));
    }

    @Test
    @DisplayName("fetchSubscription: unconfigured + dev returns a mock 'active' stub with no period, does not throw")
    void fetchSubscription_unconfiguredDev_returnsStub() {
        RazorpayClient.SubscriptionSnapshot snapshot = devClient.fetchSubscription("sub_stub_pro");
        assertEquals("active", snapshot.status());
        assertNull(snapshot.currentStart());
        assertNull(snapshot.currentEnd());
    }

    @Test
    @DisplayName("fetchSubscription: unconfigured + prod fails fast instead of stubbing")
    void fetchSubscription_unconfiguredProd_throws() {
        assertThrows(RazorpayIntegrationException.class, () -> prodClient.fetchSubscription("sub_stub_pro"));
    }

    /**
     * F-0860 — {@link RazorpayClient#parseSubscriptionSnapshot} is exercised against a REAL {@code
     * com.razorpay.Subscription} built from a hand-written JSON payload shaped like Razorpay's
     * actual subscription-fetch response, not a Mockito mock of the SDK — a mock would stub out
     * exactly the parsing layer this test exists to cover (see that method's own javadoc). {@code
     * com.razorpay.Subscription}'s constructor is public, so this is possible without any network
     * call or SDK client wiring.
     */
    @Test
    @DisplayName("parseSubscriptionSnapshot: parses a realistic active-subscription payload")
    void parseSubscriptionSnapshot_realisticActivePayload() {
        JSONObject payload = new JSONObject();
        payload.put("id", "sub_ABC123realistic");
        payload.put("plan_id", "plan_DEF456realistic");
        payload.put("status", "active");
        payload.put("current_start", 1700000000L);
        payload.put("current_end", 1702592000L);
        payload.put("total_count", 120);
        payload.put("paid_count", 3);

        com.razorpay.Subscription sdkSubscription = new com.razorpay.Subscription(payload);
        RazorpayClient.SubscriptionSnapshot snapshot = RazorpayClient.parseSubscriptionSnapshot(sdkSubscription);

        assertEquals("active", snapshot.status());
        assertEquals("plan_DEF456realistic", snapshot.planId());
        assertEquals(Instant.ofEpochSecond(1700000000L), snapshot.currentStart());
        assertEquals(Instant.ofEpochSecond(1702592000L), snapshot.currentEnd());
    }

    /**
     * F-0860 — Razorpay's own docs show {@code current_end} can be absent on some subscription
     * states (e.g. immediately after creation, before the first charge). The job (F-0860) must
     * fall back to its own estimate in exactly this case, so the parser must surface {@code null}
     * rather than throwing or defaulting to a wrong value.
     */
    @Test
    @DisplayName("parseSubscriptionSnapshot: a payload missing current_end parses to a null currentEnd, not a thrown exception")
    void parseSubscriptionSnapshot_missingCurrentEnd() {
        JSONObject payload = new JSONObject();
        payload.put("id", "sub_ABC123realistic");
        payload.put("plan_id", "plan_DEF456realistic");
        payload.put("status", "pending");
        payload.put("current_start", 1700000000L);
        // current_end intentionally omitted — Razorpay does not always include it.

        com.razorpay.Subscription sdkSubscription = new com.razorpay.Subscription(payload);
        RazorpayClient.SubscriptionSnapshot snapshot = RazorpayClient.parseSubscriptionSnapshot(sdkSubscription);

        assertEquals("pending", snapshot.status());
        assertEquals(Instant.ofEpochSecond(1700000000L), snapshot.currentStart());
        assertNull(snapshot.currentEnd());
    }

    @Test
    @DisplayName("parseSubscriptionSnapshot: a payload missing status/plan_id entirely parses to nulls, not a thrown exception")
    void parseSubscriptionSnapshot_missingStatusAndPlan() {
        JSONObject payload = new JSONObject();
        payload.put("id", "sub_ABC123realistic");

        com.razorpay.Subscription sdkSubscription = new com.razorpay.Subscription(payload);
        RazorpayClient.SubscriptionSnapshot snapshot = RazorpayClient.parseSubscriptionSnapshot(sdkSubscription);

        assertNull(snapshot.status());
        assertNull(snapshot.planId());
        assertNull(snapshot.currentStart());
        assertNull(snapshot.currentEnd());
    }

    private static MockEnvironment withProfiles(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }
}
