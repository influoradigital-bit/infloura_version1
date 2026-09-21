package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.math.BigDecimal;
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
                        creatorMetricsRepository,
                        org.mockito.Mockito.mock(com.influora.service.analytics.AnalyticsService.class));
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
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq("profile1"), eq("META_API"), any()))
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

        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq("profile1"), eq("META_API"), any()))
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
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq("profile1"), eq("META_API"), any()))
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
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq("profile1"), eq("META_API"), any()))
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
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq("profile1"), eq("META_API"), any()))
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

    /**
     * SPEC.md &sect;3.1 catalogue order, and the five tools that have a route TODAY. Declared here
     * as a literal rather than read from {@code CreatorToolScopes} -- a test that imported the
     * production list would agree with it no matter what it said.
     */
    private static final List<String> WIRED_CREATOR_TOOLS =
            List.of(
                    "get_my_deals",
                    "get_brief",
                    "estimate_my_rate",
                    "get_my_metrics",
                    "check_deal_risks");

    @Test
    @DisplayName(
            "[QA Wave 2 blocker] the ASSEMBLED CREATOR context carries a non-empty tools_enabled"
                    + " naming exactly the five wired creator tools -- CreatorToolScopes"
                    + ".toolNamesForLevel was green in isolation while this seam still shipped"
                    + " List.of(), which made the controller, every executor, the validator and the"
                    + " creator scope mint unreachable in production with every test on both sides"
                    + " passing")
    void testCreatorContextCarriesWiredToolNames() {
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
        // EV-008: the assembler now reads the Meta-verified row via
        // findByCreatorProfileIdAndDataSourceOrderByTimeDesc(id, DATA_SOURCE_META_API, ...), not the
        // unfiltered findByCreatorProfileIdOrderByTimeDesc this stub used to target pre-merge (that
        // overload is asserted NEVER called at line ~547's
        // `verify(creatorMetricsRepository, never()).findByCreatorProfileIdOrderByTimeDesc(...)`).
        // Stubbing the no-longer-called overload here left this the one test in the file still
        // pointed at the old collaborator, which is exactly what Mockito's strict-stub
        // UnnecessaryStubbingException flagged after the EV-008/B0 merge.
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq("profile1"), eq("META_API"), any()))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(WORKSPACE_ID)).thenReturn(List.of());

        com.influora.domain.entity.CreatorAgentPreferences prefs =
                mock(com.influora.domain.entity.CreatorAgentPreferences.class);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile1")).thenReturn(Optional.of(prefs));
        when(prefs.getBrandTone()).thenReturn("FRIENDLY");
        when(prefs.getApprovalLevel()).thenReturn(0);
        when(prefs.isRepresented()).thenReturn(false);
        when(prefs.isConsentAccepted()).thenReturn(true);

        var creatorContext =
                (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse)
                        service.assemble(WORKSPACE_ID, "CREATOR");

        // The bidirectional tripwire, and the actual lesson of Wave 2: a class and its call site
        // are one change. Compared against the WIDEST scope (level 2, not represented), whose
        // toolNamesForLevel is CreatorToolScopes.WIRED_TOOL_NAMES in full -- so this is a set
        // equality between "what the assembler can ever offer" and "what the controller can
        // answer", and it fails from either side alone:
        //   - a name added to WIRED_TOOL_NAMES with no @PostMapping -> left side bigger;
        //   - a @PostMapping added without the name -> right side bigger, dead code that no
        //     production traffic can reach, which is exactly how Wave 2 shipped.
        // Both were run against this assertion before it was committed, and again when get_brief
        // made it five.
        //
        // Deliberately FIRST, ahead of the WIRED_CREATOR_TOOLS literal below. With the literal
        // first, a route added without its name was reported as "the list is the wrong length" --
        // true, but it named neither half of the seam, and it meant this reflective check was never
        // the assertion that fired for that half. Now it is the one that fires for both.
        assertEquals(
                new java.util.TreeSet<>(CreatorToolScopes.toolNamesForLevel(2, false, false)),
                creatorMeeraToolRoutes(),
                "the tools the assembler can offer and the routes the controller serves have"
                        + " diverged -- one of them was changed without the other");

        // The assertion that would have caught the gap. An empty list here degrades to `tools = []`
        // on the Python side, so the model is never offered a creator tool and nothing downstream
        // of this response can ever be entered.
        assertFalse(
                creatorContext.toolsEnabled().isEmpty(),
                "tools_enabled is empty: the model is offered no creator tool and the entire Wave 2"
                        + " surface is dead in production");
        assertEquals(WIRED_CREATOR_TOOLS, creatorContext.toolsEnabled());

        // Every offered name must be a declared CreatorToolName. Necessary but NOT sufficient --
        // all nine of SPEC.md 3.1's tools will eventually be constants, so this alone cannot tell
        // a wired tool from a planned one. The route assertion above is the one that can.
        for (String name : creatorContext.toolsEnabled()) {
            assertTrue(
                    com.influora.domain.enums.CreatorToolName.parse(name).isPresent(),
                    "tools_enabled offers a name with no route: " + name);
        }

        // The three flags the wire carries and the three that decided the offer are the same
        // reading of the same row -- what the hoisted locals at the call site exist to guarantee.
        assertEquals(
                CreatorToolScopes.toolNamesForLevel(
                        creatorContext.approvalLevel(),
                        creatorContext.represented(),
                        creatorContext.negotiationHoldout()),
                creatorContext.toolsEnabled(),
                "tools_enabled disagrees with the approval_level/represented/holdout it ships beside");

        // An agency-represented creator is on the reads-only scope, and all five wired tools are
        // reads (get_brief included), so she is still offered all five -- representation must not
        // silently blank or shorten the tool list.
        com.influora.domain.entity.CreatorAgentPreferences representedPrefs =
                mock(com.influora.domain.entity.CreatorAgentPreferences.class);
        when(representedPrefs.getBrandTone()).thenReturn("FRIENDLY");
        when(representedPrefs.getApprovalLevel()).thenReturn(0);
        when(representedPrefs.isRepresented()).thenReturn(true);
        when(representedPrefs.isConsentAccepted()).thenReturn(true);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile1"))
                .thenReturn(Optional.of(representedPrefs));

        var representedContext =
                (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse)
                        service.assemble(WORKSPACE_ID, "CREATOR");
        assertTrue(representedContext.represented());
        assertEquals(WIRED_CREATOR_TOOLS, representedContext.toolsEnabled());
    }

    /**
     * The {@code /internal/meera/creator/*} tool names {@link CreatorMeeraToolController} actually
     * serves, read off its {@code @PostMapping} annotations.
     *
     * <p>Reflection rather than a hand-kept list, because a hand-kept list is the same defect one
     * level up: it would have to be edited by the same person who forgot to edit
     * {@code WIRED_TOOL_NAMES}.
     */
    private static java.util.TreeSet<String> creatorMeeraToolRoutes() {
        java.util.TreeSet<String> routes = new java.util.TreeSet<>();
        for (java.lang.reflect.Method method :
                com.influora.web.CreatorMeeraToolController.class.getDeclaredMethods()) {
            org.springframework.web.bind.annotation.PostMapping mapping =
                    method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
            if (mapping == null) {
                continue;
            }
            for (String path : mapping.value()) {
                routes.add(path.startsWith("/") ? path.substring(1) : path);
            }
        }
        assertFalse(
                routes.isEmpty(),
                "no @PostMapping found on CreatorMeeraToolController -- this assertion cannot pass"
                        + " vacuously");
        return routes;
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
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq("profile1"), eq("META_API"), any()))
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
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq("profile1"), eq("META_API"), any()))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(WORKSPACE_ID)).thenReturn(List.of());

        Object result = service.assemble(WORKSPACE_ID, "CREATOR");

        var creatorContext = (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse) result;
        assertEquals(
                "12,400 followers (self-reported, not verified)",
                creatorContext.metricsSummary().get("followers"));
    }

    private com.influora.domain.entity.CreatorProfile ev008Profile(long total, String source, BigDecimal rate) {
        com.influora.domain.entity.CreatorProfile profile = mock(com.influora.domain.entity.CreatorProfile.class);
        when(creatorProfileRepository.findByUserId(WORKSPACE_ID)).thenReturn(Optional.of(profile));
        when(profile.getId()).thenReturn("profile1");
        when(profile.getDisplayName()).thenReturn("Priya Shah");
        when(profile.getCity()).thenReturn("Pune");
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getTotalFollowers()).thenReturn(total);
        when(profile.getFollowersSource()).thenReturn(source);
        // lenient: read only when no Meta row exists (the fallback path).
        org.mockito.Mockito.lenient().when(profile.getEngagementRate()).thenReturn(rate);
        when(profile.getGstin()).thenReturn(null);
        when(profile.getIdentityKycStatus()).thenReturn(com.influora.domain.enums.VerificationStatus.UNVERIFIED);
        when(profile.getTierOverride()).thenReturn(null);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile1")).thenReturn(Optional.empty());
        when(collaborationRepository.findByCreatorId(WORKSPACE_ID)).thenReturn(List.of());
        return profile;
    }

    private static com.influora.domain.entity.CreatorMetric ev008Metric(long followers, String dataSource) {
        return com.influora.domain.entity.CreatorMetric.builder()
                .id("01EV008METRIC0000000000000")
                .time(java.time.Instant.parse("2026-09-18T00:00:00Z"))
                .creatorProfileId("profile1")
                .platform("YOUTUBE")
                .username("priya")
                .followers(followers)
                .avgEngagementRate(new BigDecimal("9.9"))
                .dataSource(dataSource)
                .fetchedAt(java.time.Instant.parse("2026-09-18T00:00:00Z"))
                .build();
    }

    @Test
    @DisplayName(
            "EV-008 -- Meera reads ONLY Meta-synced metric rows: the any-source finder (which let a"
                    + " creator-declared 900,000 become a plain '900,000 followers') is never called")
    void testCreatorAudienceNeverReadsAnySourceMetricRow() {
        ev008Profile(0L, com.influora.service.FollowerTotals.NONE, null);
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq("profile1"), eq("META_API"), any()))
                .thenReturn(List.of());

        var creatorContext =
                (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse) service.assemble(WORKSPACE_ID, "CREATOR");

        verify(creatorMetricsRepository, never()).findByCreatorProfileIdOrderByTimeDesc(any(), any());
        assertFalse(creatorContext.metricsSummary().containsKey("followers"), creatorContext.metricsSummary().toString());
        assertFalse(creatorContext.metricsSummary().containsKey("engagement_rate"), creatorContext.metricsSummary().toString());
    }

    @Test
    @DisplayName("EV-008 -- a CREATOR_REPORTED row handed back by the source finder is still refused (second guard)")
    void testCreatorAudienceDeclaredRowIsFilteredEvenIfReturned() {
        ev008Profile(0L, com.influora.service.FollowerTotals.NONE, null);
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq("profile1"), eq("META_API"), any()))
                .thenReturn(List.of(ev008Metric(900_000L, com.influora.domain.entity.CreatorMetric.DATA_SOURCE_CREATOR_REPORTED)));

        var creatorContext =
                (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse) service.assemble(WORKSPACE_ID, "CREATOR");

        assertFalse(creatorContext.metricsSummary().containsKey("followers"), creatorContext.metricsSummary().toString());
        assertFalse(creatorContext.metricsSummary().containsKey("engagement_rate"), creatorContext.metricsSummary().toString());
    }

    @Test
    @DisplayName("EV-008 -- a Meta-synced row is quoted plainly (no provenance suffix)")
    void testCreatorAudienceMetaRowIsPlain() {
        ev008Profile(0L, com.influora.service.FollowerTotals.NONE, null);
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq("profile1"), eq("META_API"), any()))
                .thenReturn(List.of(ev008Metric(12_400L, com.influora.domain.entity.CreatorMetric.DATA_SOURCE_META_API)));

        var creatorContext =
                (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse) service.assemble(WORKSPACE_ID, "CREATOR");

        assertEquals("12,400 followers", creatorContext.metricsSummary().get("followers"));
        assertEquals("9.9% engagement", creatorContext.metricsSummary().get("engagement_rate"));
    }

    @Test
    @DisplayName("EV-008 -- IMPORTED profile totals (Marketplace/admin import) are labelled 'imported, not verified'")
    void testCreatorAudienceImportedTotalsAreLabelled() {
        ev008Profile(50_000L, com.influora.service.FollowerTotals.IMPORTED, new BigDecimal("3.2"));
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq("profile1"), eq("META_API"), any()))
                .thenReturn(List.of());

        var creatorContext =
                (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse) service.assemble(WORKSPACE_ID, "CREATOR");

        assertEquals("50,000 followers (imported, not verified)", creatorContext.metricsSummary().get("followers"));
        assertEquals("3.2% engagement (imported, not verified)", creatorContext.metricsSummary().get("engagement_rate"));
    }

    @Test
    @DisplayName("EV-008 -- VERIFIED profile totals (sum of Meta-synced platforms) carry no 'not verified' suffix")
    void testCreatorAudienceVerifiedTotalsArePlain() {
        ev008Profile(30_000L, com.influora.service.FollowerTotals.VERIFIED, new BigDecimal("4.5"));
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq("profile1"), eq("META_API"), any()))
                .thenReturn(List.of());

        var creatorContext =
                (com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse) service.assemble(WORKSPACE_ID, "CREATOR");

        assertEquals("30,000 followers", creatorContext.metricsSummary().get("followers"));
        assertEquals("4.5% engagement", creatorContext.metricsSummary().get("engagement_rate"));
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
