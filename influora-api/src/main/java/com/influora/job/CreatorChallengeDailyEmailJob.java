package com.influora.job;

import com.influora.domain.entity.CreatorChallenge;
import com.influora.domain.entity.CreatorChallengeDay;
import com.influora.domain.entity.CreatorChallengeDayId;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.User;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.CreatorChallengeStatus;
import com.influora.repository.CreatorChallengeDayRepository;
import com.influora.repository.CreatorChallengeRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.UserRepository;
import com.influora.service.MetaConnectionService;
import com.influora.service.notification.event.CreatorChallengeDayDueEvent;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CHALLENGE-SPEC.md Backend &sect;7 -- 08:00 IST reminder for every ACTIVE {@link CreatorChallenge}
 * whose today is a posting day, not yet DONE. Deliberately NOT a new tick-off job -- CHALLENGE-
 * SPEC.md is explicit ("no new job"): whether a day is already DONE is read exactly as the last GET
 * left it, and the next real GET does its own lazy tick-off/completion independently of this job.
 *
 * <p><b>Disabled by default, same reasoning as {@code CreatorConnectNudgeJob}.</b> This mails real
 * people on a schedule, so turning it on is a deliberate deployment act, behind {@code
 * influora.creator-challenge.daily-email-enabled} (env {@code
 * CREATOR_CHALLENGE_DAILY_EMAIL_ENABLED}) -- the owner turns it on after reading the email
 * (CHALLENGE-SPEC.md Backend &sect;7).
 *
 * <p>Idempotency is per (challenge id, date), carried in {@link CreatorChallengeDayDueEvent}'s
 * {@code entityId} (see that event's javadoc) -- a re-run on the same day for the same challenge is
 * a no-op in {@code NotificationService}, so this job can run more than once a day with no risk of
 * a double-send.
 *
 * <p><b>Round 2 fix: skips a creator whose Instagram is no longer connected.</b> A reminder to post
 * "today" for an account this job can no longer see any data for is pure noise -- worse, the day
 * could never tick off anyway, since {@code CreatorChallengeService}'s tick-off reads from the same
 * Meta-sourced {@code media_metrics} table this creator has effectively stopped feeding. Checked via
 * {@link MetaConnectionService#getStatus}, the same call {@code CreatorChallengeService.start}/{@code
 * getState} use for {@code instagramConnected} -- one connection-status source of truth.
 */
@Component
public class CreatorChallengeDailyEmailJob {

    private static final Logger log = LoggerFactory.getLogger(CreatorChallengeDailyEmailJob.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Co-pilot page hosts the challenge card (CHALLENGE-SPEC.md Backend &sect;7). */
    static final String LINK = "/creator/copilot";

    private final CreatorChallengeRepository challengeRepository;
    private final CreatorChallengeDayRepository dayRepository;
    private final UserRepository userRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final MetaConnectionService metaConnectionService;
    private final ApplicationEventPublisher events;
    private final boolean enabled;

    public CreatorChallengeDailyEmailJob(
            CreatorChallengeRepository challengeRepository,
            CreatorChallengeDayRepository dayRepository,
            UserRepository userRepository,
            CreatorProfileRepository creatorProfileRepository,
            MetaConnectionService metaConnectionService,
            ApplicationEventPublisher events,
            @Value("${influora.creator-challenge.daily-email-enabled:false}") boolean enabled) {
        this.challengeRepository = challengeRepository;
        this.dayRepository = dayRepository;
        this.userRepository = userRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.metaConnectionService = metaConnectionService;
        this.events = events;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${influora.creator-challenge.daily-email-cron:0 0 8 * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = "CreatorChallengeDailyEmailJob", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void sendReminders() {
        if (!enabled) {
            return;
        }
        run(LocalDate.now(IST));
    }

    /** Package-private so tests drive the date rather than waiting on the scheduler. */
    int run(LocalDate today) {
        List<CreatorChallenge> activeChallenges = challengeRepository.findByStatus(CreatorChallengeStatus.ACTIVE);
        int sent = 0;
        for (CreatorChallenge challenge : activeChallenges) {
            Optional<CreatorChallengeDay> maybeDay = todaysDay(challenge, today);
            if (maybeDay.isEmpty()) {
                continue;
            }
            CreatorChallengeDay day = maybeDay.get();
            if (day.getPlannedType() == ChallengeDayType.REST || day.isDone()) {
                continue; // rest day, or already ticked off by a GET before this run fired
            }
            if (!isInstagramConnected(challenge)) {
                log.info(
                        "CreatorChallengeDailyEmailJob: skipping challengeId={} -- Instagram is no"
                                + " longer connected",
                        challenge.getId());
                continue;
            }
            User user = userRepository.findById(challenge.getCreatorUserId()).orElse(null);
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
                log.warn(
                        "CreatorChallengeDailyEmailJob: no resolvable email for creatorUserId={},"
                                + " skipping",
                        challenge.getCreatorUserId());
                continue;
            }
            try {
                // Published, not handed straight to NotificationService -- same reasoning as
                // CreatorConnectNudgeJob: NotificationEventContractTest requires a real
                // publisher AND listener for every event, and the listener runs synchronously
                // (plain @EventListener), so a failure here still lands in the catch below.
                events.publishEvent(
                        new CreatorChallengeDayDueEvent(
                                challenge.getCreatorUserId(),
                                challenge.getId() + ":" + today,
                                greetingName(user),
                                day.getPlannedType().name(),
                                windowText(day),
                                user.getEmail(),
                                LINK));
                sent++;
            } catch (RuntimeException e) {
                // One creator's failure must not abandon the rest of the run; idempotency means a
                // retry on the next scheduled run (or a later manual run) is always safe.
                log.error(
                        "CreatorChallengeDailyEmailJob: failed for challengeId={}", challenge.getId(), e);
            }
        }
        log.info(
                "CreatorChallengeDailyEmailJob: {} active challenges, {} reminders sent",
                activeChallenges.size(),
                sent);
        return sent;
    }

    private Optional<CreatorChallengeDay> todaysDay(CreatorChallenge challenge, LocalDate today) {
        if (today.isBefore(challenge.getStartedOn())
                || today.isAfter(challenge.getStartedOn().plusDays(6))) {
            return Optional.empty();
        }
        int dayIndex = (int) ChronoUnit.DAYS.between(challenge.getStartedOn(), today);
        return dayRepository.findById(new CreatorChallengeDayId(challenge.getId(), dayIndex));
    }

    /** Round 2 fix -- same connection check {@code CreatorChallengeService} uses for
     * {@code instagramConnected}. A profile that no longer resolves reads as not connected. */
    private boolean isInstagramConnected(CreatorChallenge challenge) {
        Optional<CreatorProfile> profile = creatorProfileRepository.findById(challenge.getCreatorProfileId());
        return profile.isPresent() && metaConnectionService.getStatus(profile.get()).connected();
    }

    /**
     * Round 2 fix: human words for the email, never the raw enum -- "a reel", "a carousel", "a
     * photo post", plus a natural time window like "this evening, 5–10 pm" (never "17:00-22:00" or
     * the raw daypart alone). {@code NotificationListener} does the SAME humanisation for the
     * in-app title/body and the {@code planned_type} template variable, so both channels read the
     * same way -- see that class for the {@code plannedType} mapping.
     */
    private static String windowText(CreatorChallengeDay day) {
        if (day.getWindowLabel() == null || day.getWindowFrom() == null || day.getWindowTo() == null) {
            return "today";
        }
        return "this " + day.getWindowLabel() + ", " + formatTimeRange(day.getWindowFrom(), day.getWindowTo());
    }

    /** "5–10 pm" when both ends share an am/pm period, else "11 am–2 pm". Every window this job
     * ever sees comes from a fixed on-the-hour daypart boundary or the 17:00-22:00 suggestion
     * default, so minutes are always :00 in practice -- the {@code :mm} branch below exists only
     * so this never silently mis-renders if that ever changes. */
    private static String formatTimeRange(LocalTime from, LocalTime to) {
        String fromPeriod = from.getHour() < 12 ? "am" : "pm";
        String toPeriod = to.getHour() < 12 ? "am" : "pm";
        String fromLabel = formatHour(from);
        String toLabel = formatHour(to);
        return fromPeriod.equals(toPeriod)
                ? fromLabel + "–" + toLabel + " " + toPeriod
                : fromLabel + " " + fromPeriod + "–" + toLabel + " " + toPeriod;
    }

    private static String formatHour(LocalTime time) {
        int hour12 = time.getHour() % 12;
        if (hour12 == 0) {
            hour12 = 12;
        }
        return time.getMinute() == 0 ? String.valueOf(hour12) : hour12 + ":" + String.format("%02d", time.getMinute());
    }

    /** Same "first name, else display name, else neutral greeting" fallback as
     * {@code CreatorConnectNudgeJob}. */
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
