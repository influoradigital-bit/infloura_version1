package com.influora.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.AbuseThrottleCounter;
import com.influora.repository.AbuseThrottleCounterRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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

/**
 * Mockito-level wiring tests for {@link AbuseThrottleService} — verifies it calls {@link
 * AbuseThrottleCounterRepository#increment} then reads back the SAME {@code (throttleKey,
 * windowStart)}, and decides {@code true}/{@code false} correctly from whatever count comes back.
 *
 * <p>What this class deliberately does NOT prove: that the underlying upsert is actually atomic
 * under real concurrency, or that the fixed-hour window truncation lines up with wall-clock
 * boundaries the way production traffic will hit them. Both require a real database transaction,
 * not a mocked repository — see {@code AbuseThrottleServiceConcurrencyTest} (T-FESTIVALBOX-0905
 * phase 9's required "drive the real atomic path, not a mocked count" proof) for that.
 */
@ExtendWith(MockitoExtension.class)
class AbuseThrottleServiceTest {

    @Mock private AbuseThrottleCounterRepository repository;

    private AbuseThrottleService service;

    @BeforeEach
    void setUp() {
        service = new AbuseThrottleService(repository);
    }

    @Test
    @DisplayName("returns true when the post-increment count is within the cap")
    void withinCap_returnsTrue() {
        when(repository.findByThrottleKeyAndWindowStart(eq("k"), any(Instant.class)))
                .thenReturn(Optional.of(counterWithCount(3)));

        assertTrue(service.tryConsume("k", Duration.ofHours(1), 5));
        verify(repository).increment(anyString(), eq("k"), any(Instant.class));
    }

    @Test
    @DisplayName("returns false once the post-increment count exceeds the cap")
    void overCap_returnsFalse() {
        when(repository.findByThrottleKeyAndWindowStart(eq("k"), any(Instant.class)))
                .thenReturn(Optional.of(counterWithCount(6)));

        assertFalse(service.tryConsume("k", Duration.ofHours(1), 5));
    }

    @Test
    @DisplayName("a count exactly AT the cap is still allowed — the cap is inclusive")
    void countExactlyAtCap_stillAllowed() {
        when(repository.findByThrottleKeyAndWindowStart(eq("k"), any(Instant.class)))
                .thenReturn(Optional.of(counterWithCount(5)));

        assertTrue(service.tryConsume("k", Duration.ofHours(1), 5));
    }

    @Test
    @DisplayName("increment and the read-back are called with the exact SAME windowStart")
    void incrementAndReadBackShareOneWindow() {
        when(repository.findByThrottleKeyAndWindowStart(eq("k"), any(Instant.class)))
                .thenReturn(Optional.of(counterWithCount(1)));

        service.tryConsume("k", Duration.ofHours(1), 5);

        ArgumentCaptor<Instant> incrementWindow = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> readWindow = ArgumentCaptor.forClass(Instant.class);
        verify(repository).increment(anyString(), eq("k"), incrementWindow.capture());
        verify(repository).findByThrottleKeyAndWindowStart(eq("k"), readWindow.capture());

        assertEquals(incrementWindow.getValue(), readWindow.getValue());
    }

    @Test
    @DisplayName(
            "fails CLOSED (denies) if the counter row is somehow absent immediately after increment —"
                    + " should be unreachable in production, but a throttle bug must never fail open")
    void missingRowAfterIncrement_failsClosed() {
        when(repository.findByThrottleKeyAndWindowStart(anyString(), any(Instant.class)))
                .thenReturn(Optional.empty());

        assertFalse(service.tryConsume("k", Duration.ofHours(1), 1_000_000L));
    }

    /**
     * {@link AbuseThrottleCounter} has a protected no-arg constructor and NO setters by design (see
     * its class javadoc: the only correct write path is the repository's atomic upsert). Reflection
     * is used here, in the TEST only, specifically so production code is never given a back-door
     * mutator just to make this test easier to write.
     */
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
}
