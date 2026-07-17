package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Dispute;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DisputeOpenerType;
import com.influora.domain.enums.DisputeStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminAuditLogService;
import com.influora.service.admin.AdminContextService;
import com.influora.web.dto.dispute.DisputeDtos.OpenDisputeRequest;
import com.influora.web.dto.dispute.DisputeDtos.ResolveDisputeRequest;
import com.influora.web.dto.money.MoneyDtos.EscrowStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Task #34 — dispute gates: IDOR isolation, one-active-dispute, escrow freeze on open. */
@ExtendWith(MockitoExtension.class)
class DisputeServiceTest {

    private static final String DEAL_ID = "01HDEAL12345678901234";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String OTHER_CREATOR_ID = "01HOTHERCREATOR123456";
    private static final String BRAND_USER_ID = "01HBRANDUSER1234567890";
    private static final String WORKSPACE_ID = "01HWORKSPACE1234567890";
    private static final String DISPUTE_ID = "01HDISPUTE123456789012";
    private static final String ADMIN_ID = "01HADMIN12345678901234";

    @Mock private CreatorContextService creatorContext;
    @Mock private BrandContextService brandContext;
    @Mock private AdminContextService adminContext;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private DisputeRepository disputeRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private EscrowService escrowService;
    @Mock private AdminAuditLogService adminAuditLogService;
    @Mock private AuthPrincipal principal;
    @Mock private HttpServletRequest request;

    private DisputeService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service =
                new DisputeService(
                        disputeRepository,
                        collaborationRepository,
                        campaignRepository,
                        creatorProfileRepository,
                        workspaceRepository,
                        escrowService,
                        brandContext,
                        creatorContext,
                        adminContext,
                        adminAuditLogService);
        workspace = Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Beauty", "SMB");
    }

    private static Collaboration inProgressDeal(String creatorId) {
        Collaboration c =
                Collaboration.invite(DEAL_ID, "camp1", creatorId, "Invite", "INR");
        c.transitionTo(CollaborationStatus.IN_PROGRESS);
        return c;
    }

    @Test
    @DisplayName("creator open: happy path freezes escrow and marks collaboration DISPUTED")
    void creatorOpenHappyPath() {
        when(principal.getUserType()).thenReturn(UserType.CREATOR);
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(inProgressDeal(CREATOR_USER_ID)));
        when(escrowService.hasFundedUnreleasedEscrow(DEAL_ID)).thenReturn(true);
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(DEAL_ID), any(Set.class)))
                .thenReturn(false);

        var response =
                service.openDispute(
                        principal, DEAL_ID, new OpenDisputeRequest("Deliverables not as agreed"));

        assertEquals(DEAL_ID, response.collaborationId());
        assertEquals("CREATOR", response.openedByType());
        assertEquals("OPEN", response.status());

        verify(escrowService).freezeUnreleasedForDispute(DEAL_ID);

        ArgumentCaptor<Dispute> savedDispute = ArgumentCaptor.forClass(Dispute.class);
        verify(disputeRepository).save(savedDispute.capture());
        assertEquals(DisputeOpenerType.CREATOR, savedDispute.getValue().getOpenedByType());

        ArgumentCaptor<Collaboration> savedCollab = ArgumentCaptor.forClass(Collaboration.class);
        verify(collaborationRepository).save(savedCollab.capture());
        assertEquals(CollaborationStatus.DISPUTED, savedCollab.getValue().getStatus());
    }

    @Test
    @DisplayName("brand open: happy path")
    void brandOpenHappyPath() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(principal.getUserId()).thenReturn(BRAND_USER_ID);
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(inProgressDeal(CREATOR_USER_ID)));
        when(escrowService.hasFundedUnreleasedEscrow(DEAL_ID)).thenReturn(true);
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(DEAL_ID), any(Set.class)))
                .thenReturn(false);

        var response =
                service.openDispute(principal, DEAL_ID, new OpenDisputeRequest("Quality issues"));

        assertEquals("BRAND", response.openedByType());
        verify(escrowService).freezeUnreleasedForDispute(DEAL_ID);
        verify(disputeRepository).save(any(Dispute.class));
    }

    @Test
    @DisplayName("creator open: IDOR — foreign deal returns 404")
    void creatorOpenIdorForeignDeal() {
        when(principal.getUserType()).thenReturn(UserType.CREATOR);
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.openDispute(
                                        principal, DEAL_ID, new OpenDisputeRequest("Not my deal")));

        assertEquals("DEAL_NOT_FOUND", ex.getCode());
        verify(disputeRepository, never()).save(any());
        verify(escrowService, never()).freezeUnreleasedForDispute(any());
    }

    @Test
    @DisplayName("brand open: IDOR — foreign workspace deal returns 404")
    void brandOpenIdorForeignDeal() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.openDispute(
                                        principal, DEAL_ID, new OpenDisputeRequest("Wrong workspace")));

        assertEquals("DEAL_NOT_FOUND", ex.getCode());
        verify(escrowService, never()).freezeUnreleasedForDispute(any());
    }

    @Test
    @DisplayName("open: rejects when active dispute already exists")
    void openRejectsDuplicateActiveDispute() {
        when(principal.getUserType()).thenReturn(UserType.CREATOR);
        when(principal.getUserId()).thenReturn(OTHER_CREATOR_ID);
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, OTHER_CREATOR_ID))
                .thenReturn(Optional.of(inProgressDeal(OTHER_CREATOR_ID)));
        when(escrowService.hasFundedUnreleasedEscrow(DEAL_ID)).thenReturn(true);
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(DEAL_ID), any(Set.class)))
                .thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.openDispute(
                                        principal, DEAL_ID, new OpenDisputeRequest("Second dispute")));

        assertEquals("DISPUTE_ALREADY_OPEN", ex.getCode());
        verify(disputeRepository, never()).save(any());
        verify(escrowService, never()).freezeUnreleasedForDispute(any());
    }

    @Test
    @DisplayName("open: rejects when no funded unreleased escrow")
    void openRejectsNoFundedEscrow() {
        when(principal.getUserType()).thenReturn(UserType.CREATOR);
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(inProgressDeal(CREATOR_USER_ID)));
        when(escrowService.hasFundedUnreleasedEscrow(DEAL_ID)).thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.openDispute(
                                        principal, DEAL_ID, new OpenDisputeRequest("No escrow")));

        assertEquals("NO_FUNDED_ESCROW", ex.getCode());
        verify(disputeRepository, never()).save(any());
    }

    @Test
    @DisplayName("H-T34-1: open freezes escrow before dispute row is persisted")
    void openFreezesEscrowBeforeDisputeSave() {
        when(principal.getUserType()).thenReturn(UserType.CREATOR);
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(inProgressDeal(CREATOR_USER_ID)));
        when(escrowService.hasFundedUnreleasedEscrow(DEAL_ID)).thenReturn(true);
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(DEAL_ID), any(Set.class)))
                .thenReturn(false);

        service.openDispute(principal, DEAL_ID, new OpenDisputeRequest("Race fix ordering"));

        InOrder inOrder = inOrder(escrowService, disputeRepository);
        inOrder.verify(escrowService).freezeUnreleasedForDispute(DEAL_ID);
        inOrder.verify(disputeRepository).save(any(Dispute.class));
    }

    @Test
    void adminResolveHappyPath() {
        Dispute dispute =
                Dispute.open(
                        DISPUTE_ID,
                        DEAL_ID,
                        DisputeOpenerType.BRAND,
                        BRAND_USER_ID,
                        "Issue with deliverables");
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute));
        AdminUser admin = AdminUser.create(ADMIN_ID, "admin@test.com", "hash", AdminRole.ADMIN);
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenReturn(admin);
        EscrowStatusResponse settlement =
                new EscrowStatusResponse(
                        "01HESCROW1234567890AB",
                        WORKSPACE_ID,
                        null,
                        null,
                        new BigDecimal("10000.00"),
                        "INR",
                        com.influora.domain.enums.EscrowStatus.RELEASED,
                        null,
                        null);
        when(escrowService.adminReleaseForDispute(DEAL_ID)).thenReturn(List.of(settlement));

        var response =
                service.resolveDispute(
                        principal,
                        request,
                        DISPUTE_ID,
                        new ResolveDisputeRequest(DisputeStatus.RESOLVED_CREATOR, "Creator upheld", null));

        assertEquals("RESOLVED_CREATOR", response.status());
        verify(disputeRepository).saveAndFlush(dispute);
        assertEquals(DisputeStatus.RESOLVED_CREATOR, dispute.getStatus());
        assertEquals(ADMIN_ID, dispute.getResolvedByAdminId());
        verify(escrowService).adminReleaseForDispute(DEAL_ID);
        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("ESCROW_RELEASE"),
                        eq("ESCROW"),
                        eq("01HESCROW1234567890AB"),
                        any(),
                        any(),
                        any());
        // Finding #2 — unconditional top-level DISPUTE audit entry, independent of the ESCROW one.
        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("UPDATE"),
                        eq("DISPUTE"),
                        eq(DISPUTE_ID),
                        any(),
                        any(),
                        any());
    }

    @Test
    @DisplayName("admin resolve: RESOLVED_BRAND calls escrowService.adminRefundForDispute, not release")
    void adminResolveBrandRefundsEscrow() {
        Dispute dispute =
                Dispute.open(DISPUTE_ID, DEAL_ID, DisputeOpenerType.CREATOR, CREATOR_USER_ID, "Not delivered");
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute));
        AdminUser admin = AdminUser.create(ADMIN_ID, "admin@test.com", "hash", AdminRole.ADMIN);
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenReturn(admin);
        EscrowStatusResponse settlement =
                new EscrowStatusResponse(
                        "01HESCROW1234567890AB",
                        WORKSPACE_ID,
                        null,
                        null,
                        new BigDecimal("10000.00"),
                        "INR",
                        com.influora.domain.enums.EscrowStatus.REFUNDED,
                        null,
                        null);
        when(escrowService.adminRefundForDispute(DEAL_ID)).thenReturn(List.of(settlement));

        var response =
                service.resolveDispute(
                        principal,
                        request,
                        DISPUTE_ID,
                        new ResolveDisputeRequest(DisputeStatus.RESOLVED_BRAND, "Brand upheld", null));

        assertEquals("RESOLVED_BRAND", response.status());
        verify(escrowService).adminRefundForDispute(DEAL_ID);
        verify(escrowService, never()).adminReleaseForDispute(any());
        verify(escrowService, never()).adminSplitForDispute(any(), any());
        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("ESCROW_REFUND"),
                        eq("ESCROW"),
                        eq("01HESCROW1234567890AB"),
                        any(),
                        any(),
                        any());
    }

    @Test
    @DisplayName("admin resolve: RESOLVED_SPLIT forwards creatorSplitPercent to escrowService")
    void adminResolveSplitForwardsPercent() {
        Dispute dispute =
                Dispute.open(DISPUTE_ID, DEAL_ID, DisputeOpenerType.CREATOR, CREATOR_USER_ID, "Partial delivery");
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute));
        AdminUser admin = AdminUser.create(ADMIN_ID, "admin@test.com", "hash", AdminRole.ADMIN);
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenReturn(admin);
        BigDecimal splitPercent = new BigDecimal("60");
        EscrowStatusResponse settlement =
                new EscrowStatusResponse(
                        "01HESCROW1234567890AB",
                        WORKSPACE_ID,
                        null,
                        null,
                        new BigDecimal("6000.00"),
                        "INR",
                        com.influora.domain.enums.EscrowStatus.RELEASED,
                        null,
                        null);
        when(escrowService.adminSplitForDispute(DEAL_ID, splitPercent)).thenReturn(List.of(settlement));

        var response =
                service.resolveDispute(
                        principal,
                        request,
                        DISPUTE_ID,
                        new ResolveDisputeRequest(DisputeStatus.RESOLVED_SPLIT, "60/40 split", splitPercent));

        assertEquals("RESOLVED_SPLIT", response.status());
        verify(escrowService).adminSplitForDispute(DEAL_ID, splitPercent);
        verify(escrowService, never()).adminReleaseForDispute(any());
        verify(escrowService, never()).adminRefundForDispute(any());
    }

    @Test
    @DisplayName("admin resolve: escrow settlement happens BEFORE the dispute row is saved resolved")
    void adminResolveSettlesEscrowBeforeSavingDispute() {
        Dispute dispute =
                Dispute.open(DISPUTE_ID, DEAL_ID, DisputeOpenerType.BRAND, BRAND_USER_ID, "Issue");
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute));
        AdminUser admin = AdminUser.create(ADMIN_ID, "admin@test.com", "hash", AdminRole.ADMIN);
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenReturn(admin);
        EscrowStatusResponse settlement =
                new EscrowStatusResponse(
                        "01HESCROW1234567890AB",
                        WORKSPACE_ID,
                        null,
                        null,
                        new BigDecimal("10000.00"),
                        "INR",
                        com.influora.domain.enums.EscrowStatus.RELEASED,
                        null,
                        null);
        when(escrowService.adminReleaseForDispute(DEAL_ID)).thenReturn(List.of(settlement));

        service.resolveDispute(
                principal,
                request,
                DISPUTE_ID,
                new ResolveDisputeRequest(DisputeStatus.RESOLVED_CREATOR, "Creator upheld", null));

        InOrder inOrder = inOrder(escrowService, disputeRepository);
        inOrder.verify(escrowService).adminReleaseForDispute(DEAL_ID);
        inOrder.verify(disputeRepository).saveAndFlush(dispute);
    }

    @Test
    @DisplayName("admin resolve: rejects non-terminal resolution status")
    void adminResolveRejectsInvalidResolution() {
        AdminUser admin = AdminUser.create(ADMIN_ID, "admin@test.com", "hash", AdminRole.ADMIN);
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenReturn(admin);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.resolveDispute(
                                        principal,
                                        request,
                                        DISPUTE_ID,
                                        new ResolveDisputeRequest(DisputeStatus.OPEN, "Bad", null)));

        assertEquals("INVALID_RESOLUTION", ex.getCode());
        verify(disputeRepository, never()).save(any());
        verify(disputeRepository, never()).saveAndFlush(any());
        verify(escrowService, never()).adminReleaseForDispute(any());
        verify(escrowService, never()).adminRefundForDispute(any());
        verify(escrowService, never()).adminSplitForDispute(any(), any());
    }

    @Test
    @DisplayName(
            "Kabir H-1: concurrent resolve — stale @Version on saveAndFlush surfaces as a clean 409,"
                    + " not a raw 500")
    void concurrentResolveReturns409() {
        Dispute dispute =
                Dispute.open(DISPUTE_ID, DEAL_ID, DisputeOpenerType.BRAND, BRAND_USER_ID, "Issue");
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute));
        AdminUser admin = AdminUser.create(ADMIN_ID, "admin@test.com", "hash", AdminRole.ADMIN);
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenReturn(admin);
        EscrowStatusResponse settlement =
                new EscrowStatusResponse(
                        "01HESCROW1234567890AB",
                        WORKSPACE_ID,
                        null,
                        null,
                        new BigDecimal("10000.00"),
                        "INR",
                        com.influora.domain.enums.EscrowStatus.RELEASED,
                        null,
                        null);
        when(escrowService.adminReleaseForDispute(DEAL_ID)).thenReturn(List.of(settlement));
        // Simulates another admin's concurrent resolve having already committed and bumped the
        // row's version — this dispute's in-memory copy is now stale.
        when(disputeRepository.saveAndFlush(dispute))
                .thenThrow(new ObjectOptimisticLockingFailureException(Dispute.class, DISPUTE_ID));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.resolveDispute(
                                        principal,
                                        request,
                                        DISPUTE_ID,
                                        new ResolveDisputeRequest(
                                                DisputeStatus.RESOLVED_CREATOR, "Creator upheld", null)));

        assertEquals("DISPUTE_RESOLVE_CONFLICT", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        // The unconditional top-level DISPUTE audit entry must not fire for a resolve that never
        // durably committed.
        verify(adminAuditLogService, never())
                .record(any(), any(), any(), eq("DISPUTE"), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "Kabir H-2: empty settlement list (nothing left to move) still writes the unconditional"
                    + " top-level DISPUTE audit entry")
    void emptySettlementResolveStillWritesAudit() {
        Dispute dispute =
                Dispute.open(DISPUTE_ID, DEAL_ID, DisputeOpenerType.BRAND, BRAND_USER_ID, "Issue");
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute));
        AdminUser admin = AdminUser.create(ADMIN_ID, "admin@test.com", "hash", AdminRole.ADMIN);
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenReturn(admin);
        // Nothing left to settle (e.g. already-moved elsewhere) — the pre-fix code wrote ZERO
        // audit_log rows in this case.
        when(escrowService.adminReleaseForDispute(DEAL_ID)).thenReturn(List.of());

        var response =
                service.resolveDispute(
                        principal,
                        request,
                        DISPUTE_ID,
                        new ResolveDisputeRequest(DisputeStatus.RESOLVED_CREATOR, "Creator upheld", null));

        assertEquals("RESOLVED_CREATOR", response.status());
        verify(disputeRepository).saveAndFlush(dispute);
        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("UPDATE"),
                        eq("DISPUTE"),
                        eq(DISPUTE_ID),
                        any(),
                        any(),
                        any());
        // No ESCROW entries since settlements was empty.
        verify(adminAuditLogService, never())
                .record(any(), any(), any(), eq("ESCROW"), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "Low finding: already-resolved dispute short-circuits to a clean 409, never touches"
                    + " escrow")
    void alreadyResolvedDisputeReturnsCleanConflict() {
        Dispute dispute =
                Dispute.open(DISPUTE_ID, DEAL_ID, DisputeOpenerType.BRAND, BRAND_USER_ID, "Issue");
        dispute.resolve(DisputeStatus.RESOLVED_BRAND, "01HOTHERADMIN12345678", "Already handled");
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute));
        AdminUser admin = AdminUser.create(ADMIN_ID, "admin@test.com", "hash", AdminRole.ADMIN);
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenReturn(admin);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.resolveDispute(
                                        principal,
                                        request,
                                        DISPUTE_ID,
                                        new ResolveDisputeRequest(
                                                DisputeStatus.RESOLVED_CREATOR, "Second attempt", null)));

        assertEquals("DISPUTE_ALREADY_RESOLVED", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(escrowService, never()).adminReleaseForDispute(any());
        verify(escrowService, never()).adminRefundForDispute(any());
        verify(escrowService, never()).adminSplitForDispute(any(), any());
        verify(disputeRepository, never()).saveAndFlush(any());
    }
}
