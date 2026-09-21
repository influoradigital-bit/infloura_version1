package com.influora.repository;

import com.influora.domain.entity.Deliverable;
import com.influora.domain.enums.DeliverableStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliverableRepository extends JpaRepository<Deliverable, String> {

  /**
   * Creator-scoped lookup — ownership is one hop via {@code collaborations.creator_id} (user id),
   * mirroring {@code ContractRepository#findByIdAndCreatorId}.
   */
  @Query(
      "SELECT d FROM Deliverable d WHERE d.id = :id AND d.collaborationId IN "
          + "(SELECT co.id FROM Collaboration co WHERE co.creatorId = :creatorUserId)")
  Optional<Deliverable> findByIdAndCreatorUserId(
      @Param("id") String id, @Param("creatorUserId") String creatorUserId);

  List<Deliverable> findByCollaborationIdOrderBySlotIndexAsc(String collaborationId);

  /**
   * [EV-015] Locking read of every deliverable on a collaboration, for {@code
   * EscrowService#refundInternal}'s "has the creator started delivering?" gate. It must be a
   * locking read ({@code SELECT ... FOR UPDATE}), not a plain one: under MySQL REPEATABLE READ a
   * plain SELECT is served from the read view the refund transaction opened at its first plain
   * read (the membership lookup), so it would miss a submission committed while the refund was
   * waiting on the hold/collaboration locks. A locking read always sees the latest committed row
   * and also blocks a submission whose UPDATE is not committed yet until this transaction ends.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT d FROM Deliverable d WHERE d.collaborationId = :collaborationId ORDER BY d.id")
  List<Deliverable> findByCollaborationIdForUpdate(@Param("collaborationId") String collaborationId);

  /**
   * @deprecated CR-51 / Priya's Option B ruling: the release-condition gate in {@code
   *     EscrowService#assertReleaseConditionSatisfied} now re-keys off {@code collaborationId} via
   *     {@link #findByCollaborationIdOrderBySlotIndexAsc(String)}, not this method. {@code
   *     Deliverable.milestoneId} is never set by materialization (milestones and deliverables come
   *     from independent sources with no principled N:M mapping — see {@code
   *     ContractService.materializeDeliverables}), so this lookup always returns empty and has no
   *     remaining production caller. Left in place, not deleted, per CR-51 scope.
   */
  @Deprecated
  List<Deliverable> findByMilestoneId(String milestoneId);

  /**
   * H-22 — batch source for {@code AdminCampaignService}'s per-campaign deliverable aggregates
   * (deliverablesPending/deliverablesApproved/slaBreachRate), fetched once for a whole page of
   * campaigns via their collaboration ids rather than N+1 per campaign.
   */
  List<Deliverable> findByCollaborationIdIn(List<String> collaborationIds);

  /**
   * Brand-scoped lookup — workspace trust boundary via collaboration → campaign join-through
   * (mirrors {@code CollaborationRepository#findByIdAndWorkspaceId} / {@code DealService} brand
   * paths).
   *
   * <p>[F-0580] {@code PESSIMISTIC_WRITE} — {@code Deliverable} carries no {@code @Version}, and
   * this is the only lookup {@code BrandDeliverableService#approve} uses to resolve the row it is
   * about to read-modify-write on the money path (an approval attempts an escrow release). Same
   * {@code SELECT ... FOR UPDATE} pattern already used to serialize a concurrent read-modify-write
   * elsewhere in this codebase — see {@code EscrowHoldRepository#findByIdForUpdate} (via {@code
   * EscrowService#requireHoldForUpdate}) and {@code CollaborationRepository#findByIdForUpdate}.
   * Also taken by {@code getDetail}/{@code reject}, which never write through it — harmless.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT d FROM Deliverable d WHERE d.id = :id AND d.collaborationId IN "
          + "(SELECT c.id FROM Collaboration c WHERE c.campaignId IN "
          + "(SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :workspaceId))")
  Optional<Deliverable> findByIdAndWorkspaceId(
      @Param("id") String id, @Param("workspaceId") String workspaceId);

  /** DPF-8 — superseded revisions cleanup (approved deliverables older than 30 days). */
  List<Deliverable> findByStatusInAndApprovedAtBefore(
      Set<DeliverableStatus> statuses, Instant cutoff);

  /**
   * DPF-6 — verification sweep candidates: deliverables in a pre-verification status that already
   * have a live post URL (postUrl is null for a deliverable that reached {@code METRICS_REPORTED}
   * straight from {@code APPROVED}, i.e. self-reported before ever marking posted — nothing to
   * verify against yet for those).
   */
  List<Deliverable> findByStatusInAndPostUrlIsNotNull(Set<DeliverableStatus> statuses);

  /** DPF-8 — abandoned drafts cleanup (non-approved deliverables untouched for 90+ days). */
  List<Deliverable> findByStatusInAndUpdatedAtBefore(
      Set<DeliverableStatus> statuses, Instant cutoff);

  /**
   * Brand-review-clock sweep candidates ({@code BrandReviewSlaEscalationJob}): drafts still
   * awaiting a brand decision that have never been escalated and were submitted before {@code
   * submittedBefore}.
   *
   * <p>{@code submittedBefore} is only a cheap floor, NOT the deadline. The real deadline is a
   * working-day count that depends on the submission's own weekday, so it cannot be expressed as
   * a single SQL cutoff; the job re-checks every candidate against {@code ReviewSlaService} before
   * escalating anything. The floor is set from the SHORTEST configured window, so it can never
   * exclude a deliverable that is genuinely overdue — a window of N working days always spans at
   * least N calendar days.
   *
   * <p>{@code Limit} bounds one run (see {@code ReviewSlaProperties.batchLimit}); ordering by
   * {@code submittedAt} ascending means the creator who has been waiting longest is served first
   * rather than whoever the storage engine happens to return.
   */
  List<Deliverable> findByStatusInAndReviewEscalatedAtIsNullAndSubmittedAtBeforeOrderBySubmittedAtAsc(
      Set<DeliverableStatus> statuses, Instant submittedBefore, Limit limit);

  /**
   * Locking read used by {@code ReviewSlaService#escalate} to re-check {@code reviewEscalatedAt}
   * and stamp it in one transaction.
   *
   * <p>{@code PESSIMISTIC_WRITE} for the same reason {@link #findByIdAndWorkspaceId} takes it
   * (F-0580): {@code Deliverable} carries no {@code @Version}, and this is a read-modify-write on
   * a row a brand may be approving at the same instant. Without the lock, two sweeps — or a sweep
   * racing an approval — could both read {@code reviewEscalatedAt IS NULL} and both escalate.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT d FROM Deliverable d WHERE d.id = :id")
  Optional<Deliverable> findByIdForUpdate(@Param("id") String id);
}
