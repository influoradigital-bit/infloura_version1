package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.ContentFlagStatus;
import com.influora.domain.enums.CreatorApplicationStatus;
import com.influora.domain.enums.TicketStatus;
import com.influora.domain.enums.VerificationStatus;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.ContentFlagRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.SupportTicketRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminDashboardDtos.OperationsSummaryDto;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@code AdminDashboardService#operations}, focused on the {@code
 * reviewBacklog} KPI after Priya's 2026-07-15 ruling: ESCALATED content flags must count toward
 * the backlog exactly like PENDING ones (an escalated flag is still unreviewed work, not resolved
 * — see {@code ContentFlagStatus} javadoc), or the KPI would silently under-report the same way the
 * M-28 fix (see class javadoc / inline comment in {@code AdminDashboardService}) once had to fix a
 * hardcoded 0 for this exact field. Plain Mockito, no {@code @SpringBootTest} — Testcontainers/
 * Docker discovery does not work in this environment, same constraint every other {@code
 * Admin*ServiceTest} in this package works around; this is the best available verification until
 * the V20260715210000 migration can be exercised against a live DB (boot is currently blocked at
 * V54 pending a separate Flyway history approval).
 */
@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

    @Mock private AdminContextService adminContext;
    @Mock private AdminDashboardStatsCache statsCache;
    @Mock private CampaignRepository campaignRepository;
    @Mock private SupportTicketRepository supportTicketRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private ContentFlagRepository contentFlagRepository;
    @Mock private AuthPrincipal principal;

    private AdminDashboardService adminDashboardService;

    @BeforeEach
    void setUp() {
        adminDashboardService =
                new AdminDashboardService(
                        adminContext,
                        statsCache,
                        campaignRepository,
                        supportTicketRepository,
                        workspaceRepository,
                        creatorProfileRepository,
                        contentFlagRepository);
    }

    @Test
    @DisplayName(
            "operations().reviewBacklog includes ESCALATED content flags alongside PENDING KYC /"
                    + " application / content-flag counts, via countByStatusIn not the single-status"
                    + " countByStatus")
    void testReviewBacklogIncludesEscalatedContentFlags() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(null);
        when(campaignRepository.countByStatus(CampaignStatus.ACTIVE)).thenReturn(10L);
        when(supportTicketRepository.countByStatusIn(
                        List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_USER)))
                .thenReturn(4L);
        when(workspaceRepository.countByTypeAndVerificationStatus(
                        WorkspaceType.BRAND, VerificationStatus.PENDING))
                .thenReturn(3L);
        when(creatorProfileRepository.countByApplicationStatus(CreatorApplicationStatus.PENDING))
                .thenReturn(2L);
        // 1 PENDING + 1 ESCALATED content flag, only visible together via countByStatusIn.
        when(contentFlagRepository.countByStatusIn(
                        Set.of(ContentFlagStatus.PENDING, ContentFlagStatus.ESCALATED)))
                .thenReturn(2L);
        when(supportTicketRepository.findByResolvedAtIsNotNullAndResolvedAtAfter(any(Instant.class)))
                .thenReturn(List.of());

        OperationsSummaryDto result = adminDashboardService.operations(principal);

        // reviewBacklog = pendingKyc(3) + pendingApplications(2) + pendingContentFlags(2) = 7.
        // If ESCALATED were dropped (old PENDING-only behavior), this would incorrectly read 6.
        assertEquals(7L, result.reviewBacklog());

        verify(contentFlagRepository)
                .countByStatusIn(eq(Set.of(ContentFlagStatus.PENDING, ContentFlagStatus.ESCALATED)));
        verify(contentFlagRepository, never()).countByStatus(any(ContentFlagStatus.class));
    }
}
