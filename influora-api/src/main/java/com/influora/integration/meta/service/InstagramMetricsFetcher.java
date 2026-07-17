package com.influora.integration.meta.service;

import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.InstagramInsightsResponse;
import com.influora.integration.meta.dto.InstagramMediaResponse;
import com.influora.integration.meta.dto.InstagramUserResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * High-level orchestrator that composes {@link InstagramInsightsClient}'s three separate Graph API
 * calls (profile fetch, recent-media list fetch, per-media insights fetch) into one {@link
 * #fetchAll} call — Wave B task B3 (spec {@code wiki/tech/REMAINING_WORK_PLAN.md} §B3, "high-level
 * compose of profile+media+insights fetch", package diagram §1.1).
 *
 * <p><b>Relationship to {@code MetricsPollingJob} [important — read before changing either class]
 * </b>: {@code MetricsPollingJob} (Wave B task B1) already contains this exact compose-and-degrade
 * orchestration inline (its {@code pollOne}/{@code pollRecentMedia}/{@code pollOneMedia} methods),
 * including:
 *
 * <ul>
 *   <li>a pre-flight rate-limit check (skip entirely at &ge;90% usage) before the profile call;
 *   <li>a second rate-limit check before the media-list call (the profile call may have pushed
 *       usage higher);
 *   <li>per-media-item graceful degradation: a {@code MetaApiException} (including Meta's 400 for
 *       an insights-metric/media-type combo it doesn't support) or a {@code
 *       MetaRateLimitException} fetching one media item's insights must never fail that item's
 *       base row (media type/permalink/likes/comments/timestamp are still captured from the media
 *       list) and must never abort the rest of the batch;
 *   <li>a rate-limited media-list fetch skips media polling entirely for this cycle without
 *       failing the creator's already-recorded profile poll.
 * </ul>
 *
 * <p><b>Design choice made for this task: built alongside, {@code MetricsPollingJob} NOT
 * refactored to delegate to this class.</b> {@code MetricsPollingJob} carries ~20 existing unit
 * tests (see {@code MetricsPollingJobTest}) that assert on its exact persistence side effects
 * (mock-verify calls into {@code CreatorMetricsRepository}/{@code MediaMetricsRepository}/{@code
 * AuditLogService}) and its exact rate-limit-check call sequence (e.g. {@code
 * testMediaMetricsDefersWhenRateLimitApproachingAfterProfilePoll} asserts two separate {@code
 * rateLimitTracker.getCurrentUsage} calls with different stubbed return values). Rewiring that job
 * to delegate its fetch logic to this new class would require either (a) changing this
 * orchestrator's shape to also own persistence and audit-logging so behavior/verification order
 * stays identical -- which would defeat the point of a fetch-only orchestrator and bloat its
 * responsibility -- or (b) restructuring the job's persistence loop around this class's return
 * shape, which risks subtly changing the very rate-limit-check ordering and graceful-degradation
 * behavior the task explicitly says must be preserved exactly. Building this class alongside as a
 * pure fetch-composition unit (no repository/audit dependencies) is the safe-diff choice: it adds
 * new, independently-tested surface without touching a single line of the already-reviewed,
 * heavily-tested {@code MetricsPollingJob}. A future task can migrate {@code MetricsPollingJob} to
 * call {@link #fetchAll} once that migration itself is scoped and its test suite updated
 * deliberately, rather than as a side effect of this one.
 *
 * <p><b>What this class does NOT do</b> (by design, to stay a pure fetch orchestrator): it does
 * not persist anything ({@code CreatorMetric}/{@code MediaMetric} rows), does not write audit-log
 * entries, and does not know about {@code MetaOAuthToken}/{@code MetaTokenStorage} token lookup --
 * callers own persistence, auditing, and token resolution, exactly as {@code MetricsPollingJob}
 * does today. This keeps the orchestrator trivially unit-testable against mocked clients (per this
 * task's acceptance criteria) and reusable by any future caller (e.g. an on-demand
 * "refresh this creator now" admin action) without dragging in polling-job-specific concerns.
 *
 * <p><b>Never logs tokens or captions</b> -- {@code accessToken} is passed through to {@link
 * InstagramInsightsClient} calls but never logged by this class; media captions ({@link
 * InstagramMediaResponse.MediaItem#caption()}) are present on the raw Graph API response but this
 * class does not read or log that field (caption persistence is Wave C task C1's separate,
 * ADR-governed concern, not this orchestrator's).
 */
@Component
public class InstagramMetricsFetcher {

    private static final Logger log = LoggerFactory.getLogger(InstagramMetricsFetcher.class);

    // Mirrors MetricsPollingJob.RECENT_MEDIA_LIMIT (spec §3.1's recent-media fetch cap) and
    // MetricsPollingJob's rate-limit threshold constant -- kept in sync deliberately since this
    // orchestrator preserves that job's exact behavior contract (see class javadoc).
    private static final int DEFAULT_MEDIA_LIMIT = 25;
    private static final int RATE_LIMIT_THRESHOLD_PERCENT = 90;

    private final InstagramInsightsClient instagramClient;
    private final MetaRateLimitTracker rateLimitTracker;

    public InstagramMetricsFetcher(
            InstagramInsightsClient instagramClient, MetaRateLimitTracker rateLimitTracker) {
        this.instagramClient = instagramClient;
        this.rateLimitTracker = rateLimitTracker;
    }

    /**
     * One creator's full fetch: profile, then (rate-limit permitting) recent media plus per-media
     * insights, with the same graceful-degradation rules {@code MetricsPollingJob} already applies
     * (see class javadoc). Never throws for a rate-limit/API error on the media side -- those are
     * captured in the returned {@link Result} instead, so a caller can decide what to persist/log
     * without this class making that decision for them.
     *
     * @param creatorProfileId the Instagram business account id (also the rate-limit tracker key)
     * @param accessToken the creator's valid Meta access token; never logged
     * @return a {@link Result} describing what was fetched and what was skipped/degraded
     * @throws MetaRateLimitException if the profile fetch itself is rate-limited -- unlike the
     *     media side, there is no partial profile to return, so this propagates to the caller
     *     (mirrors {@code MetricsPollingJob.pollOne}'s try/catch around {@code getProfile})
     * @throws com.influora.integration.meta.exception.MetaTokenExpiredException if the token is
     *     expired/invalid -- propagates for the same reason
     * @throws MetaApiException for any other profile-fetch failure -- propagates for the same
     *     reason
     */
    public Result fetchAll(String creatorProfileId, String accessToken) {
        return fetchAll(creatorProfileId, accessToken, DEFAULT_MEDIA_LIMIT);
    }

    /**
     * Same as {@link #fetchAll(String, String)} with an explicit recent-media fetch cap.
     *
     * <p>Note on rate-limit check placement: {@code MetricsPollingJob.pollOne} checks the
     * rate-limit budget BEFORE its profile call, as a belt-and-braces guard on top of {@code
     * MetaGraphApiClient}'s own enforcement. This method intentionally does not duplicate that
     * pre-profile-call check -- {@code fetchAll} always needs to attempt the profile fetch to
     * produce a {@link Result} at all, and a caller who wants that pre-flight skip (e.g. to avoid
     * calling {@code fetchAll} entirely for a near-limited creator) can call {@link
     * MetaRateLimitTracker#getCurrentUsage} themselves first, exactly as {@code
     * MetricsPollingJob.pollOne} does today. The pre-media-list-call check (mirroring {@code
     * pollRecentMedia}'s second guard, since the profile call may have pushed usage higher) IS
     * applied here, inside {@link #fetchMediaWithInsights}.
     */
    public Result fetchAll(String creatorProfileId, String accessToken, int mediaLimit) {
        InstagramUserResponse profile = instagramClient.getProfile(creatorProfileId, accessToken);
        List<MediaWithInsights> media = fetchMediaWithInsights(creatorProfileId, accessToken, mediaLimit);
        return new Result(profile, media);
    }

    /**
     * Fetches up to {@code mediaLimit} recent media items and, for each, attempts the per-media
     * insights call -- same shape as {@code MetricsPollingJob.pollRecentMedia}/{@code
     * pollOneMedia}. Never throws: a rate-limited or failed media-list fetch yields an empty list
     * (mirrors the job's "skip media_metrics this cycle" behavior); a rate-limited or rejected
     * per-item insights call yields that item with {@code insights == null} (mirrors the job's
     * "persist base fields only" degradation) rather than dropping the item entirely.
     */
    public List<MediaWithInsights> fetchMediaWithInsights(
            String creatorProfileId, String accessToken, int mediaLimit) {
        if (isRateLimited(creatorProfileId, "media_metrics (pre media-list fetch)")) {
            return List.of();
        }

        InstagramMediaResponse mediaResponse;
        try {
            mediaResponse = instagramClient.getMedia(creatorProfileId, accessToken, mediaLimit);
        } catch (MetaRateLimitException e) {
            rateLimitTracker.markLimited(creatorProfileId);
            log.warn(
                    "InstagramMetricsFetcher: rate limited fetching media list for creator {}, returning"
                            + " empty media result this cycle",
                    creatorProfileId);
            return List.of();
        } catch (MetaApiException e) {
            log.error(
                    "InstagramMetricsFetcher: failed to fetch media list for creator {}: {}",
                    creatorProfileId,
                    e.getMessage());
            return List.of();
        }

        if (mediaResponse == null || mediaResponse.data() == null || mediaResponse.data().isEmpty()) {
            return List.of();
        }

        List<MediaWithInsights> results = new ArrayList<>(mediaResponse.data().size());
        for (InstagramMediaResponse.MediaItem mediaItem : mediaResponse.data()) {
            results.add(fetchOneMediaWithInsights(creatorProfileId, accessToken, mediaItem));
        }
        return results;
    }

    /**
     * Fetches insights for one media item, degrading to {@code insights == null} on a rate-limit or
     * API error (including Meta's 400 for an unsupported metric/media_type combo) rather than
     * throwing -- mirrors {@code MetricsPollingJob.pollOneMedia}'s per-item try/catch exactly.
     */
    private MediaWithInsights fetchOneMediaWithInsights(
            String creatorProfileId, String accessToken, InstagramMediaResponse.MediaItem mediaItem) {
        try {
            InstagramInsightsResponse insights =
                    instagramClient.getMediaInsights(mediaItem.id(), accessToken, creatorProfileId);
            return new MediaWithInsights(mediaItem, insights);
        } catch (MetaRateLimitException e) {
            rateLimitTracker.markLimited(creatorProfileId);
            log.warn(
                    "InstagramMetricsFetcher: rate limited fetching insights for media {} (creator {}),"
                            + " returning base media fields only",
                    mediaItem.id(),
                    creatorProfileId);
            return new MediaWithInsights(mediaItem, null);
        } catch (MetaApiException e) {
            log.warn(
                    "InstagramMetricsFetcher: insights unavailable for media {} (creator {}): {}",
                    mediaItem.id(),
                    creatorProfileId,
                    e.getMessage());
            return new MediaWithInsights(mediaItem, null);
        }
    }

    /**
     * Pre-flight rate-limit guard, mirroring {@code MetricsPollingJob.pollRecentMedia}'s
     * pre-media-list-call check -- logs and returns {@code true} at {@link
     * #RATE_LIMIT_THRESHOLD_PERCENT}+ usage rather than spending budget on a call already known to
     * be near-limited.
     */
    private boolean isRateLimited(String creatorProfileId, String context) {
        int usage = rateLimitTracker.getCurrentUsage(creatorProfileId);
        if (usage >= RATE_LIMIT_THRESHOLD_PERCENT) {
            log.warn(
                    "InstagramMetricsFetcher: creator {} at {}% Meta rate-limit usage, deferring {} to"
                            + " next cycle",
                    creatorProfileId,
                    usage,
                    context);
            return true;
        }
        return false;
    }

    /** One media item paired with its insights response, or {@code null} insights if unavailable/degraded. */
    public record MediaWithInsights(
            InstagramMediaResponse.MediaItem mediaItem, InstagramInsightsResponse insights) {}

    /** Full composed result of one creator's {@link #fetchAll} call. */
    public record Result(InstagramUserResponse profile, List<MediaWithInsights> media) {}
}
