package com.influora.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DeliverableType;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MemberRole;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.notification.event.ContractPendingSignatureEvent;
import com.influora.service.notification.event.ContractReadyForEscrowEvent;
import com.influora.service.notification.event.ContractSignedEvent;
import com.influora.web.dto.money.MoneyDtos.ContractGenerateRequest;
import com.influora.web.dto.money.MoneyDtos.ContractPdfDownloadResponse;
import com.influora.web.dto.money.MoneyDtos.ContractResponse;
import com.influora.web.dto.money.MoneyDtos.MilestoneDto;
import com.influora.web.dto.money.MoneyDtos.MilestoneWriteRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract + milestone generation, signature recording, and state transitions.
 *
 * <p>{@code generate} computes {@code total_amount} as the sum of the supplied milestone
 * amounts server-side (never trusts a client-supplied contract total) and stamps a SHA-256
 * tamper-hash reference alongside the persisted terms so downstream signature verification has
 * something immutable to check against.
 */
@Service
public class ContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Storage path prefix for generated contract PDFs. */
    private static final String CONTRACT_PDF_KEY_PREFIX = "contracts/";

    private final ContractRepository contractRepository;
    private final PaymentMilestoneRepository milestoneRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final CollaborationRepository collaborationRepository;
    private final CampaignRepository campaignRepository;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final BrandContextService brandContext;
    private final CreatorContextService creatorContext;
    private final ContractPdfService contractPdfService;
    private final R2StorageService r2StorageService;
    private final ApplicationEventPublisher eventPublisher;
    private final IdempotencyService idempotencyService;
    // B2 — deliverable-row materialization at contract-generation time.
    private final DeliverableRepository deliverableRepository;
    private final DealMessageRepository dealMessageRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final CollaborationLifecycleService collaborationLifecycleService;

    public ContractService(
            ContractRepository contractRepository,
            PaymentMilestoneRepository milestoneRepository,
            EscrowHoldRepository escrowHoldRepository,
            CollaborationRepository collaborationRepository,
            CampaignRepository campaignRepository,
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            BrandContextService brandContext,
            CreatorContextService creatorContext,
            ContractPdfService contractPdfService,
            R2StorageService r2StorageService,
            ApplicationEventPublisher eventPublisher,
            IdempotencyService idempotencyService,
            DeliverableRepository deliverableRepository,
            DealMessageRepository dealMessageRepository,
            CreatorProfileRepository creatorProfileRepository,
            CollaborationLifecycleService collaborationLifecycleService) {
        this.contractRepository = contractRepository;
        this.milestoneRepository = milestoneRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.collaborationRepository = collaborationRepository;
        this.campaignRepository = campaignRepository;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.brandContext = brandContext;
        this.creatorContext = creatorContext;
        this.contractPdfService = contractPdfService;
        this.r2StorageService = r2StorageService;
        this.eventPublisher = eventPublisher;
        this.idempotencyService = idempotencyService;
        this.deliverableRepository = deliverableRepository;
        this.dealMessageRepository = dealMessageRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.collaborationLifecycleService = collaborationLifecycleService;
    }

    @Transactional
    public ContractResponse generate(AuthPrincipal principal, String workspaceId, ContractGenerateRequest req) {
        WorkspaceMember member = brandContext.requireMember(principal, workspaceId);
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.MANAGER);

        Collaboration collaboration =
                collaborationRepository
                        .findById(req.collaborationId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "COLLABORATION_NOT_FOUND",
                                                "Collaboration not found",
                                                HttpStatus.NOT_FOUND));

        // [SEC: Kabir, Wave E1 escalation — fixed] Workspace-ownership check: the collaboration's
        // OWN campaign must belong to the calling workspace. `Collaboration` has no workspace_id
        // column of its own, so `req.collaborationId()` alone proves nothing about ownership — a
        // brand authenticated into a totally unrelated workspace could otherwise pass ANY other
        // brand's collaborationId and have a Contract + PaymentMilestones built against that
        // stranger's real creator relationship. Mirrors CampaignLinkService#createTrackingLink's
        // resolve-then-scope pattern exactly (findByIdAndWorkspaceId as the authorization check
        // itself, not a defense-in-depth afterthought). Deliberately reuses COLLABORATION_NOT_FOUND
        // rather than a distinct "forbidden"/"mismatch" code, so an unauthorized caller cannot use
        // the response to distinguish "this collaboration doesn't exist" from "it exists but isn't
        // yours" (same enumeration-oracle discipline as PayoutService#validateForPayout).
        campaignRepository
                .findByIdAndWorkspaceId(collaboration.getCampaignId(), workspaceId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "COLLABORATION_NOT_FOUND",
                                        "Collaboration not found",
                                        HttpStatus.NOT_FOUND));

        // [SEC: Kabir MEDIUM-1, contract-flow-architecture-2026-07-23 review — race close]
        // Acquire a DB row lock on the collaboration BEFORE the exists-check below. Without this,
        // `existsByCollaborationIdAndStatusNot` (a plain SELECT) then `contractRepository.save`
        // (an INSERT) had no DB-level guarantee: two concurrent POST /contracts for the SAME
        // collaboration could each pass the exists-check before either transaction commits
        // (ordinary non-locking reads under REPEATABLE READ), then both INSERT — two non-CANCELLED
        // contracts for one collaboration, exactly the state this guard exists to prevent. This
        // PESSIMISTIC_WRITE lock is held for the rest of this @Transactional method and released
        // at commit; a second concurrent call blocks on `findByIdForUpdate` until the first
        // commits, so its own exists-check then correctly observes the just-inserted row and
        // throws CONTRACT_ALREADY_EXISTS below. Deliberately acquired AFTER the workspace-ownership
        // check above (an unauthorized caller should fail fast without taking a row lock) but
        // BEFORE the exists-check + insert it is meant to serialize.
        // Re-fetched under the lock rather than reassigning `collaboration` itself — the latter is
        // captured by the milestone-building lambda further down and reassigning it would make it
        // non-effectively-final. Within this persistence context the two reads resolve to the SAME
        // managed entity instance (JPA first-level cache, same @Id), so this is not a second,
        // divergent copy — just a name that makes "read under the lock" explicit at the call site.
        Collaboration lockedCollaboration =
                collaborationRepository
                        .findByIdForUpdate(collaboration.getId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "COLLABORATION_NOT_FOUND",
                                                "Collaboration not found",
                                                HttpStatus.NOT_FOUND));

        // [CR-22a, Kabir finding #1] Nothing downstream of DealService#reject ever checked
        // CollaborationStatus, so a CANCELLED deal's contract could still be generated (and later
        // fully signed to ACTIVE — see the equivalent guard in #doRecordSignature). Checked here,
        // under the row lock just acquired above, so a reject() racing this exact call is
        // serialized against it rather than raced: whichever of {reject, generate} commits first
        // is what the other observes.
        if (lockedCollaboration.getStatus() == CollaborationStatus.CANCELLED) {
            throw new ApiException(
                    "COLLABORATION_CANCELLED",
                    "This deal was cancelled and can no longer be contracted",
                    HttpStatus.CONFLICT);
        }

        // [BE-1: Vikram, contract-flow-architecture-2026-07-23 §6.4 — immutability/duplicate guard]
        // Previously `generate` had no awareness of any contract that might already exist for this
        // collaboration: a second (or third...) POST /contracts call would happily create another
        // Contract + PaymentMilestone set, silently coexisting with an already-signed/ACTIVE
        // contract. Every new Contract also defaults to version=1 (`Contract.Builder#build`), so
        // `ContractRepository#findByCollaborationIdOrderByVersionDesc` (the lookup DealService uses
        // to decide "the" contract for a collaboration) had no reliable secondary sort among ties —
        // which of the two rows displayed as "the" contract was effectively query-plan-dependent.
        // A collaboration may have at most one non-CANCELLED contract at a time; a CANCELLED
        // contract (future re-negotiation support) does not block a fresh one. The row lock above
        // is what makes this check race-safe under concurrency, not just correct sequentially.
        if (contractRepository.existsByCollaborationIdAndStatusNot(
                collaboration.getId(), ContractStatus.CANCELLED)) {
            throw new ApiException(
                    "CONTRACT_ALREADY_EXISTS",
                    "A contract already exists for this collaboration",
                    HttpStatus.CONFLICT);
        }

        List<MilestoneWriteRequest> milestoneReqs = req.milestones();
        if (milestoneReqs == null || milestoneReqs.isEmpty()) {
            throw new ApiException(
                    "MILESTONES_REQUIRED", "At least one milestone is required", HttpStatus.BAD_REQUEST);
        }

        // [SEC: Kabir MEDIUM-2, contract-flow-architecture-2026-07-23 review] Previously only the
        // SUMMED total was checked (`totalAmount.signum() <= 0` below), so a negative milestone
        // (e.g. [500, -200], net 300) was silently accepted. `MilestoneWriteRequest.amount` now
        // carries `@DecimalMin("0.01")` at the request-validation boundary (`ContractController`'s
        // `@Valid`), but this service method is re-checked defensively here too — the same
        // belt-and-suspenders discipline as `EscrowService#adminSplitForDispute`'s own boundary
        // re-check — so a caller that ever bypasses bean validation still cannot slip a
        // zero/negative milestone through.
        for (MilestoneWriteRequest m : milestoneReqs) {
            if (m.amount() == null || m.amount().signum() <= 0) {
                throw new ApiException(
                        "INVALID_MILESTONE_AMOUNT",
                        "Each milestone amount must be positive",
                        HttpStatus.BAD_REQUEST);
            }
        }

        BigDecimal totalAmount =
                milestoneReqs.stream()
                        .map(MilestoneWriteRequest::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalAmount.signum() <= 0) {
            throw new ApiException(
                    "INVALID_CONTRACT_TOTAL", "Contract total must be positive", HttpStatus.BAD_REQUEST);
        }

        // [SEC: Kabir MEDIUM-2] Bind the contract total to the negotiated deal value so a brand
        // cannot set an arbitrary contract amount unrelated to what was actually agreed in
        // negotiation. Only enforced when the collaboration actually HAS an agreed rate — older/
        // edge-case collaborations negotiated without one (agreedRate == null) have nothing to
        // bind against, and rejecting those outright would be a functional regression, not a
        // security fix. "Does not exceed" (not "must equal exactly") so splitting the agreed rate
        // into fewer/rounder installments that sum to less than the full rate is still allowed.
        if (collaboration.getAgreedRate() != null
                && totalAmount.compareTo(collaboration.getAgreedRate()) > 0) {
            throw new ApiException(
                    "CONTRACT_TOTAL_EXCEEDS_AGREED_RATE",
                    "Milestone total exceeds the collaboration's agreed rate",
                    HttpStatus.BAD_REQUEST);
        }

        Contract contract =
                Contract.builder()
                        .id(Ulids.newUlid())
                        .collaborationId(collaboration.getId())
                        .workspaceId(workspaceId)
                        .totalAmount(totalAmount)
                        .termsJson(sha256TamperHash(req))
                        .build();
        contractRepository.save(contract);

        List<PaymentMilestone> milestones =
                milestoneReqs.stream()
                        .map(
                                m ->
                                        PaymentMilestone.builder()
                                                .id(Ulids.newUlid())
                                                .contractId(contract.getId())
                                                .collaborationId(collaboration.getId())
                                                .sequenceNo(m.sequenceNo())
                                                .description(m.description())
                                                .amount(m.amount())
                                                .dueDate(m.dueDate())
                                                .build())
                        .toList();
        milestoneRepository.saveAll(milestones);

        // B2 — same transaction as the payment milestones above.
        materializeDeliverables(collaboration);

        // W2-1 — a contract now exists; the collaboration leaves negotiation and awaits signature.
        collaborationLifecycleService.onContractGenerated(collaboration.getId());

        // W3-1 — #7 "contract ready for signature" (07-NOTIFICATION-SYSTEM-SPEC.md §3.1): notify
        // the creator a contract is waiting on them. Best-effort, mirrors every other notification
        // publish in this class — a lookup failure here must never fail contract generation, which
        // has already fully succeeded above.
        try {
            promptCreatorToSignIfPossible(collaboration, contract, workspaceId);
        } catch (Exception e) {
            log.error(
                    "Contract-ready-for-signature notification failed for contract {} — contract"
                            + " generation itself already succeeded",
                    contract.getId(),
                    e);
        }

        return toResponse(contract, milestones);
    }

    private void promptCreatorToSignIfPossible(
            Collaboration collaboration, Contract contract, String workspaceId) {
        User creator = userRepository.findById(collaboration.getCreatorId()).orElse(null);
        if (creator == null) {
            return;
        }
        Campaign campaign =
                campaignRepository.findByIdAndWorkspaceId(collaboration.getCampaignId(), workspaceId).orElse(null);
        String campaignTitle = campaign != null ? campaign.getTitle() : "your campaign";
        String brandName = resolveBrandName(workspaceId);
        eventPublisher.publishEvent(
                new ContractPendingSignatureEvent(
                        creator.getId(), workspaceId, contract.getId(), brandName, campaignTitle));
    }

    private String resolveBrandName(String workspaceId) {
        return workspaceRepository.findById(workspaceId).map(Workspace::getName).orElse("A brand");
    }

    /**
     * B2 — no {@link Deliverable} row was ever created anywhere in the collaboration lifecycle
     * (audit finding: {@code CreatorDeliverableService}'s upload/submit flow only ever reads/updates
     * an existing row, which never existed to read). This materializes one row per agreed
     * deliverable slot at contract-generation time (same transaction as the PaymentMilestones
     * above) so a creator has something to upload against once the contract exists.
     *
     * <p>Source of truth for "agreed slots": the {@code deliverables} metadata on the most
     * recent {@code proposal}-kind {@link DealMessage} for this collaboration — the last accepted
     * offer. Best-effort / non-fatal: a collaboration whose last offer has no structured
     * deliverables (e.g. a pre-fix proposal, or one negotiated without the deliverables field)
     * materializes zero rows rather than failing contract generation — the contract/milestones are
     * the load-bearing part of this method.
     *
     * <p>Idempotent — skips entirely if this collaboration already has {@link Deliverable} rows
     * (e.g. a contract regenerated after renegotiation), so re-running {@link #generate} never
     * duplicates slots.
     */
    private void materializeDeliverables(Collaboration collaboration) {
        if (!deliverableRepository
                .findByCollaborationIdOrderBySlotIndexAsc(collaboration.getId())
                .isEmpty()) {
            return;
        }

        List<Map<String, Object>> slots = latestAgreedDeliverableSlots(collaboration.getId());
        if (slots.isEmpty()) {
            return;
        }

        String creatorProfileId =
                creatorProfileRepository
                        .findByUserId(collaboration.getCreatorId())
                        .map(CreatorProfile::getId)
                        .orElse(null);
        if (creatorProfileId == null) {
            log.warn(
                    "Skipping deliverable materialization for collaboration {} — no CreatorProfile"
                            + " found for creator {}",
                    collaboration.getId(),
                    collaboration.getCreatorId());
            return;
        }

        List<Deliverable> rows = new ArrayList<>();
        int slotIndex = 0;
        for (Map<String, Object> slot : slots) {
            int qty = slotQty(slot.get("qty"));
            DeliverableType type = parseDeliverableType((String) slot.get("type"));
            for (int i = 0; i < qty; i++) {
                rows.add(
                        Deliverable.builder()
                                .id(Ulids.newUlid())
                                .collaborationId(collaboration.getId())
                                .creatorProfileId(creatorProfileId)
                                .slotIndex(slotIndex++)
                                .type(type)
                                .title(humanizeType(type) + " #" + (i + 1))
                                .build());
            }
        }
        if (!rows.isEmpty()) {
            deliverableRepository.saveAll(rows);
        }
    }

    /** Reads the {@code deliverables} array persisted by {@code DealService#persistProposalMessage}. */
    private List<Map<String, Object>> latestAgreedDeliverableSlots(String collaborationId) {
        Optional<DealMessage> lastProposal =
                dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        collaborationId, DealMessageKind.proposal);
        if (lastProposal.isEmpty() || lastProposal.get().getMetadataJson() == null) {
            return List.of();
        }
        try {
            Map<String, Object> metadata =
                    MAPPER.readValue(
                            lastProposal.get().getMetadataJson(),
                            new TypeReference<Map<String, Object>>() {});
            Object raw = metadata.get("deliverables");
            if (raw == null) {
                return List.of();
            }
            return MAPPER.convertValue(raw, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn(
                    "Could not parse deliverables metadata for collaboration {}: {}",
                    collaborationId,
                    e.getMessage());
            return List.of();
        }
    }

    private static int slotQty(Object rawQty) {
        if (rawQty instanceof Number n && n.intValue() > 0) {
            return n.intValue();
        }
        return 1;
    }

    private static DeliverableType parseDeliverableType(String raw) {
        if (raw == null || raw.isBlank()) {
            return DeliverableType.INSTAGRAM_REEL;
        }
        try {
            return DeliverableType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DeliverableType.INSTAGRAM_REEL;
        }
    }

    private static String humanizeType(DeliverableType type) {
        return type.name().replace('_', ' ');
    }

    /**
     * Records a signature for {@code role} on {@code contractId}.
     *
     * <p><b>Already-signed guard [E2 audit finding #10, HIGH — fixed]</b> — previously, {@code
     * Contract.recordBrandSignature()}/{@code recordCreatorSignature()} unconditionally overwrote
     * the signed-at timestamp on every call with no guard, so a retried sign request (e.g. the
     * response was lost to a network blip and the client retries the identical, already-succeeded
     * call) would re-enter this method, re-evaluate {@code advanceIfFullySigned()} as still true,
     * and re-fire {@link #generateAndDeliverContractPdf} — re-sending the contract PDF email to
     * both parties. This method now short-circuits to an idempotent no-op (returns the current,
     * already-persisted state, same shape as {@code EscrowService.release}/{@code refund}'s
     * status-guard pattern) whenever the given role has already signed, before touching the
     * entity, the repository, or the PDF/email side effect. A first-time signature for the OTHER
     * role after one party already signed is still recorded normally — only a re-signature by the
     * SAME role on a call that already succeeded is suppressed.
     *
     * <p><b>Role forgery [SEC: Kabir, E2 LOW-4 — fixed, pre-existing, not an E2 regression].</b>
     * Previously {@code role} was trusted verbatim from the request body with no binding to the
     * authenticated principal's actual identity — a brand-authenticated user could pass {@code
     * role=CREATOR} and record the CREATOR's signature on a legal document, a signature-attribution
     * forgery, with no extra permission check beyond ordinary workspace membership.
     *
     * <p><b>Investigated the auth model before fixing</b> — {@link ContractController#sign} (the
     * only caller) runs {@code brandContext.requireBrandWorkspace(principal)} before this method is
     * ever reached, which throws unless {@code principal.getUserType() == UserType.BRAND} (see
     * {@code BrandContextService#requireBrand}). {@code WorkspaceMember}/{@link MemberRole} — the
     * only membership model in this codebase — is exclusively a BRAND-side concept; creators are
     * never {@code WorkspaceMember} rows and have no workspace to authenticate into here. In other
     * words: <b>every principal that can reach this method today is a BRAND principal, full stop —
     * there is no creator-authenticated call path into {@code /contracts/{id}/sign} at all</b>
     * (verified against every route in {@code ContractController}, and against {@code
     * UserType.CREATOR}'s only other usage in this codebase, {@code
     * MetaOAuthController#requireCreator}, which is unrelated).
     *
     * <p><b>Why {@code role=CREATOR} cannot simply be rejected:</b> a first attempt at this fix
     * rejected {@code role=CREATOR} outright, on the reasoning that no principal who could
     * legitimately claim it can ever reach this method. That is true, but {@code
     * ContractServiceTest#testSecondRoleCompletesSignatureAndFiresPdfOnce}/{@code
     * testRetriedSignatureAfterFullyExecutedIsNoOp} prove {@code role=CREATOR} is the ONLY existing
     * mechanism this product has for a contract to ever reach {@link
     * com.influora.domain.enums.ContractStatus#ACTIVE} — the brand relays the creator's (out-of-band,
     * e.g. verbal/email) assent and records it here. Rejecting it outright would not just close a
     * forgery, it would permanently break every contract's path to becoming ACTIVE — a functional
     * regression far larger than the security finding. That is NOT "strictest feasible validation,"
     * it is throwing out a load-bearing feature.
     *
     * <p><b>The fix actually applied</b> — {@code role=BRAND} was never forgeable (a brand principal
     * self-attributing their own signature is definitionally correct) and needs no new check.
     * {@code role=CREATOR} still cannot be bound to a real creator identity with the auth data
     * available at this call site, so the strictest available tightening is applied instead:
     * recording a signature ON BEHALF OF the creator now additionally requires the brand principal
     * hold an elevated {@link MemberRole} ({@code OWNER}/{@code ADMIN}/{@code MANAGER}, the same
     * gate {@link #generate} already uses for creating the contract in the first place) — a
     * {@code VIEWER}/{@code MEMBER} workspace member can no longer unilaterally claim the creator's
     * signature. This does not eliminate the forgery (an OWNER/ADMIN/MANAGER can still misattribute
     * a signature), but it closes the widest version of it (any active member, regardless of
     * permission level, could previously do so) and matches the permission level this codebase
     * already trusts for other contract-binding actions.
     *
     * <p><b>Residual risk, stated plainly:</b> creator sign-off on this contract is still recorded
     * by a BRAND principal on the creator's behalf — this method has never captured the CREATOR's
     * own cryptographic assent, only an elevated brand member's claim that the creator agreed.
     * Closing this fully requires a real creator-authenticated signing flow (a product decision, not
     * a code fix) — flagged here for Arjun/product rather than silently worked around.
     */
    @Transactional
    public ContractResponse recordSignature(
            AuthPrincipal principal, String workspaceId, String contractId, String role) {
        WorkspaceMember member = brandContext.requireMember(principal, workspaceId);
        Contract contract = requireContract(contractId, workspaceId);

        boolean isBrand;
        if ("BRAND".equalsIgnoreCase(role)) {
            isBrand = true;
        } else if ("CREATOR".equalsIgnoreCase(role)) {
            isBrand = false;
            // [SEC: Kabir, E2 LOW-4] Recording a signature ON BEHALF OF the creator is the one
            // residual-forgery-risk path (see method javadoc) -- restrict it to the same elevated
            // membership levels #generate already requires to create the contract at all, rather
            // than letting any active member (including VIEWER/MEMBER) claim it.
            brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.MANAGER);
        } else {
            throw new ApiException(
                    "INVALID_SIGNER_ROLE", "role must be BRAND or CREATOR", HttpStatus.BAD_REQUEST);
        }

        // [SEC: Kabir, E2 LOW-3 — fixed] Two truly-concurrent requests for the SAME role could both
        // read alreadySignedByThisRole == false before either commits, both proceed, and both reach
        // generateAndDeliverContractPdf below (duplicate contract email). Wrapped in the shared
        // IdempotencyService.executeOnce, keyed per-contract-per-role (mirrors PayoutService's
        // "payout:"+milestoneId convention) so the DB's UNIQUE(idempotency_key) — not this method's
        // own read-then-write — arbitrates: exactly one concurrent same-role sign request proceeds,
        // the loser is told to retry and replays the now-updated contract instead of double-signing.
        String signKey = "contract-sign:" + contractId + ":" + (isBrand ? "BRAND" : "CREATOR");
        try {
            return idempotencyService.executeOnce(
                    signKey, workspaceId, "contract.sign", () -> doRecordSignature(contract, isBrand));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            // Lost the race to a concurrent same-role sign of the same contract -- re-read and
            // return the (by now updated) persisted state rather than a bare 500/duplicate email.
            Contract refreshed = requireContract(contractId, workspaceId);
            List<PaymentMilestone> milestones =
                    milestoneRepository.findByContractIdOrderBySequenceNoAsc(refreshed.getId());
            return toResponse(refreshed, milestones);
        }
    }

    /**
     * Creator-authenticated signature — records the CREATOR role only, scoped via {@link
     * #requireContractForCreator}. The request body is ignored (creators cannot self-attribute as
     * BRAND). Idempotency and PDF delivery reuse the same {@link #doRecordSignature} path as the
     * brand relay flow.
     */
    @Transactional
    public ContractResponse recordSignatureForCreator(AuthPrincipal principal, String contractId) {
        creatorContext.requireCreator(principal);
        Contract contract = requireContractForCreator(contractId, principal.getUserId());

        String signKey = "contract-sign:" + contractId + ":CREATOR";
        try {
            return idempotencyService.executeOnce(
                    signKey,
                    contract.getWorkspaceId(),
                    "contract.sign",
                    () -> doRecordSignature(contract, false));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            Contract refreshed = requireContractForCreator(contractId, principal.getUserId());
            List<PaymentMilestone> milestones =
                    milestoneRepository.findByContractIdOrderBySequenceNoAsc(refreshed.getId());
            return toResponse(refreshed, milestones);
        }
    }

    /**
     * Runs inside {@code executeOnce} (see {@link #recordSignature}, E2 LOW-3) — the already-signed
     * short-circuit still runs first as a fast, non-error idempotent-replay path for a genuine
     * sequential retry (the original E2 audit finding #10 scenario); {@code executeOnce} additionally
     * arbitrates the narrower truly-concurrent race that check alone cannot catch.
     *
     * <p><b>[CR-22a, Kabir finding #1 + #6]</b> Previously this method had no {@code
     * CollaborationStatus} awareness at all — a contract could be signed to {@code ACTIVE} on a
     * {@code CANCELLED} collaboration ({@code CollaborationLifecycleService#onContractFullySigned}
     * only froze the STATUS LABEL, it never stopped the signature itself). A genuinely NEW
     * signature (not an idempotent replay of one already recorded) now takes the same {@code
     * PESSIMISTIC_WRITE} lock on the collaboration row that {@link #generate} and {@code
     * DealService#doReject} take, and re-checks {@code CANCELLED} under that lock — so this is
     * serialized against a concurrent {@code reject()} the same way {@link #generate} is: whichever
     * of {reject, sign} commits first is what the other observes, not a stale snapshot. Placed
     * AFTER the already-signed short-circuit below, deliberately — an idempotent replay of an
     * already-fully-executed signature must keep touching nothing beyond the contract itself (see
     * {@code ContractServiceTest#testRetriedSignatureAfterFullyExecutedIsNoOp}'s {@code
     * verifyNoInteractions(..., collaborationRepository)}), and is in any case not a hole: once a
     * signature is genuinely recorded, {@code canReject()} can no longer reach {@code CANCELLED}
     * from here (a contract implies at least {@code CONTRACT_PENDING}, which is past the CR-22a
     * cut line).
     */
    private ContractResponse doRecordSignature(Contract contract, boolean isBrand) {
        boolean alreadySignedByThisRole =
                isBrand ? contract.getBrandSignedAt() != null : contract.getCreatorSignedAt() != null;
        if (alreadySignedByThisRole) {
            // Idempotent no-op — this role already signed (a genuine first call previously
            // succeeded; this is a retry of that same request). Never re-fires PDF
            // generation/email delivery a second time.
            List<PaymentMilestone> existingMilestones =
                    milestoneRepository.findByContractIdOrderBySequenceNoAsc(contract.getId());
            return toResponse(contract, existingMilestones);
        }

        Collaboration collaboration =
                collaborationRepository
                        .findByIdForUpdate(contract.getCollaborationId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "COLLABORATION_NOT_FOUND",
                                                "Collaboration not found",
                                                HttpStatus.NOT_FOUND));
        if (collaboration.getStatus() == CollaborationStatus.CANCELLED) {
            throw new ApiException(
                    "COLLABORATION_CANCELLED",
                    "This deal was cancelled and its contract can no longer be signed",
                    HttpStatus.CONFLICT);
        }

        if (isBrand) {
            contract.recordBrandSignature();
        } else {
            contract.recordCreatorSignature();
        }
        contractRepository.save(contract);

        List<PaymentMilestone> milestones =
                milestoneRepository.findByContractIdOrderBySequenceNoAsc(contract.getId());

        // P0 #2: once BOTH signatures are in (contract fully executed), generate the PDF, store
        // it to R2, and email a secure download link to both parties. Signing is not atomic with
        // this side-effect chain failing the whole request — a PDF/email hiccup must never block
        // the signature itself from being recorded, so failures here are logged, not thrown.
        if (contract.getBrandSignedAt() != null && contract.getCreatorSignedAt() != null) {
            // W2-1 — both signatures are in; the collaboration is now CONTRACTED.
            collaborationLifecycleService.onContractFullySigned(contract.getCollaborationId());
            generateAndDeliverContractPdf(contract, milestones);
            promptEscrowFundingIfNeeded(contract);
        }

        return toResponse(contract, milestones);
    }

    /**
     * When both parties have signed and no FUNDED escrow exists yet for this collaboration, notify
     * the brand owner to fund escrow (actual debit remains brand-initiated via {@code
     * EscrowService#initiateFund} — MF-1 / wallet consent).
     */
    private void promptEscrowFundingIfNeeded(Contract contract) {
        try {
            // [CR-49, renamed CR-50] Use the milestone-aware existence check, not the direct
            // collaboration_id-column one -- the latter is NULL on every hold the ordinary brand
            // escrow flow creates (only ConfirmLaunchExecutor ever calls bindCollaboration; see
            // EscrowHoldRepository's javadoc and CR-39/CR-35), so it would answer "not funded" for
            // exactly the holds that matter and fire a spurious "please fund escrow" notification on
            // an already-funded deal. CR-50 consolidated the repository to this one method.
            if (escrowHoldRepository.hasEscrowForCollaboration(
                    contract.getCollaborationId(), Set.of(EscrowStatus.FUNDED))) {
                return;
            }
            Collaboration collaboration =
                    collaborationRepository
                            .findById(contract.getCollaborationId())
                            .orElse(null);
            Campaign campaign =
                    collaboration != null
                            ? campaignRepository
                                    .findByIdAndWorkspaceId(
                                            collaboration.getCampaignId(), contract.getWorkspaceId())
                                    .orElse(null)
                            : null;
            String campaignTitle = campaign != null ? campaign.getTitle() : "your campaign";
            String brandRecipientUserId = resolveBrandOwnerUserId(contract.getWorkspaceId());
            if (brandRecipientUserId != null) {
                eventPublisher.publishEvent(
                        new ContractReadyForEscrowEvent(
                                brandRecipientUserId,
                                contract.getWorkspaceId(),
                                contract.getId(),
                                campaignTitle));
            }
        } catch (Exception e) {
            log.error(
                    "Escrow funding prompt failed for contract {} — signatures were still recorded",
                    contract.getId(),
                    e);
        }
    }

    /**
     * Best-effort: generates the contract PDF, stores it to R2, sets {@code pdfR2Key}, and emails
     * a secure presigned download link to the brand and creator. Any failure is logged and
     * swallowed — the signature transaction above must not be rolled back by a storage/PDF
     * problem, and the contract remains fully valid (signed) even if the PDF isn't ready yet.
     */
    private void generateAndDeliverContractPdf(Contract contract, List<PaymentMilestone> milestones) {
        try {
            Collaboration collaboration =
                    collaborationRepository
                            .findById(contract.getCollaborationId())
                            .orElseThrow(
                                    () ->
                                            new ApiException(
                                                    "COLLABORATION_NOT_FOUND",
                                                    "Collaboration not found",
                                                    HttpStatus.NOT_FOUND));
            Campaign campaign =
                    campaignRepository
                            .findByIdAndWorkspaceId(collaboration.getCampaignId(), contract.getWorkspaceId())
                            .orElse(null);
            Workspace workspace = workspaceRepository.findById(contract.getWorkspaceId()).orElse(null);
            User creator = userRepository.findById(collaboration.getCreatorId()).orElse(null);

            String brandName = workspace != null ? workspace.getName() : "N/A";
            String creatorName = ContractPdfService.displayNameOf(creator);

            byte[] pdfBytes =
                    contractPdfService.render(contract, campaign, milestones, brandName, creatorName);

            String objectKey = CONTRACT_PDF_KEY_PREFIX + contract.getId() + ".pdf";
            String downloadUrl = null;
            if (r2StorageService.isAvailable()) {
                r2StorageService.putBytes(objectKey, pdfBytes, "application/pdf");
                contract.setPdfR2Key(objectKey);
                contractRepository.save(contract);
                downloadUrl = r2StorageService.presignGet(objectKey).uploadUrl();
            } else {
                log.warn(
                        "R2 not configured — contract PDF for {} generated but not stored", contract.getId());
            }

            String campaignTitle = campaign != null ? campaign.getTitle() : "your campaign";
            String brandRecipientUserId = resolveBrandOwnerUserId(contract.getWorkspaceId());
            String brandRecipientEmail = resolveBrandOwnerEmail(contract.getWorkspaceId());
            String creatorEmail = creator != null ? creator.getEmail() : null;

            if (brandRecipientUserId != null) {
                eventPublisher.publishEvent(
                        new ContractSignedEvent(
                                brandRecipientUserId,
                                contract.getWorkspaceId(),
                                contract.getId(),
                                brandRecipientEmail,
                                creatorName,
                                campaignTitle,
                                downloadUrl));
            }
            if (creator != null) {
                eventPublisher.publishEvent(
                        new ContractSignedEvent(
                                creator.getId(),
                                contract.getWorkspaceId(),
                                contract.getId(),
                                creatorEmail,
                                creatorName,
                                campaignTitle,
                                downloadUrl));
            }
        } catch (Exception e) {
            log.error(
                    "Contract PDF generation/delivery failed for contract {} — signature was still"
                            + " recorded successfully",
                    contract.getId(),
                    e);
        }
    }

    private String resolveBrandOwnerUserId(String workspaceId) {
        return workspaceMemberRepository
                .findFirstByWorkspaceIdAndRoleAndActiveTrue(workspaceId, MemberRole.OWNER)
                .map(WorkspaceMember::getUserId)
                .orElse(null);
    }

    private String resolveBrandOwnerEmail(String workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        if (workspace != null && workspace.getBillingEmail() != null) {
            return workspace.getBillingEmail();
        }
        // Fall back to the owner's own account email if no billing email is configured.
        String ownerUserId = resolveBrandOwnerUserId(workspaceId);
        return ownerUserId != null
                ? userRepository.findById(ownerUserId).map(User::getEmail).orElse(null)
                : null;
    }

    @Transactional(readOnly = true)
    public ContractResponse get(AuthPrincipal principal, String workspaceId, String contractId) {
        brandContext.requireMember(principal, workspaceId);
        Contract contract = requireContract(contractId, workspaceId);
        return toResponseWithMilestones(contract);
    }

    /** Brand-scoped contract list — all contracts belonging to the caller's own workspace. */
    @Transactional(readOnly = true)
    public List<ContractResponse> listForBrand(AuthPrincipal principal, String workspaceId) {
        brandContext.requireMember(principal, workspaceId);
        List<Contract> contracts = contractRepository.findByWorkspaceId(workspaceId);
        return contracts.stream().map(this::toResponseWithMilestones).toList();
    }

    /**
     * Creator-scoped contract read — ownership resolved via {@code
     * ContractRepository#findByIdAndCreatorId} (H-1 fix). Uses {@code CONTRACT_NOT_FOUND} on miss
     * to preserve enumeration-oracle discipline.
     */
    @Transactional(readOnly = true)
    public ContractResponse getForCreator(AuthPrincipal principal, String contractId) {
        creatorContext.requireCreator(principal);
        Contract contract = requireContractForCreator(contractId, principal.getUserId());
        return toResponseWithMilestones(contract);
    }

    /** Creator-scoped contract list — optional {@code dealId} filters to one collaboration. */
    @Transactional(readOnly = true)
    public List<ContractResponse> listForCreator(AuthPrincipal principal, String dealId) {
        creatorContext.requireCreator(principal);
        List<Contract> contracts =
                dealId != null && !dealId.isBlank()
                        ? contractRepository.findByCollaborationIdAndCreatorId(
                                dealId, principal.getUserId())
                        : contractRepository.findByCreatorId(principal.getUserId());
        return contracts.stream().map(this::toResponseWithMilestones).toList();
    }

    /** Contracts awaiting the authenticated creator's signature. */
    @Transactional(readOnly = true)
    public List<ContractResponse> listUnsignedForCreator(AuthPrincipal principal) {
        creatorContext.requireCreator(principal);
        return contractRepository.findUnsignedByCreatorId(principal.getUserId()).stream()
                .map(this::toResponseWithMilestones)
                .toList();
    }

    /**
     * Mints a fresh presigned GET link for the already-generated contract PDF. 404s with
     * {@code CONTRACT_PDF_NOT_READY} if the PDF hasn't been generated yet (e.g. contract isn't
     * fully signed by both parties, or generation failed after signing — see
     * {@link #generateAndDeliverContractPdf}).
     */
    @Transactional(readOnly = true)
    public ContractPdfDownloadResponse getPdfDownloadUrl(
            AuthPrincipal principal, String workspaceId, String contractId) {
        brandContext.requireMember(principal, workspaceId);
        Contract contract = requireContract(contractId, workspaceId);
        return mintPdfDownloadUrl(contract);
    }

    /**
     * Creator-scoped PDF download — must pass the same ownership-scoped lookup as {@link
     * #getForCreator} before minting a presigned URL (H-1 highest-value IDOR target).
     */
    @Transactional(readOnly = true)
    public ContractPdfDownloadResponse getPdfDownloadUrlForCreator(
            AuthPrincipal principal, String contractId) {
        creatorContext.requireCreator(principal);
        Contract contract = requireContractForCreator(contractId, principal.getUserId());
        return mintPdfDownloadUrl(contract);
    }

    private ContractPdfDownloadResponse mintPdfDownloadUrl(Contract contract) {
        if (contract.getPdfR2Key() == null || contract.getPdfR2Key().isBlank()) {
            throw new ApiException(
                    "CONTRACT_PDF_NOT_READY",
                    "Contract PDF has not been generated yet",
                    HttpStatus.NOT_FOUND);
        }
        if (!r2StorageService.isAvailable()) {
            throw new ApiException(
                    "STORAGE_UNAVAILABLE", "File storage is not currently configured", HttpStatus.SERVICE_UNAVAILABLE);
        }
        var presigned = r2StorageService.presignGet(contract.getPdfR2Key());
        return new ContractPdfDownloadResponse(presigned.uploadUrl(), presigned.expiresAt());
    }

    private ContractResponse toResponseWithMilestones(Contract contract) {
        List<PaymentMilestone> milestones =
                milestoneRepository.findByContractIdOrderBySequenceNoAsc(contract.getId());
        return toResponse(contract, milestones);
    }

    private Contract requireContract(String contractId, String workspaceId) {
        return contractRepository
                .findByIdAndWorkspaceId(contractId, workspaceId)
                .orElseThrow(
                        () -> new ApiException("CONTRACT_NOT_FOUND", "Contract not found", HttpStatus.NOT_FOUND));
    }

    private Contract requireContractForCreator(String contractId, String creatorUserId) {
        return contractRepository
                .findByIdAndCreatorId(contractId, creatorUserId)
                .orElseThrow(
                        () -> new ApiException("CONTRACT_NOT_FOUND", "Contract not found", HttpStatus.NOT_FOUND));
    }

    private static String sha256TamperHash(ContractGenerateRequest req) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(req.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "{\"tamperHashSha256\":\"" + hex + "\"}";
        } catch (Exception e) {
            throw new ApiException(
                    "CONTRACT_HASH_FAILED", "Failed to compute contract tamper hash", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static ContractResponse toResponse(Contract contract, List<PaymentMilestone> milestones) {
        List<MilestoneDto> milestoneDtos =
                milestones.stream()
                        .map(
                                m ->
                                        new MilestoneDto(
                                                m.getId(),
                                                m.getContractId(),
                                                m.getCollaborationId(),
                                                m.getSequenceNo(),
                                                m.getDescription(),
                                                m.getAmount(),
                                                m.getCurrency(),
                                                m.getDueDate(),
                                                m.getStatus(),
                                                m.getEscrowHoldId()))
                        .toList();
        return new ContractResponse(
                contract.getId(),
                contract.getCollaborationId(),
                contract.getWorkspaceId(),
                contract.getVersion(),
                contract.getStatus(),
                contract.getTotalAmount(),
                contract.getCurrency(),
                contract.getPdfR2Key(),
                contract.getBrandSignedAt(),
                contract.getCreatorSignedAt(),
                contract.getEffectiveDate(),
                contract.getExpirationDate(),
                milestoneDtos,
                contract.getCreatedAt(),
                contract.getUpdatedAt());
    }
}
