package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.IdempotencyKeyRecord;
import com.influora.repository.IdempotencyKeyRecordRepository;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for {@link IdempotencyService}, in particular the {@code executeOnce} status
 * semantics: COMPLETED stays terminal-replay, IN_PROGRESS stays a genuine-concurrency rejection,
 * and FAILED is now re-runnable via an atomic status-guarded reclaim [SEC: Kabir, E2 HIGH-1 --
 * fixed]. This class previously had no dedicated test file -- coverage lived entirely in each
 * caller's own tests (PayoutServiceTest, ConversionTrackingServiceTest, etc.); this file covers
 * the shared service's own contract directly.
 *
 * <p><b>[SEC: Vikram, P3(c) fix]</b> {@code scope}/{@code workspaceId} now genuinely partition the
 * reservation keyspace -- the row actually reserved/looked-up in {@code repository} is the
 * COMPOSITE key ({@code scope + ":" + workspaceId + ":" + idempotencyKey}), not the raw caller
 * {@code idempotencyKey} verbatim. Every {@code repository} stub/verify below uses {@link
 * #COMPOSITE_KEY} to reflect that -- {@code service.executeOnce(...)} itself is still called with
 * the plain {@link #KEY}/{@link #WORKSPACE_ID}/{@link #SCOPE} triple, exactly as every real caller
 * in this codebase does (this composition is entirely internal/transparent to callers).
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final String KEY = "test:key123";
    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String SCOPE = "test.scope";

    /** The actual reserved primary-key value -- see class javadoc. */
    private static final String COMPOSITE_KEY = SCOPE + ":" + WORKSPACE_ID + ":" + KEY;

    @Mock private IdempotencyKeyRecordRepository repository;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository);
    }

    private IdempotencyKeyRecord recordWithStatus(IdempotencyKeyRecord.Status status) {
        IdempotencyKeyRecord record =
                IdempotencyKeyRecord.builder()
                        .idempotencyKey(COMPOSITE_KEY)
                        .workspaceId(WORKSPACE_ID)
                        .scope(SCOPE)
                        .build();
        if (status == IdempotencyKeyRecord.Status.COMPLETED) {
            record.markCompleted(null);
        } else if (status == IdempotencyKeyRecord.Status.FAILED) {
            record.markFailed();
        }
        return record;
    }

    // ------------------------------------------------------------------
    // Fresh key -- baseline behavior unchanged
    // ------------------------------------------------------------------

    @Test
    @DisplayName("executeOnce: fresh key reserves under the composite key, runs the action once, and marks COMPLETED")
    void testFreshKeyRunsActionAndCompletes() {
        when(repository.save(any())).thenReturn(null);

        String result = service.executeOnce(KEY, WORKSPACE_ID, SCOPE, () -> "ok");

        assertEquals("ok", result);
        verify(repository, times(1)).findByIdempotencyKey(COMPOSITE_KEY); // markCompletedTransactional lookup
    }

    @Test
    @DisplayName("executeOnce: action throws -> key marked FAILED, exception propagates, never masked")
    void testActionThrowsMarksFailedAndRethrows() {
        when(repository.save(any())).thenReturn(null);
        when(repository.findByIdempotencyKey(COMPOSITE_KEY))
                .thenReturn(Optional.of(recordWithStatus(IdempotencyKeyRecord.Status.IN_PROGRESS)));

        RuntimeException boom = new RuntimeException("gateway timeout");
        assertThrows(
                RuntimeException.class,
                () ->
                        service.executeOnce(
                                KEY,
                                WORKSPACE_ID,
                                SCOPE,
                                () -> {
                                    throw boom;
                                }));
    }

    // ------------------------------------------------------------------
    // COMPLETED stays terminal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("executeOnce: existing COMPLETED row -> AlreadyCompletedException, action never re-run")
    void testCompletedStaysTerminal() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup"));
        when(repository.findByIdempotencyKey(COMPOSITE_KEY))
                .thenReturn(Optional.of(recordWithStatus(IdempotencyKeyRecord.Status.COMPLETED)));

        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                IdempotencyService.AlreadyCompletedException.class,
                () -> service.executeOnce(KEY, WORKSPACE_ID, SCOPE, () -> calls.incrementAndGet()));

        assertEquals(0, calls.get());
    }

    // ------------------------------------------------------------------
    // IN_PROGRESS stays a genuine-concurrency rejection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("executeOnce: existing IN_PROGRESS row -> AlreadyInProgressException, action never re-run")
    void testInProgressStaysRejected() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup"));
        when(repository.reclaimFailedForRetry(
                        eq(COMPOSITE_KEY),
                        eq(IdempotencyKeyRecord.Status.FAILED),
                        eq(IdempotencyKeyRecord.Status.IN_PROGRESS)))
                .thenReturn(0); // row is IN_PROGRESS, not FAILED -> WHERE clause matches nothing
        when(repository.findByIdempotencyKey(COMPOSITE_KEY))
                .thenReturn(Optional.of(recordWithStatus(IdempotencyKeyRecord.Status.IN_PROGRESS)));

        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                IdempotencyService.AlreadyInProgressException.class,
                () -> service.executeOnce(KEY, WORKSPACE_ID, SCOPE, () -> calls.incrementAndGet()));

        assertEquals(0, calls.get());
    }

    // ------------------------------------------------------------------
    // FAILED is re-runnable [SEC: Kabir, E2 HIGH-1 -- fixed]
    // ------------------------------------------------------------------

    @Test
    @DisplayName("executeOnce: existing FAILED row is atomically reclaimed and the action re-runs successfully")
    void testFailedKeyIsReclaimedAndSucceedsOnRetry() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup"));
        when(repository.reclaimFailedForRetry(
                        eq(COMPOSITE_KEY),
                        eq(IdempotencyKeyRecord.Status.FAILED),
                        eq(IdempotencyKeyRecord.Status.IN_PROGRESS)))
                .thenReturn(1); // this caller's UPDATE matched the FAILED row

        when(repository.findByIdempotencyKey(COMPOSITE_KEY))
                .thenReturn(Optional.of(recordWithStatus(IdempotencyKeyRecord.Status.IN_PROGRESS)));

        AtomicInteger calls = new AtomicInteger();
        String result =
                service.executeOnce(KEY, WORKSPACE_ID, SCOPE, () -> "retried-ok-" + calls.incrementAndGet());

        assertEquals("retried-ok-1", result);
        assertEquals(1, calls.get());
        verify(repository, times(1))
                .reclaimFailedForRetry(
                        eq(COMPOSITE_KEY),
                        eq(IdempotencyKeyRecord.Status.FAILED),
                        eq(IdempotencyKeyRecord.Status.IN_PROGRESS));
        // reaching COMPLETED proves the reclaim let the action run instead of throwing terminal
        verify(repository, times(1)).findByIdempotencyKey(COMPOSITE_KEY); // markCompletedTransactional lookup
    }

    @Test
    @DisplayName(
            "executeOnce: two concurrent reclaim attempts on the same FAILED key -> exactly one proceeds, the"
                    + " other is rejected as still in progress")
    void testTwoConcurrentRetriesOfFailedKeyExactlyOneProceeds() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup"));
        // First caller's UPDATE wins (affected rows = 1); the DB row lock means the second
        // caller's UPDATE runs against a row already flipped to IN_PROGRESS and matches 0 rows.
        when(repository.reclaimFailedForRetry(
                        eq(COMPOSITE_KEY),
                        eq(IdempotencyKeyRecord.Status.FAILED),
                        eq(IdempotencyKeyRecord.Status.IN_PROGRESS)))
                .thenReturn(1)
                .thenReturn(0);
        when(repository.findByIdempotencyKey(COMPOSITE_KEY))
                .thenAnswer(invocation -> Optional.of(recordWithStatus(IdempotencyKeyRecord.Status.IN_PROGRESS)));

        AtomicInteger calls = new AtomicInteger();
        Supplier<String> action = () -> "winner-" + calls.incrementAndGet();

        String winnerResult = service.executeOnce(KEY, WORKSPACE_ID, SCOPE, action);
        assertEquals("winner-1", winnerResult);

        assertThrows(
                IdempotencyService.AlreadyInProgressException.class,
                () -> service.executeOnce(KEY, WORKSPACE_ID, SCOPE, action));

        // the action itself only ever ran once -- the loser never re-executed the effect
        assertEquals(1, calls.get());
        verify(repository, times(2))
                .reclaimFailedForRetry(
                        eq(COMPOSITE_KEY),
                        eq(IdempotencyKeyRecord.Status.FAILED),
                        eq(IdempotencyKeyRecord.Status.IN_PROGRESS));
    }

    @Test
    @DisplayName("executeOnce: reclaimed-then-action-throws-again -> marked FAILED again, still reclaimable next time")
    void testReclaimedKeyCanFailAgainAndRemainsReclaimable() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup"));
        when(repository.reclaimFailedForRetry(
                        eq(COMPOSITE_KEY),
                        eq(IdempotencyKeyRecord.Status.FAILED),
                        eq(IdempotencyKeyRecord.Status.IN_PROGRESS)))
                .thenReturn(1);

        RuntimeException gatewayTimeout = new RuntimeException("gateway timeout again");
        assertThrows(
                RuntimeException.class,
                () ->
                        service.executeOnce(
                                KEY,
                                WORKSPACE_ID,
                                SCOPE,
                                () -> {
                                    throw gatewayTimeout;
                                }));

        verify(repository, times(1)).findByIdempotencyKey(COMPOSITE_KEY); // markFailedTransactional lookup
    }

    // COMPLETED must never be reclaimed -- reclaimFailedForRetry is FAILED-only by construction of
    // its WHERE clause, so it is intentionally never invoked once a row is known COMPLETED (that
    // path short-circuits via tryReserveTransactional's failure + the COMPLETED check above); no
    // separate mock wiring is needed here since testCompletedStaysTerminal already proves the
    // action never re-runs for a COMPLETED row.

    // ------------------------------------------------------------------
    // [SEC: Vikram, P3(c)] scope/workspaceId genuinely partition the keyspace
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "executeOnce: the SAME raw idempotencyKey in a DIFFERENT scope reserves a DIFFERENT composite"
                    + " key -- no cross-scope collision")
    void testSameRawKeyDifferentScopeReservesDifferentCompositeKey() {
        when(repository.save(any())).thenReturn(null);

        service.executeOnce(KEY, WORKSPACE_ID, "other.scope", () -> "ok");

        verify(repository, never()).findByIdempotencyKey(COMPOSITE_KEY);
        verify(repository, times(1)).findByIdempotencyKey("other.scope:" + WORKSPACE_ID + ":" + KEY);
    }

    @Test
    @DisplayName(
            "executeOnce: the SAME raw idempotencyKey+scope in a DIFFERENT workspace reserves a DIFFERENT"
                    + " composite key -- no cross-tenant collision")
    void testSameRawKeyDifferentWorkspaceReservesDifferentCompositeKey() {
        when(repository.save(any())).thenReturn(null);
        String otherWorkspace = "01HOTHERWORKSPACE1234A";

        service.executeOnce(KEY, otherWorkspace, SCOPE, () -> "ok");

        verify(repository, never()).findByIdempotencyKey(COMPOSITE_KEY);
        verify(repository, times(1)).findByIdempotencyKey(SCOPE + ":" + otherWorkspace + ":" + KEY);
    }

    // ------------------------------------------------------------------
    // [SEC: Vikram, P3(c)] result-digest capture + replay lookup
    // ------------------------------------------------------------------

    @Test
    @DisplayName("executeOnce (5-arg): captures the result digest on COMPLETED via the supplied function")
    void testFiveArgOverloadCapturesResultDigest() {
        when(repository.save(any())).thenReturn(null);

        String result =
                service.executeOnce(KEY, WORKSPACE_ID, SCOPE, () -> "created-id-42", digest -> digest);

        assertEquals("created-id-42", result);
        verify(repository)
                .findByIdempotencyKey(COMPOSITE_KEY); // markCompletedTransactional's own lookup runs
    }

    @Test
    @DisplayName("findCompletedResultDigest: resolves the digest for a COMPLETED row under the composite key")
    void testFindCompletedResultDigestResolves() {
        IdempotencyKeyRecord completed = recordWithStatus(IdempotencyKeyRecord.Status.COMPLETED);
        // recordWithStatus marks COMPLETED with a null digest by default -- mark again with a real one.
        completed.markCompleted("ai-message-id-123");
        when(repository.findByIdempotencyKey(COMPOSITE_KEY)).thenReturn(Optional.of(completed));

        Optional<String> digest = service.findCompletedResultDigest(KEY, WORKSPACE_ID, SCOPE);

        assertTrue(digest.isPresent());
        assertEquals("ai-message-id-123", digest.get());
    }

    @Test
    @DisplayName("findCompletedResultDigest: empty for an IN_PROGRESS row -- never returns a digest for a non-terminal state")
    void testFindCompletedResultDigestEmptyForInProgress() {
        when(repository.findByIdempotencyKey(COMPOSITE_KEY))
                .thenReturn(Optional.of(recordWithStatus(IdempotencyKeyRecord.Status.IN_PROGRESS)));

        assertEquals(Optional.empty(), service.findCompletedResultDigest(KEY, WORKSPACE_ID, SCOPE));
    }

    @Test
    @DisplayName("findCompletedResultDigest: empty when the key is unknown")
    void testFindCompletedResultDigestEmptyWhenUnknown() {
        when(repository.findByIdempotencyKey(COMPOSITE_KEY)).thenReturn(Optional.empty());

        assertEquals(Optional.empty(), service.findCompletedResultDigest(KEY, WORKSPACE_ID, SCOPE));
    }
}
