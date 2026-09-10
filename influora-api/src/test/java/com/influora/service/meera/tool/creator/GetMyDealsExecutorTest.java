package com.influora.service.meera.tool.creator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorBriefRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.DealSummary;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyDealsResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.6) — {@code get_my_deals}. */
@ExtendWith(MockitoExtension.class)
class GetMyDealsExecutorTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE123A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String DEAL_ID = "01HCOLLAB12345678901A";

    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private CreatorBriefRepository creatorBriefRepository;

    private GetMyDealsExecutor executor;

    @BeforeEach
    void setUp() {
        executor =
                new GetMyDealsExecutor(
                        preferencesService,
                        collaborationRepository,
                        campaignRepository,
                        workspaceRepository,
                        dealMessageRepository,
                        deliverableRepository,
                        escrowHoldRepository,
                        creatorBriefRepository);
        lenient()
                .when(preferencesService.requireCreatorProfile(CREATOR_USER_ID))
                .thenReturn(CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Priya Shah"));
        lenient()
                .when(preferencesService.getOrCreatePreferences(CREATOR_USER_ID))
                .thenReturn(preferences());
    }

    /**
     * <b>The regression that matters most (CR-49/CR-50).</b> A brand-funded escrow hold is bound to
     * a PaymentMilestone, not to the collaboration directly, so
     * {@code findByCollaborationIdAndStatus} — which reads the direct {@code collaboration_id}
     * column — comes back empty on a genuinely funded deal. Here that finder is stubbed to return
     * nothing (exactly what it does in production for a milestone-funded hold) while the
     * milestone-aware existence query says the money is held. {@code secured} must be true.
     *
     * <p>A creator told her money is not secured does not start work. This is the whole point of
     * the field.
     */
    @Test
    @DisplayName(
            "secured is TRUE for a milestone-funded collaboration -- the milestone-aware query is"
                    + " used, and the direct-column finder that reports false on funded deals is"
                    + " never called")
    void testSecuredTrueForMilestoneFundedDeal() {
        Collaboration deal = contractedDeal();
        stubOneDeal(deal);
        when(escrowHoldRepository.hasEscrowForCollaboration(DEAL_ID, Set.of(EscrowStatus.FUNDED)))
                .thenReturn(true);

        GetMyDealsResult result = executor.execute(CREATOR_USER_ID, Map.of());

        DealSummary summary = result.deals().get(0);
        assertTrue(summary.secured(), "a milestone-funded hold must read as secured");
        assertEquals("funds secured, start work", summary.nextAction());

        // The wrong finder, named explicitly: it EXISTS on the repository, so this is a live
        // hazard, not a hypothetical. Verified with concrete arguments rather than anyString(),
        // which would not match a null argument if one were ever passed.
        verify(escrowHoldRepository, never())
                .findByCollaborationIdAndStatus(DEAL_ID, EscrowStatus.FUNDED);
        verify(escrowHoldRepository, never())
                .findByCollaborationIdAndStatus(anyString(), any(EscrowStatus.class));
        verify(escrowHoldRepository).hasEscrowForCollaboration(DEAL_ID, Set.of(EscrowStatus.FUNDED));
    }

    @Test
    @DisplayName(
            "secured is FALSE with no funded hold, and a CONTRACTED deal then tells the creator to"
                    + " wait rather than to start shooting")
    void testUnsecuredContractedDealTellsCreatorToWait() {
        stubOneDeal(contractedDeal());
        when(escrowHoldRepository.hasEscrowForCollaboration(DEAL_ID, Set.of(EscrowStatus.FUNDED)))
                .thenReturn(false);

        DealSummary summary = executor.execute(CREATOR_USER_ID, Map.of()).deals().get(0);

        assertFalse(summary.secured());
        assertEquals("waiting for brand to secure funds", summary.nextAction());
    }

    @Test
    @DisplayName(
            "unread_count comes from the shared DealService helper, so a message the creator has"
                    + " not read counts exactly once")
    void testUnreadCountUsesTheSharedHelper() {
        stubOneDeal(contractedDeal());
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID))
                .thenReturn(
                        List.of(
                                message(DealSenderType.brand, "unread-1"),
                                message(DealSenderType.brand, "unread-2")));

        DealSummary summary = executor.execute(CREATOR_USER_ID, Map.of()).deals().get(0);

        // readByJson is "[]" on a freshly created message, so neither is read by this creator.
        assertEquals(2, summary.unreadCount());
    }

    @Test
    @DisplayName(
            "IN_NEGOTIATION: the last message's sender decides between 'reply to brand' and"
                    + " 'waiting for brand', and only a brand's last word is a pending offer")
    void testNegotiationNextActionAndPendingOffer() {
        Collaboration deal = dealWithStatus(CollaborationStatus.IN_NEGOTIATION);
        stubOneDeal(deal);
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID))
                .thenReturn(List.of(message(DealSenderType.creator, "m1"), message(DealSenderType.brand, "m2")));

        DealSummary brandLast = executor.execute(CREATOR_USER_ID, Map.of()).deals().get(0);
        assertEquals("reply to brand", brandLast.nextAction());
        assertTrue(brandLast.hasPendingOffer());

        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID))
                .thenReturn(List.of(message(DealSenderType.brand, "m1"), message(DealSenderType.creator, "m2")));

        DealSummary creatorLast = executor.execute(CREATOR_USER_ID, Map.of()).deals().get(0);
        assertEquals("waiting for brand", creatorLast.nextAction());
        assertFalse(creatorLast.hasPendingOffer());
    }

    @Test
    @DisplayName(
            "counts are computed over EVERY collaboration, before the status filter and the limit --"
                    + " asking for one deal must not change how many the creator is told she has")
    void testCountsIgnoreFilterAndLimit() {
        Collaboration active = dealWithStatus(CollaborationStatus.IN_PROGRESS);
        Collaboration completed = dealWithStatus(CollaborationStatus.COMPLETED, "01HCOLLAB22222222222");
        Collaboration cancelled = dealWithStatus(CollaborationStatus.CANCELLED, "01HCOLLAB33333333333");
        when(collaborationRepository.findByCreatorId(CREATOR_USER_ID))
                .thenReturn(List.of(active, completed, cancelled));
        stubPerDealLookups();

        GetMyDealsResult result = executor.execute(CREATOR_USER_ID, Map.of("limit", 1));

        assertEquals(1, result.activeCount(), "only IN_PROGRESS is non-terminal here");
        assertEquals(1, result.completedCount());
        assertEquals(1, result.deals().size(), "limit applies to the page, not to the counts");
        assertEquals(active.getId(), result.deals().get(0).dealId());
    }

    @Test
    @DisplayName(
            "status=completed returns only COMPLETED; status=all returns terminal deals too;"
                    + " an unrecognised status degrades to active rather than erroring")
    void testStatusFilter() {
        Collaboration active = dealWithStatus(CollaborationStatus.IN_PROGRESS);
        Collaboration completed = dealWithStatus(CollaborationStatus.COMPLETED, "01HCOLLAB22222222222");
        when(collaborationRepository.findByCreatorId(CREATOR_USER_ID))
                .thenReturn(List.of(active, completed));
        stubPerDealLookups();

        assertEquals(1, executor.execute(CREATOR_USER_ID, Map.of("status", "active")).deals().size());
        assertEquals(
                completed.getId(),
                executor.execute(CREATOR_USER_ID, Map.of("status", "completed")).deals().get(0).dealId());
        assertEquals(2, executor.execute(CREATOR_USER_ID, Map.of("status", "all")).deals().size());
        assertEquals(1, executor.execute(CREATOR_USER_ID, Map.of("status", "banana")).deals().size());
    }

    @Test
    @DisplayName("limit is clamped to 25 -- a model asking for 5000 gets the capped page, not an error")
    void testLimitIsClamped() {
        Collaboration[] many = new Collaboration[30];
        for (int i = 0; i < many.length; i++) {
            many[i] = dealWithStatus(CollaborationStatus.IN_PROGRESS, "01HCOLLAB" + String.format("%012d", i));
        }
        when(collaborationRepository.findByCreatorId(CREATOR_USER_ID)).thenReturn(List.of(many));
        stubPerDealLookups();

        assertEquals(25, executor.execute(CREATOR_USER_ID, Map.of("limit", 5000)).deals().size());
        assertEquals(10, executor.execute(CREATOR_USER_ID, Map.of()).deals().size(), "default is 10");
        assertEquals(1, executor.execute(CREATOR_USER_ID, Map.of("limit", 0)).deals().size(), "floor is 1");
    }

    @Test
    @DisplayName(
            "a missing campaign or workspace yields NULL names, never the 'Brand'/'Campaign'"
                    + " placeholders the deal room shows -- a model would state those as facts")
    void testMissingCounterpartyYieldsNullsNotPlaceholders() {
        stubOneDeal(contractedDeal());
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

        DealSummary summary = executor.execute(CREATOR_USER_ID, Map.of()).deals().get(0);

        assertNull(summary.brandName());
        assertNull(summary.campaignTitle());
    }

    @Test
    @DisplayName("amount is rendered AND carried as a number, and the raw status name travels too")
    void testAmountIsRenderedAndNumeric() {
        Collaboration deal = contractedDeal();
        deal.updateAgreedRate(new BigDecimal("8000"));
        stubOneDeal(deal);

        DealSummary summary = executor.execute(CREATOR_USER_ID, Map.of()).deals().get(0);

        assertEquals("8,000", summary.amount());
        assertEquals(new BigDecimal("8000"), summary.amountValue());
        assertEquals("INR", summary.currency());
        assertEquals("CONTRACTED", summary.status());
        assertEquals("Contracted", summary.statusLabel());
    }

    // ---- fixtures -------------------------------------------------------------------------

    private void stubOneDeal(Collaboration deal) {
        when(collaborationRepository.findByCreatorId(CREATOR_USER_ID)).thenReturn(List.of(deal));
        stubPerDealLookups();
    }

    private void stubPerDealLookups() {
        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "Acme Foods", "acme", "FMCG", "50-200");
        lenient().when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign()));
        lenient().when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        lenient()
                .when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(anyString()))
                .thenReturn(List.of());
        lenient()
                .when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(anyString()))
                .thenReturn(List.of());
        lenient()
                .when(escrowHoldRepository.hasEscrowForCollaboration(anyString(), eq(Set.of(EscrowStatus.FUNDED))))
                .thenReturn(false);
        lenient()
                .when(
                        creatorBriefRepository.findFirstByCollaborationIdAndCreatorProfileId(
                                anyString(), eq(CREATOR_PROFILE_ID)))
                .thenReturn(Optional.empty());
    }

    private static Campaign campaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Diwali reels")
                .build();
    }

    private static Collaboration contractedDeal() {
        return dealWithStatus(CollaborationStatus.CONTRACTED);
    }

    private static Collaboration dealWithStatus(CollaborationStatus status) {
        return dealWithStatus(status, DEAL_ID);
    }

    private static Collaboration dealWithStatus(CollaborationStatus status, String id) {
        Collaboration c = Collaboration.invite(id, CAMPAIGN_ID, CREATOR_USER_ID, "hi", "INR");
        c.transitionTo(status);
        return c;
    }

    private static DealMessage message(DealSenderType senderType, String id) {
        return DealMessage.create(
                id, DEAL_ID, DealMessageKind.text, "sender", senderType, "hello", null);
    }

    private static PreferencesResponse preferences() {
        return new PreferencesResponse(
                null, null, null, null, List.of(), List.of(), 0, "en-IN", null, null, null, null,
                List.of(), null, false, null, true, null, false, null, false, 0, false);
    }
}
