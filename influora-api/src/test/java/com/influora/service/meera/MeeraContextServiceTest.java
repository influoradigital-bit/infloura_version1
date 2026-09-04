package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignTemplate;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CampaignTemplateCategory;
import com.influora.domain.enums.CampaignTemplateScope;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignTemplateRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.web.dto.meera.MeeraContextDtos.ContextResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Platform-AI Phase 1, Wave 1a (Priya A2/A4) — {@link MeeraContextService} orchestrates the
 * repository reads for {@code POST /internal/meera/context} and hands them to {@link
 * BrandContextAssembler}, which is where the actual field allow-list is enforced (see {@link
 * BrandContextAssemblerTest}). This test covers: audience guard (A4 — CREATOR is Phase 3, must be
 * rejected, never silently populated), the SYSTEM+CUSTOM template digest fetch, and the
 * last-N-campaigns funded/creator-count summary.
 */
@ExtendWith(MockitoExtension.class)
class MeeraContextServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456";

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private BrandProfileRepository brandProfileRepository;
    @Mock private CampaignTemplateRepository templateRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private DeliverableMetricRepository deliverableMetricRepository;
    @Mock private UtmCampaignRepository utmCampaignRepository;
    @Mock private AICreditService creditService;
    @Mock private Workspace workspace;
    @Mock private com.influora.repository.CreatorProfileRepository creatorProfileRepository;
    @Mock private com.influora.repository.CreatorAgentPreferencesRepository creatorAgentPreferencesRepository;
    @Mock private com.influora.repository.CreatorMetricsRepository creatorMetricsRepository;

    private MeeraContextService service;

    @BeforeEach
    void setUp() {
        service =
                new MeeraContextService(
                        workspaceRepository,
                        brandProfileRepository,
                        templateRepository,
                        campaignRepository,
                        collaborationRepository,
                        escrowHoldRepository,
                        deliverableMetricRepository,
                        utmCampaignRepository,
                        creditService,
                        new BrandContextAssembler(),
                        creatorProfileRepository,
                        creatorAgentPreferencesRepository,
                        creatorMetricsRepository);
    }

    @Test
    @DisplayName("audience=CREATOR (SPEC.md T-MEERA-CREATOR-PHASE-A, A4) — creator not found -> 404, never a silent empty context")
    void testCreatorAudienceCreatorNotFound() {
        when(creatorProfileRepository.findByUserId(WORKSPACE_ID)).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class, () -> service.assemble(WORKSPACE_ID, "CREATOR"));
        assertEquals("CREATOR_PROFILE_NOT_FOUND", ex.getCode());
        verifyNoInteractions(collaborationRepository, creatorMetricsRepository);
    }

    @Test
    @DisplayName("audience=CREATOR (A4/A7) — identity carries ONLY kyc_done/gstin_present booleans, never PAN/GSTIN/Aadhaar")
    void testCreatorAudienceIdentityBarrier() {
        com.influora.domain.entity.CreatorProfile profile = mock(com.influora.domain.entity.CreatorProfile.class);
        when(creatorProfileRepository.findByUserId(WORKSPACE_ID)).thenReturn(Optional.of(profile));
        when(profile.getId()).thenReturn("profile1");
        when(profile.getDisplayName()).thenReturn("Priya Shah");
        when(profile.getCity()).thenReturn("Pune");
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getTotalFollowers()).thenReturn(12_400L);
        when(profile.getGstin()).thenReturn(null);
        when(profile.getIdentityKycStatus()).thenReturn(com.influora.domain.enums.VerificationStatus.VERIFIED);
        when(profile.getTierOverride()).thenReturn(null);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile1")).thenReturn(Optional.empty());
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq("profile1"), any()))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(WORKSPACE_ID)).thenReturn(List.of());

        Object result = service.assemble(WORKSPACE_ID, "CREATOR");

        assertTrue(result instanceof com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse);
        var creatorContext = (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse) result;
        assertEquals(java.util.Set.of("kyc_done", "gstin_present"), creatorContext.identity().keySet());
        assertEquals(true, creatorContext.identity().get("kyc_done"));
        assertEquals(false, creatorContext.identity().get("gstin_present"));
        assertEquals("Priya", creatorContext.firstName());
        assertEquals("Priya Shah", creatorContext.displayName());
        assertFalse(creatorContext.consentAccepted());
    }

    @Test
    @DisplayName(
            "audience=CREATOR (fix round 2, item 3 -- Priya Q8) -- excluded_categories, blocked_brands,"
                    + " working_hours_start/end, working_days and weekly_sponsored_limit are all present"
                    + " in the response when the creator has set them, so Meera can actually honor a"
                    + " blocklisted brand instead of never being told about it")
    void testCreatorAudienceIncludesFiltersAndWorkingHours() {
        com.influora.domain.entity.CreatorProfile profile = mock(com.influora.domain.entity.CreatorProfile.class);
        when(creatorProfileRepository.findByUserId(WORKSPACE_ID)).thenReturn(Optional.of(profile));
        when(profile.getId()).thenReturn("profile1");
        when(profile.getDisplayName()).thenReturn("Priya Shah");
        when(profile.getCity()).thenReturn("Pune");
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getTotalFollowers()).thenReturn(12_400L);
        when(profile.getGstin()).thenReturn(null);
        when(profile.getIdentityKycStatus()).thenReturn(com.influora.domain.enums.VerificationStatus.VERIFIED);
        when(profile.getTierOverride()).thenReturn(null);

        com.influora.domain.entity.CreatorAgentPreferences prefs =
                mock(com.influora.domain.entity.CreatorAgentPreferences.class);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile1")).thenReturn(Optional.of(prefs));
        when(prefs.getCreatorLanguage()).thenReturn("en-IN");
        when(prefs.getBrandTone()).thenReturn("FRIENDLY");
        when(prefs.getApprovalLevel()).thenReturn(0);
        when(prefs.isRepresented()).thenReturn(false);
        when(prefs.isConsentAccepted()).thenReturn(true);
        when(prefs.getExcludedCategoriesJson()).thenReturn("[\"Alcohol\",\"Gambling\"]");
        when(prefs.getBlockedBrandsJson()).thenReturn("[\"RivalCo\"]");
        when(prefs.getWorkingHoursStart()).thenReturn(9);
        when(prefs.getWorkingHoursEnd()).thenReturn(18);
        when(prefs.getWorkingDaysJson()).thenReturn("[\"1\",\"2\",\"3\",\"4\",\"5\"]");
        when(prefs.getWeeklySponsoredLimit()).thenReturn(3);

        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq("profile1"), any()))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(WORKSPACE_ID)).thenReturn(List.of());

        Object result = service.assemble(WORKSPACE_ID, "CREATOR");

        var creatorContext = (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse) result;
        assertEquals(List.of("Alcohol", "Gambling"), creatorContext.excludedCategories());
        assertEquals(List.of("RivalCo"), creatorContext.blockedBrands());
        assertEquals(9, creatorContext.workingHoursStart());
        assertEquals(18, creatorContext.workingHoursEnd());
        assertEquals(List.of(1, 2, 3, 4, 5), creatorContext.workingDays());
        assertEquals(3, creatorContext.weeklySponsoredLimit());
    }

    @Test
    @DisplayName(
            "Priya gate review defect 2 -- a represented creator's agency_name is populated on the"
                    + " CREATOR context from CreatorAgentPreferences.getAgencyName(), and is null when"
                    + " the creator has no preferences row at all")
    void testCreatorAudienceSurfacesAgencyName() {
        com.influora.domain.entity.CreatorProfile profile = mock(com.influora.domain.entity.CreatorProfile.class);
        when(creatorProfileRepository.findByUserId(WORKSPACE_ID)).thenReturn(Optional.of(profile));
        when(profile.getId()).thenReturn("profile1");
        when(profile.getDisplayName()).thenReturn("Priya Shah");
        when(profile.getCity()).thenReturn("Pune");
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getTotalFollowers()).thenReturn(12_400L);
        when(profile.getGstin()).thenReturn(null);
        when(profile.getIdentityKycStatus()).thenReturn(com.influora.domain.enums.VerificationStatus.VERIFIED);
        when(profile.getTierOverride()).thenReturn(null);
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq("profile1"), any()))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(WORKSPACE_ID)).thenReturn(List.of());

        com.influora.domain.entity.CreatorAgentPreferences prefs =
                mock(com.influora.domain.entity.CreatorAgentPreferences.class);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile1")).thenReturn(Optional.of(prefs));
        when(prefs.getBrandTone()).thenReturn("FRIENDLY");
        when(prefs.getApprovalLevel()).thenReturn(0);
        when(prefs.isRepresented()).thenReturn(true);
        when(prefs.getAgencyName()).thenReturn("Zylo Talent Partners");
        when(prefs.isConsentAccepted()).thenReturn(true);

        var creatorContext =
                (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse)
                        service.assemble(WORKSPACE_ID, "CREATOR");
        assertTrue(creatorContext.represented());
        assertEquals("Zylo Talent Partners", creatorContext.agencyName());
    }

    @Test
    @DisplayName(
            "Priya gate review defect 2 -- agency_name is null (and omitted from the wire payload"
                    + " by @JsonInclude(NON_NULL)) when the creator has no preferences row")
    void testCreatorAudienceAgencyNameNullWithNoPreferencesRow() {
        com.influora.domain.entity.CreatorProfile profile = mock(com.influora.domain.entity.CreatorProfile.class);
        when(creatorProfileRepository.findByUserId(WORKSPACE_ID)).thenReturn(Optional.of(profile));
        when(profile.getId()).thenReturn("profile1");
        when(profile.getDisplayName()).thenReturn("Priya Shah");
        when(profile.getCity()).thenReturn("Pune");
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getTotalFollowers()).thenReturn(12_400L);
        when(profile.getGstin()).thenReturn(null);
        when(profile.getIdentityKycStatus()).thenReturn(com.influora.domain.enums.VerificationStatus.VERIFIED);
        when(profile.getTierOverride()).thenReturn(null);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile1")).thenReturn(Optional.empty());
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq("profile1"), any()))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(WORKSPACE_ID)).thenReturn(List.of());

        var creatorContext =
                (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse)
                        service.assemble(WORKSPACE_ID, "CREATOR");
        assertFalse(creatorContext.represented());
        assertEquals(null, creatorContext.agencyName());
    }

    @Test
    @DisplayName(
            "Gate fix round 1 (Priya Q7): a creator with an admin-set ai_monthly_cap_usd override"
                    + " has it rendered as a 2-decimal string on the CREATOR context payload, and it"
                    + " is omitted entirely when unset")
    void testCreatorAudienceSurfacesAiMonthlyCapUsdOverride() {
        com.influora.domain.entity.CreatorProfile profile = mock(com.influora.domain.entity.CreatorProfile.class);
        when(creatorProfileRepository.findByUserId(WORKSPACE_ID)).thenReturn(Optional.of(profile));
        when(profile.getId()).thenReturn("profile1");
        when(profile.getDisplayName()).thenReturn("Priya Shah");
        when(profile.getCity()).thenReturn("Pune");
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getTotalFollowers()).thenReturn(0L);
        when(profile.getGstin()).thenReturn(null);
        when(profile.getIdentityKycStatus()).thenReturn(com.influora.domain.enums.VerificationStatus.UNVERIFIED);
        when(profile.getTierOverride()).thenReturn(null);
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq("profile1"), any()))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(WORKSPACE_ID)).thenReturn(List.of());

        com.influora.domain.entity.CreatorAgentPreferences prefsWithCap =
                mock(com.influora.domain.entity.CreatorAgentPreferences.class);
        when(prefsWithCap.getAiMonthlyCapUsd()).thenReturn(new java.math.BigDecimal("2.50"));
        when(prefsWithCap.getBrandTone()).thenReturn("FRIENDLY");
        when(prefsWithCap.getApprovalLevel()).thenReturn(0);
        when(prefsWithCap.isRepresented()).thenReturn(false);
        when(prefsWithCap.isConsentAccepted()).thenReturn(false);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile1")).thenReturn(Optional.of(prefsWithCap));

        var withCap =
                (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse)
                        service.assemble(WORKSPACE_ID, "CREATOR");
        assertEquals("2.50", withCap.aiMonthlyCapUsd());

        com.influora.domain.entity.CreatorAgentPreferences prefsNoCap =
                mock(com.influora.domain.entity.CreatorAgentPreferences.class);
        when(prefsNoCap.getAiMonthlyCapUsd()).thenReturn(null);
        when(prefsNoCap.getBrandTone()).thenReturn("FRIENDLY");
        when(prefsNoCap.getApprovalLevel()).thenReturn(0);
        when(prefsNoCap.isRepresented()).thenReturn(false);
        when(prefsNoCap.isConsentAccepted()).thenReturn(false);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile1")).thenReturn(Optional.of(prefsNoCap));

        var withoutCap =
                (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse)
                        service.assemble(WORKSPACE_ID, "CREATOR");
        assertEquals(null, withoutCap.aiMonthlyCapUsd());
    }

    @Test
    @DisplayName("unknown audience value is rejected the same way as CREATOR")
    void testUnknownAudienceGuarded() {
        assertThrows(ApiException.class, () -> service.assemble(WORKSPACE_ID, "SOMETHING_ELSE"));
    }

    @Test
    @DisplayName(
            "Gate fix round 1 (Priya Q6/Q9.6) -- a creator who never connected Instagram and never"
                    + " self-reported a follower count gets NO 'followers' key at all, so Meera's honest"
                    + " 'Instagram not connected yet' branch fires instead of a fabricated '0 followers'")
    void testCreatorAudienceUnconnectedNoMetricsOmitsFollowersKey() {
        com.influora.domain.entity.CreatorProfile profile = mock(com.influora.domain.entity.CreatorProfile.class);
        when(creatorProfileRepository.findByUserId(WORKSPACE_ID)).thenReturn(Optional.of(profile));
        when(profile.getId()).thenReturn("profile1");
        when(profile.getDisplayName()).thenReturn("Priya Shah");
        when(profile.getCity()).thenReturn("Pune");
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getTotalFollowers()).thenReturn(0L);
        when(profile.getEngagementRate()).thenReturn(null);
        when(profile.getGstin()).thenReturn(null);
        when(profile.getIdentityKycStatus()).thenReturn(com.influora.domain.enums.VerificationStatus.UNVERIFIED);
        when(profile.getTierOverride()).thenReturn(null);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile1")).thenReturn(Optional.empty());
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq("profile1"), any()))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(WORKSPACE_ID)).thenReturn(List.of());

        Object result = service.assemble(WORKSPACE_ID, "CREATOR");

        var creatorContext = (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse) result;
        assertFalse(
                creatorContext.metricsSummary().containsKey("followers"),
                "expected no 'followers' key for an unconnected creator with no self-reported total, got: "
                        + creatorContext.metricsSummary());
    }

    @Test
    @DisplayName(
            "Gate fix round 1 (Priya Q6/Q9.6) -- a creator with no verified CreatorMetric row but a"
                    + " nonzero self-reported onboarding follower count is labelled 'self-reported, not"
                    + " verified' rather than presented as a Meta-verified fact")
    void testCreatorAudienceSelfReportedFollowersAreLabelled() {
        com.influora.domain.entity.CreatorProfile profile = mock(com.influora.domain.entity.CreatorProfile.class);
        when(creatorProfileRepository.findByUserId(WORKSPACE_ID)).thenReturn(Optional.of(profile));
        when(profile.getId()).thenReturn("profile1");
        when(profile.getDisplayName()).thenReturn("Priya Shah");
        when(profile.getCity()).thenReturn("Pune");
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getTotalFollowers()).thenReturn(12_400L);
        when(profile.getEngagementRate()).thenReturn(null);
        when(profile.getGstin()).thenReturn(null);
        when(profile.getIdentityKycStatus()).thenReturn(com.influora.domain.enums.VerificationStatus.UNVERIFIED);
        when(profile.getTierOverride()).thenReturn(null);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile1")).thenReturn(Optional.empty());
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq("profile1"), any()))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(WORKSPACE_ID)).thenReturn(List.of());

        Object result = service.assemble(WORKSPACE_ID, "CREATOR");

        var creatorContext = (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse) result;
        assertEquals(
                "12,400 followers (self-reported, not verified)",
                creatorContext.metricsSummary().get("followers"));
    }

    @Test
    @DisplayName("workspace not found -> 404, never a silent empty context")
    void testWorkspaceNotFound() {
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class, () -> service.assemble(WORKSPACE_ID, "BRAND"));
        assertEquals("WORKSPACE_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("template_digest = SYSTEM templates always + this workspace's CUSTOM ones only")
    void testTemplateDigestIncludesSystemAndOwnCustomOnly() {
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getName()).thenReturn("Acme");
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(campaignRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of());

        CampaignTemplate systemTemplate =
                CampaignTemplate.builder()
                        .id("sys1")
                        .name("Brand Awareness")
                        .category(CampaignTemplateCategory.AWARENESS)
                        .scope(CampaignTemplateScope.SYSTEM)
                        .campaignType(CampaignIntentType.HYPE)
                        .build();
        CampaignTemplate customTemplate =
                CampaignTemplate.builder()
                        .id("custom1")
                        .name("My Playbook")
                        .category(CampaignTemplateCategory.CUSTOM)
                        .scope(CampaignTemplateScope.CUSTOM)
                        .workspaceId(WORKSPACE_ID)
                        .campaignType(CampaignIntentType.DIRECT)
                        .build();
        when(templateRepository.findByScope(CampaignTemplateScope.SYSTEM)).thenReturn(List.of(systemTemplate));
        when(templateRepository.findByScopeAndWorkspaceId(CampaignTemplateScope.CUSTOM, WORKSPACE_ID))
                .thenReturn(List.of(customTemplate));

        when(creditService.getStatus(WORKSPACE_ID))
                .thenReturn(BrandAiCredit.builder().workspaceId(WORKSPACE_ID).creditsRemaining(50).build());

        ContextResponse response = (ContextResponse) service.assemble(WORKSPACE_ID, "BRAND");

        assertEquals(2, response.templateDigest().size());
        assertTrue(response.templateDigest().stream().anyMatch(t -> t.name().equals("Brand Awareness")));
        assertTrue(response.templateDigest().stream().anyMatch(t -> t.name().equals("My Playbook")));
        // Never another workspace's CUSTOM template — the fetch itself is tenant-scoped by the
        // repository call, asserted here via the exact mock invocation above.
    }

    @Test
    @DisplayName("past_campaign_summary: creator_count = distinct creators via collaborations, funded reflects status")
    void testPastCampaignSummaryCreatorCountAndFunded() {
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getName()).thenReturn("Acme");
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(templateRepository.findByScope(CampaignTemplateScope.SYSTEM)).thenReturn(List.of());
        when(templateRepository.findByScopeAndWorkspaceId(CampaignTemplateScope.CUSTOM, WORKSPACE_ID))
                .thenReturn(List.of());

        Campaign fundedCampaign =
                Campaign.builder()
                        .id("camp1")
                        .workspaceId(WORKSPACE_ID)
                        .title("Live campaign")
                        .status(CampaignStatus.ACTIVE)
                        .createdBy("user1")
                        .campaignType(CampaignIntentType.HYPE)
                        .build();
        Campaign draftCampaign =
                Campaign.builder()
                        .id("camp2")
                        .workspaceId(WORKSPACE_ID)
                        .title("Draft campaign")
                        .status(CampaignStatus.DRAFT)
                        .createdBy("user1")
                        .campaignType(CampaignIntentType.DIRECT)
                        .build();
        when(campaignRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(fundedCampaign, draftCampaign));

        Collaboration c1 = Collaboration.invite("collab1", "camp1", "creator1", null, "INR");
        Collaboration c2 = Collaboration.invite("collab2", "camp1", "creator2", null, "INR");
        // Duplicate creator on the same campaign must not double-count.
        Collaboration c3 = Collaboration.invite("collab3", "camp1", "creator1", null, "INR");
        when(collaborationRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(c1, c2, c3));

        when(creditService.getStatus(WORKSPACE_ID))
                .thenReturn(BrandAiCredit.builder().workspaceId(WORKSPACE_ID).creditsRemaining(10).build());

        ContextResponse response = (ContextResponse) service.assemble(WORKSPACE_ID, "BRAND");

        assertEquals(2, response.pastCampaignSummary().size());
        var funded = response.pastCampaignSummary().stream().filter(p -> p.type().equals("HYPE")).findFirst().get();
        assertEquals(2, funded.creatorCount());
        assertEquals(true, funded.funded());
        var draft = response.pastCampaignSummary().stream().filter(p -> p.type().equals("DIRECT")).findFirst().get();
        assertEquals(0, draft.creatorCount());
        assertEquals(false, draft.funded());
    }
}
