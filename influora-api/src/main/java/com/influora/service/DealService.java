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
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.CollaborationSource;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MemberRole;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    /** F-0225 — shared revive decision; see CollaborationReviveService. */
    private final CollaborationReviveService collaborationReviveService;
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
            DealMessageStreamRegistry messageStreamRegistry,
            CollaborationReviveService collaborationReviveService) {
        this.collaborationRepository = collaborationRepository;
        this.collaborationReviveService = collaborationReviveService;
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
        List<Collaboration> filtered =
                collaborations.stream()
                        .filter(c -> allowed == null || allowed.contains(c.getStatus()))
                        .toList();
        Map<String, List<Deliverable>> deliverablesByCollaboration =
                loadDeliverablesByCollaboration(filtered);
        return filtered.stream()
                .map(
                        c ->
                                toDealResponse(
                                        c,
                                        principal,
                                        role,
                                        deliverablesByCollaboration.getOrDefault(
                                                c.getId(), List.of())))
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
        // [CR-37 followup, Kavya QA of 7991342] Gated on the same management tier as
        // accept/counter/reject. That commit closed those three and left this one open, which made
        // the model self-contradictory: a VIEWER could not COUNTER a deal to an amount but could
        // PROPOSE one at that amount, and both set a negotiation price. There is no web-layer gate
        // to fall back on — DealController carries no @PreAuthorize/@Secured/@RolesAllowed, so this
        // service is the sole authz point.
        //
        // Placed before the campaign and creator lookups on purpose: a VIEWER must not be able to
        // probe which campaignIds or creatorIds exist by reading 404 vs 403 off this endpoint.
        Workspace workspace = requireBrandDealManagerWorkspace(principal);
        Campaign campaign = requireWorkspaceCampaign(workspace.getId(), body.campaignId());
        CreatorProfile creator = requireOfferableProfile(body.creatorId());
        validateProposalAmount(campaign, body.amount());

        // F-0225 — was a status-blind existsBy..., so a withdrawn deal blocked the brand from ever
        // re-offering on this campaign. Shares the decision with apply/invite rather than keeping a
        // fourth copy of it: the CANCELLED-but-encumbered case is far too dangerous to duplicate.
        // Revives to IN_NEGOTIATION/INVITATION, matching Collaboration.propose's own status/source
        // pair below, then applies this path's own terms (amount, usage rights) via the existing
        // updateAgreedRate/setUsageRights calls.
        Collaboration revived =
                collaborationReviveService.reviveOrRefuse(
                        campaign.getId(),
                        creator.getUserId(),
                        CollaborationStatus.IN_NEGOTIATION,
                        CollaborationSource.INVITATION,
                        body.message(),
                        campaign.getCurrency(),
                        "COLLABORATION_EXISTS",
                        "A deal already exists for this campaign and creator");
        Collaboration collaboration;
        if (revived != null) {
            // revive() cleared the withdrawn attempt's rate and usage rights; this proposal's own
            // terms go on now. Sanitised here for the same reason the insert branch sanitises —
            // setUsageRights does not do it itself.
            collaboration = revived;
            collaboration.updateAgreedRate(body.amount());
            if (body.usageRights() != null && !body.usageRights().isBlank()) {
                collaboration.setUsageRights(TextSanitizer.sanitizePlainText(body.usageRights()));
            }
            collaborationRepository.save(collaboration);
        } else {
            collaboration =
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
                        : requireBrandDealManagerScope(principal);
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
     *
     * <p><b>CR-22a — narrowed to a pre-contract allowlist</b> (Priya's ruling, `CREATOR-BUG-
     * TRACKER.md` §10.1/§10.7). {@link Collaboration#canReject()} used to be a denylist over the
     * whole lifecycle, so this endpoint could unilaterally CANCEL a signed, escrow-funded deal
     * with no compensating action anywhere. It now only admits the pre-contract negotiation
     * states; {@code CONTRACT_PENDING} and beyond return 409 {@code DEAL_NOT_REJECTABLE}.
     * Post-contract withdrawal is CR-22b (a separate, two-party termination flow) — out of scope
     * here.
     *
     * <p><b>Kabir's finding #6 folded in here</b> (fixed in this method, not touched twice, per
     * the ruling): this previously read the collaboration through a plain, non-locking finder
     * and re-checked nothing before writing {@code CANCELLED} — a real lost-update race against
     * {@code ContractService#doRecordSignature} (both starting from {@code CONTRACT_PENDING}
     * before this narrowing; today the equivalent race is against a fresh
     * {@code ContractService#generate}/{@code EscrowService#initiateFund} call starting from
     * {@code TERMS_AGREED}, the highest status still in the allowlist). Fixed the same way {@code
     * ContractService#generate} already serializes itself: {@link #doReject} takes a {@code
     * PESSIMISTIC_WRITE} lock on the collaboration row ({@link CollaborationRepository
     * #findByIdForUpdate}) BEFORE the {@code canReject()} check-then-write, so whichever
     * transaction reaches the lock first serializes the other one behind it — the loser observes
     * the winner's COMMITTED status, never a stale snapshot. This method is also now routed
     * through {@link IdempotencyService}, mirroring {@link #accept}/{@link #counter} — which
     * incidentally resolves finding #8 too: a retried reject after success now replays the prior
     * 200 instead of a fresh 409.
     */
    @Transactional
    public OkResponse reject(
            AuthPrincipal principal, String dealId, RejectRequest body, String idempotencyKey) {
        UserType role = requireRole(principal);
        // Ownership/existence check ONLY, deliberately unlocked and deliberately not also
        // re-running canReject() here: on a genuine retry of an already-succeeded reject, this
        // deal is now CANCELLED, so an early canReject() check here would 409 before the
        // idempotency wrapper below ever gets a chance to recognize the call as a replay — exactly
        // the finding #8 failure mode this change is supposed to close. The real gate lives in
        // doReject, under the row lock, where it belongs.
        requireOwnedCollaboration(principal, dealId);
        String scopeId =
                role == UserType.CREATOR
                        ? principal.getUserId()
                        : requireBrandDealManagerScope(principal);
        String key = resolveIdempotencyKey(idempotencyKey, "deal-reject:" + dealId);

        try {
            return idempotencyService.executeOnce(
                    key, scopeId, "deal.reject", () -> doReject(dealId, role, body));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            // A prior call already reserved/completed this exact key — the effect already ran (or
            // is running). Replay success rather than re-entering doReject, which would otherwise
            // re-check canReject() against the now-CANCELLED row and 409.
            return OkResponse.success();
        }
    }

    /**
     * Runs inside {@code executeOnce} (see {@link #reject}, Kabir finding #6). Re-resolves the
     * collaboration by id under a {@code PESSIMISTIC_WRITE} lock — deliberately NOT reusing the
     * unlocked instance {@link #reject} already read, since that read happened before the lock
     * was acquired and may be stale by the time this runs.
     */
    private OkResponse doReject(String dealId, UserType role, RejectRequest body) {
        Collaboration collaboration =
                collaborationRepository
                        .findByIdForUpdate(dealId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND));
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
        DealMessage systemMessage =
                appendSystemMessage(
                        collaboration.getId(),
                        actorLabel + " rejected: " + TextSanitizer.sanitizePlainText(reason));
        // CR-02 — same reason as the accept path: a declined offer must stop rendering as
        // actionable. Pre-CR-22a, canReject() also permitted withdrawal from
        // TERMS_AGREED/CONTRACTED, so this deliberately no-ops on a card already settled as
        // "accepted" rather than rewriting agreed history — settleStatus enforces that; the
        // system message above carries the withdrawal. Kept post-narrowing since TERMS_AGREED
        // (an already-accepted, pre-contract state) is still in the allowlist.
        Optional<DealMessage> settledCard = settleLatestProposal(collaboration.getId(), "rejected");

        // CR-08 — same shape and same ordering rationale as doAccept: settled card first so it is
        // inert before "… rejected: …" lands, then the system message. On the withdrawal case
        // settleStatus no-oped, so what is republished here is the card's UNCHANGED already-settled
        // state — an upsert of identical content, which is harmless and doubles as a resync for a
        // subscriber that missed the original accept frame.
        settledCard.ifPresent(
                card -> publishToStream(collaboration.getId(), toMessageResponse(card)));
        publishToStream(collaboration.getId(), toMessageResponse(systemMessage));
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
        validateCounterAmount(campaign, body.amount());

        UserType role = principal.getUserType();
        String scopeId =
                role == UserType.CREATOR
                        ? principal.getUserId()
                        : requireBrandDealManagerScope(principal);
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
        // identically.
        publishToStream(collaboration.getId(), response);

        return response;
    }

    /**
     * CR-08 — single fan-out call site for every timeline row the deal room needs to see in
     * realtime. Publishes {@code response} to {@code GET /deals/{dealId}/messages/stream} using the
     * exact DTO shape {@link #listMessages} returns, so a subscriber renders a pushed row
     * identically to a refetched one.
     *
     * <p><b>Deliberately best-effort, and that is a different question from the CR-02 [C2]
     * ruling.</b> Unlike {@link #settleLatestProposal} — which writes to {@code deal_messages} and
     * therefore MUST be atomic with the caller's transaction — this touches nothing durable.
     * {@link DealMessageStreamRegistry} is an in-memory {@code ConcurrentHashMap} of live {@link
     * org.springframework.web.servlet.mvc.method.annotation.SseEmitter}s; the message is already
     * persisted before we get here and a subscriber that misses a frame refetches
     * {@code GET /deals/{dealId}/messages} on its next reconnect. A dead or slow emitter must never
     * roll back an accept, so the catch here is load-bearing rather than a swallowed error, and the
     * log line above is the only signal it produces.
     *
     * <p><b>CR-25 — this now runs AFTER COMMIT.</b> Previously every caller published from inside
     * the caller's {@code @Transactional} boundary, so a subscriber could observe a row that a
     * later rollback erased — a creator watching the room would see "Brand accepted the proposal"
     * for an accept that never happened. That predated CR-08 ({@link #sendMessage} had published
     * from inside its transaction since the stream shipped); CR-08 only widened the surface. The
     * fan-out is now registered as an {@code afterCommit} synchronization, so a subscriber only
     * ever sees committed state.
     *
     * <p>When no transaction is active the publish happens inline, unchanged — that keeps the
     * method safe to call from a non-transactional context and keeps unit tests, which do not open
     * a transaction, exercising the real publish path rather than silently dropping every frame.
     */
    private void publishToStream(String collaborationId, DealMessageResponse response) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publishToStreamNow(collaborationId, response);
                        }
                    });
            return;
        }
        publishToStreamNow(collaborationId, response);
    }

    /**
     * The actual fan-out. Best-effort by design — see {@link #publishToStream}. A dead or slow
     * emitter must never fail the action that produced the message, and after CR-25 it provably
     * cannot: by the time this runs the transaction has already committed, so there is nothing
     * left to roll back.
     */
    private void publishToStreamNow(String collaborationId, DealMessageResponse response) {
        try {
            messageStreamRegistry.publish(collaborationId, response);
        } catch (RuntimeException e) {
            log.error(
                    "SSE publish failed for collaboration {} — the message is already persisted and"
                            + " the action stands",
                    collaborationId,
                    e);
        }
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
        DealMessage systemMessage =
                appendSystemMessage(collaboration.getId(), actorLabel + " accepted the proposal");
        // CR-02 — settle the proposal card that produced this accept. Must run AFTER the
        // transition above, so the card is only marked accepted once the deal really is.
        Optional<DealMessage> settledCard = settleLatestProposal(collaboration.getId(), "accepted");

        // CR-08 — until now doAccept persisted both rows above and published neither, so the
        // counterparty's open deal room showed nothing until a manual reload. Publish order is
        // load-bearing and is NOT persistence order: the settled card goes first so a subscriber
        // applying these in sequence never has "Creator accepted the proposal" sitting above a card
        // still offering live Accept/Counter/Decline buttons. The card is an UPSERT keyed on its
        // ORIGINAL id (settleLatestProposal returns the same managed entity, post-settle), so the
        // client replaces the row it already holds rather than appending a duplicate; the system
        // message is genuinely new and appends.
        settledCard.ifPresent(
                card -> publishToStream(collaboration.getId(), toMessageResponse(card)));
        publishToStream(collaboration.getId(), toMessageResponse(systemMessage));

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
        // [H1] Settle the offer this counter supersedes BEFORE persisting the new one — ordering
        // is load-bearing, since settleLatestProposal resolves "the newest proposal" and the new
        // card must not exist yet. Without this every superseded card stayed "pending" and kept
        // its live Accept/Counter/Decline buttons: after three counters the deal was still
        // IN_NEGOTIATION with four actionable cards. POST /deals/{id}/accept carries no proposal
        // id (see api.deals.accept), so accepting is always "accept whatever is current" —
        // clicking Accept on a stale 40,000 card would have agreed to the current 25,000 offer.
        // "countered" is styled by the SPA and is != "pending", so the stale cards go inert.
        Optional<DealMessage> supersededCard =
                settleLatestProposal(collaboration.getId(), "countered");

        // Carry forward deliverables from the superseded proposal when the counter does not
        // revise them (e.g. a creator countering on price/deadline only). Without this, a counter
        // with no deliverables field produces a proposal card with an empty deliverables list,
        // which breaks the contract generator even though the parties never renegotiated scope.
        // Slots are stored as List<LinkedHashMap> after JSON round-trip (Jackson deserialises
        // them as Map, not DeliverableSlot records), so we rehydrate them here.
        var effectiveDeliverables = body.deliverables();
        if ((effectiveDeliverables == null || effectiveDeliverables.isEmpty())
                && supersededCard.isPresent()) {
            Map<String, Object> supersededMetadata =
                    parseMetadata(supersededCard.get().getMetadataJson());
            Object rawSlots = supersededMetadata == null ? null : supersededMetadata.get("deliverables");
            if (rawSlots instanceof List<?> slotList && !slotList.isEmpty()) {
                effectiveDeliverables = slotList.stream()
                        .filter(item -> item instanceof Map)
                        .map(item -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = (Map<String, Object>) item;
                            String type = String.valueOf(m.getOrDefault("type", ""));
                            int qty = ((Number) m.getOrDefault("qty", 1)).intValue();
                            return new com.influora.web.dto.deal.DealDtos.DeliverableSlot(type, qty);
                        })
                        .filter(slot -> !slot.type().isBlank())
                        .toList();
            }
        }

        DealMessage newProposal =
                persistProposalMessage(
                        collaboration,
                        principal.getUserId(),
                        senderType,
                        body.amount(),
                        body.message(),
                        effectiveDeliverables,
                        body.deadline());

        // CR-08 — publish the superseded card BEFORE the new one. This mirrors the [H1] persistence
        // ordering above for the same reason, one layer up: a subscriber that applied these in the
        // opposite order would briefly render TWO cards both offering Accept, and since POST
        // /deals/{id}/accept carries no proposal id, accepting the stale one would have agreed to
        // the new amount. The first publish is an upsert on the superseded card's ORIGINAL id
        // (status now "countered", so the SPA renders it inert); the second is a genuinely new row.
        supersededCard.ifPresent(
                card -> publishToStream(collaboration.getId(), toMessageResponse(card)));
        publishToStream(collaboration.getId(), toMessageResponse(newProposal));

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

    /** CR-80 — statuses a dispute can never be opened against; see {@link #listEligibleForDispute}. */
    private static final Set<CollaborationStatus> DISPUTE_INELIGIBLE_STATUSES =
            Set.of(
                    CollaborationStatus.DISPUTED,
                    CollaborationStatus.COMPLETED,
                    CollaborationStatus.CANCELLED);

    /**
     * CR-80 — backs the creator "open a dispute" deal dropdown ({@code GET
     * /creator/disputes/eligible-deals}, {@code CreatorDisputeController}). Eligible = funded
     * escrow, not already disputed/completed/cancelled — same rule the client used to apply in JS
     * after fetching {@code GET /deals?status=all} (the creator's ENTIRE deal history).
     *
     * <p>The status half of the rule is now a real {@code WHERE status NOT IN (...)} ({@link
     * CollaborationRepository#findByCreatorIdAndStatusNotIn}), so terminal-state deals never reach
     * this service, let alone {@link #toDealResponse}'s per-row enrichment (campaign/contract/
     * escrow/deliverable lookups). {@code escrowFunded} is not a {@link Collaboration} column —
     * it's derived per-row in {@link #toDealResponse} from {@link EscrowHoldRepository} — so it
     * stays a final in-memory filter, but now over only the creator's non-terminal deals instead
     * of their whole history.
     */
    @Transactional(readOnly = true)
    public List<DealResponse> listEligibleForDispute(AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        List<Collaboration> collaborations =
                collaborationRepository.findByCreatorIdAndStatusNotIn(
                        profile.getUserId(), DISPUTE_INELIGIBLE_STATUSES);
        Map<String, List<Deliverable>> deliverablesByCollaboration =
                loadDeliverablesByCollaboration(collaborations);
        return collaborations.stream()
                .map(
                        c ->
                                toDealResponse(
                                        c,
                                        principal,
                                        UserType.CREATOR,
                                        deliverablesByCollaboration.getOrDefault(
                                                c.getId(), List.of())))
                .filter(DealResponse::escrowFunded)
                .toList();
    }

    /**
     * CR-96 — batch source for {@link #list} and {@link #listEligibleForDispute}'s deliverable
     * aggregation, mirroring {@code AdminCampaignService}'s H-22 pattern: one {@link
     * DeliverableRepository#findByCollaborationIdIn} for the whole page of collaborations instead
     * of {@link #toDealResponse} issuing a per-row query when called in a loop.
     */
    private Map<String, List<Deliverable>> loadDeliverablesByCollaboration(
            List<Collaboration> collaborations) {
        if (collaborations.isEmpty()) {
            return Map.of();
        }
        List<String> collaborationIds = collaborations.stream().map(Collaboration::getId).toList();
        return deliverableRepository.findByCollaborationIdIn(collaborationIds).stream()
                .collect(Collectors.groupingBy(Deliverable::getCollaborationId));
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

    /**
     * CR-37 (Kabir audit finding #5) — the brand-side scope id for a deal mutation, AND the
     * member-role gate that {@code requireBrandWorkspace} on its own was missing.
     *
     * <p>{@link #accept}, {@link #counter} and {@link #reject} all resolved the caller's brand
     * workspace but never checked their MEMBER ROLE — so a workspace {@code VIEWER} (or {@code
     * MEMBER}) could accept an offer, send a counter, or cancel a deal, while every actual money
     * movement ({@code EscrowService#initiateFund}/{@code release}/{@code refund}) requires
     * {@code OWNER}/{@code ADMIN}. That is a privilege inversion: the read-only role could steer a
     * negotiation it could not fund.
     *
     * <p>The tier is {@code OWNER}/{@code ADMIN}/{@code MANAGER} — deliberately the *management*
     * tier, not the treasury one. Managing a negotiation is the same class of action as generating
     * a contract ({@code ContractService#generate}) or managing a campaign
     * ({@code CampaignService}), which already use exactly this set; a MANAGER who can generate the
     * contract can obviously accept the terms that precede it. It excludes {@code VIEWER} and
     * {@code MEMBER}. Fixed for all three sibling methods at once — closing only {@code reject}
     * would leave {@code accept}/{@code counter} open to the identical bypass.
     */
    private String requireBrandDealManagerScope(AuthPrincipal principal) {
        return requireBrandDealManagerWorkspace(principal).getId();
    }

    /**
     * Same gate as {@link #requireBrandDealManagerScope}, returning the resolved {@link Workspace}
     * rather than just its id — {@link #createProposal} needs the entity itself (for {@code
     * ProposalSentEvent}'s workspace name), so without this it would have had to re-resolve the
     * workspace or skip the gate.
     *
     * <p>[CR-37 followup] Deliberately ONE definition of the gate with two return shapes, rather
     * than a second copy of the role set. The tier is asserted in exactly one place, so narrowing
     * or widening it cannot drift between the four call sites — which is the failure mode that put
     * {@code createProposal} outside the original fix in the first place.
     */
    private Workspace requireBrandDealManagerWorkspace(AuthPrincipal principal) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        WorkspaceMember member = brandContext.requireMember(principal, workspace.getId());
        brandContext.requireRole(
                member, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.MANAGER);
        return workspace;
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

    /**
     * [BUG FIX 2026-08-13, Arjun-routed] Amount validation for {@link #counter}, deliberately
     * narrower than {@link #validateProposalAmount} — which {@link #counter} used to call
     * verbatim, sharing the {@code AMOUNT_EXCEEDS_BUDGET} ceiling with a brand's very first
     * offer.
     *
     * <p><b>Why sharing it was a bug, not just strict:</b> {@code campaign.budgetMax} exists to
     * catch a brand's wildly-out-of-range FIRST offer ({@link #createProposal}) — it is not a
     * ceiling on where a live negotiation between two already-engaged parties may land. The
     * standing price on a deal is, by construction, already {@code <= budgetMax} (it passed this
     * same check to get there), so ANY counter that raised the price at all risked landing above
     * the cap whenever the standing offer was already near it — structurally squeezing out the
     * one thing a counter exists to do. No {@code DealServiceTest} case ever exercised a value
     * near/above {@code budgetMax}, and the frontend counter form ({@code
     * counter-proposal-form.tsx}) gives no pre-submit warning, so the bug shipped silent.
     *
     * <p><b>What stays enforced:</b> the positive-amount check (a counter collapsing to $0/
     * negative is nonsensical regardless of role) and the {@code budgetMin} floor — that
     * protection is unrelated to this defect and is left exactly as strict as before. Only the
     * upper ceiling is lifted, and deliberately by removing the check rather than widening it by
     * some percentage: there is no principled number to hardcode, and see below for why an
     * unbounded ceiling is still safe here.
     *
     * <p><b>Symmetric across both roles that call {@link #counter}</b> (brand and creator share
     * the one endpoint, disambiguated only by {@code senderType} inside {@link #doCounter}) —
     * deliberately NOT creator-only. Once a deal is {@code IN_NEGOTIATION}, the ceiling is
     * equally pointless against a BRAND counter: e.g. a creator counters above {@code budgetMax}
     * and the brand wants to meet in the middle at a figure still above {@code budgetMax} but
     * below the creator's ask — that is a legitimate compromise move, not a fat-fingered opening
     * offer, and gating only the creator's half of the negotiation would just as structurally
     * strangle compromise from the brand's side.
     *
     * <p><b>Why lifting the ceiling is safe (the money-path question — this paragraph took five
     * fresh-context Priya review rounds before every claim in it held true; the change was never
     * committed between rounds, so there is no git history of the four narrower-each-time false
     * claims it replaced. Do not re-add a "safety" sentence to this javadoc without re-verifying it
     * against the code, not against how convincing it sounds):</b>
     *
     * <ul>
     * <li>A counter cannot inflate any escrow amount by itself. Both fund amounts are always
     *     server-derived from persisted rows — {@code milestone.getAmount()} or {@code
     *     campaign.getBudgetMax()} ({@link EscrowService#deriveFundAmount}) — never from a
     *     proposal/counter request payload.
     * <li>{@link #doCounter} writes {@code collaboration.agreedRate} from this amount immediately,
     *     at counter time, not only after {@link #accept}. {@code ContractService#generate} does
     *     not require {@code TERMS_AGREED} (it only blocks {@code CANCELLED}), and its milestone
     *     total is capped at {@code agreedRate} with no {@code budgetMax} check — so on the
     *     MILESTONE path, an inflated {@code agreedRate} can reach a hold, but only after both
     *     {@code contract.getBrandSignedAt()} and {@code contract.getCreatorSignedAt()} are
     *     non-null ({@code EscrowService}'s {@code milestoneId != null} funding branch).
     * <li>On the NO-MILESTONE ("pool") path the fund amount is {@code campaign.getBudgetMax()}
     *     itself, counter-independent, so an inflated counter cannot inflate it. This path is
     *     ungated by contract signatures in BOTH directions (funding and release) — that is a
     *     pre-existing property of the campaign-level pool model, not something this change
     *     touches or widens. The one real consequence of lifting the ceiling: a pool hold can now
     *     legitimately UNDER-cover an above-{@code budgetMax} {@code agreedRate}. That is a
     *     funding-coverage gap worth its own ticket, not a way to move money without consent.
     * <li>No release can be redirected: every release is initiated by the brand side or by admin
     *     dispute settlement, and the payee is always resolved server-side from {@code
     *     Collaboration.creatorId}, never from the request. (This says nothing about {@code
     *     EscrowService#refund}, which the brand can invoke unilaterally on either route — that is
     *     pre-existing behavior, unrelated to and unwidened by this change.)
     * </ul>
     */
    private void validateCounterAmount(Campaign campaign, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(
                    "INVALID_AMOUNT", "Amount must be positive", HttpStatus.BAD_REQUEST);
        }
        if (campaign.getBudgetMin() != null && amount.compareTo(campaign.getBudgetMin()) < 0) {
            throw new ApiException(
                    "AMOUNT_BELOW_BUDGET",
                    "Proposed amount is below campaign minimum budget",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * CR-08 — returns the persisted proposal card (it used to return {@code void}) so {@link
     * #doCounter} can publish the new offer over SSE. {@link #createProposal} ignores the return:
     * a brand-side proposal is the first event on a brand-new Collaboration, so there is no open
     * deal room subscribed to it yet.
     */
    private DealMessage persistProposalMessage(
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
        // Snapshot the usage rights as they stand for THIS offer. The SPA renders the card from
        // message metadata (creator-chat.tsx), and until now this key was never written, so every
        // proposal card read "Usage Rights: Not specified" no matter what the brand submitted —
        // the value was only ever on the Collaboration, never on the message.
        //
        // Read from the collaboration rather than taking a parameter: both call sites
        // (createProposal, doCounter) apply their usage-rights term to the entity BEFORE calling
        // this, so the field already holds the value as of this offer.
        //
        // Snapshot, not a live lookup, because usage rights are renegotiable per counter — the
        // card is a historical record of what was offered at the time. Rendering the deal's
        // current value instead would retroactively rewrite what older cards claim was on the
        // table, which is exactly the kind of evidence-trail rewriting settleStatus() exists to
        // prevent. Existing rows are immutable history and are deliberately NOT backfilled, so
        // the key is absent on pre-2026-07-27 messages and the renderer keeps its `?? 'Not
        // specified'` fallback.
        String usageRights = collaboration.getUsageRights();
        if (usageRights != null && !usageRights.isBlank()) {
            metadata.put("usageRights", usageRights);
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
        return proposal;
    }

    /**
     * CR-02 — settles the most recent proposal card's {@code metadata.status} once the offer it
     * represents is no longer on the table ({@code "accepted"} / {@code "rejected"} /
     * {@code "countered"}).
     *
     * <p>{@link #persistProposalMessage} stamps {@code "pending"} at creation and, until this
     * existed, nothing ever rewrote it — so every proposal card stayed "pending" forever. The SPA
     * gates its Accept/Counter/Decline buttons on {@code metadata.status === 'pending'}
     * (creator-chat.tsx), so it kept offering those actions on an already-agreed deal and the
     * second click came back 409. The 409 itself is CORRECT and stays: {@code
     * Collaboration.canAccept()} is the authority on what may be accepted. The stale card is the
     * bug, not the guard.
     *
     * <p>[C2] This participates in the caller's transaction — it is NOT best-effort, and must not
     * be commented as though it were. An earlier revision wrapped this in try/catch claiming a
     * failure here could not roll back an already-successful accept. That was simply false:
     * {@code proposal} is a managed entity, so Hibernate dirty-checking issues the UPDATE at
     * commit-time flush no matter what this method catches, and a flush failure throws at commit
     * OUTSIDE any try here; a {@code DataAccessException} from the save would meanwhile have
     * already marked the transaction rollback-only, turning a swallowed error into
     * {@code UnexpectedRollbackException}. Either way the caller gets a 500 with the deal unmoved
     * while the log insists the transition succeeded — the log lying during the incident it exists
     * to explain. Atomic is also the behaviour we actually want: on a payment evidence trail, a
     * card whose badge disagrees with the deal's real state is the CR-02 bug returning as a silent
     * failure mode. If this cannot be written, the accept should fail and be retried.
     *
     * <p>The read-modify-write and the pending-only rule both live in {@link
     * DealMessage#settleStatus} so no caller can rewrite {@code amount} or re-stamp an already
     * settled card — see the ruling documented there.
     *
     * <p>CR-08 — returns the settled card (empty only when the deal has no proposal card at all) so
     * the caller can republish it over SSE. The returned instance is the SAME managed entity that
     * was just settled, so {@code getId()} is its ORIGINAL persisted id and {@code
     * getMetadataJson()} is already post-settle. Publishing it is an UPDATE to a row the client
     * already has, not a new message — see {@link #publishToStream}'s callers.
     */
    private Optional<DealMessage> settleLatestProposal(String collaborationId, String status) {
        // Same lookup doAccept already ran for its "can't accept your own offer" guard; within one
        // transaction the persistence context returns that same managed instance, so this is not a
        // second round trip in the accept path and reject/counter get the lookup they lack.
        Optional<DealMessage> latest =
                dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        collaborationId, DealMessageKind.proposal);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        DealMessage proposal = latest.get();
        proposal.settleStatus(status);
        dealMessageRepository.save(proposal);
        return latest;
    }

    /**
     * CR-08 — returns the persisted row (it used to return {@code void}) so the lifecycle paths can
     * hand it to {@link #publishToStream}. The returned instance carries the id and {@code
     * createdAt} that were actually written, which is what a subscriber needs to place the row.
     */
    private DealMessage appendSystemMessage(String collaborationId, String content) {
        DealMessage message =
                DealMessage.create(
                        Ulids.newUlid(),
                        collaborationId,
                        DealMessageKind.system,
                        "system",
                        DealSenderType.system,
                        TextSanitizer.sanitizePlainText(content),
                        null);
        dealMessageRepository.save(message);
        return message;
    }

    /**
     * Single-collaboration convenience overload for the non-list call sites ({@code get},
     * {@code accept}, {@code reject}, {@code counter}, {@code createProposal}, {@code
     * sendMessage}) — one deliverable query for one row is not the N+1 the batched overload below
     * exists to avoid.
     */
    private DealResponse toDealResponse(
            Collaboration collaboration, AuthPrincipal principal, UserType role) {
        List<Deliverable> deliverables =
                deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(collaboration.getId());
        return toDealResponse(collaboration, principal, role, deliverables);
    }

    /**
     * CR-96 — batched-friendly overload: callers iterating a list of collaborations ({@link #list},
     * {@link #listEligibleForDispute}) pass in deliverables pre-fetched via {@link
     * #loadDeliverablesByCollaboration} instead of triggering a per-row query here.
     */
    private DealResponse toDealResponse(
            Collaboration collaboration,
            AuthPrincipal principal,
            UserType role,
            List<Deliverable> deliverables) {
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

        // [CR-49, renamed CR-50] Milestone-aware existence check, not the direct collaboration_id-
        // column one -- see ContractService#promptEscrowFundingIfNeeded and EscrowHoldRepository's
        // javadoc (CR-35/CR-39): the direct column is NULL on every ordinary brand-funded hold, so
        // the derived query would silently report this read-only "escrow funded" flag as false on a
        // genuinely funded deal. CR-50 consolidated the repository to this one method.
        boolean escrowFunded =
                escrowHoldRepository.hasEscrowForCollaboration(
                        collaboration.getId(), Set.of(EscrowStatus.FUNDED));

        // PL-1 (BrandF.md §69) / creatorF.md C-4: deliverablesDone/Total/nextDeadline used to be
        // hardcoded 0/0/null here regardless of real deliverable state, which is why the brand
        // pipeline board's SLA "at-risk" filter was structurally always empty in live mode — there
        // was never a deadline for it to compare against. Computed for real below from the
        // `deliverables` param (single-row query for single-item callers, batched via {@link
        // #loadDeliverablesByCollaboration} for list callers — see the two overloads above).
        int deliverablesTotal = deliverables.size();
        int deliverablesDone =
                (int)
                        deliverables.stream()
                                .filter(d -> DELIVERABLE_DONE_STATUSES.contains(d.getStatus()))
                                .count();
        Instant nextDeadline =
                deliverables.stream()
                        .filter(d -> !DELIVERABLE_DONE_STATUSES.contains(d.getStatus()))
                        .filter(d -> d.getStatus() != DeliverableStatus.REJECTED)
                        .map(Deliverable::getDeadline)
                        .filter(java.util.Objects::nonNull)
                        .min(java.util.Comparator.naturalOrder())
                        .map(d -> d.atStartOfDay(ZoneOffset.UTC).toInstant())
                        .orElse(null);

        return new DealResponse(
                collaboration.getId(),
                collaboration.getCampaignId(),
                campaignName,
                counterparty.id(),
                counterparty.profileId(),
                counterparty.name(),
                counterparty.avatar(),
                counterparty.handle(),
                counterparty.verificationStatus(),
                collaboration.getStatus(),
                collaboration.getAgreedRate(),
                collaboration.getCurrency(),
                lastMsg.map(DealMessage::getContent).orElse(collaboration.getNotes()),
                lastMsg.map(DealMessage::getCreatedAt).orElse(collaboration.getUpdatedAt()),
                unread,
                deliverablesDone,
                deliverablesTotal,
                nextDeadline,
                latest != null ? latest.getId() : null,
                latest != null ? latest.getStatus() : null,
                escrowFunded);
    }

    /** Brand-approved-or-beyond — see {@link #toDealResponse} deliverable aggregation. */
    private static final Set<DeliverableStatus> DELIVERABLE_DONE_STATUSES =
            Set.of(
                    DeliverableStatus.APPROVED,
                    DeliverableStatus.POSTED,
                    DeliverableStatus.METRICS_REPORTED,
                    DeliverableStatus.VERIFIED);

    private Counterparty resolveCounterparty(
            Collaboration collaboration, Campaign campaign, UserType viewerRole) {
        if (viewerRole == UserType.CREATOR) {
            String workspaceId = campaign != null ? campaign.getWorkspaceId() : null;
            if (workspaceId == null) {
                return new Counterparty("unknown", null, "Brand", null, null, null);
            }
            Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
            return new Counterparty(
                    workspaceId,
                    null,
                    workspace != null ? workspace.getName() : "Brand",
                    workspace != null ? workspace.getLogoUrl() : null,
                    null,
                    workspace != null && workspace.getVerificationStatus() != null
                            ? workspace.getVerificationStatus().name()
                            : null);
        }
        CreatorProfile creator =
                creatorProfileRepository.findByUserId(collaboration.getCreatorId()).orElse(null);
        return new Counterparty(
                collaboration.getCreatorId(),
                creator != null ? creator.getId() : null,
                creator != null ? creator.getDisplayName() : "Creator",
                creator != null ? creator.getAvatarUrl() : null,
                creator != null ? creator.getUsername() : null,
                null);
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

    /**
     * CR-13 — resolves a {@code ?status=} filter to the collaboration states it selects.
     *
     * <p><b>Accepts a comma-separated union</b> ({@code ?status=contracted,in_progress,review}),
     * which is what the creator "Active" chip sends. Before this, that chip sent the single value
     * {@code in_progress}, which mapped to {@code [IN_PROGRESS]} only — so a signed, CONTRACTED
     * deal was invisible on the one tab a creator would look for it on, and the tab rendered
     * "Nothing active." while an active deal existed. Union rather than overloading
     * {@code in_progress} to mean three stages: {@code in_progress} keeps meaning exactly
     * IN_PROGRESS, so no other caller is surprised by a name that quietly changed meaning.
     *
     * <p><b>TERMS_AGREED moved from {@code contracted} to {@code negotiating}.</b> Per Priya's
     * ruling on CR-13, the FILTER path is the side that moves. Every DISPLAY mapper already agreed
     * TERMS_AGREED is pre-contract — {@code DashboardService.bucketFor}, {@code
     * AdminCampaignService}, {@code CreatorApplicationMapper}, and the frontend's single
     * {@code mapCollaborationStatusToDealStage} — and this function was one of only two sites
     * putting it on the far side of the line. It is structurally pre-contract: {@code doAccept}
     * transitions to TERMS_AGREED and no contract row exists at that point. Consequence fixed: a
     * TERMS_AGREED deal was badged "Negotiating" yet was NOT returned by the "Negotiating" chip,
     * leaving it reachable only under "All".
     *
     * <p><b>APPLIED added to the creator's {@code negotiating} set</b> — the same defect, one row
     * over, found while fixing the above. The display mappers put APPLIED in "negotiating", but no
     * creator-role filter selected it at all: creator {@code new} is {@code [INVITED]} and
     * {@code negotiating} did not list it, so a creator's own application was unreachable from
     * every chip except "All". Left in {@code new} for the BRAND role, where an incoming
     * application genuinely is new work. Strictly speaking this is beyond CR-13's literal text; it
     * is the identical filter-vs-display divergence in the same switch and was not left behind.
     *
     * <p><b>Still selected by no filter: CANCELLED and DISPUTED.</b> That is CR-26, which needs a
     * display bucket, a chip, and empty-state work on both pages — deliberately not folded in here.
     *
     * @return {@code null} to mean "no filtering" (the {@code all} case), never an empty list.
     */
    private static List<CollaborationStatus> statusesForFilter(String filter, UserType role) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        LinkedHashSet<CollaborationStatus> union = new LinkedHashSet<>();
        for (String token : filter.split(",")) {
            String key = token.trim().toLowerCase();
            if (key.isEmpty()) {
                continue;
            }
            // "all" anywhere in the union widens to everything — filtering on
            // "all,negotiating" cannot sensibly mean less than "all".
            if ("all".equals(key)) {
                return null;
            }
            union.addAll(statusesForSingleFilter(key, filter, role));
        }
        // Only reachable for input that is entirely separators (","/", ,"). Treated as an
        // explicit bad request rather than silently degrading to "all", which would show a
        // creator every deal they own in response to a malformed query.
        if (union.isEmpty()) {
            throw new ApiException(
                    "INVALID_STATUS_FILTER",
                    "Unknown status filter: " + filter,
                    HttpStatus.BAD_REQUEST);
        }
        return List.copyOf(union);
    }

    /**
     * One token of {@link #statusesForFilter}. {@code rawFilter} is echoed in the error so a bad
     * token inside a union reports the whole filter the caller actually sent.
     */
    private static List<CollaborationStatus> statusesForSingleFilter(
            String key, String rawFilter, UserType role) {
        return switch (key) {
            case "new" ->
                    role == UserType.CREATOR
                            ? List.of(CollaborationStatus.INVITED)
                            : List.of(CollaborationStatus.APPLIED, CollaborationStatus.INVITED);
            case "negotiating" ->
                    role == UserType.CREATOR
                            ? List.of(
                                    CollaborationStatus.APPLIED,
                                    CollaborationStatus.SHORTLISTED,
                                    CollaborationStatus.IN_NEGOTIATION,
                                    CollaborationStatus.TERMS_AGREED)
                            : List.of(
                                    CollaborationStatus.SHORTLISTED,
                                    CollaborationStatus.IN_NEGOTIATION,
                                    CollaborationStatus.TERMS_AGREED);
            case "contracted" ->
                    List.of(CollaborationStatus.CONTRACT_PENDING, CollaborationStatus.CONTRACTED);
            case "in_progress" -> List.of(CollaborationStatus.IN_PROGRESS);
            case "review" ->
                    List.of(
                            CollaborationStatus.REVIEW_PENDING,
                            CollaborationStatus.REVISION_REQUESTED);
            case "completed" -> List.of(CollaborationStatus.COMPLETED);
            // CR-26 — before this, CANCELLED and DISPUTED were selected by NO filter at all, so
            // the only way to reach a disputed deal was the unfiltered "All" list, where it was
            // additionally mislabelled "Done". Gives the new client-side "Disputed" chip a real
            // server-backed filter instead of a client-only invention.
            case "disputed" ->
                    List.of(CollaborationStatus.CANCELLED, CollaborationStatus.DISPUTED);
            default ->
                    throw new ApiException(
                            "INVALID_STATUS_FILTER",
                            "Unknown status filter: " + rawFilter,
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

    private record Counterparty(
            String id,
            String profileId,
            String name,
            String avatar,
            String handle,
            String verificationStatus) {}
}
