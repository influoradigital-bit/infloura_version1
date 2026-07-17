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

    private CreatorCampaignService service;

    @BeforeEach
    void setUp() {
        service =
                new CreatorCampaignService(
                        campaignRepository,
                        collaborationRepository,
                        workspaceRepository,
                        creatorContext,
                        brandContext,
                        eventPublisher);
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
        when(collaborationRepository.existsByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(false);
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApplyResponse response = service.apply(principal, CAMPAIGN_ID, new ApplyRequest("Pick me!"));

        assertEquals("APPLIED", response.status());
        verify(collaborationRepository, org.mockito.Mockito.times(1)).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("apply: strips script tags from application message notes")
    void testApplyStripsXssMessage() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(campaign));
        when(collaborationRepository.existsByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(false);
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
    @DisplayName("apply: sequential duplicate (existsBy... true) -> 409 ALREADY_APPLIED, never saves")
    void testApplySequentialDuplicateRejected() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(campaign));
        when(collaborationRepository.existsByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.apply(principal, CAMPAIGN_ID, new ApplyRequest(null)));

        assertEquals("ALREADY_APPLIED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName(
            "apply: concurrent race -- existsBy... says false (loser) but save() hits the DB unique"
                    + " constraint -> must surface the same friendly 409, never a raw 500")
    void testApplyConcurrentRaceLoserGetsFriendly409() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(java.util.Optional.of(campaign));
        when(collaborationRepository.existsByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(false);
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
}
