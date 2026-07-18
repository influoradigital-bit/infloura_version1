package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import com.influora.web.dto.deal.DealDtos.DealResponse;
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
        when(contractRepository.findByCollaborationIdOrderByVersionDesc(DEAL_ID)).thenReturn(List.of());
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
        when(contractRepository.findByCollaborationIdOrderByVersionDesc(DEAL_ID)).thenReturn(List.of());
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

        CounterRequest body = new CounterRequest(new BigDecimal("25000"), "Counter offer", null);
        DealResponse response = service.counter(brandPrincipal, DEAL_ID, body, null);

        assertEquals(CollaborationStatus.IN_NEGOTIATION, response.status());
        assertEquals(new BigDecimal("25000"), response.dealValue());
    }

    // ------------------------------------------------------------------
    // B-4 Brand-initiated deal accept/reject — accept()/reject() were hard-gated
    // creatorContext.requireCreator(principal) (brand callers 403'd) even though the
    // already-shipped counter() is dual-role. Mirrors counter()'s auth + workspace/
    // ownership scoping pattern (requireOwnedCollaboration, brand scopeId = workspace id).
    // ------------------------------------------------------------------

    private static DealMessage proposalMessage(String senderId, DealSenderType senderType) {
        return DealMessage.create(
                "01HMSGLASTOFFER00000" + (senderType == DealSenderType.brand ? "1" : "2"),
                DEAL_ID,
                DealMessageKind.proposal,
                senderId,
                senderType,
                "Offer on the table",
                null);
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
        when(contractRepository.findByCollaborationIdOrderByVersionDesc(DEAL_ID)).thenReturn(List.of());
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
        verify(dealMessageRepository).save(any(DealMessage.class));
    }

    @Test
    @DisplayName("reject: brand can reject/withdraw from own workspace's deal")
    void testBrandReject() {
        stubBrandWorkspace();
        Collaboration collaboration = invitedDeal();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        OkResponse response = service.reject(brandPrincipal, DEAL_ID, new RejectRequest("Not a fit"));

        assertEquals(true, response.ok());
        assertEquals(CollaborationStatus.CANCELLED, collaboration.getStatus());
        verify(dealMessageRepository).save(any(DealMessage.class));
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
                        () -> service.reject(brandPrincipal, DEAL_ID, new RejectRequest("nope")));

        assertEquals("DEAL_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
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
        when(contractRepository.findByCollaborationIdOrderByVersionDesc(DEAL_ID)).thenReturn(List.of());
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
