package com.influora.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.AbuseThrottleCounter;
import com.influora.repository.AbuseThrottleCounterRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Kabir L-1 and L-3] Two independent ways {@link AbuseThrottleService} failed at the job it exists
 * to do, both invisible to the existing Mockito wiring tests.
 *
 * <ul>
 *   <li><b>L-1</b> — {@code throttle_key} is {@code VARCHAR(255)} and nothing bounded what callers
 *       put in it. {@code FestivalEnquiryService} builds {@code "festival-email:" + email} against
 *       an address bounded at 255, so a long-but-valid address overflowed the column: an
 *       unauthenticated 500 on a public form under MySQL strict mode, or — worse, under a
 *       non-strict deployment — a silent truncation that merges two people's budgets.
 *   <li><b>L-3</b> — the class promised "a REFUSED attempt still consumes budget", and
 *       {@code REQUIRED} propagation made that false: callers refuse by throwing from inside their
 *       own transaction, which rolled the increment back and handed out unlimited free probes at
 *       the cap boundary.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AbuseThrottleServiceKeyBoundAndPropagationTest {

    private static final int COLUMN_WIDTH = 255;

    @Mock private AbuseThrottleCounterRepository repository;

    private AbuseThrottleService service;

    @BeforeEach
    void setUp() {
        service = new AbuseThrottleService(repository);
        // Enough headroom that every test below is exercising the KEY, never the cap decision.
        lenient()
                .when(repository.findByThrottleKeyAndWindowStart(anyString(), any(Instant.class)))
                .thenReturn(Optional.of(counterWithCount(1)));
    }

    /** Same reflective construction as the sibling {@code AbuseThrottleServiceTest} — the entity
     * has no public setter and none should be added just for tests. */
    private static AbuseThrottleCounter counterWithCount(long count) {
        try {
            Constructor<AbuseThrottleCounter> ctor =
                    AbuseThrottleCounter.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            AbuseThrottleCounter counter = ctor.newInstance();
            Field requestCount = AbuseThrottleCounter.class.getDeclaredField("requestCount");
            requestCount.setAccessible(true);
            requestCount.setLong(counter, count);
            return counter;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** The longest key the enquiry form can actually produce: prefix + a max-length email. */
    private static String longestRealKey() {
        return "festival-email:" + "a".repeat(255 - "@example.com".length()) + "@example.com";
    }

    private String keyPassedToRepository(String requestedKey) {
        service.tryConsume(requestedKey, Duration.ofHours(1), 10);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).increment(anyString(), keyCaptor.capture(), any(Instant.class));
        return keyCaptor.getValue();
    }

    @Test
    @DisplayName("[L-1] the key the enquiry form can really build DOES exceed the column — the"
            + " precondition this fix exists for is real, not hypothetical")
    void theOverflowingKeyIsReachable() {
        // Asserted rather than assumed. If a future change shortens the email bound or the prefix,
        // this fails and tells the next reader the fix below may no longer be load-bearing, instead
        // of leaving them guarding a condition that can no longer occur.
        assertTrue(
                longestRealKey().length() > COLUMN_WIDTH,
                "expected the longest buildable enquiry key to overflow VARCHAR(255), got "
                        + longestRealKey().length());
    }

    @Test
    @DisplayName("[L-1] an over-long key is bounded to the column width before it reaches the DB")
    void overlongKeyIsBounded() {
        String stored = keyPassedToRepository(longestRealKey());

        assertTrue(
                stored.length() <= COLUMN_WIDTH,
                "key must fit VARCHAR(255), got " + stored.length() + ": " + stored);
    }

    @Test
    @DisplayName("[L-1] two different over-long keys stay DIFFERENT — bounded by hashing, never by"
            + " truncation")
    void overlongKeysDoNotCollide() {
        String tail = "a".repeat(230);
        String first = "festival-email:" + tail + "one@example.com";
        String second = "festival-email:" + tail + "two@example.com";

        String storedFirst = keyPassedToRepository(first);
        // Fresh mock interaction for the second call.
        org.mockito.Mockito.clearInvocations(repository);
        String storedSecond = keyPassedToRepository(second);

        // Plain truncation would map both onto the same 255 characters, silently merging two
        // people's budgets so each throttles the other. That is a correctness bug that looks like
        // nothing at all in production, which is why it gets its own test rather than relying on
        // the length assertion above.
        assertNotEquals(storedFirst, storedSecond);
        assertTrue(storedFirst.length() <= COLUMN_WIDTH);
        assertTrue(storedSecond.length() <= COLUMN_WIDTH);
    }

    @Test
    @DisplayName("[L-1] bounding is deterministic — the same key always maps to the same row")
    void boundingIsStable() {
        String key = longestRealKey();

        String first = keyPassedToRepository(key);
        org.mockito.Mockito.clearInvocations(repository);
        String second = keyPassedToRepository(key);

        // A throttle whose key varied between calls would create a brand-new counter every request
        // and cap nothing at all — the failure would look exactly like "the throttle is off".
        assertEquals(first, second);
    }

    @Test
    @DisplayName("[L-1] a key that already fits is passed through completely unchanged")
    void shortKeyIsUntouched() {
        String key = "festival-ip:" + "f".repeat(64);

        assertEquals(key, keyPassedToRepository(key));
    }

    @Test
    @DisplayName("[L-1] the read-back uses the SAME bounded key as the increment")
    void readBackUsesTheBoundedKey() {
        String stored = keyPassedToRepository(longestRealKey());

        // If increment() wrote the bounded key while the read looked up the raw one, the read would
        // miss, fall to the fail-closed Long.MAX_VALUE branch, and refuse EVERY request for that
        // key forever — a self-inflicted outage on the public form.
        verify(repository).findByThrottleKeyAndWindowStart(org.mockito.ArgumentMatchers.eq(stored), any(Instant.class));
    }

    @Test
    @DisplayName("[L-3] tryConsume runs in REQUIRES_NEW so a caller's rollback cannot undo the"
            + " increment")
    void tryConsumeCommitsIndependentlyOfTheCaller() throws Exception {
        Method tryConsume =
                AbuseThrottleService.class.getMethod(
                        "tryConsume", String.class, Duration.class, long.class);
        Transactional annotation = tryConsume.getAnnotation(Transactional.class);

        assertFalse(annotation == null, "tryConsume must be transactional");
        assertEquals(
                Propagation.REQUIRES_NEW,
                annotation.propagation(),
                "REQUIRED joins the caller's transaction, and every caller refuses by throwing from"
                        + " inside its own @Transactional method — which rolls this increment back,"
                        + " making refused attempts free and the documented 'refused attempts consume"
                        + " budget' guarantee false");

        // HONEST LIMIT: this asserts the declaration, not Spring's runtime behaviour. Proving the
        // commit survives a caller's rollback needs a real transaction manager and database, which
        // no test in this class has — and that is exactly why the bug lived here undetected: a
        // Mockito test has no transaction to roll back, so every existing test passed either way.
    }
}
