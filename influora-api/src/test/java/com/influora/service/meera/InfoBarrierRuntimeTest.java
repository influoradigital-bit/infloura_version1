package com.influora.service.meera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorAgentPreferences;
import com.influora.domain.entity.CreatorMetric;
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
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DealOfferHistoryRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.AuditLogService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.FollowerTotals;
import com.influora.service.rates.RateAddOns;
import com.influora.service.rates.RateQuoteService;
import com.influora.service.rates.RateTierProperties;
import com.influora.service.scoring.QualityScoreService;
import com.influora.service.scoring.RateEstimationService;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.deal.DealDtos.DeliverableSlot;
import com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
                        creatorMetricsRepository,
                        org.mockito.Mockito.mock(com.influora.service.analytics.AnalyticsService.class),
                        org.mockito.Mockito.mock(com.influora.repository.MetaOAuthTokenRepository.class));
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

        // D-01 (PENDING-0912.md; QA-TECH-0912.md q6) — scan for the floor KEY, not just the floor
        // VALUE. Every assertion above this line tests a distinctive value (99999 / an agency name),
        // so a floor field that is PRESENT but empty, null, zero, or holding a value this test did
        // not seed passes all of them. The static half of this barrier
        // (com.influora.architecture.FloorBarrierTest) proves no DECLARED field carries a floor key;
        // it cannot see this route at all, because MeeraInternalController#context is declared
        // ResponseEntity<ApiResponse<Object>> and is the one handler a BRAND caller and a CREATOR
        // caller share. So the key scan belongs here, on the assembled object, where it also covers
        // a floor written into a Map<String, ?> under a key computed at runtime.
        assertThat(floorKeysIn(mapper.readTree(json)))
                .as(
                        "the BRAND context carries a floor key. The floor is the creator's negotiating"
                                + " position and a brand reading it is the worst outcome this feature"
                                + " can produce -- the key must not be on a brand payload even when"
                                + " its value happens to be null or empty. Brand context JSON: %s",
                        json)
                .isEmpty();

        // Structural proof: the BRAND path never even reads the repository that holds floors
        // (and agency names).
        verifyNoInteractions(creatorAgentPreferencesRepository);
    }

    /**
     * D-01 — the positive control for {@link #floorKeysIn}, and the reason the empty-set assertion
     * above is not vacuous.
     *
     * <p>A scanner that returns an empty set for every input would make {@code
     * brandContextNeverExposesCreatorFloor}'s key assertion pass forever. So the same scanner is
     * pointed at a payload that genuinely DOES carry a floor: a creator's own CREATOR context, whose
     * {@code CreatorContextResponse} declares {@code @JsonProperty("floors")} and {@code
     * floor_currency}. If the scan stops working, this test goes red first.
     */
    @Test
    @DisplayName(
            "D-01 — the floor-key scanner is proven against a payload that DOES carry a floor: a"
                    + " creator's own CREATOR context")
    void floorKeyScannerFindsTheFloorOnACreatorContext() throws Exception {
        String creatorUserId = "01J2CREATORAUSER";
        stubCreator(creatorUserId, "creatorA-profile", "Creator A", LEAK_FLOOR_RAW, LEAK_AGENCY_NAME);

        String json = mapper.writeValueAsString(service.assemble(creatorUserId, "CREATOR"));

        assertThat(floorKeysIn(mapper.readTree(json)))
                .as(
                        "the floor-key scanner found no floor key on a CREATOR context, which is"
                                + " documented to carry one -- the scanner is broken, and the"
                                + " empty-set assertion in brandContextNeverExposesCreatorFloor is"
                                + " therefore passing vacuously. CREATOR context JSON: %s",
                        json)
                .contains("floors");
    }

    /**
     * Every JSON object key anywhere in the tree that names, or renders, a creator floor. Kept in
     * step with {@code com.influora.architecture.FloorBarrierTest.FLOOR_JSON_KEYS} — that class
     * enforces the same names over DECLARED types, this one over a SERIALISED payload.
     */
    private static final java.util.Set<String> FLOOR_JSON_KEYS =
            java.util.Set.of(
                    "floor",
                    "floors",
                    "floor_total",
                    "floor_total_value",
                    "floor_value",
                    "floor_currency",
                    "rate_floor",
                    "anchor",
                    "anchor_value",
                    "range_min",
                    "range_max",
                    "quote_json");

    private static java.util.Set<String> floorKeysIn(com.fasterxml.jackson.databind.JsonNode node) {
        java.util.Set<String> found = new java.util.LinkedHashSet<>();
        collectFloorKeys(node, found);
        return found;
    }

    private static void collectFloorKeys(
            com.fasterxml.jackson.databind.JsonNode node, java.util.Set<String> into) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            java.util.Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (FLOOR_JSON_KEYS.contains(name)) {
                    into.add(name);
                }
                collectFloorKeys(node.get(name), into);
            }
            return;
        }
        if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                collectFloorKeys(child, into);
            }
        }
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

        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq(profileId), eq("META_API"), any())).thenReturn(List.of());
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

    /**
     * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;14.1.f, B0-33) — the OTHER end of the same barrier.
     *
     * <p>The two tests above stop a floor crossing into a brand's context. This one stops a floor
     * crossing into {@code audit_log}, which is a different reader with a different threat model:
     * the table is append-only, permanently retained, and read by operators who are not that
     * creator. A quote's audit row exists so quoted-vs-realised is measurable after launch
     * (&sect;14.5.c metric 4) — it does not need, and must never carry, the creator's private
     * negotiating floor.
     *
     * <p>The allow-list is declared HERE, in the test, and is never read from
     * {@code RateQuoteService}. A test that imported the service's own list would go green no
     * matter what the service added to the map, which is precisely the leak this is a gate
     * against.
     */
    @Nested
    @DisplayName("14.1.f — RATE_QUOTE_ISSUED audit detail")
    class RateQuoteAuditDetail {

        private static final String PROFILE_ID = "01J2AUDITBARRIERPROFILE";
        private static final String USER_ID = "01J2AUDITBARRIERUSER000";

        /** A floor value distinctive enough that it cannot collide with a legitimate figure. */
        private static final String LEAK_FLOOR = "97531";

        private static final List<String> ALLOWED_DETAIL_KEYS =
                List.of(
                        "tier",
                        "provenance",
                        "sample",
                        "total",
                        "anchor",
                        "currency",
                        "deliverable_count",
                        "context");

        @Mock private CreatorAgentPreferencesService preferencesService;
        @Mock private CollaborationRepository quoteCollaborationRepository;
        @Mock private DealMessageRepository dealMessageRepository;
        @Mock private DealOfferHistoryRepository dealOfferHistoryRepository;
        @Mock private CreatorMetricsRepository quoteCreatorMetricsRepository;
        @Mock private MediaMetricsRepository mediaMetricsRepository;
        @Mock private QualityScoreService qualityScoreService;
        @Mock private AuditLogService auditLogService;

        @Test
        @DisplayName("A7(c) — the audit detail carries only the allow-listed keys, and no floor value")
        void auditDetailCarriesNoFloor() {
            RateQuoteService quoteService =
                    new RateQuoteService(
                            preferencesService,
                            quoteCollaborationRepository,
                            dealMessageRepository,
                            dealOfferHistoryRepository,
                            quoteCreatorMetricsRepository,
                            mediaMetricsRepository,
                            qualityScoreService,
                            new RateEstimationService(),
                            new RateAddOns(
                                    RateAddOns.REPOST_30D_PCT_DEFAULT,
                                    RateAddOns.PAID_ADS_QUARTER_PCT_DEFAULT,
                                    RateAddOns.WHITELISTING_PCT_DEFAULT,
                                    RateAddOns.PERPETUITY_MULTIPLE_DEFAULT,
                                    RateAddOns.EXCLUSIVITY_30D_FLOOR_PCT_DEFAULT),
                            new RateTierProperties(
                                    null, null, null, null, null, null, null, null, null, null),
                            auditLogService);

            CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Barrier Creator");
            profile.applyAdminProfileEdit("Barrier Creator", "[\"BEAUTY\"]");
            profile.applyFollowerTotals(
                    new FollowerTotals(3_000L, new BigDecimal("2.4"), FollowerTotals.VERIFIED));

            when(quoteCollaborationRepository.findByCreatorId(USER_ID)).thenReturn(List.of());
            when(quoteCollaborationRepository.findRateBandCandidates("BEAUTY")).thenReturn(List.of());
            // A metric row IS present, so the quote prices from the benchmark formula and the unit
            // is NOT the floor. Without it the "your floor" branch would make the total legitimately
            // equal the floor and this assertion could not tell a leak from a correct quote.
            when(quoteCreatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                            eq(PROFILE_ID), any()))
                    .thenReturn(
                            List.of(
                                    CreatorMetric.builder()
                                            .id("01J2AUDITBARRIERMETRIC1")
                                            .creatorProfileId(PROFILE_ID)
                                            .platform("INSTAGRAM")
                                            .followers(3_000L)
                                            .avgEngagementRate(new BigDecimal("2.4"))
                                            .dataSource("META_API")
                                            .time(Instant.now())
                                            .build()));
            when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(PROFILE_ID), any()))
                    .thenReturn(List.of());
            when(qualityScoreService.calculate(any(), any()))
                    .thenReturn(QualityScoreService.QualityScoreResult.absent());

            quoteService.quote(
                    profile,
                    barrierPrefs(),
                    List.of(new DeliverableSlot("REEL", 1)),
                    List.of(),
                    null,
                    Locale.forLanguageTag("en-IN"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService)
                    .recordCreatorEvent(eq(USER_ID), eq("RATE_QUOTE_ISSUED"), eq("ALLOWED"), detail.capture());

            assertThat(detail.getValue())
                    .withFailMessage(
                            "RATE_QUOTE_ISSUED detail gained a key outside the allow-list %s: %s",
                            ALLOWED_DETAIL_KEYS, detail.getValue().keySet())
                    .containsOnlyKeys(ALLOWED_DETAIL_KEYS.toArray(new String[0]));

            assertThat(detail.getValue().values().stream().map(String::valueOf))
                    .withFailMessage(
                            "the creator's private floor (%s) reached the append-only audit table: %s",
                            LEAK_FLOOR, detail.getValue())
                    .noneMatch(value -> value.contains(LEAK_FLOOR));
        }

        /**
         * All three floors set to the distinctive leak value. The quote itself prices from the
         * benchmark (3,750 for this fixture), so every floor-derived figure — the per-type floor,
         * the floor total, the below-floor flags — exists inside the quote and none of it may
         * appear in the audit map.
         */
        private PreferencesResponse barrierPrefs() {
            BigDecimal floor = new BigDecimal(LEAK_FLOOR);
            return new PreferencesResponse(
                    floor, floor, floor, "INR", List.of(), List.of(), 0, "en-IN", "FRIENDLY", null,
                    null, "Asia/Kolkata", List.of(), null, false, null, true, "v1", false, null,
                    false, 0, false);
        }
    }
}
