package com.influora.service.portfolio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.entity.Review;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ReviewerType;
import com.influora.integration.storage.R2StorageService;
import com.influora.service.security.MalwareScanService;
import com.influora.service.notification.NotificationService;
import com.influora.service.notification.event.PortfolioContactEvent;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.ReviewRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorContextService;
import com.influora.service.CreatorProfileService;
import com.influora.web.dto.creator.CreatorDtos.PlatformStatResponse;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Spec 12 §5.1 image cap (10 MB) — same as deliverable proof screenshots. */
    private static final long MAX_COVER_BYTES = 10_485_760L;

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
    private final NotificationService notificationService;
    private final UserRepository userRepository;

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
            NotificationService notificationService,
            UserRepository userRepository) {
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
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public PortfolioPageResponse getPublic(String username) {
        CreatorProfile profile = creatorProfileService.requireProfileByUsername(username);
        requireDiscoverablePortfolio(profile);
        return assemble(profile, true);
    }

    @Transactional(readOnly = true)
    public PortfolioPageResponse getMine(AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        return assemble(profile, false);
    }

    @Transactional
    public PortfolioPageResponse updateMine(AuthPrincipal principal, PortfolioPatchRequest patch) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);

        if (patch.username() != null && !patch.username().isBlank()) {
            creatorProfileService.applyUsername(profile, patch.username().trim());
        }

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
        profile.applyPortfolioSettingsJson(writeSettings(settings));
        try {
            creatorProfileRepository.save(profile);
        } catch (DataIntegrityViolationException dup) {
            throw new ApiException(
                    "USERNAME_TAKEN", "This username is already taken", HttpStatus.CONFLICT);
        }
        return assemble(profile, false);
    }

    @Transactional
    public SyncPlatformsResponse syncPlatforms(AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);

        // For now, sync is limited to refreshing existing platform_stats entries
        // Full OAuth flow for fetching fresh data is deferred — this validates the profile exists
        // and confirms the creator can call the endpoint. Future: integrate with MetaOAuthToken
        // to fetch live data from Instagram/Facebook via InstagramInsightsClient.

        log.info("Portfolio platform sync requested for creator={}", profile.getId());
        return new SyncPlatformsResponse(Instant.now().toString());
    }

    @Transactional(readOnly = true)
    public PortfolioAnalyticsResponse analytics(AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);

        // Calculate real portfolio metrics from existing data
        // Page views and link clicks tracking not yet implemented (requires analytics events table)
        // For now, return real counts from what we have: collaborations as proxy for brand inquiries

        // collaborations.creator_id is an FK to users.id (V6), NOT creator_profiles.id — scope
        // these counts by the profile's userId or they silently return 0 in production, since
        // CreatorProfile.id and User.id are different ULIDs (Priya §6h; see the javadoc on
        // CollaborationRepository#countByCreatorIdAndStatus / #countByCreatorId).
        long completedCollabs = collaborationRepository.countByCreatorIdAndStatus(
                profile.getUserId(), CollaborationStatus.COMPLETED);

        long totalInquiries = collaborationRepository.countByCreatorId(profile.getUserId());

        // Media kit downloads not tracked yet — return 0 until analytics table added
        long mediaKitDownloads = 0;

        // Profile clicks from platform stats (total followers as proxy metric)
        long profileClicks = profile.getTotalFollowers() > 0 ?
                profile.getTotalFollowers() / 100 : 0; // Rough estimate

        // Page views calculation deferred until analytics events table exists
        var pageViews = new PortfolioAnalyticsResponse.PageViews(0, 0);

        return new PortfolioAnalyticsResponse(
                pageViews,
                profileClicks,
                List.of(), // Link clicks require custom link tracking (not implemented)
                totalInquiries,
                mediaKitDownloads
        );
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

        // Send notification to creator
        PortfolioContactEvent event = new PortfolioContactEvent(
                profile.getUserId(),
                null, // No workspace context for portfolio contacts
                Ulids.newUlid(), // Entity ID for idempotency
                name,
                email,
                message
        );

        String title = "New portfolio contact from " + name;
        String body = "You received a message via your portfolio page.";
        String link = "/creator/inbox"; // Deep link to inbox (adjust based on frontend routing)

        // Fetch creator's email from User entity
        String creatorEmail = getCreatorEmail(profile.getUserId());

        Map<String, Object> templateData = Map.of(
                "creatorName", profile.getDisplayName(),
                "senderName", name,
                "senderEmail", email,
                "message", message
        );

        notificationService.notify(
                event,
                title,
                body,
                link,
                creatorEmail,
                "portfolio.contact", // Template key for email rendering
                templateData
        );

        log.info("Portfolio contact delivered to creator={} from sender={}", profile.getId(), email);
        return new PortfolioContactResponse(true);
    }

    private String getCreatorEmail(String userId) {
        return userRepository.findById(userId)
                .map(user -> user.getEmail())
                .orElseThrow(() -> new ApiException(
                        "USER_NOT_FOUND",
                        "Creator user account not found",
                        HttpStatus.NOT_FOUND
                ));
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

        PortfolioStats stats = computeStats(completed, brandReviewsByCollab);
        List<PortfolioCollab> collabs =
                buildCollabs(completed, publicView, settings, brandReviewsByCollab);
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
                buildRateCard(profile, settings),
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
            Map<String, Review> brandReviewsByCollab) {
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
            Workspace workspace =
                    workspaceRepository.findById(campaign.getWorkspaceId()).orElse(null);
            Review brandReview = brandReviewsByCollab.get(collab.getId());
            Double rating = brandReview != null ? (double) brandReview.getStars() : null;
            String quote = brandReview != null ? brandReview.getReviewText() : null;
            out.add(
                    new PortfolioCollab(
                            collab.getId(),
                            campaign.getWorkspaceId(),
                            workspace != null ? workspace.getName() : "Brand",
                            workspace != null ? workspace.getLogoUrl() : null,
                            campaign.getTitle(),
                            "Campaign collaboration",
                            "INSTAGRAM",
                            collab.getCreatedAt().toString(),
                            rating,
                            quote,
                            "logo"));
        }
        return out;
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
        return new PortfolioStats(completed.size(), avgRating, 95, repeatBrands);
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

    private List<PortfolioRateRow> buildRateCard(CreatorProfile profile, PortfolioSettings settings) {
        if ("hidden".equals(settings.getVisibility().rateCard())) {
            return List.of();
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

    private String resolveUsername(CreatorProfile profile) {
        if (profile.getUsername() != null && !profile.getUsername().isBlank()) {
            return profile.getUsername();
        }
        return UsernameUtils.normalize(profile.getDisplayName());
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

    private PlatformStatResponse toPlatform(PlatformStat ps) {
        return new PlatformStatResponse(
                ps.getPlatform(),
                ps.getHandle() != null ? ps.getHandle() : "",
                ps.getFollowers(),
                ps.getEngagementRate(),
                ps.isVerified(),
                ps.getProfileUrl());
    }

    private static BigDecimal extractRateMin(List<PortfolioRateRow> rateCard) {
        if (rateCard == null || rateCard.isEmpty()) {
            return null;
        }
        return rateCard.get(0).min();
    }

    private static BigDecimal extractRateMax(List<PortfolioRateRow> rateCard) {
        if (rateCard == null || rateCard.isEmpty()) {
            return null;
        }
        return rateCard.get(0).max();
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
