package com.influora.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.WorkingDays;
import com.influora.config.ReviewSlaProperties;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.SupportTicket;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.domain.enums.TicketPriority;
import com.influora.domain.enums.TicketStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.SupportTicketRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The brand's review clock and its escalation (owner's ruling, 2026-09-21).
 *
 * <p>Every date here is a checked weekday: 2026-09-21 Monday through 2026-09-25 Friday, 26/27 the
 * weekend, 2026-09-28 the following Monday. Times are pinned to midday IST so no assertion depends
 * on where this test happens to run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewSlaServiceTest {

    private static final String DELIVERABLE_ID = "01HDELIVERABLEREVIEW01";
    private static final String COLLAB_ID = "01HCOLLABREVIEWSLA0001";
    private static final String CAMPAIGN_ID = "01HCAMPAIGNREVIEWSLA01";
    private static final String CREATOR_USER_ID = "01HCREATORREVIEWSLA001";

    private static final LocalDate MON = LocalDate.of(2026, 9, 21);
    private static final LocalDate TUE = LocalDate.of(2026, 9, 22);
    private static final LocalDate WED = LocalDate.of(2026, 9, 23);
    private static final LocalDate THU = LocalDate.of(2026, 9, 24);
    private static final LocalDate FRI = LocalDate.of(2026, 9, 25);
    private static final LocalDate NEXT_MON = LocalDate.of(2026, 9, 28);
    private static final LocalDate NEXT_WED = LocalDate.of(2026, 9, 30);

    @Mock private DeliverableRepository deliverableRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private SupportTicketRepository supportTicketRepository;

    private ReviewSlaService service;

    @BeforeEach
    void setUp() {
        service =
                new ReviewSlaService(
                        new ReviewSlaProperties(),
                        deliverableRepository,
                        collaborationRepository,
                        campaignRepository,
                        supportTicketRepository);
    }

    private static Instant middayIst(LocalDate date) {
        return date.atTime(12, 0).atZone(WorkingDays.ZONE).toInstant();
    }

    private Deliverable deliverable(DeliverableStatus status, Instant submittedAt) {
        Deliverable d =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("01HPROFILEREVIEWSLA001")
                        .slotIndex(1)
                        .type(DeliverableType.INSTAGRAM_REEL)
                        .title("Launch reel")
                        .status(status)
                        .build();
        // submittedAt is stamped by applySubmit with Instant.now(); the tests need a submission
        // that happened on a specific weekday, which only direct field access can give.
        ReflectionTestUtils.setField(d, "submittedAt", submittedAt);
        return d;
    }

    private void wireDealFor(Deliverable d) {
        when(deliverableRepository.findByIdForUpdate(DELIVERABLE_ID)).thenReturn(Optional.of(d));
        Collaboration collaboration =
                Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());
    }

    // ---------------------------------------------------------------------------------
    // The clock itself
    // ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("the clock both sides see")
    class Clock {

        @Test
        @DisplayName("a first submission gets 3 working days, a resubmission gets 2")
        void windows() {
            Deliverable first = deliverable(DeliverableStatus.SUBMITTED, middayIst(MON));
            Deliverable again = deliverable(DeliverableStatus.RESUBMITTED, middayIst(MON));

            assertThat(service.clockFor(first, middayIst(MON)).orElseThrow().dueDate())
                    .isEqualTo(THU);
            assertThat(service.clockFor(again, middayIst(MON)).orElseThrow().dueDate())
                    .isEqualTo(WED);
        }

        @Test
        @DisplayName("weekends do not count as review time")
        void weekendsDoNotCount() {
            // Submitted Friday. Three working days is the following Wednesday, five calendar days
            // later - Saturday and Sunday are not review time.
            Deliverable friday = deliverable(DeliverableStatus.SUBMITTED, middayIst(FRI));
            ReviewSlaService.ReviewClock clock =
                    service.clockFor(friday, middayIst(FRI)).orElseThrow();

            assertThat(clock.dueDate()).isEqualTo(NEXT_WED);
            assertThat(clock.workingDaysLeft()).isEqualTo(3);

            // Falsified: a calendar-day clock would have made this due on Monday, and it would
            // already have been overdue by Tuesday morning.
            assertThat(clock.dueDate()).isNotEqualTo(FRI.plusDays(3));
            assertThat(service.clockFor(friday, middayIst(NEXT_MON)).orElseThrow().overdue())
                    .isFalse();
            assertThat(service.clockFor(friday, middayIst(NEXT_MON)).orElseThrow().workingDaysLeft())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("working days left counts down and stops at zero on the due date")
        void countdown() {
            Deliverable d = deliverable(DeliverableStatus.SUBMITTED, middayIst(MON));
            assertThat(service.clockFor(d, middayIst(MON)).orElseThrow().workingDaysLeft())
                    .isEqualTo(3);
            assertThat(service.clockFor(d, middayIst(TUE)).orElseThrow().workingDaysLeft())
                    .isEqualTo(2);
            assertThat(service.clockFor(d, middayIst(WED)).orElseThrow().workingDaysLeft())
                    .isEqualTo(1);
            assertThat(service.clockFor(d, middayIst(THU)).orElseThrow().workingDaysLeft())
                    .isZero();
            // Due today is not overdue: the deadline is the END of Thursday.
            assertThat(service.clockFor(d, middayIst(THU)).orElseThrow().overdue()).isFalse();
            assertThat(service.clockFor(d, middayIst(FRI)).orElseThrow().overdue()).isTrue();
            // And it never goes negative once past.
            assertThat(service.clockFor(d, middayIst(NEXT_WED)).orElseThrow().workingDaysLeft())
                    .isZero();
        }

        @Test
        @DisplayName("no clock runs once the brand has acted, or before anything is submitted")
        void noClockWhenNotWaitingOnTheBrand() {
            for (DeliverableStatus status : DeliverableStatus.values()) {
                if (status == DeliverableStatus.SUBMITTED || status == DeliverableStatus.RESUBMITTED) {
                    continue;
                }
                assertThat(service.clockFor(deliverable(status, middayIst(MON)), middayIst(FRI)))
                        .as("no review clock for %s", status)
                        .isEmpty();
            }
            // Including the two the brand's own actions produce.
            assertThat(service.clockFor(deliverable(DeliverableStatus.APPROVED, middayIst(MON)), middayIst(FRI)))
                    .isEmpty();
            assertThat(
                            service.clockFor(
                                    deliverable(DeliverableStatus.REVISION_REQUESTED, middayIst(MON)),
                                    middayIst(FRI)))
                    .isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------
    // Escalation
    // ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("escalation to the Influora team")
    class Escalation {

        @Test
        @DisplayName("nothing escalates before the deadline")
        void notBeforeTheDeadline() {
            Deliverable d = deliverable(DeliverableStatus.SUBMITTED, middayIst(MON));
            wireDealFor(d);

            // Every moment inside the window, including the due date itself.
            assertThat(service.escalate(DELIVERABLE_ID, middayIst(MON))).isFalse();
            assertThat(service.escalate(DELIVERABLE_ID, middayIst(TUE))).isFalse();
            assertThat(service.escalate(DELIVERABLE_ID, middayIst(WED))).isFalse();
            assertThat(service.escalate(DELIVERABLE_ID, middayIst(THU))).isFalse();
            // 23:59 on the due date is still the brand's time.
            assertThat(service.escalate(DELIVERABLE_ID, WorkingDays.endOfDay(THU).minusMillis(1)))
                    .isFalse();

            verify(supportTicketRepository, never()).save(any());
            assertThat(d.getReviewEscalatedAt()).isNull();

            // Falsified: the very next instant DOES escalate, so the assertions above are about
            // the deadline and not about escalation being broken outright.
            assertThat(service.escalate(DELIVERABLE_ID, WorkingDays.endOfDay(THU))).isTrue();
        }

        @Test
        @DisplayName("escalates once, and only once, after the deadline")
        void onceAndOnlyOnce() {
            Deliverable d = deliverable(DeliverableStatus.SUBMITTED, middayIst(MON));
            wireDealFor(d);

            Instant firstFriday = middayIst(FRI);
            assertThat(service.escalate(DELIVERABLE_ID, firstFriday)).isTrue();
            assertThat(d.getReviewEscalatedAt()).isEqualTo(firstFriday);

            // Every later sweep - an hourly job runs many times a day - is a no-op.
            assertThat(service.escalate(DELIVERABLE_ID, middayIst(FRI).plusSeconds(3600))).isFalse();
            assertThat(service.escalate(DELIVERABLE_ID, middayIst(NEXT_MON))).isFalse();
            assertThat(service.escalate(DELIVERABLE_ID, middayIst(NEXT_WED))).isFalse();

            // Exactly one ticket, and the timestamp is still the first one.
            verify(supportTicketRepository).save(any(SupportTicket.class));
            assertThat(d.getReviewEscalatedAt()).isEqualTo(firstFriday);
        }

        @Test
        @DisplayName("a brand action stops the clock - an approved draft is never escalated")
        void brandActionStopsTheClock() {
            Deliverable d = deliverable(DeliverableStatus.SUBMITTED, middayIst(MON));
            wireDealFor(d);

            // Falsify the setup first: left alone, this one WOULD escalate on Friday.
            assertThat(service.clockFor(d, middayIst(FRI)).orElseThrow().overdue()).isTrue();

            // The brand approves on Wednesday, inside its window.
            d.applyApprove();

            assertThat(service.escalate(DELIVERABLE_ID, middayIst(FRI))).isFalse();
            assertThat(service.escalate(DELIVERABLE_ID, middayIst(NEXT_WED))).isFalse();
            verify(supportTicketRepository, never()).save(any());
            assertThat(d.getReviewEscalatedAt()).isNull();
        }

        @Test
        @DisplayName("asking for a revision also stops the clock - the ball is with the creator")
        void revisionRequestStopsTheClock() {
            Deliverable d = deliverable(DeliverableStatus.SUBMITTED, middayIst(MON));
            wireDealFor(d);

            d.applyRevision("Please reshoot the opening.");

            assertThat(service.escalate(DELIVERABLE_ID, middayIst(NEXT_WED))).isFalse();
            verify(supportTicketRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejecting also stops the clock")
        void rejectStopsTheClock() {
            Deliverable d = deliverable(DeliverableStatus.SUBMITTED, middayIst(MON));
            wireDealFor(d);

            d.applyReject("Not what we briefed.");

            assertThat(service.escalate(DELIVERABLE_ID, middayIst(NEXT_WED))).isFalse();
            verify(supportTicketRepository, never()).save(any());
        }

        @Test
        @DisplayName("a revision round restarts a fresh 2-working-day clock that escalates again")
        void revisionRoundRestartsTheClock() {
            Deliverable d = deliverable(DeliverableStatus.SUBMITTED, middayIst(MON));
            wireDealFor(d);

            // Round one runs out and escalates.
            assertThat(service.escalate(DELIVERABLE_ID, middayIst(FRI))).isTrue();

            // The brand finally acts, asking for a revision; the creator resubmits the following
            // Monday. applySubmit restamps submittedAt and clears the escalation flag.
            d.applyRevision("Tighten the last five seconds.");
            d.applySubmit(null, null, null, DeliverableStatus.RESUBMITTED);
            ReflectionTestUtils.setField(d, "submittedAt", middayIst(NEXT_MON));
            assertThat(d.getReviewEscalatedAt()).isNull();

            // Two working days now, not three: Monday -> due end of Wednesday.
            ReviewSlaService.ReviewClock clock =
                    service.clockFor(d, middayIst(NEXT_MON)).orElseThrow();
            assertThat(clock.dueDate()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(clock.workingDaysLeft()).isEqualTo(2);

            // Still inside the new window on Tuesday...
            assertThat(service.escalate(DELIVERABLE_ID, middayIst(LocalDate.of(2026, 9, 29))))
                    .isFalse();
            // ...and escalates a second time - once for this round - on Thursday.
            assertThat(service.escalate(DELIVERABLE_ID, middayIst(LocalDate.of(2026, 10, 1))))
                    .isTrue();

            // Two rounds, two tickets. Not one, and not three.
            verify(supportTicketRepository, org.mockito.Mockito.times(2)).save(any(SupportTicket.class));
        }

        @Test
        @DisplayName("escalating never approves, never rejects and never pays")
        void escalationChangesNothingElse() {
            Deliverable d = deliverable(DeliverableStatus.SUBMITTED, middayIst(MON));
            wireDealFor(d);

            assertThat(service.escalate(DELIVERABLE_ID, middayIst(FRI))).isTrue();

            // The deliverable is exactly where it was: still waiting on the brand's decision.
            assertThat(d.getStatus()).isEqualTo(DeliverableStatus.SUBMITTED);
            assertThat(d.getApprovedAt()).isNull();
            assertThat(d.getReviewedAt()).isNull();
            assertThat(d.getPostedAt()).isNull();
            assertThat(d.getReviewNotes()).isNull();
        }

        @Test
        @DisplayName("the ticket lands in the team's queue naming the creator who is waiting")
        void ticketShape() {
            Deliverable d = deliverable(DeliverableStatus.SUBMITTED, middayIst(MON));
            when(deliverableRepository.findByIdForUpdate(DELIVERABLE_ID)).thenReturn(Optional.of(d));
            when(collaborationRepository.findById(COLLAB_ID))
                    .thenReturn(
                            Optional.of(
                                    Collaboration.invite(
                                            COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR")));
            Campaign campaign = org.mockito.Mockito.mock(Campaign.class);
            when(campaign.getTitle()).thenReturn("Diwali launch");
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

            assertThat(service.escalate(DELIVERABLE_ID, middayIst(FRI))).isTrue();

            ArgumentCaptor<SupportTicket> captor = ArgumentCaptor.forClass(SupportTicket.class);
            verify(supportTicketRepository).save(captor.capture());
            SupportTicket ticket = captor.getValue();

            assertThat(ticket.getUserId()).isEqualTo(CREATOR_USER_ID);
            assertThat(ticket.getUserType()).isEqualTo(UserType.CREATOR);
            assertThat(ticket.getCategory()).isEqualTo(ReviewSlaService.TICKET_CATEGORY);
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
            assertThat(ticket.getPriority()).isEqualTo(TicketPriority.HIGH);
            assertThat(ticket.getSubject())
                    .contains("Diwali launch")
                    .contains(COLLAB_ID)
                    .contains("3 working days")
                    .contains(MON.toString())
                    .contains(THU.toString());
            // support_tickets.subject is VARCHAR(255) - a longer campaign title must not blow it.
            assertThat(ticket.getSubject().length()).isLessThanOrEqualTo(255);
        }

        @Test
        @DisplayName("a very long campaign title is truncated to fit the subject column")
        void subjectFitsTheColumn() {
            Deliverable d = deliverable(DeliverableStatus.SUBMITTED, middayIst(MON));
            when(deliverableRepository.findByIdForUpdate(DELIVERABLE_ID)).thenReturn(Optional.of(d));
            when(collaborationRepository.findById(COLLAB_ID))
                    .thenReturn(
                            Optional.of(
                                    Collaboration.invite(
                                            COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR")));
            Campaign campaign = org.mockito.Mockito.mock(Campaign.class);
            when(campaign.getTitle()).thenReturn("x".repeat(400));
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

            assertThat(service.escalate(DELIVERABLE_ID, middayIst(FRI))).isTrue();

            ArgumentCaptor<SupportTicket> captor = ArgumentCaptor.forClass(SupportTicket.class);
            verify(supportTicketRepository).save(captor.capture());
            assertThat(captor.getValue().getSubject().length()).isEqualTo(255);
        }

        @Test
        @DisplayName("no deal means no ticket - and no flag, so the next sweep retries")
        void missingCollaborationIsNotSwallowed() {
            Deliverable d = deliverable(DeliverableStatus.SUBMITTED, middayIst(MON));
            when(deliverableRepository.findByIdForUpdate(DELIVERABLE_ID)).thenReturn(Optional.of(d));
            when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.empty());

            assertThat(service.escalate(DELIVERABLE_ID, middayIst(FRI))).isFalse();
            verify(supportTicketRepository, never()).save(any());
            assertThat(d.getReviewEscalatedAt()).isNull();
        }

        @Test
        @DisplayName("an already-escalated round is a no-op even if the row is re-read")
        void alreadyEscalatedRowIsNeverRestamped() {
            Deliverable d = deliverable(DeliverableStatus.SUBMITTED, middayIst(MON));
            Instant earlier = middayIst(FRI);
            ReflectionTestUtils.setField(d, "reviewEscalatedAt", earlier);
            wireDealFor(d);

            assertThat(service.escalate(DELIVERABLE_ID, middayIst(NEXT_MON))).isFalse();
            assertThat(d.getReviewEscalatedAt()).isEqualTo(earlier);
            verify(supportTicketRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("a window of zero working days is refused at startup, not at 2am")
    void refusesAZeroWindow() {
        ReviewSlaProperties broken = new ReviewSlaProperties();
        broken.setFirstReviewWorkingDays(0);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new ReviewSlaService(
                                        broken,
                                        deliverableRepository,
                                        collaborationRepository,
                                        campaignRepository,
                                        supportTicketRepository))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("first-review-working-days");
    }

    @Test
    @DisplayName("the candidate floor can never exclude a genuinely overdue deliverable")
    void candidateFloorIsSafe() {
        // The floor is the shortest window in CALENDAR days. N working days always span at least
        // N calendar days, so anything submitted after the floor cannot be overdue yet.
        assertThat(service.candidateFloorDays()).isEqualTo(2);
        for (int startOffset = 0; startOffset < 14; startOffset++) {
            LocalDate submitted = MON.plusDays(startOffset);
            for (int window : new int[] {2, 3}) {
                LocalDate due = WorkingDays.addWorkingDays(submitted, window);
                assertThat(java.time.temporal.ChronoUnit.DAYS.between(submitted, due))
                        .as("a %d-working-day window from %s spans at least %d calendar days", window, submitted, window)
                        .isGreaterThanOrEqualTo(window);
            }
        }
    }
}
