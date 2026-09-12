package com.influora.service.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.Ulids;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.enums.AnalysisStatus;
import com.influora.integration.ai.AnalyzeSiteAiClient;
import com.influora.integration.ai.AnalyzeSiteAiException;
import com.influora.integration.ai.dto.AnalyzeSiteAiDtos.AnalyzeSiteResponse;
import com.influora.integration.ai.dto.AnalyzeSiteAiDtos.Data;
import com.influora.repository.BrandProfileRepository;
import com.influora.web.dto.meera.MeeraDtos.AnalyzeSiteCallback;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * H-23 fix — "analyze-site loop unwired end-to-end; brand profiles never populated." influora-ai's
 * {@code POST /analyze-site} was fully built and sound but had no Spring caller, no onboarding
 * trigger, and no persist path, so {@code BrandProfile.analysisStatus} could never leave {@code
 * PENDING}. This class is the missing Spring-side half: the client ({@link AnalyzeSiteAiClient}),
 * the async job (this class schedules the call off the request thread), and the "callback
 * consumer" ({@link #applyCallback}, which maps a resolved result onto {@code BrandProfile} via
 * the pre-existing {@link BrandProfile#applyAnalysisResult} — its javadoc already described this
 * exact role as "Applies the website analyzer's (Python/Domain D) callback result").
 *
 * <p><b>Call site:</b> {@code OnboardingService.saveBrandCompany} calls {@link #trigger} whenever
 * a brand saves/changes their website URL — the natural point a URL first becomes known.
 *
 * <p><b>Why an internal event instead of scheduling directly from {@link #trigger}:</b> {@link
 * #trigger} runs on the caller's request thread, typically inside an already-open {@code
 * @Transactional} (e.g. {@code saveBrandCompany}). If the background analyze-site call were
 * scheduled immediately, it could start running — and, on completion, try to {@code
 * findByWorkspaceId} the row it needs to update — before the caller's transaction (which is what
 * actually makes the {@code ANALYZING} row durable and visible to other transactions) has
 * committed. Publishing {@link AnalyzeSiteRequestedEvent} and consuming it via {@code
 * @TransactionalEventListener(phase = AFTER_COMMIT)} guarantees the scheduling only happens once
 * that row is safely committed — same idiom {@code DealService.onEscrowFunded} already uses for
 * {@code EscrowFundedEvent}.
 *
 * <p><b>Contract mismatch, handled deliberately:</b> influora-ai's actual {@code /analyze-site}
 * response ({@link com.influora.integration.ai.dto.AnalyzeSiteAiDtos.Data}) is narrower than the
 * {@link AnalyzeSiteCallback} shape a {@code brand_profiles} row expects — no {@code
 * competitor_urls}, and {@code brand_color}/{@code tone_dial} aren't structured the way the DB's
 * {@code brand_aesthetic}/{@code tone_profile} JSON columns describe themselves. {@link
 * #applySuccess} adapts what Python actually returns into the {@link AnalyzeSiteCallback} shape
 * rather than fabricating fields Python doesn't provide.
 */
@Service
public class AnalyzeSiteTriggerService {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeSiteTriggerService.class);

    /**
     * [FIX 3, 2026-09-12 analyze-site prod incident] Counter name for "one analyze-site call was
     * attempted", tagged by {@link #OUTCOME_TAG}.
     *
     * <p>Why this exists: on live, {@code analyze_site} had never once succeeded -- 6 of 6 {@code
     * brand_profiles} rows FAILED, from 2026-08-30 onward -- and the ONLY signal was a per-call
     * {@code log.warn} in {@code AnalyzeSiteAiClient} (lines 128-133) that nothing aggregated. We
     * learned about a total, weeks-long feature outage from a hand-run SQL query. A counter here
     * is aggregable: {@code outcome=transport_failure} climbing while {@code outcome=success}
     * stays at zero is the shape that should have screamed on day one.
     *
     * <p>Instrumented here rather than in {@link AnalyzeSiteAiClient} because this method is the
     * one place all outcomes converge -- including the HTTP-200-but-unsuccessful case, which never
     * reaches the client's exception paths at all.
     */
    static final String ATTEMPT_COUNTER = "analyze_site.attempt";

    static final String OUTCOME_TAG = "outcome";

    /** HTTP 200 and a usable payload -- the row goes READY. */
    static final String OUTCOME_SUCCESS = "success";

    /** HTTP 200, {@code success=false} -- influora-ai read the site and had nothing usable. A
     * real product outcome (a brand's site may genuinely be unreadable), NOT an infra alarm. */
    static final String OUTCOME_HANDLED_FAILURE = "handled_failure";

    /**
     * The request never got an answer: DNS/connect/TLS/read-timeout. {@code
     * AnalyzeSiteAiClient#analyze} wraps exactly these as {@code new AnalyzeSiteAiException(msg,
     * cause)} -- a non-null cause is the discriminator, since its non-200 and unparseable-body
     * throws pass no cause. This is the tag the 2026-08-30 incident would have pinned to the wall:
     * an unresolvable base-url host produces this and nothing else.
     */
    static final String OUTCOME_TRANSPORT_FAILURE = "transport_failure";

    /** We reached influora-ai and it answered badly: non-200, or a body we could not parse. */
    static final String OUTCOME_UPSTREAM_ERROR = "upstream_error";

    /** A bug on this side of the call -- kept distinct so it can never be read as an AI outage. */
    static final String OUTCOME_UNEXPECTED_ERROR = "unexpected_error";

    private final BrandProfileRepository brandProfileRepository;
    private final AnalyzeSiteAiClient aiClient;
    private final TaskScheduler taskScheduler;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    /**
     * Constructor-injected like every other collaborator here. Note for future readers: {@code
     * BrandCampaignFeeService} (line ~134) carries a comment asserting there is "no
     * io.micrometer/spring-boot-starter-actuator dependency in pom.xml, no MeterRegistry bean
     * anywhere in the codebase" and therefore falls back to a greppable log line. Half of that is
     * stale: {@code spring-boot-starter-actuator} IS a compile dependency (pom.xml, added for
     * {@code /actuator/health}), it brings {@code micrometer-core} with it, and Boot's
     * {@code MetricsAutoConfiguration} registers a {@code MeterRegistry} bean. So no new
     * dependency and no TECH-STACK approval is needed to count things -- this is the first
     * service to actually use it.
     */
    private final MeterRegistry meterRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalyzeSiteTriggerService(
            BrandProfileRepository brandProfileRepository,
            AnalyzeSiteAiClient aiClient,
            TaskScheduler taskScheduler,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.brandProfileRepository = brandProfileRepository;
        this.aiClient = aiClient;
        this.taskScheduler = taskScheduler;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
    }

    private void countAttempt(String outcome) {
        Counter.builder(ATTEMPT_COUNTER).tag(OUTCOME_TAG, outcome).register(meterRegistry).increment();
    }

    /**
     * Marks (or creates) the workspace's {@code BrandProfile} row {@code ANALYZING} and arranges
     * for the actual influora-ai call to run off-thread once that write is durably committed.
     * Silently no-ops on a blank workspace/URL — callers are not expected to pre-validate.
     */
    public void trigger(String workspaceId, String websiteUrl) {
        if (workspaceId == null
                || workspaceId.isBlank()
                || websiteUrl == null
                || websiteUrl.isBlank()) {
            return;
        }
        transactionTemplate.executeWithoutResult(
                status -> {
                    markAnalyzing(workspaceId, websiteUrl);
                    // Published from inside this transaction (whether newly opened here or joined
                    // from an outer @Transactional caller) so AFTER_COMMIT below always has a real
                    // commit to wait for — see class javadoc.
                    eventPublisher.publishEvent(new AnalyzeSiteRequestedEvent(workspaceId, websiteUrl));
                });
    }

    private void markAnalyzing(String workspaceId, String websiteUrl) {
        BrandProfile profile =
                brandProfileRepository
                        .findByWorkspaceId(workspaceId)
                        .orElseGet(
                                () ->
                                        BrandProfile.builder()
                                                .id(Ulids.newUlid())
                                                .workspaceId(workspaceId)
                                                .build());
        profile.setWebsiteUrl(websiteUrl);
        profile.setAnalysisStatus(AnalysisStatus.ANALYZING);
        brandProfileRepository.save(profile);
    }

    /** H-23's "async job" — see class javadoc for why this only fires after commit. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnalyzeSiteRequested(AnalyzeSiteRequestedEvent event) {
        taskScheduler.schedule(
                () -> runAnalysis(event.workspaceId(), event.websiteUrl()), Instant.now());
    }

    /** Runs on the shared scheduler pool (see {@code TaskSchedulerConfig}, M-17) — never the
     * request thread. Every exit path ends in a DB write (success, handled failure, or unexpected
     * exception all resolve the row out of {@code ANALYZING}) so a profile can never get stuck. */
    private void runAnalysis(String workspaceId, String websiteUrl) {
        try {
            AnalyzeSiteResponse response = aiClient.analyze(workspaceId, websiteUrl);
            if (response.success() && response.data() != null) {
                countAttempt(OUTCOME_SUCCESS);
                applySuccess(workspaceId, response.data());
            } else {
                countAttempt(OUTCOME_HANDLED_FAILURE);
                String message =
                        response.error() != null && response.error().message() != null
                                ? response.error().message()
                                : "analyze-site returned an unsuccessful result";
                markFailed(workspaceId, message);
            }
        } catch (AnalyzeSiteAiException e) {
            // See OUTCOME_TRANSPORT_FAILURE: the client wraps a cause only when httpClient().send()
            // itself threw (DNS/connect/TLS/timeout). A cause-less AnalyzeSiteAiException means we
            // did reach influora-ai and it answered non-200 or unparseably.
            countAttempt(
                    e.getCause() != null ? OUTCOME_TRANSPORT_FAILURE : OUTCOME_UPSTREAM_ERROR);
            markFailed(workspaceId, e.getMessage());
        } catch (Exception e) {
            countAttempt(OUTCOME_UNEXPECTED_ERROR);
            log.error(
                    "AnalyzeSiteTriggerService: unexpected failure analyzing site for workspace={}",
                    workspaceId,
                    e);
            markFailed(workspaceId, "unexpected error during site analysis");
        }
    }

    private void applySuccess(String workspaceId, Data data) {
        AnalyzeSiteCallback callback = toCallback(workspaceId, data);
        transactionTemplate.executeWithoutResult(status -> applyCallback(workspaceId, callback));
    }

    private AnalyzeSiteCallback toCallback(String workspaceId, Data data) {
        return new AnalyzeSiteCallback(
                workspaceId,
                "READY",
                normalizePriceSource(data.productCatalog()),
                data.brandColor() != null ? Map.of("accent_color", data.brandColor()) : Map.of(),
                data.toneDial() != null ? data.toneDial() : Map.of(),
                data.nicheTags() != null ? data.nicheTags() : List.of(),
                List.of(),
                null);
    }

    /**
     * C1 (Kabir P1-B audit, condition 1): influora-ai's {@code merge_known_products} always sets
     * {@code price_source} ("scraped"|"inferred") on every catalog entry today, but this Spring
     * side must not silently trust that invariant forever — a future influora-ai build, a partial
     * response, or a hand-crafted callback could omit the field. Fail safe: any entry missing (or
     * blank) {@code price_source} is normalized to {@code "inferred"} here, at the write path,
     * before the value is ever persisted to {@link BrandProfile#getProductCatalogJson()} — unknown
     * provenance is treated as untrusted, never silently upgraded to "scraped".
     */
    private static List<Map<String, Object>> normalizePriceSource(List<Map<String, Object>> productCatalog) {
        if (productCatalog == null) {
            return List.of();
        }
        List<Map<String, Object>> normalized = new java.util.ArrayList<>(productCatalog.size());
        for (Map<String, Object> item : productCatalog) {
            if (item == null) {
                continue;
            }
            Map<String, Object> copy = new java.util.LinkedHashMap<>(item);
            Object priceSource = copy.get("price_source");
            if (!(priceSource instanceof String s) || s.isBlank()) {
                copy.put("price_source", "inferred");
            }
            normalized.add(copy);
        }
        return normalized;
    }

    /**
     * Write-back path for the Meera CHAT tool loop's LOCAL {@code analyze_site} tool (H-23
     * follow-up). {@code trigger()}/{@code runAnalysis()} above are the FORM/onboarding path —
     * Spring calls influora-ai itself via {@link AnalyzeSiteAiClient}. The chat path is the
     * opposite direction: influora-ai already ran {@code perform_site_analysis} locally (for a fast
     * in-turn reply) and forwards the same-shaped result here, behind the {@code
     * /internal/meera/analyze_site_result} mesh-authenticated endpoint ({@code
     * MeeraInternalController#analyzeSiteResult}). Reuses {@link #toCallback}/{@link
     * #applyCallback}/{@link #markFailed} exactly as the onboarding path does — the only new
     * behavior is creating the {@code BrandProfile} row here if one doesn't exist yet (the
     * onboarding path's {@link #markAnalyzing} does this too; chat has no equivalent "mark
     * ANALYZING" step since the analysis already completed by the time this is called).
     */
    public void applyChatResult(
            String workspaceId, String websiteUrl, boolean success, Data data, String errorMessage) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        AnalysisStatus statusBeforeUpdate =
                transactionTemplate.execute(
                        status -> {
                            BrandProfile profile =
                                    brandProfileRepository
                                            .findByWorkspaceId(workspaceId)
                                            .orElseGet(
                                                    () ->
                                                            BrandProfile.builder()
                                                                    .id(Ulids.newUlid())
                                                                    .workspaceId(workspaceId)
                                                                    .build());
                            AnalysisStatus currentStatus = profile.getAnalysisStatus();
                            if (websiteUrl != null && !websiteUrl.isBlank()) {
                                profile.setWebsiteUrl(websiteUrl);
                            }
                            brandProfileRepository.save(profile);
                            return currentStatus;
                        });

        if (success && data != null) {
            applySuccess(workspaceId, data);
        } else if (statusBeforeUpdate == AnalysisStatus.READY) {
            // A brand with an already-READY profile pasted a broken/unreachable URL in chat —
            // the chat reply already told them the page couldn't be read, so don't clobber the
            // prior successful snapshot. Only the chat path (this method) skips markFailed here;
            // FORM/onboarding failures via runAnalysis() still call markFailed unconditionally.
            log.info(
                    "AnalyzeSiteTriggerService: ignoring transient chat analysis failure for"
                            + " workspace={} — profile is already READY",
                    workspaceId);
        } else {
            markFailed(workspaceId, errorMessage != null ? errorMessage : "chat analysis failed");
        }
    }

    /**
     * The "AnalyzeSiteCallback consumer" (H-23) — maps a resolved {@link AnalyzeSiteCallback} onto
     * the {@code BrandProfile} row via the pre-existing {@link
     * BrandProfile#applyAnalysisResult}, JSON-encoding each field the same way every other JSON
     * column in this codebase is written (Jackson, not hand-built strings).
     */
    private void applyCallback(String workspaceId, AnalyzeSiteCallback callback) {
        brandProfileRepository
                .findByWorkspaceId(workspaceId)
                .ifPresentOrElse(
                        profile -> {
                            profile.applyAnalysisResult(
                                    writeJson(callback.productCatalog()),
                                    writeJson(callback.brandAesthetic()),
                                    writeJson(callback.toneProfile()),
                                    writeJson(callback.nicheTags()),
                                    writeJson(callback.competitorUrls()));
                            brandProfileRepository.save(profile);
                        },
                        () ->
                                log.warn(
                                        "AnalyzeSiteTriggerService: no BrandProfile row found for workspace={} when"
                                                + " applying a completed analysis — dropping the result",
                                        workspaceId));
    }

    private void markFailed(String workspaceId, String error) {
        transactionTemplate.executeWithoutResult(
                status ->
                        brandProfileRepository
                                .findByWorkspaceId(workspaceId)
                                .ifPresent(
                                        profile -> {
                                            profile.markFailed(error);
                                            brandProfileRepository.save(profile);
                                        }));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "null";
        }
    }
}
