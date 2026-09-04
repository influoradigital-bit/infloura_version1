package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.User;
import com.influora.repository.UserRepository;
import com.influora.service.notification.event.CreatorNotConnectedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;

/**
 * The sequencing here has no table behind it — it is carried by the outbox idempotency key, with
 * the stage in {@code entityId}. That makes the stage number the load-bearing value: get it wrong
 * and a creator either receives the same email repeatedly or silently receives nothing, and in both
 * cases the job logs a cheerful success. These tests pin the stage arithmetic at its boundaries,
 * the one-stage-per-run rule, and the payload the email actually needs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreatorConnectNudgeJobTest {

    private static final String BASE = "https://app.influora.in";

    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher events;

    private CreatorConnectNudgeJob job(boolean enabled) {
        return new CreatorConnectNudgeJob(userRepository, events, BASE, enabled, 30, 200);
    }

    /**
     * A real entity, aged by reflection. {@code User.newCreator} stamps {@code createdAt =
     * Instant.now()} and the field has no setter, so ageing is the only part that needs the back
     * door — everything the job reads is genuine.
     */
    private static User creator(String id, String firstName, Instant createdAt) {
        User u = User.newCreator(id, id + "@example.com", "hash", firstName, "Last", firstName);
        try {
            java.lang.reflect.Field f = User.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(u, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not age the test User", e);
        }
        return u;
    }

    private CreatorNotConnectedEvent capturePublished() {
        ArgumentCaptor<Object> c = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(c.capture());
        return (CreatorNotConnectedEvent) c.getValue();
    }

    @Test
    @DisplayName("stage boundaries: 1 at day 1, 2 at day 4, 3 at day 10, nothing before day 1")
    void stageBoundaries() {
        Instant now = Instant.parse("2026-09-20T09:30:00Z");

        assertEquals(0, CreatorConnectNudgeJob.dueStage(now.minus(Duration.ofHours(23)), now));
        assertEquals(1, CreatorConnectNudgeJob.dueStage(now.minus(Duration.ofDays(1)), now));
        assertEquals(1, CreatorConnectNudgeJob.dueStage(now.minus(Duration.ofDays(3)), now));
        assertEquals(2, CreatorConnectNudgeJob.dueStage(now.minus(Duration.ofDays(4)), now));
        assertEquals(2, CreatorConnectNudgeJob.dueStage(now.minus(Duration.ofDays(9)), now));
        assertEquals(3, CreatorConnectNudgeJob.dueStage(now.minus(Duration.ofDays(10)), now));
        // Past the last stage it stays at 3 forever; the outbox unique key is what stops the mail,
        // not the arithmetic.
        assertEquals(3, CreatorConnectNudgeJob.dueStage(now.minus(Duration.ofDays(400)), now));
        assertEquals(0, CreatorConnectNudgeJob.dueStage(null, now));
    }

    @Test
    @DisplayName("an older creator gets only the highest due stage, never a burst of all three")
    void offersOnlyTheHighestDueStage() {
        Instant now = Instant.parse("2026-09-20T09:30:00Z");
        User u = creator("u1", "Aditi", now.minus(Duration.ofDays(12)));
        when(userRepository.findCreatorsWithoutConnectedAccount(any(), any(), any(Limit.class)))
                .thenReturn(List.of(u));

        assertEquals(1, job(true).run(now));

        CreatorNotConnectedEvent e = capturePublished();
        assertEquals("connect-nudge-3", e.entityId());
        assertEquals("creator.not_connected", e.eventType());
    }

    @Test
    @DisplayName("event carries the CTA url and recipient — without them the email has no button")
    void carriesConnectUrlNameAndRecipient() {
        Instant now = Instant.parse("2026-09-20T09:30:00Z");
        User u = creator("u1", "Aditi", now.minus(Duration.ofDays(2)));
        when(userRepository.findCreatorsWithoutConnectedAccount(any(), any(), any(Limit.class)))
                .thenReturn(List.of(u));

        job(true).run(now);

        CreatorNotConnectedEvent e = capturePublished();
        assertEquals(BASE + "/creator/settings", e.connectUrl());
        assertEquals("Aditi", e.userName());
        assertEquals("u1@example.com", e.toEmail());
        assertEquals("u1", e.userId());
        assertEquals("connect-nudge-1", e.entityId());
    }

    @Test
    @DisplayName("a creator with no name still gets a readable greeting, not a blank or an email")
    void fallsBackToNeutralGreeting() {
        Instant now = Instant.parse("2026-09-20T09:30:00Z");
        User u = creator("u1", "   ", now.minus(Duration.ofDays(2)));
        when(userRepository.findCreatorsWithoutConnectedAccount(any(), any(), any(Limit.class)))
                .thenReturn(List.of(u));

        job(true).run(now);

        assertEquals("there", capturePublished().userName());
    }

    @Test
    @DisplayName("disabled by default: nothing is even queried")
    void disabledJobTouchesNothing() {
        job(false).sendNudges();
        verifyNoInteractions(userRepository, events);
    }

    @Test
    @DisplayName("one creator failing does not abandon the rest of the batch")
    void oneFailureDoesNotStopTheBatch() {
        Instant now = Instant.parse("2026-09-20T09:30:00Z");
        User bad = creator("bad", "A", now.minus(Duration.ofDays(2)));
        User good = creator("good", "B", now.minus(Duration.ofDays(2)));
        when(userRepository.findCreatorsWithoutConnectedAccount(any(), any(), any(Limit.class)))
                .thenReturn(List.of(bad, good));
        doThrow(new IllegalStateException("boom"))
                .when(events)
                .publishEvent(
                        argThat(
                                (Object o) ->
                                        o instanceof CreatorNotConnectedEvent ev
                                                && "bad".equals(ev.userId())));

        assertEquals(1, job(true).run(now), "the healthy creator should still have been published");
        verify(events)
                .publishEvent(
                        argThat(
                                (Object o) ->
                                        o instanceof CreatorNotConnectedEvent ev
                                                && "good".equals(ev.userId())));
    }

    @Test
    @DisplayName("query is floored so the back catalogue is never mailed in one batch")
    void queryIsBoundedAtBothEnds() {
        Instant now = Instant.parse("2026-09-20T09:30:00Z");
        when(userRepository.findCreatorsWithoutConnectedAccount(any(), any(), any(Limit.class)))
                .thenReturn(List.of());

        job(true).run(now);

        ArgumentCaptor<Instant> before = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> after = ArgumentCaptor.forClass(Instant.class);
        verify(userRepository)
                .findCreatorsWithoutConnectedAccount(before.capture(), after.capture(), any(Limit.class));

        // Newest eligible = one stage-1 delay ago; oldest eligible = the registered-within floor.
        assertEquals(now.minus(Duration.ofDays(1)), before.getValue());
        assertEquals(now.minus(Duration.ofDays(30)), after.getValue());
    }

    @Test
    @DisplayName("a creator younger than stage 1 is skipped even if the query returns them")
    void skipsCreatorBelowFirstStage() {
        Instant now = Instant.parse("2026-09-20T09:30:00Z");
        User u = creator("u1", "Aditi", now.minus(Duration.ofHours(2)));
        when(userRepository.findCreatorsWithoutConnectedAccount(any(), any(), any(Limit.class)))
                .thenReturn(List.of(u));

        assertEquals(0, job(true).run(now));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("stage() builds the entityId half of the outbox idempotency key")
    void stageIdentifiersAreStable() {
        // These strings are load-bearing: they are persisted in email_outbox.idempotency_key, so
        // changing them silently re-sends the whole sequence to every creator already nudged.
        assertEquals("connect-nudge-1", CreatorNotConnectedEvent.stage(1));
        assertEquals("connect-nudge-2", CreatorNotConnectedEvent.stage(2));
        assertEquals("connect-nudge-3", CreatorNotConnectedEvent.stage(3));
    }
}
