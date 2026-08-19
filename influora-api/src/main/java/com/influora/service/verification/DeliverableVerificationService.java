package com.influora.service.verification;

import com.influora.common.Ulids;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.DeliverableMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.InstagramInsightsResponse;
import com.influora.integration.meta.dto.InstagramMediaResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.exception.MetaTokenExpiredException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DPF-6 — platform-verified analytics. Pulls real metrics from a deliverable's already-validated
 * {@code postUrl} via the existing Meta Graph API client and persists them as {@code
 * PLATFORM_VERIFIED} on the linked {@link DeliverableMetric} row, promoting the {@link Deliverable}
 * to {@code VERIFIED} status. Fixes defect #2b: analytics were previously 100% creator-self-typed.
 *
 * <p><b>Fail-closed, always</b> (spec requirement): no valid token, an API error, a private/removed
 * post, or a rate limit never crashes and never fabricates a number — it falls back to leaving
 * whatever {@code CREATOR_REPORTED} row already exists untouched, and logs the reason. Every branch
 * below either returns {@link Outcome#VERIFIED} after a real successful fetch+persist, or a
 * {@code FALLBACK_*} outcome with nothing written.
 *
 * <p><b>YouTube — explicitly not implemented yet</b> (Step-0 investigation finding): there is no
 * YouTube API client anywhere in this codebase (only {@code MetaGraphApiClient}/{@code
 * InstagramInsightsClient}/{@code FacebookPageClient} exist, all Meta/Instagram). A YouTube
 * {@code postUrl} is recognized by {@link PostUrlIdentifier} but always falls back to {@code
 * FALLBACK_YOUTUBE_UNSUPPORTED} here — this is the explicit, documented follow-up the task
 * description called out, not an oversight.
 *
 * <p><b>Instagram media lookup:</b> the Graph API has no "look up media by shortcode/permalink"
 * endpoint for a Business/Creator account, so this reuses the existing {@link
 * InstagramInsightsClient#getMedia} (already used by {@code MetricsPollingJob}/{@code
 * InstagramMetricsFetcher}) to fetch the creator's recent media and matches the deliverable's
 * shortcode against each item's {@code permalink}. A shortcode not found in the recent-media page
 * (post is private, deleted, or simply outside the fetched window) is a fallback, never an error.
 *
 * <p><b>Idempotent by construction:</b> {@link DeliverableMetricRepository#findByMilestoneId} is a
 * 1:1 upsert target — re-running verification for the same deliverable overwrites the same row
 * with a fresh fetch rather than appending, so re-running never double-counts.
 *
 * <p><b>Never touches escrow/release logic</b> (DPF-5, blocked separately) — this only writes
 * {@link DeliverableMetric} rows and flips {@link Deliverable#applyVerify()}'s status field.
 */
@Service
public class DeliverableVerificationService {

    private static final Logger log = LoggerFactory.getLogger(DeliverableVerificationService.class);

    /** Mirrors MetricsPollingJob/InstagramMetricsFetcher's own pre-flight threshold. */
    private static final int RATE_LIMIT_THRESHOLD_PERCENT = 90;

    private static final int RECENT_MEDIA_LIMIT = 50;

    // F-0355: the response keys follow InstagramInsightsClient#INSIGHTS_METRICS. Meta removed
    // "impressions" (media created after 2024-07-02) and "engagement"; "views" and
    // "total_interactions" are their documented replacements. The DeliverableMetric columns keep
    // their existing names — only the Meta-side key changed.
    private static final String METRIC_VIEWS = "views";
    private static final String METRIC_REACH = "reach";
    private static final String METRIC_TOTAL_INTERACTIONS = "total_interactions";

    /** Outcome of one {@link #verify(Deliverable)} call — never an exception for expected failures. */
    public enum Outcome {
        VERIFIED,
        FALLBACK_NO_POST_URL,
        FALLBACK_NO_MILESTONE,
        FALLBACK_UNRECOGNIZED_URL,
        FALLBACK_YOUTUBE_UNSUPPORTED,
        FALLBACK_NO_TOKEN,
        FALLBACK_TOKEN_EXPIRED,
        FALLBACK_RATE_LIMITED,
        FALLBACK_NOT_FOUND,
        FALLBACK_API_ERROR,
        FALLBACK_DATA_INTEGRITY
    }

    private final DeliverableRepository deliverableRepository;
    private final DeliverableMetricRepository deliverableMetricRepository;
    private final CollaborationRepository collaborationRepository;
    private final MetaOAuthTokenRepository metaOAuthTokenRepository;
    private final MetaTokenStorage metaTokenStorage;
    private final InstagramInsightsClient instagramInsightsClient;
    private final MetaRateLimitTracker rateLimitTracker;

    public DeliverableVerificationService(
            DeliverableRepository deliverableRepository,
            DeliverableMetricRepository deliverableMetricRepository,
            CollaborationRepository collaborationRepository,
            MetaOAuthTokenRepository metaOAuthTokenRepository,
            MetaTokenStorage metaTokenStorage,
            InstagramInsightsClient instagramInsightsClient,
            MetaRateLimitTracker rateLimitTracker) {
        this.deliverableRepository = deliverableRepository;
        this.deliverableMetricRepository = deliverableMetricRepository;
        this.collaborationRepository = collaborationRepository;
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
        this.metaTokenStorage = metaTokenStorage;
        this.instagramInsightsClient = instagramInsightsClient;
        this.rateLimitTracker = rateLimitTracker;
    }

    /**
     * Attempts to verify one deliverable's posted content against the real platform. Always
     * returns normally (never throws for an expected failure mode) — see class javadoc.
     */
    @Transactional
    public Outcome verify(Deliverable deliverable) {
        String postUrl = deliverable.getPostUrl();
        if (postUrl == null || postUrl.isBlank()) {
            return fallback(deliverable, Outcome.FALLBACK_NO_POST_URL, "no postUrl set yet");
        }

        String milestoneId = deliverable.getMilestoneId();
        if (milestoneId == null || milestoneId.isBlank()) {
            return fallback(
                    deliverable,
                    Outcome.FALLBACK_NO_MILESTONE,
                    "deliverable is not linked to a payment milestone, cannot persist a verified metric row");
        }

        Optional<PostUrlIdentifier.Result> identifier = PostUrlIdentifier.extract(postUrl);
        if (identifier.isEmpty()) {
            return fallback(
                    deliverable,
                    Outcome.FALLBACK_UNRECOGNIZED_URL,
                    "postUrl did not match a recognized, safe platform shape (unknown platform or"
                            + " contained an encoded bracket/quote — defense-in-depth reject)");
        }

        return switch (identifier.get().platform()) {
            case YOUTUBE ->
                    fallback(
                            deliverable,
                            Outcome.FALLBACK_YOUTUBE_UNSUPPORTED,
                            "no YouTube API client exists yet in this codebase (Step-0 finding) —"
                                    + " YouTube verification is a documented follow-up, not built here");
            case INSTAGRAM -> verifyInstagram(deliverable, identifier.get().mediaId());
        };
    }

    private Outcome verifyInstagram(Deliverable deliverable, String shortcode) {
        String creatorProfileId = deliverable.getCreatorProfileId();

        Optional<MetaOAuthToken> tokenRow =
                metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(
                        creatorProfileId);
        if (tokenRow.isEmpty()) {
            return fallback(
                    deliverable, Outcome.FALLBACK_NO_TOKEN, "no active Meta connection for creator " + creatorProfileId);
        }

        // F-0166: this called the WORKSPACE-scoped getValidToken(workspaceId, creatorProfileId)
        // with tokenRow.get().getWorkspaceId() — always null for a creator-owned row. The
        // repository method backing getValidToken carries an explicit `workspaceId IS NOT NULL`
        // predicate (CR-111 hardening, MetaOAuthTokenRepository.java), so a null workspaceId can
        // NEVER match any row, brand or creator — this could not have worked for a single
        // creator, independent of the CR-99 ULID fix above it. getValidCreatorToken is the
        // creator-scoped getter every other creator-owned code path already uses (e.g.
        // MetaConnectionService, CreatorCaptionSyncJob).
        Optional<String> accessToken = metaTokenStorage.getValidCreatorToken(creatorProfileId);
        // T-IGLOGIN-0820: the token's own path decides the Graph host; an Instagram-Login token
        // sent to graph.facebook.com is rejected outright.
        MetaAuthPath authPath =
                metaTokenStorage.getCreatorAuthPath(creatorProfileId).orElse(MetaAuthPath.FACEBOOK_LOGIN);
        if (accessToken.isEmpty()) {
            return fallback(
                    deliverable,
                    Outcome.FALLBACK_NO_TOKEN,
                    "Meta token for creator " + creatorProfileId + " is missing, revoked, or expired");
        }

        // CR-99/F-0113 fix: Meta's Graph API requires the numeric IG Business Account ID in the
        // request path, not our internal ULID creatorProfileId — a token row with none on file
        // cannot be verified against Meta at all.
        String igBusinessAccountId = tokenRow.get().getIgBusinessAccountId();
        if (igBusinessAccountId == null || igBusinessAccountId.isBlank()) {
            return fallback(
                    deliverable,
                    Outcome.FALLBACK_DATA_INTEGRITY,
                    "no igBusinessAccountId on file for creator " + creatorProfileId);
        }

        // F-0126: keyed on creatorProfileId (internal ULID) while MetaGraphApiClient's own
        // enforcement and usage-header parsing key on igBusinessAccountId — two disjoint key
        // namespaces for the same account meant this pre-flight guard could never see usage the
        // client itself had recorded, so it always read 0 and was decorative.
        if (rateLimitTracker.getCurrentUsage(igBusinessAccountId) >= RATE_LIMIT_THRESHOLD_PERCENT) {
            return fallback(
                    deliverable,
                    Outcome.FALLBACK_RATE_LIMITED,
                    "creator " + creatorProfileId + " is at/near Meta rate-limit usage, deferring");
        }

        InstagramMediaResponse mediaResponse;
        try {
            mediaResponse =
                    instagramInsightsClient.getMedia(
                            igBusinessAccountId, accessToken.get(), RECENT_MEDIA_LIMIT, authPath);
        } catch (MetaRateLimitException e) {
            // F-0126: markLimited must use the same key as getCurrentUsage() above (and the same
            // key MetaGraphApiClient itself uses) — igBusinessAccountId, not creatorProfileId.
            rateLimitTracker.markLimited(igBusinessAccountId);
            return fallback(deliverable, Outcome.FALLBACK_RATE_LIMITED, "rate limited fetching media list: " + e.getMessage());
        } catch (MetaTokenExpiredException e) {
            return fallback(deliverable, Outcome.FALLBACK_TOKEN_EXPIRED, "token expired fetching media list: " + e.getMessage());
        } catch (MetaApiException e) {
            return fallback(deliverable, Outcome.FALLBACK_API_ERROR, "Meta API error fetching media list: " + e.getMessage());
        }

        Optional<InstagramMediaResponse.MediaItem> matched = findByShortcode(mediaResponse, shortcode);
        if (matched.isEmpty()) {
            return fallback(
                    deliverable,
                    Outcome.FALLBACK_NOT_FOUND,
                    "no media in creator's recent-media page matched shortcode " + shortcode
                            + " (private account, deleted post, or outside the fetched window)");
        }

        InstagramInsightsResponse insights;
        try {
            // F-0126: third arg is InstagramInsightsClient's businessAccountId param, which flows
            // into MetaGraphApiClient's own rate-limit tracking (both its pre-flight check and its
            // X-Business-Use-Case-Usage header parsing) — passing creatorProfileId here fed the
            // client's real Meta-usage data into the wrong key, orphaned from every other read of
            // this account's usage. Must be igBusinessAccountId, same as getMedia() above.
            insights =
                    instagramInsightsClient.getMediaInsights(
                            matched.get().id(), accessToken.get(), igBusinessAccountId, authPath);
        } catch (MetaRateLimitException e) {
            rateLimitTracker.markLimited(igBusinessAccountId);
            return fallback(deliverable, Outcome.FALLBACK_RATE_LIMITED, "rate limited fetching insights: " + e.getMessage());
        } catch (MetaTokenExpiredException e) {
            return fallback(deliverable, Outcome.FALLBACK_TOKEN_EXPIRED, "token expired fetching insights: " + e.getMessage());
        } catch (MetaApiException e) {
            // Includes Meta's 400 for an insights-metric/media_type combo it doesn't support
            // (e.g. a private/restricted post) — degrade gracefully, never crash.
            return fallback(deliverable, Outcome.FALLBACK_API_ERROR, "Meta API error fetching insights: " + e.getMessage());
        }

        Long reach = metricValue(insights, METRIC_REACH);
        Long impressions = metricValue(insights, METRIC_VIEWS);
        Long engagements = metricValue(insights, METRIC_TOTAL_INTERACTIONS);

        return persistVerified(deliverable, reach, impressions, engagements, matched.get().id());
    }

    /**
     * Upserts the {@link DeliverableMetric} row and promotes the deliverable to {@code VERIFIED}.
     * {@link CollaborationRepository} is only consulted when a row does not exist yet (to attribute
     * a brand-new row's required {@code reportedByCreatorId} FK) — refreshing an already-existing
     * row (re-verification) never needs it.
     */
    private Outcome persistVerified(
            Deliverable deliverable, Long reach, Long impressions, Long engagements, String platformMediaId) {
        Optional<DeliverableMetric> existing =
                deliverableMetricRepository.findByMilestoneId(deliverable.getMilestoneId());

        DeliverableMetric metric;
        if (existing.isPresent()) {
            metric = existing.get();
        } else {
            Optional<Collaboration> collaboration =
                    collaborationRepository.findById(deliverable.getCollaborationId());
            if (collaboration.isEmpty()) {
                return fallback(
                        deliverable,
                        Outcome.FALLBACK_DATA_INTEGRITY,
                        "collaboration " + deliverable.getCollaborationId() + " referenced by deliverable not found");
            }
            metric =
                    DeliverableMetric.builder()
                            .id(Ulids.newUlid())
                            .milestoneId(deliverable.getMilestoneId())
                            .collaborationId(deliverable.getCollaborationId())
                            .reportedByCreatorId(collaboration.get().getCreatorId())
                            .build();
        }

        metric.applyVerifiedReport(reach, impressions, engagements, platformMediaId);
        deliverableMetricRepository.save(metric);

        deliverable.applyVerify();
        deliverableRepository.save(deliverable);

        log.info(
                "DeliverableVerificationService: verified deliverable {} against Instagram media {}",
                deliverable.getId(),
                platformMediaId);
        return Outcome.VERIFIED;
    }

    private static Optional<InstagramMediaResponse.MediaItem> findByShortcode(
            InstagramMediaResponse mediaResponse, String shortcode) {
        if (mediaResponse == null || mediaResponse.data() == null) {
            return Optional.empty();
        }
        String marker = "/" + shortcode + "/";
        return mediaResponse.data().stream()
                .filter(item -> item.permalink() != null && item.permalink().contains(marker))
                .findFirst();
    }

    private static Long metricValue(InstagramInsightsResponse insights, String metricName) {
        if (insights == null || insights.data() == null) {
            return null;
        }
        return insights.data().stream()
                .filter(m -> metricName.equals(m.name()))
                .findFirst()
                .map(DeliverableVerificationService::firstValue)
                .orElse(null);
    }

    private static Long firstValue(InstagramInsightsResponse.InsightMetric metric) {
        List<InstagramInsightsResponse.InsightValue> values = metric.values();
        if (values == null || values.isEmpty() || values.get(0).value() == null) {
            return null;
        }
        Object raw = values.get(0).value();
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Outcome fallback(Deliverable deliverable, Outcome outcome, String reason) {
        log.warn(
                "DeliverableVerificationService: deliverable {} falling back to CREATOR_REPORTED ({}): {}",
                deliverable.getId(),
                outcome,
                reason);
        return outcome;
    }
}
