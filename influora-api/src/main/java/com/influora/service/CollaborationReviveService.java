package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.enums.CollaborationSource;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.ShipmentRepository;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F-0225 — the ONE place that decides whether a (campaign, creator) pair may start a new
 * engagement when a prior collaboration row already exists.
 *
 * <h2>Why the decision is centralised</h2>
 *
 * Three call sites create a collaboration for a pair on a human action —
 * {@code CreatorCampaignService#apply}, {@code CreatorDiscoveryService#invite} and
 * {@code DealService#createProposal} — and each carried its own copy of a status-blind
 * {@code existsByCampaignIdAndCreatorId} guard. That is the exact shape of CR-05, CR-13 and
 * CR-24 in this codebase: one precondition, several copies, and the copies drift. A guard that
 * has to reason about attached contracts and escrow is far too dangerous to hold in three
 * places, so it is held here. All three now call this service.
 *
 * <p><b>A fourth call site deliberately does NOT use this, and that is not an oversight.</b>
 * {@code ConfirmLaunchExecutor} (~:466) keeps its own {@code existsByCampaignIdAndCreatorId}
 * check, but it is a different thing wearing the same method name: a SKIP-FILTER over Meera's
 * bulk candidate pool, not a duplicate-409 precondition. It drops any creator who already has a
 * row and moves on to the next candidate. Routing it here would make an AI auto-launch resurrect
 * a deal the brand had withdrawn from, without anyone asking for it — strictly worse than
 * skipping. Changing that needs a product ruling, not a refactor.
 *
 * <h2>The rule</h2>
 *
 * <ul>
 *   <li>No prior row → the caller inserts a fresh one.
 *   <li>Prior row is not {@code CANCELLED} → 409. A live engagement already exists.
 *   <li>Prior row is {@code CANCELLED} and nothing durable hangs off it → revive it in place.
 *       {@code UNIQUE(campaign_id, creator_id)} (V6) leaves no second row to create, so
 *       reviving is the only shape available.
 *   <li>Prior row is {@code CANCELLED} but a contract, escrow hold or shipment is attached →
 *       409. See below; this is the case that must fail closed.
 * </ul>
 *
 * <h2>Why the encumbrance check is not redundant</h2>
 *
 * It is tempting to argue it away: {@code CANCELLED} is written only by
 * {@code DealService#doReject}, which is gated on {@code Collaboration#canReject()}, an
 * allowlist over pre-contract states. If that held universally, a {@code CANCELLED} row could
 * never have a contract behind it. It does not hold, for three separate reasons:
 *
 * <ol>
 *   <li><b>Legacy rows.</b> {@code canReject()}'s own javadoc records that the pre-CR-22a
 *       denylist admitted {@code CONTRACT_PENDING} through {@code REVISION_REQUESTED}, so
 *       {@code reject} "could unilaterally CANCEL a signed, escrow-funded deal, with no escrow
 *       refund, no contract voiding". Rows cancelled under the old rule are in the database
 *       today with live contracts attached. CR-22a fixed the forward direction and left those
 *       rows where they were.
 *   <li><b>Shipments are not gated on collaboration status at all.</b>
 *       {@code ShipmentService} never reads {@code CollaborationStatus}; {@code submitAddress}
 *       lazily creates a {@code Shipment} keyed {@code UNIQUE(collaboration_id)} on
 *       authorisation alone. So even under today's correct allowlist: apply → submit a
 *       shipping address → withdraw → re-apply, and a real address (possibly already SHIPPED)
 *       would ride into the fresh engagement.
 *   <li><b>Reviving is what un-blocks the money guards.</b> {@code EscrowService}'s funding and
 *       release gates are STATUS checks — they refuse while the row reads {@code CANCELLED}.
 *       Setting it back to {@code APPLIED}/{@code INVITED} does not just fail to reconcile the
 *       old artifacts, it re-arms the paths that move money against them. CR-36 exists to stop
 *       money moving on a dead deal; a revive that ignored encumbrance would defeat it as a
 *       side effect.
 * </ol>
 *
 * So the check is on the artifacts themselves, not on the status that is supposed to imply
 * their absence. A 409 the brand or creator can read is the correct outcome — the alternative
 * is a silently re-opened escrow.
 *
 * <h2>F-0232 — why the contract probe is status-blind</h2>
 *
 * The obvious method to reach for is {@code existsByCollaborationIdAndStatusNot(id, CANCELLED)},
 * and it is asked first below. But that method encodes {@code ContractService#generate}'s
 * DUPLICATE rule — "a CANCELLED contract (future re-negotiation support) does not block a fresh
 * one" — which is not the same question as "is anything durable still attached". A {@code
 * CANCELLED} contract leaves its {@code PaymentMilestone} and {@code Deliverable} rows behind:
 * both are keyed on {@code collaboration_id}, and no path in this codebase deletes or reverses
 * them. Reusing the duplicate guard as the encumbrance guard would have re-opened the same hole
 * the section above exists to close, one status further along. So a second probe asks the
 * status-blind question: does ANY contract row exist for this pair, in any status?
 *
 * <p>Those probes also cover the artifacts this service holds no repository for. {@code
 * PaymentMilestone} and {@code Deliverable} are materialised only inside {@code
 * ContractService#generate}, so neither can exist without a contract row to trip on; and {@code
 * DisputeService#openDispute} refuses unless there is FUNDED, unreleased escrow, so an open
 * dispute always trips the escrow probe. Every artifact {@code Collaboration#revive}'s javadoc
 * claims a {@code CANCELLED} row cannot have — contract, escrow hold, milestone, deliverable,
 * dispute — is therefore reachable from the probes here. That javadoc's claim is the false
 * assumption F-0232 names; this service does not rely on it.
 */
@Service
public class CollaborationReviveService {

    private static final Logger log = LoggerFactory.getLogger(CollaborationReviveService.class);

    /** Any hold at all encumbers the row, whatever state it is in. */
    private static final EnumSet<EscrowStatus> ANY_ESCROW = EnumSet.allOf(EscrowStatus.class);

    private final CollaborationRepository collaborationRepository;
    private final ContractRepository contractRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final ShipmentRepository shipmentRepository;

    public CollaborationReviveService(
            CollaborationRepository collaborationRepository,
            ContractRepository contractRepository,
            EscrowHoldRepository escrowHoldRepository,
            ShipmentRepository shipmentRepository) {
        this.collaborationRepository = collaborationRepository;
        this.contractRepository = contractRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.shipmentRepository = shipmentRepository;
    }

    /**
     * Resolve the prior collaboration for this pair, reviving it if it is a clean withdrawal.
     *
     * @param duplicateCode the caller's own 409 code — {@code ALREADY_APPLIED} on the creator
     *     side, {@code COLLABORATION_EXISTS} on the brand side. Kept as the caller's, because
     *     the frontends already branch on these and the message a creator reads is not the
     *     message a brand reads.
     * @return the revived, saved collaboration, or {@code null} when no prior row exists and
     *     the caller should insert a fresh one.
     */
    @Transactional
    public Collaboration reviveOrRefuse(
            String campaignId,
            String creatorUserId,
            CollaborationStatus newStatus,
            CollaborationSource newSource,
            String message,
            String currency,
            String duplicateCode,
            String duplicateMessage) {

        var existing = collaborationRepository.findByCampaignIdAndCreatorId(campaignId, creatorUserId);
        if (existing.isEmpty()) {
            return null;
        }
        Collaboration prior = existing.get();

        if (prior.getStatus() != CollaborationStatus.CANCELLED) {
            throw new ApiException(duplicateCode, duplicateMessage, HttpStatus.CONFLICT);
        }

        String encumbrance = describeEncumbrance(prior.getId());
        if (encumbrance != null) {
            // Deliberately loud rather than silently reviving. Reconciling a contract or a
            // funded hold is CR-22b's two-party termination flow, which does not exist yet —
            // and inventing a reconciliation here would move real money on a dead deal.
            log.warn(
                    "Refusing to revive CANCELLED collaboration {} — {} still attached",
                    prior.getId(),
                    encumbrance);
            throw new ApiException(
                    "COLLABORATION_NOT_REVIVABLE",
                    "This collaboration was cancelled after work had already started, so it cannot be"
                            + " restarted. Please create a new campaign to work together again.",
                    HttpStatus.CONFLICT);
        }

        prior.revive(newStatus, newSource, message, currency);
        return collaborationRepository.save(prior);
    }

    /** Null when the row is clean; otherwise a short phrase naming what is attached, for the log. */
    private String describeEncumbrance(String collaborationId) {
        if (contractRepository.existsByCollaborationIdAndStatusNot(
                collaborationId, ContractStatus.CANCELLED)) {
            return "a contract that was never voided";
        }
        // F-0232 — the probe above answers ContractService#generate's duplicate question, under
        // which a CANCELLED contract is "clean". For revive it is not: cancelling a contract
        // leaves its PaymentMilestone and Deliverable rows keyed on this collaboration, and
        // nothing deletes them. Status-blind on purpose; see the class javadoc.
        if (!contractRepository
                .findByCollaborationIdOrderByVersionDescCreatedAtDesc(collaborationId)
                .isEmpty()) {
            return "a cancelled contract whose milestones and deliverables were never reversed";
        }
        if (escrowHoldRepository.hasEscrowForCollaboration(collaborationId, ANY_ESCROW)) {
            return "an escrow hold";
        }
        if (shipmentRepository.findByCollaborationId(collaborationId).isPresent()) {
            return "a shipment";
        }
        return null;
    }
}
