package com.influora.service;

import com.influora.security.AuthPrincipal;
import com.influora.service.analytics.AnalyticsService;
import com.influora.web.dto.analytics.AnalyticsDtos.ContentPerformanceResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorDemographicsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorMetricsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorScoresResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task #35 (P2-V6, Creator Week 4+) — creator-self analytics reads. Resolves the authenticated
 * creator's profile via {@link CreatorContextService} (never a path-param creator id), then
 * delegates to {@link AnalyticsService}'s profile-scoped loaders — same data pipeline as
 * {@code AnalyticsController} (B5/B6), brand-safety scores included when present on {@code
 * CreatorScore}.
 */
@Service
public class CreatorAnalyticsService {

    private final CreatorContextService creatorContext;
    private final AnalyticsService analyticsService;

    public CreatorAnalyticsService(CreatorContextService creatorContext, AnalyticsService analyticsService) {
        this.creatorContext = creatorContext;
        this.analyticsService = analyticsService;
    }

    @Transactional(readOnly = true)
    public CreatorMetricsResponse getMyMetrics(AuthPrincipal principal, Instant startDate, Instant endDate) {
        String creatorProfileId = creatorContext.requireCreatorProfile(principal).getId();
        return analyticsService.getCreatorMetricsForProfile(creatorProfileId, startDate, endDate);
    }

    @Transactional(readOnly = true)
    public CreatorScoresResponse getMyScores(AuthPrincipal principal) {
        String creatorProfileId = creatorContext.requireCreatorProfile(principal).getId();
        return analyticsService.getCreatorScoresForProfile(creatorProfileId);
    }

    @Transactional(readOnly = true)
    public CreatorDemographicsResponse getMyDemographics(AuthPrincipal principal) {
        String creatorProfileId = creatorContext.requireCreatorProfile(principal).getId();
        return analyticsService.getCreatorDemographicsForProfile(creatorProfileId);
    }

    /**
     * P2-14 — per-post content performance for the authenticated creator (self-service).
     */
    @Transactional(readOnly = true)
    public List<ContentPerformanceResponse> getMyContentPerformance(AuthPrincipal principal) {
        String creatorProfileId = creatorContext.requireCreatorProfile(principal).getId();
        return analyticsService.getContentPerformanceForProfile(creatorProfileId);
    }
}
