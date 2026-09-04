package com.influora.service.meera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorAgentPreferences;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CampaignTemplateScope;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignTemplateRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorAgentPreferencesRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse;
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
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 5.2, A7b) — runtime info-barrier proof. Uses a distinctive,
 * never-otherwise-occurring rate floor ({@code 99999.99} — SPEC.md &sect;9's own risk mitigation:
 * an ordinary value like "9999" risks a false positive against an unrelated numeric field) and
 * asserts:
 * <ol>
 *   <li>the BRAND context for a workspace never contains it, and — stronger than a string-absence
 *       check — the BRAND code path never even queries {@link CreatorAgentPreferencesRepository}
 *       at all;</li>
 *   <li>a SECOND creator's own CREATOR context never contains the FIRST creator's floor, proven
 *       comparatively: both contexts are assembled in the same test, each renders its OWN floor
 *       and never the other's.</li>
 * </ol>
 *
 * <p>Uses Mockito, not {@code @SpringBootTest} — this codebase has no full-Spring-context unit
 * test anywhere (see the sibling {@code MeeraContextServiceTest}), so this stays consistent with
 * that and needs no test database or Docker.
 */
@ExtendWith(MockitoExtension.class)
class InfoBarrierRuntimeTest {

    // Whole-number floors deliberately (not "99999.99") — MeeraContextService's A8 number
    // formatting (NumberFormat.getIntegerInstance) rounds a fractional value, so a .99 fraction
    // would render as "1,00,000" rather than "99,999" and silently defeat this test's string
    // assertions below. SPEC.md's own risk mitigation (&sect;9) only asks for a value distinctive
    // enough not to collide with an unrelated field — 99999 already satisfies that.
    private static final String LEAK_FLOOR_RAW = "99999.00";
    private static final String OTHER_FLOOR_RAW = "111.00";

    // Priya gate review defect 2 — a seeded, distinctive agency name that must never appear
    // outside a CREATOR context, and never leak between two creators' own CREATOR contexts.
    private static final String LEAK_AGENCY_NAME = "Zylo Talent Partners LEAK9999";
    private static final String OTHER_AGENCY_NAME = "Marigold Creator Mgmt OTHER1111";

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private BrandProfileRepository brandProfileRepository;
    @Mock private CampaignTemplateRepository templateRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private DeliverableMetricRepository deliverableMetricRepository;
    @Mock private UtmCampaignRepository utmCampaignRepository;
    @Mock private AICreditService creditService;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CreatorAgentPreferencesRepository creatorAgentPreferencesRepository;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private Workspace workspace;

    private MeeraContextService service;
    private final ObjectMapper mapper = new ObjectMapper();

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

    /**
     * Fix round 1, item 5 — the original version of this test fed NO creator-linked data into any
     * brand-path mock (an empty {@code collaborationRepository.findByWorkspaceId}), so the
     * {@code doesNotContain} assertions below were trivially true regardless of whether the info
     * barrier actually worked: there was nothing for a bug to leak in the first place. This
     * version seeds a REAL collaboration for the SAME creator whose floor is
     * {@link #LEAK_FLOOR_RAW} — a legitimate, brand-visible deal at a DIFFERENT rate ({@code
     * SEEDED_DEAL_RATE}) — into the workspace's own collaborations, so it flows through {@code
     * MeeraContextService#buildPastCampaignSummary}/{@code buildOutcomeDigest} into the actual
     * JSON payload (via {@code creator_count}, non-trivial now instead of an empty list). The
     * assertions then prove something real: even with this creator's own (legitimate) deal data
     * present in the brand context, their Meera rate-FLOOR preference specifically never appears.
     */
    @Test
    @DisplayName(
            "A7(b) — BRAND context never contains a creator's rate floor (even when that SAME"
                    + " creator has a real, legitimately brand-visible collaboration seeded into the"
                    + " workspace), and never even queries the floor repository")
    void brandContextNeverExposesCreatorFloor() throws Exception {
        String workspaceId = "01J2TESTWORKSPACE";
        String campaignId = "01J2TESTCAMPAIGN0001";
        // The same creator user id InfoBarrierRuntimeTest's CREATOR-audience test below uses for
        // LEAK_FLOOR_RAW's owner — proving the barrier holds even for THIS specific creator's own
        // legitimately brand-visible deal data, not just an arbitrary/unrelated one.
        String sameCreatorUserId = "01J2CREATORAUSER";

        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
        when(workspace.getId()).thenReturn(workspaceId);
        when(workspace.getName()).thenReturn("Acme");
        when(brandProfileRepository.findByWorkspaceId(workspaceId)).thenReturn(Optional.empty());
        when(templateRepository.findByScope(CampaignTemplateScope.SYSTEM)).thenReturn(List.of());
        when(templateRepository.findByScopeAndWorkspaceId(CampaignTemplateScope.CUSTOM, workspaceId))
                .thenReturn(List.of());

        Campaign campaign =
                Campaign.builder()
                        .id(campaignId)
                        .workspaceId(workspaceId)
                        .status(CampaignStatus.ACTIVE)
                        .campaignType(CampaignIntentType.STANDARD)
                        // Builder#build() stamps createdAt/updatedAt to Instant.now() itself.
                        .build();
        when(campaignRepository.findByWorkspaceId(workspaceId)).thenReturn(List.of(campaign));

        // getStatus()/getAgreedRate() are deliberately NOT stubbed -- MeeraContextService's BRAND
        // path (buildPastCampaignSummary/buildOutcomeDigest) never reads either off a Collaboration
        // (status/rate feed CREATOR-side buildDealsSummary only); campaignId/creatorId are the only
        // two fields the brand path actually consumes here.
        Collaboration seededDeal = mock(Collaboration.class);
        when(seededDeal.getCampaignId()).thenReturn(campaignId);
        when(seededDeal.getCreatorId()).thenReturn(sameCreatorUserId);
        when(collaborationRepository.findByWorkspaceId(workspaceId)).thenReturn(List.of(seededDeal));

        when(escrowHoldRepository.sumAmountByCampaignIdAndStatus(campaignId, EscrowStatus.RELEASED))
                .thenReturn(BigDecimal.ZERO);
        when(deliverableMetricRepository.findByCollaborationIdIn(any())).thenReturn(List.of());
        when(utmCampaignRepository.findByCampaignId(campaignId)).thenReturn(List.of());

        when(creditService.getStatus(workspaceId))
                .thenReturn(BrandAiCredit.builder().workspaceId(workspaceId).creditsRemaining(10).build());

        Object brandContext = service.assemble(workspaceId, "BRAND");
        String json = mapper.writeValueAsString(brandContext);

        // Sanity: the seeded deal actually reached the JSON (a non-trivial payload, not an empty
        // "nothing to leak" one) -- the creator_count field it drives is exactly 1. (The deal's
        // agreedRate itself, seededDealRate, is deliberately NOT asserted here: A1's allow-list
        // never surfaces a raw per-deal rate on the BRAND context at all -- only aggregate/derived
        // fields like creator_count and the escrow-sourced spend -- so asserting its presence
        // would be asserting a field this payload was never meant to carry.)
        assertThat(json).contains("\"creator_count\":1");

        // The Meera rate-FLOOR for this same creator must never appear, regardless.
        assertThat(json).doesNotContain(LEAK_FLOOR_RAW);
        assertThat(json).doesNotContain("99,999");
        // Priya gate review defect 2 — the creator's agency name (a CREATOR-only field, added to
        // CreatorContextResponse) must never appear in a BRAND context either. There is no way to
        // stub it into this path's data (see the verifyNoInteractions below — the BRAND path
        // never queries the repository agencyName lives in at all), so this is a defense-in-depth
        // literal check against the SAME distinctive marker the CREATOR-audience test below
        // proves is actually rendered for a CREATOR context.
        assertThat(json).doesNotContain(LEAK_AGENCY_NAME);
        assertThat(json).doesNotContain("agency_name");
        // Structural proof: the BRAND path never even reads the repository that holds floors
        // (and agency names).
        verifyNoInteractions(creatorAgentPreferencesRepository);
    }

    @Test
    @DisplayName("A7(b) — a creator's CREATOR context renders its OWN floor and never another creator's floor")
    void creatorContextNeverExposesAnotherCreatorsFloor() throws Exception {
        String creatorAUserId = "01J2CREATORAUSER";
        String creatorBUserId = "01J2CREATORBUSER";
        stubCreator(creatorAUserId, "creatorA-profile", "Creator A", LEAK_FLOOR_RAW, LEAK_AGENCY_NAME);
        stubCreator(creatorBUserId, "creatorB-profile", "Creator B", OTHER_FLOOR_RAW, OTHER_AGENCY_NAME);

        String jsonA = mapper.writeValueAsString(service.assemble(creatorAUserId, "CREATOR"));
        String jsonB = mapper.writeValueAsString(service.assemble(creatorBUserId, "CREATOR"));

        // Each creator sees only their own floor...
        assertThat(jsonA).contains("99,999");
        assertThat(jsonB).contains("111");
        // ...and never the other creator's.
        assertThat(jsonA).doesNotContain("111");
        assertThat(jsonB).doesNotContain(LEAK_FLOOR_RAW).doesNotContain("99,999");

        // Priya gate review defect 2 — same proof for agency_name: each creator sees only their
        // own agency name, never the other creator's.
        assertThat(jsonA).contains(LEAK_AGENCY_NAME);
        assertThat(jsonB).contains(OTHER_AGENCY_NAME);
        assertThat(jsonA).doesNotContain(OTHER_AGENCY_NAME);
        assertThat(jsonB).doesNotContain(LEAK_AGENCY_NAME);
    }

    private void stubCreator(String userId, String profileId, String displayName, String floorRaw) {
        stubCreator(userId, profileId, displayName, floorRaw, null);
    }

    private void stubCreator(
            String userId, String profileId, String displayName, String floorRaw, String agencyName) {
        CreatorProfile profile = mock(CreatorProfile.class);
        when(creatorProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(profile.getId()).thenReturn(profileId);
        when(profile.getDisplayName()).thenReturn(displayName);
        when(profile.getTierOverride()).thenReturn(null);
        when(profile.getTotalFollowers()).thenReturn(1000L);

        CreatorAgentPreferences prefs = mock(CreatorAgentPreferences.class);
        when(prefs.getReelFloor()).thenReturn(new BigDecimal(floorRaw));
        when(prefs.getStorySetFloor()).thenReturn(new BigDecimal(floorRaw));
        when(prefs.getPostFloor()).thenReturn(new BigDecimal(floorRaw));
        when(prefs.getCreatorLanguage()).thenReturn("en-IN");
        when(prefs.getBrandTone()).thenReturn("FRIENDLY");
        when(prefs.getApprovalLevel()).thenReturn(0);
        when(prefs.isRepresented()).thenReturn(agencyName != null);
        if (agencyName != null) {
            when(prefs.getAgencyName()).thenReturn(agencyName);
        }
        when(prefs.isConsentAccepted()).thenReturn(false);
        when(creatorAgentPreferencesRepository.findByCreatorId(profileId)).thenReturn(Optional.of(prefs));

        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(profileId), any())).thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(userId)).thenReturn(List.of());
    }

    /** Sanity check that the DTO shape itself is what we think it is, independent of the JSON string checks above. */
    @Test
    @DisplayName("A7(b) — CreatorContextResponse.identity carries only kyc_done/gstin_present, never a raw floor field name")
    void creatorContextResponseShapeIsInfoBarrierSafe() throws Exception {
        String userId = "01J2CREATORSHAPEUSER";
        stubCreator(userId, "creatorShape-profile", "Shape Creator", "500.00");

        Object result = service.assemble(userId, "CREATOR");
        assertThat(result).isInstanceOf(CreatorContextResponse.class);
        CreatorContextResponse response = (CreatorContextResponse) result;

        assertThat(response.identity().keySet()).containsExactlyInAnyOrder("kyc_done", "gstin_present");
        assertThat(response.floors()).containsOnlyKeys("reel_floor", "story_set_floor", "post_floor");
    }
}
