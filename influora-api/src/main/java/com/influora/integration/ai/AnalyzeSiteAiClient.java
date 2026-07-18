package com.influora.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.config.AnalyzeSiteAiProperties;
import com.influora.integration.ai.dto.AnalyzeSiteAiDtos.AnalyzeSiteRequest;
import com.influora.integration.ai.dto.AnalyzeSiteAiDtos.AnalyzeSiteResponse;
import com.influora.integration.ai.dto.AnalyzeSiteAiDtos.TransportErrorResponse;
import com.influora.service.integration.BrandSafetyServiceTokenService;
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
 * HTTP client for influora-ai's {@code POST /analyze-site} (H-23 — "analyze-site loop unwired
 * end-to-end"). Mirrors {@link BrandSafetyAiClient}/{@code TrendSparkAiClient}'s conventions:
 * plain {@code java.net.http.HttpClient} (the established outbound-REST pattern in this codebase),
 * Jackson {@code ObjectMapper}, and the same service-token direction (reuses {@link
 * BrandSafetyServiceTokenService} — see that class's javadoc for why signing lives there and not
 * here).
 *
 * <p><b>Caller contract:</b> never throws for an application-level failure (SSRF-blocked URL,
 * empty page, classify failure all come back as HTTP 200 with {@code success: false} from Python
 * — see {@code analyze_site.py}); {@link #analyze} returns that response body as-is so {@link
 * com.influora.service.brand.AnalyzeSiteTriggerService} can distinguish "handled failure with a
 * degraded-mode hint" from "call didn't even complete." Only throws {@link AnalyzeSiteAiException}
 * on a transport failure, a non-200 (auth/validation error), or a response body that doesn't
 * parse — those are genuinely "the call failed," not "the site couldn't be analyzed."
 */
@Component
@Lazy
public class AnalyzeSiteAiClient {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeSiteAiClient.class);
    private static final String PATH = "/analyze-site";

    private final AnalyzeSiteAiProperties props;
    private final BrandSafetyServiceTokenService tokenService;
    // Built on first use, not in the constructor: HttpClient.build() spins up the underlying
    // selector thread + NIO loopback pipe, and doing that at bean-construction time makes
    // application BOOT depend on outbound-HTTP plumbing this class may never use in a given
    // deployment.
    private volatile HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AnalyzeSiteAiClient(AnalyzeSiteAiProperties props, BrandSafetyServiceTokenService tokenService) {
        this(props, tokenService, null);
    }

    /** Package-visible constructor for tests to inject a mocked {@link HttpClient}. */
    AnalyzeSiteAiClient(
            AnalyzeSiteAiProperties props,
            BrandSafetyServiceTokenService tokenService,
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

    /**
     * Calls {@code POST /analyze-site} for the given workspace/URL. Blocking — callers on a
     * request thread must never invoke this synchronously; {@code AnalyzeSiteTriggerService} runs
     * it off-thread (H-23's "async job").
     *
     * @throws AnalyzeSiteAiException on transport failure, non-200 response, or an unparseable
     *     body. A handled "couldn't analyze this site" outcome is NOT an exception — see class
     *     javadoc.
     */
    public AnalyzeSiteResponse analyze(String workspaceId, String url) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new AnalyzeSiteAiException("workspaceId is required");
        }
        if (url == null || url.isBlank()) {
            throw new AnalyzeSiteAiException("url is required");
        }

        String token = tokenService.mint(workspaceId);
        String requestBody = writeJson(new AnalyzeSiteRequest(workspaceId, url));

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
            // Deliberately no URL/body logging beyond the workspace id — the request body is a
            // brand-supplied URL, not raw PII, but keep the discipline consistent with the other
            // AI clients in this package.
            log.warn(
                    "AnalyzeSiteAiClient: transport failure calling {} for workspace={}: {}",
                    PATH,
                    workspaceId,
                    e.getMessage());
            throw new AnalyzeSiteAiException("influora-ai analyze-site call failed", e);
        }

        if (response.statusCode() != 200) {
            String code = extractErrorCode(response.body());
            log.warn(
                    "AnalyzeSiteAiClient: non-200 response from {} for workspace={}, status={}, code={}",
                    PATH,
                    workspaceId,
                    response.statusCode(),
                    code);
            throw new AnalyzeSiteAiException(
                    "influora-ai analyze-site call returned status "
                            + response.statusCode()
                            + (code != null ? " (" + code + ")" : ""));
        }

        AnalyzeSiteResponse parsed = readResponse(response.body());
        if (parsed == null) {
            log.warn(
                    "AnalyzeSiteAiClient: unparseable response body from {} for workspace={}", PATH, workspaceId);
            throw new AnalyzeSiteAiException("influora-ai analyze-site response body was unparseable");
        }
        return parsed;
    }

    private String writeJson(AnalyzeSiteRequest requestDto) {
        try {
            return objectMapper.writeValueAsString(requestDto);
        } catch (Exception e) {
            throw new AnalyzeSiteAiException("Failed to serialize analyze-site request", e);
        }
    }

    private AnalyzeSiteResponse readResponse(String rawJson) {
        try {
            return objectMapper.readValue(rawJson, AnalyzeSiteResponse.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractErrorCode(String rawJson) {
        try {
            TransportErrorResponse error = objectMapper.readValue(rawJson, TransportErrorResponse.class);
            return error.detail() != null ? error.detail().code() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
