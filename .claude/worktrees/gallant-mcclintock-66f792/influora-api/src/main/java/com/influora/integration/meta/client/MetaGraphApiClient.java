package com.influora.integration.meta.client;

import com.influora.config.MetaApiProperties;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaPermissionDeniedException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.exception.MetaTokenExpiredException;
import com.influora.integration.meta.service.MetaRateLimitTracker;
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
    private static final String BASE_URL = "https://graph.facebook.com";
    private static final String USAGE_HEADER = "X-Business-Use-Case-Usage";

    private final MetaApiProperties props;
    private final MetaRateLimitTracker rateLimitTracker;
    private final RestClient restClient;

    public MetaGraphApiClient(MetaApiProperties props, MetaRateLimitTracker rateLimitTracker) {
        this.props = props;
        this.rateLimitTracker = rateLimitTracker;
        this.restClient =
                RestClient.builder()
                        .baseUrl(BASE_URL + "/" + props.getGraphApiVersion())
                        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .build();
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
                    restClient
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
