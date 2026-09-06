package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.DeliverableMetric;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.domain.enums.UserType;
import com.influora.web.dto.analytics.AnalyticsDtos.CampaignAnalyticsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.DeliverableMetricResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.DeliverableMetricSubmitRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P0 #3: Unit tests for DeliverableMetricService (wiki/tech/TASK-brand-audit-backend-build.md).
 * Priority: the honesty rule (source flag always CREATOR_REPORTED, never fabricated), the
 * ownership check, and the "reportable only once funded" gate.
 */
@ExtendWith(MockitoExtension.class)
class DeliverableMetricServiceTest {

    private static final String CREATOR_ID = "01HCREATOR12345678901";
    private static final String OTHER_USER_ID = "01HOTHER123456789012A";
    private static final String MILESTONE_ID = "01HMILESTONE1234567890";
    private static final String COLLAB_ID = "01HCOLLAB1234567890AB";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";

    @Mock private DeliverableMetricRepository deliverableMetricRepository;
    @Mock private PaymentMilestoneRepository milestoneRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private BrandContextService brandContext;

    private DeliverableMetricService service;

    @BeforeEach
    void setUp() {
        service =
                new DeliverableMetricService(
                        deliverableMetricRepository,
                        milestoneRepository,
                        collaborationRepository,
                        campaignRepository,
                        contractRepository,
                        brandContext);
    }

    @Test
    @DisplayName("submit: rejects a creator reporting on someone else's deliverable")
    void testSubmitRejectsWrongCreator() {
        PaymentMilestone milestone = milestoneWithStatus(MilestoneStatus.FUNDED);
        Collaboration collaboration = collaborationOwnedBy(CREATOR_ID);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration));

        AuthPrincipal intruder = new AuthPrincipal(OTHER_USER_ID, "x@x.com", UserType.CREATOR, null);
        DeliverableMetricSubmitRequest req =
                new DeliverableMetricSubmitRequest(100L, 1000L, 50L, "https://x.com/post", null);

        ApiException ex = assertThrows(ApiException.class, () -> service.submit(intruder, MILESTONE_ID, req));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    @DisplayName("submit: rejects reporting on a milestone that isn't funded/released yet")
    void testSubmitRejectsUnapprovedMilestone() {
        PaymentMilestone milestone = milestoneWithStatus(MilestoneStatus.PENDING);
        Collaboration collaboration = collaborationOwnedBy(CREATOR_ID);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration));

        AuthPrincipal creator = new AuthPrincipal(CREATOR_ID, "c@x.com", UserType.CREATOR, null);
        DeliverableMetricSubmitRequest req =
                new DeliverableMetricSubmitRequest(100L, 1000L, 50L, null, null);

        ApiException ex = assertThrows(ApiException.class, () -> service.submit(creator, MILESTONE_ID, req));
        assertEquals("DELIVERABLE_NOT_APPROVED", ex.getCode());
    }

    @Test
    @DisplayName("submit: rejects negative metric values")
    void testSubmitRejectsNegativeValues() {
        PaymentMilestone milestone = milestoneWithStatus(MilestoneStatus.FUNDED);
        Collaboration collaboration = collaborationOwnedBy(CREATOR_ID);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration));

        AuthPrincipal creator = new AuthPrincipal(CREATOR_ID, "c@x.com", UserType.CREATOR, null);
        DeliverableMetricSubmitRequest req = new DeliverableMetricSubmitRequest(-1L, 1000L, 50L, null, null);

        ApiException ex = assertThrows(ApiException.class, () -> service.submit(creator, MILESTONE_ID, req));
        assertEquals("INVALID_METRIC_VALUE", ex.getCode());
    }

    @Test
    @DisplayName("submit: accepted report always carries source = CREATOR_REPORTED")
    void testSubmitResponseCarriesHonestySourceFlag() {
        PaymentMilestone milestone = milestoneWithStatus(MilestoneStatus.FUNDED);
        Collaboration collaboration = collaborationOwnedBy(CREATOR_ID);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration));
        when(deliverableMetricRepository.findByMilestoneId(MILESTONE_ID)).thenReturn(Optional.empty());
        lenient()
                .when(deliverableMetricRepository.save(any(DeliverableMetric.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AuthPrincipal creator = new AuthPrincipal(CREATOR_ID, "c@x.com", UserType.CREATOR, null);
        DeliverableMetricSubmitRequest req =
                new DeliverableMetricSubmitRequest(500L, 5000L, 250L, "https://x.com/post", null);

        DeliverableMetricResponse response = service.submit(creator, MILESTONE_ID, req);

        assertEquals("CREATOR_REPORTED", response.source());
        assertEquals(500L, response.reach());
        assertEquals(5000L, response.impressions());
        assertEquals(250L, response.engagements());
    }

    @Test
    @DisplayName("getCampaignAnalytics: empty when no deliverables reported yet, not fake zeros with a false source")
    void testAnalyticsEmptyStateStillHonest() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());

        AuthPrincipal brandUser = new AuthPrincipal("brandUser", "b@x.com", UserType.BRAND, WORKSPACE_ID);
        CampaignAnalyticsResponse response =
                service.getCampaignAnalytics(brandUser, WORKSPACE_ID, CAMPAIGN_ID);

        assertEquals("CREATOR_REPORTED", response.source());
        assertEquals(0L, response.totalReach());
        assertEquals(0L, response.totalImpressions());
        assertEquals(0L, response.totalEngagements());
        assertNull(response.derivedEngagementRate());
        assertEquals(0, response.deliverablesReported());
        assertEquals(0, response.deliverables().size());
    }

    /**
     * [F-0644 / F-0653 correction] This test previously asserted the EXACT regression a
     * fresh-context CTO review caught and rejected: with {@code newContract} a still-unsigned
     * {@code DRAFT} amendment, it locked in {@code totalReach == 300} — i.e. it PROVED (and
     * guarded) that drafting an amendment silently dropped the funded predecessor's RELEASED
     * milestone metrics (reach 1000+2000=3000 -> 300) the instant the draft existed, before
     * anyone signed anything. The bare {@code findFirst()} this method used to call always took
     * the newest {@code (version, createdAt)} row with no regard for whether it was actually
     * signed, which is exactly what made that wrong number "pass".
     *
     * <p>Renamed and corrected: while {@code newContract} sits {@code DRAFT}/unsigned, the still-
     * {@code ACTIVE} {@code oldContract} is "current" (the same rule {@code
     * DealService#toDealResponse} already applied via F-0645 — now the SAME shared method,
     * {@link ContractService#resolveCurrentContract}, per the F-0653 fix), so BOTH of its
     * milestones/metrics must count, and the unsigned draft's milestone must NOT.
     */
    @Test
    @DisplayName(
            "getCampaignAnalytics: F-0644/F-0653 — while an amendment is an UNSIGNED DRAFT, its"
                    + " still-ACTIVE, funded predecessor stays \"current\" and its milestones (incl. a"
                    + " RELEASED one) keep counting; the draft's own milestone does not")
    void testAnalyticsKeepsFundedActivePredecessorCurrentWhileAmendmentIsUnsignedDraft() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        Collaboration collaboration = collaborationOwnedBy(CREATOR_ID);
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(collaboration));

        String oldContractId = "01HCONTRACTOLD12345678";
        String newContractId = "01HCONTRACTNEW12345678";
        Contract oldContract =
                Contract.builder().id(oldContractId).collaborationId(COLLAB_ID).workspaceId(WORKSPACE_ID)
                        .version(1).status(ContractStatus.ACTIVE).totalAmount(java.math.BigDecimal.TEN).build();
        Contract newContract =
                Contract.builder().id(newContractId).collaborationId(COLLAB_ID).workspaceId(WORKSPACE_ID)
                        .version(2).status(ContractStatus.DRAFT).totalAmount(java.math.BigDecimal.TEN).build();
        // Latest-version-first, exactly what findByCollaborationIdOrderByVersionDescCreatedAtDesc returns.
        List<Contract> contractVersions = List.of(newContract, oldContract);
        // [F-0657] The service now resolves "current" via a single batched
        // ContractRepository#findByWorkspaceId call instead of one
        // findByCollaborationIdOrderByVersionDescCreatedAtDesc call per collaboration — mock the
        // batched entry point; the service reproduces the version/createdAt-desc ordering itself.
        when(contractRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(contractVersions);

        // [F-0653 cross-check] The exact test that would have caught the original
        // disagreement: DealService's resolution (ContractService#resolveCurrentContract,
        // called directly here standing in for DealService#toDealResponse, which now delegates
        // to this identical method) and DeliverableMetricService's resolution (exercised below
        // via getCampaignAnalytics) must agree on which contract is "current" for this SAME
        // input. Before the fix these were two independently-written implementations that could
        // (and did) disagree; asserting this first pins the expected "current" contract for the
        // rest of the test to the one DealService would actually surface.
        Contract dealServiceCurrent = ContractService.resolveCurrentContract(contractVersions);
        assertEquals(
                oldContractId,
                dealServiceCurrent.getId(),
                "DealService's resolution must prefer the still-ACTIVE predecessor over the"
                        + " unsigned draft");

        // N=2 superseded (old-contract) milestones left persisted collaboration-keyed, plus M=1
        // current (new-contract) milestone — all funded/reportable.
        PaymentMilestone oldMilestone1 = milestoneFor(oldContractId, "old-m1", MilestoneStatus.FUNDED);
        PaymentMilestone oldMilestone2 = milestoneFor(oldContractId, "old-m2", MilestoneStatus.RELEASED);
        PaymentMilestone newMilestone1 = milestoneFor(newContractId, "new-m1", MilestoneStatus.FUNDED);
        when(milestoneRepository.findByCollaborationIdIn(List.of(COLLAB_ID)))
                .thenReturn(List.of(oldMilestone1, oldMilestone2, newMilestone1));

        DeliverableMetric oldMetric1 = metricFor("old-m1", 1000L, 10000L, 500L);
        DeliverableMetric oldMetric2 = metricFor("old-m2", 2000L, 20000L, 800L);
        DeliverableMetric newMetric1 = metricFor("new-m1", 300L, 3000L, 90L);
        when(deliverableMetricRepository.findByCollaborationIdIn(List.of(COLLAB_ID)))
                .thenReturn(List.of(oldMetric1, oldMetric2, newMetric1));

        AuthPrincipal brandUser = new AuthPrincipal("brandUser", "b@x.com", UserType.BRAND, WORKSPACE_ID);
        CampaignAnalyticsResponse response =
                service.getCampaignAnalytics(brandUser, WORKSPACE_ID, CAMPAIGN_ID);

        // The funded predecessor's 2 milestones (incl. the RELEASED one) count; the unsigned
        // draft's milestone does not. THE REGRESSION: before this fix, this asserted 300/3000/90
        // (new-contract only) — the funded predecessor's RELEASED milestone metrics vanished the
        // instant an amendment was drafted, before either party ever signed it.
        assertEquals(2, response.deliverablesTotal());
        assertEquals(2, response.deliverablesReported());
        assertEquals(2, response.deliverables().size());
        assertEquals(3000L, response.totalReach());
        assertEquals(30000L, response.totalImpressions());
        assertEquals(1300L, response.totalEngagements());
    }

    /**
     * [F-0654 completion, F-0655 correction] The other half of the same lifecycle: once the
     * amendment is genuinely signed, {@link ContractService#retirePredecessorIfSuperseded} retires
     * the predecessor out of {@code ACTIVE} (to {@code COMPLETED}) in the same transaction the
     * amendment itself becomes {@code ACTIVE} — modeled here as read-state (this is a {@code
     * DeliverableMetricService}-focused test; the retirement transition itself is proven live via
     * the real signature flow in {@code ContractAmendmentSupersessionTest}).
     *
     * <p>A previous pass here asserted the F-0655 regression itself: with the retired predecessor
     * carrying a {@code RELEASED} milestone (money already paid out, deliverable already done) and
     * the amendment carrying one {@code FUNDED} milestone, it locked in {@code totalReach == 300}
     * — i.e. it PROVED that the predecessor's already-paid-out {@code RELEASED} milestone vanished
     * from the aggregate the instant the amendment was signed, even though nothing about signing an
     * amendment un-pays a creator for a deliverable that already released. The right total is
     * {@code 300 + 2000 = 2300}: the amendment's own {@code FUNDED} milestone (current contract)
     * PLUS the retired predecessor's {@code RELEASED} one (paid out, permanent, contract-version-
     * independent) — with a THIRD milestone added below to prove this isn't "count everything on
     * the retired predecessor too": a {@code FUNDED}-but-never-released milestone left on that same
     * retired predecessor must NOT count, because that one really was superseded/renegotiated away
     * by the amendment and never actually paid.
     */
    @Test
    @DisplayName(
            "getCampaignAnalytics: F-0655 — once the amendment is signed and the predecessor is"
                    + " retired to COMPLETED, its RELEASED (already paid out) milestone still counts;"
                    + " its unreleased FUNDED milestone does not; the amendment's own milestone does")
    void testAnalyticsKeepsReleasedPredecessorMilestoneAfterSignatureButNotUnreleasedOne() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        Collaboration collaboration = collaborationOwnedBy(CREATOR_ID);
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(collaboration));

        String oldContractId = "01HCONTRACTOLD12345678";
        String newContractId = "01HCONTRACTNEW12345678";
        // The predecessor has been retired by ContractService#retirePredecessorIfSuperseded —
        // COMPLETED, not the ACTIVE it was while the amendment was still a draft.
        Contract oldContractRetired =
                Contract.builder().id(oldContractId).collaborationId(COLLAB_ID).workspaceId(WORKSPACE_ID)
                        .version(1).status(ContractStatus.COMPLETED).totalAmount(java.math.BigDecimal.TEN).build();
        Contract amendmentNowActive =
                Contract.builder().id(newContractId).collaborationId(COLLAB_ID).workspaceId(WORKSPACE_ID)
                        .version(2).status(ContractStatus.ACTIVE).totalAmount(java.math.BigDecimal.TEN).build();
        List<Contract> contractVersions = List.of(amendmentNowActive, oldContractRetired);
        // [F-0657] Batched entry point — see the matching note on the draft-window test above.
        when(contractRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(contractVersions);

        // [F-0653 cross-check] Same agreement assertion as the draft-window test above, this
        // time for the post-signature state: the newest row is no longer unsigned, so both
        // resolutions must now point at the amendment.
        Contract dealServiceCurrent = ContractService.resolveCurrentContract(contractVersions);
        assertEquals(newContractId, dealServiceCurrent.getId());
        assertEquals(
                ContractStatus.ACTIVE,
                dealServiceCurrent.getStatus(),
                "exactly one ACTIVE contract must be current — the retired predecessor is COMPLETED");

        PaymentMilestone oldMilestoneReleased =
                milestoneFor(oldContractId, "old-m1-released", MilestoneStatus.RELEASED);
        // Left FUNDED, never released, on the now-retired predecessor — superseded by the
        // amendment's renegotiated terms and must NOT count, unlike the RELEASED one above.
        PaymentMilestone oldMilestoneUnreleased =
                milestoneFor(oldContractId, "old-m2-unreleased", MilestoneStatus.FUNDED);
        PaymentMilestone newMilestone = milestoneFor(newContractId, "new-m1", MilestoneStatus.FUNDED);
        when(milestoneRepository.findByCollaborationIdIn(List.of(COLLAB_ID)))
                .thenReturn(List.of(oldMilestoneReleased, oldMilestoneUnreleased, newMilestone));

        DeliverableMetric oldMetricReleased = metricFor("old-m1-released", 2000L, 20000L, 800L);
        // Distinct, larger numbers than every other metric in this test — if this wrongly counted,
        // the assertions below would fail loudly rather than by coincidence matching the total.
        DeliverableMetric oldMetricUnreleased = metricFor("old-m2-unreleased", 9_000_000L, 9_000_000L, 9_000_000L);
        DeliverableMetric newMetric = metricFor("new-m1", 300L, 3000L, 90L);
        when(deliverableMetricRepository.findByCollaborationIdIn(List.of(COLLAB_ID)))
                .thenReturn(List.of(oldMetricReleased, oldMetricUnreleased, newMetric));

        AuthPrincipal brandUser = new AuthPrincipal("brandUser", "b@x.com", UserType.BRAND, WORKSPACE_ID);
        CampaignAnalyticsResponse response =
                service.getCampaignAnalytics(brandUser, WORKSPACE_ID, CAMPAIGN_ID);

        // RELEASED predecessor milestone (2000/20000/800) + current amendment milestone
        // (300/3000/90) = 2300/23000/890. The unreleased predecessor milestone (9,000,000 each)
        // must be entirely absent.
        assertEquals(2, response.deliverablesTotal());
        assertEquals(2, response.deliverablesReported());
        assertEquals(2, response.deliverables().size());
        assertEquals(2300L, response.totalReach());
        assertEquals(23000L, response.totalImpressions());
        assertEquals(890L, response.totalEngagements());
    }

    /**
     * [F-0657, n-plus-one-query] Resolving "current contract" for a campaign's collaborations must
     * not issue one {@code ContractRepository} query per collaboration — that was the actual bug:
     * {@code findByCollaborationIdOrderByVersionDescCreatedAtDesc} was called inside a stream over
     * every collaboration id. Proven here with THREE collaborations: the per-collaboration method
     * must be called zero times, and the batched {@code findByWorkspaceId} entry point exactly
     * once, regardless of how many collaborations the campaign has.
     */
    @Test
    @DisplayName(
            "getCampaignAnalytics: F-0657 — contract resolution is batched into one query, not one"
                    + " per collaboration")
    void testAnalyticsBatchesContractLookupInsteadOfOnePerCollaboration() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));

        String collabId1 = "01HCOLLABAAAAAAAAAAAA1";
        String collabId2 = "01HCOLLABAAAAAAAAAAAA2";
        String collabId3 = "01HCOLLABAAAAAAAAAAAA3";
        Collaboration collab1 = Collaboration.invite(collabId1, CAMPAIGN_ID, CREATOR_ID, null, "INR");
        Collaboration collab2 = Collaboration.invite(collabId2, CAMPAIGN_ID, CREATOR_ID, null, "INR");
        Collaboration collab3 = Collaboration.invite(collabId3, CAMPAIGN_ID, CREATOR_ID, null, "INR");
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(collab1, collab2, collab3));

        Contract contract1 =
                Contract.builder().id("c1").collaborationId(collabId1).workspaceId(WORKSPACE_ID)
                        .version(1).status(ContractStatus.ACTIVE).totalAmount(java.math.BigDecimal.TEN).build();
        Contract contract2 =
                Contract.builder().id("c2").collaborationId(collabId2).workspaceId(WORKSPACE_ID)
                        .version(1).status(ContractStatus.ACTIVE).totalAmount(java.math.BigDecimal.TEN).build();
        Contract contract3 =
                Contract.builder().id("c3").collaborationId(collabId3).workspaceId(WORKSPACE_ID)
                        .version(1).status(ContractStatus.ACTIVE).totalAmount(java.math.BigDecimal.TEN).build();
        // ALL of the workspace's contracts come back from a SINGLE call.
        when(contractRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(List.of(contract1, contract2, contract3));

        when(milestoneRepository.findByCollaborationIdIn(List.of(collabId1, collabId2, collabId3)))
                .thenReturn(List.of());
        when(deliverableMetricRepository.findByCollaborationIdIn(List.of(collabId1, collabId2, collabId3)))
                .thenReturn(List.of());

        AuthPrincipal brandUser = new AuthPrincipal("brandUser", "b@x.com", UserType.BRAND, WORKSPACE_ID);
        service.getCampaignAnalytics(brandUser, WORKSPACE_ID, CAMPAIGN_ID);

        org.mockito.Mockito.verify(contractRepository, org.mockito.Mockito.times(1))
                .findByWorkspaceId(WORKSPACE_ID);
        org.mockito.Mockito.verify(contractRepository, org.mockito.Mockito.never())
                .findByCollaborationIdOrderByVersionDescCreatedAtDesc(org.mockito.ArgumentMatchers.any());
    }

    private static PaymentMilestone milestoneFor(String contractId, String id, MilestoneStatus status) {
        return PaymentMilestone.builder()
                .id(id)
                .contractId(contractId)
                .collaborationId(COLLAB_ID)
                .sequenceNo(1)
                .amount(java.math.BigDecimal.TEN)
                .status(status)
                .build();
    }

    private static DeliverableMetric metricFor(
            String milestoneId, long reach, long impressions, long engagements) {
        DeliverableMetric metric =
                DeliverableMetric.builder()
                        .id("metric-" + milestoneId)
                        .milestoneId(milestoneId)
                        .collaborationId(COLLAB_ID)
                        .reportedByCreatorId(CREATOR_ID)
                        .build();
        metric.applyReport(reach, impressions, engagements, null, null, CREATOR_ID);
        return metric;
    }

    private static PaymentMilestone milestoneWithStatus(MilestoneStatus status) {
        return PaymentMilestone.builder()
                .id(MILESTONE_ID)
                .contractId("01HCONTRACT123456789A")
                .collaborationId(COLLAB_ID)
                .sequenceNo(1)
                .amount(java.math.BigDecimal.TEN)
                .status(status)
                .build();
    }

    private static Collaboration collaborationOwnedBy(String creatorId) {
        return Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, creatorId, null, "INR");
    }

    private static Campaign campaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Test Campaign")
                .createdBy("brandUser")
                .build();
    }
}
