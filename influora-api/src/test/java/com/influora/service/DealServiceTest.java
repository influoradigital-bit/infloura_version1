package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CollaborationSource;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.deal.DealDtos.CounterRequest;
import com.influora.web.dto.deal.DealDtos.CreateDealRequest;
import com.influora.web.dto.deal.DealDtos.DealMessageResponse;
import com.influora.web.dto.deal.DealDtos.DealResponse;
import com.influora.web.dto.deal.DealDtos.DeliverableSlot;
import com.influora.web.dto.deal.DealDtos.OkResponse;
import com.influora.web.dto.deal.DealDtos.RejectRequest;
import com.influora.web.dto.deal.DealDtos.SendMessageRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableListItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** Task #9 — access isolation, negotiation state transitions, idempotency wiring. */
@ExtendWith(MockitoExtension.class)
class DealServiceTest {

    private static final String DEAL_ID = "01HDEAL00000000000001";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567890";
    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String BRAND_USER_ID = "01HBRANDUSER123456789";

    @Mock private CollaborationRepository collaborationRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private CreatorContextService creatorContext;
    @Mock private BrandContextService brandContext;
    @Mock private IdempotencyService idempotencyService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private DealMessageStreamRegistry messageStreamRegistry;
    @Mock private AuthPrincipal creatorPrincipal;
    @Mock private AuthPrincipal brandPrincipal;

    private DealService service;

    @BeforeEach
    void setUp() {
        service =
                new DealService(
                        collaborationRepository,
                        dealMessageRepository,
                        campaignRepository,
                        creatorProfileRepository,
                        workspaceRepository,
                        contractRepository,
                        escrowHoldRepository,
                        deliverableRepository,
                        creatorContext,
                        brandContext,
                        idempotencyService,
                        eventPublisher,
                        messageStreamRegistry);
    }

    private static Collaboration invitedDeal() {
        Collaboration c =
                Collaboration.invite(
                        DEAL_ID, CAMPAIGN_ID, CREATOR_USER_ID, "Join us!", "INR");
        return c;
    }

    private static Campaign activeCampaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Summer Campaign")
                .status(CampaignStatus.ACTIVE)
                .budgetMin(new BigDecimal("10000"))
                .budgetMax(new BigDecimal("50000"))
                .currency("INR")
                .createdBy(BRAND_USER_ID)
                .build();
    }

    private void stubCreatorPrincipal() {
        when(creatorPrincipal.getUserType()).thenReturn(UserType.CREATOR);
        when(creatorPrincipal.getUserId()).thenReturn(CREATOR_USER_ID);
    }

    private void stubBrandWorkspace() {
        Workspace workspace =
                Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Beauty", "10-50");
        when(brandPrincipal.getUserType()).thenReturn(UserType.BRAND);
        when(brandContext.requireBrandWorkspace(brandPrincipal)).thenReturn(workspace);
    }

    /**
     * CR-22a — {@code reject} is now routed through {@code IdempotencyService.executeOnce}
     * (Kabir finding #6), mirroring {@link #testCounterPublishesSupersededCardBeforeNewCard}'s
     * pattern for {@code counter}: runs the supplied action as the (only, in these unit tests)
     * race winner so the Mockito-default {@code null} doesn't silently swallow {@code doReject}'s
     * real return value.
     */
    private void mockRejectIdempotencyExecuteOnce() {
        when(idempotencyService.executeOnce(anyString(), anyString(), eq("deal.reject"), any()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            java.util.function.Supplier<OkResponse> action = inv.getArgument(3);
                            return action.get();
                        });
    }

    // ---------------------------------------------------------------------
    // createProposal — visibility gate (SEC 2026-07-26)
    //
    // Until the direct-offer UI was wired, POST /deals had zero callers and resolved the target
    // with a bare findById — so it would happily send a priced offer to a creator who had turned
    // discoverability off, or who moderation had suspended. POST /creators/{id}/invite blocked
    // both. These two tests pin the two entry points to the same rule.
    //
    // The happy path is deliberately not asserted here: it runs on into toDealResponse, whose
    // mock surface (messages, contract, escrow, deliverables, counterparty lookup) is wide enough
    // that an unrunnable test is a liability — Maven was unavailable when this was written. Cover
    // it when the suite can actually be executed.
    // ---------------------------------------------------------------------

    private CreateDealRequest proposalRequest() {
        return new CreateDealRequest(
                CAMPAIGN_ID, CREATOR_PROFILE_ID, new BigDecimal("25000"), null, null, null, "Work with us");
    }

    /**
     * Only what the rejection path actually touches.
     *
     * Deliberately does NOT reuse {@link #stubBrandWorkspace()}: that helper also stubs {@code
     * brandPrincipal.getUserType()}, which {@code createProposal} never reads — it calls {@code
     * brandContext.requireBrandWorkspace(principal)} directly rather than going through {@code
     * requireRole}. Under Mockito's default strict stubs that unused stub fails the test with
     * UnnecessaryStubbingException, which is exactly how these two cases broke Backend CI.
     *
     * Likewise nothing here stubs the principal's userId: both cases throw inside {@code
     * requireOfferableProfile}, before {@code persistProposalMessage} ever asks for it.
     */
    private void stubProposalCampaign() {
        Workspace workspace =
                Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Beauty", "10-50");
        when(brandContext.requireBrandWorkspace(brandPrincipal)).thenReturn(workspace);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(activeCampaign()));
    }

    @Test
    @DisplayName("createProposal: creator who turned discoverability off is not offerable — 404")
    void testCreateProposalRejectsNonDiscoverableCreator() {
        stubProposalCampaign();
        // Both lookup legs miss: the discoverable-filtered query finds nothing, and the userId
        // fallback is filtered out by isDiscoverable().
        when(creatorProfileRepository.findByIdAndDiscoverableTrue(CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());
        when(creatorProfileRepository.findByUserId(CREATOR_PROFILE_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.createProposal(brandPrincipal, proposalRequest()));

        assertEquals("CREATOR_NOT_FOUND", ex.getCode());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("createProposal: suspended creator receives no offer even while discoverable — 404")
    void testCreateProposalRejectsSuspendedCreator() {
        stubProposalCampaign();
        CreatorProfile suspended =
                CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Creator");
        suspended.suspend("Policy violation", "admin_1");
        // Still discoverable, so the first query returns it and Optional.or() short-circuits (no
        // findByUserId fallback stub here — it is never reached). The suspension filter is what
        // must reject, which is exactly the leg a discoverability-only check would have missed.
        when(creatorProfileRepository.findByIdAndDiscoverableTrue(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(suspended));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.createProposal(brandPrincipal, proposalRequest()));

        assertEquals("CREATOR_NOT_FOUND", ex.getCode());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("accept: creator can only accept own deal — foreign deal returns 404")
    void testAcceptRejectsForeignDeal() {
        stubCreatorPrincipal();
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.accept(creatorPrincipal, DEAL_ID, "key-1"));

        assertEquals("DEAL_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(idempotencyService, never()).executeOnce(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("accept: happy path transitions to TERMS_AGREED via idempotency wrapper")
    void testAcceptHappyPath() {
        stubCreatorPrincipal();
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(collaboration));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(activeCampaign()));
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(DEAL_ID)).thenReturn(List.of());
        when(escrowHoldRepository.existsByCollaborationIdAndStatus(anyString(), any())).thenReturn(false);
        when(dealMessageRepository.findFirstByCollaborationIdOrderByCreatedAtDesc(DEAL_ID))
                .thenReturn(Optional.empty());
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID))
                .thenReturn(List.of());
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(idempotencyService.executeOnce(
                        eq("deal-accept:" + DEAL_ID),
                        eq(CREATOR_USER_ID),
                        eq("deal.accept"),
                        any()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            java.util.function.Supplier<DealResponse> action = inv.getArgument(3);
                            return action.get();
                        });

        DealResponse response = service.accept(creatorPrincipal, DEAL_ID, null);

        assertEquals(CollaborationStatus.TERMS_AGREED, response.status());
        verify(collaborationRepository).save(any(Collaboration.class));
        verify(dealMessageRepository).save(any(DealMessage.class));
    }

    @Test
    @DisplayName("get: brand cannot read deal outside workspace — returns 404")
    void testBrandCannotReadForeignDeal() {
        stubBrandWorkspace();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(ApiException.class, () -> service.get(brandPrincipal, DEAL_ID));

        assertEquals("DEAL_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    @DisplayName("counter: brand path uses workspace-scoped collaboration lookup")
    void testBrandCounterUsesWorkspaceScope() {
        stubBrandWorkspace();
        when(brandPrincipal.getUserId()).thenReturn(BRAND_USER_ID);
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(activeCampaign()));
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(DEAL_ID)).thenReturn(List.of());
        when(escrowHoldRepository.existsByCollaborationIdAndStatus(anyString(), any())).thenReturn(false);
        when(dealMessageRepository.findFirstByCollaborationIdOrderByCreatedAtDesc(DEAL_ID))
                .thenReturn(Optional.empty());
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID))
                .thenReturn(List.of());
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID))
                .thenReturn(Optional.of(CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Creator")));
        when(idempotencyService.executeOnce(anyString(), eq(WORKSPACE_ID), eq("deal.counter"), any()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            java.util.function.Supplier<DealResponse> action = inv.getArgument(3);
                            return action.get();
                        });

        CounterRequest body = new CounterRequest(new BigDecimal("25000"), "Counter offer", null, null, null);
        DealResponse response = service.counter(brandPrincipal, DEAL_ID, body, null);

        assertEquals(CollaborationStatus.IN_NEGOTIATION, response.status());
        assertEquals(new BigDecimal("25000"), response.dealValue());
    }

    @Test
    @DisplayName("counter: usageRights persists onto the deal and deadline lands in the proposal")
    void testCounterPersistsAlignedTerms() {
        stubBrandWorkspace();
        when(brandPrincipal.getUserId()).thenReturn(BRAND_USER_ID);
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(activeCampaign()));
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(DEAL_ID))
                .thenReturn(List.of());
        when(escrowHoldRepository.existsByCollaborationIdAndStatus(anyString(), any())).thenReturn(false);
        when(dealMessageRepository.findFirstByCollaborationIdOrderByCreatedAtDesc(DEAL_ID))
                .thenReturn(Optional.empty());
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID))
                .thenReturn(List.of());
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID))
                .thenReturn(
                        Optional.of(CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Creator")));
        when(idempotencyService.executeOnce(anyString(), eq(WORKSPACE_ID), eq("deal.counter"), any()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            java.util.function.Supplier<DealResponse> action = inv.getArgument(3);
                            return action.get();
                        });

        CounterRequest body =
                new CounterRequest(
                        new BigDecimal("25000"),
                        "Revised terms",
                        List.of(new DeliverableSlot("REEL", 2)),
                        "2026-08-15",
                        "6 months");
        service.counter(brandPrincipal, DEAL_ID, body, null);

        // usageRights is now a real column update, exactly as createProposal does it — before the
        // DTOs were aligned a counter could not express it at all.
        assertEquals("6 months", collaboration.getUsageRights());

        ArgumentCaptor<DealMessage> saved = ArgumentCaptor.forClass(DealMessage.class);
        verify(dealMessageRepository).save(saved.capture());
        String metadata = saved.getValue().getMetadataJson();
        // deadline used to be hardcoded null on the counter path, so it vanished on every
        // counter; and deliverables were persisted as a bare COUNT, losing type and quantity.
        assertTrue(metadata.contains("2026-08-15"), "deadline missing from proposal metadata");
        assertTrue(metadata.contains("REEL"), "deliverable type missing from proposal metadata");
    }

    // ------------------------------------------------------------------
    // B-4 Brand-initiated deal accept/reject — accept()/reject() were hard-gated
    // creatorContext.requireCreator(principal) (brand callers 403'd) even though the
    // already-shipped counter() is dual-role. Mirrors counter()'s auth + workspace/
    // ownership scoping pattern (requireOwnedCollaboration, brand scopeId = workspace id).
    // ------------------------------------------------------------------

    /**
     * The last offer on the table, as a real row in {@code deal_messages}.
     *
     * <p>CR-28 — this helper used to pass {@code null} metadata. {@code settleStatus} no-ops on
     * null metadata, so every older accept/counter test built on it passed WITHOUT ever
     * exercising the settle-and-republish path that CR-02 added: the assertions were real but
     * the settle was silently skipped. Only the newer {@link #pendingProposalMessage} covered it.
     * The risk was missing assertions rather than wrong ones — no false positive was ever traced
     * to it — but a helper that quietly disables the code under test is a trap for the next test
     * that reaches for it.
     *
     * <p>Fixed in the helper rather than at the three call sites, so a future test cannot
     * reintroduce the gap by picking the wrong one. Ids stay distinct from
     * {@link #PROPOSAL_MSG_ID} so the CR-08 publish-order tests, which assert on that exact id,
     * are unaffected.
     */
    private static DealMessage proposalMessage(String senderId, DealSenderType senderType) {
        return DealMessage.create(
                "01HMSGLASTOFFER00000" + (senderType == DealSenderType.brand ? "1" : "2"),
                DEAL_ID,
                DealMessageKind.proposal,
                senderId,
                senderType,
                "Offer on the table",
                "{\"amount\":25000.00,\"status\":\"pending\"}");
    }

    @Test
    @DisplayName("accept: brand can accept the creator's last offer — dual-role, workspace-scoped")
    void testBrandAcceptHappyPath() {
        stubBrandWorkspace();
        when(brandPrincipal.getUserId()).thenReturn(BRAND_USER_ID);
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        // Last offer on the table was the creator's counter — brand (the counterparty) may
        // accept it.
        when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        DEAL_ID, DealMessageKind.proposal))
                .thenReturn(Optional.of(proposalMessage(CREATOR_USER_ID, DealSenderType.creator)));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(activeCampaign()));
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(DEAL_ID)).thenReturn(List.of());
        when(escrowHoldRepository.existsByCollaborationIdAndStatus(anyString(), any())).thenReturn(false);
        when(dealMessageRepository.findFirstByCollaborationIdOrderByCreatedAtDesc(DEAL_ID))
                .thenReturn(Optional.empty());
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID))
                .thenReturn(List.of());
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID))
                .thenReturn(Optional.of(CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Creator")));
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(idempotencyService.executeOnce(
                        eq("deal-accept:" + DEAL_ID), eq(WORKSPACE_ID), eq("deal.accept"), any()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            java.util.function.Supplier<DealResponse> action = inv.getArgument(3);
                            return action.get();
                        });

        DealResponse response = service.accept(brandPrincipal, DEAL_ID, null);

        assertEquals(CollaborationStatus.TERMS_AGREED, response.status());
        verify(collaborationRepository).save(any(Collaboration.class));
        // [CR-08 baseline fix] This asserted a single save and had been RED since Wave 1 landed
        // CR-02: doAccept now writes twice — the system message, then the settled proposal card
        // (settleLatestProposal). Two saves is the correct post-CR-02 behaviour, so the assertion
        // was stale, not the production code. Distinct from testAcceptHappyPath, which stubs no
        // proposal card at all and so genuinely saves once.
        verify(dealMessageRepository, times(2)).save(any(DealMessage.class));

        // CR-28 — explicit proof the settle path actually RAN, which is the coverage this test
        // silently lacked. `proposalMessage` used to carry null metadata, so settleStatus no-oped:
        // the two saves above were the system message and an untouched card, and this assertion
        // would have failed. Now the originating offer is genuinely marked accepted.
        ArgumentCaptor<DealMessage> saved = ArgumentCaptor.forClass(DealMessage.class);
        verify(dealMessageRepository, times(2)).save(saved.capture());
        DealMessage settledCard =
                saved.getAllValues().stream()
                        .filter(m -> m.getKind() == DealMessageKind.proposal)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "no proposal card was saved — the settle path did"
                                                        + " not run"));
        assertTrue(
                settledCard.getMetadataJson().contains("\"status\":\"accepted\""),
                "settled card metadata: " + settledCard.getMetadataJson());
    }

    @Test
    @DisplayName("reject: brand can reject/withdraw from own workspace's deal")
    void testBrandReject() {
        stubBrandWorkspace();
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(collaborationRepository.findByIdForUpdate(DEAL_ID))
                .thenReturn(Optional.of(collaboration));
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        mockRejectIdempotencyExecuteOnce();

        OkResponse response =
                service.reject(brandPrincipal, DEAL_ID, new RejectRequest("Not a fit"), null);

        assertEquals(true, response.ok());
        assertEquals(CollaborationStatus.CANCELLED, collaboration.getStatus());
        verify(dealMessageRepository).save(any(DealMessage.class));
    }

    /**
     * CR-22a — {@code canReject()} narrowed from a denylist (everything except COMPLETED/
     * CANCELLED/DISPUTED) to an allowlist ending at TERMS_AGREED. This is the ticket's central
     * finding: pre-fix, this exact call would have transitioned a CONTRACT_PENDING deal (a
     * durable Contract row already exists) straight to CANCELLED. Revert {@code
     * Collaboration#canReject()} to the old denylist and this test is the one that must fail.
     */
    @Test
    @DisplayName(
            "reject: CR-22a — CONTRACT_PENDING (post-contract) returns 409 DEAL_NOT_REJECTABLE,"
                    + " does not transition or save")
    void testRejectRejectsPostContractStatus() {
        stubBrandWorkspace();
        Collaboration collaboration = invitedDeal();
        collaboration.transitionTo(CollaborationStatus.CONTRACT_PENDING);
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(collaborationRepository.findByIdForUpdate(DEAL_ID))
                .thenReturn(Optional.of(collaboration));
        mockRejectIdempotencyExecuteOnce();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.reject(
                                        brandPrincipal, DEAL_ID, new RejectRequest("too late"), null));

        assertEquals("DEAL_NOT_REJECTABLE", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        assertEquals(
                CollaborationStatus.CONTRACT_PENDING,
                collaboration.getStatus(),
                "must not have been mutated");
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    /**
     * The other half of the allowlist boundary: TERMS_AGREED is the HIGHEST status still
     * rejectable (it is {@code ContractService#generate}'s legal predecessor, not its output —
     * no Contract row exists yet). Proves the cut line is exactly at CONTRACT_PENDING, not one
     * status earlier by accident.
     */
    @Test
    @DisplayName("reject: CR-22a — TERMS_AGREED (highest pre-contract status) is still rejectable")
    void testRejectAllowsTermsAgreed() {
        stubBrandWorkspace();
        Collaboration collaboration = invitedDeal();
        collaboration.transitionTo(CollaborationStatus.TERMS_AGREED);
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(collaborationRepository.findByIdForUpdate(DEAL_ID))
                .thenReturn(Optional.of(collaboration));
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        mockRejectIdempotencyExecuteOnce();

        OkResponse response =
                service.reject(brandPrincipal, DEAL_ID, new RejectRequest("changed our mind"), null);

        assertEquals(true, response.ok());
        assertEquals(CollaborationStatus.CANCELLED, collaboration.getStatus());
    }

    /**
     * Kabir finding #6 (the lost-update race) — the fix is a {@code PESSIMISTIC_WRITE} row lock
     * taken via {@code findByIdForUpdate} INSIDE the idempotency-guarded action, mirroring {@code
     * ContractService#generate}. A unit test cannot force the actual concurrent interleaving (see
     * {@code ContractServiceTest#testConcurrentGenerateCallsAreSerializedByCollaborationLock} for
     * why that needs real threads + a JDBC-level lock), but it CAN prove the lock is acquired at
     * all — which a plain, non-locking {@code findByIdAndWorkspaceId}-only implementation would
     * fail. Revert {@code doReject} back to reading through the unlocked instance and this is the
     * test that catches it.
     */
    @Test
    @DisplayName("reject: acquires a PESSIMISTIC_WRITE row lock on the collaboration before transitioning it")
    void testRejectAcquiresRowLock() {
        stubBrandWorkspace();
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(collaborationRepository.findByIdForUpdate(DEAL_ID))
                .thenReturn(Optional.of(collaboration));
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        mockRejectIdempotencyExecuteOnce();

        service.reject(brandPrincipal, DEAL_ID, new RejectRequest("Not a fit"), null);

        verify(collaborationRepository).findByIdForUpdate(DEAL_ID);
    }

    /**
     * Kabir finding #8 — a retried reject after the first call already succeeded must replay the
     * prior 200, not 409 DEAL_NOT_REJECTABLE (the old behavior: the deal is now CANCELLED, so a
     * plain re-check of {@code canReject()} on retry would always 409). Simulates the retry by
     * having the idempotency wrapper report the key as already COMPLETED — {@code doReject} must
     * never even be invoked in that case.
     */
    @Test
    @DisplayName(
            "reject: a retried call after success replays 200 (not 409) — finding #8, resolved as a"
                    + " side effect of routing through IdempotencyService")
    void testRejectRetryAfterSuccessReplays200() {
        stubBrandWorkspace();
        Collaboration collaboration = invitedDeal();
        collaboration.transitionTo(CollaborationStatus.CANCELLED); // as left by the first, successful call
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(idempotencyService.executeOnce(anyString(), anyString(), eq("deal.reject"), any()))
                .thenThrow(new IdempotencyService.AlreadyCompletedException("deal-reject:" + DEAL_ID));

        OkResponse response =
                service.reject(brandPrincipal, DEAL_ID, new RejectRequest("Not a fit"), null);

        assertEquals(true, response.ok());
        verify(collaborationRepository, never()).findByIdForUpdate(any());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("accept: brand cannot accept a deal outside their workspace — 404")
    void testBrandAcceptRejectsForeignWorkspace() {
        stubBrandWorkspace();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.accept(brandPrincipal, DEAL_ID, "key-2"));

        assertEquals("DEAL_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(idempotencyService, never()).executeOnce(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("reject: brand cannot reject a deal outside their workspace — 404")
    void testBrandRejectRejectsForeignWorkspace() {
        stubBrandWorkspace();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reject(brandPrincipal, DEAL_ID, new RejectRequest("nope"), null));

        assertEquals("DEAL_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
        verify(idempotencyService, never()).executeOnce(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName(
            "accept: cannot accept your own last offer — party who last countered must wait for the counterparty")
    void testCannotAcceptOwnLastOffer() {
        stubBrandWorkspace();
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        // Brand itself made the last offer — brand accepting it would be a self-accept.
        when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        DEAL_ID, DealMessageKind.proposal))
                .thenReturn(Optional.of(proposalMessage(BRAND_USER_ID, DealSenderType.brand)));
        when(idempotencyService.executeOnce(
                        eq("deal-accept:" + DEAL_ID), eq(WORKSPACE_ID), eq("deal.accept"), any()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            java.util.function.Supplier<DealResponse> action = inv.getArgument(3);
                            return action.get();
                        });

        ApiException ex =
                assertThrows(ApiException.class, () -> service.accept(brandPrincipal, DEAL_ID, null));

        assertEquals("CANNOT_ACCEPT_OWN_OFFER", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("accept: idempotency key/scope for brand matches counter()'s workspace-scoping pattern")
    void testBrandAcceptIdempotencyScopedToWorkspace() {
        stubBrandWorkspace();
        when(brandPrincipal.getUserId()).thenReturn(BRAND_USER_ID);
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        DEAL_ID, DealMessageKind.proposal))
                .thenReturn(Optional.of(proposalMessage(CREATOR_USER_ID, DealSenderType.creator)));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(activeCampaign()));
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(DEAL_ID)).thenReturn(List.of());
        when(escrowHoldRepository.existsByCollaborationIdAndStatus(anyString(), any())).thenReturn(false);
        when(dealMessageRepository.findFirstByCollaborationIdOrderByCreatedAtDesc(DEAL_ID))
                .thenReturn(Optional.empty());
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID))
                .thenReturn(List.of());
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID))
                .thenReturn(Optional.of(CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Creator")));
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(idempotencyService.executeOnce(
                        eq("custom-idem-key"), eq(WORKSPACE_ID), eq("deal.accept"), any()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            java.util.function.Supplier<DealResponse> action = inv.getArgument(3);
                            return action.get();
                        });

        service.accept(brandPrincipal, DEAL_ID, "custom-idem-key");

        // Same contract as counter(): a client-supplied Idempotency-Key header is honored
        // verbatim, and the dedupe scope is the brand's workspace id (not the user id).
        verify(idempotencyService)
                .executeOnce(eq("custom-idem-key"), eq(WORKSPACE_ID), eq("deal.accept"), any());
    }

    @Test
    @DisplayName("listMessages: seeds notes from collaboration when timeline is empty")
    void testListMessagesSeedsNotes() {
        stubCreatorPrincipal();
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(collaboration));
        when(dealMessageRepository.findPageBefore(eq(DEAL_ID), eq(null), any()))
                .thenReturn(List.of());

        var messages = service.listMessages(creatorPrincipal, DEAL_ID, null);

        assertEquals(1, messages.size());
        assertEquals("Join us!", messages.get(0).content());
        assertEquals(DealMessageKind.text, messages.get(0).kind());
        assertEquals(DealSenderType.brand, messages.get(0).senderType());
    }

    @Test
    @DisplayName("sendMessage: persists text message for owned deal")
    void testSendMessage() {
        stubCreatorPrincipal();
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(collaboration));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(
                        inv -> {
                            DealMessage m = inv.getArgument(0);
                            return m;
                        });

        var response =
                service.sendMessage(
                        creatorPrincipal,
                        DEAL_ID,
                        new com.influora.web.dto.deal.DealDtos.SendMessageRequest("Hello", DealMessageKind.text));

        assertEquals("Hello", response.content());
        assertEquals(DealSenderType.creator, response.senderType());
    }

    @Test
    @DisplayName("sendMessage: publishes the persisted DTO to the SSE registry after save")
    void testSendMessagePublishesToStreamRegistry() {
        stubCreatorPrincipal();
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(collaboration));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var response =
                service.sendMessage(
                        creatorPrincipal, DEAL_ID, new SendMessageRequest("Hello", DealMessageKind.text));

        var inOrder = org.mockito.Mockito.inOrder(dealMessageRepository, messageStreamRegistry);
        inOrder.verify(dealMessageRepository).save(any(DealMessage.class));
        inOrder.verify(messageStreamRegistry).publish(eq(DEAL_ID), eq(response));
    }

    // ------------------------------------------------------------------
    // CR-08 — accept/decline/counter must reach the counterparty's OPEN deal room.
    //
    // Before this, DealService published to the SSE registry from exactly one place (sendMessage),
    // so the proposal lifecycle actions persisted their rows and pushed nothing: a creator
    // accepted and the brand's open room showed stale, still-actionable cards until a manual
    // reload. These pin BOTH halves of the contract with Ananya's frontend:
    //   1. WHAT is published — a settled card goes out under its ORIGINAL persisted id with
    //      post-settle metadata, so the client's upsert-by-id replaces the row it already holds
    //      instead of appending a duplicate. A new id here would be the bug.
    //   2. In WHAT ORDER — settled/superseded card first, so a client applying the frames in
    //      sequence never renders an inconsistent room.
    // ------------------------------------------------------------------

    private static final String PROPOSAL_MSG_ID = "01HMSGPENDINGCARD0001";

    /**
     * A proposal card as it actually sits in {@code deal_messages} while an offer is live —
     * {@code status: "pending"} with a real amount. {@link #proposalMessage} deliberately carries
     * NULL metadata (it only exists to identify who made the last offer), and settleStatus no-ops
     * on null metadata, so it cannot exercise the settle-and-republish path at all.
     */
    private static DealMessage pendingProposalMessage(String senderId, DealSenderType senderType) {
        return DealMessage.create(
                PROPOSAL_MSG_ID,
                DEAL_ID,
                DealMessageKind.proposal,
                senderId,
                senderType,
                "Offer on the table",
                "{\"amount\":25000.00,\"status\":\"pending\"}");
    }

    @Test
    @DisplayName(
            "accept: publishes the settled card under its ORIGINAL id, then the system message")
    void testAcceptPublishesSettledCardThenSystemMessage() {
        stubBrandWorkspace();
        when(brandPrincipal.getUserId()).thenReturn(BRAND_USER_ID);
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        DEAL_ID, DealMessageKind.proposal))
                .thenReturn(Optional.of(pendingProposalMessage(CREATOR_USER_ID, DealSenderType.creator)));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(activeCampaign()));
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(DEAL_ID))
                .thenReturn(List.of());
        when(escrowHoldRepository.existsByCollaborationIdAndStatus(anyString(), any()))
                .thenReturn(false);
        when(dealMessageRepository.findFirstByCollaborationIdOrderByCreatedAtDesc(DEAL_ID))
                .thenReturn(Optional.empty());
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID))
                .thenReturn(List.of());
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID))
                .thenReturn(
                        Optional.of(CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Creator")));
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(idempotencyService.executeOnce(
                        eq("deal-accept:" + DEAL_ID), eq(WORKSPACE_ID), eq("deal.accept"), any()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            java.util.function.Supplier<DealResponse> action = inv.getArgument(3);
                            return action.get();
                        });

        service.accept(brandPrincipal, DEAL_ID, null);

        ArgumentCaptor<DealMessageResponse> published =
                ArgumentCaptor.forClass(DealMessageResponse.class);
        verify(messageStreamRegistry, times(2)).publish(eq(DEAL_ID), published.capture());
        List<DealMessageResponse> frames = published.getAllValues();

        // Frame 1 — the settled card. Its id MUST be the id already on the client, otherwise the
        // upsert appends a second copy of the same offer instead of replacing the live one.
        assertEquals(PROPOSAL_MSG_ID, frames.get(0).id());
        assertEquals(DealMessageKind.proposal, frames.get(0).kind());
        assertEquals("accepted", frames.get(0).metadata().get("status"));

        // Frame 2 — the system message, and it lands AFTER the card is already inert.
        assertEquals(DealMessageKind.system, frames.get(1).kind());
        assertEquals(DealSenderType.system, frames.get(1).senderType());
        assertTrue(
                frames.get(1).content().contains("accepted the proposal"),
                "system message content: " + frames.get(1).content());
    }

    @Test
    @DisplayName("reject: publishes the settled card (status rejected), then the system message")
    void testRejectPublishesSettledCardThenSystemMessage() {
        stubBrandWorkspace();
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(collaborationRepository.findByIdForUpdate(DEAL_ID))
                .thenReturn(Optional.of(collaboration));
        when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        DEAL_ID, DealMessageKind.proposal))
                .thenReturn(Optional.of(pendingProposalMessage(BRAND_USER_ID, DealSenderType.brand)));
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        mockRejectIdempotencyExecuteOnce();

        service.reject(brandPrincipal, DEAL_ID, new RejectRequest("Not a fit"), null);

        ArgumentCaptor<DealMessageResponse> published =
                ArgumentCaptor.forClass(DealMessageResponse.class);
        verify(messageStreamRegistry, times(2)).publish(eq(DEAL_ID), published.capture());
        List<DealMessageResponse> frames = published.getAllValues();

        assertEquals(PROPOSAL_MSG_ID, frames.get(0).id());
        assertEquals("rejected", frames.get(0).metadata().get("status"));
        assertEquals(DealMessageKind.system, frames.get(1).kind());
        assertTrue(
                frames.get(1).content().contains("Not a fit"),
                "system message content: " + frames.get(1).content());
    }

    @Test
    @DisplayName("counter: publishes the superseded card INERT before the new proposal card")
    void testCounterPublishesSupersededCardBeforeNewCard() {
        stubBrandWorkspace();
        when(brandPrincipal.getUserId()).thenReturn(BRAND_USER_ID);
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        DEAL_ID, DealMessageKind.proposal))
                .thenReturn(Optional.of(pendingProposalMessage(CREATOR_USER_ID, DealSenderType.creator)));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(activeCampaign()));
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(DEAL_ID))
                .thenReturn(List.of());
        when(escrowHoldRepository.existsByCollaborationIdAndStatus(anyString(), any()))
                .thenReturn(false);
        when(dealMessageRepository.findFirstByCollaborationIdOrderByCreatedAtDesc(DEAL_ID))
                .thenReturn(Optional.empty());
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID))
                .thenReturn(List.of());
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID))
                .thenReturn(
                        Optional.of(CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Creator")));
        when(idempotencyService.executeOnce(anyString(), eq(WORKSPACE_ID), eq("deal.counter"), any()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            java.util.function.Supplier<DealResponse> action = inv.getArgument(3);
                            return action.get();
                        });

        service.counter(
                brandPrincipal,
                DEAL_ID,
                new CounterRequest(new BigDecimal("25000"), "Counter offer", null, null, null),
                null);

        ArgumentCaptor<DealMessageResponse> published =
                ArgumentCaptor.forClass(DealMessageResponse.class);
        verify(messageStreamRegistry, times(2)).publish(eq(DEAL_ID), published.capture());
        List<DealMessageResponse> frames = published.getAllValues();

        // Frame 1 — the superseded card, same id, now inert. This MUST precede frame 2: two cards
        // both reading "pending" on the client, even briefly, means a click on the stale one
        // accepts the new amount (POST /deals/{id}/accept carries no proposal id).
        assertEquals(PROPOSAL_MSG_ID, frames.get(0).id());
        assertEquals("countered", frames.get(0).metadata().get("status"));

        // Frame 2 — the new offer: a genuinely NEW row (distinct id), still pending.
        assertEquals(DealMessageKind.proposal, frames.get(1).kind());
        assertTrue(
                !PROPOSAL_MSG_ID.equals(frames.get(1).id()),
                "the new proposal must not reuse the superseded card's id");
        assertEquals("pending", frames.get(1).metadata().get("status"));
        assertEquals(DealSenderType.brand, frames.get(1).senderType());
    }

    // ------------------------------------------------------------------
    // Realtime deal-message SSE stream — GET /deals/{dealId}/messages/stream.
    // authorizeMessageStream is a thin wrapper around the SAME ownership check
    // listMessages/sendMessage already use (requireOwnedCollaboration) — no new auth logic.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("authorizeMessageStream: creator may open a stream for their own deal (no throw)")
    void testAuthorizeMessageStreamCreatorHappyPath() {
        stubCreatorPrincipal();
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(collaboration));

        service.authorizeMessageStream(creatorPrincipal, DEAL_ID);
        // No exception — that's the pass condition. No emitter/registry interaction here; that's
        // the controller's job, which only happens after this call returns cleanly.
        org.mockito.Mockito.verifyNoInteractions(messageStreamRegistry);
    }

    @Test
    @DisplayName("authorizeMessageStream: brand cannot open a stream for a deal outside their workspace")
    void testAuthorizeMessageStreamRejectsForeignWorkspace() {
        stubBrandWorkspace();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.authorizeMessageStream(brandPrincipal, DEAL_ID));

        assertEquals("DEAL_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        org.mockito.Mockito.verifyNoInteractions(messageStreamRegistry);
    }

    @Test
    @DisplayName("sendMessage: strips script tags from persisted content")
    void testSendMessageStripsXss() {
        stubCreatorPrincipal();
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(collaboration));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var response =
                service.sendMessage(
                        creatorPrincipal,
                        DEAL_ID,
                        new com.influora.web.dto.deal.DealDtos.SendMessageRequest(
                                "<script>alert(1)</script>Hello", DealMessageKind.text));

        assertEquals("Hello", response.content());
    }

    // ------------------------------------------------------------------
    // B-1 Deal Room persistence — brand-role message coverage + cross-workspace
    // rejection (gap found: only the creator path had test coverage before,
    // even though sendMessage/listMessages are already dual-role in prod code).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sendMessage: brand can send on own workspace's deal, persists as brand sender")
    void testSendMessageBrandRole() {
        stubBrandWorkspace();
        when(brandPrincipal.getUserId()).thenReturn(BRAND_USER_ID);
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var response =
                service.sendMessage(
                        brandPrincipal, DEAL_ID, new SendMessageRequest("Here's our offer", DealMessageKind.text));

        assertEquals("Here's our offer", response.content());
        assertEquals(DealSenderType.brand, response.senderType());
        assertEquals(BRAND_USER_ID, response.senderId());
    }

    @Test
    @DisplayName("listMessages: brand cannot list messages on a deal outside their workspace — 404")
    void testListMessagesRejectsForeignWorkspace() {
        stubBrandWorkspace();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.listMessages(brandPrincipal, DEAL_ID, null));

        assertEquals("DEAL_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(dealMessageRepository, never()).findPageBefore(anyString(), any(), any());
    }

    @Test
    @DisplayName("sendMessage: brand cannot send a message on a deal outside their workspace — 404")
    void testSendMessageRejectsForeignWorkspace() {
        stubBrandWorkspace();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.sendMessage(
                                        brandPrincipal,
                                        DEAL_ID,
                                        new SendMessageRequest("Trying to probe", DealMessageKind.text)));

        assertEquals("DEAL_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(dealMessageRepository, never()).save(any(DealMessage.class));
    }

    // ------------------------------------------------------------------
    // B-1 Deal Room persistence — new GET /deals/{id}/deliverables endpoint.
    // Previously a documented gap: frontend `api.deliverables.list('brand', dealId)`
    // called this exact path and always 404'd (no backend route existed).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listDeliverables: brand sees deliverables for own workspace's deal, ordered by slot")
    void testListDeliverablesBrandHappyPath() {
        stubBrandWorkspace();
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        Deliverable d1 =
                Deliverable.builder()
                        .id("01HDEL00000000000001")
                        .collaborationId(DEAL_ID)
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .slotIndex(0)
                        .title("Instagram Reel #1")
                        .status(DeliverableStatus.APPROVED)
                        .build();
        Deliverable d2 =
                Deliverable.builder()
                        .id("01HDEL00000000000002")
                        .collaborationId(DEAL_ID)
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .slotIndex(1)
                        .title("Instagram Story")
                        .status(DeliverableStatus.SUBMITTED)
                        .build();
        when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(DEAL_ID))
                .thenReturn(List.of(d1, d2));

        List<DeliverableListItem> result = service.listDeliverables(brandPrincipal, DEAL_ID);

        assertEquals(2, result.size());
        assertEquals("01HDEL00000000000001", result.get(0).id());
        assertEquals("Instagram Reel #1", result.get(0).title());
        assertEquals(DeliverableStatus.APPROVED, result.get(0).status());
        assertEquals(true, result.get(0).completed());
        assertEquals(DeliverableStatus.SUBMITTED, result.get(1).status());
        assertEquals(false, result.get(1).completed());
    }

    @Test
    @DisplayName("listDeliverables: brand cannot list deliverables for a deal outside their workspace — 404")
    void testListDeliverablesRejectsForeignWorkspace() {
        stubBrandWorkspace();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.listDeliverables(brandPrincipal, DEAL_ID));

        assertEquals("DEAL_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(deliverableRepository, never()).findByCollaborationIdOrderBySlotIndexAsc(anyString());
    }

    @Test
    @DisplayName("listDeliverables: creator can list deliverables for their own deal")
    void testListDeliverablesCreatorHappyPath() {
        stubCreatorPrincipal();
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(collaboration));
        when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(DEAL_ID))
                .thenReturn(List.of());

        List<DeliverableListItem> result = service.listDeliverables(creatorPrincipal, DEAL_ID);

        assertEquals(0, result.size());
    }
}
