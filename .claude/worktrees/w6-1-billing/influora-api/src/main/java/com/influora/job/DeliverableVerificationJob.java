package com.influora.job;

import com.influora.domain.entity.Deliverable;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.repository.DeliverableRepository;
import com.influora.service.verification.DeliverableVerificationService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DPF-6 — periodic sweep that attempts platform verification for every deliverable that has a
 * live {@code postUrl} but has not yet been verified. Built alongside {@link
 * DeliverableVerificationService} rather than wiring the call into {@code
 * CreatorDeliverableService} (markPosted/reportMetrics) directly — same design choice already
 * made for {@code InstagramMetricsFetcher} vs {@code MetricsPollingJob} (see that class's
 * javadoc): those methods carry a large, already-reviewed unit test suite asserting exact
 * constructor shape and side effects, and this sweep needs no new dependency wired into them to
 * do its job. A future task can call {@link DeliverableVerificationService#verify} synchronously
 * from those methods too, if faster verification turnaround is wanted later.
 *
 * <p>Only scans {@link DeliverableStatus#POSTED} and {@link DeliverableStatus#METRICS_REPORTED} —
 * deliverables not yet {@code VERIFIED}. Once a deliverable is verified this sweep no longer
 * revisits it (a numbers refresh would need a separate, deliberately-scoped follow-up); one
 * unexpected failure for one deliverable never aborts the batch, mirroring {@code
 * MetricsPollingJob}'s per-item defensive catch.
 */
@Component
public class DeliverableVerificationJob {

    private static final Logger log = LoggerFactory.getLogger(DeliverableVerificationJob.class);

    private static final Set<DeliverableStatus> ELIGIBLE_STATUSES =
            Set.of(DeliverableStatus.POSTED, DeliverableStatus.METRICS_REPORTED);

    private final DeliverableRepository deliverableRepository;
    private final DeliverableVerificationService verificationService;

    /** In-memory overlap guard — matches the per-instance style used by MetricsPollingJob. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DeliverableVerificationJob(
            DeliverableRepository deliverableRepository,
            DeliverableVerificationService verificationService) {
        this.deliverableRepository = deliverableRepository;
        this.verificationService = verificationService;
    }

    /** Every 6 hours, offset 30 minutes from {@code MetricsPollingJob} to spread Meta API load. */
    @Scheduled(cron = "0 30 */6 * * *")
    @SchedulerLock(name = "DeliverableVerificationJob", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void runVerificationSweep() {
        if (!running.compareAndSet(false, true)) {
            log.warn("DeliverableVerificationJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            sweep();
        } finally {
            running.set(false);
        }
    }

    private void sweep() {
        List<Deliverable> candidates =
                deliverableRepository.findByStatusInAndPostUrlIsNotNull(ELIGIBLE_STATUSES);

        Map<DeliverableVerificationService.Outcome, Integer> tally = new java.util.EnumMap<>(
                DeliverableVerificationService.Outcome.class);

        for (Deliverable deliverable : candidates) {
            try {
                DeliverableVerificationService.Outcome outcome = verificationService.verify(deliverable);
                tally.merge(outcome, 1, Integer::sum);
            } catch (Exception e) {
                // Defensive catch-all so one deliverable's unexpected failure never aborts the batch —
                // the service itself is fail-closed for expected failure modes, this is belt-and-braces.
                log.error(
                        "DeliverableVerificationJob: unexpected failure verifying deliverable {}",
                        deliverable.getId(),
                        e);
            }
        }

        log.info(
                "DeliverableVerificationJob: swept {} candidates, outcomes={}", candidates.size(), tally);
    }
}
