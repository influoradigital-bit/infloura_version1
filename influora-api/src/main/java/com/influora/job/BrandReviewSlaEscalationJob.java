package com.influora.job;

import com.influora.domain.entity.Deliverable;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.repository.DeliverableRepository;
import com.influora.service.ReviewSlaService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps drafts the brand has left unanswered past its review window and hands them to the
 * Influora team (owner's ruling, 2026-09-21: 3 working days on a first submission, 2 on each
 * resubmission).
 *
 * <p><b>This job cannot approve anything and cannot pay anyone.</b> It calls exactly one method,
 * {@link ReviewSlaService#escalate}, which writes a support ticket and a timestamp. No status
 * moves, no escrow is touched, no payout is queued. A brand that misses its window still owes a
 * decision; a creator still gets paid on the same terms as always, after the post is live.
 *
 * <p><b>Two instances cannot double-notify.</b> {@code @SchedulerLock} is the real guard, the same
 * way every other scheduled writer in this package takes one — one instance holds the lock for a
 * run and the other simply does not run. The {@link AtomicBoolean} below is the in-process twin of
 * that, matching {@code DeliverableVerificationJob}/{@code MetricsPollingJob}: it stops a slow run
 * from overlapping the next trigger on the SAME instance. Neither is trusted on its own — {@code
 * ReviewSlaService#escalate} re-checks {@code reviewEscalatedAt} under a row lock, so even a
 * double run escalates nothing twice.
 *
 * <p><b>Hourly, on the hour, IST.</b> A deadline that expires at midnight IST should reach the
 * team's queue that morning, not up to a day later; hourly is the cheapest schedule that keeps the
 * queue within an hour of the truth without the job being a poller of note (the candidate query is
 * an index range read over drafts awaiting review, a small set by construction).
 */
@Component
public class BrandReviewSlaEscalationJob {

    private static final Logger log = LoggerFactory.getLogger(BrandReviewSlaEscalationJob.class);

    /** The only two states in which a deliverable is waiting on a brand decision. */
    private static final Set<DeliverableStatus> AWAITING_BRAND =
            Set.of(DeliverableStatus.SUBMITTED, DeliverableStatus.RESUBMITTED);

    private final DeliverableRepository deliverableRepository;
    private final ReviewSlaService reviewSlaService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public BrandReviewSlaEscalationJob(
            DeliverableRepository deliverableRepository, ReviewSlaService reviewSlaService) {
        this.deliverableRepository = deliverableRepository;
        this.reviewSlaService = reviewSlaService;
    }

    @Scheduled(cron = "${influora.review-sla.cron:0 5 * * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(
            name = "BrandReviewSlaEscalationJob",
            lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT1M")
    public void sweepOverdueReviews() {
        if (!reviewSlaService.isEscalationEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn(
                    "BrandReviewSlaEscalationJob: previous run still in progress, skipping this"
                            + " trigger");
            return;
        }
        try {
            run(Instant.now());
        } finally {
            running.set(false);
        }
    }

    /**
     * Package-private so tests drive the clock instead of waiting on the scheduler — the same
     * shape {@code CreatorConnectNudgeJob#run} uses.
     *
     * @return how many deliverables this run actually escalated
     */
    int run(Instant now) {
        // A cheap floor, not the deadline: the true deadline is a working-day count that depends
        // on each submission's own weekday. Everything that survives this query is re-checked
        // against the real clock inside escalate(), which is what stops a weekend from being
        // counted as review time.
        Instant floor = now.minus(Duration.ofDays(reviewSlaService.candidateFloorDays()));
        List<Deliverable> candidates =
                deliverableRepository
                        .findByStatusInAndReviewEscalatedAtIsNullAndSubmittedAtBeforeOrderBySubmittedAtAsc(
                                AWAITING_BRAND, floor, Limit.of(reviewSlaService.getBatchLimit()));

        int escalated = 0;
        for (Deliverable candidate : candidates) {
            try {
                if (reviewSlaService.escalate(candidate.getId(), now)) {
                    escalated++;
                }
            } catch (RuntimeException e) {
                // One deliverable's failure must never abandon the rest of the batch. Nothing is
                // lost by continuing: a failed escalation leaves reviewEscalatedAt null, so the
                // next hourly run picks the same row up again.
                log.error(
                        "BrandReviewSlaEscalationJob: escalation failed for deliverable {}",
                        candidate.getId(),
                        e);
            }
        }
        if (escalated > 0 || !candidates.isEmpty()) {
            log.info(
                    "BrandReviewSlaEscalationJob: {} candidates past the floor, {} escalated to the"
                            + " Influora team (the rest are still inside their review window)",
                    candidates.size(),
                    escalated);
        }
        return escalated;
    }
}
