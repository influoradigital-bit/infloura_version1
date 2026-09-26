package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.influora.web.dto.meta.MetaDtos.MetaConnectionStatusResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * CHALLENGE-SPEC.md Backend &sect;7/&sect;8: flag off -> nothing sent; skips REST and DONE days;
 * skips a creator whose Instagram is no longer connected (Round 2 fix); the window text and
 * planned-type words reaching the event are human-readable, never the raw enum (Round 2 fix,
 * humanised further by {@code NotificationListener#humanChallengeType} for the actual copy).
 * Idempotency-per-day is a property of {@link CreatorChallengeDayDueEvent#entityId()} (challenge id
 * + date), which {@code NotificationService}'s outbox actually enforces -- see that event's javadoc.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreatorChallengeDailyEmailJobTest {

    private static final String USER_ID = "01USERJOB000000000001";
    private static final String PROFILE_ID = "01PROFILEJOB00000001";

    @Mock private CreatorChallengeRepository challengeRepository;
    @Mock private CreatorChallengeDayRepository dayRepository;
    @Mock private UserRepository userRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private MetaConnectionService metaConnectionService;
    @Mock private ApplicationEventPublisher events;

    private CreatorChallengeDailyEmailJob job(boolean enabled) {
        return new CreatorChallengeDailyEmailJob(
                challengeRepository,
                dayRepository,
                userRepository,
                creatorProfileRepository,
                metaConnectionService,
                events,
                enabled);
    }

    @BeforeEach
    void stubInstagramConnectedByDefault() {
        // Most tests exercise paths downstream of the Round 2 connected-check; only the dedicated
        // "not connected" test below overrides this.
        when(creatorProfileRepository.findById(PROFILE_ID))
                .thenReturn(Optional.of(CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Test Creator")));
        when(metaConnectionService.getStatus(any(CreatorProfile.class))).thenReturn(metaConnected());
    }

    private static MetaConnectionStatusResponse metaConnected() {
        return new MetaConnectionStatusResponse(true, "@x", 100L, Instant.now(), List.of(), null, null, null);
    }

    private static MetaConnectionStatusResponse metaNotConnected() {
        return new MetaConnectionStatusResponse(false, null, null, null, List.of(), null, null, null);
    }

    private static User creator(String id, String firstName) {
        return User.newCreator(id, id + "@example.com", "hash", firstName, "Last", firstName);
    }

    private CreatorChallengeDayDueEvent capturePublished() {
        ArgumentCaptor<Object> c = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(c.capture());
        return (CreatorChallengeDayDueEvent) c.getValue();
    }

    @Test
    @DisplayName("flag off: sendReminders() does nothing at all")
    void flagOffSendsNothing() {
        CreatorChallengeDailyEmailJob job = job(false);
        job.sendReminders();
        // challengeRepository is the very first thing run() would touch if it got past the flag
        // check, and events is the outcome that matters most -- both untouched proves the early
        // return. (creatorProfileRepository/metaConnectionService are stubbed in @BeforeEach for
        // the other tests' benefit; whether that counts as a Mockito "interaction" is an unrelated
        // Mockito-internals question this test isn't about.)
        verifyNoInteractions(challengeRepository, dayRepository, userRepository, events);
    }

    @Test
    @DisplayName("today is a REST day: no reminder")
    void skipsRestDay() {
        LocalDate startedOn = LocalDate.of(2026, 9, 20);
        LocalDate today = startedOn; // day_index 0
        CreatorChallenge challenge = CreatorChallenge.start("01CHAL_REST", USER_ID, PROFILE_ID, startedOn);
        when(challengeRepository.findByStatus(CreatorChallengeStatus.ACTIVE)).thenReturn(List.of(challenge));
        when(dayRepository.findById(new CreatorChallengeDayId(challenge.getId(), 0)))
                .thenReturn(
                        Optional.of(
                                CreatorChallengeDay.plan(
                                        challenge.getId(), 0, today, ChallengeDayType.REST, null, null, null, null)));

        int sent = job(true).run(today);

        assertEquals(0, sent);
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("today is already DONE: no reminder")
    void skipsDoneDay() {
        LocalDate startedOn = LocalDate.of(2026, 9, 20);
        LocalDate today = startedOn;
        CreatorChallenge challenge = CreatorChallenge.start("01CHAL_DONE", USER_ID, PROFILE_ID, startedOn);
        CreatorChallengeDay day =
                CreatorChallengeDay.plan(
                        challenge.getId(), 0, today, ChallengeDayType.REEL, "evening", LocalTime.of(17, 0),
                        LocalTime.of(22, 0), "your_posts");
        day.markDone("m1", "VIDEO", true, Instant.now());
        when(challengeRepository.findByStatus(CreatorChallengeStatus.ACTIVE)).thenReturn(List.of(challenge));
        when(dayRepository.findById(new CreatorChallengeDayId(challenge.getId(), 0))).thenReturn(Optional.of(day));

        int sent = job(true).run(today);

        assertEquals(0, sent);
        verifyNoInteractions(events);
    }

    /** Round 2 fix: a reminder to post for an account this job can no longer see any data for is
     * noise, and the day could never tick off anyway. */
    @Test
    @DisplayName("Instagram no longer connected: no reminder, even though today is a due posting day")
    void skipsWhenInstagramNotConnected() {
        LocalDate startedOn = LocalDate.of(2026, 9, 20);
        LocalDate today = startedOn;
        CreatorChallenge challenge = CreatorChallenge.start("01CHAL_DISCONNECTED1", USER_ID, PROFILE_ID, startedOn);
        CreatorChallengeDay day =
                CreatorChallengeDay.plan(
                        challenge.getId(), 0, today, ChallengeDayType.REEL, "evening", LocalTime.of(17, 0),
                        LocalTime.of(22, 0), "your_posts");
        when(challengeRepository.findByStatus(CreatorChallengeStatus.ACTIVE)).thenReturn(List.of(challenge));
        when(dayRepository.findById(new CreatorChallengeDayId(challenge.getId(), 0))).thenReturn(Optional.of(day));
        when(metaConnectionService.getStatus(any(CreatorProfile.class))).thenReturn(metaNotConnected());

        int sent = job(true).run(today);

        assertEquals(0, sent);
        verifyNoInteractions(events);
        // Never even looked up the user/email for a creator this reminder is being skipped for.
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Instagram profile no longer resolves: reads as not connected, no reminder")
    void skipsWhenProfileMissing() {
        LocalDate startedOn = LocalDate.of(2026, 9, 20);
        LocalDate today = startedOn;
        CreatorChallenge challenge = CreatorChallenge.start("01CHAL_NOPROFILE0001", USER_ID, PROFILE_ID, startedOn);
        CreatorChallengeDay day =
                CreatorChallengeDay.plan(
                        challenge.getId(), 0, today, ChallengeDayType.POST, "evening", LocalTime.of(17, 0),
                        LocalTime.of(22, 0), "your_posts");
        when(challengeRepository.findByStatus(CreatorChallengeStatus.ACTIVE)).thenReturn(List.of(challenge));
        when(dayRepository.findById(new CreatorChallengeDayId(challenge.getId(), 0))).thenReturn(Optional.of(day));
        when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.empty());

        int sent = job(true).run(today);

        assertEquals(0, sent);
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("today is a posting day, not yet done, Instagram connected: sends one reminder with the right payload")
    void sendsForPostingDayNotDone() {
        LocalDate startedOn = LocalDate.of(2026, 9, 20);
        LocalDate today = startedOn.plusDays(2);
        CreatorChallenge challenge = CreatorChallenge.start("01CHAL_DUE", USER_ID, PROFILE_ID, startedOn);
        CreatorChallengeDay day =
                CreatorChallengeDay.plan(
                        challenge.getId(), 2, today, ChallengeDayType.CAROUSEL, "morning", LocalTime.of(5, 0),
                        LocalTime.of(12, 0), "your_posts");
        when(challengeRepository.findByStatus(CreatorChallengeStatus.ACTIVE)).thenReturn(List.of(challenge));
        when(dayRepository.findById(new CreatorChallengeDayId(challenge.getId(), 2))).thenReturn(Optional.of(day));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(creator(USER_ID, "Asha")));

        int sent = job(true).run(today);

        assertEquals(1, sent);
        CreatorChallengeDayDueEvent published = capturePublished();
        assertEquals(USER_ID, published.userId());
        assertEquals(challenge.getId() + ":" + today, published.entityId());
        assertEquals("Asha", published.creatorName());
        assertEquals("CAROUSEL", published.plannedType());
        // Round 2 fix: human, compact time range -- never "05:00"/"12:00", never just the raw
        // daypart with no "this" prefix.
        assertEquals("this morning, 5 am–12 pm", published.windowText());
        // User.newCreator lowercases the stored email.
        assertEquals((USER_ID + "@example.com").toLowerCase(java.util.Locale.ROOT), published.toEmail());
        assertEquals(CreatorChallengeDailyEmailJob.LINK, published.link());
    }

    @Test
    @DisplayName("evening window (17:00-22:00) renders as 'this evening, 5–10 pm'")
    void eveningWindowRendersCompactSamePeriod() {
        LocalDate startedOn = LocalDate.of(2026, 9, 20);
        CreatorChallenge challenge = CreatorChallenge.start("01CHAL_EVENING0001", USER_ID, PROFILE_ID, startedOn);
        CreatorChallengeDay day =
                CreatorChallengeDay.plan(
                        challenge.getId(), 0, startedOn, ChallengeDayType.REEL, "evening", LocalTime.of(17, 0),
                        LocalTime.of(22, 0), "suggestion");
        when(challengeRepository.findByStatus(CreatorChallengeStatus.ACTIVE)).thenReturn(List.of(challenge));
        when(dayRepository.findById(new CreatorChallengeDayId(challenge.getId(), 0))).thenReturn(Optional.of(day));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(creator(USER_ID, "Priya")));

        job(true).run(startedOn);

        CreatorChallengeDayDueEvent published = capturePublished();
        assertEquals("this evening, 5–10 pm", published.windowText());
        assertEquals("REEL", published.plannedType());
        assertFalse(published.windowText().contains(":00"), "should not show redundant :00 minutes");
    }

    @Test
    @DisplayName("entityId is stable across repeated runs on the same day -- what makes NotificationService's outbox idempotent per (challenge id, date)")
    void entityIdStableAcrossReruns() {
        LocalDate startedOn = LocalDate.of(2026, 9, 20);
        LocalDate today = startedOn;
        CreatorChallenge challenge = CreatorChallenge.start("01CHAL_RERUN", USER_ID, PROFILE_ID, startedOn);
        CreatorChallengeDay day =
                CreatorChallengeDay.plan(
                        challenge.getId(), 0, today, ChallengeDayType.POST, "evening", LocalTime.of(17, 0),
                        LocalTime.of(22, 0), "suggestion");
        when(challengeRepository.findByStatus(CreatorChallengeStatus.ACTIVE)).thenReturn(List.of(challenge));
        when(dayRepository.findById(new CreatorChallengeDayId(challenge.getId(), 0))).thenReturn(Optional.of(day));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(creator(USER_ID, "Rohit")));

        CreatorChallengeDailyEmailJob job = job(true);
        job.run(today);
        job.run(today);

        ArgumentCaptor<Object> c = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publishEvent(c.capture());
        String key1 = ((CreatorChallengeDayDueEvent) c.getAllValues().get(0)).entityId();
        String key2 = ((CreatorChallengeDayDueEvent) c.getAllValues().get(1)).entityId();
        assertEquals(key1, key2);
    }

    @Test
    @DisplayName("today outside the challenge's 7-day span: no reminder, no NPE")
    void skipsOutOfRangeChallenge() {
        LocalDate startedOn = LocalDate.of(2026, 9, 1);
        CreatorChallenge challenge = CreatorChallenge.start("01CHAL_STALE", USER_ID, PROFILE_ID, startedOn);
        when(challengeRepository.findByStatus(CreatorChallengeStatus.ACTIVE)).thenReturn(List.of(challenge));

        int sent = job(true).run(LocalDate.of(2026, 9, 30)); // way past day 6

        assertEquals(0, sent);
        verifyNoInteractions(dayRepository, events);
    }

    @Test
    @org.junit.jupiter.api.DisplayName(
            "the daily email is OFF unless the owner turns it on: the @Value default Spring resolves is"
                    + " false")
    void dailyEmailIsOffByDefault() {
        // Every other test builds the job with an explicit boolean, so the real default was never
        // exercised (Meera's falsification, 2026-09-24: flipping it to true left the suite green).
        // Read the default straight off the constructor parameter Spring injects.
        java.lang.reflect.Constructor<?> ctor =
                java.util.Arrays.stream(CreatorChallengeDailyEmailJob.class.getConstructors())
                        .filter(c -> java.util.Arrays.stream(c.getParameters())
                                .anyMatch(p -> p.isAnnotationPresent(org.springframework.beans.factory.annotation.Value.class)))
                        .findFirst()
                        .orElseThrow();
        String expression =
                java.util.Arrays.stream(ctor.getParameters())
                        .map(p -> p.getAnnotation(org.springframework.beans.factory.annotation.Value.class))
                        .filter(java.util.Objects::nonNull)
                        .map(org.springframework.beans.factory.annotation.Value::value)
                        .filter(v -> v.contains("daily-email-enabled"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("${influora.creator-challenge.daily-email-enabled:false}", expression);
    }

    @Test
    @org.junit.jupiter.api.DisplayName(
            "application.yml keeps the daily email OFF too - it is the value Spring actually uses")
    void applicationYmlKeepsTheDailyEmailOff() throws Exception {
        // The @Value default above only applies when the property is absent; application.yml
        // defines it, so ITS default is the effective one. Pin both.
        String yml =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of("src", "main", "resources", "application.yml"),
                        java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher line =
                java.util.regex.Pattern.compile("(?m)^\\s*daily-email-enabled:\\s*(.+)$").matcher(yml);
        org.junit.jupiter.api.Assertions.assertTrue(line.find(), "daily-email-enabled missing from application.yml");
        assertEquals("${CREATOR_CHALLENGE_DAILY_EMAIL_ENABLED:false}", line.group(1).trim());
    }
}
