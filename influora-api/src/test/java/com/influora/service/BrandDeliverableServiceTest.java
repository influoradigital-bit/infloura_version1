package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.R2Properties;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.DeliverableDetailResponse;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.ReviewResponse;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.ReviseRequest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Week 3 P0 Task #21 — brand deliverable approve/revise unit tests. */
@ExtendWith(MockitoExtension.class)
class BrandDeliverableServiceTest {

    private static final String DELIVERABLE_ID = "01HDELIVERABLE1234567";
    private static final String WORKSPACE_ID = "01HWORKSPACE123456789";
    private static final String COLLAB_ID = "01HCOLLAB12345678901";

    @Mock private BrandContextService brandContext;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private R2StorageService r2StorageService;
    @Mock private R2Properties r2Properties;
    @Mock private AuthPrincipal principal;
    @Mock private EscrowService escrowService;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;
    @Mock private com.influora.service.meera.MeeraInteractionLogService meeraInteractionLogService;
    @Mock private CollaborationRepository collaborationRepository;

    private BrandDeliverableService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service =
                new BrandDeliverableService(
                        brandContext,
                        deliverableRepository,
                        r2StorageService,
                        r2Properties,
                        escrowService,
                        collaborationLifecycleService,
                        meeraInteractionLogService,
                        collaborationRepository);
        workspace = Workspace.newBrand(WORKSPACE_ID, "Acme Brand", "acme", "Fashion", "SMB");
    }

    /** CR-22a — approve()'s new CollaborationStatus guard needs a resolvable, non-CANCELLED row. */
    private static Collaboration activeCollaboration() {
        return Collaboration.invite(COLLAB_ID, "01HCAMPAIGN0000000001", "01HCREATOR00000000001", null, "INR");
    }

    private void stubActiveCollaboration() {
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(java.util.Optional.of(activeCollaboration()));
    }

    private static Deliverable submittedDeliverable() {
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .slotIndex(1)
                        .type(DeliverableType.INSTAGRAM_REEL)
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.DRAFT)
                        .build();
        deliverable.applyUpload(
                1,
                "[{\"id\":\"f1\",\"fileType\":\"VIDEO\",\"fileName\":\"reel.mp4\",\"url\":\"https://x\"}]",
                "Caption",
                null,
                null);
        deliverable.applySubmit(null, null, null, DeliverableStatus.SUBMITTED);
        return deliverable;
    }

    @Test
    @DisplayName("approve: SUBMITTED transitions to APPROVED and sets approvedAt")
    void testApproveSubmitted() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverable();
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));
        stubActiveCollaboration();

        ReviewResponse response = service.approve(principal, DELIVERABLE_ID);

        assertEquals(DeliverableStatus.APPROVED, response.status());

        ArgumentCaptor<Deliverable> saved = ArgumentCaptor.forClass(Deliverable.class);
        verify(deliverableRepository).save(saved.capture());
        assertEquals(DeliverableStatus.APPROVED, saved.getValue().getStatus());
        assertNotNull(saved.getValue().getApprovedAt());
        assertNotNull(saved.getValue().getReviewedAt());
    }

    // --- B3: approve -> escrow release attempt wiring ---

    private static Deliverable submittedDeliverableWithMilestone(String milestoneId) {
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .milestoneId(milestoneId)
                        .slotIndex(1)
                        .type(DeliverableType.INSTAGRAM_REEL)
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.DRAFT)
                        .build();
        deliverable.applyUpload(
                1,
                "[{\"id\":\"f1\",\"fileType\":\"VIDEO\",\"fileName\":\"reel.mp4\",\"url\":\"https://x\"}]",
                "Caption",
                null,
                null);
        deliverable.applySubmit(null, null, null, DeliverableStatus.SUBMITTED);
        return deliverable;
    }

    @Test
    @DisplayName("approve: attempts an escrow release for the deliverable's linked milestone")
    void testApproveAttemptsEscrowRelease() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        String milestoneId = "01HMILESTONE1234567AB";
        Deliverable deliverable = submittedDeliverableWithMilestone(milestoneId);
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));
        stubActiveCollaboration();
        when(escrowService.tryReleaseOnApproval(WORKSPACE_ID, milestoneId)).thenReturn(true);

        ReviewResponse response = service.approve(principal, DELIVERABLE_ID);

        assertEquals(DeliverableStatus.APPROVED, response.status());
        verify(escrowService).tryReleaseOnApproval(WORKSPACE_ID, milestoneId);
    }

    @Test
    @DisplayName("approve: still succeeds when the deliverable has no linked milestone (release attempt is a no-op)")
    void testApproveWithoutMilestoneStillSucceeds() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverable(); // no milestoneId set
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));
        stubActiveCollaboration();
        when(escrowService.tryReleaseOnApproval(WORKSPACE_ID, null)).thenReturn(false);

        ReviewResponse response = service.approve(principal, DELIVERABLE_ID);

        assertEquals(DeliverableStatus.APPROVED, response.status());
        verify(escrowService).tryReleaseOnApproval(WORKSPACE_ID, null);
    }

    @Test
    @DisplayName("approve: an unexpected escrow-release failure propagates (rolls back the approval too)")
    void testApproveRollsBackWhenEscrowReleaseFailsUnexpectedly() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        String milestoneId = "01HMILESTONE1234567AB";
        Deliverable deliverable = submittedDeliverableWithMilestone(milestoneId);
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));
        stubActiveCollaboration();
        when(escrowService.tryReleaseOnApproval(WORKSPACE_ID, milestoneId))
                .thenThrow(
                        new ApiException(
                                "WALLET_NOT_FOUND",
                                "Unexpected wallet failure",
                                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.approve(principal, DELIVERABLE_ID));

        assertEquals("WALLET_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("approve: RESUBMITTED transitions to APPROVED")
    void testApproveResubmitted() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverable();
        deliverable.applySubmit(null, null, null, DeliverableStatus.RESUBMITTED);
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));
        stubActiveCollaboration();

        ReviewResponse response = service.approve(principal, DELIVERABLE_ID);

        assertEquals(DeliverableStatus.APPROVED, response.status());
        verify(deliverableRepository).save(any());
    }

    /**
     * CR-22a, Kabir finding #1 — approve() fires tryReleaseOnApproval, real money leaving the
     * clearing wallet, and previously had zero CollaborationStatus awareness.
     */
    @Test
    @DisplayName("approve: rejects with 409 COLLABORATION_CANCELLED when the collaboration was cancelled")
    void testApproveRejectsCancelledCollaboration() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverable();
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));
        Collaboration cancelled = activeCollaboration();
        cancelled.transitionTo(CollaborationStatus.CANCELLED);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(java.util.Optional.of(cancelled));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.approve(principal, DELIVERABLE_ID));

        assertEquals("COLLABORATION_CANCELLED", ex.getCode());
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatus());
        verify(deliverableRepository, never()).save(any());
        verify(escrowService, never()).tryReleaseOnApproval(any(), any());
    }

    @Test
    @DisplayName("approve: foreign workspace deliverable returns 404")
    void testApproveForeignDeliverable() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.empty());

        ApiException ex =
                assertThrows(ApiException.class, () -> service.approve(principal, DELIVERABLE_ID));

        assertEquals("DELIVERABLE_NOT_FOUND", ex.getCode());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("approve: rejects DRAFT state")
    void testApproveInvalidState() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverable();
        deliverable.applyUpload(1, "[{\"id\":\"f1\"}]", null, null, null);
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.approve(principal, DELIVERABLE_ID));

        assertEquals("INVALID_STATE", ex.getCode());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("revise: SUBMITTED transitions to REVISION_REQUESTED, increments revisionCount")
    void testReviseSubmitted() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverable();
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));

        ReviewResponse response =
                service.requestRevision(
                        principal, DELIVERABLE_ID, new ReviseRequest("Please fix lighting"));

        assertEquals(DeliverableStatus.REVISION_REQUESTED, response.status());

        ArgumentCaptor<Deliverable> saved = ArgumentCaptor.forClass(Deliverable.class);
        verify(deliverableRepository).save(saved.capture());
        assertEquals(DeliverableStatus.REVISION_REQUESTED, saved.getValue().getStatus());
        assertEquals("Please fix lighting", saved.getValue().getReviewNotes());
        assertEquals(1, saved.getValue().getRevisionCount());
        assertNotNull(saved.getValue().getReviewedAt());
    }

    @Test
    @DisplayName("revise: RESUBMITTED increments existing revisionCount")
    void testReviseResubmitted() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverable();
        deliverable.applyRevision("First round feedback");
        deliverable.applySubmit(null, null, null, DeliverableStatus.RESUBMITTED);
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));

        ReviewResponse response =
                service.requestRevision(
                        principal, DELIVERABLE_ID, new ReviseRequest("Second round tweaks"));

        assertEquals(DeliverableStatus.REVISION_REQUESTED, response.status());

        ArgumentCaptor<Deliverable> saved = ArgumentCaptor.forClass(Deliverable.class);
        verify(deliverableRepository).save(saved.capture());
        assertEquals(2, saved.getValue().getRevisionCount());
        assertEquals("Second round tweaks", saved.getValue().getReviewNotes());
    }

    @Test
    @DisplayName("revise: strips script tags from brand feedback before persistence")
    void testReviseStripsXssFeedback() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverable();
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));

        service.requestRevision(
                principal,
                DELIVERABLE_ID,
                new ReviseRequest("<img onerror=alert(1)>Fix the hook"));

        ArgumentCaptor<Deliverable> saved = ArgumentCaptor.forClass(Deliverable.class);
        verify(deliverableRepository).save(saved.capture());
        assertEquals("Fix the hook", saved.getValue().getReviewNotes());
    }

    @Test
    @DisplayName("revise: blank feedback returns 400")
    void testReviseMissingFeedback() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(submittedDeliverable()));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.requestRevision(
                                        principal, DELIVERABLE_ID, new ReviseRequest("  ")));

        assertEquals("INVALID_REQUEST", ex.getCode());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("revise: foreign workspace deliverable returns 404")
    void testReviseForeignDeliverable() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.requestRevision(
                                        principal,
                                        DELIVERABLE_ID,
                                        new ReviseRequest("Please adjust caption")));

        assertEquals("DELIVERABLE_NOT_FOUND", ex.getCode());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("revise: rejects APPROVED state")
    void testReviseInvalidState() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverable();
        deliverable.applyApprove();
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.requestRevision(
                                        principal,
                                        DELIVERABLE_ID,
                                        new ReviseRequest("Too late")));

        assertEquals("INVALID_STATE", ex.getCode());
        verify(deliverableRepository, never()).save(any());
    }

    // --- B6: reject — terminal REJECTED path ---

    @Test
    @DisplayName("reject: SUBMITTED transitions to REJECTED and stores feedback")
    void testRejectSubmitted() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        // D-9/CR-22a — reject() gained the same requireNotCancelled(collaborationId) guard
        // approve() already had (BrandDeliverableService.java:192), but this test was never
        // updated to stub the collaboration lookup that guard performs, so it fell through
        // Mockito's default Optional.empty() and threw "Collaboration not found" instead of
        // exercising the reject path this test is actually about.
        stubActiveCollaboration();
        Deliverable deliverable = submittedDeliverable();
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));

        ReviewResponse response =
                service.reject(principal, DELIVERABLE_ID, new ReviseRequest("Not aligned with brief"));

        assertEquals(DeliverableStatus.REJECTED, response.status());

        ArgumentCaptor<Deliverable> saved = ArgumentCaptor.forClass(Deliverable.class);
        verify(deliverableRepository).save(saved.capture());
        assertEquals(DeliverableStatus.REJECTED, saved.getValue().getStatus());
        assertEquals("Not aligned with brief", saved.getValue().getReviewNotes());
        assertNotNull(saved.getValue().getReviewedAt());
    }

    @Test
    @DisplayName("reject: blank feedback returns 400")
    void testRejectMissingFeedback() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(submittedDeliverable()));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reject(principal, DELIVERABLE_ID, new ReviseRequest("  ")));

        assertEquals("INVALID_REQUEST", ex.getCode());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("reject: rejects APPROVED state")
    void testRejectInvalidState() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverable();
        deliverable.applyApprove();
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reject(principal, DELIVERABLE_ID, new ReviseRequest("Too late")));

        assertEquals("INVALID_STATE", ex.getCode());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("reject: foreign workspace deliverable returns 404")
    void testRejectForeignDeliverable() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reject(principal, DELIVERABLE_ID, new ReviseRequest("Nope")));

        assertEquals("DELIVERABLE_NOT_FOUND", ex.getCode());
        verify(deliverableRepository, never()).save(any());
    }

    // --- DPF-1: GET /deliverables/{id} — brand file-view endpoint ---

    private static Deliverable submittedDeliverableWithRawR2Key() {
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .slotIndex(1)
                        .type(DeliverableType.INSTAGRAM_REEL)
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.DRAFT)
                        .build();
        // Real uploads persist raw R2 object keys (never public URLs) — see
        // CreatorDeliverableService#uploadFile javadoc. Rematerialized via presignGet.
        deliverable.applyUpload(
                1,
                "[{\"id\":\"f1\",\"fileType\":\"VIDEO\",\"fileName\":\"reel.mp4\","
                        + "\"url\":\"deliverables/"
                        + COLLAB_ID
                        + "/v1/reel-abc.mp4\",\"fileSize\":123}]",
                "Caption",
                null,
                null);
        deliverable.applySubmit(null, null, null, DeliverableStatus.SUBMITTED);
        return deliverable;
    }

    @Test
    @DisplayName(
            "getDetail: brand in the correct workspace can fetch deliverable with presigned file"
                    + " URLs")
    void testGetDetailReturnsFilesWithPresignedUrls() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverableWithRawR2Key();
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));
        when(r2StorageService.isAvailable()).thenReturn(true);
        String rawKey = "deliverables/" + COLLAB_ID + "/v1/reel-abc.mp4";
        when(r2StorageService.presignGet(rawKey))
                .thenReturn(
                        new R2StorageService.PresignResult(
                                "https://r2.example.com/signed?sig=abc123",
                                rawKey,
                                "bucket",
                                Instant.now().plusSeconds(300),
                                0));

        DeliverableDetailResponse response = service.getDetail(principal, DELIVERABLE_ID);

        assertEquals(DELIVERABLE_ID, response.id());
        assertEquals(DeliverableStatus.SUBMITTED, response.status());
        assertEquals(1, response.files().size());
        // (c) presigned URL is actually present in the response — not the raw stored key.
        assertEquals(
                "https://r2.example.com/signed?sig=abc123", response.files().get(0).url());
        assertEquals("VIDEO", response.files().get(0).fileType());
        assertEquals(true, response.canApprove());
        assertEquals(true, response.canRequestRevision());
    }

    @Test
    @DisplayName(
            "getDetail: brand in a DIFFERENT workspace gets DELIVERABLE_NOT_FOUND (IDOR guard —"
                    + " no cross-tenant leak)")
    void testGetDetailForeignWorkspaceRejected() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        // Cross-tenant probe: repository is scoped by WORKSPACE_ID, so a deliverable owned by a
        // different workspace never matches — must return empty, never the raw row.
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.getDetail(principal, DELIVERABLE_ID));

        assertEquals("DELIVERABLE_NOT_FOUND", ex.getCode());
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatus());
        // No file resolution/presign attempt should happen for a deliverable that was never
        // resolved.
        verify(r2StorageService, never()).presignGet(any());
    }

    @Test
    @DisplayName("getDetail: falls back to stored value when R2 is unavailable (no presign call)")
    void testGetDetailFallsBackWhenR2Unavailable() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        Deliverable deliverable = submittedDeliverableWithRawR2Key();
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(java.util.Optional.of(deliverable));
        when(r2StorageService.isAvailable()).thenReturn(false);

        DeliverableDetailResponse response = service.getDetail(principal, DELIVERABLE_ID);

        assertEquals(
                "deliverables/" + COLLAB_ID + "/v1/reel-abc.mp4", response.files().get(0).url());
        verify(r2StorageService, never()).presignGet(any());
    }
}
