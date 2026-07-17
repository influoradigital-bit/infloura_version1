package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.CreatorScore;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ContentFlagStatus;
import com.influora.domain.enums.ContentFlagType;
import com.influora.domain.enums.CreatorApplicationStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContentFlagRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorScoreRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.repository.CreatorProfileSpecs;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminCreatorDtos.CollaborationRecordDto;
import com.influora.web.dto.admin.AdminCreatorDtos.CreatorDetailDto;
import com.influora.web.dto.admin.AdminCreatorDtos.CreatorSummaryDto;
import com.influora.web.dto.admin.AdminCreatorDtos.PagedCreatorsDto;
import com.influora.web.dto.admin.AdminCreatorDtos.PlatformStatsDto;
import com.influora.web.dto.admin.AdminCreatorDtos.QualityMetricsDto;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creator CRUD/moderation API (src/admin/TASK_ASSIGNMENTS.md P1: "Creator CRUD API" — Vikram,
 * cycle 5). Backs {@code AdminCreatorController}, the swap-in target for {@code
 * useCreatorDetail.ts}/{@code CreatorProfile.tsx}'s stubbed {@code creatorApi.getById}/{@code
 * .reviewApplication}/{@code .forceInstagramReauth}/{@code .suspend}/{@code .reinstate} calls
 * (Ananya, cycle 5).
 *
 * <p><b>Entity reuse, not invention:</b> {@code CreatorDetail} maps onto the EXISTING {@link
 * CreatorProfile} entity (V6__creators_collaborations.sql) — confirmed by grep before writing this
 * service, same discipline {@code AdminBrandService} documents for {@code Workspace}. NOT the same
 * Java class as the frontend's {@code CreatorProfile.tsx} component — that naming collision is
 * frontend-component vs. backend-entity only, never ambiguous in code (this service imports {@code
 * com.influora.domain.entity.CreatorProfile}, ADT to the frontend's {@code CreatorProfile}
 * React component are unrelated namespaces). Also distinct from {@link CreatorScore} (Phase 3
 * scoring — see that entity's javadoc), which this service reads from but does not duplicate.
 * What the schema was missing (suspension flag + audit trail, application-review trail) was added
 * by V38__creator_profile_moderation.sql — mirrors V36's shape for {@code Workspace} exactly.
 *
 * <p><b>RBAC:</b> matches the role-permission-matrix (src/admin/__tests__/role-permission-matrix.md):
 * creator detail read is SUPER_ADMIN/ADMIN/SUPPORT (view-only); application review/force-reauth/
 * suspend/reinstate are all SUPER_ADMIN/ADMIN only.
 *
 * <p><b>Audit trail:</b> every mutating method calls {@link AdminAuditLogService#record} AFTER the
 * {@code CreatorProfile}/{@code MetaOAuthToken} mutation is saved, using the entityType {@code
 * "CREATOR"} field allow-list already reserved for this in {@code AdminAuditLogService} (Kabir's
 * spec anticipated this controller: {@code Set.of("id", "isSuspended", "applicationStatus",
 * "tier")}).
 *
 * <p><b>Scope, cycle 5:</b> only the 5 endpoints {@code useCreatorDetail.ts}/{@code
 * CreatorProfile.tsx} actually call are implemented — detail read, review-application,
 * force-Instagram-reauth, suspend, reinstate. {@code creatorApi.list}/{@code .update}/{@code
 * .adjustTier}/{@code .getPendingApplications} (also declared in api-contracts.ts) are NOT built —
 * no frontend caller needs them yet, same "flagged as follow-up, not silently forgotten" pattern
 * {@code AdminBrandController}'s javadoc uses for its own unimplemented list/update/overrideBudget.
 */
@Service
public class AdminCreatorService {

    /** Most recent N per-platform snapshot rows considered when deriving the 30-day post/reel counters. */
    private static final int MEDIA_LOOKBACK = 200;

    private final AdminContextService adminContext;
    private final AdminAuditLogService adminAuditLogService;
    private final CreatorProfileRepository creatorProfileRepository;
    private final UserRepository userRepository;
    private final PlatformStatRepository platformStatRepository;
    private final CreatorMetricsRepository creatorMetricsRepository;
    private final MediaMetricsRepository mediaMetricsRepository;
    private final CreatorScoreRepository creatorScoreRepository;
    private final CollaborationRepository collaborationRepository;
    private final CampaignRepository campaignRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ContentFlagRepository contentFlagRepository;
    private final MetaOAuthTokenRepository metaOAuthTokenRepository;

    public AdminCreatorService(
            AdminContextService adminContext,
            AdminAuditLogService adminAuditLogService,
            CreatorProfileRepository creatorProfileRepository,
            UserRepository userRepository,
            PlatformStatRepository platformStatRepository,
            CreatorMetricsRepository creatorMetricsRepository,
            MediaMetricsRepository mediaMetricsRepository,
            CreatorScoreRepository creatorScoreRepository,
            CollaborationRepository collaborationRepository,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository,
            ContentFlagRepository contentFlagRepository,
            MetaOAuthTokenRepository metaOAuthTokenRepository) {
        this.adminContext = adminContext;
        this.adminAuditLogService = adminAuditLogService;
        this.creatorProfileRepository = creatorProfileRepository;
        this.userRepository = userRepository;
        this.platformStatRepository = platformStatRepository;
        this.creatorMetricsRepository = creatorMetricsRepository;
        this.mediaMetricsRepository = mediaMetricsRepository;
        this.creatorScoreRepository = creatorScoreRepository;
        this.collaborationRepository = collaborationRepository;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
        this.contentFlagRepository = contentFlagRepository;
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
    }

    @Transactional(readOnly = true)
    public PagedCreatorsDto list(
            AuthPrincipal principal,
            String applicationStatus,
            Boolean suspended,
            String search,
            int page,
            int pageSize) {
        adminContext.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        // Parse applicationStatus string to enum
        CreatorApplicationStatus statusEnum = null;
        if (applicationStatus != null && !applicationStatus.isBlank()) {
            try {
                statusEnum = CreatorApplicationStatus.valueOf(applicationStatus.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ApiException(
                        "INVALID_APPLICATION_STATUS",
                        "Invalid applicationStatus: " + applicationStatus,
                        HttpStatus.BAD_REQUEST);
            }
        }

        Specification<CreatorProfile> spec = CreatorProfileSpecs.withFilters(statusEnum, suspended, search);
        PageRequest pageRequest = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CreatorProfile> pageResult = creatorProfileRepository.findAll(spec, pageRequest);
        List<CreatorProfile> profiles = pageResult.getContent();

        // Meera G-7 — toSummaryDto used to do a per-row userRepository.findById +
        // platformStatRepository.findByCreatorProfileId call (2N+1 for a page of N creators).
        // Batch-load both for the whole page in one query each, then map from the batch.
        List<String> userIds = profiles.stream().map(CreatorProfile::getUserId).distinct().toList();
        // Plain HashMap, not Collectors.toMap: User.email can be null (soft-delete anonymization
        // sets it to null — see User#anonymize-style setter around line 272), and toMap's default
        // Map::merge accumulator throws NPE on a null value.
        Map<String, String> emailsByUserId = new HashMap<>();
        for (User u : userRepository.findAllById(userIds)) {
            emailsByUserId.put(u.getId(), u.getEmail());
        }

        List<String> profileIds = profiles.stream().map(CreatorProfile::getId).toList();
        Map<String, PlatformStat> instagramByProfileId =
                platformStatRepository.findByCreatorProfileIdIn(profileIds).stream()
                        .filter(s -> "INSTAGRAM".equalsIgnoreCase(s.getPlatform()))
                        .collect(
                                Collectors.toMap(
                                        PlatformStat::getCreatorProfileId, s -> s, (first, dup) -> first));

        List<CreatorSummaryDto> summaries =
                profiles.stream()
                        .map(p -> toSummaryDto(p, emailsByUserId, instagramByProfileId))
                        .toList();

        int totalPages = pageResult.getTotalPages();
        return new PagedCreatorsDto(summaries, pageResult.getTotalElements(), page, pageSize, totalPages);
    }

    @Transactional(readOnly = true)
    public CreatorDetailDto getById(AuthPrincipal principal, String creatorProfileId) {
        adminContext.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);
        CreatorProfile profile = requireCreatorProfile(creatorProfileId);
        return toDetailDto(profile);
    }

    @Transactional
    public CreatorDetailDto reviewApplication(
            AuthPrincipal principal,
            HttpServletRequest request,
            String creatorProfileId,
            String action,
            String reason) {
        var admin =
                adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        CreatorProfile profile = requireCreatorProfile(creatorProfileId);

        CreatorApplicationStatus fromStatus = profile.getApplicationStatus();
        CreatorApplicationStatus toStatus;
        String auditAction;
        if ("APPROVE".equalsIgnoreCase(action)) {
            toStatus = CreatorApplicationStatus.APPROVED;
            auditAction = "APPROVE";
        } else if ("REJECT".equalsIgnoreCase(action)) {
            toStatus = CreatorApplicationStatus.REJECTED;
            auditAction = "REJECT";
        } else {
            throw new ApiException(
                    "INVALID_APPLICATION_ACTION",
                    "action must be APPROVE or REJECT",
                    HttpStatus.BAD_REQUEST);
        }

        try {
            profile.applyApplicationDecision(
                    toStatus, admin.getId(), toStatus == CreatorApplicationStatus.REJECTED ? reason : null);
        } catch (IllegalArgumentException e) {
            // [SEC: Vikram, P4] Defense in depth: ReviewApplicationRequest#reason is already
            // @NotBlank at the DTO layer, but this entity-level guard is the final arbiter for any
            // other caller — never let it fall through to a generic 500.
            throw new ApiException(
                    "APPLICATION_REJECTION_REASON_REQUIRED",
                    "A non-blank reason is required to reject an application",
                    HttpStatus.BAD_REQUEST);
        }
        creatorProfileRepository.save(profile);

        adminAuditLogService.record(
                principal,
                request,
                auditAction,
                "CREATOR",
                profile.getId(),
                Map.of("id", profile.getId(), "applicationStatus", fromStatus.name()),
                Map.of("id", profile.getId(), "applicationStatus", toStatus.name()),
                reason);

        return toDetailDto(profile);
    }

    /**
     * Revokes every active Meta OAuth connection this creator has (across every workspace it's
     * linked to), forcing a fresh Instagram authorization on next connect attempt. There is no
     * dedicated {@code AuditAction} for "force reauth" in {@code AdminAuditLogService}'s allow-list
     * (mirrors {@code src/admin/utils/auditLogger.ts}'s {@code AuditAction} const exactly, per its
     * class javadoc) — {@code UPDATE} is used as the closest fit (this mutates connection state),
     * same reasoning a future dedicated action could refine.
     */
    @Transactional
    public void forceInstagramReauth(
            AuthPrincipal principal, HttpServletRequest request, String creatorProfileId) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        CreatorProfile profile = requireCreatorProfile(creatorProfileId);

        List<MetaOAuthToken> activeTokens =
                metaOAuthTokenRepository.findByCreatorProfileIdAndRevokedFalse(profile.getId());
        for (MetaOAuthToken token : activeTokens) {
            token.revoke();
        }
        metaOAuthTokenRepository.saveAll(activeTokens);

        adminAuditLogService.record(
                principal,
                request,
                "UPDATE",
                "CREATOR",
                profile.getId(),
                Map.of("id", profile.getId()),
                Map.of("id", profile.getId()),
                "Force Instagram re-auth: revoked " + activeTokens.size() + " active connection(s)");
    }

    @Transactional
    public CreatorDetailDto suspend(
            AuthPrincipal principal, HttpServletRequest request, String creatorProfileId, String reason) {
        var admin =
                adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        CreatorProfile profile = requireCreatorProfile(creatorProfileId);

        if (profile.isSuspended()) {
            throw new ApiException(
                    "ALREADY_SUSPENDED", "Creator is already suspended", HttpStatus.CONFLICT);
        }

        try {
            profile.suspend(reason, admin.getId());
        } catch (IllegalStateException e) {
            // [SEC: Vikram, P4] Defense in depth: the isSuspended() fast-path above already covers
            // the common double-submit case, but this catches the same condition if a concurrent
            // request raced past it (TOCTOU) — never let it fall through to a generic 500.
            throw new ApiException(
                    "ALREADY_SUSPENDED", "Creator is already suspended", HttpStatus.CONFLICT);
        }
        creatorProfileRepository.save(profile);

        adminAuditLogService.record(
                principal,
                request,
                "SUSPEND",
                "CREATOR",
                profile.getId(),
                Map.of("id", profile.getId(), "isSuspended", false),
                Map.of("id", profile.getId(), "isSuspended", true),
                reason);

        return toDetailDto(profile);
    }

    @Transactional
    public CreatorDetailDto reinstate(
            AuthPrincipal principal, HttpServletRequest request, String creatorProfileId, String reason) {
        var admin =
                adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        CreatorProfile profile = requireCreatorProfile(creatorProfileId);

        if (!profile.isSuspended()) {
            throw new ApiException("NOT_SUSPENDED", "Creator is not suspended", HttpStatus.CONFLICT);
        }

        try {
            profile.reinstate(admin.getId());
        } catch (IllegalStateException e) {
            // [SEC: Vikram, P4] Defense in depth -- same TOCTOU rationale as suspend() above.
            throw new ApiException("NOT_SUSPENDED", "Creator is not suspended", HttpStatus.CONFLICT);
        }
        creatorProfileRepository.save(profile);

        adminAuditLogService.record(
                principal,
                request,
                "REINSTATE",
                "CREATOR",
                profile.getId(),
                Map.of("id", profile.getId(), "isSuspended", true),
                Map.of("id", profile.getId(), "isSuspended", false),
                reason);

        return toDetailDto(profile);
    }

    private CreatorProfile requireCreatorProfile(String creatorProfileId) {
        return creatorProfileRepository
                .findById(creatorProfileId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_NOT_FOUND", "Creator not found", HttpStatus.NOT_FOUND));
    }

    // ============================================
    // DTO ASSEMBLY
    // ============================================

    private CreatorSummaryDto toSummaryDto(
            CreatorProfile profile,
            Map<String, String> emailsByUserId,
            Map<String, PlatformStat> instagramByProfileId) {
        String email = emailsByUserId.get(profile.getUserId());
        PlatformStat instagram = instagramByProfileId.get(profile.getId());

        return new CreatorSummaryDto(
                profile.getId(),
                profile.getDisplayName(),
                email,
                instagram != null ? instagram.getHandle() : null,
                profile.getTotalFollowers(),
                profile.getApplicationStatus().name(),
                deriveTier(profile.getTotalFollowers()),
                profile.isSuspended(),
                profile.getCreatedAt());
    }

    private CreatorDetailDto toDetailDto(CreatorProfile profile) {
        String email = userRepository.findById(profile.getUserId()).map(User::getEmail).orElse(null);

        PlatformStat instagram =
                platformStatRepository.findByCreatorProfileId(profile.getId()).stream()
                        .filter(s -> "INSTAGRAM".equalsIgnoreCase(s.getPlatform()))
                        .findFirst()
                        .orElse(null);

        long flaggedContentCount =
                contentFlagRepository.countByContentTypeAndContentIdAndStatusIn(
                        ContentFlagType.PROFILE,
                        profile.getId(),
                        Set.of(ContentFlagStatus.PENDING, ContentFlagStatus.REVIEWED));

        Instant instagramOauthAt =
                metaOAuthTokenRepository
                        .findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(profile.getId())
                        .map(MetaOAuthToken::getCreatedAt)
                        .orElse(null);

        return new CreatorDetailDto(
                profile.getId(),
                profile.getDisplayName(),
                email,
                JsonLists.stringListFromJson(profile.getCategoriesJson()),
                profile.getTotalFollowers(),
                profile.getEngagementRate() != null ? profile.getEngagementRate() : BigDecimal.ZERO,
                profile.getApplicationStatus().name(),
                instagram != null && instagram.isVerified(),
                latestQualityScore(profile.getId()),
                deriveTier(profile.getTotalFollowers()),
                profile.isSuspended(),
                profile.getCreatedAt(),
                profile.getBio(),
                profile.getCity(),
                instagram != null ? instagram.getHandle() : null,
                null, // instagramUserId — never persisted anywhere in this schema, see DTO javadoc
                instagramOauthAt,
                platformStats(profile.getId()),
                collaborationHistory(profile.getUserId()),
                qualityMetrics(profile.getId()),
                flaggedContentCount,
                profile.getApplicationReviewedBy(),
                profile.getApplicationReviewedAt(),
                profile.getApplicationRejectionReason());
    }

    private BigDecimal latestQualityScore(String creatorProfileId) {
        return creatorScoreRepository
                .findFirstByCreatorProfileIdOrderByTimeDesc(creatorProfileId)
                .map(CreatorScore::getQualityScore)
                .orElse(BigDecimal.ZERO);
    }

    private QualityMetricsDto qualityMetrics(String creatorProfileId) {
        BigDecimal overall = latestQualityScore(creatorProfileId);
        // deadlineAdherence/revisionRate/disputeRate: honest zeros — see DTO javadoc gap note.
        return new QualityMetricsDto(0.0, 0.0, 0.0, overall);
    }

    /**
     * 4-bucket admin-surface tier, derived from {@code totalFollowers} — matches {@code
     * Creator.tier}'s exact union ({@code NANO|MICRO|MID|MACRO}). No canonical tier concept is
     * persisted anywhere in this schema (confirmed by grep); the only existing precedent is {@code
     * RateEstimationService#determineTier}, a 5-bucket NANO/MICRO/MID/MACRO/MEGA breakdown used for
     * rate estimation, not creator classification. Collapses MEGA into MACRO here (same kind of
     * deliberate collapse {@code AdminBrandService.mapKycStatus} documents for its own 4-to-3 KYC
     * mapping) since the admin frontend type has no 5th bucket.
     */
    private static String deriveTier(long followers) {
        if (followers >= 500_000) return "MACRO";
        if (followers >= 50_000) return "MID";
        if (followers >= 10_000) return "MICRO";
        return "NANO";
    }

    private PlatformStatsDto platformStats(String creatorProfileId) {
        CreatorMetric latest =
                creatorMetricsRepository
                        .findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(creatorProfileId, "INSTAGRAM")
                        .orElse(null);

        long avgReach = latest != null && latest.getAvgReachPerPost() != null ? latest.getAvgReachPerPost() : 0L;
        // avgEngagement (absolute, not a %): reach * engagementRate%, when both are known — derived
        // the same way AnalyticsService.getCreatorMetrics derives its "totalEngagements" tile, never
        // fabricated. avgEngagementRate is a distinct concept from Creator.engagementRate (top-level
        // field, sourced from the CreatorProfile row) — this one is per-post, from the metrics table.
        long avgEngagement = 0L;
        if (latest != null && latest.getAvgReachPerPost() != null && latest.getAvgEngagementRate() != null) {
            avgEngagement =
                    BigDecimal.valueOf(latest.getAvgReachPerPost())
                            .multiply(latest.getAvgEngagementRate())
                            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                            .longValue();
        }

        List<MediaMetric> recent =
                mediaMetricsRepository.findByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        creatorProfileId, "INSTAGRAM", PageRequest.of(0, MEDIA_LOOKBACK));

        // Dedupe to one (latest) row per media_id — media_metrics is append-only, one row per poll,
        // so the same post can appear multiple times in the lookback window.
        Map<String, MediaMetric> latestByMedia = new LinkedHashMap<>();
        for (MediaMetric m : recent) {
            latestByMedia.putIfAbsent(m.getMediaId(), m);
        }

        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        int posts = 0;
        int reels = 0;
        for (MediaMetric m : latestByMedia.values()) {
            if (m.getPostedAt() == null || m.getPostedAt().isBefore(cutoff)) {
                continue;
            }
            if ("REEL".equalsIgnoreCase(m.getMediaType())) {
                reels++;
            } else {
                posts++;
            }
        }

        return new PlatformStatsDto(avgReach, avgEngagement, posts, reels);
    }

    private List<CollaborationRecordDto> collaborationHistory(String creatorUserId) {
        // NOTE: Collaboration.creatorId is a users(id) FK, not a creator_profiles(id) FK (see
        // V6__creators_collaborations.sql fk_collab_creator_user) — must query by the creator's
        // user id, never the creator_profile id, same distinction AdminBrandService doesn't need
        // to make (Workspace IS the brand-facing id) but this service does.
        List<Collaboration> collaborations = collaborationRepository.findByCreatorId(creatorUserId);
        if (collaborations.isEmpty()) {
            return List.of();
        }

        List<String> campaignIds = collaborations.stream().map(Collaboration::getCampaignId).distinct().toList();
        Map<String, Campaign> campaignsById = new LinkedHashMap<>();
        for (Campaign c : campaignRepository.findAllById(campaignIds)) {
            campaignsById.put(c.getId(), c);
        }
        List<String> workspaceIds =
                campaignsById.values().stream().map(Campaign::getWorkspaceId).distinct().toList();
        Map<String, Workspace> workspacesById = new LinkedHashMap<>();
        for (Workspace w : workspaceRepository.findAllById(workspaceIds)) {
            workspacesById.put(w.getId(), w);
        }

        List<CollaborationRecordDto> dtos = new ArrayList<>();
        for (Collaboration collab : collaborations) {
            Campaign campaign = campaignsById.get(collab.getCampaignId());
            if (campaign == null) {
                continue;
            }
            Workspace workspace = workspacesById.get(campaign.getWorkspaceId());
            dtos.add(
                    new CollaborationRecordDto(
                            collab.getId(),
                            campaign.getTitle(),
                            workspace != null ? workspace.getName() : null,
                            collab.getAgreedRate() != null ? collab.getAgreedRate() : BigDecimal.ZERO,
                            collab.getStatus().name(),
                            // No dedicated completed_at column on `collaborations` — updated_at is
                            // the closest honest proxy for a COMPLETED-status row's completion time,
                            // never fabricated for any other status.
                            collab.getStatus() == CollaborationStatus.COMPLETED ? collab.getUpdatedAt() : null));
        }
        return dtos;
    }
}
