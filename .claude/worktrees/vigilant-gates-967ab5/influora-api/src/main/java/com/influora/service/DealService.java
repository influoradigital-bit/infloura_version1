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
            IdempotencyService idempotencyService) {
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

    @Transactional
    public DealResponse createProposal(AuthPrincipal principal, CreateDealRequest body) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        Campaign campaign = requireWorkspaceCampaign(workspace.getId(), body.campaignId());
        CreatorProfile creator =
                creatorProfileRepository
                        .findById(body.creatorId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_NOT_FOUND",
                                                "Creator not found",
                                                HttpStatus.NOT_FOUND));
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
        return toMessageResponse(message);
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
        return toDealResponse(collaboration, principal, role);
    }

    private DealResponse doCounter(
            Collaboration collaboration,
            AuthPrincipal principal,
            CounterRequest body,
            DealSenderType senderType) {
        collaboration.updateAgreedRate(body.amount());
        collaboration.transitionTo(CollaborationStatus.IN_NEGOTIATION);
        collaborationRepository.save(collaboration);
        persistProposalMessage(
                collaboration,
                principal.getUserId(),
                senderType,
                body.amount(),
                body.message(),
                body.deliverables(),
                null);
        return toDealResponse(collaboration, principal, principal.getUserType());
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
        if (deliverables != null) {
            metadata.put("deliverables", deliverables.size());
        }
        if (deadline != null) {
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

        List<Contract> contracts =
                contractRepository.findByCollaborationIdOrderByVersionDesc(collaboration.getId());
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
