package com.influora.integration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.config.MeeraChatAiProperties;
import com.influora.service.integration.BrandSafetyServiceTokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * HTTP client for influora-ai's {@code POST /chat} (Wave 3 task A4 — Meera's real model turn,
 * replacing {@code MeeraSessionService#doSendTurn}'s previous hardcoded placeholder echo). Mirrors
 * {@link AnalyzeSiteAiClient}/{@link BrandSafetyAiClient}'s conventions: plain {@code
 * java.net.http.HttpClient} (the established outbound-REST pattern in this codebase), a service
 * token minted by {@link BrandSafetyServiceTokenService} (the SAME Spring-&gt;influora-ai leg every
 * other Java-&gt;Python AI client in this package already uses — {@code
 * app/auth/service_token.py::ENDPOINT_SCOPES["chat"]} accepts {@code scope=service} in addition to
 * the browser's scoped {@code chat:stream} token), one unchecked exception type wrapping every
 * transport/parsing failure.
 *
 * <p><b>{@code /chat} is Server-Sent-Events, not a single JSON response</b> — unlike its siblings.
 * This client makes a synchronous, one-shot call: it sends the request, reads the SSE body to
 * completion, concatenates every {@code token} event's text into the final assistant reply, and
 * returns it. It does NOT stream incrementally to any caller — {@code doSendTurn} needs the
 * complete turn synchronously so it can persist exactly one {@code AiMessage} row before returning
 * the HTTP response (the browser's own real-time experience is unrelated: it still gets a
 * separately-minted {@link com.influora.service.meera.StreamTokenService} token to connect to
 * {@code /chat} directly per Priya's locked streaming architecture — this client is Spring's own
 * synchronous, server-to-server turn, independent of that browser connection).
 *
 * <p><b>Deliberately omits {@code onbehalf_jwt}</b> (sends {@code ""}): this is a
 * service-to-service call, not one made on behalf of a specific browser session. Two consequences,
 * both acceptable for this call site: (1) any tool the model tries to invoke mid-turn will get a
 * {@code 401} tool-result back from Spring's {@code /internal/meera/*} on-behalf gate and the loop
 * degrades gracefully (Claude sees the error and can respond in text); (2) influora-ai's own
 * end-of-stream write-back attempt ({@code app/routes/chat.py}'s {@code
 * spring.persist_assistant_message(...)}) will ALSO fail its on-behalf check and be silently
 * dropped ({@code chat.py} swallows that failure — "persistence failure shouldn't break the
 * stream"). That is intentional, not a bug: it keeps {@code doSendTurn}'s own direct {@code
 * AiMessage} save as the SOLE writer of the assistant turn for this call path, avoiding a duplicate
 * row race between Java's synchronous write and Python's async callback.
 */
@Component
@Lazy
public class MeeraChatAiClient {

    private static final Logger log = LoggerFactory.getLogger(MeeraChatAiClient.class);
    private static final String PATH = "/chat";

    private final MeeraChatAiProperties props;
    private final BrandSafetyServiceTokenService tokenService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public MeeraChatAiClient(MeeraChatAiProperties props, BrandSafetyServiceTokenService tokenService) {
        this(
                props,
                tokenService,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
                        .build());
    }

    /** Package-visible constructor for tests to inject a mocked {@link HttpClient}. */
    MeeraChatAiClient(MeeraChatAiProperties props, BrandSafetyServiceTokenService tokenService, HttpClient httpClient) {
        this.props = props;
        this.tokenService = tokenService;
        this.httpClient = httpClient;
    }

    /** One complete Meera turn's outcome — the concatenated assistant text from every {@code token} SSE event. */
    public record ChatTurnResult(String text) {}

    /**
     * Calls {@code POST /chat} for one turn and blocks until the SSE stream completes, returning
     * the full assistant reply. Blocking — callers on a request thread already accept this
     * (matches {@code doSendTurn}'s pre-existing synchronous style); this is not invoked from an
     * async/scheduled context.
     *
     * @throws MeeraChatAiException on transport failure, a non-200 response, a stream that ends
     *     with an {@code error} SSE event, or a stream that ends with no assistant text at all —
     *     every one of these must be treated as "no reply was generated this turn", never a
     *     partial/best-effort result.
     */
    public ChatTurnResult sendTurn(String workspaceId, Map<String, Object> requestBody) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new MeeraChatAiException("workspaceId is required");
        }

        String token = tokenService.mint(workspaceId);
        String body = writeJson(requestBody);

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(props.getBaseUrl() + PATH))
                        .timeout(Duration.ofSeconds(props.getRequestTimeoutSeconds()))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();

        HttpResponse<Stream<String>> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (Exception e) {
            // Deliberately no request-body logging — it carries the brand's conversation content,
            // not just an id, matching this package's "never log conversation/caption text" rule.
            log.warn("MeeraChatAiClient: transport failure calling {} for workspace={}: {}", PATH, workspaceId, e.getMessage());
            throw new MeeraChatAiException("influora-ai /chat call failed", e);
        }

        if (response.statusCode() != 200) {
            String bodyText = response.body().collect(Collectors.joining("\n"));
            log.warn(
                    "MeeraChatAiClient: non-200 response from {} for workspace={}, status={}",
                    PATH,
                    workspaceId,
                    response.statusCode());
            throw new MeeraChatAiException(
                    "influora-ai /chat call returned status "
                            + response.statusCode()
                            + (bodyText.isBlank() ? "" : " (" + truncate(bodyText, 200) + ")"));
        }

        return parseSse(response.body(), workspaceId);
    }

    /**
     * Consumes the {@code event:}/{@code data:} SSE stream (04-AI-SERVICE-SPEC.md §4 / {@code
     * app/routes/chat.py::sse_event}), accumulating {@code token} events into the final reply,
     * treating an {@code error} event as a hard failure, and requiring a {@code done} event with
     * non-empty text before returning successfully — a stream that disconnects mid-flight without
     * either is also a failure, never a silent partial reply.
     */
    private ChatTurnResult parseSse(Stream<String> lines, String workspaceId) {
        StringBuilder text = new StringBuilder();
        String currentEvent = null;
        boolean sawDone = false;
        String errorCode = null;

        Iterator<String> it = lines.iterator();
        while (it.hasNext()) {
            String line = it.next();
            if (line.isEmpty()) {
                currentEvent = null;
                continue;
            }
            if (line.startsWith(":")) {
                // heartbeat comment (": ping") -- not an event, ignore.
                continue;
            }
            if (line.startsWith("event:")) {
                currentEvent = line.substring("event:".length()).trim();
                continue;
            }
            if (!line.startsWith("data:") || currentEvent == null) {
                continue;
            }
            String data = line.substring("data:".length()).trim();
            JsonNode node = readJsonOrNull(data);
            if (node == null) {
                continue;
            }
            switch (currentEvent) {
                case "token" -> text.append(node.path("text").asText(""));
                case "error" -> errorCode = node.path("code").asText("provider_error");
                case "done" -> sawDone = true;
                default -> { /* thinking/tool_start/tool_result/prompt_meta -- not needed for the final text */ }
            }
        }

        if (errorCode != null) {
            log.warn("MeeraChatAiClient: /chat stream ended with error event for workspace={}, code={}", workspaceId, errorCode);
            throw new MeeraChatAiException("influora-ai /chat returned an error event: " + errorCode);
        }
        if (!sawDone || text.isEmpty()) {
            log.warn(
                    "MeeraChatAiClient: /chat stream for workspace={} ended without usable assistant text"
                            + " (sawDone={}, textLength={})",
                    workspaceId,
                    sawDone,
                    text.length());
            throw new MeeraChatAiException("influora-ai /chat stream ended without usable assistant text");
        }

        return new ChatTurnResult(text.toString());
    }

    private JsonNode readJsonOrNull(String rawJson) {
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Map<String, Object> requestBody) {
        try {
            return objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new MeeraChatAiException("Failed to serialize /chat request", e);
        }
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
