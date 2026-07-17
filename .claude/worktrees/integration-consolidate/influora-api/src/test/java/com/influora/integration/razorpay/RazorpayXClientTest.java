package com.influora.integration.razorpay;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.config.InfluoraEnvironment;
import com.influora.config.RazorpayProperties;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

/**
 * W0-4 — mirrors {@link RazorpayClientTest}: an unconfigured {@link RazorpayXClient} used to
 * fabricate {@code *_stub_*} contact/fund-account/payout ids unconditionally, in every
 * environment. A real creator payout initiated against those fake ids in prod/staging would
 * appear to "succeed" while nothing was ever sent. Dev keeps the stub-mock convenience; every
 * other environment now fails fast.
 *
 * <p>Uses {@link RazorpayXClient}'s package-visible test constructor to inject a mocked {@link
 * HttpClient} (same seam {@code BrandSafetyAiClientTest} uses) — every case here only exercises
 * the unconfigured/stub-vs-throw branch, so the transport is never actually invoked, but building
 * a real {@code HttpClient} eagerly still isn't safe to do in this sandboxed test runner.
 */
@ExtendWith(MockitoExtension.class)
class RazorpayXClientTest {

    @Mock private HttpClient httpClient;

    private RazorpayProperties unconfiguredProps;
    private RazorpayXClient devClient;
    private RazorpayXClient prodClient;

    @BeforeEach
    void setUp() {
        unconfiguredProps = new RazorpayProperties();
        // Defaults are blank ("") -- RazorpayXClient#isConfigured() requires keyId/keySecret AND
        // a non-blank payoutAccountNumber, all unset here.

        devClient =
                new RazorpayXClient(unconfiguredProps, new InfluoraEnvironment(withProfiles("dev")), httpClient);
        prodClient =
                new RazorpayXClient(unconfiguredProps, new InfluoraEnvironment(withProfiles("prod")), httpClient);
    }

    @Test
    @DisplayName("initiatePayout: unconfigured + dev returns a mock stub, does not throw")
    void initiatePayout_unconfiguredDev_returnsStub() {
        RazorpayXClient.PayoutResult result =
                devClient.initiatePayout("fund_acc_1", new BigDecimal("500.00"), "INR", "idem-1");
        assertTrue(result.payoutId().startsWith("payout_stub_"));
    }

    @Test
    @DisplayName("initiatePayout: unconfigured + prod fails fast instead of stubbing")
    void initiatePayout_unconfiguredProd_throws() {
        RazorpayIntegrationException ex =
                assertThrows(
                        RazorpayIntegrationException.class,
                        () ->
                                prodClient.initiatePayout(
                                        "fund_acc_1", new BigDecimal("500.00"), "INR", "idem-1"));
        assertTrue(ex.getMessage().contains("not configured"));
    }

    @Test
    @DisplayName("createContact: unconfigured + dev returns a mock stub, does not throw")
    void createContact_unconfiguredDev_returnsStub() {
        String contactId = devClient.createContact("Jane Creator", "jane@example.com", "ref-1");
        assertTrue(contactId.startsWith("contact_stub_"));
    }

    @Test
    @DisplayName("createContact: unconfigured + prod fails fast instead of stubbing")
    void createContact_unconfiguredProd_throws() {
        assertThrows(
                RazorpayIntegrationException.class,
                () -> prodClient.createContact("Jane Creator", "jane@example.com", "ref-1"));
    }

    @Test
    @DisplayName("createFundAccount: unconfigured + dev returns a mock stub, does not throw")
    void createFundAccount_unconfiguredDev_returnsStub() {
        String fundAccountId =
                devClient.createFundAccount("contact_stub_1", "123456789012", "HDFC0000001", "bank_account");
        assertTrue(fundAccountId.startsWith("fund_account_stub_"));
    }

    @Test
    @DisplayName("createFundAccount: unconfigured + prod fails fast instead of stubbing")
    void createFundAccount_unconfiguredProd_throws() {
        assertThrows(
                RazorpayIntegrationException.class,
                () ->
                        prodClient.createFundAccount(
                                "contact_stub_1", "123456789012", "HDFC0000001", "bank_account"));
    }

    @Test
    @DisplayName("fetchPayout: unconfigured + dev returns a mock stub, does not throw")
    void fetchPayout_unconfiguredDev_returnsStub() {
        assertDoesNotThrow(() -> devClient.fetchPayout("payout_123"));
    }

    @Test
    @DisplayName("fetchPayout: unconfigured + prod fails fast instead of stubbing")
    void fetchPayout_unconfiguredProd_throws() {
        assertThrows(RazorpayIntegrationException.class, () -> prodClient.fetchPayout("payout_123"));
    }

    private static MockEnvironment withProfiles(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }
}
