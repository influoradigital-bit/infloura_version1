package com.influora.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.config.CreatorSuggestionAiProperties;
import com.influora.integration.ai.dto.MeeraBriefAiDtos.ExtractRequest;
import com.influora.integration.ai.dto.MeeraBriefAiDtos.ExtractResponse;
import com.influora.service.integration.CreatorSuggestionServiceTokenService;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * HTTP client for influora-ai's {@code POST /internal/brief-extract} — the ONE AI call in the
 * paste-and-read flow (T-MEERA-CREATOR-PHASE-B, SPEC.md &sect;3.8 step 3 / &sect;7.5), B0-40.
 *
 * <p><b>Mirrors {@link CreatorSuggestionAiClient}, NOT {@link MeeraVoiceAiClient}.</b> SPEC.md
 * &sect;3.8 originally said the voice client, and that would never have worked: the Python route
 * authenticates with {@code verify_creator_token}, which requires {@code scope=creator} and a
 * {@code creator_profile_id} claim matching the body. {@link MeeraVoiceAiClient} presents a
 * SERVICE-scoped token carrying a {@code workspace_id}, and {@code app/auth/service_token.py}'s
 * {@code ENDPOINT_SCOPES} table refuses it — the segregation between {@code SCOPE_SERVICE} and
 * {@code SCOPE_CREATOR} is deliberate and bidirectional (Kabir). So the token is minted by
 * {@link CreatorSuggestionServiceTokenService}, the same creator-scoped minter the suggestion route
 * uses.
 *
 * <p><b>The id passed is a {@code creator_profiles.id}, never a {@code users.id}</b> (SPEC.md
 * &sect;0.8). The Python side equality-checks it against the token claim, so a user id here does not
 * extract the wrong brief — it fails auth. That is the good failure mode, but it is still a failure,
 * and {@code CreatorBriefService} passes {@code profile.getId()} for this reason.
 *
 * <p><b>Reuses {@link CreatorSuggestionAiProperties} rather than adding a sixth AI properties
 * class.</b> It is the same influora-ai process at the same base URL with the same outbound-call
 * shape; a parallel properties class would add a config surface, a {@code @EnableConfigurationProperties}
 * entry and a {@code SecretsStartupValidator} branch for no isolation whatsoever.
 *
 * <p><b>Never throws, and the return value carries three distinct outcomes.</b>
 *
 * <ul>
 *   <li>{@link BriefResult#extraction()} present — the model read the brief.
 *   <li>empty with {@link BriefResult#capReached()} true — the creator is over her own monthly brief
 *       allowance. She gets the deterministic extraction labelled {@code cap}.
 *   <li>empty with {@code capReached} false — transport, non-200, malformed body, or the route's own
 *       {@code extraction_failed}. She gets the deterministic extraction labelled
 *       {@code ai_unavailable}.
 * </ul>
 *
 * <p>Collapsing the last two would tell a creator who has simply used her allowance that Influora is
 * broken, and would tell one hitting a real outage that she has run out. The Python route returns
 * HTTP 200 for BOTH so that this class can tell them apart from the body — see SPEC.md &sect;14.4.a.
 */
@Component
@Lazy
public class MeeraBriefAiClient {

    private static final Logger log = LoggerFactory.getLogger(MeeraBriefAiClient.class);
    private static final String PATH = "/internal/brief-extract";

    /**
     * The one error code that means "allowance", not "outage". Same literal influora-ai's
     * {@code chat.py} uses for the chat cap, and the same literal the SPA already recognises.
     */
    public static final String CAP_ERROR_CODE = "CREATOR_MONTHLY_CAP_REACHED";

    private final CreatorSuggestionAiProperties props;
    private final CreatorSuggestionServiceTokenService tokenService;
    // Built on first use, not in the constructor — same rationale as CreatorSuggestionAiClient:
    // application BOOT must not depend on outbound-HTTP plumbing this class may never use.
    private volatile HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** T-CREATOR-CREDITS-V2 (SPEC.md B20) — the $12.00 brief-extract USD backstop, sent only when the flag is on. */
    private final com.influora.config.CreatorCreditProperties creatorCreditProperties;

    @Autowired
    public MeeraBriefAiClient(
            CreatorSuggestionAiProperties props,
            CreatorSuggestionServiceTokenService tokenService,
            com.influora.config.CreatorCreditProperties creatorCreditProperties) {
        this(props, tokenService, null, creatorCreditProperties);
    }

    /** Package-visible constructor for tests to inject a mocked {@link HttpClient}. */
    MeeraBriefAiClient(
            CreatorSuggestionAiProperties props,
            CreatorSuggestionServiceTokenService tokenService,
            HttpClient httpClient,
            com.influora.config.CreatorCreditProperties creatorCreditProperties) {
        this.props = props;
        this.tokenService = tokenService;
        this.httpClient = httpClient;
        this.creatorCreditProperties = creatorCreditProperties;
    }

    private HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient =
                            HttpClient.newBuilder()
                                    // [Ash 2026-07-23] Pin HTTP/1.1 — java.net.http's HTTP/2 default drops
                                    // the POST body over cleartext h2c against uvicorn (empty body -> 500).
                                    // See AnalyzeSiteAiClient for the full write-up.
                                    .version(HttpClient.Version.HTTP_1_1)
                                    .connectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
                                    .build();
                }
                client = httpClient;
            }
        }
        return client;
    }

    /**
     * The outcome of one extraction attempt. An {@link Optional} alone could not carry the cap/outage
     * distinction, and a null {@code BriefExtraction} plus a boolean would let a caller read the
     * extraction without ever consulting the reason it was absent.
     */
    public record BriefResult(Optional<BriefExtraction> extraction, boolean capReached) {

        public static BriefResult of(BriefExtraction extraction) {
            return new BriefResult(Optional.of(extraction), false);
        }

        public static BriefResult cap() {
            return new BriefResult(Optional.empty(), true);
        }

        /** Transport failure, non-200, malformed body, or the route's own {@code extraction_failed}. */
        public static BriefResult unavailable() {
            return new BriefResult(Optional.empty(), false);
        }
    }

    /**
     * Asks influora-ai to read one pasted brief.
     *
     * @param creatorProfileId a {@code creator_profiles.id} — see the class javadoc
     * @param rawText the brief as the creator pasted it, already sanitised and capped by {@code
     *     CreatorBrief.paste}
     * @param creatorLanguage the creator's Meera language, for the summary lines
     */
    public BriefResult extract(String creatorProfileId, String rawText, String creatorLanguage) {
        if (creatorProfileId == null || creatorProfileId.isBlank()) {
            log.warn("MeeraBriefAiClient: missing creatorProfileId, skipping AI call");
            return BriefResult.unavailable();
        }
        if (rawText == null || rawText.isBlank()) {
            // Nothing to read. Skipped here rather than sent, so an empty paste costs no token spend
            // and does not consume a slot of the creator's monthly brief allowance.
            return BriefResult.unavailable();
        }

        String token;
        String requestBody;
        try {
            token = tokenService.mint(creatorProfileId);
            java.math.BigDecimal briefMonthlyCapUsd =
                    creatorCreditProperties.isEnabled() ? creatorCreditProperties.getUsdBriefBackstopMonthly() : null;
            requestBody =
                    objectMapper.writeValueAsString(
                            new ExtractRequest(creatorProfileId, rawText, creatorLanguage, briefMonthlyCapUsd));
        } catch (Exception e) {
            log.warn(
                    "MeeraBriefAiClient: failed to build request for creator={}: {}",
                    creatorProfileId,
                    e.getMessage());
            return BriefResult.unavailable();
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
                    "MeeraBriefAiClient: transport failure calling {} for creator={}: {}",
                    PATH,
                    creatorProfileId,
                    e.getMessage());
            return BriefResult.unavailable();
        }

        if (response.statusCode() != 200) {
            // Only auth (401/403) and a missing profile id (400) are non-200 on that route, and all
            // three are OUR bug, not the creator's. They degrade to the deterministic extractor
            // rather than surfacing, because the creator's text is already persisted and a paste must
            // not dead-end on a mesh misconfiguration.
            log.warn(
                    "MeeraBriefAiClient: non-200 from {} for creator={}, status={}",
                    PATH,
                    creatorProfileId,
                    response.statusCode());
            return BriefResult.unavailable();
        }

        ExtractResponse parsed;
        try {
            parsed = objectMapper.readValue(response.body(), ExtractResponse.class);
        } catch (Exception e) {
            log.warn(
                    "MeeraBriefAiClient: malformed response body from {} for creator={}",
                    PATH,
                    creatorProfileId);
            return BriefResult.unavailable();
        }

        if (parsed == null) {
            return BriefResult.unavailable();
        }
        if (!parsed.success()) {
            String code = parsed.error() == null ? null : parsed.error().code();
            if (CAP_ERROR_CODE.equals(code)) {
                log.info(
                        "MeeraBriefAiClient: creator={} is at the monthly brief cap — falling back to"
                                + " the deterministic extractor",
                        creatorProfileId);
                return BriefResult.cap();
            }
            log.warn(
                    "MeeraBriefAiClient: extraction refused for creator={}, code={}",
                    creatorProfileId,
                    code);
            return BriefResult.unavailable();
        }
        if (parsed.data() == null) {
            // success:true with no data is a contract violation on the Python side, not a cap.
            log.warn(
                    "MeeraBriefAiClient: success with no data for creator={}", creatorProfileId);
            return BriefResult.unavailable();
        }
        return BriefResult.of(parsed.data());
    }
}
