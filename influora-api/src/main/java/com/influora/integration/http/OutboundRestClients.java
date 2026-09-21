package com.influora.integration.http;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * EV-045 — the one place that builds a {@link RestClient} with explicit connect + read timeouts.
 *
 * <p><b>The defect this closes.</b> Five outbound clients were built with a bare {@code
 * RestClient.builder().build()} and therefore inherited the JDK {@code HttpClient}'s defaults:
 * <b>no read timeout at all</b>. Those five were {@code MetaGraphApiClient}, {@code
 * MetaOAuthService}, {@code ShopifyOAuthService}, {@code ShopifyOrderOwnershipVerifier} and {@code
 * ShopifyWebhookRegistrar} — every one of them called from a Tomcat request thread while a user
 * waits. A hung Meta or Shopify endpoint (not a refused connection — a TCP connection that accepts
 * and then never answers) pinned one request thread per call for as long as the provider stayed
 * hung, and the pool is finite: enough of them and the API stops serving requests that have
 * nothing to do with Meta or Shopify at all.
 *
 * <p>The TrendSpark ingest clients ({@code NewsApiTopHeadlinesClient}, {@code TmdbUpcomingClient},
 * {@code YouTubeMostPopularClient}) already did this correctly with a {@code
 * SimpleClientHttpRequestFactory} carrying both timeouts. This class is that same pattern, named
 * and shared, so the next outbound client cannot get it wrong by omission — and so a gate can
 * assert that no {@code RestClient.builder()} in {@code integration/} bypasses it.
 *
 * <p><b>Why {@code SimpleClientHttpRequestFactory}.</b> It is what the working TrendSpark clients
 * use, it honours both timeouts, and it needs no extra dependency. {@code
 * JdkClientHttpRequestFactory} would need the read timeout set separately from the client's
 * connect timeout, which is precisely the kind of half-configured state that produced this bug.
 *
 * <p><b>No retries here.</b> Adding a blind retry to these clients would be wrong: {@code
 * MetaOAuthService}'s code-for-token exchange and {@code ShopifyWebhookRegistrar}'s registration
 * are not safely repeatable, and a retry on a hung provider multiplies the thread-holding time by
 * the retry count — the opposite of the fix. Retry belongs to the idempotent read paths only, and
 * those already run in background jobs rather than request threads.
 */
public final class OutboundRestClients {

    /**
     * Time to establish a TCP connection. Short on purpose: a provider that cannot be reached in 3
     * seconds is down, and waiting longer only holds the request thread. Matches {@code
     * CONNECT_TIMEOUT_SECONDS} in the TrendSpark ingest clients.
     */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * Time to wait for the response once connected. 10 seconds is comfortably above the observed
     * latency of a Meta Graph read or a Shopify Admin API call and well below any sane browser or
     * proxy patience, so the user gets a real error instead of a hang — and, critically, the
     * request thread comes back.
     */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    private OutboundRestClients() {}

    /** A builder with the default timeouts already applied. Callers add base URL/headers. */
    public static RestClient.Builder builder() {
        return builder(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    /** A builder with explicit timeouts, for a call path with a different latency profile. */
    public static RestClient.Builder builder(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }

    /** A ready-built client with the default timeouts. */
    public static RestClient build() {
        return builder().build();
    }
}
