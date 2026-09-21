package com.influora.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.WorkingDays;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.repository.DeliverableRepository;
import com.influora.service.ReviewSlaService;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Limit;

/**
 * The sweep that hands overdue drafts to the Influora team. The per-deliverable decision belongs to
 * {@code ReviewSlaServiceTest}; this covers the sweep's own obligations — that it asks about the
 * right rows, that one bad row does not abandon the batch, that the off switch is real, and that
 * two instances cannot both run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BrandReviewSlaEscalationJobTest {

    @Mock private DeliverableRepository deliverableRepository;
    @Mock private ReviewSlaService reviewSlaService;

    private static final Instant NOW =
            LocalDate.of(2026, 9, 25).atTime(9, 0).atZone(WorkingDays.ZONE).toInstant();

    private BrandReviewSlaEscalationJob job() {
        when(reviewSlaService.isEscalationEnabled()).thenReturn(true);
        when(reviewSlaService.candidateFloorDays()).thenReturn(2);
        when(reviewSlaService.getBatchLimit()).thenReturn(200);
        return new BrandReviewSlaEscalationJob(deliverableRepository, reviewSlaService);
    }

    private static Deliverable deliverable(String id) {
        return Deliverable.builder()
                .id(id)
                .collaborationId("01HCOLLABSWEEP00000001")
                .creatorProfileId("01HPROFILESWEEP0000001")
                .slotIndex(1)
                .type(DeliverableType.INSTAGRAM_REEL)
                .title("Reel")
                .status(DeliverableStatus.SUBMITTED)
                .build();
    }

    @SuppressWarnings("unchecked")
    private void stubCandidates(List<Deliverable> candidates) {
        when(deliverableRepository
                        .findByStatusInAndReviewEscalatedAtIsNullAndSubmittedAtBeforeOrderBySubmittedAtAsc(
                                any(Set.class), any(Instant.class), any(Limit.class)))
                .thenReturn(candidates);
    }

    @Test
    @DisplayName("asks only about drafts still waiting on a brand, never escalated, past the floor")
    void candidateQueryShape() {
        stubCandidates(List.of());
        job().run(NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<DeliverableStatus>> statuses = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Instant> floor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(deliverableRepository)
                .findByStatusInAndReviewEscalatedAtIsNullAndSubmittedAtBeforeOrderBySubmittedAtAsc(
                        statuses.capture(), floor.capture(), limit.capture());

        // Exactly the two states in which the brand owes a decision. An APPROVED or
        // REVISION_REQUESTED deliverable is never even a candidate.
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(
                        DeliverableStatus.SUBMITTED, DeliverableStatus.RESUBMITTED);
        assertThat(statuses.getValue()).doesNotContain(DeliverableStatus.APPROVED);
        assertThat(statuses.getValue()).doesNotContain(DeliverableStatus.REVISION_REQUESTED);

        assertThat(floor.getValue()).isEqualTo(NOW.minus(Duration.ofDays(2)));
        assertThat(limit.getValue().max()).isEqualTo(200);
    }

    @Test
    @DisplayName("counts only what actually escalated, not what it looked at")
    void countsRealEscalations() {
        stubCandidates(List.of(deliverable("d1"), deliverable("d2"), deliverable("d3")));
        when(reviewSlaService.escalate(eq("d1"), any(Instant.class))).thenReturn(true);
        // d2 is still inside its window once the real working-day clock is applied.
        when(reviewSlaService.escalate(eq("d2"), any(Instant.class))).thenReturn(false);
        when(reviewSlaService.escalate(eq("d3"), any(Instant.class))).thenReturn(true);

        assertThat(job().run(NOW)).isEqualTo(2);
        verify(reviewSlaService, times(3)).escalate(anyString(), eq(NOW));
    }

    @Test
    @DisplayName("one failing deliverable does not abandon the rest of the batch")
    void oneFailureDoesNotStopTheSweep() {
        stubCandidates(List.of(deliverable("d1"), deliverable("d2"), deliverable("d3")));
        when(reviewSlaService.escalate(eq("d1"), any(Instant.class)))
                .thenThrow(new IllegalStateException("database said no"));
        when(reviewSlaService.escalate(eq("d2"), any(Instant.class))).thenReturn(true);
        when(reviewSlaService.escalate(eq("d3"), any(Instant.class))).thenReturn(true);

        assertThat(job().run(NOW)).isEqualTo(2);
        // Falsified: without the per-item catch, d2 and d3 would never have been asked at all.
        verify(reviewSlaService).escalate(eq("d2"), any(Instant.class));
        verify(reviewSlaService).escalate(eq("d3"), any(Instant.class));
    }

    @Test
    @DisplayName("the off switch really is off - it reads nothing and escalates nothing")
    void offSwitch() {
        when(reviewSlaService.isEscalationEnabled()).thenReturn(false);
        new BrandReviewSlaEscalationJob(deliverableRepository, reviewSlaService)
                .sweepOverdueReviews();

        verify(deliverableRepository, never())
                .findByStatusInAndReviewEscalatedAtIsNullAndSubmittedAtBeforeOrderBySubmittedAtAsc(
                        any(), any(), any());
        verify(reviewSlaService, never()).escalate(anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("two instances cannot double-notify: the scheduled method holds a ShedLock")
    void isShedLocked() throws NoSuchMethodException {
        // An in-process AtomicBoolean only guards ONE JVM. The cross-instance guarantee is the
        // lock, and a lock that was never applied is invisible to every behavioural test - which
        // is exactly how "it escalates once" becomes "it escalates once per running instance".
        Method scheduled = BrandReviewSlaEscalationJob.class.getMethod("sweepOverdueReviews");
        SchedulerLock lock = scheduled.getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("BrandReviewSlaEscalationJob");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT30M");
        assertThat(lock.lockAtLeastFor()).isEqualTo("PT1M");
    }

    @Test
    @DisplayName("the sweep has no way to approve or pay - it depends on nothing that could")
    void cannotMoveMoney() {
        // Structural, and deliberately so: this job's only collaborators are the deliverable
        // repository and the SLA service. It holds no escrow service, no payout service, no
        // wallet and no milestone repository, so "it might auto-pay" is not a thing that can
        // become true without this assertion failing first.
        assertThat(
                        java.util.Arrays.stream(
                                        BrandReviewSlaEscalationJob.class.getDeclaredFields())
                                .filter(f -> !f.isSynthetic())
                                .map(f -> f.getType().getSimpleName())
                                .toList())
                .containsExactlyInAnyOrder(
                        "Logger", "Set", "DeliverableRepository", "ReviewSlaService", "AtomicBoolean");
    }
}
