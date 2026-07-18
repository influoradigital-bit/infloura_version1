package com.influora.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.config.BrandSafetyAiProperties;
import com.influora.integration.ai.dto.BrandSafetyDtos.BrandSafetyRequest;
import com.influora.integration.ai.dto.BrandSafetyDtos.BrandSafetyResponse;
import com.influora.integration.ai.dto.BrandSafetyDtos.ClassifiedItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.ContentItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.ErrorResponse;
import com.influora.service.integration.BrandSafetyServiceTokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * HTTP client for influora-ai's {@code POST /internal/brand-safety} (Wave C task C3). Mirrors
 * {@code RazorpayXClient}'s conventions: plain {@code java.net.http.HttpClient} (the established
 * pattern for outbound REST calls in this codebase — no WebClient/RestTemplate/RestClient
 * dependency exists in {@code pom.xml}), Jackson {@code ObjectMapper} for (de)serialization, one
 * unchecked exception type ({@link BrandSafetyAiException}) wrapping every transport/parsing/HTTP
 * failure so the caller ({@code BrandSafetyScoreService}) can catch a single type for graceful
 * degradation.
 *
 * <p><b>Caption egress discipline (Kabir's C1 sign-off MED-1 condition):</b> this class does
 * NOT log the request body (which contains raw captions) at any log level, and does not cache
 * anything — every call is a fresh, synchronous HTTP round-trip with no retained state. Only
 * counts/ids/status codes are logged, mirroring {@code MetricsPollingJob}'s "never log caption"
 * discipline.
 */
@Component
@Lazy
public class BrandSafetyAiClient {

    private static final Logger log = LoggerFactory.getLogger(BrandSafetyAiClient.class);
    private static final String PATH = "/internal/brand-safety";

    private final BrandSafetyAiProperties props;
    private final BrandSafetyServiceTokenService tokenService;
    // Built on first use, not in the constructor: HttpClient.build() spins up the underlying
    // selector thread + NIO loopback pipe, and doing that at bean-construction time makes
    // application BOOT depend on outbound-HTTP plumbing this class may never use in a given
    // deployment.
    private volatile HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public BrandSafetyAiClient(BrandSafetyAiProperties props, BrandSafetyServiceTokenService tokenService) {
        this(props, tokenService, null);
    }

    /** Package-visible constructor for tests to inject a mocked {@link HttpClient}. */
    BrandSafetyAiClient(
            BrandSafetyAiProperties props,
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
     * Classifies a batch of a single creator's content items. Batch size must already respect
     * {@link BrandSafetyAiProperties#getMaxItemsPerCall()} — callers ({@code
     * BrandSafetyScoreService}) are responsible for chunking; this method sends exactly one HTTP
     * call for the items given.
     *
     * @throws BrandSafetyAiException on any transport failure, non-2xx response, or a response
     *     body that doesn't match the expected shape. Never returns a partial/best-effort result
     *     — callers must treat any exception as "no score available this run".
     */
    public List<ClassifiedItem> classify(String workspaceId, List<ContentItem> items) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new BrandSafetyAiException("workspaceId is required");
        }
        if (items == null || items.isEmpty()) {
            throw new BrandSafetyAiException("items must not be empty");
        }
        if (items.size() > props.getMaxItemsPerCall()) {
            throw new BrandSafetyAiException(
                    "items exceeds max batch size of " + props.getMaxItemsPerCall());
        }

        String token = tokenService.mint(workspaceId);
        String requestBody = writeJson(new BrandSafetyRequest(workspaceId, items));

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
            // Deliberately no caption/body logging here — only workspace + item count.
            log.warn(
                    "BrandSafetyAiClient: transport failure calling {} for workspace={}, itemCount={}: {}",
                    PATH,
                    workspaceId,
                    items.size(),
                    e.getMessage());
            throw new BrandSafetyAiException("influora-ai brand-safety call failed", e);
        }

        if (response.statusCode() != 200) {
            String code = extractErrorCode(response.body());
            log.warn(
                    "BrandSafetyAiClient: non-200 response from {} for workspace={}, itemCount={}, status={}, code={}",
                    PATH,
                    workspaceId,
                    items.size(),
                    response.statusCode(),
                    code);
            throw new BrandSafetyAiException(
                    "influora-ai brand-safety call returned status "
                            + response.statusCode()
                            + (code != null ? " (" + code + ")" : ""));
        }

        BrandSafetyResponse parsed = readResponse(response.body());
        if (parsed == null
                || !parsed.success()
                || parsed.data() == null
                || parsed.data().items() == null
                || parsed.data().items().size() != items.size()) {
            log.warn(
                    "BrandSafetyAiClient: malformed response shape from {} for workspace={}, itemCount={}",
                    PATH,
                    workspaceId,
                    items.size());
            throw new BrandSafetyAiException("influora-ai brand-safety response shape was unexpected");
        }

        return parsed.data().items();
    }

    private String writeJson(BrandSafetyRequest requestDto) {
        try {
            return objectMapper.writeValueAsString(requestDto);
        } catch (Exception e) {
            throw new BrandSafetyAiException("Failed to serialize brand-safety request", e);
        }
    }

    private BrandSafetyResponse readResponse(String rawJson) {
        try {
            return objectMapper.readValue(rawJson, BrandSafetyResponse.class);
        } catch (Exception e) {
            return null;
        }
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
