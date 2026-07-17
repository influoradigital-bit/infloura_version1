package com.influora.service;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * W2-1 — drives {@link Collaboration#getStatus()} through {@code CONTRACTED -> IN_PROGRESS ->
 * REVIEW_PENDING -> REVISION_REQUESTED -> COMPLETED} from the same domain moments that already
 * publish the corresponding notification events (contract generated/signed, escrow funded,
 * deliverable submitted/reviewed).
 *
 * <p><b>Audit finding this closes:</b> {@code CollaborationStatus.CONTRACT_PENDING}, {@code
 * CONTRACTED}, {@code IN_PROGRESS}, {@code REVIEW_PENDING}, {@code REVISION_REQUESTED}, and {@code
 * COMPLETED} were all declared in the enum, read in several places ({@code DealService} status
 * filters, {@code ReviewService}'s {@code COMPLETED} gate, {@code DashboardService}), but never
 * WRITTEN anywhere — a collaboration could reach {@code TERMS_AGREED} (accepting a proposal) and
 * then never move again for the rest of its lifecycle, permanently blocking {@code ReviewService}
 * (W2-3) and every "in progress" / "review" / "completed" deal-list filter.
 *
 * <p><b>Why synchronous, same-transaction calls instead of another {@code @EventListener}:</b> W3-1
 * converts {@code NotificationListener} to {@code @Async @TransactionalEventListener(AFTER_COMMIT)}
 * — correct for a side-channel notification, where a delayed or (on an unexpected exception)
 * dropped update only degrades UX. Collaboration status is core business state that {@code
 * ReviewService} gates on and that money-adjacent flows (escrow release eligibility) read; an
 * async listener failure here would silently strand a collaboration off-status with no retry and
 * no visible error. Called directly, in the same transaction as the domain action that earns the
 * transition, it fails (and rolls back) loudly with that action if something is wrong, exactly like
 * every other same-transaction write in these services.
 *
 * <p>Every transition is idempotent and monotonic-guarded: a collaboration already in or past a
 * target status, or in a terminal {@code CANCELLED}/{@code DISPUTED} state, is left untouched — a
 * retried webhook, a second milestone funding on the same collaboration, or a stray call from an
 * unrelated flow can never move status backwards or resurrect a cancelled/disputed deal.
 */
@Service
public class CollaborationLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(CollaborationLifecycleService.class);

    /** Terminal statuses no lifecycle nudge may ever override — owned exclusively by DealService/DisputeService. */
    private static final Set<CollaborationStatus> FROZEN =
            EnumSet.of(CollaborationStatus.CANCELLED, CollaborationStatus.DISPUTED, CollaborationStatus.COMPLETED);

    /** Prior statuses from which "contract generated" may legally advance to CONTRACT_PENDING. */
    private static final Set<CollaborationStatus> BEFORE_CONTRACT_PENDING =
            EnumSet.of(
                    CollaborationStatus.INVITED,
                    CollaborationStatus.APPLIED,
                    CollaborationStatus.SHORTLISTED,
                    CollaborationStatus.IN_NEGOTIATION,
                    CollaborationStatus.TERMS_AGREED);

    /** Statuses already at-or-past CONTRACT_PENDING — a contract regeneration is an idempotent no-op. */
    private static final Set<CollaborationStatus> AT_OR_PAST_CONTRACT_PENDING =
            EnumSet.of(
                    CollaborationStatus.CONTRACT_PENDING,
                    CollaborationStatus.CONTRACTED,
                    CollaborationStatus.IN_PROGRESS,
                    CollaborationStatus.REVIEW_PENDING,
                    CollaborationStatus.REVISION_REQUESTED,
                    CollaborationStatus.COMPLETED);

    private static final Set<CollaborationStatus> AT_OR_PAST_CONTRACTED =
            EnumSet.of(
                    CollaborationStatus.CONTRACTED,
                    CollaborationStatus.IN_PROGRESS,
                    CollaborationStatus.REVIEW_PENDING,
                    CollaborationStatus.REVISION_REQUESTED,
                    CollaborationStatus.COMPLETED);

    private static final Set<CollaborationStatus> AT_OR_PAST_IN_PROGRESS =
            EnumSet.of(
                    CollaborationStatus.IN_PROGRESS,
                    CollaborationStatus.REVIEW_PENDING,
                    CollaborationStatus.REVISION_REQUESTED,
                    CollaborationStatus.COMPLETED);

    /** Once a contract exists (CONTRACT_PENDING+), deliverable review activity may re-derive status. */
    private static final Set<CollaborationStatus> REVIEW_PHASE_ELIGIBLE =
            EnumSet.of(
                    CollaborationStatus.CONTRACT_PENDING,
                    CollaborationStatus.CONTRACTED,
                    CollaborationStatus.IN_PROGRESS,
                    CollaborationStatus.REVIEW_PENDING,
                    CollaborationStatus.REVISION_REQUESTED,
                    CollaborationStatus.COMPLETED);

    private static final Set<DeliverableStatus> DELIVERABLE_IN_REVIEW =
            EnumSet.of(DeliverableStatus.SUBMITTED, DeliverableStatus.RESUBMITTED);

    /**
     * A deliverable the brand has finished acting on — approved (and everything downstream of
     * approval), or terminally rejected (B6 — rejection is a final brand decision on that slot, not
     * something that blocks the rest of the collaboration from completing).
     */
    private static final Set<DeliverableStatus> DELIVERABLE_RESOLVED =
            EnumSet.of(
                    DeliverableStatus.APPROVED,
                    DeliverableStatus.REJECTED,
                    DeliverableStatus.POSTED,
                    DeliverableStatus.METRICS_REPORTED,
                    DeliverableStatus.VERIFIED);

    private final CollaborationRepository collaborationRepository;
    private final DeliverableRepository deliverableRepository;

    public CollaborationLifecycleService(
            CollaborationRepository collaborationRepository, DeliverableRepository deliverableRepository) {
        this.collaborationRepository = collaborationRepository;
        this.deliverableRepository = deliverableRepository;
    }

    /** {@code ContractService#generate} — a contract now exists and awaits signature. */
    @Transactional
    public void onContractGenerated(String collaborationId) {
        advance(collaborationId, CollaborationStatus.CONTRACT_PENDING, BEFORE_CONTRACT_PENDING, AT_OR_PAST_CONTRACT_PENDING);
    }

    /** {@code ContractService#doRecordSignature} — both brand and creator have now signed. */
    @Transactional
    public void onContractFullySigned(String collaborationId) {
        advance(
                collaborationId,
                CollaborationStatus.CONTRACTED,
                Set.of(CollaborationStatus.CONTRACT_PENDING),
                AT_OR_PAST_CONTRACTED);
    }

    /** {@code EscrowService#confirmFunded} — escrow for this collaboration is now funded. */
    @Transactional
    public void onEscrowFunded(String collaborationId) {
        advance(
                collaborationId,
                CollaborationStatus.IN_PROGRESS,
                Set.of(CollaborationStatus.CONTRACTED),
                AT_OR_PAST_IN_PROGRESS);
    }

    /** {@code CreatorDeliverableService#submitForReview} — a deliverable is newly awaiting brand review. */
    @Transactional
    public void onDeliverableSubmitted(String collaborationId) {
        recomputeReviewState(collaborationId);
    }

    /**
     * {@code BrandDeliverableService#approve}/{@code requestRevision}/{@code reject} — a brand
     * review decision was just recorded for one of this collaboration's deliverables.
     */
    @Transactional
    public void onDeliverableReviewed(String collaborationId) {
        recomputeReviewState(collaborationId);
    }

    private void advance(
            String collaborationId,
            CollaborationStatus target,
            Set<CollaborationStatus> allowedFrom,
            Set<CollaborationStatus> atOrPastTarget) {
        if (collaborationId == null || collaborationId.isBlank()) {
            return;
        }
        Collaboration collaboration = collaborationRepository.findById(collaborationId).orElse(null);
        if (collaboration == null) {
            log.warn("CollaborationLifecycleService: collaboration {} not found — skipping advance to {}", collaborationId, target);
            return;
        }
        CollaborationStatus current = collaboration.getStatus();
        if (FROZEN.contains(current) || atOrPastTarget.contains(current)) {
            return; // terminal, or already at/past this step — idempotent no-op
        }
        if (!allowedFrom.contains(current)) {
            // Out-of-order call (e.g. escrow funded before contract signature was recorded due to
            // an unrelated data issue) — do not silently jump the state machine.
            log.warn(
                    "CollaborationLifecycleService: collaboration {} is {} — refusing out-of-order advance to {}",
                    collaborationId,
                    current,
                    target);
            return;
        }
        collaboration.transitionTo(target);
        collaborationRepository.save(collaboration);
    }

    private void recomputeReviewState(String collaborationId) {
        if (collaborationId == null || collaborationId.isBlank()) {
            return;
        }
        Collaboration collaboration = collaborationRepository.findById(collaborationId).orElse(null);
        if (collaboration == null) {
            return;
        }
        CollaborationStatus current = collaboration.getStatus();
        if (FROZEN.contains(current) || !REVIEW_PHASE_ELIGIBLE.contains(current)) {
            return;
        }
        List<Deliverable> deliverables =
                deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(collaborationId);
        if (deliverables.isEmpty()) {
            return; // nothing materialized yet — nothing to derive from
        }
        CollaborationStatus next = computeReviewStatus(deliverables);
        if (next != current) {
            collaboration.transitionTo(next);
            collaborationRepository.save(collaboration);
        }
    }

    /**
     * Package-visible for direct unit testing without a repository round-trip. Priority order: any
     * deliverable still awaiting brand review outranks everything else (REVIEW_PENDING); otherwise
     * any deliverable the brand sent back outranks completion (REVISION_REQUESTED); otherwise every
     * deliverable is resolved (COMPLETED); otherwise the creator still has work outstanding
     * (IN_PROGRESS — e.g. still DRAFT/PENDING).
     */
    static CollaborationStatus computeReviewStatus(List<Deliverable> deliverables) {
        boolean anyInReview =
                deliverables.stream().anyMatch(d -> DELIVERABLE_IN_REVIEW.contains(d.getStatus()));
        if (anyInReview) {
            return CollaborationStatus.REVIEW_PENDING;
        }
        boolean anyRevision =
                deliverables.stream().anyMatch(d -> d.getStatus() == DeliverableStatus.REVISION_REQUESTED);
        if (anyRevision) {
            return CollaborationStatus.REVISION_REQUESTED;
        }
        boolean allResolved =
                deliverables.stream().allMatch(d -> DELIVERABLE_RESOLVED.contains(d.getStatus()));
        if (allResolved) {
            return CollaborationStatus.COMPLETED;
        }
        return CollaborationStatus.IN_PROGRESS;
    }
}
