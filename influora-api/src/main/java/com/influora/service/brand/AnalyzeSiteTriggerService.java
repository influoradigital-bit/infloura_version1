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

    private final BrandProfileRepository brandProfileRepository;
    private final AnalyzeSiteAiClient aiClient;
    private final TaskScheduler taskScheduler;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalyzeSiteTriggerService(
            BrandProfileRepository brandProfileRepository,
            AnalyzeSiteAiClient aiClient,
            TaskScheduler taskScheduler,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.brandProfileRepository = brandProfileRepository;
        this.aiClient = aiClient;
        this.taskScheduler = taskScheduler;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
                applySuccess(workspaceId, response.data());
            } else {
                String message =
                        response.error() != null && response.error().message() != null
                                ? response.error().message()
                                : "analyze-site returned an unsuccessful result";
                markFailed(workspaceId, message);
            }
        } catch (AnalyzeSiteAiException e) {
            markFailed(workspaceId, e.getMessage());
        } catch (Exception e) {
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
                data.productCatalog() != null ? data.productCatalog() : List.of(),
                data.brandColor() != null ? Map.of("accent_color", data.brandColor()) : Map.of(),
                data.toneDial() != null ? data.toneDial() : Map.of(),
                data.nicheTags() != null ? data.nicheTags() : List.of(),
                List.of(),
                null);
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
        transactionTemplate.executeWithoutResult(
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
                    if (websiteUrl != null && !websiteUrl.isBlank()) {
                        profile.setWebsiteUrl(websiteUrl);
                    }
                    brandProfileRepository.save(profile);
                });

        if (success && data != null) {
            applySuccess(workspaceId, data);
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
