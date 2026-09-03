package com.influora.job;

import com.influora.domain.entity.User;
import com.influora.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import com.influora.service.notification.event.CreatorNotConnectedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Emails creators who registered but never connected a Meta account. Until they do, the profile
 * holds no audience data, so it is absent from brand search and no campaign match can reach them —
 * the account is inert rather than merely incomplete.
 *
 * <p><b>Three sends, and no table to track them.</b> The sequence is carried entirely by the
 * outbox's {@code UNIQUE (idempotency_key)} over {@code eventType:entityId:userId}, with {@code
 * entityId} holding the stage ({@link CreatorNotConnectedEvent#stage}). Each run computes the
 * <i>highest</i> stage whose delay has elapsed and offers it; a stage already delivered collapses
 * to a no-op inside {@code NotificationService}. So a creator crosses day 1 → stage 1, days 2-3 →
 * no-op, day 4 → stage 2, day 10 → stage 3, and thereafter nothing, permanently. Rows in {@code
 * email_outbox} are never purged, so that dedupe does not decay.
 *
 * <p>Offering only the highest due stage is deliberate. A creator who is already 12 days old the
 * first time this job sees them gets stage 3 alone, not a burst of all three — the alternative
 * would read as a system catching up on its backlog at the recipient's expense.
 *
 * <p><b>Stopping on connect needs no code.</b> The eligibility query anti-joins {@code
 * meta_oauth_tokens}, so the moment a token exists the creator leaves the result set mid-sequence.
 * Soft-revoked tokens ({@code revoked = 1}) correctly read as not connected, matching every other
 * reader of that table.
 *
 * <p><b>Disabled by default.</b> This job emails real people on a schedule, so switching it on is a
 * deliberate deployment act rather than a side effect of shipping the code. {@code registered-
 * within} additionally floors the query: without it the first enabled run would select every
 * un-connected creator ever registered and mail the entire back catalogue at once.
 */
@Component
public class CreatorConnectNudgeJob {

    private static final Logger log = LoggerFactory.getLogger(CreatorConnectNudgeJob.class);

    /** Age at which each send becomes due. Index 0 is stage 1. Must stay ascending. */
    static final List<Duration> STAGE_DELAYS =
            List.of(Duration.ofDays(1), Duration.ofDays(4), Duration.ofDays(10));

    /** Where the Connect Instagram control actually lives (renders ConnectedAccounts). */
    static final String CONNECT_PATH = "/creator/settings";

    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;
    private final String webBaseUrl;
    private final boolean enabled;
    private final Duration registeredWithin;
    private final int batchLimit;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public CreatorConnectNudgeJob(
            UserRepository userRepository,
            ApplicationEventPublisher events,
            @Value("${influora.web-base-url}") String webBaseUrl,
            @Value("${influora.creator-connect-nudge.enabled:false}") boolean enabled,
            @Value("${influora.creator-connect-nudge.registered-within-days:30}") int registeredWithinDays,
            @Value("${influora.creator-connect-nudge.batch-limit:200}") int batchLimit) {
        this.userRepository = userRepository;
        this.events = events;
        this.webBaseUrl = webBaseUrl;
        this.enabled = enabled;
        this.registeredWithin = Duration.ofDays(registeredWithinDays);
        this.batchLimit = batchLimit;
    }

    /**
     * Mid-morning IST rather than the small hours the maintenance jobs use: this one is read by a
     * person, and the send time is when it lands in their inbox.
     */
    @Scheduled(cron = "${influora.creator-connect-nudge.cron:0 30 9 * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = "CreatorConnectNudgeJob", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void sendNudges() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("CreatorConnectNudgeJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            run(Instant.now());
        } finally {
            running.set(false);
        }
    }

    /** Package-private so tests drive the clock rather than waiting on the scheduler. */
    int run(Instant now) {
        List<User> candidates =
                userRepository.findCreatorsWithoutConnectedAccount(
                        now.minus(STAGE_DELAYS.get(0)),
                        now.minus(registeredWithin),
                        Limit.of(batchLimit));

        int offered = 0;
        for (User user : candidates) {
            int stage = dueStage(user.getCreatedAt(), now);
            if (stage == 0) {
                continue;
            }
            try {
                // Published, not handed straight to NotificationService: NotificationEventContract
                // Test requires every event to have a real publisher AND a listener, precisely to
                // catch events that look wired but fire nothing. The listener runs synchronously
                // (plain @EventListener, no @Async), so a failure still lands in the catch below
                // and the count stays honest.
                events.publishEvent(
                        new CreatorNotConnectedEvent(
                                user.getId(),
                                null,
                                CreatorNotConnectedEvent.stage(stage),
                                greetingName(user),
                                user.getEmail(),
                                webBaseUrl + CONNECT_PATH));
                offered++;
            } catch (RuntimeException e) {
                // One creator's failure must not abandon the rest of the batch; the next daily run
                // retries them anyway, since an undelivered stage leaves no idempotency row.
                log.error("CreatorConnectNudgeJob: failed for userId={}", user.getId(), e);
            }
        }
        log.info(
                "CreatorConnectNudgeJob: {} candidates, {} stages offered (already-sent stages"
                        + " collapse to no-ops in NotificationService)",
                candidates.size(),
                offered);
        return offered;
    }

    /**
     * Highest stage whose delay has elapsed, or 0 if none has. Returns one stage, never a burst.
     */
    static int dueStage(Instant createdAt, Instant now) {
        if (createdAt == null) {
            return 0;
        }
        Duration age = Duration.between(createdAt, now);
        for (int i = STAGE_DELAYS.size() - 1; i >= 0; i--) {
            if (age.compareTo(STAGE_DELAYS.get(i)) >= 0) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * A first name if we have one — the copy opens "Hi {{user_name}}," and an email address or a
     * blank there reads worse than a neutral greeting.
     */
    private static String greetingName(User user) {
        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            return user.getFirstName().trim();
        }
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName().trim();
        }
        return "there";
    }
}
