package com.influora.integration.meta.client;

import com.influora.config.MetaApiProperties;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaPermissionDeniedException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.exception.MetaTokenExpiredException;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Core authenticated Meta Graph API HTTP client. Every call goes through a pre-flight rate-limit
 * check ({@link MetaRateLimitTracker}) and every response's {@code X-Business-Use-Case-Usage}
 * header is parsed back into the tracker, per business/Instagram account id.
 *
 * <p>Callers (e.g. {@link InstagramInsightsClient}, {@link FacebookPageClient}) never build the
 * base URL or handle raw HTTP errors themselves — this class centralizes both.
 */
@Component
public class MetaGraphApiClient {

    private static final Logger log = LoggerFactory.getLogger(MetaGraphApiClient.class);
    // One host per Instagram Platform configuration (T-IGLOGIN-0820). These are NOT aliases:
    // a Facebook-Login token is rejected by graph.instagram.com and vice versa, so the host is
    // chosen from the token's MetaAuthPath and never from a default.
    private static final String FACEBOOK_BASE_URL = "https://graph.facebook.com";
    private static final String INSTAGRAM_BASE_URL = "https://graph.instagram.com";
    private static final String USAGE_HEADER = "X-Business-Use-Case-Usage";

    private final MetaApiProperties props;
    private final MetaRateLimitTracker rateLimitTracker;

    // Built on first use, not in the constructor: RestClient.build() spins up the underlying
    // JDK HttpClient (selector thread + NIO pipe), and doing that at bean-construction time
    // makes application BOOT depend on outbound-HTTP plumbing this class may never use in a
    // given deployment. Meta calls only happen after a user connects an account, so the first
    // caller pays the (tiny) construction cost instead of every startup risking it.
    //
    // One client per path, built independently: a deploy that only ever serves Facebook-Login
    // creators never constructs the Instagram client at all.
    private final Map<MetaAuthPath, RestClient> restClients = new EnumMap<>(MetaAuthPath.class);

    public MetaGraphApiClient(MetaApiProperties props, MetaRateLimitTracker rateLimitTracker) {
        this.props = props;
        this.rateLimitTracker = rateLimitTracker;
    }

    private RestClient restClient(MetaAuthPath authPath) {
        RestClient client = restClients.get(authPath);
        if (client == null) {
            synchronized (this) {
                client =
                        restClients.computeIfAbsent(
                                authPath,
                                path ->
                                        RestClient.builder()
                                                .baseUrl(baseUrlFor(path) + "/" + props.getGraphApiVersion())
                                                .defaultHeader(
                                                        HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                                                .build());
            }
        }
        return client;
    }

    private static String baseUrlFor(MetaAuthPath authPath) {
        return authPath == MetaAuthPath.INSTAGRAM_LOGIN ? INSTAGRAM_BASE_URL : FACEBOOK_BASE_URL;
    }

    /**
     * Generic authenticated GET with pre-flight rate-limit tracking.
     *
     * @param path Graph API path, e.g. {@code "/17841400000000000?fields=..."} — must already
     *     include the query string except {@code access_token}, which is appended here so it
     *     never appears in caller code or logs.
     * @param accessToken decrypted long-lived user/page access token
     * @param responseType DTO type to deserialize the JSON body into
     * @param businessAccountId Instagram business account id or Facebook Page id used as the
     *     rate-limit tracking key
     */
    public <T> T get(String path, String accessToken, Class<T> responseType, String businessAccountId) {
        return get(path, accessToken, responseType, businessAccountId, MetaAuthPath.FACEBOOK_LOGIN);
    }

    /**
     * As {@link #get(String, String, Class, String)}, but routed to the host that matches the
     * token's origin.
     *
     * @param authPath which configuration minted {@code accessToken} — decides the API host. A
     *     Facebook-Login token sent to {@code graph.instagram.com} (or the reverse) is rejected,
     *     so this must come from the stored token row, never from a default.
     */
    public <T> T get(
            String path,
            String accessToken,
            Class<T> responseType,
            String businessAccountId,
            MetaAuthPath authPath) {
        int currentUsage = rateLimitTracker.getCurrentUsage(businessAccountId);
        if (currentUsage >= props.getRateLimitThrottleThreshold()) {
            throw new MetaRateLimitException(
                    "Rate limit throttle engaged at " + currentUsage + "% for account " + businessAccountId);
        }
        if (currentUsage >= props.getRateLimitAlertThreshold()) {
            log.warn("Meta rate-limit alert: account {} at {}% usage", businessAccountId, currentUsage);
        }

        try {
            ResponseEntity<T> response =
                    restClient(authPath)
                            .get()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path(path)
                                                    .queryParam("access_token", accessToken)
                                                    .build())
                            .retrieve()
                            .toEntity(responseType);

            String usageHeader = response.getHeaders().getFirst(USAGE_HEADER);
            if (usageHeader != null) {
                rateLimitTracker.update(businessAccountId, usageHeader);
            }

            return response.getBody();
        } catch (RestClientResponseException e) {
            throw translate(e, businessAccountId);
        }
    }

    private MetaApiException translate(RestClientResponseException e, String businessAccountId) {
        HttpStatusCode status = e.getStatusCode();
        if (status.value() == 429) {
            rateLimitTracker.markLimited(businessAccountId);
            return new MetaRateLimitException("Meta API rate limit exceeded for account " + businessAccountId, e);
        }
        if (status.value() == 401) {
            return new MetaTokenExpiredException("Meta access token expired or invalid", e);
        }
        if (status.value() == 403) {
            return new MetaPermissionDeniedException("Meta API permission denied (missing scope)", e);
        }
        log.error(
                "Meta Graph API call failed: account={}, status={}, body={}",
                businessAccountId,
                status.value(),
                e.getResponseBodyAsString());
        return new MetaApiException("Meta Graph API call failed with status " + status.value(), e);
    }
}
