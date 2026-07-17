package com.influora.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.config.TrendSparkAiProperties;
import com.influora.integration.ai.dto.TrendSparkAiDtos.ErrorResponse;
import com.influora.integration.ai.dto.TrendSparkAiDtos.NudgeRequest;
import com.influora.integration.ai.dto.TrendSparkAiDtos.NudgeResponse;
import com.influora.integration.ai.dto.TrendSparkAiDtos.VideoRef;
import com.influora.service.integration.BrandSafetyServiceTokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * HTTP client for influora-ai's {@code POST /internal/trendspark/nudge} — the ONE AI phrasing
 * call in the Trend-Spark flow (schema lock §4, Ash builds the Python endpoint in T8). Mirrors
 * {@link BrandSafetyAiClient}'s conventions (plain {@code java.net.http.HttpClient}, Jackson
 * {@code ObjectMapper}, same service-token direction — reuses {@link
 * BrandSafetyServiceTokenService} rather than minting a parallel signing secret for the same
 * Spring-&gt;influora-ai leg).
 *
 * <p><b>Never throws.</b> Unlike {@code BrandSafetyAiClient}, every failure mode here (transport
 * error, non-200, malformed body) returns {@code null} so {@code TrendSparkNudgeService} falls
 * back to a deterministic templated message — the user must never see an error (schema lock §4).
 *
 * <p><b>Output re-validation (the hallucination kill-switch / injection defense, schema lock
 * §4/§5.3):</b> any {@code video_id} in the response NOT present in the request's video set is
 * dropped before the caller ever sees it. The response DTO has no price field at all, so there is
 * nothing to scrub structurally — price shown to the user always comes from the persisted catalog
 * row, never from this call.
 */
@Component
@Lazy
public class TrendSparkAiClient {

    private static final Logger log = LoggerFactory.getLogger(TrendSparkAiClient.class);
    private static final String PATH = "/internal/trendspark/nudge";

    private final TrendSparkAiProperties props;
    private final BrandSafetyServiceTokenService tokenService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public TrendSparkAiClient(TrendSparkAiProperties props, BrandSafetyServiceTokenService tokenService) {
        this(
                props,
                tokenService,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
                        .build());
    }

    /** Package-visible constructor for tests to inject a mocked {@link HttpClient}. */
    TrendSparkAiClient(
            TrendSparkAiProperties props,
            BrandSafetyServiceTokenService tokenService,
            HttpClient httpClient) {
        this.props = props;
        this.tokenService = tokenService;
        this.httpClient = httpClient;
    }

    /** Result of a successful, re-validated AI phrasing call. */
    public record NudgeCopy(String message, List<String> videoIds) {}

    /**
     * Requests phrasing for a nudge already fully decided by Java rules. Returns {@code null} on
     * ANY failure (transport, non-200, malformed/missing body, unparseable JSON) — callers must
     * treat {@code null} as "use the templated fallback," never propagate an error to the brand.
     */
    public NudgeCopy requestPhrasing(
            String workspaceId,
            String brandName,
            String campaignType,
            String trendText,
            String mode,
            List<VideoRef> videos) {
        if (workspaceId == null || workspaceId.isBlank()) {
            log.warn("TrendSparkAiClient: missing workspaceId, skipping AI call");
            return null;
        }

        Set<String> sentVideoIds = new HashSet<>();
        if (videos != null) {
            for (VideoRef v : videos) {
                if (v != null && v.videoId() != null) {
                    sentVideoIds.add(v.videoId());
                }
            }
        }

        String token;
        String requestBody;
        try {
            token = tokenService.mint(workspaceId);
            requestBody =
                    objectMapper.writeValueAsString(
                            new NudgeRequest(workspaceId, brandName, campaignType, trendText, mode, videos));
        } catch (Exception e) {
            log.warn("TrendSparkAiClient: failed to build request for workspace={}: {}", workspaceId, e.getMessage());
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
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn(
                    "TrendSparkAiClient: transport failure calling {} for workspace={}: {}",
                    PATH,
                    workspaceId,
                    e.getMessage());
            return null;
        }

        if (response.statusCode() != 200) {
            log.warn(
                    "TrendSparkAiClient: non-200 response from {} for workspace={}, status={}, code={}",
                    PATH,
                    workspaceId,
                    response.statusCode(),
                    extractErrorCode(response.body()));
            return null;
        }

        NudgeResponse parsed;
        try {
            parsed = objectMapper.readValue(response.body(), NudgeResponse.class);
        } catch (Exception e) {
            log.warn("TrendSparkAiClient: malformed response body from {} for workspace={}", PATH, workspaceId);
            return null;
        }

        if (parsed == null
                || !parsed.success()
                || parsed.data() == null
                || parsed.data().message() == null
                || parsed.data().message().isBlank()) {
            log.warn("TrendSparkAiClient: response shape unexpected/empty message for workspace={}", workspaceId);
            return null;
        }

        // Hallucination kill-switch: drop any video_id the model returned that we didn't send.
        List<String> rawIds = parsed.data().videoIds();
        List<String> validatedIds = new ArrayList<>();
        if (rawIds != null) {
            for (String id : rawIds) {
                if (id != null && sentVideoIds.contains(id)) {
                    validatedIds.add(id);
                } else if (id != null) {
                    log.warn(
                            "TrendSparkAiClient: dropped hallucinated video_id from AI response for workspace={}",
                            workspaceId);
                }
            }
        }

        return new NudgeCopy(parsed.data().message(), validatedIds);
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
