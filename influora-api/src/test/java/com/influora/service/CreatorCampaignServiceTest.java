package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.ApplyRequest;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.ApplyResponse;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.CreatorCampaignDetailResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Task #7 (creator campaign browse/apply, Creator Week 2 sprint — TASK_INBOX.md P0 #7). Mirrors
 * the shape of {@code CreatorDiscoveryServiceTest}'s invite() coverage: sequential duplicate,
 * concurrent-race duplicate (DB unique constraint), and the happy path — plus the visibility gate
 * (private/DRAFT campaigns must 404, never leak existence) and the deadline/status guards that
 * are unique to apply().
 */
@ExtendWith(MockitoExtension.class)
class CreatorCampaignServiceTest {

    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567890";
    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";

    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private CreatorContextService creatorContext;
    @Mock private AuthPrincipal principal;
    @Mock private BrandContextService brandContext;
    @Mock private ApplicationEventPublisher eventPublisher;
    // F-0225 — the revive decision is exercised for real here, with only its repositories mocked.
    // Mocking CollaborationReviveService itself would stub out the exact logic these tests exist
    // to hold (revive-vs-409, and the encumbrance refusal).
    @Mock private com.influora.repository.ContractRepository contractRepository;
    @Mock private com.influora.repository.EscrowHoldRepository escrowHoldRepository;
    @Mock private com.influora.repository.ShipmentRepository shipmentRepository;
    @Mock private com.influora.repository.DealMessageRepository dealMessageRepository;

    private CreatorCampaignService service;

    @BeforeEach
    void setUp() {
        CollaborationReviveService reviveService =
                new CollaborationReviveService(
                        collaborationRepository, contractRepository, escrowHoldRepository, shipmentRepository);
        service =
                new CreatorCampaignService(
                        campaignRepository,
                        collaborationRepository,
                        workspaceRepository,
                        creatorContext,
                        brandContext,
                        eventPublisher,
                        reviveService,
                        dealMessageRepository);
        CreatorProfile creator = CreatorProfile.newForUser("profile1", CREATOR_USER_ID, "Test Creator");
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creator);
    }

    private static Campaign activeCampaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Summer Fitness Challenge")
                .description("Looking for fitness creators")
                .status(CampaignStatus.ACTIVE)
                .budgetMin(new BigDecimal("10000"))
                .budgetMax(new BigDecimal("50000"))
                .currency("INR")
                .applicationDeadline(LocalDate.now().plusDays(5))
                .platformsJson("[\"INSTAGRAM\"]")
                .isPrivate(false)
                .createdBy("brand_user_1")
                .build();
    }

    // ---- apply() ----

    @Test
    @DisplayName("apply: happy path saves exactly once, source=APPLICATION, status=APPLIED")
    void testApplyHappyPath() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(campaign));
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(java.util.Optional.empty());
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApplyResponse response = service.apply(principal, CAMPAIGN_ID, new ApplyRequest("Pick me!"));

        assertEquals("APPLIED", response.status());
        verify(collaborationRepository, org.mockito.Mockito.times(1)).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("F-0290: applying writes the event AND the creator's note onto the deal timeline")
    void testApplyRecordsTimeline() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(campaign));
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(java.util.Optional.empty());
        when(collaborationRepository.save(any(Collaboration.class))).thenAnswer(inv -> inv.getArgument(0));

        service.apply(principal, CAMPAIGN_ID, new ApplyRequest("I would love this brief"));

        org.mockito.ArgumentCaptor<com.influora.domain.entity.DealMessage> saved =
                org.mockito.ArgumentCaptor.forClass(com.influora.domain.entity.DealMessage.class);
        verify(dealMessageRepository, org.mockito.Mockito.times(2)).save(saved.capture());

        var rows = saved.getAllValues();
        // The EVENT — so the room is never empty and the brand can see a request arrived.
        assertEquals(com.influora.domain.enums.DealMessageKind.system, rows.get(0).getKind());
        assertEquals(com.influora.domain.enums.DealSenderType.system, rows.get(0).getSenderType());
        // The creator's own words, attributed to the CREATOR — not folded into the system line,
        // so it renders on their side of the thread and the brand can reply to it.
        assertEquals(com.influora.domain.enums.DealMessageKind.text, rows.get(1).getKind());
        assertEquals(com.influora.domain.enums.DealSenderType.creator, rows.get(1).getSenderType());
        assertEquals(CREATOR_USER_ID, rows.get(1).getSenderId());
        assertEquals("I would love this brief", rows.get(1).getContent());
    }

    @Test
    @DisplayName("F-0290: an application with no note still records the event, and invents no message")
    void testApplyWithoutNoteRecordsEventOnly() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(campaign));
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(java.util.Optional.empty());
        when(collaborationRepository.save(any(Collaboration.class))).thenAnswer(inv -> inv.getArgument(0));

        service.apply(principal, CAMPAIGN_ID, new ApplyRequest(null));

        // Exactly one row: the event. Fabricating a creator "message" they never wrote would be
        // the same class of defect as the escrow copy F-0226 removed.
        verify(dealMessageRepository, org.mockito.Mockito.times(1))
                .save(any(com.influora.domain.entity.DealMessage.class));
    }

    @Test
    @DisplayName("F-0290: the application's note is sanitised on the timeline, not just on the row")
    void testApplyTimelineNoteIsSanitised() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(campaign));
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(java.util.Optional.empty());
        when(collaborationRepository.save(any(Collaboration.class))).thenAnswer(inv -> inv.getArgument(0));

        service.apply(principal, CAMPAIGN_ID, new ApplyRequest("<script>alert(1)</script>hello"));

        org.mockito.ArgumentCaptor<com.influora.domain.entity.DealMessage> saved =
                org.mockito.ArgumentCaptor.forClass(com.influora.domain.entity.DealMessage.class);
        verify(dealMessageRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertEquals("hello", saved.getAllValues().get(1).getContent());
    }

    @Test
    @DisplayName("F-0290: a timeline write failure never rolls back an application that succeeded")
    void testApplySurvivesTimelineFailure() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(campaign));
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(java.util.Optional.empty());
        when(collaborationRepository.save(any(Collaboration.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("timeline down"))
                .when(dealMessageRepository)
                .save(any(com.influora.domain.entity.DealMessage.class));

        // An application with no timeline is the bug being fixed; an application that 500s because
        // its timeline could not be written is worse.
        ApplyResponse response = service.apply(principal, CAMPAIGN_ID, new ApplyRequest("hi"));
        assertEquals("APPLIED", response.status());
    }

    @Test
    @DisplayName("apply: strips script tags from application message notes")
    void testApplyStripsXssMessage() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(campaign));
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(java.util.Optional.empty());
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.apply(
                principal,
                CAMPAIGN_ID,
                new ApplyRequest("<script>alert(1)</script>I am a great fit"));

        org.mockito.ArgumentCaptor<Collaboration> saved =
                org.mockito.ArgumentCaptor.forClass(Collaboration.class);
        verify(collaborationRepository).save(saved.capture());
        assertEquals("I am a great fit", saved.getValue().getNotes());
    }

    @Test
    @DisplayName("apply: duplicate against a LIVE prior application -> 409 ALREADY_APPLIED, never saves")
    void testApplySequentialDuplicateRejected() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(campaign));
        // F-0225 — a prior row only blocks re-application while it is still LIVE. CANCELLED is the
        // one status that revives instead; every other status must still 409, which is this test.
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(java.util.Optional.of(
                        Collaboration.apply("collab_prior", CAMPAIGN_ID, CREATOR_USER_ID, null, "INR")));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.apply(principal, CAMPAIGN_ID, new ApplyRequest(null)));

        assertEquals("ALREADY_APPLIED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("apply: F-0225 -- a WITHDRAWN prior application revives instead of 409-ing")
    void testApplyRevivesCancelledApplication() {
        Campaign campaign = activeCampaign();
        Collaboration withdrawn =
                Collaboration.propose(
                        "collab_prior", CAMPAIGN_ID, CREATOR_USER_ID, new java.math.BigDecimal("50000"), "INR", "old");
        withdrawn.transitionTo(com.influora.domain.enums.CollaborationStatus.CANCELLED);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(campaign));
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(java.util.Optional.of(withdrawn));
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApplyResponse response = service.apply(principal, CAMPAIGN_ID, new ApplyRequest("second time"));

        // The defect was a permanent 409 here. The row is reused, so the id is the SAME row --
        // UNIQUE(campaign_id, creator_id) leaves no second one to create.
        assertEquals("APPLIED", response.status());
        assertEquals("collab_prior", response.collaborationId());

        org.mockito.ArgumentCaptor<Collaboration> saved =
                org.mockito.ArgumentCaptor.forClass(Collaboration.class);
        verify(collaborationRepository).save(saved.capture());
        assertEquals(
                com.influora.domain.enums.CollaborationSource.APPLICATION, saved.getValue().getSource());
        // The withdrawn negotiation's price must not price the new application.
        assertNull(saved.getValue().getAgreedRate());
        assertEquals("second time", saved.getValue().getNotes());
    }

    @Test
    @DisplayName(
            "apply: concurrent race -- existsBy... says false (loser) but save() hits the DB unique"
                    + " constraint -> must surface the same friendly 409, never a raw 500")
    void testApplyConcurrentRaceLoserGetsFriendly409() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(campaign));
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(java.util.Optional.empty());
        doThrow(new DataIntegrityViolationException("Duplicate entry for key 'uq_campaign_creator'"))
                .when(collaborationRepository)
                .save(any(Collaboration.class));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.apply(principal, CAMPAIGN_ID, new ApplyRequest(null)));

        assertEquals("ALREADY_APPLIED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    @DisplayName("apply: campaign not ACTIVE -> 409 CAMPAIGN_NOT_OPEN")
    void testApplyRejectsNonActiveCampaign() {
        Campaign paused =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId(WORKSPACE_ID)
                        .title("Paused Campaign")
                        .status(CampaignStatus.PAUSED)
                        .currency("INR")
                        .isPrivate(false)
                        .createdBy("brand_user_1")
                        .build();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(paused));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.apply(principal, CAMPAIGN_ID, new ApplyRequest(null)));

        assertEquals("CAMPAIGN_NOT_OPEN", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("apply: application deadline passed -> 409 APPLICATION_DEADLINE_PASSED")
    void testApplyRejectsPastDeadline() {
        Campaign expired =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId(WORKSPACE_ID)
                        .title("Expired Campaign")
                        .status(CampaignStatus.ACTIVE)
                        .currency("INR")
                        .applicationDeadline(LocalDate.now().minusDays(1))
                        .isPrivate(false)
                        .createdBy("brand_user_1")
                        .build();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(expired));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.apply(principal, CAMPAIGN_ID, new ApplyRequest(null)));

        assertEquals("APPLICATION_DEADLINE_PASSED", ex.getCode());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("apply: DRAFT campaign is never creator-visible -> 404 CAMPAIGN_NOT_FOUND")
    void testApplyRejectsDraftCampaignAsNotFound() {
        Campaign draft =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId(WORKSPACE_ID)
                        .title("Draft Campaign")
                        .status(CampaignStatus.DRAFT)
                        .currency("INR")
                        .isPrivate(false)
                        .createdBy("brand_user_1")
                        .build();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(draft));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.apply(principal, CAMPAIGN_ID, new ApplyRequest(null)));

        assertEquals("CAMPAIGN_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    @DisplayName(
            "apply: private (invite-only) campaign with no existing invitation -> 404, never leaks"
                    + " existence")
    void testApplyRejectsPrivateCampaignWithoutInvitationAsNotFound() {
        Campaign privateCampaign =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId(WORKSPACE_ID)
                        .title("Invite Only Campaign")
                        .status(CampaignStatus.ACTIVE)
                        .currency("INR")
                        .isPrivate(true)
                        .createdBy("brand_user_1")
                        .build();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(privateCampaign));
        // requireVisibleCampaign's invite-only gate — still existsBy..., and it 404s before the
        // F-0225 duplicate/revive lookup is ever reached.
        when(collaborationRepository.existsByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.apply(principal, CAMPAIGN_ID, new ApplyRequest(null)));

        assertEquals("CAMPAIGN_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    @DisplayName("apply: unknown campaign id -> 404 CAMPAIGN_NOT_FOUND")
    void testApplyUnknownCampaignNotFound() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.apply(principal, CAMPAIGN_ID, new ApplyRequest(null)));

        assertEquals("CAMPAIGN_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    // ---- getDetail() ----

    @Test
    @DisplayName("getDetail: happy path returns brand summary and null applicationStatus pre-apply")
    void testGetDetailHappyPath() {
        Campaign campaign = activeCampaign();
        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "HealthKart", "healthkart", "Health", "50-200");
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(campaign));
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(java.util.Optional.of(workspace));
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(java.util.Optional.empty());

        CreatorCampaignDetailResponse detail = service.getDetail(principal, CAMPAIGN_ID);

        assertEquals(CAMPAIGN_ID, detail.id());
        assertEquals("HealthKart", detail.brand().name());
        assertNull(detail.applicationStatus());
    }

    @Test
    @DisplayName("getDetail: private campaign the creator was invited to is visible with INVITED status")
    void testGetDetailPrivateCampaignVisibleWhenInvited() {
        Campaign privateCampaign =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId(WORKSPACE_ID)
                        .title("Invite Only Campaign")
                        .status(CampaignStatus.ACTIVE)
                        .currency("INR")
                        .isPrivate(true)
                        .createdBy("brand_user_1")
                        .build();
        Collaboration invitation =
                Collaboration.invite("collab1", CAMPAIGN_ID, CREATOR_USER_ID, "Join us!", "INR");
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(privateCampaign));
        when(collaborationRepository.existsByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(true);
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(java.util.Optional.empty());
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(java.util.Optional.of(invitation));

        CreatorCampaignDetailResponse detail = service.getDetail(principal, CAMPAIGN_ID);

        assertEquals("INVITED", detail.applicationStatus());
    }

    // ---- browse() ----

    @Test
    @DisplayName("browse: paginates and maps applicationStatus for campaigns already applied to")
    void testBrowseReturnsPagedResultsWithApplicationStatus() {
        Campaign campaign = activeCampaign();
        Page<Campaign> page = new PageImpl<>(List.of(campaign), PageRequest.of(0, 20), 1);
        when(campaignRepository.findAll(
                        org.mockito.ArgumentMatchers.<Specification<Campaign>>any(),
                        org.mockito.ArgumentMatchers.<Pageable>any()))
                .thenReturn(page);
        Collaboration applied =
                Collaboration.apply("collab1", CAMPAIGN_ID, CREATOR_USER_ID, "msg", "INR");
        when(collaborationRepository.findByCreatorIdAndCampaignIdIn(
                        eq(CREATOR_USER_ID), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(applied));
        when(workspaceRepository.findAllById(org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenReturn(List.of());

        var result = service.browse(principal, null, null, null, null, 1, 20);

        assertEquals(1, result.items().size());
        assertEquals("APPLIED", result.items().get(0).applicationStatus());
        assertEquals(1, result.meta().total());
    }

    @Test
    @DisplayName("browse: platform filter excludes non-matching campaigns and reports page-only total")
    void testBrowseFiltersByPlatformInMemory() {
        Campaign instaCampaign = activeCampaign();
        Page<Campaign> page = new PageImpl<>(List.of(instaCampaign), PageRequest.of(0, 20), 1);
        when(campaignRepository.findAll(
                        org.mockito.ArgumentMatchers.<Specification<Campaign>>any(),
                        org.mockito.ArgumentMatchers.<Pageable>any()))
                .thenReturn(page);
        when(collaborationRepository.findByCreatorIdAndCampaignIdIn(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());
        when(workspaceRepository.findAllById(org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenReturn(List.of());

        var matching = service.browse(principal, null, null, null, "instagram", 1, 20);
        assertEquals(1, matching.items().size());

        var nonMatching = service.browse(principal, null, null, null, "youtube", 1, 20);
        assertEquals(0, nonMatching.items().size());
        assertEquals(0, nonMatching.meta().total());
    }

    @Test
    @DisplayName(
            "browse: niche filter is case-insensitive (CR-62) — matches title/description/hashtags"
                    + " regardless of frontend casing")
    void testBrowseNicheFilterIsCaseInsensitive() {
        Campaign fitnessCampaign = activeCampaign(); // title="Summer Fitness Challenge"
        Page<Campaign> page = new PageImpl<>(List.of(fitnessCampaign), PageRequest.of(0, 20), 1);
        when(campaignRepository.findAll(
                        org.mockito.ArgumentMatchers.<Specification<Campaign>>any(),
                        org.mockito.ArgumentMatchers.<Pageable>any()))
                .thenReturn(page);
        when(collaborationRepository.findByCreatorIdAndCampaignIdIn(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());
        when(workspaceRepository.findAllById(org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenReturn(List.of());

        // All three casing variants must match the title "Summer Fitness Challenge"
        var lowercase = service.browse(principal, "fitness", null, null, null, 1, 20);
        assertEquals(1, lowercase.items().size(), "lowercase 'fitness' should match title");

        var titleCase = service.browse(principal, "Fitness", null, null, null, 1, 20);
        assertEquals(1, titleCase.items().size(), "title-case 'Fitness' should match title");

        var uppercase = service.browse(principal, "FITNESS", null, null, null, 1, 20);
        assertEquals(1, uppercase.items().size(), "uppercase 'FITNESS' should match title");

        // Non-matching niche should return 0
        var noMatch = service.browse(principal, "beauty", null, null, null, 1, 20);
        assertEquals(0, noMatch.items().size(), "'beauty' should not match fitness campaign");
    }
}
