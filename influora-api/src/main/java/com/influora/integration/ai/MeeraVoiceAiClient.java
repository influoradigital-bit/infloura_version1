package com.influora.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.service.integration.BrandSafetyServiceTokenService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * HTTP client for influora-ai's {@code POST /voice/speak} (Meera voice-output proxy) and {@code
 * POST /voice/transcribe} (Meera voice-INPUT proxy — {@link #transcribe}, the mirror-image leg:
 * Sarvam STT + Gemini cleanup, same {@code scope=service} token, same never-throw discipline).
 *
 * <p><b>Apache HttpClient5 (classic/blocking), NOT {@code java.net.http.HttpClient}</b> — live-
 * verified this matters, not just style: with the Java backend running in Docker and influora-ai
 * on the host, every {@code java.net.http.HttpClient} call to the host (via {@code
 * host.docker.internal} or even its bare resolved IP) failed with {@code
 * java.net.ConnectException} — while {@code wget} to the exact same address from inside the same
 * container succeeded immediately. Forcing IPv4 ({@code -Djava.net.preferIPv4Stack=true}) and
 * eliminating DNS entirely (literal IP, no hostname) both made no difference, which rules out a
 * DNS/dual-stack explanation and points at the JDK client's own NIO-based connection path — the
 * exact subsystem {@code pom.xml}'s own httpclient5 dependency comment already documents as
 * failing "outright on loopback-restricted/resource-starved hosts" (that comment previously
 * covered Spring's {@code RestClient} auto-preferring httpclient5 for the same reason; this class
 * was the one AI-integration client that still used the raw JDK client directly instead).
 * Apache's classic API uses plain blocking sockets with no NIO {@code Selector}/loopback-pipe
 * involved at all, and resolved the failure outright.
 *
 * <p>Mirrors {@link BrandSafetyAiClient}/{@link TrendSparkAiClient}'s conventions: Jackson {@code
 * ObjectMapper}, and reuses {@link BrandSafetyServiceTokenService} for the signed service token
 * (same Spring -&gt; influora-ai direction, same JWKS-verified {@code scope=service} mechanism the
 * Python side's {@code ENDPOINT_SCOPES["voice_speak"]} already accepts — no new signing secret
 * needed for this third outbound leg).
 *
 * <p><b>Config is deliberately {@code @Value}-based, not a new {@code @ConfigurationProperties}
 * class</b>: every existing sibling ({@link BrandSafetyAiProperties}/{@code TrendSparkAiProperties})
 * requires a matching entry in {@code InfluoraApiApplication}'s {@code
 * @EnableConfigurationProperties} list to actually be constructible (a properties class injected
 * into a live bean without that registration previously left the app compiling but unable to
 * boot — see the incident notes on that annotation). Reusing {@code @Value} with the same {@code
 * influora.voice-ai.*} key names avoids adding a registration dependency outside this task's file
 * scope while keeping the same env-var override shape ({@code VOICE_AI_BASE_URL}, mirroring {@code
 * BRAND_SAFETY_AI_BASE_URL}/{@code TRENDSPARK_AI_BASE_URL} in {@code application-prod.yml}). A prod
 * deploy needs its own {@code VOICE_AI_BASE_URL} entry alongside those two — flagged here since
 * {@code application-prod.yml} is outside this task's owned files.
 *
 * <p><b>Never throws — degrades to {@link SpeakResult#fallback()} on any failure</b> (missing
 * input, transport error, non-200, or a response that isn't audio), matching {@link
 * TrendSparkAiClient}'s "never surface an error to the brand" discipline and the locked FE
 * contract: the caller ({@code MeeraController#speak}) must return 200 either way, never a 5xx for
 * a provider hiccup.
 */
@Component
@Lazy
public class MeeraVoiceAiClient {

    private static final Logger log = LoggerFactory.getLogger(MeeraVoiceAiClient.class);
    private static final String PATH = "/voice/speak";
    private static final String TRANSCRIBE_PATH = "/voice/transcribe";
    private static final String CRLF = "\r\n";

    private final String baseUrl;
    private final int requestTimeoutSeconds;
    // Only meaningfully set by the @Autowired constructor below (default 5 matches its @Value
    // default); irrelevant on the test-injected-httpClient path since httpClient() never rebuilds
    // once non-null.
    private volatile int connectTimeoutSeconds = 5;
    private final BrandSafetyServiceTokenService tokenService;
    // Built on first use, not in the constructor: same lazy-construction discipline as before,
    // now for a plain blocking-socket client rather than an NIO one — still no reason to make
    // application BOOT depend on outbound-HTTP plumbing this class may never use.
    private volatile CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public MeeraVoiceAiClient(
            @Value("${influora.voice-ai.base-url:http://localhost:8000}") String baseUrl,
            @Value("${influora.voice-ai.connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${influora.voice-ai.request-timeout-seconds:10}") int requestTimeoutSeconds,
            BrandSafetyServiceTokenService tokenService) {
        this(baseUrl, requestTimeoutSeconds, tokenService, null);
        this.connectTimeoutSeconds = connectTimeoutSeconds <= 0 ? 5 : connectTimeoutSeconds;
    }

    /** Package-visible constructor for tests to inject a mocked {@link CloseableHttpClient}. */
    MeeraVoiceAiClient(
            String baseUrl,
            int requestTimeoutSeconds,
            BrandSafetyServiceTokenService tokenService,
            CloseableHttpClient httpClient) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "http://localhost:8000" : baseUrl;
        this.requestTimeoutSeconds = requestTimeoutSeconds <= 0 ? 10 : requestTimeoutSeconds;
        this.tokenService = tokenService;
        this.httpClient = httpClient;
    }

    private CloseableHttpClient httpClient() {
        CloseableHttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                if (httpClient == null) {
                    RequestConfig requestConfig =
                            RequestConfig.custom()
                                    .setConnectTimeout(Timeout.ofSeconds(connectTimeoutSeconds))
                                    .setResponseTimeout(Timeout.ofSeconds(requestTimeoutSeconds))
                                    .build();
                    httpClient = HttpClients.custom().setDefaultRequestConfig(requestConfig).build();
                }
                client = httpClient;
            }
        }
        return client;
    }

    /**
     * Result of a {@link #speak} call. {@code ok=true} carries the raw TTS audio bytes and the
     * content type influora-ai reported (defaults to {@code audio/wav}, matching {@code
     * voice.py}'s {@code Response(..., media_type=result.content_type or "audio/wav")}). {@code
     * ok=false} is the silent-fallback signal — callers must never treat it as an error.
     */
    public record SpeakResult(boolean ok, byte[] audioBytes, String contentType) {
        public static SpeakResult fallback() {
            return new SpeakResult(false, null, null);
        }

        public static SpeakResult audio(byte[] bytes, String contentType) {
            return new SpeakResult(true, bytes, contentType);
        }
    }

    /** Plain internal transfer object — the one shape both {@link #speak}/{@link #transcribe} need out of a response. */
    private record RawResponse(int statusCode, byte[] body, String contentType) {}

    /**
     * Executes {@code request} and drains the response body into a {@link RawResponse} before
     * returning — {@link CloseableHttpClient#execute(org.apache.hc.core5.http.ClassicHttpRequest,
     * HttpClientResponseHandler)} guarantees the underlying connection/response is released back
     * to the pool once the handler returns, so no caller here needs its own try-with-resources.
     */
    private RawResponse execute(HttpPost request) throws IOException {
        HttpClientResponseHandler<RawResponse> handler =
                response -> {
                    HttpEntity entity = response.getEntity();
                    byte[] body = entity != null ? EntityUtils.toByteArray(entity) : new byte[0];
                    String contentType = entity != null && entity.getContentType() != null ? entity.getContentType() : "";
                    return new RawResponse(response.getCode(), body, contentType);
                };
        return httpClient().execute(request, handler);
    }

    /**
     * Requests TTS audio for {@code text} scoped to {@code workspaceId}. influora-ai truncates
     * the text to ~200 chars itself (P18) and applies its own spend gate — this client sends
     * whatever it's given and trusts the Python side's degrade-not-error behavior; on ANY
     * provider failure Python responds 200 with a small JSON fallback signal instead of audio
     * bytes, which this method surfaces as {@link SpeakResult#fallback()}, same as every other
     * failure mode here.
     */
    public SpeakResult speak(String workspaceId, String text) {
        if (workspaceId == null || workspaceId.isBlank() || text == null || text.isBlank()) {
            log.warn("MeeraVoiceAiClient: missing workspaceId/text, skipping call");
            return SpeakResult.fallback();
        }

        String token;
        String requestBody;
        try {
            token = tokenService.mint(workspaceId);
            requestBody = objectMapper.writeValueAsString(new SpeakRequest(workspaceId, text));
        } catch (Exception e) {
            log.warn(
                    "MeeraVoiceAiClient: failed to build request for workspace={}: {}",
                    workspaceId,
                    e.getMessage());
            return SpeakResult.fallback();
        }

        HttpPost request = new HttpPost(baseUrl + PATH);
        request.setHeader("Authorization", "Bearer " + token);
        request.setEntity(
                new ByteArrayEntity(requestBody.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON));

        RawResponse response;
        try {
            response = execute(request);
        } catch (Exception e) {
            // Deliberately no text-content logging here — only workspace + failure reason,
            // mirroring BrandSafetyAiClient's "never log the payload" discipline. Exception class
            // + cause logged (not just getMessage(), which is frequently null for this call's
            // actual failures) so a real transport bug is diagnosable instead of reading "null".
            Throwable cause = e.getCause();
            log.warn(
                    "MeeraVoiceAiClient: transport failure calling {} for workspace={}: {}: {} (cause: {})",
                    PATH,
                    workspaceId,
                    e.getClass().getName(),
                    e.getMessage(),
                    cause == null ? "none" : cause.getClass().getName() + ": " + cause.getMessage());
            return SpeakResult.fallback();
        }

        if (response.statusCode() != 200) {
            // A 401/403 here would mean a Java/Python service-auth misconfiguration (never
            // expected in normal operation, unlike voice.py's own 200-with-fallback provider
            // miss) -- still degrades to fallback (never a 5xx to the brand), but logged at WARN
            // either way so the distinction is visible in logs/alerts.
            log.warn(
                    "MeeraVoiceAiClient: non-200 response from {} for workspace={}, status={}",
                    PATH,
                    workspaceId,
                    response.statusCode());
            return SpeakResult.fallback();
        }

        String contentType = response.contentType() == null ? "" : response.contentType();
        if (contentType.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            return SpeakResult.audio(response.body(), contentType);
        }

        // Any 200 that isn't audio is voice.py's documented fallback envelope
        // ({"fallback": true, "message": ...}) or an unexpected shape -- both are treated
        // identically: the silent-fallback signal, never parsed further.
        return SpeakResult.fallback();
    }

    private record SpeakRequest(@JsonProperty("workspace_id") String workspaceId, String text) {}

    /**
     * Result of a {@link #transcribe} call — the voice-INPUT mirror of {@link SpeakResult}. {@code
     * ok=true} carries influora-ai's raw transcript JSON body ({@code {raw_transcript, cleaned_text,
     * lang_detected, fallback}}, see {@code voice.py:237-242}) exactly as returned, plus the content
     * type it reported (defaults to {@code application/json}); the controller forwards those bytes
     * verbatim so the frontend gets the same shape whether Python succeeded or emitted its OWN
     * 200-with-{@code fallback:true} envelope (spend-gate / STT miss). {@code ok=false} is the
     * transport/non-200 silent-fallback signal — callers must never treat it as an error, and return
     * a bare {@code {"fallback": true}} 200 so the browser can fall back to webkitSpeechRecognition.
     */
    public record TranscribeResult(boolean ok, byte[] jsonBytes, String contentType) {
        public static TranscribeResult fallback() {
            return new TranscribeResult(false, null, null);
        }

        public static TranscribeResult json(byte[] bytes, String contentType) {
            return new TranscribeResult(true, bytes, contentType);
        }
    }

    /**
     * Transcribes {@code audioBytes} scoped to {@code workspaceId} via influora-ai's {@code POST
     * /voice/transcribe}. Mirrors {@link #speak} leg-for-leg: mints the same {@code scope=service}
     * token ({@code ENDPOINT_SCOPES["voice_transcribe"]} accepts it, same as {@code voice_speak}),
     * sends the workspace id in the request body (never trusts a caller-supplied one — Python
     * re-checks it against the token), and NEVER throws — any missing input, transport error,
     * non-200, or unreadable body degrades to {@link TranscribeResult#fallback()} so {@code
     * MeeraController#transcribe} can return a 200 fallback and the browser can fall back to its
     * native recognizer, exactly the "never a dead end" contract voice.py documents for this route.
     *
     * <p>The Python endpoint reads {@code multipart/form-data} ({@code await request.form()}): a
     * {@code workspace_id} text part and an {@code audio} file part ({@code voice.py:144-146}).
     * Apache HttpClient5's classic API has no built-in multipart body builder on this project's
     * classpath (that needs a separate {@code httpmime}/fluent dependency, not added here), so the
     * body is still assembled by hand ({@link #buildMultipartBody}) exactly as before — that part
     * of this class is HTTP-client-agnostic, just raw bytes over a {@link ByteArrayEntity}.
     */
    public TranscribeResult transcribe(String workspaceId, byte[] audioBytes, String contentType) {
        if (workspaceId == null || workspaceId.isBlank() || audioBytes == null || audioBytes.length == 0) {
            log.warn("MeeraVoiceAiClient: missing workspaceId/audio, skipping transcribe call");
            return TranscribeResult.fallback();
        }

        String token;
        byte[] body;
        String boundary = "InfluoraVoiceBoundary-" + UUID.randomUUID();
        try {
            token = tokenService.mint(workspaceId);
            body = buildMultipartBody(boundary, workspaceId, audioBytes, contentType);
        } catch (Exception e) {
            log.warn(
                    "MeeraVoiceAiClient: failed to build transcribe request for workspace={}: {}",
                    workspaceId,
                    e.getMessage());
            return TranscribeResult.fallback();
        }

        HttpPost request = new HttpPost(baseUrl + TRANSCRIBE_PATH);
        request.setHeader("Authorization", "Bearer " + token);
        request.setEntity(new ByteArrayEntity(body, ContentType.parse("multipart/form-data; boundary=" + boundary)));

        RawResponse response;
        try {
            response = execute(request);
        } catch (Exception e) {
            // No payload logging — only workspace + reason, same discipline as speak().
            log.warn(
                    "MeeraVoiceAiClient: transport failure calling {} for workspace={}: {}",
                    TRANSCRIBE_PATH,
                    workspaceId,
                    e.getMessage());
            return TranscribeResult.fallback();
        }

        if (response.statusCode() != 200) {
            // A non-200 here means a Java/Python service-auth misconfig (Python itself degrades to
            // 200-with-{fallback:true} for provider misses) -- still never a 5xx to the brand,
            // logged at WARN so the distinction is visible, same as speak()'s non-200 branch.
            log.warn(
                    "MeeraVoiceAiClient: non-200 response from {} for workspace={}, status={}",
                    TRANSCRIBE_PATH,
                    workspaceId,
                    response.statusCode());
            return TranscribeResult.fallback();
        }

        // Any 200 (real transcript OR Python's own {fallback:true} envelope) is forwarded verbatim:
        // both are the JSON shape the frontend already parses, and Python's envelope preserves its
        // "type it instead?" message. The Spring layer only synthesizes a fallback on transport /
        // non-200 failures above.
        String responseType = response.contentType() == null || response.contentType().isBlank()
                ? "application/json"
                : response.contentType();
        return TranscribeResult.json(response.body(), responseType);
    }

    /**
     * Assembles the {@code multipart/form-data} body influora-ai's {@code /voice/transcribe}
     * expects: a {@code workspace_id} text part (re-verified against the service token on the Python
     * side) and an {@code audio} file part carrying the raw recorded bytes. Content type falls back
     * to {@code application/octet-stream} when the browser upload didn't report one.
     */
    private static byte[] buildMultipartBody(
            String boundary, String workspaceId, byte[] audioBytes, String contentType)
            throws IOException {
        String audioContentType =
                (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
        out.write(
                ("Content-Disposition: form-data; name=\"workspace_id\"" + CRLF + CRLF)
                        .getBytes(StandardCharsets.UTF_8));
        out.write(workspaceId.getBytes(StandardCharsets.UTF_8));
        out.write(CRLF.getBytes(StandardCharsets.UTF_8));

        out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
        out.write(
                ("Content-Disposition: form-data; name=\"audio\"; filename=\"audio\"" + CRLF)
                        .getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + audioContentType + CRLF + CRLF).getBytes(StandardCharsets.UTF_8));
        out.write(audioBytes);
        out.write(CRLF.getBytes(StandardCharsets.UTF_8));

        out.write(("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
