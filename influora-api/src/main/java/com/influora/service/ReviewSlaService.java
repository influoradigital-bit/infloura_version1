package com.influora.service;

import com.influora.common.TextSanitizer;
import com.influora.common.Ulids;
import com.influora.common.WorkingDays;
import com.influora.config.ReviewSlaProperties;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.SupportTicket;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.TicketPriority;
import com.influora.domain.enums.UserType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.SupportTicketRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The brand's review clock, and what happens when it runs out.
 *
 * <p><b>The rule (owner's ruling, 2026-09-21).</b> Once a creator submits a draft, the brand has 3
 * working days to approve it, reject it or ask for a revision — 2 working days on each
 * resubmission. If the brand does none of those things in time, the deliverable is escalated to
 * the Influora team, who then chase it as people. It is <b>never auto-approved</b> and it
 * <b>never triggers a payment</b>: nothing in this class touches {@code DeliverableStatus},
 * escrow, a milestone or a wallet, and payment is a separate event entirely (the creator is paid
 * after the post is live and its link is submitted).
 *
 * <p><b>Why a support ticket and not something new.</b> The team already has one queue it works
 * every day — {@code support_tickets}, with triage, assignment, priority, reply and resolve, wired
 * to a live admin screen ({@code AdminSupportService}). An escalation is exactly a thing a person
 * has to pick up and act on, so it goes there. Two alternatives were checked and rejected:
 * {@code content_flags} is the <i>moderation</i> queue, and filing a late review there would count
 * against the creator's {@code flaggedContentCount} — it would make the person who did nothing
 * wrong look flagged; and an in-app {@code Notification} row is addressed to a {@code users.id},
 * which no Influora team member has (they are {@code admin_users}).
 *
 * <p><b>The ticket is opened in the creator's name.</b> {@code support_tickets.user_id} is the
 * requester, and the creator is who is waiting and who is owed an answer. This also means the
 * ticket appears in the creator's own support list ({@code SupportService#listMine}) and they can
 * reply to it — which is the recourse they did not previously have. No opening message row is
 * written: {@code support_ticket_messages.sender_type} is only {@code USER} or {@code ADMIN}, and
 * writing the platform's own words under either name would put words in someone's mouth. The
 * subject carries the facts instead.
 *
 * <p><b>Once, and only once, per submission round.</b> {@link Deliverable#getReviewEscalatedAt()}
 * is stamped under a row lock inside this method's transaction, and {@link
 * Deliverable#applySubmit} clears it — so each round gets exactly one escalation and a brand
 * action simply ends the round (status leaves SUBMITTED/RESUBMITTED and {@link #clockFor} stops
 * returning a clock at all).
 */
@Service
public class ReviewSlaService {

    private static final Logger log = LoggerFactory.getLogger(ReviewSlaService.class);

    /** Ticket category — the filter value the team triages these under. */
    public static final String TICKET_CATEGORY = "DELIVERABLE_REVIEW";

    /** {@code support_tickets.subject} is VARCHAR(255). */
    private static final int SUBJECT_MAX = 255;

    private final ReviewSlaProperties properties;
    private final DeliverableRepository deliverableRepository;
    private final CollaborationRepository collaborationRepository;
    private final CampaignRepository campaignRepository;
    private final SupportTicketRepository supportTicketRepository;

    public ReviewSlaService(
            ReviewSlaProperties properties,
            DeliverableRepository deliverableRepository,
            CollaborationRepository collaborationRepository,
            CampaignRepository campaignRepository,
            SupportTicketRepository supportTicketRepository) {
        // Fail at startup, not per-row at 2am. A window of 0 working days would make
        // WorkingDays.addWorkingDays throw on every candidate, and the job's per-item catch would
        // turn that into a log line nobody reads while the promise quietly went unenforced.
        requireAtLeastOneDay("first-review-working-days", properties.getFirstReviewWorkingDays());
        requireAtLeastOneDay(
                "revision-review-working-days", properties.getRevisionReviewWorkingDays());
        this.properties = properties;
        this.deliverableRepository = deliverableRepository;
        this.collaborationRepository = collaborationRepository;
        this.campaignRepository = campaignRepository;
        this.supportTicketRepository = supportTicketRepository;
    }

    private static void requireAtLeastOneDay(String key, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(
                    "influora.review-sla." + key + " must be at least 1 working day, got " + value);
        }
    }

    /**
     * What both sides are shown. The brand reads {@link #workingDaysLeft} ("3 working days left to
     * review"); the creator reads {@link #submittedAt} and {@link #dueAt} ("submitted Mon 22 Sep,
     * Influora steps in after Thu 25 Sep"), plus {@link #escalatedAt} once it has.
     *
     * @param dueDate last working day the brand can act on, in {@link WorkingDays#ZONE}
     * @param dueAt the exact deadline — the first instant after {@link #dueDate} ends
     * @param workingDaysLeft working days remaining, not counting today; {@code 0} means the
     *     deadline is the end of today, and it stays {@code 0} once past rather than going negative
     * @param overdue true once {@code now} is at or past {@link #dueAt}
     * @param escalatedAt when the Influora team was told, or null if they have not been
     */
    public record ReviewClock(
            Instant submittedAt,
            LocalDate dueDate,
            Instant dueAt,
            int workingDaysLeft,
            boolean overdue,
            Instant escalatedAt) {}

    /**
     * The clock for one deliverable, or empty when none is running — that is, whenever the
     * deliverable is not sitting in {@code SUBMITTED}/{@code RESUBMITTED} waiting on the brand.
     * A deliverable the brand has already acted on has no clock, which is what stops a brand
     * action from ever being chased.
     *
     * <p>Computed fresh on every read and never persisted, exactly like {@code
     * CreatorDeliverableService#isOverdue} — there is no stored "days left" to drift.
     */
    public Optional<ReviewClock> clockFor(Deliverable deliverable, Instant now) {
        if (deliverable == null || !awaitingBrand(deliverable.getStatus())) {
            return Optional.empty();
        }
        Instant submittedAt = deliverable.getSubmittedAt();
        if (submittedAt == null) {
            // Defensive: applySubmit always stamps it alongside the status, so this pairing does
            // not occur in practice. Reporting "no clock" is the honest answer if it ever did —
            // better than inventing a deadline from a timestamp we do not have.
            return Optional.empty();
        }
        LocalDate dueDate =
                WorkingDays.addWorkingDays(
                        WorkingDays.dateOf(submittedAt), windowWorkingDays(deliverable.getStatus()));
        Instant dueAt = WorkingDays.endOfDay(dueDate);
        return Optional.of(
                new ReviewClock(
                        submittedAt,
                        dueDate,
                        dueAt,
                        WorkingDays.workingDaysLeft(now, dueDate),
                        !now.isBefore(dueAt),
                        deliverable.getReviewEscalatedAt()));
    }

    /** Working days the brand gets, by which round this is. */
    public int windowWorkingDays(DeliverableStatus status) {
        return status == DeliverableStatus.RESUBMITTED
                ? properties.getRevisionReviewWorkingDays()
                : properties.getFirstReviewWorkingDays();
    }

    static boolean awaitingBrand(DeliverableStatus status) {
        return status == DeliverableStatus.SUBMITTED || status == DeliverableStatus.RESUBMITTED;
    }

    /**
     * Escalates one deliverable to the Influora team if — and only if — its clock has genuinely
     * run out and it has not already been escalated this round.
     *
     * <p>{@code REQUIRES_NEW} so one deliverable's escalation commits (or fails) on its own: the
     * sweep processes a batch, and a row that cannot be escalated must not take the rest of the
     * batch's already-stamped rows down with it. Every check is re-done here against a locked
     * re-read, so a brand who approved between the sweep's candidate query and this call wins the
     * race and is never chased for something they already did.
     *
     * @return true if this call escalated the deliverable, false if it was already escalated, no
     *     longer awaiting the brand, or still inside its window
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean escalate(String deliverableId, Instant now) {
        Deliverable deliverable = deliverableRepository.findByIdForUpdate(deliverableId).orElse(null);
        if (deliverable == null) {
            return false;
        }
        if (deliverable.getReviewEscalatedAt() != null) {
            return false;
        }
        ReviewClock clock = clockFor(deliverable, now).orElse(null);
        if (clock == null || !clock.overdue()) {
            return false;
        }

        Collaboration collaboration =
                collaborationRepository.findById(deliverable.getCollaborationId()).orElse(null);
        if (collaboration == null) {
            // No deal means no creator to name and no deal for the team to open. Refuse rather
            // than file a ticket nobody can act on; the row keeps reviewEscalatedAt null, so if
            // this is ever transient the next sweep tries again.
            log.error(
                    "Review escalation skipped for deliverable {}: collaboration {} not found",
                    deliverableId,
                    deliverable.getCollaborationId());
            return false;
        }

        supportTicketRepository.save(
                SupportTicket.open(
                        Ulids.newUlid(),
                        collaboration.getCreatorId(),
                        UserType.CREATOR,
                        TICKET_CATEGORY,
                        buildSubject(deliverable, collaboration, clock),
                        // HIGH, not URGENT: a creator is waiting on money and needs a person this
                        // week, but URGENT is what the team raises a ticket TO when it is on fire
                        // (SupportTicket#escalate), and a queue where everything arrives URGENT
                        // has no way left to say so.
                        TicketPriority.HIGH));

        deliverable.markReviewEscalated(now);
        deliverableRepository.save(deliverable);
        log.info(
                "Review escalated to the Influora team: deliverable={} deal={} submittedAt={}"
                        + " dueAt={} (no status change, no payment)",
                deliverableId,
                collaboration.getId(),
                clock.submittedAt(),
                clock.dueAt());
        return true;
    }

    /**
     * The whole record the team reads, in one line, because no message row is written (see the
     * class javadoc). Carries what someone needs to pick it up: what happened, which campaign,
     * which deal to open, and the two dates.
     */
    private String buildSubject(
            Deliverable deliverable, Collaboration collaboration, ReviewClock clock) {
        String campaignTitle =
                campaignRepository
                        .findById(collaboration.getCampaignId())
                        .map(Campaign::getTitle)
                        .filter(title -> title != null && !title.isBlank())
                        .map(TextSanitizer::sanitizePlainText)
                        .orElse("a campaign");
        int window = windowWorkingDays(deliverable.getStatus());
        String subject =
                String.format(
                        "Brand has not reviewed a draft in %d working days - %s (deal %s,"
                                + " submitted %s, was due %s)",
                        window,
                        campaignTitle,
                        collaboration.getId(),
                        WorkingDays.dateOf(clock.submittedAt()),
                        clock.dueDate());
        return subject.length() <= SUBJECT_MAX ? subject : subject.substring(0, SUBJECT_MAX);
    }

    /**
     * Candidate floor for the sweep — the shortest configured window in calendar days. Anything
     * submitted after this cannot possibly be overdue yet, because N working days always span at
     * least N calendar days.
     */
    public int candidateFloorDays() {
        return Math.min(
                properties.getFirstReviewWorkingDays(), properties.getRevisionReviewWorkingDays());
    }

    public boolean isEscalationEnabled() {
        return properties.isEscalationEnabled();
    }

    public int getBatchLimit() {
        return properties.getBatchLimit();
    }
}
