package com.influora.common;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a best-effort side write only after the caller's business transaction has committed and
 * released its row locks.
 *
 * <p><b>The defect this prevents.</b> {@code application_history_events.application_id} has a real
 * InnoDB foreign key to {@code collaborations(id)} ({@code fk_app_history_application},
 * V69__application_history_events.sql:64). To check an FK on insert, InnoDB takes an {@code
 * S,REC_NOT_GAP} lock on the parent row. The history writer used to be {@code
 * @Transactional(REQUIRES_NEW)}, so it inserted on a second connection while the caller's
 * transaction was still open, and that caller already held the same {@code collaborations} row
 * under {@code X,REC_NOT_GAP}: from a flushed {@code UPDATE} in {@code DealService#doAccept}, or
 * from {@code CollaborationRepository#findByIdForUpdate} in {@code ContractService#generate} and
 * the signing path. The inner insert waited on its own caller, and the caller was waiting in the
 * JVM for the inner call to return. InnoDB's deadlock detector cannot see a cycle that passes
 * through the JVM, so nothing broke it until {@code innodb_lock_wait_timeout} (50 s). The row was
 * then lost. Measured on MySQL 8.0.40 at 2d143c6: accept 50.44 s, contract generation 50.48 s,
 * the signature that makes a contract ACTIVE 50.84 s, and 0 history rows written.
 *
 * <p><b>Why a raw {@link TransactionSynchronization} and not {@code
 * @TransactionalEventListener(AFTER_COMMIT)}.</b> {@code
 * CreatorCampaignService#onApplicationHistoryRecorded} is itself an AFTER_COMMIT listener, and it
 * calls {@code ApplicationHistoryService#record}. {@code
 * AbstractPlatformTransactionManager.triggerAfterCommit} iterates a snapshot of the registered
 * synchronizations, so a synchronization registered from inside an {@code afterCommit} callback
 * never gets its own {@code afterCommit}. The AFTER_COMMIT event listener does nothing in {@code
 * afterCompletion}, so a nested publish would be dropped silently. This class implements both
 * callbacks behind a {@code ran} latch. {@code triggerAfterCompletion} takes a fresh snapshot, so
 * a late registration still runs exactly once.
 *
 * <p><b>Outcomes, by completion status.</b>
 *
 * <ul>
 *   <li>{@code STATUS_COMMITTED}: the action runs once, after the commit. If it throws, the
 *       exception is caught and logged at ERROR with the reconstruction context. The business
 *       write has already committed and cannot be affected. An exception escaping an {@code
 *       afterCommit} callback would otherwise propagate out of {@code processCommit} and turn a
 *       committed request into a 500.
 *   <li>{@code STATUS_ROLLED_BACK}: the action is discarded and logged at WARN with the
 *       reconstruction context. These are records of things that happened. An append-only row
 *       asserting a fact that was rolled back is worse than a missing row, because nothing can
 *       take it back.
 *   <li>{@code STATUS_UNKNOWN}: the action is NOT run, and the discard is logged at ERROR with the
 *       full reconstruction context and a distinct message saying the outcome is unknown. Spring
 *       reports this status when the commit itself failed in a way that does not prove a rollback
 *       (for example the connection died during {@code COMMIT}, or a heuristic outcome). The
 *       business write may or may not be in the database, and the caller has already received an
 *       exception from the commit. Writing the row would assert, permanently, a fact the caller
 *       was told failed. Dropping it silently would hide a row that may be owed. So it is
 *       withheld and logged at ERROR, never at WARN, with every field needed to insert the row by
 *       hand once someone has checked whether the parent change landed.
 * </ul>
 *
 * <p><b>No synchronization active: the action runs inline, immediately, with the same guard.</b>
 * With no transaction there is no lock for the write to wait on and no commit to defer to. This
 * keeps non-transactional callers, and every plain Mockito test, on the synchronous behaviour.
 *
 * <p><b>Any action that writes must open its own transaction</b> (a {@code REQUIRES_NEW} bean
 * method). During {@code afterCommit} the committed transaction's resources are still bound to
 * the thread, so a {@code REQUIRED} method would join a transaction that has already committed
 * and its writes would never be committed.
 *
 * <p><b>Process death.</b> If the JVM dies between the business commit and the action, the action
 * is lost with no log line. There is no outbox. The old inline shape lost the row on every one of
 * these requests, so this is strictly better, but it is not durable.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {}

    /**
     * @param what short, stable description of the side write, used verbatim in log lines
     * @param context supplier of the reconstruction payload: enough to rebuild the row by hand
     *     from the log line. Only evaluated on the discard and failure paths.
     * @param action the side write. Its outcome must never be relied on by the caller.
     */
    public static void run(String what, Supplier<String> context, Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runGuarded(what, context, action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    private boolean ran;

                    @Override
                    public void afterCommit() {
                        ran = true;
                        runGuarded(what, context, action);
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (ran) {
                            return;
                        }
                        ran = true;
                        onCompletionWithoutAfterCommit(what, context, action, status);
                    }
                });
    }

    /**
     * Package-private so the status branches can be tested directly. Reached when {@code
     * afterCommit} did not fire: the transaction rolled back, the outcome is unknown, or the
     * synchronization was registered from inside another synchronization's {@code afterCommit}.
     */
    static void onCompletionWithoutAfterCommit(
            String what, Supplier<String> context, Runnable action, int status) {
        switch (status) {
            case TransactionSynchronization.STATUS_COMMITTED ->
                    // Registered from inside another synchronization's afterCommit; see the class
                    // javadoc. triggerAfterCompletion re-snapshots, so this is the callback we get.
                    runGuarded(what, context, action);
            case TransactionSynchronization.STATUS_ROLLED_BACK ->
                    log.warn(
                            "DISCARDED {}: the business transaction rolled back, so the event it"
                                    + " records did not happen. This is deliberate. Context: {}",
                            what,
                            safeContext(context));
            default ->
                    log.error(
                            "WITHHELD {}: the business transaction finished with an UNKNOWN outcome"
                                    + " (completion status {}). Its commit may or may not have"
                                    + " landed, and the caller was given an error. This row was NOT"
                                    + " written. Check whether the business change is in the database;"
                                    + " if it is, insert this row by hand. Context: {}",
                            what,
                            status,
                            safeContext(context));
        }
    }

    private static void runGuarded(String what, Supplier<String> context, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error(
                    "LOST {}: the business transaction it belongs to already committed and is NOT"
                            + " affected, but this row could not be written and there is no retry."
                            + " Reconstruct from: {}",
                    what,
                    safeContext(context),
                    e);
        }
    }

    /** A context supplier that throws must not turn a log line into a lost log line. */
    private static String safeContext(Supplier<String> context) {
        try {
            return context.get();
        } catch (RuntimeException e) {
            return "<context unavailable: " + e.getClass().getSimpleName() + ">";
        }
    }
}
