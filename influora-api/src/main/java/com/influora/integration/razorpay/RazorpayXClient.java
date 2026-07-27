package com.influora.integration.razorpay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.config.InfluoraEnvironment;
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
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p><b>W0-4 (2026-07-15):</b> same fail-fast-outside-dev gate as {@link RazorpayClient} — see
 * that class's javadoc. An unconfigured RazorpayX client used to fabricate {@code *_stub_*}
 * contact/fund-account/payout ids in every environment; that meant a real creator payout in
 * prod/staging could silently "succeed" against nothing.
 */
@Component
public class RazorpayXClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayXClient.class);

    /**
     * [FIX: IMPS cap, 2026-07-27] IMPS caps at ₹5,00,000 per transaction — RazorpayX rejects (or
     * fails) any payout above that amount if sent with {@code mode=IMPS}. This used to be
     * hardcoded to {@code "IMPS"} unconditionally in {@link #initiatePayout}, so any single payout
     * over the cap would fail at the gateway with no amount-based fallback. NEFT has no
     * per-transaction cap and has settled 24x7 same-day since RBI's December 2019 mandate, so it's
     * the correct fallback above this ceiling — RTGS is unneeded extra complexity here.
     */
    static final BigDecimal IMPS_MAX_AMOUNT = new BigDecimal("500000.00");

    private final RazorpayProperties props;
    private final InfluoraEnvironment environment;
    // Built on first use, not in the constructor: HttpClient.build() spins up the underlying
    // selector thread + NIO loopback pipe, and doing that at bean-construction time makes
    // application BOOT depend on outbound-HTTP plumbing this class may never use in a given
    // deployment.
    private volatile HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public RazorpayXClient(RazorpayProperties props, InfluoraEnvironment environment) {
        this(props, environment, null);
    }

    /**
     * Package-visible constructor for tests to inject a mocked {@link HttpClient} — mirrors
     * {@code BrandSafetyAiClient}'s test-seam convention. Needed because the public constructor's
     * real {@code HttpClient.newBuilder().build()} opens an NIO selector at construction time,
     * which the unconfigured-path unit tests (W0-4) never need to reach but would otherwise pay
     * for/be blocked on in a sandboxed test runner with no loopback socket access.
     */
    RazorpayXClient(RazorpayProperties props, InfluoraEnvironment environment, HttpClient httpClient) {
        this.props = props;
        this.environment = environment;
        this.httpClient = httpClient;
    }

    private HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                }
                client = httpClient;
            }
        }
        return client;
    }

    /**
     * W0-4: fails fast (instead of silently returning a stub) when RazorpayX is unconfigured
     * outside dev. Mirrors {@link RazorpayClient#requireConfiguredOutsideDev(String)}.
     */
    private void requireConfiguredOutsideDev(String operation) {
        if (!environment.isDev()) {
            throw new RazorpayIntegrationException(
                    "RazorpayX is not configured (key/secret/payout account missing or placeholder)"
                            + " outside dev — refusing to fabricate a result for: "
                            + operation,
                    null);
        }
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
            requireConfiguredOutsideDev("initiate payout");
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
        requestBody.put("mode", resolvePayoutMode(amountInRupees));
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
                    httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            requireSuccessStatus(response, "payout initiation");

            String payoutId = extractPayoutId(response.body());
            log.debug("RazorpayX payout initiated: payoutId={}, amount={}", payoutId, amountInPaise);

            return new PayoutResult(payoutId, "queued", response.body());
        } catch (RazorpayIntegrationException e) {
            throw e;
        } catch (Exception e) {
            log.error("RazorpayX payout initiation failed: fundAccount={}, amount={}, error={}",
                    fundAccountId, amountInRupees, e.getMessage());
            throw new RazorpayIntegrationException("Failed to initiate RazorpayX payout", e);
        }
    }

    /**
     * Creates (or would create) a RazorpayX Contact for a payee — the first step of lazily
     * provisioning a fund account (P2-12). Returns a deterministic stub id when RazorpayX is not
     * configured, mirroring {@link #initiatePayout}'s mock-stub fallback so non-configured
     * dev/test environments behave the same way.
     *
     * <p>[SEC: PII path] {@code email} and {@code name} are sent to Razorpay verbatim — this
     * method must never be called with client-unvalidated input.
     */
    public String createContact(String name, String email, String referenceId) {
        if (!isConfigured()) {
            requireConfiguredOutsideDev("create contact");
            log.info("[MOCK] RazorpayX contact would be created: name={}, referenceId={}", name, referenceId);
            return "contact_stub_" + referenceId;
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("name", name);
        requestBody.put("email", email);
        requestBody.put("type", "vendor");
        requestBody.put("reference_id", referenceId);
        String body = writeJson(requestBody);

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(props.getPayoutApiBaseUrl() + "/contacts"))
                        .header("Authorization", basicAuthHeader())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();

        try {
            HttpResponse<String> response =
                    httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            requireSuccessStatus(response, "contact creation");
            String contactId = extractPayoutId(response.body());
            log.debug("RazorpayX contact created: contactId={}", contactId);
            return contactId;
        } catch (RazorpayIntegrationException e) {
            throw e;
        } catch (Exception e) {
            log.error("RazorpayX contact creation failed: referenceId={}, error={}", referenceId, e.getMessage());
            throw new RazorpayIntegrationException("Failed to create RazorpayX contact", e);
        }
    }

    /**
     * Creates (or would create) a RazorpayX Fund Account bound to an existing contact — the
     * second step of lazily provisioning a payout destination (P2-12). {@code accountType} is
     * this codebase's already-mapped Razorpay value ({@code "bank_account"} or {@code "vpa"} —
     * see {@code RazorpayFundAccountService.determineAccountType}). Returns a deterministic stub
     * id when RazorpayX is not configured, mirroring {@link #initiatePayout}'s mock-stub fallback.
     *
     * <p>[SEC: PII path] {@code accountNumber}/{@code ifsc} are already-decrypted bank details —
     * this method must never log them.
     */
    public String createFundAccount(
            String contactId, String accountNumber, String ifsc, String accountType) {
        if (!isConfigured()) {
            requireConfiguredOutsideDev("create fund account");
            log.info("[MOCK] RazorpayX fund account would be created: contactId={}, accountType={}",
                    contactId, accountType);
            return "fund_account_stub_" + contactId;
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contact_id", contactId);
        requestBody.put("account_type", accountType);
        if ("vpa".equals(accountType)) {
            Map<String, Object> vpa = new LinkedHashMap<>();
            vpa.put("address", accountNumber);
            requestBody.put("vpa", vpa);
        } else {
            Map<String, Object> bankAccount = new LinkedHashMap<>();
            bankAccount.put("name", "Creator Payout Account");
            bankAccount.put("ifsc", ifsc);
            bankAccount.put("account_number", accountNumber);
            requestBody.put("bank_account", bankAccount);
        }
        String body = writeJson(requestBody);

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(props.getPayoutApiBaseUrl() + "/fund_accounts"))
                        .header("Authorization", basicAuthHeader())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();

        try {
            HttpResponse<String> response =
                    httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            requireSuccessStatus(response, "fund account creation");
            String fundAccountId = extractPayoutId(response.body());
            log.debug("RazorpayX fund account created: fundAccountId={}", fundAccountId);
            return fundAccountId;
        } catch (RazorpayIntegrationException e) {
            throw e;
        } catch (Exception e) {
            log.error("RazorpayX fund account creation failed: contactId={}, error={}", contactId, e.getMessage());
            throw new RazorpayIntegrationException("Failed to create RazorpayX fund account", e);
        }
    }

    /**
     * Fetches an existing payout by ID. Useful for status verification.
     */
    public PayoutResult fetchPayout(String payoutId) {
        if (!isConfigured()) {
            requireConfiguredOutsideDev("fetch payout");
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
                    httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            requireSuccessStatus(response, "payout fetch");
            return new PayoutResult(payoutId, extractStatus(response.body()), response.body());
        } catch (RazorpayIntegrationException e) {
            throw e;
        } catch (Exception e) {
            log.error("RazorpayX payout fetch failed: payoutId={}, error={}", payoutId, e.getMessage());
            throw new RazorpayIntegrationException("Failed to fetch RazorpayX payout", e);
        }
    }

    /**
     * [FIX: IMPS cap, 2026-07-27] {@code IMPS} for anything at or under the ₹5,00,000 cap,
     * {@code NEFT} above it. Package-private static so it's directly unit-testable without
     * standing up the HTTP transport.
     */
    static String resolvePayoutMode(BigDecimal amountInRupees) {
        return amountInRupees.compareTo(IMPS_MAX_AMOUNT) > 0 ? "NEFT" : "IMPS";
    }

    /**
     * [SEC: Vikram, P3(a) fix] Fails closed on any non-2xx RazorpayX HTTP response, BEFORE the
     * caller parses {@code id}/{@code status} out of the body. Previously every method here parsed
     * the response body unconditionally regardless of status code -- a 4xx/5xx error response (e.g.
     * an auth failure, a malformed payout request, an account-not-found) has no {@code id}/{@code
     * status} field, so {@link #extractPayoutId}/{@link #extractStatus} silently fell through to
     * their {@code "unknown"} defaults and the caller received what LOOKED like a successful {@code
     * PayoutResult} for a payout/contact/fund-account that was never actually created. Never logs
     * {@code response.body()} -- RazorpayX error payloads can echo back request fields (bank
     * account/VPA details, contact PII); only the HTTP status code is logged.
     */
    private void requireSuccessStatus(HttpResponse<String> response, String operation) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            log.error("RazorpayX {} failed: httpStatus={}", operation, status);
            throw new RazorpayIntegrationException(
                    "RazorpayX " + operation + " failed with HTTP status " + status, null);
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
