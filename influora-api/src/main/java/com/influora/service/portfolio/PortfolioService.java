package com.influora.service.portfolio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.LimitedInputStream;
import com.influora.common.MediaMimeSniffer;
import com.influora.common.Ulids;
import com.influora.common.UsernameUtils;
import com.influora.config.R2Properties;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.entity.PortfolioEvent;
import com.influora.domain.entity.Review;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.domain.enums.PortfolioEventType;
import com.influora.domain.enums.ReviewerType;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.InstagramUserResponse;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.storage.R2StorageService;
import com.influora.service.security.AbuseThrottleService;
import com.influora.service.security.MalwareScanService;
import com.influora.service.notification.event.PortfolioContactEvent;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.PortfolioEventRepository;
import com.influora.repository.ReviewRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorContextService;
import com.influora.service.CreatorProfileService;
import com.influora.service.ExternalCreatorLinkService;
import com.influora.web.dto.creator.CreatorDtos.PlatformStatResponse;
import com.influora.web.dto.portfolio.PortfolioDtos.PlatformDeclarationRequest;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioAnalyticsResponse;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioCollab;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioContactResponse;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioCustomLink;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPageResponse;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPatchRequest;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPinnedPost;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioRateRow;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioStats;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioVisibility;
import com.influora.web.dto.portfolio.PortfolioDtos.SyncPlatformsResponse;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);
    /**
     * F-0498 — {@code withExactBigDecimals(true)} because plain {@code JsonNodeFactory}'s {@code
     * numberNode(BigDecimal)} calls {@code stripTrailingZeros()} on the way into a tree node
     * (JsonNode-databind's long-standing surprise: a scale-0 5000 becomes 5E+3, still numerically
     * equal but a different scale/representation once read back). Only surfaces once a BigDecimal
     * round-trips through {@link #loadRateCard}/{@link #writeSettings(PortfolioSettings, List)}'s
     * tree-node merge — nothing else on this MAPPER carries a BigDecimal through tree nodes.
     */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    /** Spec 12 §5.1 image cap (10 MB) — same as deliverable proof screenshots. */
    private static final long MAX_COVER_BYTES = 10_485_760L;
    /**
     * F-0498 — no cap existed before, so a client could persist an unbounded array into
     * {@code portfolio_settings_json}. {@link DeliverableType} is the platform's own catalog of
     * deliverable formats (INSTAGRAM_POST..TIKTOK_VIDEO, 9 values); a legitimate rate card has at
     * most one row per format, so this mirrors that existing catalog instead of picking an
     * arbitrary number.
     */
    private static final int MAX_RATE_CARD_ROWS = DeliverableType.values().length;

    /**
     * F-0665/F-0434 — the exact 4 values {@code creator-portfolio-editor.tsx}'s "Past collabs —
     * what shows on your page" Select offers (mirrored in the frontend's {@code
     * PortfolioCollab.displayMode} union in {@code src/lib/api.ts}). A PATCH naming any other value
     * is rejected outright ({@link #validateCollabDisplayModes}) rather than silently defaulted —
     * same discipline {@link #validateRateCard} already applies to rate-card rows.
     */
    private static final Set<String> ALLOWED_COLLAB_DISPLAY_MODES =
            Set.of("logo", "name_only", "category", "hidden");

    /** F-0665/F-0434 — what a collab with no stored preference (or a pre-fix profile) shows. */
    private static final String DEFAULT_COLLAB_DISPLAY_MODE = "logo";

    /**
     * F-0694 — exactly the four values the brand's Discover platform chips offer (the {@code
     * platforms} array in {@code creator-discovery.tsx}). Declaring anything outside this set could
     * never be filtered on, so accepting it would only write a row nothing reads.
     */
    private static final Set<String> SELF_DECLARABLE_PLATFORMS =
            Set.of("INSTAGRAM", "YOUTUBE", "TIKTOK", "TWITTER");

    /**
     * F-0694 — the intersection of what Instagram, YouTube, TikTok and X allow, kept deliberately
     * narrow because this string is persisted and rendered on a brand-facing card. Mirrors the
     * shape {@code ExternalCreatorService.USERNAME_PATTERN} already enforces on the lookup path.
     */
    private static final Pattern SELF_DECLARED_HANDLE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,60}$");

    /** F-0694 — above any real account; a larger number is a typo or a test, not a creator. */
    private static final long MAX_SELF_DECLARED_FOLLOWERS = 1_000_000_000L;

    private final CreatorContextService creatorContext;
    private final CreatorProfileService creatorProfileService;
    private final CreatorProfileRepository creatorProfileRepository;
    private final PlatformStatRepository platformStatRepository;
    private final CollaborationRepository collaborationRepository;
    private final CampaignRepository campaignRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AudienceDemographicsRepository audienceDemographicsRepository;
    private final ReviewRepository reviewRepository;
    private final R2StorageService r2StorageService;
    private final R2Properties r2Properties;
    private final MalwareScanService malwareScanService;
    private final ApplicationEventPublisher eventPublisher;
    private final UserRepository userRepository;
    private final DeliverableRepository deliverableRepository;
    private final PortfolioEventRepository portfolioEventRepository;
    private final MetaOAuthTokenRepository metaOAuthTokenRepository;
    private final MetaTokenStorage metaTokenStorage;
    private final InstagramInsightsClient instagramInsightsClient;
    private final CreatorMetricsRepository creatorMetricsRepository;
    private final ExternalCreatorLinkService externalCreatorLinkService;
    private final AbuseThrottleService abuseThrottleService;

    /** Trailing window for the "Page views (30d)" analytics number and its period-over-period delta. */
    private static final Duration ANALYTICS_WINDOW = Duration.ofDays(30);

    /**
     * T-FESTIVALBOX-0905 phase 9 [Kabir F-3] — {@link #contact} was an unauthenticated mail-injection
     * amplifier: no rate limit, no honeypot, no IP tracking, not even a persisted row, and every
     * accepted POST emails a real creator with attacker-controlled name/reply-to/body. The edge
     * {@code AuthRateLimitFilter} "portfolio-contact" bucket (per source IP) is one layer; this is
     * the second, keyed by the RECIPIENT creator rather than the sender's IP, specifically so a
     * caller that rotates IP between requests (defeating the edge bucket entirely) is still bounded
     * — no matter how many origins an attacker sources from, one creator's inbox cannot be flooded
     * past this cap in one window. Reuses {@link AbuseThrottleService} (the same atomic upsert
     * counter {@code FestivalEnquiryService#enforceThrottle} uses) rather than inventing a second
     * throttle mechanism.
     *
     * <p>No honeypot is added here (unlike {@code FestivalEnquiryService}): this is a pre-existing
     * public API with existing callers, and a new required field would break them. Throttling alone
     * is the fix for this endpoint.
     */
    private static final long MAX_CONTACT_PER_CREATOR_PER_WINDOW = 20;

    /** Same fixed-hour bucket shape as {@code FestivalEnquiryService#THROTTLE_WINDOW}. */
    private static final Duration CONTACT_THROTTLE_WINDOW = Duration.ofHours(1);

    public PortfolioService(
            CreatorContextService creatorContext,
            CreatorProfileService creatorProfileService,
            CreatorProfileRepository creatorProfileRepository,
            PlatformStatRepository platformStatRepository,
            CollaborationRepository collaborationRepository,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository,
            AudienceDemographicsRepository audienceDemographicsRepository,
            ReviewRepository reviewRepository,
            R2StorageService r2StorageService,
            R2Properties r2Properties,
            MalwareScanService malwareScanService,
            ApplicationEventPublisher eventPublisher,
            UserRepository userRepository,
            DeliverableRepository deliverableRepository,
            PortfolioEventRepository portfolioEventRepository,
            MetaOAuthTokenRepository metaOAuthTokenRepository,
            MetaTokenStorage metaTokenStorage,
            InstagramInsightsClient instagramInsightsClient,
            CreatorMetricsRepository creatorMetricsRepository,
            ExternalCreatorLinkService externalCreatorLinkService,
            AbuseThrottleService abuseThrottleService) {
        this.creatorContext = creatorContext;
        this.creatorProfileService = creatorProfileService;
        this.creatorProfileRepository = creatorProfileRepository;
        this.platformStatRepository = platformStatRepository;
        this.collaborationRepository = collaborationRepository;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
        this.audienceDemographicsRepository = audienceDemographicsRepository;
        this.reviewRepository = reviewRepository;
        this.r2StorageService = r2StorageService;
        this.r2Properties = r2Properties;
        this.malwareScanService = malwareScanService;
        this.eventPublisher = eventPublisher;
        this.userRepository = userRepository;
        this.deliverableRepository = deliverableRepository;
        this.portfolioEventRepository = portfolioEventRepository;
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
        this.metaTokenStorage = metaTokenStorage;
        this.instagramInsightsClient = instagramInsightsClient;
        this.creatorMetricsRepository = creatorMetricsRepository;
        this.externalCreatorLinkService = externalCreatorLinkService;
        this.abuseThrottleService = abuseThrottleService;
    }

    @Transactional(readOnly = true)
    public PortfolioPageResponse getPublic(String username) {
        CreatorProfile profile = creatorProfileService.requireProfileByUsername(username);
        requireDiscoverablePortfolio(profile);
        return assemble(profile, true);
    }

    /**
     * Records one public view of {@code username}'s portfolio (Priya CTO ruling 2026-07-18). Its
     * OWN writable transaction, separate from the read-only {@link #getPublic} that serves the page,
     * so the view write is never entangled with page assembly. The controller calls this off the
     * response path and swallows any failure — a view-counter write must NEVER turn a good page into
     * a 500. Silently no-ops for a non-discoverable profile (nothing public was actually served).
     * v1 records raw views (no dedup); {@code visitorHash} stays null until the unique-visitor pass.
     */
    @Transactional
    public void recordPublicView(String username) {
        CreatorProfile profile = creatorProfileService.requireProfileByUsername(username);
        if (!profile.isDiscoverable()) {
            return;
        }
        portfolioEventRepository.save(
                PortfolioEvent.builder()
                        .id(Ulids.newUlid())
                        .creatorProfileId(profile.getId())
                        .eventType(PortfolioEventType.VIEW)
                        .occurredAt(Instant.now())
                        .build());
    }

    /**
     * NOT read-only: {@link CreatorProfileService#ensureUsername} may lazily persist an
     * auto-generated handle for profiles that never went through "claim your handle" — same fix
     * as {@code CreatorProfileService#getMyProfile} (2026-07-23 P-1). Without this, {@code
     * resolveUsername} below fell back to a display-only slug that was never saved, so the
     * portfolio editor's "your public page" link pointed at a handle no profile was actually
     * registered under.
     */
    @Transactional
    public PortfolioPageResponse getMine(AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        creatorProfileService.ensureUsername(profile);
        return assemble(profile, false);
    }

    @Transactional
    public PortfolioPageResponse updateMine(AuthPrincipal principal, PortfolioPatchRequest patch) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);

        if (patch.username() != null && !patch.username().isBlank()) {
            creatorProfileService.applyUsername(profile, patch.username().trim());
        }

        validateRateCard(patch.rateCard());
        validateCollabDisplayModes(patch.collabs());

        profile.applySelfEdit(
                patch.displayName(),
                patch.bio(),
                patch.avatarUrl(),
                patch.coverUrl(),
                patch.city(),
                patch.niches() != null ? JsonLists.toJson(patch.niches()) : null,
                patch.languages() != null ? JsonLists.toJson(patch.languages()) : null,
                null,
                extractRateMin(patch.rateCard()),
                extractRateMax(patch.rateCard()),
                null);

        PortfolioSettings settings = loadSettings(profile);
        if (patch.visibility() != null) {
            settings.setVisibility(patch.visibility());
        }
        if (patch.customLinks() != null) {
            settings.setCustomLinks(patch.customLinks());
        }
        if (patch.pinnedPosts() != null) {
            settings.setPinnedPosts(patch.pinnedPosts());
        }
        // F-0498 — persist the real per-row rate card (id/label/min/max/currency for every row
        // the client sent) into the portfolio_settings_json blob instead of collapsing it down to
        // the single rateMin/rateMax pair above; see buildRateCard for the read-back side. Reads
        // the CURRENT stored rows first (before the applyPortfolioSettingsJson below overwrites
        // them) so an update that doesn't touch the rate card — e.g. just editing the bio — never
        // wipes it, matching how every other settings.set*() call above only fires when patch
        // actually supplied that field.
        List<PortfolioRateRow> effectiveRateCard =
                patch.rateCard() != null ? patch.rateCard() : loadRateCard(profile);
        // F-0665/F-0434 — same never-wipe-if-not-supplied discipline as effectiveRateCard above:
        // only recompute the id->displayMode map when this patch actually included a collabs
        // array, otherwise keep whatever was previously persisted.
        Map<String, String> effectiveCollabDisplayModes =
                patch.collabs() != null
                        ? extractCollabDisplayModes(patch.collabs())
                        : loadCollabDisplayModes(profile);
        profile.applyPortfolioSettingsJson(
                writeSettings(settings, effectiveRateCard, effectiveCollabDisplayModes));
        try {
            creatorProfileRepository.save(profile);
        } catch (DataIntegrityViolationException dup) {
            throw new ApiException(
                    "USERNAME_TAKEN", "This username is already taken", HttpStatus.CONFLICT);
        }
        return assemble(profile, false);
    }

    /**
     * CR-84 — was a no-op that only validated the profile existed and returned a fabricated
     * "synced" timestamp; the client showed a real-looking success confirmation for a call that
     * touched no data at all. Now does a genuine on-demand refresh using the same creator-owned
     * Meta OAuth pipeline {@code CreatorMetaOAuthService}/{@code MetricsPollingJob} already use:
     * looks up the creator's connected Instagram Business Account, calls the Graph API for a fresh
     * profile snapshot, records it as a real {@code creator_metrics} row, and immediately rolls it
     * into {@code platform_stats} (the same upsert {@link PlatformStatsAggregationJob} performs on
     * its daily schedule, just synchronously here instead of waiting for that batch run).
     *
     * <p>Throws (as an {@link ApiException}, surfaced to the client with a real reason) rather than
     * returning a fake success when there is nothing to sync from: no connected account
     * ({@code NOT_CONNECTED}), or an expired/revoked token ({@code TOKEN_EXPIRED}) that needs the
     * creator to reconnect via {@code CreatorMetaOAuthService}. A Meta API/rate-limit failure during
     * the fetch itself propagates as the {@link com.influora.integration.meta.exception.MetaApiException}
     * subclass {@link InstagramInsightsClient} already throws (each maps to its own honest HTTP
     * status) rather than being swallowed into a false "synced" response.
     */
    @Transactional
    public SyncPlatformsResponse syncPlatforms(AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);

        MetaOAuthToken tokenRow =
                metaOAuthTokenRepository
                        .findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(profile.getId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "NOT_CONNECTED",
                                                "Connect your Instagram account before syncing",
                                                HttpStatus.CONFLICT));
        String igBusinessAccountId = tokenRow.getIgBusinessAccountId();
        if (igBusinessAccountId == null || igBusinessAccountId.isBlank()) {
            throw new ApiException(
                    "NOT_CONNECTED",
                    "Your Instagram connection is incomplete — reconnect to sync",
                    HttpStatus.CONFLICT);
        }

        String accessToken =
                metaTokenStorage
                        .getValidCreatorToken(profile.getId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "TOKEN_EXPIRED",
                                                "Your Instagram connection expired — reconnect to sync",
                                                HttpStatus.CONFLICT));

        InstagramUserResponse igProfile =
                instagramInsightsClient.getProfile(igBusinessAccountId, accessToken);

        CreatorMetric metric =
                CreatorMetric.builder()
                        .id(Ulids.newUlid())
                        .time(Instant.now())
                        .creatorProfileId(profile.getId())
                        .platform("INSTAGRAM")
                        .username(igProfile.username())
                        .followers(igProfile.followersCount() == null ? 0L : igProfile.followersCount())
                        .following(igProfile.followsCount())
                        .mediaCount(
                                igProfile.mediaCount() == null ? null : igProfile.mediaCount().intValue())
                        .dataSource(CreatorMetric.DATA_SOURCE_META_API)
                        .fetchedAt(Instant.now())
                        .build();
        creatorMetricsRepository.save(metric);

        // Q5.3 (T-CREATORCONNECT-0902) — thread the already-resolved igBusinessAccountId through
        // so the JOINED hook can match on the exact id instead of falling back to the weaker
        // case-insensitive username branch.
        upsertPlatformStat(profile, "INSTAGRAM", metric, igBusinessAccountId);

        log.info("Portfolio platform sync completed for creator={}", profile.getId());
        return new SyncPlatformsResponse(Instant.now().toString());
    }

    /**
     * F-0694/F-0695 — the second door into {@code platform_stats}, for the creator who has no Meta
     * connection to walk through the first one.
     *
     * <p>A brand narrowing Discover to {@code platforms=INSTAGRAM} runs {@code
     * CreatorProfileSpecifications#hasPlatforms}, an {@code EXISTS} subquery over {@code
     * platform_stats}. Until this method existed, that table's only writers were {@link
     * #syncPlatforms} and {@code PlatformStatsAggregationJob}, both fed by a Meta {@code
     * CreatorMetric} — so the filter that reads as "creators on Instagram" actually meant "creators
     * who completed Meta OAuth", and everyone else was invisible to the single most obvious search
     * a brand performs.
     *
     * <p>Three things keep this honest rather than just permissive. The snapshot is written {@link
     * CreatorMetric#DATA_SOURCE_CREATOR_REPORTED}, so {@code CreatorMetric#isPlatformVerified} — the
     * only thing allowed to set {@code PlatformStat.verified} (CR-119) — returns false and the
     * brand-facing verified badge stays dark. A row a Meta sync already verified is never
     * overwritten, so a typed number can never downgrade measured data. And the {@code JOINED}
     * cross-link hook is skipped, because a typed handle is a claim of ownership, not proof of it.
     */
    @Transactional
    public SyncPlatformsResponse declarePlatform(AuthPrincipal principal, PlatformDeclarationRequest request) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);

        String platform = request == null || request.platform() == null ? "" : request.platform().trim().toUpperCase();
        if (!SELF_DECLARABLE_PLATFORMS.contains(platform)) {
            throw new ApiException(
                    "INVALID_PLATFORM",
                    "Choose one of " + String.join(", ", SELF_DECLARABLE_PLATFORMS),
                    HttpStatus.BAD_REQUEST);
        }

        String handle = request.handle() == null ? "" : request.handle().trim();
        if (handle.startsWith("@")) {
            handle = handle.substring(1);
        }
        if (!SELF_DECLARED_HANDLE_PATTERN.matcher(handle).matches()) {
            throw new ApiException(
                    "INVALID_HANDLE", "That doesn't look like a valid username", HttpStatus.BAD_REQUEST);
        }

        long followers = request.followers() == null ? 0L : request.followers();
        if (followers < 0 || followers > MAX_SELF_DECLARED_FOLLOWERS) {
            throw new ApiException(
                    "INVALID_FOLLOWERS", "Enter a follower count we can believe", HttpStatus.BAD_REQUEST);
        }

        // A Meta sync already proved this account. Refuse rather than silently keeping the better
        // data and returning 200 — the creator asked to change something and deserves to be told
        // it did not change, and why.
        Optional<PlatformStat> existing =
                platformStatRepository.findByCreatorProfileIdAndPlatform(profile.getId(), platform);
        if (existing.isPresent() && existing.get().isVerified()) {
            throw new ApiException(
                    "PLATFORM_ALREADY_VERIFIED",
                    "This account is already connected — its numbers come straight from the platform",
                    HttpStatus.CONFLICT);
        }

        CreatorMetric metric =
                CreatorMetric.builder()
                        .id(Ulids.newUlid())
                        .time(Instant.now())
                        .creatorProfileId(profile.getId())
                        .platform(platform)
                        .username(handle)
                        .followers(followers)
                        .dataSource(CreatorMetric.DATA_SOURCE_CREATOR_REPORTED)
                        .fetchedAt(Instant.now())
                        .build();
        creatorMetricsRepository.save(metric);

        upsertPlatformStat(profile, platform, metric, null, false);

        log.info(
                "Creator-reported platform declared for creator={} platform={} (unverified)",
                profile.getId(),
                platform);
        return new SyncPlatformsResponse(Instant.now().toString());
    }

    /**
     * Same upsert shape as {@code PlatformStatsAggregationJob#upsertPlatformStat} (not shared code
     * — that job's method is package-private to {@code com.influora.job} and carries its own
     * batch-run javadoc; duplicating the small upsert here keeps this on-demand sync independent of
     * that job's lifecycle) plus the {@code creator_profiles} denormalized-totals update that job
     * also performs, so a manual sync and the nightly aggregation leave the portfolio in the same
     * state.
     */
    private void upsertPlatformStat(
            CreatorProfile profile, String platform, CreatorMetric metric, String igAccountId) {
        upsertPlatformStat(profile, platform, metric, igAccountId, true);
    }

    /**
     * F-0694/F-0695 — {@code crossLink} exists only so {@link #declarePlatform} can reuse this
     * upsert without firing the {@code JOINED} hook at the bottom. A Meta sync proves the creator
     * owns the account it read; a typed handle proves nothing, and letting one mark an
     * admin-imported {@code external_creators} row as joined would let any creator claim any
     * Instagram account and inherit its brand-facing "Verified with Influora" state.
     */
    private void upsertPlatformStat(
            CreatorProfile profile,
            String platform,
            CreatorMetric metric,
            String igAccountId,
            boolean crossLink) {
        Optional<PlatformStat> existing =
                platformStatRepository.findByCreatorProfileIdAndPlatform(profile.getId(), platform);
        if (existing.isPresent()) {
            String handle = metric.getUsername() != null ? metric.getUsername() : existing.get().getHandle();
            // CR-119 — mirrors PlatformStatsAggregationJob#upsertPlatformStat: the verified flag
            // tracks THIS snapshot's provenance instead of being pinned to the row's prior value
            // (which nothing ever set true, leaving the brand-facing badge permanently dark).
            existing
                    .get()
                    .applySnapshot(
                            metric.getFollowers(),
                            metric.getAvgEngagementRate(),
                            metric.isPlatformVerified(),
                            handle);
            platformStatRepository.save(existing.get());
        } else {
            platformStatRepository.save(
                    PlatformStat.builder()
                            .id(Ulids.newUlid())
                            .creatorProfileId(profile.getId())
                            .platform(platform)
                            .handle(metric.getUsername())
                            .followers(metric.getFollowers())
                            .engagementRate(metric.getAvgEngagementRate())
                            // CR-119 — was a hardcoded `false`; see the update branch above.
                            .verified(metric.isPlatformVerified())
                            .build());
        }

        long totalFollowers =
                platformStatRepository.findByCreatorProfileId(profile.getId()).stream()
                        .mapToLong(PlatformStat::getFollowers)
                        .sum();
        profile.applyAggregatedStats(totalFollowers, metric.getAvgEngagementRate());
        creatorProfileRepository.save(profile);

        // T-CREATORCONNECT-0902 — the JOINED hook. A real Instagram handle from a Meta sync, not
        // the auto-generated Influora-username fallback. Q5.3: pass the igAccountId already
        // resolved by the caller (syncPlatforms) so the hook takes the exact ig_account_id match
        // branch instead of the weaker case-insensitive username fallback — igAccountId is null
        // only for callers of this method that never had one (there are none left after Q5.3, but
        // the parameter stays honest rather than silently required).
        // Q5.5 — try/catch at the call site, matching MetaTokenStorage#storeCreatorToken: REQUIRES_NEW
        // on onCreatorIdentified isolates the DATABASE transaction, but a deferred-flush failure
        // inside it still re-surfaces as an exception from THIS call when its own commit fails,
        // even though the hook's internal try/catch already logged it. Without this, that would
        // still fail the surrounding syncPlatforms() request (and its own already-good writes to
        // platform_stats/creator_profiles above) over a best-effort cross-link.
        if (crossLink && "INSTAGRAM".equals(platform)) {
            try {
                externalCreatorLinkService.onCreatorIdentified(profile.getId(), metric.getUsername(), igAccountId);
            } catch (RuntimeException e) {
                log.error(
                        "upsertPlatformStat: JOINED hook failed for creatorProfileId={} (swallowed —"
                                + " the platform sync above already succeeded and is unaffected)",
                        profile.getId(),
                        e);
            }
        }
    }

    @Transactional(readOnly = true)
    public PortfolioAnalyticsResponse analytics(AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);

        // Calculate real portfolio metrics from existing data.
        // Link clicks tracking is still not implemented (requires per-custom-link counters).

        // collaborations.creator_id is an FK to users.id (V6), NOT creator_profiles.id — scope
        // these counts by the profile's userId or they silently return 0 in production, since
        // CreatorProfile.id and User.id are different ULIDs (Priya §6h; see the javadoc on
        // CollaborationRepository#countByCreatorIdAndStatus / #countByCreatorId).
        long completedCollabs = collaborationRepository.countByCreatorIdAndStatus(
                profile.getUserId(), CollaborationStatus.COMPLETED);

        long totalInquiries = collaborationRepository.countByCreatorId(profile.getUserId());

        // Real media-kit download count (portfolio_events, type MEDIA_KIT_DOWNLOAD). Returns 0
        // until the media-kit PDF download endpoint exists to record them — but it is now a real
        // query, not a hardcoded literal, so it becomes live the moment that endpoint ships.
        long mediaKitDownloads =
                portfolioEventRepository.countByCreatorProfileIdAndEventType(
                        profile.getId(), PortfolioEventType.MEDIA_KIT_DOWNLOAD);

        // CR-71 — profile clicks from platform stats (total followers as proxy metric). No
        // real click-tracking event exists for this metric (unlike pageViews/mediaKitDownloads
        // below, which are real portfolio_events counts) — this is, and stays, an estimate.
        // profileClicksEstimated=true on the response so the client labels it honestly rather
        // than presenting a proxy number as a measurement.
        long profileClicks = profile.getTotalFollowers() > 0 ?
                profile.getTotalFollowers() / 100 : 0; // Rough estimate

        // Real page views from portfolio_events (type VIEW), keyed by creator_profiles.id (the same
        // id recordPublicView writes) — NOT userId like the collaboration counts above.
        var pageViews = computePageViews(profile.getId());

        return new PortfolioAnalyticsResponse(
                pageViews,
                profileClicks,
                true, // profileClicksEstimated — see CR-71 note above
                List.of(), // Link clicks require custom link tracking (not implemented)
                totalInquiries,
                mediaKitDownloads
        );
    }

    /**
     * Real "Page views (30d)" plus a period-over-period delta, from {@code portfolio_events} (VIEW).
     * {@code last30Days} = views in the trailing 30 days; {@code deltaPercent} compares that to the
     * preceding 30 days. Delta is 0 when the prior window had no views (no baseline to grow from —
     * avoids a divide-by-zero and a misleading "+100%" on a creator's very first views).
     */
    private PortfolioAnalyticsResponse.PageViews computePageViews(String creatorProfileId) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(ANALYTICS_WINDOW);
        Instant priorStart = windowStart.minus(ANALYTICS_WINDOW);

        long last30Days =
                portfolioEventRepository.countByCreatorProfileIdAndEventTypeAndOccurredAtAfter(
                        creatorProfileId, PortfolioEventType.VIEW, windowStart);
        long prior30Days =
                portfolioEventRepository.countByCreatorProfileIdAndEventTypeAndOccurredAtBetween(
                        creatorProfileId, PortfolioEventType.VIEW, priorStart, windowStart);

        int deltaPercent =
                prior30Days == 0
                        ? 0
                        : (int) Math.round(((last30Days - prior30Days) * 100.0) / prior30Days);
        return new PortfolioAnalyticsResponse.PageViews(last30Days, deltaPercent);
    }

    /**
     * M-K6-C3-4 — align with #40 deliverable path: magic-byte sniff, stream-to-R2 (no {@code
     * getBytes()}), persist object key (not public URL), return time-limited {@code presignGet}.
     */
    @Transactional
    public String uploadCover(AuthPrincipal principal, MultipartFile file) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        if (file == null || file.isEmpty()) {
            throw new ApiException("INVALID_FILE", "Cover image file is required", HttpStatus.BAD_REQUEST);
        }
        if (!r2StorageService.isAvailable()) {
            throw new ApiException(
                    "STORAGE_UNAVAILABLE",
                    "File storage is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (file.getSize() > MAX_COVER_BYTES) {
            throw new ApiException(
                    "FILE_TOO_LARGE",
                    "Cover image exceeds maximum size of " + MAX_COVER_BYTES + " bytes",
                    HttpStatus.BAD_REQUEST);
        }

        validateCoverImageMime(file);
        malwareScanService.requireClean(file, "portfolio-cover");

        String contentType =
                file.getContentType() != null ? file.getContentType() : "image/jpeg";
        String key = "creators/" + profile.getId() + "/cover/" + Ulids.newUlid();
        streamCoverToR2(key, file, contentType);

        // Persist R2 object key only — API responses rematerialize via resolveCoverUrl / presignGet.
        profile.applyCoverImageUrl(key);
        creatorProfileRepository.save(profile);
        return resolveCoverUrl(key);
    }

    @Transactional
    public PortfolioContactResponse contact(String username, String name, String email, String message) {
        CreatorProfile profile = creatorProfileService.requireProfileByUsername(username);
        requireDiscoverablePortfolio(profile);

        // Validate inputs
        if (name == null || name.isBlank()) {
            throw new ApiException("INVALID_NAME", "Name is required", HttpStatus.BAD_REQUEST);
        }
        if (email == null || !email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new ApiException("INVALID_EMAIL", "Valid email is required", HttpStatus.BAD_REQUEST);
        }
        if (message == null || message.isBlank()) {
            throw new ApiException("INVALID_MESSAGE", "Message is required", HttpStatus.BAD_REQUEST);
        }
        if (message.length() > 2000) {
            throw new ApiException("MESSAGE_TOO_LONG", "Message must be under 2000 characters", HttpStatus.BAD_REQUEST);
        }

        // T-FESTIVALBOX-0905 phase 9 [Kabir F-3] — see MAX_CONTACT_PER_CREATOR_PER_WINDOW's javadoc.
        // Keyed by the RECIPIENT creator, not the sender's IP: the edge AuthRateLimitFilter
        // "portfolio-contact" bucket already bounds one IP, so this bounds one creator's inbox
        // regardless of how many IPs an attacker sends from. Runs AFTER field validation (an
        // obviously-malformed request should not consume a real creator's budget) but BEFORE the
        // event is published, same "validated attempts consume budget" discipline as
        // FestivalEnquiryService#enforceThrottle.
        if (!abuseThrottleService.tryConsume(
                "portfolio-contact:" + profile.getId(),
                CONTACT_THROTTLE_WINDOW,
                MAX_CONTACT_PER_CREATOR_PER_WINDOW)) {
            log.warn("Portfolio contact throttled: per-creator cap reached for profile={}", profile.getId());
            throw new ApiException(
                    "TOO_MANY_REQUESTS",
                    "This creator has received several messages recently. Please try again later.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }

        // W3-1 — publish through the event bus like every other domain event instead of calling
        // NotificationService directly. Previously this constructed the event record purely as
        // metadata to hand to NotificationService.notify() and never actually published it — the
        // event class existed and was never published anywhere (one of the "6 unhandled events"
        // this task closes), and this call bypassed the async/AFTER_COMMIT notification pipeline
        // every other domain event goes through.
        PortfolioContactEvent event =
                new PortfolioContactEvent(
                        profile.getUserId(),
                        null, // No workspace context for portfolio contacts
                        Ulids.newUlid(), // Entity ID for idempotency
                        name,
                        email,
                        message);
        eventPublisher.publishEvent(event);

        // T-FESTIVALBOX-0905 phase 9 [Kabir F-3] — this used to log the sender's email address
        // (`from sender={}`, email). That is PII in an application log, exactly what
        // FestivalEnquiryService deliberately avoids (see its submit() javadoc) — an application
        // log is not access-controlled the way the eventual notification / admin record is.
        log.info("Portfolio contact delivered to creator={}", profile.getId());
        return new PortfolioContactResponse(true);
    }

    private void requireDiscoverablePortfolio(CreatorProfile profile) {
        if (!profile.isDiscoverable()) {
            throw new ApiException(
                    "PORTFOLIO_NOT_FOUND", "Portfolio not found", HttpStatus.NOT_FOUND);
        }
    }

    private PortfolioPageResponse assemble(CreatorProfile profile, boolean publicView) {
        List<PlatformStat> platformStats = platformStatRepository.findByCreatorProfileId(profile.getId());
        PortfolioSettings settings = loadSettings(profile);
        List<Collaboration> completed =
                collaborationRepository.findByCreatorIdAndStatus(
                        profile.getUserId(), CollaborationStatus.COMPLETED);
        Map<String, Review> brandReviewsByCollab = loadBrandReviewsByCollab(profile.getUserId());
        Map<String, String> collabDisplayModes = loadCollabDisplayModes(profile);

        PortfolioStats stats = computeStats(completed, brandReviewsByCollab);
        List<PortfolioCollab> collabs =
                buildCollabs(completed, publicView, settings, brandReviewsByCollab, collabDisplayModes);
        List<String> badges = computeBadges(profile, platformStats, stats);
        List<String> topCities = loadTopAudienceCities(profile.getId());

        String username = resolveUsername(profile);
        return new PortfolioPageResponse(
                username,
                profile.getDisplayName(),
                profile.getBio() != null ? profile.getBio() : "",
                profile.getCity(),
                JsonLists.stringListFromJson(profile.getCategoriesJson()),
                profile.getAvatarUrl(),
                resolveCoverUrl(profile.getCoverImageUrl()),
                profile.isVerified(),
                stats,
                publicView && !settings.getVisibility().trustBar() ? List.of() : badges,
                platformStats.stream().map(this::toPlatform).toList(),
                publicView && !settings.getVisibility().pastCollabs() ? List.of() : collabs,
                publicView && !settings.getVisibility().contentPortfolio()
                        ? List.of()
                        : settings.getPinnedPosts(),
                publicView && !settings.getVisibility().customLinks()
                        ? List.of()
                        : settings.getCustomLinks(),
                buildRateCard(profile, settings, publicView),
                JsonLists.stringListFromJson(profile.getLanguagesJson()),
                topCities,
                settings.getVisibility());
    }

    /** Brand→creator reviews keyed by collaboration id (A-GA-6 / ReviewRepository). */
    private Map<String, Review> loadBrandReviewsByCollab(String creatorUserId) {
        Map<String, Review> byCollab = new HashMap<>();
        for (Review review :
                reviewRepository.findReceivedByCreatorUserId(creatorUserId, ReviewerType.BRAND)) {
            byCollab.putIfAbsent(review.getCollaborationId(), review);
        }
        return byCollab;
    }

    private List<PortfolioCollab> buildCollabs(
            List<Collaboration> completed,
            boolean publicView,
            PortfolioSettings settings,
            Map<String, Review> brandReviewsByCollab,
            Map<String, String> collabDisplayModes) {
        if (publicView && !settings.getVisibility().pastCollabs()) {
            return List.of();
        }
        List<PortfolioCollab> out = new ArrayList<>();
        for (Collaboration collab : completed.stream().limit(12).toList()) {
            Campaign campaign =
                    campaignRepository.findById(collab.getCampaignId()).orElse(null);
            if (campaign == null) {
                continue;
            }
            // F-0674 (privacy-leak-client-side-only) — server-side enforcement of the "Past
            // collabs — what shows on your page" choice, mirroring the pattern
            // getVisiblePinnedPosts already uses for section-level visibility. The public,
            // unauthenticated GET /portfolio/{username} path (publicView=true) previously
            // returned every collab's real brandName regardless of displayMode — the browser
            // (creator-portfolio-public.tsx) filtered "hidden" and swapped in a label for
            // "category" purely cosmetically, so an unauthenticated curl of the endpoint still
            // exposed the brand names a creator had explicitly chosen to hide or anonymise.
            // getMine (publicView=false) is UNCHANGED: the creator must still see and edit their
            // own real collab names, including ones they've marked hidden.
            String displayMode =
                    collabDisplayModes.getOrDefault(collab.getId(), DEFAULT_COLLAB_DISPLAY_MODE);
            if (publicView && "hidden".equals(displayMode)) {
                continue;
            }
            Workspace workspace =
                    workspaceRepository.findById(campaign.getWorkspaceId()).orElse(null);
            Review brandReview = brandReviewsByCollab.get(collab.getId());
            Double rating = brandReview != null ? (double) brandReview.getStars() : null;
            String quote = brandReview != null ? brandReview.getReviewText() : null;
            boolean anonymize = publicView && "category".equals(displayMode);
            out.add(
                    new PortfolioCollab(
                            collab.getId(),
                            // brandId/brandLogoUrl are just as identifying as brandName (a real
                            // logo image or an id a client could look up elsewhere) — anonymising
                            // only the name string while leaving those intact would still leak
                            // the brand's identity, so all three are withheld together.
                            anonymize ? null : campaign.getWorkspaceId(),
                            anonymize
                                    ? anonymizedBrandLabel(workspace)
                                    : (workspace != null ? workspace.getName() : "Brand"),
                            anonymize ? null : (workspace != null ? workspace.getLogoUrl() : null),
                            campaign.getTitle(),
                            "Campaign collaboration",
                            "INSTAGRAM",
                            collab.getCreatedAt().toString(),
                            rating,
                            quote,
                            // F-0665/F-0434 — was hardcoded "logo" regardless of what the creator
                            // chose; now the creator's own persisted preference for THIS collab id,
                            // falling back to the default only when none was ever set.
                            displayMode));
        }
        return out;
    }

    /**
     * F-0674 — the public payload's substitute for a real brand name on a "category" collab.
     * Derived from the workspace's own {@code industry} (e.g. "Beauty Brand") so the
     * anonymisation stays informative without naming the actual brand; falls back to a bare
     * "Brand" when the workspace lookup failed or has no industry recorded.
     */
    private static String anonymizedBrandLabel(Workspace workspace) {
        String industry = workspace != null ? workspace.getIndustry() : null;
        return (industry != null && !industry.isBlank()) ? industry + " Brand" : "Brand";
    }

    private PortfolioStats computeStats(
            List<Collaboration> completed, Map<String, Review> brandReviewsByCollab) {
        int repeatBrands = countRepeatBrands(completed);
        double avgRating = 0;
        if (!brandReviewsByCollab.isEmpty()) {
            double sum =
                    brandReviewsByCollab.values().stream().mapToInt(Review::getStars).sum();
            avgRating =
                    BigDecimal.valueOf(sum / (double) brandReviewsByCollab.size())
                            .setScale(1, RoundingMode.HALF_UP)
                            .doubleValue();
        }
        return new PortfolioStats(completed.size(), avgRating, computeOnTimeRate(completed), repeatBrands);
    }

    /**
     * H-22 — real on-time-delivery rate, replacing the previous hardcoded {@code 95}. A completed
     * collaboration counts as "on time" if every one of its deliverables that actually has both a
     * {@code deadline} and a {@code submittedAt} was submitted on or before that deadline — a
     * deliverable with no deadline set, or one that was never actually submitted (can't evaluate
     * lateness at all), is not held against the creator. A collaboration with zero deliverable
     * rows is treated as on-time (nothing to be late on) rather than excluded, matching {@code
     * completed.size()} already being the stats denominator elsewhere in this method.
     */
    private int computeOnTimeRate(List<Collaboration> completed) {
        if (completed.isEmpty()) {
            return 0;
        }
        long onTimeCount = completed.stream().filter(this::isCollaborationOnTime).count();
        return (int) Math.round((onTimeCount * 100.0) / completed.size());
    }

    private boolean isCollaborationOnTime(Collaboration collaboration) {
        List<Deliverable> deliverables =
                deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(collaboration.getId());
        if (deliverables == null) {
            return true;
        }
        return deliverables.stream().allMatch(PortfolioService::isDeliverableOnTime);
    }

    private static boolean isDeliverableOnTime(Deliverable deliverable) {
        if (deliverable.getDeadline() == null || deliverable.getSubmittedAt() == null) {
            return true;
        }
        java.time.LocalDate submittedDate =
                deliverable.getSubmittedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return !submittedDate.isAfter(deliverable.getDeadline());
    }

    private int countRepeatBrands(List<Collaboration> completed) {
        Map<String, Long> counts =
                completed.stream()
                        .map(Collaboration::getCampaignId)
                        .map(id -> campaignRepository.findById(id).orElse(null))
                        .filter(c -> c != null)
                        .collect(Collectors.groupingBy(Campaign::getWorkspaceId, Collectors.counting()));
        return (int) counts.values().stream().filter(c -> c > 1).count();
    }

    private List<String> computeBadges(
            CreatorProfile profile, List<PlatformStat> platforms, PortfolioStats stats) {
        List<String> badges = new ArrayList<>();
        if (profile.getEngagementRate() != null
                && profile.getEngagementRate().compareTo(BigDecimal.valueOf(3)) >= 0) {
            badges.add("top_creator");
        }
        if (stats.onTimeRate() >= 90) {
            badges.add("on_time");
        }
        if (stats.repeatBrands() >= 3) {
            badges.add("brand_favorite");
        }
        if (!platforms.isEmpty()) {
            badges.add("fast_responder");
        }
        return badges;
    }

    /**
     * Rate-card visibility is enforced here, server-side. {@code brands_only} (the default) must
     * strip the numbers from the unauthenticated {@code GET /portfolio/{username}} payload, not
     * just hide them in the UI: the public page previously rendered a "sign in as a brand" card
     * while the values still sat in the JSON.
     */
    private List<PortfolioRateRow> buildRateCard(
            CreatorProfile profile, PortfolioSettings settings, boolean publicView) {
        String rateCardVisibility = settings.getVisibility().rateCard();
        if ("hidden".equals(rateCardVisibility)) {
            return List.of();
        }
        if (publicView && !"public".equals(rateCardVisibility)) {
            return List.of();
        }
        // F-0498 — real per-deliverable rows persisted via updateMine win over the fabricated
        // fallback below. The fallback stays for profiles whose only rate data is the
        // profile-level rateMin/rateMax pair (set via CreatorOnboardingService or
        // CreatorProfileService.patchMyProfile, never routed through this portfolio rate-card
        // editor), so their public page still shows pricing instead of going blank.
        List<PortfolioRateRow> storedRows = loadRateCard(profile);
        if (!storedRows.isEmpty()) {
            return storedRows;
        }
        List<PortfolioRateRow> rows = new ArrayList<>();
        if (profile.getRateMin() != null || profile.getRateMax() != null) {
            BigDecimal min = profile.getRateMin() != null ? profile.getRateMin() : profile.getRateMax();
            BigDecimal max = profile.getRateMax() != null ? profile.getRateMax() : profile.getRateMin();
            rows.add(new PortfolioRateRow("instagram_post", "Instagram Post", min, max, profile.getCurrency()));
            rows.add(new PortfolioRateRow("instagram_reel", "Instagram Reel", min, max, profile.getCurrency()));
        }
        return rows;
    }

    private List<String> loadTopAudienceCities(String creatorProfileId) {
        return audienceDemographicsRepository
                .findFirstByCreatorProfileIdOrderByTimeDesc(creatorProfileId)
                .map(
                        d -> {
                            Map<String, Long> cities =
                                    JsonLists.objectFromJson(d.getCityBreakdownJson(), Map.class);
                            if (cities == null || cities.isEmpty()) {
                                return List.<String>of();
                            }
                            return cities.entrySet().stream()
                                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                                    .limit(5)
                                    .map(Map.Entry::getKey)
                                    .toList();
                        })
                .orElse(List.of());
    }

    /**
     * {@code getMine} now persists a real username via {@link
     * CreatorProfileService#ensureUsername} before this ever runs, and {@code getPublic} only
     * reaches here after a successful username lookup — so the blank-username branch below is a
     * defensive fallback only, not the primary path (2026-07-23 P-1 fix).
     */
    private String resolveUsername(CreatorProfile profile) {
        if (profile.getUsername() != null && !profile.getUsername().isBlank()) {
            return profile.getUsername();
        }
        return UsernameUtils.normalize(profile.getDisplayName());
    }

    /**
     * M-2 (BrandF.md §87): public accessor for {@link CreatorMapper} (via
     * CreatorDiscoveryService) so the brand-facing creator profile can show real pinned posts
     * instead of the {@code Collections.emptyList()} stub {@code CreatorMapper.java:47} used to
     * hardcode. Respects the same {@code contentPortfolio} visibility flag {@link #assemble} gates
     * the public portfolio page on — a creator who's turned that section off shouldn't have it
     * leak into the brand discovery card just because a different caller reads it.
     */
    public List<PortfolioPinnedPost> getVisiblePinnedPosts(CreatorProfile profile) {
        PortfolioSettings settings = loadSettings(profile);
        return settings.getVisibility().contentPortfolio() ? settings.getPinnedPosts() : List.of();
    }

    private PortfolioSettings loadSettings(CreatorProfile profile) {
        if (profile.getPortfolioSettingsJson() == null || profile.getPortfolioSettingsJson().isBlank()) {
            return new PortfolioSettings();
        }
        try {
            return MAPPER.readValue(profile.getPortfolioSettingsJson(), PortfolioSettings.class);
        } catch (JsonProcessingException e) {
            return new PortfolioSettings();
        }
    }

    private String writeSettings(PortfolioSettings settings) {
        try {
            return MAPPER.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new ApiException(
                    "INVALID_PORTFOLIO_SETTINGS",
                    "Could not save portfolio settings",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * F-0498 — same as {@link #writeSettings(PortfolioSettings)}, plus the real per-row rate card
     * merged in as an extra top-level {@code "rateCard"} array. This stays a tree-level merge
     * rather than a new field on {@link PortfolioSettings} so the fix is scoped to this service
     * class; see {@link #loadRateCard} for the read-back side.
     *
     * <p>F-0665/F-0434 — same tree-level-merge convention now carries the collab display-mode
     * preferences too, as a second extra top-level {@code "collabDisplayModes"} object (collab id
     * -> display mode); see {@link #loadCollabDisplayModes} for the read-back side.
     */
    private String writeSettings(
            PortfolioSettings settings,
            List<PortfolioRateRow> rateCard,
            Map<String, String> collabDisplayModes) {
        try {
            ObjectNode node = (ObjectNode) MAPPER.valueToTree(settings);
            node.set("rateCard", MAPPER.valueToTree(rateCard != null ? rateCard : List.of()));
            node.set(
                    "collabDisplayModes",
                    MAPPER.valueToTree(collabDisplayModes != null ? collabDisplayModes : Map.of()));
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new ApiException(
                    "INVALID_PORTFOLIO_SETTINGS",
                    "Could not save portfolio settings",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * F-0498 — reads the real per-row rate card directly out of the {@code
     * portfolio_settings_json} blob's {@code "rateCard"} array. Not a field on {@link
     * PortfolioSettings} (kept untouched — this fix is scoped to PortfolioService.java); see
     * {@link #writeSettings(PortfolioSettings, List)} for the write side. Mirrors {@link
     * #loadSettings}'s fail-open behaviour: missing/blank/unparseable JSON, or no {@code
     * "rateCard"} key, all yield an empty list rather than throwing.
     */
    private List<PortfolioRateRow> loadRateCard(CreatorProfile profile) {
        String json = profile.getPortfolioSettingsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            // USE_BIG_DECIMAL_FOR_FLOATS — plain MAPPER.readTree parses a bare numeric literal like
            // 5000 through a double, which turned rateMin/rateMax into 5000.0 on read-back (caught
            // by this fix's own round-trip test). Scoped to this one reader so it doesn't change
            // behaviour for loadSettings' non-numeric fields elsewhere in this class.
            JsonNode root =
                    MAPPER.reader()
                            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                            .readTree(json);
            JsonNode rateCardNode = root.get("rateCard");
            if (rateCardNode == null || !rateCardNode.isArray()) {
                return List.of();
            }
            List<PortfolioRateRow> rows =
                    MAPPER.convertValue(
                            rateCardNode,
                            MAPPER.getTypeFactory()
                                    .constructCollectionType(List.class, PortfolioRateRow.class));
            return rows != null ? rows : List.of();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return List.of();
        }
    }

    /**
     * F-0665/F-0434 — reads the persisted collab id -> display-mode map out of the {@code
     * portfolio_settings_json} blob's {@code "collabDisplayModes"} object, same convention as
     * {@link #loadRateCard}'s {@code "rateCard"} array. Not a field on {@link PortfolioSettings}
     * (scoped to this service, like the rate card); see {@link #writeSettings(PortfolioSettings,
     * List, Map)} for the write side. Same fail-open behaviour: missing/blank/unparseable JSON, or
     * no {@code "collabDisplayModes"} key, all yield an empty map (every collab then falls back to
     * {@link #DEFAULT_COLLAB_DISPLAY_MODE} in {@link #buildCollabs}) rather than throwing.
     */
    private Map<String, String> loadCollabDisplayModes(CreatorProfile profile) {
        String json = profile.getPortfolioSettingsJson();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode modesNode = root.get("collabDisplayModes");
            if (modesNode == null || !modesNode.isObject()) {
                return Map.of();
            }
            Map<String, String> modes =
                    MAPPER.convertValue(
                            modesNode,
                            MAPPER.getTypeFactory()
                                    .constructMapType(HashMap.class, String.class, String.class));
            return modes != null ? modes : Map.of();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return Map.of();
        }
    }

    /**
     * F-0665/F-0434 — the write-side counterpart of {@link #loadCollabDisplayModes}: pulls
     * {@code (id, displayMode)} out of the client-sent {@link PortfolioCollab} rows. Only these two
     * fields are trusted — everything else on each row (brandName, campaignTitle, rating, ...) is
     * recomputed live from {@code Collaboration}/{@code Campaign}/{@code Workspace} in {@link
     * #buildCollabs} and is never read from the patch. A row with a blank id or a null
     * displayMode is skipped rather than persisted as noise; {@link #validateCollabDisplayModes}
     * has already rejected any row with a displayMode outside {@link
     * #ALLOWED_COLLAB_DISPLAY_MODES} by the time this runs.
     */
    private static Map<String, String> extractCollabDisplayModes(List<PortfolioCollab> collabs) {
        Map<String, String> modes = new HashMap<>();
        for (PortfolioCollab collab : collabs) {
            if (collab.id() != null && !collab.id().isBlank() && collab.displayMode() != null) {
                modes.put(collab.id(), collab.displayMode());
            }
        }
        return modes;
    }

    private PlatformStatResponse toPlatform(PlatformStat ps) {
        return new PlatformStatResponse(
                ps.getPlatform(),
                ps.getHandle() != null ? ps.getHandle() : "",
                ps.getFollowers(),
                ps.getEngagementRate(),
                ps.isVerified(),
                ps.getProfileUrl());
    }

    /**
     * F-0498 — the profile-level {@code rateMin} column feeds {@code CreatorDiscoveryService}'s
     * rate filter, so it still needs a single aggregate value even now that the full per-row rate
     * card is preserved (see {@link #buildRateCard}). Was {@code rateCard.get(0).min()}, which
     * silently discarded every row past the first; now the true floor across all rows.
     */
    private static BigDecimal extractRateMin(List<PortfolioRateRow> rateCard) {
        if (rateCard == null || rateCard.isEmpty()) {
            return null;
        }
        return rateCard.stream()
                .map(PortfolioRateRow::min)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }

    /** F-0498 — see {@link #extractRateMin}; the true ceiling across all rows. */
    private static BigDecimal extractRateMax(List<PortfolioRateRow> rateCard) {
        if (rateCard == null || rateCard.isEmpty()) {
            return null;
        }
        return rateCard.stream()
                .map(PortfolioRateRow::max)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    /**
     * F-0498 — same {@code INVALID_RATE_RANGE} check {@code CreatorOnboardingService#saveProfile}
     * and {@code CreatorProfileService#patchMyProfile} already enforce on the single profile-level
     * rateMin/rateMax pair (same code, message, and status), applied per row now that the
     * portfolio rate card persists real per-deliverable rows instead of one collapsed pair. Also
     * enforces {@link #MAX_RATE_CARD_ROWS}, which this PATCH path never capped before.
     */
    private static void validateRateCard(List<PortfolioRateRow> rateCard) {
        if (rateCard == null) {
            return;
        }
        if (rateCard.size() > MAX_RATE_CARD_ROWS) {
            throw new ApiException(
                    "RATE_CARD_TOO_LARGE",
                    "A portfolio rate card can have at most " + MAX_RATE_CARD_ROWS + " rows",
                    HttpStatus.BAD_REQUEST);
        }
        for (PortfolioRateRow row : rateCard) {
            if (row.min() != null && row.max() != null && row.min().compareTo(row.max()) > 0) {
                throw new ApiException(
                        "INVALID_RATE_RANGE", "rateMin cannot exceed rateMax", HttpStatus.BAD_REQUEST);
            }
        }
    }

    /**
     * F-0665/F-0434 — rejects a PATCH naming a collab display mode outside {@link
     * #ALLOWED_COLLAB_DISPLAY_MODES} outright, rather than silently defaulting it (the frontend's
     * Select only ever offers those 4 values, so anything else is either a stale client or a
     * fabricated request). No-ops when {@code collabs} is absent from the patch (nothing to
     * validate), same as {@link #validateRateCard} no-ops on an absent {@code rateCard}.
     */
    private static void validateCollabDisplayModes(List<PortfolioCollab> collabs) {
        if (collabs == null) {
            return;
        }
        for (PortfolioCollab collab : collabs) {
            if (!ALLOWED_COLLAB_DISPLAY_MODES.contains(collab.displayMode())) {
                throw new ApiException(
                        "INVALID_COLLAB_DISPLAY_MODE",
                        "Unknown collab display mode: " + collab.displayMode(),
                        HttpStatus.BAD_REQUEST);
            }
        }
    }

    private static void validateCoverImageMime(MultipartFile file) {
        String declared = file.getContentType();
        if (declared == null || !declared.startsWith("image/")) {
            throw new ApiException(
                    "INVALID_FILE_TYPE", "Cover must be an image", HttpStatus.BAD_REQUEST);
        }
        try {
            String sniffed = MediaMimeSniffer.detectMimeType(file.getInputStream());
            if (sniffed == null || !sniffed.startsWith("image/")) {
                throw new ApiException(
                        "INVALID_FILE_TYPE",
                        "File content does not match an allowed image type",
                        HttpStatus.BAD_REQUEST);
            }
            if (!MediaMimeSniffer.mimeTypesCompatible(declared, sniffed)) {
                throw new ApiException(
                        "INVALID_FILE_TYPE",
                        "Declared content type does not match file content",
                        HttpStatus.BAD_REQUEST);
            }
        } catch (IOException e) {
            throw new ApiException(
                    "INVALID_FILE_TYPE", "Unable to read file content", HttpStatus.BAD_REQUEST);
        }
    }

    private void streamCoverToR2(String key, MultipartFile file, String contentType) {
        long size = file.getSize();
        try (InputStream raw = file.getInputStream();
                LimitedInputStream limited = new LimitedInputStream(raw, MAX_COVER_BYTES)) {
            r2StorageService.putStream(key, limited, size, contentType);
        } catch (IOException e) {
            throw new ApiException(
                    "UPLOAD_FAILED", "Failed to upload cover image", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (IllegalArgumentException e) {
            throw new ApiException("FILE_TOO_LARGE", e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Resolve stored cover key (or legacy public URL) to a time-limited presigned GET — same
     * pattern as {@code CreatorDeliverableService#resolveDownloadUrl} (#40 / M-K6-C3-4).
     */
    private String resolveCoverUrl(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String key = toCoverObjectKey(stored);
        if (key == null || !r2StorageService.isAvailable()) {
            return stored;
        }
        try {
            return r2StorageService.presignGet(key).uploadUrl();
        } catch (RuntimeException e) {
            return stored;
        }
    }

    private String toCoverObjectKey(String stored) {
        if (!stored.startsWith("http://") && !stored.startsWith("https://")) {
            return stored;
        }
        String base = r2Properties.getPublicUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (stored.startsWith(base + "/")) {
            return stored.substring(base.length() + 1);
        }
        return null;
    }
}
