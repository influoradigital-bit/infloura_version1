package com.influora.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.common.TextSanitizer;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import com.influora.domain.enums.EscrowStatus;
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
import com.influora.service.notification.event.BidAcceptedEvent;
import com.influora.service.notification.event.BidCounteredEvent;
import com.influora.service.notification.event.CreatorFirstMessageEvent;
import com.influora.service.notification.event.FirstMessageSentEvent;
import com.influora.service.notification.event.ProposalAcceptedEvent;
import com.influora.service.notification.event.ProposalSentEvent;
import com.influora.web.dto.deal.DealDtos.CounterRequest;
import com.influora.web.dto.deal.DealDtos.CreateDealRequest;
import com.influora.web.dto.deal.DealDtos.DealMessageResponse;
import com.influora.web.dto.deal.DealDtos.DealResponse;
import com.influora.web.dto.deal.DealDtos.OkResponse;
import com.influora.web.dto.deal.DealDtos.RejectRequest;
import com.influora.web.dto.deal.DealDtos.SendMessageRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableListItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task #9 — unified deal room backed by {@link Collaboration} + {@link DealMessage} timeline.
 * Creator identity always from {@link CreatorContextService}; brand scope always from {@link
 * BrandContextService} — never trust path-param user ids.
 */
@Service
public class DealService {

    private static final Logger log = LoggerFactory.getLogger(DealService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MESSAGE_PAGE_SIZE = 50;

    private final CollaborationRepository collaborationRepository;
    private final DealMessageRepository dealMessageRepository;
    private final CampaignRepository campaignRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ContractRepository contractRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final DeliverableRepository deliverableRepository;
    private final CreatorContextService creatorContext;
    private final BrandContextService brandContext;
    private final IdempotencyService idempotencyService;
    private final ApplicationEventPublisher eventPublisher;
    private final DealMessageStreamRegistry messageStreamRegistry;

    public DealService(
            CollaborationRepository collaborationRepository,
            DealMessageRepository dealMessageRepository,
            CampaignRepository campaignRepository,
            CreatorProfileRepository creatorProfileRepository,
            WorkspaceRepository workspaceRepository,
            ContractRepository contractRepository,
            EscrowHoldRepository escrowHoldRepository,
            DeliverableRepository deliverableRepository,
            CreatorContextService creatorContext,
            BrandContextService brandContext,
            IdempotencyService idempotencyService,
            ApplicationEventPublisher eventPublisher,
            DealMessageStreamRegistry messageStreamRegistry) {
        this.collaborationRepository = collaborationRepository;
        this.dealMessageRepository = dealMessageRepository;
        this.campaignRepository = campaignRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.workspaceRepository = workspaceRepository;
        this.contractRepository = contractRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.deliverableRepository = deliverableRepository;
        this.creatorContext = creatorContext;
        this.brandContext = brandContext;
        this.idempotencyService = idempotencyService;
        this.eventPublisher = eventPublisher;
        this.messageStreamRegistry = messageStreamRegistry;
    }

    @Transactional(readOnly = true)
    public List<DealResponse> list(AuthPrincipal principal, String statusFilter) {
        UserType role = requireRole(principal);
        List<Collaboration> collaborations = loadCollaborations(principal, role);
        List<CollaborationStatus> allowed = statusesForFilter(statusFilter, role);
        return collaborations.stream()
                .filter(c -> allowed == null || allowed.contains(c.getStatus()))
                .map(c -> toDealResponse(c, principal, role))
                .toList();
    }

    @Transactional(readOnly = true)
    public DealResponse get(AuthPrincipal principal, String dealId) {
        Collaboration collaboration = requireOwnedCollaboration(principal, dealId);
        return toDealResponse(collaboration, principal, principal.getUserType());
    }

    /**
     * Resolves the offer target for {@link #createProposal}, applying the SAME visibility rules
     * {@code CreatorDiscoveryService.requireDiscoverableProfile} applies to {@code POST
     * /creators/{id}/invite}.
     *
     * <p>[SEC 2026-07-26, CEO call] Until this existed {@code createProposal} used a bare {@code
     * findById}, so a priced offer could be sent to a creator who had turned discoverability off
     * or — worse — been suspended by moderation. Invite blocked exactly that; the two brand-side
     * entry points into a Collaboration disagreed. The path had zero UI callers, which is why the
     * gap survived: wiring the direct-offer UI is what would have made it reachable.
     *
     * <p>Suspension is non-negotiable (a suspended creator receives no offers from anyone).
     * Discoverability is the creator's own choice and is respected the same way. Accepts either a
     * CreatorProfile id or a userId, matching invite's tolerance — the request field is named
     * {@code creatorId} but discovery hands the frontend a PROFILE id.
     */
    private CreatorProfile requireOfferableProfile(String creatorIdOrUserId) {
        return creatorProfileRepository
                .findByIdAndDiscoverableTrue(creatorIdOrUserId)
                .or(
                        () ->
                                creatorProfileRepository
                                        .findByUserId(creatorIdOrUserId)
                                        .filter(CreatorProfile::isDiscoverable))
                .filter(profile -> !profile.isSuspended())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_NOT_FOUND",
                                        "Creator not found",
                                        HttpStatus.NOT_FOUND));
    }

    @Transactional
    public DealResponse createProposal(AuthPrincipal principal, CreateDealRequest body) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        Campaign campaign = requireWorkspaceCampaign(workspace.getId(), body.campaignId());
        CreatorProfile creator = requireOfferableProfile(body.creatorId());
        validateProposalAmount(campaign, body.amount());

        if (collaborationRepository.existsByCampaignIdAndCreatorId(
                campaign.getId(), creator.getUserId())) {
            throw new ApiException(
                    "COLLABORATION_EXISTS",
                    "A deal already exists for this campaign and creator",
                    HttpStatus.CONFLICT);
        }

        Collaboration collaboration =
                Collaboration.propose(
                        Ulids.newUlid(),
                        campaign.getId(),
                        creator.getUserId(),
                        body.amount(),
                        campaign.getCurrency(),
                        body.message());
        // A7-U1 — persist the submitted usage-rights terms instead of silently dropping them.
        if (body.usageRights() != null && !body.usageRights().isBlank()) {
            collaboration.setUsageRights(TextSanitizer.sanitizePlainText(body.usageRights()));
        }
        try {
            collaborationRepository.save(collaboration);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(
                    "COLLABORATION_EXISTS",
                    "A deal already exists for this campaign and creator",
                    HttpStatus.CONFLICT);
        }

        persistProposalMessage(
                collaboration,
                principal.getUserId(),
                DealSenderType.brand,
                body.amount(),
                body.message(),
                body.deliverables(),
                body.deadline());

        // W3-1 — #3 "brand sends proposal/bid" (07-NOTIFICATION-SYSTEM-SPEC.md §3.1).
        try {
            eventPublisher.publishEvent(
                    new ProposalSentEvent(
                            creator.getUserId(),
                            workspace.getId(),
                            collaboration.getId(),
                            workspace.getName(),
                            campaign.getTitle(),
                            body.amount() + " " + campaign.getCurrency()));
        } catch (RuntimeException e) {
            log.error(
                    "ProposalSentEvent publish failed for collaboration {} — proposal itself already"
                            + " succeeded",
                    collaboration.getId(),
                    e);
        }

        return toDealResponse(collaboration, principal, UserType.BRAND);
    }

    /**
     * B-4 — role-aware: brand OR creator may accept whichever offer is currently on the
     * table, mirroring {@link #counter}'s dual-role auth + workspace/ownership scoping via
     * {@link #requireOwnedCollaboration}. Previously hard-gated {@code
     * creatorContext.requireCreator}, which 403'd every brand-side accept attempt.
     */
    @Transactional
    public DealResponse accept(AuthPrincipal principal, String dealId, String idempotencyKey) {
        UserType role = requireRole(principal);
        Collaboration collaboration = requireOwnedCollaboration(principal, dealId);
        String scopeId =
                role == UserType.CREATOR
                        ? principal.getUserId()
                        : brandContext.requireBrandWorkspace(principal).getId();
        String key = resolveIdempotencyKey(idempotencyKey, "deal-accept:" + dealId);

        try {
            return idempotencyService.executeOnce(
                    key,
                    scopeId,
                    "deal.accept",
                    () -> doAccept(collaboration, principal, role));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            Collaboration refreshed = requireOwnedCollaboration(principal, dealId);
            return toDealResponse(refreshed, principal, role);
        }
    }

    /**
     * B-4 — role-aware: brand OR creator may reject/withdraw from the deal, mirroring {@link
     * #counter}'s dual-role auth + workspace/ownership scoping. Previously hard-gated {@code
     * creatorContext.requireCreator}, which 403'd every brand-side reject attempt.
     */
    @Transactional
    public OkResponse reject(AuthPrincipal principal, String dealId, RejectRequest body) {
        UserType role = requireRole(principal);
        Collaboration collaboration = requireOwnedCollaboration(principal, dealId);
        if (!collaboration.canReject()) {
            throw new ApiException(
                    "DEAL_NOT_REJECTABLE",
                    "This deal cannot be rejected in its current state",
                    HttpStatus.CONFLICT);
        }
        collaboration.transitionTo(CollaborationStatus.CANCELLED);
        collaborationRepository.save(collaboration);
        String reason = body != null && body.reason() != null ? body.reason() : "Deal rejected";
        String actorLabel = role == UserType.CREATOR ? "Creator" : "Brand";
        appendSystemMessage(
                collaboration.getId(),
                actorLabel + " rejected: " + TextSanitizer.sanitizePlainText(reason));
        return OkResponse.success();
    }

    @Transactional
    public DealResponse counter(
            AuthPrincipal principal, String dealId, CounterRequest body, String idempotencyKey) {
        Collaboration collaboration = requireOwnedCollaboration(principal, dealId);
        if (!collaboration.canCounter()) {
            throw new ApiException(
                    "DEAL_NOT_NEGOTIABLE",
                    "This deal cannot be countered in its current state",
                    HttpStatus.CONFLICT);
        }

        Campaign campaign =
                campaignRepository
                        .findById(collaboration.getCampaignId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND",
                                                "Campaign not found",
                                                HttpStatus.NOT_FOUND));
        validateProposalAmount(campaign, body.amount());

        UserType role = principal.getUserType();
        String scopeId =
                role == UserType.CREATOR
                        ? principal.getUserId()
                        : brandContext.requireBrandWorkspace(principal).getId();
        String key = resolveIdempotencyKey(idempotencyKey, "deal-counter:" + dealId + ":" + body.amount());

        DealSenderType senderType = role == UserType.CREATOR ? DealSenderType.creator : DealSenderType.brand;

        try {
            return idempotencyService.executeOnce(
                    key,
                    scopeId,
                    "deal.counter",
                    () -> doCounter(collaboration, principal, body, senderType));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            Collaboration refreshed = requireOwnedCollaboration(principal, dealId);
            return toDealResponse(refreshed, principal, role);
        }
    }

    @Transactional(readOnly = true)
    public List<DealMessageResponse> listMessages(AuthPrincipal principal, String dealId, String before) {
        Collaboration collaboration = requireOwnedCollaboration(principal, dealId);
        Instant beforeInstant = parseBefore(before);
        List<DealMessage> rows =
                dealMessageRepository.findPageBefore(
                        collaboration.getId(), beforeInstant, PageRequest.of(0, MESSAGE_PAGE_SIZE));
        if (rows.isEmpty() && collaboration.getNotes() != null && !collaboration.getNotes().isBlank()) {
            return List.of(seedNotesMessage(collaboration));
        }
        List<DealMessageResponse> result = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            result.add(toMessageResponse(rows.get(i)));
        }
        return result;
    }

    @Transactional
    public DealMessageResponse sendMessage(
            AuthPrincipal principal, String dealId, SendMessageRequest body) {
        Collaboration collaboration = requireOwnedCollaboration(principal, dealId);
        UserType role = principal.getUserType();
        DealSenderType senderType = role == UserType.CREATOR ? DealSenderType.creator : DealSenderType.brand;
        // Kabir M-1: user-initiated messages must not be able to spoof privileged
        // card kinds (system/payment/contract/proposal). Only 'text' is client-
        // selectable here; all server-authoritative kinds are set exclusively on
        // internal paths (persistProposalMessage, system notifications).
        DealMessageKind kind = DealMessageKind.text;

        // W3-1 — #2/#15 "brand/creator sends first message" (07-NOTIFICATION-SYSTEM-SPEC.md
        // §3.1/§3.2). Checked BEFORE persisting this message, so it only fires the very first time
        // this sender type posts on this collaboration.
        boolean isFirstFromThisSender =
                !dealMessageRepository.existsByCollaborationIdAndSenderType(collaboration.getId(), senderType);

        DealMessage message =
                DealMessage.create(
                        Ulids.newUlid(),
                        collaboration.getId(),
                        kind,
                        principal.getUserId(),
                        senderType,
                        TextSanitizer.sanitizePlainText(body.content()),
                        null);
        dealMessageRepository.save(message);

        if (isFirstFromThisSender) {
            try {
                notifyFirstMessage(collaboration, senderType);
            } catch (RuntimeException e) {
                log.error(
                        "First-message notification publish failed for collaboration {} — message"
                                + " itself already succeeded",
                        collaboration.getId(),
                        e);
            }
        }

        DealMessageResponse response = toMessageResponse(message);
        // Realtime fan-out (SSE) — same DTO shape as the response returned to the sender, so
        // both parties' open GET /deals/{dealId}/messages/stream connections render it
        // identically. Best-effort: a registry publish failure must never fail the send itself,
        // which has already fully succeeded above (message is persisted regardless).
        try {
            messageStreamRegistry.publish(collaboration.getId(), response);
        } catch (RuntimeException e) {
            log.error(
                    "SSE publish failed for collaboration {} — message itself already persisted",
                    collaboration.getId(),
                    e);
        }

        return response;
    }

    /**
     * Authorization gate for {@code GET /deals/{dealId}/messages/stream}. Reuses the exact same
     * ownership check {@link #listMessages}/{@link #sendMessage} rely on — never invent a
     * separate one for the SSE path. Throws {@link ApiException} (propagates as the normal 404/
     * 403 JSON error response) if the caller is not a party to this deal; the controller MUST
     * call this and let it throw BEFORE creating or registering an SseEmitter, so an unauthorized
     * caller never gets a live connection.
     */
    @Transactional(readOnly = true)
    public void authorizeMessageStream(AuthPrincipal principal, String dealId) {
        requireOwnedCollaboration(principal, dealId);
    }

    private void notifyFirstMessage(Collaboration collaboration, DealSenderType senderType) {
        Campaign campaign = campaignRepository.findById(collaboration.getCampaignId()).orElse(null);
        if (campaign == null) {
            return;
        }
        if (senderType == DealSenderType.brand) {
            Workspace workspace = workspaceRepository.findById(campaign.getWorkspaceId()).orElse(null);
            String brandName = workspace != null ? workspace.getName() : "The brand";
            eventPublisher.publishEvent(
                    new FirstMessageSentEvent(
                            collaboration.getCreatorId(), campaign.getWorkspaceId(), collaboration.getId(), brandName));
        } else if (senderType == DealSenderType.creator) {
            var recipient = brandContext.resolveBillingRecipient(campaign.getWorkspaceId());
            if (recipient == null) {
                return;
            }
            CreatorProfile creator =
                    creatorProfileRepository.findByUserId(collaboration.getCreatorId()).orElse(null);
            String creatorName = creator != null ? creator.getDisplayName() : "The creator";
            eventPublisher.publishEvent(
                    new CreatorFirstMessageEvent(
                            recipient.userId(), campaign.getWorkspaceId(), collaboration.getId(), creatorName));
        }
    }

    @Transactional
    public OkResponse markRead(AuthPrincipal principal, String dealId) {
        Collaboration collaboration = requireOwnedCollaboration(principal, dealId);
        String userId = principal.getUserId();
        for (DealMessage message : dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(collaboration.getId())) {
            List<String> readBy = parseReadBy(message.getReadByJson());
            if (!readBy.contains(userId)) {
                readBy.add(userId);
                message.setReadByJson(writeJson(readBy));
                dealMessageRepository.save(message);
            }
        }
        return OkResponse.success();
    }

    /**
     * Brand-side deal-room deliverables list ({@code GET /deals/{dealId}/deliverables}) — was a
     * documented gap (frontend {@code api.deliverables.list} called it, backend 404'd; see
     * "Deals/Contracts (40% → live)" backlog notes). Dual-role via {@link
     * #requireOwnedCollaboration}, same trust boundary as {@link #listMessages}/{@link
     * #sendMessage}. Reuses {@link CreatorDeliverableService#toListItem} so the row shape is
     * identical for both roles instead of a second, drifting mapping.
     */
    @Transactional(readOnly = true)
    public List<DeliverableListItem> listDeliverables(AuthPrincipal principal, String dealId) {
        Collaboration collaboration = requireOwnedCollaboration(principal, dealId);
        return deliverableRepository
                .findByCollaborationIdOrderBySlotIndexAsc(collaboration.getId())
                .stream()
                .map(CreatorDeliverableService::toListItem)
                .toList();
    }

    private DealResponse doAccept(Collaboration collaboration, AuthPrincipal principal, UserType role) {
        if (!collaboration.canAccept()) {
            throw new ApiException(
                    "DEAL_NOT_ACCEPTABLE",
                    "This deal cannot be accepted in its current state",
                    HttpStatus.CONFLICT);
        }

        // B-4 product-assumption guard: a party must not accept the offer they themselves
        // last put on the table — only the counterparty can accept it. Determined from the
        // most recent proposal/counter event, not collaboration state, since either role can
        // author the "last offer" via createProposal or counter(). See report for the
        // Priya/CTO-overridable assumption this encodes.
        DealSenderType actingAs = role == UserType.CREATOR ? DealSenderType.creator : DealSenderType.brand;
        Optional<DealMessage> lastOffer =
                dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        collaboration.getId(), DealMessageKind.proposal);
        if (lastOffer.isPresent() && lastOffer.get().getSenderType() == actingAs) {
            throw new ApiException(
                    "CANNOT_ACCEPT_OWN_OFFER",
                    "You cannot accept the offer you last made — waiting on the other party",
                    HttpStatus.CONFLICT);
        }

        collaboration.transitionTo(CollaborationStatus.TERMS_AGREED);
        collaborationRepository.save(collaboration);
        String actorLabel = role == UserType.CREATOR ? "Creator" : "Brand";
        appendSystemMessage(collaboration.getId(), actorLabel + " accepted the proposal");

        // W3-1 — #4 "brand accepts creator's counter-bid" / #11 "creator accepts proposal"
        // (07-NOTIFICATION-SYSTEM-SPEC.md §3.1/§3.2). Best-effort — a lookup failure here must
        // never fail the accept itself, which has already fully succeeded above.
        try {
            notifyAccepted(collaboration, role);
        } catch (RuntimeException e) {
            log.error(
                    "Accept notification publish failed for collaboration {} — accept itself already"
                            + " succeeded",
                    collaboration.getId(),
                    e);
        }

        return toDealResponse(collaboration, principal, role);
    }

    private void notifyAccepted(Collaboration collaboration, UserType actingRole) {
        Campaign campaign = campaignRepository.findById(collaboration.getCampaignId()).orElse(null);
        if (campaign == null) {
            return;
        }
        String campaignTitle = campaign.getTitle();
        String amount = collaboration.getAgreedRate() != null
                ? collaboration.getAgreedRate() + " " + collaboration.getCurrency()
                : "the agreed amount";
        if (actingRole == UserType.CREATOR) {
            // Creator accepted the brand's offer — notify the brand (#11 ProposalAcceptedEvent).
            var recipient = brandContext.resolveBillingRecipient(campaign.getWorkspaceId());
            if (recipient == null) {
                return;
            }
            CreatorProfile creator =
                    creatorProfileRepository.findByUserId(collaboration.getCreatorId()).orElse(null);
            String creatorName = creator != null ? creator.getDisplayName() : "The creator";
            eventPublisher.publishEvent(
                    new ProposalAcceptedEvent(
                            recipient.userId(), campaign.getWorkspaceId(), collaboration.getId(), creatorName, campaignTitle));
        } else {
            // Brand accepted the creator's offer — notify the creator (#4 BidAcceptedEvent).
            Workspace workspace = workspaceRepository.findById(campaign.getWorkspaceId()).orElse(null);
            String brandName = workspace != null ? workspace.getName() : "The brand";
            eventPublisher.publishEvent(
                    new BidAcceptedEvent(
                            collaboration.getCreatorId(),
                            campaign.getWorkspaceId(),
                            collaboration.getId(),
                            brandName,
                            campaignTitle,
                            amount));
        }
    }

    private DealResponse doCounter(
            Collaboration collaboration,
            AuthPrincipal principal,
            CounterRequest body,
            DealSenderType senderType) {
        collaboration.updateAgreedRate(body.amount());
        // Aligned with createProposal 2026-07-26: a counter that revises usage rights now updates
        // the deal's terms instead of leaving the original proposal's value standing. Blank is
        // treated as "not renegotiating this term", so a counter that only moves the price keeps
        // whatever usage rights were already agreed rather than clearing them.
        if (body.usageRights() != null && !body.usageRights().isBlank()) {
            collaboration.setUsageRights(TextSanitizer.sanitizePlainText(body.usageRights()));
        }
        collaboration.transitionTo(CollaborationStatus.IN_NEGOTIATION);
        collaborationRepository.save(collaboration);
        persistProposalMessage(
                collaboration,
                principal.getUserId(),
                senderType,
                body.amount(),
                body.message(),
                body.deliverables(),
                body.deadline());

        // W3-1 — #10 "creator sends counter-bid" (07-NOTIFICATION-SYSTEM-SPEC.md §3.2). No
        // equivalent notification event exists for a brand counter (spec only models this
        // direction), so nothing is published when the brand is the one countering.
        if (senderType == DealSenderType.creator) {
            try {
                notifyBidCountered(collaboration, body);
            } catch (RuntimeException e) {
                log.error(
                        "BidCounteredEvent publish failed for collaboration {} — counter itself already"
                                + " succeeded",
                        collaboration.getId(),
                        e);
            }
        }

        return toDealResponse(collaboration, principal, principal.getUserType());
    }

    private void notifyBidCountered(Collaboration collaboration, CounterRequest body) {
        Campaign campaign = campaignRepository.findById(collaboration.getCampaignId()).orElse(null);
        if (campaign == null) {
            return;
        }
        var recipient = brandContext.resolveBillingRecipient(campaign.getWorkspaceId());
        if (recipient == null) {
            return;
        }
        CreatorProfile creator =
                creatorProfileRepository.findByUserId(collaboration.getCreatorId()).orElse(null);
        String creatorName = creator != null ? creator.getDisplayName() : "The creator";
        eventPublisher.publishEvent(
                new BidCounteredEvent(
                        recipient.userId(),
                        campaign.getWorkspaceId(),
                        collaboration.getId(),
                        creatorName,
                        campaign.getTitle(),
                        body.amount() + " " + campaign.getCurrency()));
    }

    private Collaboration requireOwnedCollaboration(AuthPrincipal principal, String dealId) {
        UserType role = requireRole(principal);
        if (role == UserType.CREATOR) {
            return requireCreatorCollaboration(principal, dealId);
        }
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        return collaborationRepository
                .findByIdAndWorkspaceId(dealId, workspace.getId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND));
    }

    private Collaboration requireCreatorCollaboration(AuthPrincipal principal, String dealId) {
        creatorContext.requireCreator(principal);
        return collaborationRepository
                .findByIdAndCreatorId(dealId, principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND));
    }

    private List<Collaboration> loadCollaborations(AuthPrincipal principal, UserType role) {
        if (role == UserType.CREATOR) {
            CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
            return collaborationRepository.findByCreatorId(profile.getUserId());
        }
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        return collaborationRepository.findByWorkspaceId(workspace.getId());
    }

    private UserType requireRole(AuthPrincipal principal) {
        if (principal == null
                || (principal.getUserType() != UserType.CREATOR
                        && principal.getUserType() != UserType.BRAND)) {
            throw new ApiException(
                    "WRONG_USER_TYPE",
                    "This endpoint is for brand or creator accounts only",
                    HttpStatus.FORBIDDEN);
        }
        return principal.getUserType();
    }

    private Campaign requireWorkspaceCampaign(String workspaceId, String campaignId) {
        Campaign campaign =
                campaignRepository
                        .findById(campaignId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND",
                                                "Campaign not found",
                                                HttpStatus.NOT_FOUND));
        if (!workspaceId.equals(campaign.getWorkspaceId())) {
            throw new ApiException(
                    "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND);
        }
        return campaign;
    }

    private void validateProposalAmount(Campaign campaign, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(
                    "INVALID_AMOUNT", "Amount must be positive", HttpStatus.BAD_REQUEST);
        }
        if (campaign.getBudgetMax() != null && amount.compareTo(campaign.getBudgetMax()) > 0) {
            throw new ApiException(
                    "AMOUNT_EXCEEDS_BUDGET",
                    "Proposed amount exceeds campaign budget",
                    HttpStatus.BAD_REQUEST);
        }
        if (campaign.getBudgetMin() != null && amount.compareTo(campaign.getBudgetMin()) < 0) {
            throw new ApiException(
                    "AMOUNT_BELOW_BUDGET",
                    "Proposed amount is below campaign minimum budget",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private void persistProposalMessage(
            Collaboration collaboration,
            String senderId,
            DealSenderType senderType,
            BigDecimal amount,
            String message,
            List<com.influora.web.dto.deal.DealDtos.DeliverableSlot> deliverables,
            String deadline) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("amount", amount);
        metadata.put("status", "pending");
        if (deliverables != null && !deliverables.isEmpty()) {
            // Store the slots themselves, not just how many there were. Until 2026-07-26 this was
            // `deliverables.size()`, so a proposal for "2 Reels + 1 Story" persisted as the
            // integer 3 and the deal room could never render what was actually offered — the
            // types and quantities the brand chose were dropped at the persistence layer. Nothing
            // consumed the old count (no reader in the API or the SPA), so this is a safe shape
            // change rather than a breaking one.
            metadata.put("deliverables", deliverables);
            metadata.put("deliverableCount", deliverables.size());
        }
        if (deadline != null && !deadline.isBlank()) {
            metadata.put("deadline", deadline);
        }
        DealMessage proposal =
                DealMessage.create(
                        Ulids.newUlid(),
                        collaboration.getId(),
                        DealMessageKind.proposal,
                        senderId,
                        senderType,
                        TextSanitizer.sanitizePlainText(message),
                        writeJson(metadata));
        dealMessageRepository.save(proposal);
    }

    private void appendSystemMessage(String collaborationId, String content) {
        dealMessageRepository.save(
                DealMessage.create(
                        Ulids.newUlid(),
                        collaborationId,
                        DealMessageKind.system,
                        "system",
                        DealSenderType.system,
                        TextSanitizer.sanitizePlainText(content),
                        null));
    }

    private DealResponse toDealResponse(
            Collaboration collaboration, AuthPrincipal principal, UserType role) {
        Campaign campaign =
                campaignRepository.findById(collaboration.getCampaignId()).orElse(null);
        String campaignName = campaign != null ? campaign.getTitle() : "Campaign";

        Counterparty counterparty = resolveCounterparty(collaboration, campaign, role);

        Optional<DealMessage> lastMsg =
                dealMessageRepository.findFirstByCollaborationIdOrderByCreatedAtDesc(collaboration.getId());

        String userId = principal.getUserId();
        int unread =
                (int)
                        dealMessageRepository
                                .findByCollaborationIdOrderByCreatedAtAsc(collaboration.getId())
                                .stream()
                                .filter(m -> !parseReadBy(m.getReadByJson()).contains(userId))
                                .count();

        // [BE-1: Vikram, contract-flow-architecture-2026-07-23 §6.4] version DESC alone is an
        // unstable tiebreak -- every Contract row defaults to version=1 (Contract.Builder#build),
        // so with more than one row for a collaboration "the latest" was query-plan-dependent.
        // createdAt DESC as the secondary sort makes "most recently created wins" deterministic.
        List<Contract> contracts =
                contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(
                        collaboration.getId());
        Contract latest = contracts.isEmpty() ? null : contracts.get(0);

        boolean escrowFunded =
                escrowHoldRepository.existsByCollaborationIdAndStatus(
                        collaboration.getId(), EscrowStatus.FUNDED);

        return new DealResponse(
                collaboration.getId(),
                collaboration.getCampaignId(),
                campaignName,
                counterparty.id(),
                counterparty.name(),
                counterparty.avatar(),
                counterparty.handle(),
                collaboration.getStatus(),
                collaboration.getAgreedRate(),
                collaboration.getCurrency(),
                lastMsg.map(DealMessage::getContent).orElse(collaboration.getNotes()),
                lastMsg.map(DealMessage::getCreatedAt).orElse(collaboration.getUpdatedAt()),
                unread,
                0,
                0,
                null,
                latest != null ? latest.getId() : null,
                latest != null ? latest.getStatus() : null,
                escrowFunded);
    }

    private Counterparty resolveCounterparty(
            Collaboration collaboration, Campaign campaign, UserType viewerRole) {
        if (viewerRole == UserType.CREATOR) {
            String workspaceId = campaign != null ? campaign.getWorkspaceId() : null;
            if (workspaceId == null) {
                return new Counterparty("unknown", "Brand", null, null);
            }
            Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
            return new Counterparty(
                    workspaceId,
                    workspace != null ? workspace.getName() : "Brand",
                    workspace != null ? workspace.getLogoUrl() : null,
                    null);
        }
        CreatorProfile creator =
                creatorProfileRepository.findByUserId(collaboration.getCreatorId()).orElse(null);
        return new Counterparty(
                collaboration.getCreatorId(),
                creator != null ? creator.getDisplayName() : "Creator",
                creator != null ? creator.getAvatarUrl() : null,
                creator != null ? creator.getUsername() : null);
    }

    private DealMessageResponse seedNotesMessage(Collaboration collaboration) {
        DealSenderType senderType =
                collaboration.getSource() == com.influora.domain.enums.CollaborationSource.APPLICATION
                        ? DealSenderType.creator
                        : DealSenderType.brand;
        String senderId =
                senderType == DealSenderType.creator
                        ? collaboration.getCreatorId()
                        : "brand";
        return new DealMessageResponse(
                "seed-" + collaboration.getId(),
                collaboration.getId(),
                DealMessageKind.text,
                senderId,
                senderType,
                collaboration.getNotes(),
                null,
                collaboration.getCreatedAt(),
                List.of());
    }

    private DealMessageResponse toMessageResponse(DealMessage message) {
        return new DealMessageResponse(
                message.getId(),
                message.getCollaborationId(),
                message.getKind(),
                message.getSenderId(),
                message.getSenderType(),
                message.getContent(),
                parseMetadata(message.getMetadataJson()),
                message.getCreatedAt(),
                parseReadBy(message.getReadByJson()));
    }

    private static List<CollaborationStatus> statusesForFilter(String filter, UserType role) {
        if (filter == null || filter.isBlank() || "all".equalsIgnoreCase(filter)) {
            return null;
        }
        return switch (filter.toLowerCase()) {
            case "new" ->
                    role == UserType.CREATOR
                            ? List.of(CollaborationStatus.INVITED)
                            : List.of(CollaborationStatus.APPLIED, CollaborationStatus.INVITED);
            case "negotiating" ->
                    List.of(CollaborationStatus.SHORTLISTED, CollaborationStatus.IN_NEGOTIATION);
            case "contracted" ->
                    List.of(
                            CollaborationStatus.TERMS_AGREED,
                            CollaborationStatus.CONTRACT_PENDING,
                            CollaborationStatus.CONTRACTED);
            case "in_progress" -> List.of(CollaborationStatus.IN_PROGRESS);
            case "review" ->
                    List.of(
                            CollaborationStatus.REVIEW_PENDING,
                            CollaborationStatus.REVISION_REQUESTED);
            case "completed" -> List.of(CollaborationStatus.COMPLETED);
            default ->
                    throw new ApiException(
                            "INVALID_STATUS_FILTER",
                            "Unknown status filter: " + filter,
                            HttpStatus.BAD_REQUEST);
        };
    }

    private static String resolveIdempotencyKey(String header, String fallback) {
        if (header != null && !header.isBlank()) {
            return header;
        }
        return fallback;
    }

    private static Instant parseBefore(String before) {
        if (before == null || before.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(before);
        } catch (Exception ex) {
            throw new ApiException(
                    "INVALID_BEFORE_CURSOR",
                    "before must be an ISO-8601 instant",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private static List<String> parseReadBy(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(MAPPER.readValue(json, new TypeReference<List<String>>() {}));
        } catch (JsonProcessingException ex) {
            return new ArrayList<>();
        }
    }

    private static Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            return Collections.emptyMap();
        }
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(
                    "JSON_ERROR", "Failed to serialize JSON", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private record Counterparty(String id, String name, String avatar, String handle) {}
}
