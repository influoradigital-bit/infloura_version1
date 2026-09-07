package com.influora.job;

import com.influora.config.CreatorCopilotProperties;
import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorCaptionCache;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.InstagramMediaResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.repository.CreatorCaptionCacheRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import com.influora.service.creatorcopilot.CreatorMetaConnectedEvent;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly batch that closes the gap {@code CreatorThemeTaggingJob} explicitly calls out in its own
 * javadoc: something has to fetch each connected creator's recent Instagram media captions and
 * write {@code PENDING creator_captions} rows before the tagging job has anything to tag. Runs at
 * 2 AM UTC by default, one hour before {@code CreatorThemeTaggingJob}'s 3 AM UTC default, so a
 * night's captions are cached in time for that night's tagging pass.
 *
 * <p>Enumerates every connected creator by sweeping {@code MetaOAuthToken} rows with {@code
 * workspace_id IS NULL} (the creator-owned key-space — see that entity's javadoc), the same
 * system-wide, not-workspace-scoped convention {@code MetaOAuthTokenRepository} already uses for
 * the refresh sweep and {@code MetricsPollingJob}. For each token, decrypts the access token via
 * {@link MetaTokenStorage#getValidCreatorToken(String)} and calls {@link
 * InstagramInsightsClient#getMedia(String, String, int)} directly — deliberately NOT {@code
 * InstagramMetricsFetcher.fetchMediaWithInsights}, which would also fire one Graph API insights
 * call per media item we don't need just to cache a caption.
 *
 * <p>The fetcher's {@code igUserId} parameter is the Instagram Business Account id ({@link
 * MetaOAuthToken#getIgBusinessAccountId()}), not our own {@code creatorProfileId} — rows are
 * fetched keyed on the former but persisted keyed on the latter, exactly like every other Meta
 * integration class in this package. A token with no resolved business account id is skipped
 * (nothing to fetch against) and counted, not treated as a failure.
 *
 * <p>Per-creator AND per-item try/catch, same resilience discipline as {@code
 * CreatorThemeTaggingJob}: one creator's Meta failure (expired/rate-limited/API error) never
 * aborts the batch, and one bad media item never aborts the rest of that creator's page.
 *
 * <p>[SEC] Never logs access tokens or caption text — completion/warning logs carry only ids,
 * counts, and error messages, same convention as {@code MetaTokenStorage} and {@code
 * CreatorThemeTaggingJob}.
 */
@Component
public class CreatorCaptionSyncJob {

    private static final Logger log = LoggerFactory.getLogger(CreatorCaptionSyncJob.class);

    /** Matches Meta Graph API's non-colon offset format, e.g. {@code 2025-09-01T12:00:00+0000}. */
    private static final DateTimeFormatter META_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final MetaOAuthTokenRepository tokenRepository;
    private final MetaTokenStorage tokenStorage;
    private final InstagramInsightsClient instagramClient;
    private final CreatorCaptionCacheRepository captionRepository;
    private final CreatorCopilotProperties props;

    public CreatorCaptionSyncJob(
            MetaOAuthTokenRepository tokenRepository,
            MetaTokenStorage tokenStorage,
            InstagramInsightsClient instagramClient,
            CreatorCaptionCacheRepository captionRepository,
            CreatorCopilotProperties props) {
        this.tokenRepository = tokenRepository;
        this.tokenStorage = tokenStorage;
        this.instagramClient = instagramClient;
        this.captionRepository = captionRepository;
        this.props = props;
    }

    /** Nightly at 2 AM UTC by default — OFF by default via the same {@link
     * CreatorCopilotProperties#isEnabled()} flag {@code CreatorThemeTaggingJob} gates on. */
    @Scheduled(cron = "${influora.creator-copilot.caption-sync-cron:0 0 2 * * *}", zone = "UTC")
    @SchedulerLock(name = "CreatorCaptionSyncJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void syncCaptions() {
        if (!props.isEnabled()) {
            log.info("CreatorCaptionSyncJob: disabled (influora.creator-copilot.enabled=false), skipping run");
            return;
        }

        List<MetaOAuthToken> creatorTokens =
                tokenRepository.findByWorkspaceIdIsNullAndRevokedFalseAndExpiresAtAfter(Instant.now());

        int maxCreators = props.getCaptionSyncMaxCreatorsPerRun();
        if (maxCreators > 0 && creatorTokens.size() > maxCreators) {
            creatorTokens = creatorTokens.subList(0, maxCreators);
        }

        int creatorsProcessed = 0;
        int creatorsSkippedNoAccount = 0;
        int creatorsFailed = 0;
        int inserted = 0;
        int skipped = 0;
        int itemsFailed = 0;

        for (MetaOAuthToken token : creatorTokens) {
            // T-IGTRUST-0907 — body extracted to syncOneCreator so the connect-triggered sync
            // runs byte-identical logic instead of a second, drifting copy. The counters are
            // accumulated here exactly as before, so this run's log line is unchanged.
            CreatorSyncOutcome outcome = syncOneCreator(token);
            if (outcome.processed()) creatorsProcessed++;
            if (outcome.skippedNoAccount()) creatorsSkippedNoAccount++;
            if (outcome.failed()) creatorsFailed++;
            inserted += outcome.inserted();
            skipped += outcome.skipped();
            itemsFailed += outcome.itemsFailed();
        }

        log.info(
                "CreatorCaptionSyncJob: completed run — {} creators processed, {} captions inserted, {}"
                        + " skipped (duplicate/blank), {} creators failed, {} creators skipped (no linked IG"
                        + " account), {} items failed",
                creatorsProcessed,
                inserted,
                skipped,
                creatorsFailed,
                creatorsSkippedNoAccount,
                itemsFailed);
    }

    /**
     * Syncs one creator's captions immediately, off the back of a successful Meta connect
     * (T-IGTRUST-0907). Without this the creator waits for the 02:00 UTC cron — up to ~21 hours
     * for someone who connects mid-morning IST — while the Co-pilot reports
     * {@code pending_tagging}.
     *
     * <p>{@code @Async} + {@code AFTER_COMMIT} (see {@link CreatorMetaConnectedEvent}): the
     * token row is durably committed before this runs, and this runs on another thread, so the
     * creator's connect response is never delayed by a Graph round trip and no failure here can
     * roll back the connect.
     *
     * <p><b>This never throws.</b> A best-effort head start on a nightly job must not surface as
     * an error anywhere — the cron remains the guarantee, and this is the optimisation. Every
     * failure is logged and swallowed; the creator simply waits for tonight's run as they did
     * before this existed.
     *
     * <p>Gated on the same {@code influora.creator-copilot.enabled} flag as the cron. With the
     * flag false — which is the default in application.yml and in BOTH shipped compose files —
     * this does nothing, exactly like the cron it front-runs.
     */
    @Async
    @EventListener
    public void onCreatorConnected(CreatorMetaConnectedEvent event) {
        if (!props.isEnabled()) {
            return;
        }
        String creatorProfileId = event.creatorProfileId();
        try {
            Optional<MetaOAuthToken> token =
                    tokenRepository
                            .findByWorkspaceIdIsNullAndRevokedFalseAndExpiresAtAfter(Instant.now())
                            .stream()
                            .filter(t -> creatorProfileId.equals(t.getCreatorProfileId()))
                            .findFirst();
            if (token.isEmpty()) {
                log.info(
                        "CreatorCaptionSyncJob: connect-triggered sync for creator {} found no live"
                                + " token; leaving it to the nightly run",
                        creatorProfileId);
                return;
            }
            CreatorSyncOutcome outcome = syncOneCreator(token.get());
            log.info(
                    "CreatorCaptionSyncJob: connect-triggered sync for creator {} — {} inserted, {}"
                            + " skipped, {} items failed, processed={}",
                    creatorProfileId,
                    outcome.inserted(),
                    outcome.skipped(),
                    outcome.itemsFailed(),
                    outcome.processed());
        } catch (Exception e) {
            // Deliberately swallowed — see the javadoc. The cron is the guarantee.
            log.warn(
                    "CreatorCaptionSyncJob: connect-triggered sync failed for creator {}: {}",
                    creatorProfileId,
                    e.getMessage());
        }
    }

    /** Per-creator counters, so the cron's aggregate logging is unchanged by the extraction. */
    private record CreatorSyncOutcome(
            boolean processed, boolean skippedNoAccount, boolean failed, int inserted, int skipped, int itemsFailed) {}

    /**
     * Syncs one creator's recent captions. Extracted verbatim from the cron loop
     * (T-IGTRUST-0907) so the scheduled run and the connect-triggered run cannot diverge.
     * Swallows the same exceptions the loop swallowed and reports them through the returned
     * counters instead of throwing.
     */
    private CreatorSyncOutcome syncOneCreator(MetaOAuthToken token) {
        String creatorProfileId = token.getCreatorProfileId();
        String igBusinessAccountId = token.getIgBusinessAccountId();

        if (igBusinessAccountId == null || igBusinessAccountId.isBlank()) {
            return new CreatorSyncOutcome(false, true, false, 0, 0, 0);
        }

        int inserted = 0;
        int skipped = 0;
        int itemsFailed = 0;

        try {
            Optional<String> accessToken = tokenStorage.getValidCreatorToken(creatorProfileId);
            if (accessToken.isEmpty()) {
                return new CreatorSyncOutcome(false, true, false, 0, 0, 0);
            }

            InstagramMediaResponse mediaResponse =
                    instagramClient.getMedia(
                            igBusinessAccountId,
                            accessToken.get(),
                            props.getCaptionSyncMediaLimit(),
                            // T-IGLOGIN-0820: the caller already holds the token row, so the
                            // host comes straight off it rather than a second lookup.
                            token.getAuthPath());

            if (mediaResponse == null || mediaResponse.data() == null) {
                return new CreatorSyncOutcome(true, false, false, 0, 0, 0);
            }

            for (InstagramMediaResponse.MediaItem item : mediaResponse.data()) {
                try {
                    if (persistIfNew(creatorProfileId, item)) {
                        inserted++;
                    } else {
                        skipped++;
                    }
                } catch (Exception itemFailure) {
                    itemsFailed++;
                    log.warn(
                            "CreatorCaptionSyncJob: failed to persist media {} for creator {}: {}",
                            item.id(),
                            creatorProfileId,
                            itemFailure.getMessage());
                }
            }
            return new CreatorSyncOutcome(true, false, false, inserted, skipped, itemsFailed);
        } catch (MetaApiException metaFailure) {
            log.warn(
                    "CreatorCaptionSyncJob: Meta fetch failed for creator {}: {}",
                    creatorProfileId,
                    metaFailure.getMessage());
            return new CreatorSyncOutcome(false, false, true, inserted, skipped, itemsFailed);
        } catch (Exception unexpected) {
            log.warn(
                    "CreatorCaptionSyncJob: unexpected failure syncing captions for creator {}: {}",
                    creatorProfileId,
                    unexpected.getMessage());
            return new CreatorSyncOutcome(false, false, true, inserted, skipped, itemsFailed);
        }
    }

    /**
     * Inserts a {@code PENDING} row for one media item unless it is already cached or has no
     * caption text to tag. Returns {@code true} if a new row was inserted, {@code false} if the
     * item was skipped (duplicate or blank caption).
     */
    private boolean persistIfNew(String creatorProfileId, InstagramMediaResponse.MediaItem item) {
        if (item.caption() == null || item.caption().isBlank()) {
            return false;
        }

        if (captionRepository.findByCreatorProfileIdAndIgMediaId(creatorProfileId, item.id()).isPresent()) {
            return false;
        }

        CreatorCaptionCache row =
                CreatorCaptionCache.builder()
                        .id(Ulids.newUlid())
                        .creatorProfileId(creatorProfileId)
                        .igMediaId(item.id())
                        .captionText(item.caption())
                        .postedAt(parseTimestamp(item.timestamp()))
                        .build();
        captionRepository.save(row);
        return true;
    }

    /** Parses Meta's {@code 2025-09-01T12:00:00+0000}-style timestamp defensively — {@code
     * postedAt} is nullable, so any parse failure just yields {@code null} rather than aborting
     * the item. */
    private Instant parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw, META_TIMESTAMP_FORMAT).toInstant();
        } catch (DateTimeParseException primaryFailure) {
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException fallbackFailure) {
                return null;
            }
        }
    }
}
