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
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            String creatorProfileId = token.getCreatorProfileId();
            String igBusinessAccountId = token.getIgBusinessAccountId();

            if (igBusinessAccountId == null || igBusinessAccountId.isBlank()) {
                creatorsSkippedNoAccount++;
                continue;
            }

            try {
                Optional<String> accessToken = tokenStorage.getValidCreatorToken(creatorProfileId);
                if (accessToken.isEmpty()) {
                    creatorsSkippedNoAccount++;
                    continue;
                }

                InstagramMediaResponse mediaResponse =
                        instagramClient.getMedia(
                                igBusinessAccountId,
                                accessToken.get(),
                                props.getCaptionSyncMediaLimit(),
                                // T-IGLOGIN-0820: this loop already holds the token row, so the
                                // host comes straight off it rather than a second lookup.
                                token.getAuthPath());
                creatorsProcessed++;

                if (mediaResponse == null || mediaResponse.data() == null) {
                    continue;
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
            } catch (MetaApiException metaFailure) {
                creatorsFailed++;
                log.warn(
                        "CreatorCaptionSyncJob: Meta fetch failed for creator {}: {}",
                        creatorProfileId,
                        metaFailure.getMessage());
            } catch (Exception unexpected) {
                creatorsFailed++;
                log.warn(
                        "CreatorCaptionSyncJob: unexpected failure syncing captions for creator {}: {}",
                        creatorProfileId,
                        unexpected.getMessage());
            }
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
