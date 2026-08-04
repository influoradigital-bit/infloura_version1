package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.R2Properties;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.DeliverableMetric;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.security.NoOpMalwareScanService;
import org.springframework.context.ApplicationEventPublisher;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableListItem;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableStatusResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MarkPostedRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MarkPostedResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MetricsPayload;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MetricsReportRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MetricsReportResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.SubmitRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.SubmitResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.UploadResponse;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** Week 3 P0 — creator deliverable upload + status unit tests. */
@ExtendWith(MockitoExtension.class)
class CreatorDeliverableServiceTest {

    private static final String DELIVERABLE_ID = "01HDELIVERABLE1234567";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String COLLAB_ID = "01HCOLLAB12345678901";
    private static final String MILESTONE_ID = "01HMILESTONE1234567890";

    /** Minimal ISO-BMFF ftyp box — passes Kabir M-19-1 magic-byte sniffing. */
    private static final byte[] MINIMAL_MP4 =
            new byte[] {
                0x00, 0x00, 0x00, 0x1c, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d, 0x00, 0x00,
                0x00, 0x00
            };

    @Mock private CreatorContextService creatorContext;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private DeliverableMetricRepository deliverableMetricRepository;
    @Mock private R2StorageService r2StorageService;
    @Mock private AuthPrincipal principal;
    @Mock private CampaignRepository campaignRepository;
    @Mock private BrandContextService brandContext;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;
    @Mock private com.influora.service.verification.DeliverableVerificationService verificationService;
    @Mock private com.influora.repository.MetaOAuthTokenRepository metaOAuthTokenRepository;

    private CreatorDeliverableService service;
    private CreatorProfile profile;

    @BeforeEach
    void setUp() {
        R2Properties r2Properties = new R2Properties();
        r2Properties.setMaxVideoBytes(524_288_000L);
        service =
                new CreatorDeliverableService(
                        creatorContext,
                        collaborationRepository,
                        deliverableRepository,
                        deliverableMetricRepository,
                        r2StorageService,
                        r2Properties,
                        new NoOpMalwareScanService(),
                        campaignRepository,
                        brandContext,
                        eventPublisher,
                        collaborationLifecycleService,
                        verificationService,
                        metaOAuthTokenRepository);
        profile = CreatorProfile.newForUser("profile1", CREATOR_USER_ID, "Test Creator");
        lenient().when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
    }

    private static Deliverable pendingDeliverable() {
        return Deliverable.builder()
                .id(DELIVERABLE_ID)
                .collaborationId(COLLAB_ID)
                .creatorProfileId("profile1")
                .slotIndex(1)
                .type(DeliverableType.INSTAGRAM_REEL)
                .title("Workout Reel 1")
                .status(DeliverableStatus.PENDING)
                .build();
    }

    @Test
    @DisplayName("upload: happy path creates version 1, sets DRAFT, stores files in R2")
    void testUploadHappyPath() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = pendingDeliverable();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));
        when(r2StorageService.isAvailable()).thenReturn(true);

        MultipartFile video =
                new MockMultipartFile(
                        "files",
                        "reel.mp4",
                        "video/mp4",
                        MINIMAL_MP4);

        UploadResponse response =
                service.uploadContent(principal, DELIVERABLE_ID, List.of(video), null, "Caption", List.of("#fit"), "Notes");

        assertEquals(1, response.versionNumber());
        assertEquals(DeliverableStatus.DRAFT, response.status());
        assertEquals(1, response.files().size());
        assertEquals("reel.mp4", response.files().get(0).fileName());
        assertNotNull(response.versionId());

        ArgumentCaptor<Deliverable> saved = ArgumentCaptor.forClass(Deliverable.class);
        verify(deliverableRepository).save(saved.capture());
        assertEquals(DeliverableStatus.DRAFT, saved.getValue().getStatus());
        assertEquals(1, saved.getValue().getVersionNumber());
        assertEquals("Caption", saved.getValue().getCaption());
    }

    @Test
    @DisplayName("upload: rejects foreign deliverable with 404")
    void testUploadForeignDeliverable() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        MultipartFile video =
                new MockMultipartFile("files", "reel.mp4", "video/mp4", MINIMAL_MP4);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.uploadContent(
                                        principal, DELIVERABLE_ID, List.of(video), null, null, null, null));

        assertEquals("DELIVERABLE_NOT_FOUND", ex.getCode());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("upload: rejects SUBMITTED state")
    void testUploadInvalidState() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable submitted =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.SUBMITTED)
                        .build();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(submitted));

        MultipartFile video =
                new MockMultipartFile("files", "reel.mp4", "video/mp4", MINIMAL_MP4);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.uploadContent(
                                        principal, DELIVERABLE_ID, List.of(video), null, null, null, null));

        assertEquals("INVALID_STATE", ex.getCode());
    }

    @Test
    @DisplayName("upload: rejects invalid MIME type")
    void testUploadInvalidMime() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(pendingDeliverable()));
        when(r2StorageService.isAvailable()).thenReturn(true);

        MultipartFile bad =
                new MockMultipartFile("files", "evil.exe", "application/x-msdownload", new byte[] {1});

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.uploadContent(
                                        principal, DELIVERABLE_ID, List.of(bad), null, null, null, null));

        assertEquals("INVALID_FILE_TYPE", ex.getCode());
    }

    @Test
    @DisplayName("upload: rejects MIME spoof (video/mp4 header with ZIP bytes)")
    void testUploadMimeSpoofRejected() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(pendingDeliverable()));
        when(r2StorageService.isAvailable()).thenReturn(true);

        byte[] zipPayload = new byte[] {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00};
        MultipartFile spoofed =
                new MockMultipartFile("files", "payload.zip", "video/mp4", zipPayload);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.uploadContent(
                                        principal, DELIVERABLE_ID, List.of(spoofed), null, null, null, null));

        assertEquals("INVALID_FILE_TYPE", ex.getCode());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("upload: increments version on revision")
    void testUploadNewVersionAfterRevision() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.REVISION_REQUESTED)
                        .build();
        deliverable.applyUpload(1, "[{\"id\":\"f1\"}]", "v1", null, null);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));
        when(r2StorageService.isAvailable()).thenReturn(true);

        MultipartFile video =
                new MockMultipartFile("files", "v2.mp4", "video/mp4", MINIMAL_MP4);

        UploadResponse response =
                service.uploadContent(principal, DELIVERABLE_ID, List.of(video), null, "v2 caption", null, null);

        assertEquals(2, response.versionNumber());
        assertEquals(DeliverableStatus.DRAFT, response.status());
    }

    @Test
    @DisplayName("getStatus: returns current version and action flags")
    void testGetStatus() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = pendingDeliverable();
        deliverable.applyUpload(
                1,
                "[{\"id\":\"f1\",\"fileType\":\"VIDEO\",\"fileName\":\"reel.mp4\",\"url\":\"https://x\",\"thumbnailUrl\":null,\"fileSize\":100}]",
                "Caption",
                "[\"#tag\"]",
                "Notes");
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));

        DeliverableStatusResponse status = service.getStatus(principal, DELIVERABLE_ID);

        assertEquals(DELIVERABLE_ID, status.id());
        assertEquals(DeliverableStatus.DRAFT, status.status());
        assertEquals(1, status.versionNumber());
        assertEquals(1, status.files().size());
        assertEquals(true, status.actions().canSubmit());
        assertEquals(true, status.actions().canUploadNewVersion());
    }

    @Test
    @DisplayName("getStatus: foreign deliverable 404")
    void testGetStatusForeign() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(ApiException.class, () -> service.getStatus(principal, DELIVERABLE_ID));

        assertEquals("DELIVERABLE_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("listForCollaboration: returns slot-ordered picker rows for owned deal")
    void testListForCollaborationHappyPath() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(collaborationRepository.findByIdAndCreatorId(COLLAB_ID, CREATOR_USER_ID))
                .thenReturn(
                        Optional.of(
                                Collaboration.invite(
                                        COLLAB_ID, "01HCAMPAIGN123456789A", CREATOR_USER_ID, null, "INR")));
        Deliverable slot1 = pendingDeliverable();
        Deliverable slot2 =
                Deliverable.builder()
                        .id("01HDELIVERABLE2234567")
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .slotIndex(2)
                        .type(DeliverableType.INSTAGRAM_REEL)
                        .title("Workout Reel 2")
                        .status(DeliverableStatus.APPROVED)
                        .build();
        when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(COLLAB_ID))
                .thenReturn(List.of(slot1, slot2));

        List<DeliverableListItem> items = service.listForCollaboration(principal, COLLAB_ID);

        assertEquals(2, items.size());
        assertEquals(DELIVERABLE_ID, items.get(0).id());
        assertEquals(false, items.get(0).completed());
        assertEquals("01HDELIVERABLE2234567", items.get(1).id());
        assertEquals(true, items.get(1).completed());
    }

    @Test
    @DisplayName("listForCollaboration: foreign deal returns 404")
    void testListForCollaborationForeignDeal() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(collaborationRepository.findByIdAndCreatorId(COLLAB_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.listForCollaboration(principal, COLLAB_ID));

        assertEquals("DEAL_NOT_FOUND", ex.getCode());
        verify(deliverableRepository, never()).findByCollaborationIdOrderBySlotIndexAsc(any());
    }

    @Test
    @DisplayName("listForCollaboration: blank collaboration_id returns 400")
    void testListForCollaborationMissingParam() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.listForCollaboration(principal, "  "));

        assertEquals("INVALID_REQUEST", ex.getCode());
        verify(collaborationRepository, never()).findByIdAndCreatorId(any(), any());
    }

    /** CR-22a — submitForReview's new CollaborationStatus guard needs a resolvable, non-CANCELLED row. */
    private void stubActiveCollaboration() {
        when(collaborationRepository.findById(COLLAB_ID))
                .thenReturn(
                        Optional.of(
                                Collaboration.invite(
                                        COLLAB_ID, "01HCAMPAIGN123456789A", CREATOR_USER_ID, null, "INR")));
    }

    @Test
    @DisplayName("submit: DRAFT with files transitions to SUBMITTED")
    void testSubmitHappyPath() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = pendingDeliverable();
        deliverable.applyUpload(
                1,
                "[{\"id\":\"f1\",\"fileType\":\"VIDEO\",\"fileName\":\"reel.mp4\",\"url\":\"https://x\"}]",
                "Draft caption",
                null,
                null);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));
        stubActiveCollaboration();

        SubmitResponse response =
                service.submitForReview(
                        principal,
                        DELIVERABLE_ID,
                        new SubmitRequest("Final caption", List.of("#fit"), "Ready for review"));

        assertEquals(DELIVERABLE_ID, response.deliverableId());
        assertEquals(DeliverableStatus.SUBMITTED, response.status());
        assertEquals("Submitted for brand review", response.message());

        ArgumentCaptor<Deliverable> saved = ArgumentCaptor.forClass(Deliverable.class);
        verify(deliverableRepository).save(saved.capture());
        assertEquals(DeliverableStatus.SUBMITTED, saved.getValue().getStatus());
        assertEquals("Final caption", saved.getValue().getCaption());
        assertEquals("Ready for review", saved.getValue().getCreatorNotes());
        assertNotNull(saved.getValue().getSubmittedAt());
    }

    @Test
    @DisplayName("submit: strips script tags from caption, notes, and hashtags")
    void testSubmitStripsXss() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.DRAFT)
                        .build();
        deliverable.applyUpload(
                1,
                "[{\"id\":\"f1\",\"fileType\":\"VIDEO\",\"fileName\":\"reel.mp4\",\"url\":\"https://x\"}]",
                "Draft caption",
                null,
                null);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));
        stubActiveCollaboration();

        service.submitForReview(
                principal,
                DELIVERABLE_ID,
                new SubmitRequest(
                        "<script>x</script>Caption",
                        List.of("<b>#tag</b>", "#clean"),
                        "<svg onload=alert(1)>Notes"));

        ArgumentCaptor<Deliverable> saved = ArgumentCaptor.forClass(Deliverable.class);
        verify(deliverableRepository).save(saved.capture());
        assertEquals("Caption", saved.getValue().getCaption());
        assertEquals("Notes", saved.getValue().getCreatorNotes());
        assertEquals("[\"#tag\",\"#clean\"]", saved.getValue().getHashtagsJson());
    }

    @Test
    @DisplayName("submit: revisionCount > 0 transitions to RESUBMITTED")
    void testSubmitResubmitted() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.DRAFT)
                        .revisionCount(1)
                        .build();
        deliverable.applyUpload(2, "[{\"id\":\"f2\"}]", "v2", null, null);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));
        stubActiveCollaboration();

        SubmitResponse response =
                service.submitForReview(principal, DELIVERABLE_ID, new SubmitRequest(null, null, null));

        assertEquals(DeliverableStatus.RESUBMITTED, response.status());
        assertEquals("Resubmitted for brand review", response.message());
    }

    @Test
    @DisplayName("submit: REVISION_REQUESTED with files is allowed")
    void testSubmitFromRevisionRequested() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.REVISION_REQUESTED)
                        .filesJson("[{\"id\":\"f1\"}]")
                        .build();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));
        stubActiveCollaboration();

        SubmitResponse response =
                service.submitForReview(principal, DELIVERABLE_ID, null);

        assertEquals(DeliverableStatus.SUBMITTED, response.status());
    }

    /**
     * CR-22a, Kabir finding #1 — a CANCELLED collaboration must not accept new deliverable
     * submissions. Not on the money-moving path itself (only #approve releases funds), but a
     * creator continuing to "work" a dead deal is exactly the confusion the ruling calls out.
     */
    @Test
    @DisplayName("submit: rejects with 409 COLLABORATION_CANCELLED when the collaboration was cancelled")
    void testSubmitRejectsCancelledCollaboration() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = pendingDeliverable();
        deliverable.applyUpload(
                1,
                "[{\"id\":\"f1\",\"fileType\":\"VIDEO\",\"fileName\":\"reel.mp4\",\"url\":\"https://x\"}]",
                "Draft caption",
                null,
                null);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));
        Collaboration cancelled =
                Collaboration.invite(COLLAB_ID, "01HCAMPAIGN123456789A", CREATOR_USER_ID, null, "INR");
        cancelled.transitionTo(com.influora.domain.enums.CollaborationStatus.CANCELLED);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(cancelled));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.submitForReview(
                                        principal,
                                        DELIVERABLE_ID,
                                        new SubmitRequest("Final caption", null, null)));

        assertEquals("COLLABORATION_CANCELLED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("submit: rejects when no files uploaded")
    void testSubmitNoFiles() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = pendingDeliverable();
        deliverable.applyUpload(1, "[]", null, null, null);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));
        stubActiveCollaboration();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.submitForReview(principal, DELIVERABLE_ID, null));

        assertEquals("NO_CONTENT", ex.getCode());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("submit: rejects SUBMITTED state")
    void testSubmitInvalidState() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.SUBMITTED)
                        .filesJson("[{\"id\":\"f1\"}]")
                        .build();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.submitForReview(principal, DELIVERABLE_ID, null));

        assertEquals("INVALID_STATE", ex.getCode());
    }

    @Test
    @DisplayName("submit: foreign deliverable 404")
    void testSubmitForeignDeliverable() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.submitForReview(principal, DELIVERABLE_ID, null));

        assertEquals("DELIVERABLE_NOT_FOUND", ex.getCode());
    }

    private static MetricsReportRequest sampleMetricsRequest() {
        // M-24-1 / #40 — proof keys must be ownership-bound: proof/{userId}/{deliverableId}/{object}
        String proofA =
                "proof/" + CREATOR_USER_ID + "/" + DELIVERABLE_ID + "/scrxxx01.png";
        String proofB =
                "proof/" + CREATOR_USER_ID + "/" + DELIVERABLE_ID + "/scryyy02.png";
        return new MetricsReportRequest(
                new MetricsPayload(15000, 450, 230, 125000, 95000, 180000, 1200),
                List.of(proofA, proofB),
                7);
    }

    private Deliverable postedDeliverable() {
        return Deliverable.builder()
                .id(DELIVERABLE_ID)
                .collaborationId(COLLAB_ID)
                .creatorProfileId("profile1")
                .milestoneId(MILESTONE_ID)
                .slotIndex(1)
                .type(DeliverableType.INSTAGRAM_REEL)
                .title("Workout Reel 1")
                .status(DeliverableStatus.POSTED)
                .build();
    }

    @Test
    @DisplayName("reportMetrics: POSTED deliverable transitions to METRICS_REPORTED")
    void testReportMetricsFromPosted() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = postedDeliverable();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));
        when(deliverableMetricRepository.findByMilestoneId(MILESTONE_ID)).thenReturn(Optional.empty());
        when(deliverableMetricRepository.save(any(DeliverableMetric.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // verified-analytics-0804: manual self-report is only accepted when Meta verification fails.
        when(verificationService.verify(any()))
                .thenReturn(
                        com.influora.service.verification.DeliverableVerificationService.Outcome
                                .FALLBACK_API_ERROR);

        MetricsReportResponse response =
                service.reportMetrics(principal, DELIVERABLE_ID, sampleMetricsRequest());

        assertEquals(DELIVERABLE_ID, response.deliverableId());
        assertEquals(DeliverableStatus.METRICS_REPORTED, response.status());
        assertEquals(16.3, response.engagementRate());
        assertEquals("PENDING", response.verificationStatus());
        assertEquals("Metrics submitted. They will be verified.", response.message());

        ArgumentCaptor<Deliverable> saved = ArgumentCaptor.forClass(Deliverable.class);
        verify(deliverableRepository).save(saved.capture());
        assertEquals(DeliverableStatus.METRICS_REPORTED, saved.getValue().getStatus());
        verify(deliverableMetricRepository).save(any(DeliverableMetric.class));
    }

    @Test
    @DisplayName("reportMetrics: APPROVED deliverable is allowed")
    void testReportMetricsFromApproved() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .milestoneId(MILESTONE_ID)
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.APPROVED)
                        .build();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));
        when(deliverableMetricRepository.findByMilestoneId(MILESTONE_ID)).thenReturn(Optional.empty());
        when(deliverableMetricRepository.save(any(DeliverableMetric.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // verified-analytics-0804: manual self-report is only accepted when Meta verification fails.
        when(verificationService.verify(any()))
                .thenReturn(
                        com.influora.service.verification.DeliverableVerificationService.Outcome
                                .FALLBACK_API_ERROR);

        MetricsReportResponse response =
                service.reportMetrics(principal, DELIVERABLE_ID, sampleMetricsRequest());

        assertEquals(DeliverableStatus.METRICS_REPORTED, response.status());
    }

    @Test
    @DisplayName("reportMetrics: rejects manual entry when Meta verification succeeds (VERIFIED)")
    void testReportMetricsRejectedWhenVerified() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = postedDeliverable();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));
        // verified-analytics-0804: Meta returned real numbers → manual self-report must be blocked.
        when(verificationService.verify(any()))
                .thenReturn(
                        com.influora.service.verification.DeliverableVerificationService.Outcome
                                .VERIFIED);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reportMetrics(principal, DELIVERABLE_ID, sampleMetricsRequest()));
        assertEquals("MANUAL_METRICS_NOT_ALLOWED", ex.getCode());
    }

    @Test
    @DisplayName("reportMetrics: rejects DRAFT state")
    void testReportMetricsInvalidState() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = pendingDeliverable();
        deliverable.applyUpload(1, "[{\"id\":\"f1\"}]", null, null, null);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reportMetrics(principal, DELIVERABLE_ID, sampleMetricsRequest()));

        assertEquals("INVALID_STATE", ex.getCode());
        verify(deliverableMetricRepository, never()).save(any());
    }

    @Test
    @DisplayName("reportMetrics: rejects negative metric values")
    void testReportMetricsNegativeValues() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(postedDeliverable()));

        MetricsReportRequest bad =
                new MetricsReportRequest(
                        new MetricsPayload(-1, 0, 0, 0, 100, 100, 0), List.of(), 1);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reportMetrics(principal, DELIVERABLE_ID, bad));

        assertEquals("INVALID_METRIC_VALUE", ex.getCode());
    }

    @Test
    @DisplayName("reportMetrics: rejects deliverable without milestone link")
    void testReportMetricsMissingMilestone() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = postedDeliverable();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(
                        Optional.of(
                                Deliverable.builder()
                                        .id(DELIVERABLE_ID)
                                        .collaborationId(COLLAB_ID)
                                        .creatorProfileId("profile1")
                                        .title("Workout Reel 1")
                                        .status(DeliverableStatus.POSTED)
                                        .build()));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reportMetrics(principal, DELIVERABLE_ID, sampleMetricsRequest()));

        assertEquals("MILESTONE_NOT_LINKED", ex.getCode());
    }

    @Test
    @DisplayName("reportMetrics: foreign deliverable 404")
    void testReportMetricsForeignDeliverable() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reportMetrics(principal, DELIVERABLE_ID, sampleMetricsRequest()));

        assertEquals("DELIVERABLE_NOT_FOUND", ex.getCode());
    }

    // ---------------------------------------------------------------------
    // DPF-3 / DPF-3b — markPosted
    // ---------------------------------------------------------------------

    private static Deliverable approvedDeliverable() {
        return Deliverable.builder()
                .id(DELIVERABLE_ID)
                .collaborationId(COLLAB_ID)
                .creatorProfileId("profile1")
                .milestoneId(MILESTONE_ID)
                .slotIndex(1)
                .type(DeliverableType.INSTAGRAM_REEL)
                .title("Workout Reel 1")
                .status(DeliverableStatus.APPROVED)
                .build();
    }

    @Test
    @DisplayName("markPosted: APPROVED + valid Instagram URL transitions to POSTED")
    void testMarkPostedInstagramHappyPath() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = approvedDeliverable();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));

        MarkPostedResponse response =
                service.markPosted(
                        principal,
                        DELIVERABLE_ID,
                        new MarkPostedRequest("https://www.instagram.com/p/ABC123/"));

        assertEquals(DELIVERABLE_ID, response.id());
        assertEquals(DeliverableStatus.POSTED, response.status());
        assertEquals("https://www.instagram.com/p/ABC123/", response.postUrl());
        assertNotNull(response.postedAt());

        ArgumentCaptor<Deliverable> saved = ArgumentCaptor.forClass(Deliverable.class);
        verify(deliverableRepository).save(saved.capture());
        assertEquals(DeliverableStatus.POSTED, saved.getValue().getStatus());
        assertEquals("https://www.instagram.com/p/ABC123/", saved.getValue().getPostUrl());
    }

    @Test
    @DisplayName("markPosted: APPROVED + valid YouTube URL transitions to POSTED")
    void testMarkPostedYouTubeHappyPath() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = approvedDeliverable();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));

        MarkPostedResponse response =
                service.markPosted(
                        principal,
                        DELIVERABLE_ID,
                        new MarkPostedRequest("https://www.youtube.com/watch?v=abc123&t=30s"));

        assertEquals(DeliverableStatus.POSTED, response.status());
        assertEquals("https://www.youtube.com/watch?v=abc123&t=30s", response.postUrl());
    }

    @Test
    @DisplayName("markPosted: youtu.be short URL is accepted")
    void testMarkPostedYouTubeShortUrl() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = approvedDeliverable();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));

        MarkPostedResponse response =
                service.markPosted(
                        principal, DELIVERABLE_ID, new MarkPostedRequest("https://youtu.be/abc123"));

        assertEquals(DeliverableStatus.POSTED, response.status());
    }

    @Test
    @DisplayName("markPosted: non-APPROVED deliverable (SUBMITTED) rejected with 409, no state change")
    void testMarkPostedInvalidState() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.SUBMITTED)
                        .build();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.markPosted(
                                        principal,
                                        DELIVERABLE_ID,
                                        new MarkPostedRequest("https://www.instagram.com/p/ABC123/")));

        assertEquals("INVALID_STATE", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(DeliverableStatus.SUBMITTED, deliverable.getStatus());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("markPosted: creator B marking creator A's deliverable returns 404 (IDOR)")
    void testMarkPostedForeignDeliverableIsIdor() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        // Repository is scoped by (deliverableId, creatorUserId) — creator B's principal id
        // simply won't match creator A's row, so the lookup returns empty exactly as it would
        // for a nonexistent id. Asserting 404 (never a 403/leak) per M-24-1 pattern.
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.markPosted(
                                        principal,
                                        DELIVERABLE_ID,
                                        new MarkPostedRequest("https://www.instagram.com/p/ABC123/")));

        assertEquals("DELIVERABLE_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(deliverableRepository, never()).save(any());
    }

    @Test
    @DisplayName("markPosted: rejects non-HTTPS scheme (http)")
    void testMarkPostedRejectsHttp() {
        assertMarkPostedRejected("http://www.instagram.com/p/ABC123/");
    }

    @Test
    @DisplayName("markPosted: rejects file:// scheme")
    void testMarkPostedRejectsFileScheme() {
        assertMarkPostedRejected("file:///etc/passwd");
    }

    @Test
    @DisplayName("markPosted: rejects javascript: scheme")
    void testMarkPostedRejectsJavascriptScheme() {
        assertMarkPostedRejected("javascript:alert(1)");
    }

    @Test
    @DisplayName("markPosted: rejects lookalike host instagram.com.attacker.com")
    void testMarkPostedRejectsLookalikeHost() {
        assertMarkPostedRejected("https://instagram.com.attacker.com/p/ABC123/");
    }

    @Test
    @DisplayName("markPosted: rejects path-spoofed host attacker.com/instagram.com/p/x")
    void testMarkPostedRejectsPathSpoofedHost() {
        assertMarkPostedRejected("https://attacker.com/instagram.com/p/x");
    }

    @Test
    @DisplayName("markPosted: rejects localhost host (SSRF guard)")
    void testMarkPostedRejectsLocalhost() {
        assertMarkPostedRejected("https://localhost/p/ABC123/");
    }

    @Test
    @DisplayName("markPosted: rejects loopback IP 127.0.0.1 (SSRF guard)")
    void testMarkPostedRejectsLoopbackIp() {
        assertMarkPostedRejected("https://127.0.0.1/p/ABC123/");
    }

    @Test
    @DisplayName("markPosted: rejects private-range IP 10.0.0.5 (SSRF guard)")
    void testMarkPostedRejectsPrivateRangeIp() {
        assertMarkPostedRejected("https://10.0.0.5/p/ABC123/");
    }

    @Test
    @DisplayName("markPosted: rejects cloud metadata IP 169.254.169.254 (SSRF guard)")
    void testMarkPostedRejectsCloudMetadataIp() {
        assertMarkPostedRejected("https://169.254.169.254/latest/meta-data/");
    }

    @Test
    @DisplayName("markPosted: rejects localhost disguised as instagram subdomain "
            + "(instagram.com.localhost)")
    void testMarkPostedRejectsLocalhostDisguisedAsSubdomain() {
        assertMarkPostedRejected("https://instagram.com.localhost/p/ABC123/");
    }

    @Test
    @DisplayName("markPosted: rejects a regex-VALID YouTube URL over 500 chars as clean 400 "
            + "(isolates the length guard — old test used a regex-invalid URL and didn't)")
    void testMarkPostedRejectsOverLengthRegexValidYouTubeUrl() {
        // Video-id-shaped run of [A-Za-z0-9_-] chars only, so this URL WOULD match the YouTube
        // regex on shape alone were it not for the length guard running first. Proves the 400
        // comes from the explicit length check, not an incidental regex mismatch.
        String longVideoId = "a".repeat(520);
        String overLong = "https://www.youtube.com/watch?v=" + longVideoId;
        assertMarkPostedRejected(overLong);
    }

    @Test
    @DisplayName("markPosted: YouTube regex accepts bounded query tail (&t=30s)")
    void testMarkPostedYouTubeRegexAcceptsBoundedTail() {
        assertMarkPostedAccepted("https://www.youtube.com/watch?v=abc&t=30s");
    }

    @Test
    @DisplayName("markPosted: YouTube regex rejects unbounded/XSS query tail (&x=<script>)")
    void testMarkPostedYouTubeRegexRejectsXssTail() {
        assertMarkPostedRejected("https://www.youtube.com/watch?v=abc&x=<script>");
    }

    @Test
    @DisplayName("markPosted: accepts real creator-pasted &ab_channel=My%20Name param (Kavya P1 fix)")
    void testMarkPostedAcceptsAbChannelParam() {
        assertMarkPostedAccepted("https://www.youtube.com/watch?v=abc123&ab_channel=My%20Name");
    }

    @Test
    @DisplayName("markPosted: accepts real creator-pasted &feature=youtu.be param (Kavya P1 fix)")
    void testMarkPostedAcceptsFeatureYoutuBeParam() {
        assertMarkPostedAccepted("https://www.youtube.com/watch?v=abc123&feature=youtu.be");
    }

    @Test
    @DisplayName("markPosted: rejects double-quote XSS attribute-breakout tail")
    void testMarkPostedRejectsDoubleQuoteXssTail() {
        assertMarkPostedRejected("https://www.youtube.com/watch?v=abc&x=\"onmouseover=\"");
    }

    @Test
    @DisplayName("markPosted: rejects single-quote/tag-breakout XSS tail")
    void testMarkPostedRejectsSingleQuoteXssTail() {
        assertMarkPostedRejected("https://www.youtube.com/watch?v=abc&x='></a>");
    }

    @Test
    @DisplayName(
            "markPosted: URL-encoded %3Cscript%3E is ACCEPTED as an opaque string (documents the "
                    + "FE constraint: postUrl must never be URL-decoded before render, or this "
                    + "becomes live XSS — see validatePlatformPostUrl javadoc)")
    void testMarkPostedAcceptsEncodedScriptTagAsOpaqueString() {
        assertMarkPostedAccepted("https://www.youtube.com/watch?v=abc&x=%3Cscript%3E");
    }

    /** Shared assertion: given URL is accepted, deliverable transitions to POSTED with that URL. */
    private void assertMarkPostedAccepted(String url) {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = approvedDeliverable();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));

        MarkPostedResponse response =
                service.markPosted(principal, DELIVERABLE_ID, new MarkPostedRequest(url));

        assertEquals(DeliverableStatus.POSTED, response.status());
        assertEquals(url, response.postUrl());
    }

    /** Shared assertion: given URL is rejected with INVALID_POST_URL / 400, no state change. */
    private void assertMarkPostedRejected(String url) {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        Deliverable deliverable = approvedDeliverable();
        when(deliverableRepository.findByIdAndCreatorUserId(DELIVERABLE_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(deliverable));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.markPosted(principal, DELIVERABLE_ID, new MarkPostedRequest(url)));

        assertEquals("INVALID_POST_URL", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(DeliverableStatus.APPROVED, deliverable.getStatus());
        verify(deliverableRepository, never()).save(any());
    }
}
