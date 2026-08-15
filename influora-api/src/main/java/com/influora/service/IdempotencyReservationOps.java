package com.influora.service;

import com.influora.domain.entity.IdempotencyKeyRecord;
import com.influora.repository.IdempotencyKeyRecordRepository;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * [SEC: Vikram, BL-3 round-4 fix — Priya's ambient-transaction finding] Extracted out of {@link
 * IdempotencyService} into its OWN Spring bean so {@code @Transactional(REQUIRES_NEW)} on the
 * three methods below is a REAL proxy boundary, not a self-invocation no-op.
 *
 * <p><b>Why the previous shape was broken.</b> {@code tryReserveTransactional} /
 * {@code markCompletedTransactional} / {@code markFailedTransactional} used to live directly on
 * {@code IdempotencyService} and were called as {@code this.tryReserveTransactional(...)} from
 * {@code executeOnce}/{@code runExclusive} on the SAME instance. Spring's proxy-based
 * {@code @Transactional} only intercepts calls that arrive THROUGH the proxy (i.e. a caller
 * holding an injected reference to the bean) — a call from inside the bean to its own method
 * bypasses the proxy entirely (Spring's documented self-invocation limitation), so
 * {@code @Transactional(REQUIRES_NEW)} on those methods was a no-op annotation.
 *
 * <p>For a caller with NO ambient transaction (the persistence test's original setup, and a
 * handful of real callers), this was invisible: each {@code repository.save()} /
 * {@code repository.markCompleted()} call is itself a Spring Data repository method carrying its
 * own {@code @Transactional} (either the framework default on {@code SimpleJpaRepository}, or the
 * explicit one on this repository's {@code @Modifying} queries), so with no surrounding
 * transaction each such call opened and committed its OWN transaction — accidentally correct.
 *
 * <p>It broke for real, however, for the (at least) 6 real production callers that invoke {@code
 * executeOnce}/{@code runExclusive} from INSIDE their own {@code @Transactional} method —
 * {@code WalletService#requestCreatorWithdrawal}, {@code DealService#accept}/{@code #reject},
 * {@code ContractService#recordSignature}/{@code #recordSignatureForCreator}, {@code
 * AICreditService#release}. There, {@code repository.save()}'s own (REQUIRED-propagation, the
 * Spring Data default) transaction simply JOINS the caller's already-active ambient transaction
 * instead of opening a new one — so the INSERT is not executed synchronously at all; Hibernate
 * defers it to the ambient transaction's own flush, which happens at COMMIT time, long after
 * {@code tryReserveTransactional}'s {@code try/catch} block has already returned. The
 * {@code catch (DataIntegrityViolationException)} therefore never fires at reservation time — the
 * method always returns {@code true}, the guarded action always runs, and a real duplicate only
 * surfaces as a raw, uncaught {@code DataIntegrityViolationException} when the AMBIENT transaction
 * commits — bypassing every caller's {@code AlreadyInProgressException}/{@code
 * AlreadyCompletedException} catch block entirely (see {@code
 * IdempotencyServicePersistenceTest#ambientTransactionCallerStillGetsRealReservationSemantics}
 * for the reproduction).
 *
 * <p><b>The fix.</b> Moving these three operations to a SEPARATE bean means
 * {@code IdempotencyService} now calls them through an INJECTED reference — a genuine proxy call
 * — so {@code @Transactional(REQUIRES_NEW)} is honored: each call here suspends whatever ambient
 * transaction the caller is in (if any) and runs in a brand-new, independent transaction that
 * commits (or rolls back) before the method returns, regardless of what the caller's own
 * transaction later does.
 *
 * <p><b>Why {@code saveAndFlush}, not {@code save}, in {@link #tryReserve}.</b> A REQUIRES_NEW
 * transaction is still only a transaction BOUNDARY — by itself it does not force Hibernate to
 * execute the INSERT synchronously inside this method's try/catch. With a manually-assigned
 * {@code @Id} (no {@code IDENTITY} generation to force an immediate round-trip), plain
 * {@code save()} → {@code entityManager.persist()} only SCHEDULES the insert; Hibernate is free
 * to defer the actual SQL to flush time, which for a plain {@code @Transactional} method is the
 * proxy's own commit — AFTER the target method has already returned {@code true} from the try
 * block, so the constraint violation would again go uncaught (just one layer further out than the
 * original bug). {@code saveAndFlush()} forces the flush — and therefore the INSERT, and
 * therefore any {@code DataIntegrityViolationException} — to happen synchronously, inside this
 * method, inside the try block, before the REQUIRES_NEW transaction commits. Both fixes are
 * required together: REQUIRES_NEW-via-separate-bean makes the reservation's commit independent of
 * the caller's ambient transaction; {@code saveAndFlush} makes the constraint check happen
 * synchronously so THIS method's own catch block (not some later, uncontrolled commit) is what
 * translates it into a clean {@code false} return.
 *
 * <p><b>Why the catch block also calls {@code setRollbackOnly()} explicitly — the third piece,
 * found empirically, not by inspection.</b> Catching {@code DataIntegrityViolationException} from
 * {@code saveAndFlush} is NOT by itself enough to make {@link #tryReserve} return {@code false}
 * cleanly. Hibernate marks the underlying {@code Session}/persistence context (and, via {@code
 * JpaTransactionManager}'s participation, the ambient {@code EntityManagerHolder}) rollback-only
 * THE MOMENT the flush fails — beneath the JPA/Spring exception-translation layer, independent of
 * whether application code goes on to catch the translated exception. So even though {@code
 * tryReserve}'s {@code try/catch} catches the exception and returns {@code false} normally, the
 * surrounding {@code @Transactional(REQUIRES_NEW)} proxy still sees a transaction whose {@code
 * isGlobalRollbackOnly()} is {@code true} when the method returns and the proxy attempts to
 * COMMIT — Spring's {@code AbstractPlatformTransactionManager} treats a commit attempt against a
 * globally-rollback-only transaction as an error condition and throws {@link
 * org.springframework.transaction.UnexpectedRollbackException}, which then propagates out of
 * {@code tryReserve} instead of the clean {@code false} return — a THIRD exception shape (neither
 * {@code DataIntegrityViolationException} nor {@link IdempotencyService.AlreadyCompletedException}
 * / {@link IdempotencyService.AlreadyInProgressException}), reproduced empirically by {@code
 * IdempotencyServicePersistenceTest#ambientTransactionCallerStillGetsRealReservationSemantics}
 * before this line was added. Explicitly calling {@code
 * TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()} converts this from an
 * "unexpected" global rollback into an explicit LOCAL one — Spring's commit logic special-cases a
 * transaction whose {@code TransactionStatus} was deliberately marked rollback-only by the
 * application itself: it rolls back silently instead of throwing, and the {@code false} this
 * method returns propagates to the caller exactly as intended. This is safe specifically because
 * {@code tryReserve} runs in ITS OWN {@code REQUIRES_NEW} transaction — rolling it back only
 * discards the failed reservation attempt (nothing else was written in it), and has no effect on
 * whatever ambient transaction the caller is in.
 */
@Component
public class IdempotencyReservationOps {

    private final IdempotencyKeyRecordRepository repository;

    public IdempotencyReservationOps(IdempotencyKeyRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryReserve(String idempotencyKey, String workspaceId, String scope) {
        try {
            repository.saveAndFlush(
                    IdempotencyKeyRecord.builder()
                            .idempotencyKey(idempotencyKey)
                            .workspaceId(workspaceId)
                            .scope(scope)
                            .build());
            return true;
        } catch (DataIntegrityViolationException alreadyExists) {
            // See class javadoc "Why the catch block also calls setRollbackOnly() explicitly" --
            // without this, Hibernate has already marked this REQUIRES_NEW transaction's
            // underlying resource rollback-only beneath us, and the proxy's subsequent commit
            // attempt throws UnexpectedRollbackException instead of letting this clean `false`
            // return propagate.
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (NoTransactionException noAopManagedTransaction) {
                // Only reachable when this bean is constructed directly (`new
                // IdempotencyReservationOps(repository)`) instead of resolved through Spring --
                // e.g. IdempotencyServiceTest/SubscriptionServiceTest, which unit-test
                // IdempotencyService's branching logic against a fully-mocked repository and have
                // no real Hibernate/transaction underneath to mark rollback-only in the first
                // place. Safe to ignore: in production this bean is always the Spring-proxied
                // singleton, and @Transactional(REQUIRES_NEW) always provides a real
                // TransactionStatus here (proved by
                // IdempotencyServicePersistenceTest#ambientTransactionCallerStillGetsRealReservationSemantics,
                // which runs against a real DataJpaTest-managed bean).
            }
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(String idempotencyKey, String resultDigest) {
        repository.markCompleted(
                idempotencyKey, IdempotencyKeyRecord.Status.COMPLETED, resultDigest, Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String idempotencyKey) {
        repository.markFailed(idempotencyKey, IdempotencyKeyRecord.Status.FAILED, Instant.now());
    }

    /** [BL-3] {@code runExclusive}'s success path — deletes the reservation outright rather than marking it COMPLETED, so the key is immediately reusable by the next non-concurrent caller. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String idempotencyKey) {
        repository.deleteById(idempotencyKey);
    }

    /**
     * [SEC: Vikram, BL-3 round-5 fix — Priya's fresh review] Reclaims a {@code FAILED}
     * reservation back to {@code IN_PROGRESS} so a caller retrying the SAME key can safely
     * re-run the guarded effect. Moved here, behind {@code REQUIRES_NEW} via this bean's real
     * Spring proxy, for the exact same reason {@link #tryReserve}/{@link #markCompleted}/{@link
     * #markFailed}/{@link #release} live here and not on {@code IdempotencyService} — see this
     * class's javadoc for the general ambient-transaction defect shape.
     *
     * <p><b>Why this one mattered even though the other four were already fixed in round 4.</b>
     * {@code IdempotencyService} continued to call {@code repository.reclaimFailedForRetry(...)}
     * directly — NOT through this bean — from {@code executeOnce}/{@code runExclusive}'s
     * not-reserved branch. {@code IdempotencyKeyRecordRepository#reclaimFailedForRetry} itself
     * carries a bare {@code @Transactional} (REQUIRED propagation, the Spring Data default):
     * called directly from inside a caller's OWN ambient transaction (e.g. {@code
     * WalletService#requestCreatorWithdrawal} retrying a FAILED key, since {@code
     * creator-wallet.tsx} deliberately reuses the same idempotency key across withdrawal
     * retries), it JOINS that ambient transaction instead of running independently, and its
     * row-level write lock is then held for the ambient transaction's ENTIRE remaining
     * lifetime — not released when the repository call returns. {@link #markCompleted}/{@link
     * #markFailed} then try to update that SAME row from a second, independent {@code
     * REQUIRES_NEW} transaction/connection — a genuine deadlock between the ambient
     * transaction's held lock and the REQUIRES_NEW transaction's wait for it. Proved empirically
     * with a real H2 probe: a {@link org.springframework.dao.PessimisticLockingFailureException}
     * after the lock-wait timeout; MySQL's default 50s {@code innodb_lock_wait_timeout} would
     * instead hold an HTTP request thread and its pooled connection for that long in production
     * before failing the same way.
     *
     * <p>Routing the reclaim through this bean's own {@code REQUIRES_NEW}, exactly like the
     * other four operations, closes the gap: every write to the idempotency row now happens
     * inside THIS bean's own short-lived transaction, and the caller's ambient transaction never
     * touches the row directly — the invariant the other four operations already held.
     *
     * @return {@code 1} if this caller won the reclaim, {@code 0} otherwise — see {@link
     *     IdempotencyKeyRecordRepository#reclaimFailedForRetry} for the full contract
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reclaimFailedForRetry(String idempotencyKey) {
        return repository.reclaimFailedForRetry(
                idempotencyKey, IdempotencyKeyRecord.Status.FAILED, IdempotencyKeyRecord.Status.IN_PROGRESS);
    }
}
