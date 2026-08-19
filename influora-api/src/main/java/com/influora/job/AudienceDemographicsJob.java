package com.influora.job;

import com.influora.common.JsonLists;
import com.influora.common.Ulids;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.domain.entity.AudienceDemographics;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.AudienceDemographicsResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.exception.MetaTokenExpiredException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.AuditLogService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Weekly fetch of Meta audience-demographics insights (age/gender, country, city, locale
 * breakdowns) for every creator with a valid (non-revoked, non-expired) Meta token — Wave B task
 * B4 (wiki/tech/REMAINING_WORK_PLAN.md). Writes {@code audience_demographics} rows (V25).
 *
 * <p>Mirrors {@link MetricsPollingJob}/{@link MetaTokenRefreshService}'s exact conventions: a
 * single-threaded scheduled sweep over {@link MetaOAuthTokenRepository}, an {@link AtomicBoolean}
 * overlap guard, per-creator try/catch isolation so one creator's failure never aborts the batch,
 * and a pre-flight {@link MetaRateLimitTracker} check before spending budget on the call.
 *
 * <p>Weekly cadence (not the 6h {@code MetricsPollingJob} cadence): demographic breakdowns shift
 * slowly compared to engagement metrics, and Meta's own {@code audience_*} metrics are a
 * {@code period=lifetime} snapshot recomputed server-side rather than a per-interaction counter, so
 * polling more often buys nothing.
 *
 * <p>Iterates {@link MetaOAuthToken} rows, not {@code creator_profiles} directly — same reasoning
 * as {@code MetricsPollingJob}: the token row carries the (workspaceId, creatorProfileId) pairing,
 * and this job runs system-wide across all workspaces, not per-request, so {@code
 * MetricsAuthorizationService} does not apply here (see that service's javadoc, "Not wired into
 * MetricsPollingJob").
 *
 * <p><b>Graceful degradation, not fabrication:</b> Meta's audience insights are only available for
 * accounts with 100+ followers ({@link InstagramInsightsClient#getAudienceDemographics} javadoc) —
 * an empty/error response for a smaller creator is logged and skipped, never turned into a
 * fabricated zero-filled snapshot row.
 */
@Component
public class AudienceDemographicsJob {

    private static final Logger log = LoggerFactory.getLogger(AudienceDemographicsJob.class);
    private static final String PLATFORM_INSTAGRAM = "INSTAGRAM";
    private static final String DATA_SOURCE_META_API = "META_API";
    private static final int RATE_LIMIT_THRESHOLD_PERCENT = 90;

    // Meta's audience_gender_age breakdown name, e.g. "F.25-34" / "M.18-24" keys.
    private static final String BREAKDOWN_GENDER_AGE = "audience_gender_age";
    private static final String BREAKDOWN_COUNTRY = "audience_country";
    private static final String BREAKDOWN_CITY = "audience_city";
    private static final String BREAKDOWN_LOCALE = "audience_locale";

    private final MetaOAuthTokenRepository tokenRepository;
    private final MetaTokenStorage tokenStorage;
    private final InstagramInsightsClient instagramClient;
    private final AudienceDemographicsRepository demographicsRepository;
    private final MetaRateLimitTracker rateLimitTracker;
    private final AuditLogService auditLog;

    /** In-memory overlap guard — same per-instance style as {@code MetricsPollingJob.running}. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AudienceDemographicsJob(
            MetaOAuthTokenRepository tokenRepository,
            MetaTokenStorage tokenStorage,
            InstagramInsightsClient instagramClient,
            AudienceDemographicsRepository demographicsRepository,
            MetaRateLimitTracker rateLimitTracker,
            AuditLogService auditLog) {
        this.tokenRepository = tokenRepository;
        this.tokenStorage = tokenStorage;
        this.instagramClient = instagramClient;
        this.demographicsRepository = demographicsRepository;
        this.rateLimitTracker = rateLimitTracker;
        this.auditLog = auditLog;
    }

    /** Every Sunday at 3:30 AM — weekly cadence, offset from the daily 2:30/3:00/4:00 AM token jobs. */
    @Scheduled(cron = "0 30 3 * * SUN")
    @SchedulerLock(name = "AudienceDemographicsJob", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void pollDemographics() {
        if (!running.compareAndSet(false, true)) {
            log.warn("AudienceDemographicsJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            runPoll();
        } finally {
            running.set(false);
        }
    }

    private void runPoll() {
        List<MetaOAuthToken> connectedTokens =
                tokenRepository.findByRevokedFalseAndExpiresAtAfter(Instant.now());

        int polled = 0;
        int failed = 0;

        for (MetaOAuthToken tokenRow : connectedTokens) {
            String creatorProfileId = tokenRow.getCreatorProfileId();
            String igBusinessAccountId = tokenRow.getIgBusinessAccountId();

            try {
                if (pollOne(creatorProfileId, igBusinessAccountId)) {
                    polled++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                // Defensive catch-all so one creator's unexpected failure never aborts the batch.
                failed++;
                log.error(
                        "AudienceDemographicsJob: unexpected failure polling creator {}",
                        creatorProfileId,
                        e);
            }
        }

        auditLog.recordToolCall(
                null,
                "AUDIENCE_DEMOGRAPHICS_POLL_COMPLETED",
                "SYSTEM",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of(
                        "creatorsPolled", polled,
                        "creatorsFailed", failed,
                        "totalTokens", connectedTokens.size()));
    }

    /** @return true if a demographics snapshot was successfully written for this creator. */
    private boolean pollOne(String creatorProfileId, String igBusinessAccountId) {
        // CR-99/F-0113 fix: same ULID-vs-real-Meta-ID bug as MetricsPollingJob — Meta's Graph API
        // path segment must be the numeric IG Business Account ID, never our internal ULID.
        if (igBusinessAccountId == null || igBusinessAccountId.isBlank()) {
            log.warn(
                    "AudienceDemographicsJob: no igBusinessAccountId on file for creator {}, skipping",
                    creatorProfileId);
            return false;
        }

        // F-0166 follow-up (Priya review): this called the WORKSPACE-scoped getValidToken with
        // `workspaceId`, always null for a creator-owned row — the backing repository query has
        // an explicit `workspaceId IS NOT NULL` predicate (CR-111), so it could never match.
        Optional<String> token = tokenStorage.getValidCreatorToken(creatorProfileId);
        if (token.isEmpty()) {
            log.warn(
                    "AudienceDemographicsJob: no valid token for creator {}, skipping", creatorProfileId);
            return false;
        }

        // F-0126: keyed on creatorProfileId (internal ULID) while MetaGraphApiClient's own
        // enforcement and usage-header parsing key on igBusinessAccountId — two disjoint key
        // namespaces for the same account meant this pre-flight guard could never see usage the
        // client itself had recorded, so it always read 0 and was decorative.
        int usage = rateLimitTracker.getCurrentUsage(igBusinessAccountId);
        if (usage >= RATE_LIMIT_THRESHOLD_PERCENT) {
            log.warn(
                    "AudienceDemographicsJob: creator {} at {}% Meta rate-limit usage, deferring to next"
                            + " cycle",
                    creatorProfileId,
                    usage);
            return false;
        }

        try {
            AudienceDemographicsResponse response =
                    instagramClient.getAudienceDemographics(
                            igBusinessAccountId,
                            token.get(),
                            // T-IGLOGIN-0820: route to the host the token belongs to. Defaulting
                            // to FACEBOOK_LOGIN is safe only because getCreatorAuthPath is empty
                            // exactly when there is no usable token, which this method already
                            // returned on above.
                            tokenStorage
                                    .getCreatorAuthPath(creatorProfileId)
                                    .orElse(MetaAuthPath.FACEBOOK_LOGIN));

            if (response == null || response.data() == null || response.data().isEmpty()) {
                // Accounts under Meta's 100+ follower threshold (or with no audience data yet) return
                // an empty payload — logged and skipped, never persisted as a fabricated empty row.
                log.warn(
                        "AudienceDemographicsJob: empty audience-demographics response for creator {}"
                                + " (likely below Meta's 100+ follower threshold), skipping",
                        creatorProfileId);
                return false;
            }

            Map<String, Long> ageGender = extractBreakdown(response, BREAKDOWN_GENDER_AGE);
            Map<String, Long> country = extractBreakdown(response, BREAKDOWN_COUNTRY);
            Map<String, Long> city = extractBreakdown(response, BREAKDOWN_CITY);
            Map<String, Long> locale = extractBreakdown(response, BREAKDOWN_LOCALE);

            if (ageGender.isEmpty() && country.isEmpty() && city.isEmpty() && locale.isEmpty()) {
                log.warn(
                        "AudienceDemographicsJob: no recognized breakdowns in response for creator {},"
                                + " skipping",
                        creatorProfileId);
                return false;
            }

            AudienceDemographics snapshot =
                    AudienceDemographics.builder()
                            .id(Ulids.newUlid())
                            .time(Instant.now())
                            .creatorProfileId(creatorProfileId)
                            .platform(PLATFORM_INSTAGRAM)
                            .ageGenderBreakdownJson(JsonLists.toJsonObject(nullIfEmpty(ageGender)))
                            .countryBreakdownJson(JsonLists.toJsonObject(nullIfEmpty(country)))
                            .cityBreakdownJson(JsonLists.toJsonObject(nullIfEmpty(city)))
                            .localeBreakdownJson(JsonLists.toJsonObject(nullIfEmpty(locale)))
                            .dataSource(DATA_SOURCE_META_API)
                            .fetchedAt(Instant.now())
                            .build();

            demographicsRepository.save(snapshot);
            return true;
        } catch (MetaRateLimitException e) {
            // F-0126: markLimited must use the same key as getCurrentUsage() above (and the same
            // key MetaGraphApiClient itself uses) — igBusinessAccountId, not creatorProfileId.
            rateLimitTracker.markLimited(igBusinessAccountId);
            log.warn(
                    "AudienceDemographicsJob: rate limited for creator {}, will retry next cycle",
                    creatorProfileId);
            return false;
        } catch (MetaTokenExpiredException e) {
            log.warn(
                    "AudienceDemographicsJob: token expired for creator {}, needs re-auth",
                    creatorProfileId);
            return false;
        } catch (MetaApiException e) {
            log.error(
                    "AudienceDemographicsJob: Meta API error polling creator {}: {}",
                    creatorProfileId,
                    e.getMessage());
            return false;
        }
    }

    /**
     * Extracts the single value-map for a named breakdown (e.g. {@code audience_country}) from
     * Meta's response, merging across any values entries present (the API returns at most one
     * value per breakdown for {@code period=lifetime}, but this defensively merges rather than
     * assuming exactly one). Returns an empty map, never null, if the breakdown is absent.
     */
    private Map<String, Long> extractBreakdown(AudienceDemographicsResponse response, String name) {
        Map<String, Long> merged = new HashMap<>();
        for (AudienceDemographicsResponse.DemographicBreakdown breakdown : response.data()) {
            if (breakdown == null || !name.equals(breakdown.name()) || breakdown.values() == null) {
                continue;
            }
            for (AudienceDemographicsResponse.DemographicValue value : breakdown.values()) {
                if (value != null && value.value() != null) {
                    merged.putAll(value.value());
                }
            }
        }
        return merged;
    }

    private static Map<String, Long> nullIfEmpty(Map<String, Long> map) {
        return (map == null || map.isEmpty()) ? null : map;
    }
}
