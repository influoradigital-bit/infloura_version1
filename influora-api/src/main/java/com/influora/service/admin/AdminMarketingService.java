package com.influora.service.admin;

import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.CreatorApplicationStatus;
import com.influora.domain.enums.ReviewerType;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.ReviewRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminMarketingDtos.ConversionRatesDto;
import com.influora.web.dto.admin.AdminMarketingDtos.FunnelDto;
import com.influora.web.dto.admin.AdminMarketingDtos.GrowthMetricsDto;
import com.influora.web.dto.admin.AdminMarketingDtos.PlatformReputationScoreDto;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link com.influora.web.AdminMarketingController} — the admin marketing/analytics console.
 *
 * <p>Reputation and growth are implemented; see {@link
 * com.influora.web.dto.admin.AdminMarketingDtos} for why the acquisition/referrals endpoints (and
 * part of growth's shape — {@code profileComplete}/{@code cohortRetention}/{@code referralStats})
 * are deliberately absent (no backing data — never fabricated). Read-only.
 */
@Service
public class AdminMarketingService {

    private static final double SECONDS_PER_HOUR = 3600.0;

    private final AdminContextService adminContext;
    private final ReviewRepository reviewRepository;
    private final DisputeRepository disputeRepository;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final CampaignRepository campaignRepository;
    private final CreatorProfileRepository creatorProfileRepository;

    public AdminMarketingService(
            AdminContextService adminContext,
            ReviewRepository reviewRepository,
            DisputeRepository disputeRepository,
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            CampaignRepository campaignRepository,
            CreatorProfileRepository creatorProfileRepository) {
        this.adminContext = adminContext;
        this.reviewRepository = reviewRepository;
        this.disputeRepository = disputeRepository;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.campaignRepository = campaignRepository;
        this.creatorProfileRepository = creatorProfileRepository;
    }

    /**
     * {@code GET /admin/marketing/reputation} — platform reputation snapshot, every field a live
     * aggregate over {@code Review} stars and {@code Dispute} resolution times. See {@link
     * PlatformReputationScoreDto} for the exact per-field derivation. A metric with no underlying
     * rows coalesces to {@code 0.0} (honest "no data yet"), never a fabricated value. Gated
     * SUPER_ADMIN + ADMIN.
     */
    @Transactional(readOnly = true)
    public PlatformReputationScoreDto getReputation(AuthPrincipal principal) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        double creatorQualityAvg = coalesce(reviewRepository.avgStarsByReviewerType(ReviewerType.BRAND));
        double brandSatisfactionAvg =
                coalesce(reviewRepository.avgStarsByReviewerType(ReviewerType.CREATOR));
        double overall = coalesce(reviewRepository.avgAllStars());
        Double avgResolutionSeconds = disputeRepository.avgResolutionSeconds();
        double disputeResolutionSpeedHours =
                avgResolutionSeconds == null ? 0.0 : avgResolutionSeconds / SECONDS_PER_HOUR;

        return new PlatformReputationScoreDto(
                overall,
                creatorQualityAvg,
                brandSatisfactionAvg,
                disputeResolutionSpeedHours,
                Instant.now().toString());
    }

    /**
     * {@code GET /admin/marketing/growth} — real-data subset of the growth funnel. See {@link
     * GrowthMetricsDto} for the exact per-field derivation and what's deliberately omitted. Both
     * conversion rates coalesce to {@code 0.0} on a zero denominator (honest "no data yet"), never a
     * divide-by-zero or a fabricated ratio. Gated SUPER_ADMIN + ADMIN.
     */
    @Transactional(readOnly = true)
    public GrowthMetricsDto getGrowth(AuthPrincipal principal) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        long signups = userRepository.count();
        long firstCampaign = campaignRepository.countBrandWorkspacesWithCampaign();
        long repeatCampaign = campaignRepository.countBrandWorkspacesWithRepeatCampaigns();

        long totalApplications = creatorProfileRepository.count();
        double creatorApplicationToApproval =
                totalApplications == 0
                        ? 0.0
                        : (double)
                                        creatorProfileRepository.countByApplicationStatus(
                                                CreatorApplicationStatus.APPROVED)
                                / totalApplications;

        long totalBrands = workspaceRepository.countByType(WorkspaceType.BRAND);
        double brandSignupToFirstCampaign =
                totalBrands == 0 ? 0.0 : (double) firstCampaign / totalBrands;

        return new GrowthMetricsDto(
                new FunnelDto(signups, firstCampaign, repeatCampaign),
                new ConversionRatesDto(creatorApplicationToApproval, brandSignupToFirstCampaign),
                Instant.now().toString());
    }

    private static double coalesce(Double value) {
        return value == null ? 0.0 : value;
    }
}
