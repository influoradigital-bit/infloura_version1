package com.influora.integration.meta.client;

import com.influora.config.MetaApiProperties;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaPermissionDeniedException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.exception.MetaTokenExpiredException;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
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
                            .uri(buildUri(baseUrlFor(authPath) + "/" + props.getGraphApiVersion(), path, accessToken))
                            .retrieve()
                            .toEntity(responseType);

            String usageHeader = response.getHeaders().getFirst(USAGE_HEADER);
            if (usageHeader != null) {
                rateLimitTracker.update(businessAccountId, usageHeader);
            }

            return response.getBody();
        } catch (RestClientResponseException e) {
            throw translate(e, businessAccountId);
        } catch (ResourceAccessException e) {
            // Same defect class as the URI bug above: this class promises callers that a failed
            // Meta call arrives as a MetaApiException, and every caller's error handling is a
            // catch(MetaApiException). A connect/read timeout or DNS failure is a
            // ResourceAccessException, NOT a RestClientResponseException, so before this it flew
            // straight past those handlers and out as a bare 500 INTERNAL_ERROR. Narrow on
            // purpose — a bug in our own code must still surface as a 500 rather than be
            // laundered into "Meta is unavailable".
            throw new MetaApiException(
                    "Meta Graph API call failed to reach " + baseUrlFor(authPath) + " for account " + businessAccountId,
                    e);
        }
    }

    /**
     * Builds the absolute request URI by hand instead of going through {@code RestClient}'s
     * {@code UriBuilder} lambda. The lambda form ({@code uriBuilder.path(path).queryParam(...)
     * .build()}) was wrong for BOTH shapes of path this client is documented to accept, and the
     * two failures had different symptoms:
     *
     * <ul>
     *   <li><b>Braces were parsed as URI template variables.</b> Business Discovery is the only
     *       call that uses Graph's nested-field syntax, {@code business_discovery.username(x)
     *       {id,username,...}}. {@code DefaultUriBuilder.build()} expands templates with an empty
     *       variable map, so {@code {id,username,...}} was read as a placeholder named
     *       {@code "id,username,..."} and build() threw {@code IllegalArgumentException: Not
     *       enough variable values available to expand}. That is a plain RuntimeException, not a
     *       {@link MetaApiException}, so every caller's {@code catch (MetaApiException)} missed it
     *       and it surfaced as a bare 500 INTERNAL_ERROR — the reported symptom on
     *       {@code GET /creators/external/lookup?username=…}. The request never left the JVM.
     *   <li><b>The '?' was percent-encoded into the path.</b> Every call site here passes its
     *       query string inside {@code path} (see the {@code get} javadoc). {@code UriBuilder
     *       .path()} treats its whole argument as a path, and '?' is not legal in a path, so it
     *       was encoded to {@code %3F}: {@code /{id}/insights%3Fmetric=reach&period=day}. Graph
     *       then saw a nonexistent path node and no fields/metric parameters at all.
     * </ul>
     *
     * <p>Splitting on the first '?' and percent-encoding only the characters that are illegal in
     * an RFC 3986 query fixes both: the query survives as a query, and braces travel as
     * {@code %7B}/{@code %7D}, which Graph decodes. The URI is absolute, so {@code RestClient}
     * sends it verbatim (the builder's {@code baseUrl} is left in place as documentation of the
     * host and as a fallback for any future relative-URI caller).
     *
     * @param baseUrl scheme, host and API version, with no trailing slash
     * @param path caller path, optionally carrying its own query string after a '?'
     * @param accessToken appended last as {@code access_token}, form-encoded
     */
    static URI buildUri(String baseUrl, String path, String accessToken) {
        int separator = path.indexOf('?');
        String pathPart = separator < 0 ? path : path.substring(0, separator);
        String queryPart = separator < 0 ? "" : path.substring(separator + 1);
        StringBuilder uri = new StringBuilder(baseUrl).append(pathPart).append('?');
        if (!queryPart.isEmpty()) {
            uri.append(encodeQueryLiteral(queryPart)).append('&');
        }
        // URLEncoder is application/x-www-form-urlencoded, which spells a space "+". That is a
        // form convention, not RFC 3986 — in a bare query component "+" is a literal plus. Meta
        // tokens are URL-safe in practice, but this is the one component whose shape we do not
        // control, so normalise to %20 rather than rely on the far end guessing which spec applies.
        uri.append("access_token=")
                .append(URLEncoder.encode(accessToken, StandardCharsets.UTF_8).replace("+", "%20"));
        return URI.create(uri.toString());
    }

    /**
     * Percent-encodes only what RFC 3986 forbids in a query component. Deliberately NOT a
     * blanket {@code URLEncoder.encode}: the caller hands us an already-assembled query string,
     * so the '&' and '=' separators — and Graph's own ',' '(' ')' '.' ':' — must survive
     * untouched. In practice this encodes the '{' and '}' of the nested-field syntax and any
     * stray whitespace. '%' is intentionally treated as illegal and encoded to {@code %25}: no
     * call site pre-encodes its path, so a literal '%' here is data, not an escape sequence.
     */
    private static String encodeQueryLiteral(String query) {
        StringBuilder encoded = new StringBuilder(query.length() + 16);
        for (byte raw : query.getBytes(StandardCharsets.UTF_8)) {
            int octet = raw & 0xFF;
            char c = (char) octet;
            boolean legal =
                    (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9')
                            || "-._~!$&'()*+,;=:@/?".indexOf(c) >= 0;
            if (legal) {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", octet));
            }
        }
        return encoded.toString();
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
