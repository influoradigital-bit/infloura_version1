package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.ContentFlag;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.ContentFlagStatus;
import com.influora.domain.enums.ContentFlagType;
import com.influora.repository.ContentFlagRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminApprovalDtos.ApprovalWorkflowDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Mockito unit tests for {@code ApprovalWorkflowService#getPendingApprovals}, focused on the
 * CONTENT_MODERATION slice after Priya's 2026-07-15 ruling: ESCALATED content flags must surface
 * in this unified queue exactly like PENDING ones (an escalated flag is still unresolved work, not
 * a terminal state — see {@code ContentFlagStatus} javadoc), and the widened read must report each
 * row's real status rather than the old hardcoded {@code "PENDING"} literal. Plain Mockito, no
 * {@code @SpringBootTest} — Testcontainers/Docker discovery does not work in this environment, same
 * constraint every other {@code Admin*ServiceTest} in this package works around; this is the best
 * available verification until the V20260715210000 migration can be exercised against a live DB
 * (boot is currently blocked at V54 pending a separate Flyway history approval).
 */
@ExtendWith(MockitoExtension.class)
class ApprovalWorkflowServiceTest {

    private static final String ADMIN_ID = "01HWXYZADMIN000000000001";
    private static final String PENDING_FLAG_ID = "01HWXYZFLAG00000000001";
    private static final String ESCALATED_FLAG_ID = "01HWXYZFLAG00000000002";

    @Mock private AdminContextService adminContext;
    @Mock private AdminBrandService adminBrandService;
    @Mock private AdminCreatorService adminCreatorService;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private ContentFlagRepository contentFlagRepository;
    @Mock private AuthPrincipal principal;

    private ApprovalWorkflowService approvalWorkflowService;

    @BeforeEach
    void setUp() {
        approvalWorkflowService =
                new ApprovalWorkflowService(
                        adminContext,
                        adminBrandService,
                        adminCreatorService,
                        workspaceRepository,
                        creatorProfileRepository,
                        contentFlagRepository);
    }

    @Test
    @DisplayName(
            "getPendingApprovals(CONTENT_MODERATION) surfaces an ESCALATED flag alongside a PENDING"
                    + " one, via findPendingReviewQueue (not the PENDING-only"
                    + " findByStatusOrderByCreatedAtAsc), reporting each row's real status")
    void testContentModerationQueueSurfacesEscalatedFlags() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenReturn(AdminUser.create(ADMIN_ID, "admin@influora.ai", "hash", AdminRole.ADMIN));

        ContentFlag pendingFlag =
                ContentFlag.userFlag(
                        PENDING_FLAG_ID,
                        ContentFlagType.DELIVERABLE,
                        "01HWXYZCONTENT000000001",
                        "preview text",
                        "Inappropriate content",
                        "01HWXYZUSER0000000001");
        ContentFlag escalatedFlag =
                ContentFlag.userFlag(
                        ESCALATED_FLAG_ID,
                        ContentFlagType.PROFILE,
                        "01HWXYZCONTENT000000002",
                        "preview text 2",
                        "Repeat offender",
                        "01HWXYZUSER0000000002");
        escalatedFlag.markEscalated("01HWXYZADMIN000000000099");

        Page<ContentFlag> queue = new PageImpl<>(List.of(escalatedFlag, pendingFlag));
        when(contentFlagRepository.findPendingReviewQueue(any(Pageable.class))).thenReturn(queue);

        List<ApprovalWorkflowDto> results =
                approvalWorkflowService.getPendingApprovals(principal, "CONTENT_MODERATION");

        assertEquals(2, results.size());

        ApprovalWorkflowDto escalatedDto =
                results.stream().filter(dto -> dto.entityId().equals(ESCALATED_FLAG_ID)).findFirst().orElseThrow();
        assertEquals(
                ContentFlagStatus.ESCALATED.name(),
                escalatedDto.status(),
                "an escalated flag must report its real status, not a hardcoded PENDING");

        ApprovalWorkflowDto pendingDto =
                results.stream().filter(dto -> dto.entityId().equals(PENDING_FLAG_ID)).findFirst().orElseThrow();
        assertEquals(ContentFlagStatus.PENDING.name(), pendingDto.status());

        assertTrue(
                results.stream().allMatch(dto -> dto.id().startsWith("CONTENT_MODERATION:")),
                "composite ids must carry the CONTENT_MODERATION type prefix");

        verify(contentFlagRepository).findPendingReviewQueue(any(Pageable.class));
        verify(contentFlagRepository, never()).findByStatusOrderByCreatedAtAsc(any(), any());
    }
}
