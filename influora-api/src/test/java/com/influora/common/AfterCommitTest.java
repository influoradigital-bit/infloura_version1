package com.influora.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * The deferral primitive on its own, driven directly against {@link
 * TransactionSynchronizationManager}: no database, no Spring context.
 *
 * <p>Callbacks are driven the way {@code AbstractPlatformTransactionManager} drives them: {@code
 * invokeAfterCommit} over a snapshot of the registry, then a fresh snapshot for {@code
 * afterCompletion}. That snapshotting is why {@link #writeRegisteredFromInsideAfterCommitStillRuns}
 * passes here and would not with {@code @TransactionalEventListener(AFTER_COMMIT)}.
 * {@code ApplicationHistoryAfterCommitTest} proves the same contract end to end through a real
 * transaction manager.
 */
class AfterCommitTest {

    private ListAppender<ILoggingEvent> logs;
    private Logger afterCommitLogger;

    @BeforeEach
    void captureLogs() {
        afterCommitLogger = (Logger) LoggerFactory.getLogger(AfterCommit.class);
        logs = new ListAppender<>();
        logs.start();
        afterCommitLogger.addAppender(logs);
    }

    @AfterEach
    void cleanUp() {
        afterCommitLogger.detachAppender(logs);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** Mirrors AbstractPlatformTransactionManager.processCommit's callback sequence. */
    private static void commit() {
        TransactionSynchronizationUtils.invokeAfterCommit(
                new ArrayList<>(TransactionSynchronizationManager.getSynchronizations()));
        complete(TransactionSynchronization.STATUS_COMMITTED);
    }

    /** Rollback, or a commit that failed with an unknown outcome: afterCommit never fires. */
    private static void complete(int status) {
        List<TransactionSynchronization> forCompletion =
                new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationUtils.invokeAfterCompletion(forCompletion, status);
    }

    private List<ILoggingEvent> logsAt(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    @Test
    @DisplayName("with a transaction active: the write runs once, and only after the commit")
    void writeIsDeferredUntilAfterCommitAndRunsExactlyOnce() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger runs = new AtomicInteger();

        AfterCommit.run("test write", () -> "ctx", runs::incrementAndGet);

        assertEquals(
                0,
                runs.get(),
                "the write must not run while the transaction is open; that is the window in which"
                        + " its FK check waits on the caller's own row lock");

        commit();

        assertEquals(1, runs.get(), "exactly once: afterCommit and afterCompletion must not both run it");
    }

    @Test
    @DisplayName("rolled back: the write is DISCARDED and the discard is logged at WARN with context")
    void writeIsDiscardedWhenTheBusinessTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger runs = new AtomicInteger();

        AfterCommit.run("test write", () -> "eventType=X applicationId=A1", runs::incrementAndGet);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertEquals(0, runs.get(), "the business fact did not happen, so its row must not be written");
        List<ILoggingEvent> warns = logsAt(Level.WARN);
        assertEquals(1, warns.size(), "a discard must never be silent");
        assertTrue(warns.get(0).getFormattedMessage().contains("DISCARDED test write"));
        assertTrue(
                warns.get(0).getFormattedMessage().contains("eventType=X applicationId=A1"),
                "the discard line must carry the reconstruction context");
    }

    /**
     * STATUS_UNKNOWN: the commit failed in a way that does not prove a rollback. The row must not
     * be written (the caller was told the request failed), and it must not vanish quietly either:
     * it is logged at ERROR, not WARN, with the full reconstruction context. The first port of
     * this class treated UNKNOWN exactly like ROLLED_BACK, a WARN that reads as "deliberate".
     */
    @Test
    @DisplayName("unknown outcome: the write is withheld and logged at ERROR, never silently or at WARN")
    void unknownOutcomeIsWithheldAndLoggedAtError() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger runs = new AtomicInteger();

        AfterCommit.run("test write", () -> "eventType=X applicationId=A2", runs::incrementAndGet);
        complete(TransactionSynchronization.STATUS_UNKNOWN);

        assertEquals(0, runs.get(), "an unknown outcome must not assert the event happened");
        assertEquals(0, logsAt(Level.WARN).size(), "UNKNOWN must not be filed as a routine discard");
        List<ILoggingEvent> errors = logsAt(Level.ERROR);
        assertEquals(1, errors.size());
        String line = errors.get(0).getFormattedMessage();
        assertTrue(line.contains("WITHHELD test write"), line);
        assertTrue(line.contains("UNKNOWN outcome"), line);
        assertTrue(line.contains("eventType=X applicationId=A2"), line);
    }

    @Test
    @DisplayName("with NO transaction active: the write runs inline, immediately")
    void writeRunsInlineWithNoTransaction() {
        AtomicInteger runs = new AtomicInteger();

        AfterCommit.run("test write", () -> "ctx", runs::incrementAndGet);

        assertEquals(1, runs.get(), "with no commit to defer to, deferring would drop the write");
    }

    /**
     * The trap that ruled out {@code @TransactionalEventListener(AFTER_COMMIT)}: {@code
     * CreatorCampaignService#onApplicationHistoryRecorded} is itself an AFTER_COMMIT listener and
     * calls {@code ApplicationHistoryService#record} from inside it.
     */
    @Test
    @DisplayName("a write registered from inside another afterCommit callback still runs, once")
    void writeRegisteredFromInsideAfterCommitStillRuns() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger runs = new AtomicInteger();

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        AfterCommit.run("nested write", () -> "ctx", runs::incrementAndGet);
                    }
                });

        commit();

        assertEquals(1, runs.get(), "a nested registration must run exactly once, not be dropped");
    }

    @Test
    @DisplayName("a failing write is swallowed and logged at ERROR; it never escapes into commit()")
    void failingWriteNeverEscapesTheCommitCallback() {
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.run(
                "failing write",
                () -> "eventType=X applicationId=A3",
                () -> {
                    throw new IllegalStateException("history insert failed at flush");
                });

        assertDoesNotThrow(
                AfterCommitTest::commit,
                "an exception from an afterCommit callback propagates out of processCommit and would"
                        + " 500 a caller whose business transaction already committed");
        List<ILoggingEvent> errors = logsAt(Level.ERROR);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).getFormattedMessage().contains("LOST failing write"));
        assertTrue(errors.get(0).getFormattedMessage().contains("eventType=X applicationId=A3"));
    }

    @Test
    @DisplayName("a failing write with no transaction active is swallowed too")
    void failingInlineWriteNeverThrowsAtTheCallSite() {
        assertDoesNotThrow(
                () ->
                        AfterCommit.run(
                                "failing write",
                                () -> "ctx",
                                () -> {
                                    throw new IllegalStateException("history insert failed");
                                }),
                "record()/recordViewIfAbsent() must never throw at a business call site");
    }

    @Test
    @DisplayName("a context supplier that throws cannot turn a logged loss into an escaped exception")
    void throwingContextSupplierIsContained() {
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.run(
                "failing write",
                () -> {
                    throw new IllegalStateException("context blew up");
                },
                () -> {
                    throw new IllegalStateException("write blew up");
                });

        assertDoesNotThrow(AfterCommitTest::commit);
        assertEquals(1, logsAt(Level.ERROR).size());
    }
}
