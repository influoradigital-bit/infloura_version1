package com.influora.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.config.CreatorSuggestionAiProperties;
import com.influora.integration.ai.dto.CreatorSuggestionAiDtos.ErrorResponse;
import com.influora.integration.ai.dto.CreatorSuggestionAiDtos.SuggestionRequest;
import com.influora.integration.ai.dto.CreatorSuggestionAiDtos.SuggestionResponse;
import com.influora.service.integration.CreatorSuggestionServiceTokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * HTTP client for influora-ai's {@code POST /internal/creator-suggestion} — the ONE AI phrasing
 * call in the Creator Co-pilot flow (be-services-plan.md §4). Mirrors {@link TrendSparkAiClient}'s
 * conventions exactly (plain {@code java.net.http.HttpClient}, Jackson {@code ObjectMapper}), but
 * mints via {@link CreatorSuggestionServiceTokenService} (creator scope + {@code
 * creator_profile_id} claim) rather than {@code BrandSafetyServiceTokenService} (service scope +
 * {@code workspace_id} claim) — see that class's javadoc for why a creator has no workspace.
 *
 * <p><b>Never throws.</b> Every failure mode (transport error, non-200, malformed body, empty/
 * blank fields) returns {@code null} so {@code CreatorNudgeService} falls back to a deterministic
 * templated {@code (headline, contentIdea)} tuple — the creator must never see an error.
 *
 * <p><b>No hallucination kill-switch needed over a theme value (unlike Trend-Spark's video-id
 * filter):</b> by the time this client is called, {@code theme} has already been reduced to a
 * closed-vocab member by the caller's own trend/creator theme-match overlap (server-derived, never
 * sent through the model as free text to begin with), and the response contract carries no {@code
 * theme} field to echo back — {@code headline}/{@code contentIdea} are the model's free-text
 * phrasing output, which this class does not attempt to structurally re-validate (same as Trend-
 * Spark's {@code message} field: content-safety is a prompt/prompt-injection concern owned by the
 * Python route, not a structural one this client can enforce).
 */
@Component
@Lazy
public class CreatorSuggestionAiClient {

    private static final Logger log = LoggerFactory.getLogger(CreatorSuggestionAiClient.class);
    private static final String PATH = "/internal/creator-suggestion";

    private final CreatorSuggestionAiProperties props;
    private final CreatorSuggestionServiceTokenService tokenService;
    // Built on first use, not in the constructor — same rationale as TrendSparkAiClient: application
    // BOOT must not depend on outbound-HTTP plumbing this class may never use in a given deployment.
    private volatile HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public CreatorSuggestionAiClient(
            CreatorSuggestionAiProperties props, CreatorSuggestionServiceTokenService tokenService) {
        this(props, tokenService, null);
    }

    /** Package-visible constructor for tests to inject a mocked {@link HttpClient}. */
    CreatorSuggestionAiClient(
            CreatorSuggestionAiProperties props,
            CreatorSuggestionServiceTokenService tokenService,
            HttpClient httpClient) {
        this.props = props;
        this.tokenService = tokenService;
        this.httpClient = httpClient;
    }

    private HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient =
                            HttpClient.newBuilder()
                                    .connectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
                                    .build();
                }
                client = httpClient;
            }
        }
        return client;
    }

    /** Result of a successful AI phrasing call — same two-value tuple shape the templated fallback
     * returns (be-services-plan.md §1 step 6), so {@code CreatorNudgeService} never branches on
     * "AI gives 2 fields, template gives 1". */
    public record SuggestionCopy(String headline, String contentIdea) {}

    /**
     * Requests phrasing for a theme/trend pairing already fully decided by Java rules. Returns
     * {@code null} on ANY failure (transport, non-200, malformed/missing body, blank fields) —
     * callers must treat {@code null} as "use the templated fallback," never propagate an error.
     */
    public SuggestionCopy requestSuggestion(String creatorProfileId, String theme, String trendText) {
        if (creatorProfileId == null || creatorProfileId.isBlank()) {
            log.warn("CreatorSuggestionAiClient: missing creatorProfileId, skipping AI call");
            return null;
        }

        String token;
        String requestBody;
        try {
            token = tokenService.mint(creatorProfileId);
            requestBody =
                    objectMapper.writeValueAsString(
                            new SuggestionRequest(creatorProfileId, theme, trendText));
        } catch (Exception e) {
            log.warn(
                    "CreatorSuggestionAiClient: failed to build request for creator={}: {}",
                    creatorProfileId,
                    e.getMessage());
            return null;
        }

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(props.getBaseUrl() + PATH))
                        .timeout(Duration.ofSeconds(props.getRequestTimeoutSeconds()))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();

        HttpResponse<String> response;
        try {
            response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn(
                    "CreatorSuggestionAiClient: transport failure calling {} for creator={}: {}",
                    PATH,
                    creatorProfileId,
                    e.getMessage());
            return null;
        }

        if (response.statusCode() != 200) {
            log.warn(
                    "CreatorSuggestionAiClient: non-200 response from {} for creator={}, status={}, code={}",
                    PATH,
                    creatorProfileId,
                    response.statusCode(),
                    extractErrorCode(response.body()));
            return null;
        }

        SuggestionResponse parsed;
        try {
            parsed = objectMapper.readValue(response.body(), SuggestionResponse.class);
        } catch (Exception e) {
            log.warn(
                    "CreatorSuggestionAiClient: malformed response body from {} for creator={}",
                    PATH,
                    creatorProfileId);
            return null;
        }

        if (parsed == null
                || !parsed.success()
                || parsed.data() == null
                || parsed.data().headline() == null
                || parsed.data().headline().isBlank()
                || parsed.data().contentIdea() == null
                || parsed.data().contentIdea().isBlank()) {
            log.warn(
                    "CreatorSuggestionAiClient: response shape unexpected/empty for creator={}",
                    creatorProfileId);
            return null;
        }

        return new SuggestionCopy(parsed.data().headline(), parsed.data().contentIdea());
    }

    private String extractErrorCode(String rawJson) {
        try {
            ErrorResponse error = objectMapper.readValue(rawJson, ErrorResponse.class);
            return error.detail() != null ? error.detail().code() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
