package com.influora.service;

import com.influora.domain.entity.IdempotencyKeyRecord;
import com.influora.repository.IdempotencyKeyRecordRepository;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generic dedupe-by-key helper (Domain E / [SEC: 3.1, LB-3]). {@code executeOnce} inserts the
 * idempotency-key row FIRST, in its own transaction, so the database's {@code UNIQUE} constraint
 * — not application logic — is the arbiter under concurrency: exactly one concurrent caller wins
 * the insert, every other caller (including a genuine retry) is told the key is already in
 * flight/settled and must not re-run the effect.
 *
 * <p>Tool-call executors under {@code service/meera/tool} additionally rely on
 * {@code meera_tool_calls.idempotency_key} (V14, its own {@code UNIQUE}) as the primary
 * tool-call ledger — this service is the shared building block for any write path (including
 * non-Meera ones) that wants the same guarantee without a bespoke table.
 *
 * <p><b>[SEC: Vikram, P3(c) fix] {@code scope}/{@code workspaceId} now actually scope the
 * reservation.</b> Previously {@code idempotency_keys.idempotency_key} (V15 {@code PRIMARY KEY})
 * was reserved using ONLY the caller-supplied {@code idempotencyKey} verbatim — {@code scope} and
 * {@code workspaceId} were persisted as plain (non-key) columns, metadata only, never actually
 * part of what the database's uniqueness guarantee arbitrated. Two callers in DIFFERENT scopes (or
 * different workspaces) that happened to submit the same raw token string would collide on the
 * SAME row — one caller's "already in progress/completed" state could shadow a completely
 * unrelated caller's key. The actual DB row is now reserved under a composite key — {@code
 * scope + ":" + workspaceId + ":" + idempotencyKey} (code-level only; {@code idempotency_keys
 * .idempotency_key} is already {@code VARCHAR(128)}, so this needed no schema change) — so scope
 * and workspace are genuine partitions of the keyspace, not just descriptive columns. The original
 * {@code workspaceId}/{@code scope} values are still stored as-is in their own columns (unchanged)
 * for indexing/inspection; only the PRIMARY KEY value changes shape. This is fully transparent to
 * every existing caller of {@link #executeOnce(String, String, String, Supplier)} — none of them
 * need to change what they pass in.
 */
@Service
public class IdempotencyService {

    private final IdempotencyKeyRecordRepository repository;

    public IdempotencyService(IdempotencyKeyRecordRepository repository) {
        this.repository = repository;
    }

    /** Thrown when the same key is currently in flight (concurrent double-submit) — callers should surface a 409/202-style "already processing" response, never retry the effect themselves. */
    public static final class AlreadyInProgressException extends RuntimeException {
        public AlreadyInProgressException(String key) {
            super("Idempotency key already in progress: " + key);
        }
    }

    /** Thrown when the same key already completed — the caller should return the prior result, not re-execute. */
    public static final class AlreadyCompletedException extends RuntimeException {
        public AlreadyCompletedException(String key) {
            super("Idempotency key already completed: " + key);
        }
    }

    /**
     * Reserves {@code key} for {@code scope} (insert-first, own transaction) then runs
     * {@code action}. On success, marks the row COMPLETED; on failure, marks it FAILED and
     * rethrows. If the key already exists, throws {@link AlreadyInProgressException} or
     * {@link AlreadyCompletedException} depending on the stored status — the caller is
     * responsible for mapping those to "return prior result" behavior using its own
     * domain-specific lookup (e.g. {@code MeeraToolCallRepository.findByIdempotencyKey}).
     *
     * <p>Equivalent to {@link #executeOnce(String, String, String, Supplier, Function)} with no
     * result-digest capture (stores {@code null} on completion).
     */
    public <T> T executeOnce(String idempotencyKey, String workspaceId, String scope, Supplier<T> action) {
        return executeOnce(idempotencyKey, workspaceId, scope, action, null);
    }

    /**
     * Same contract as {@link #executeOnce(String, String, String, Supplier)}, but additionally
     * runs {@code resultDigestFn} over the action's result on COMPLETED and persists the returned
     * string as {@code idempotency_keys.result_digest} — letting a caller that needs to replay the
     * EXACT prior result (not just "something was already done") look it back up via {@link
     * #findCompletedResultDigest} rather than falling back to an approximation like "the most
     * recent row in some other table" (see {@code MeeraSessionService#persistAssistantWriteback}
     * for why that approximation was unsafe). {@code resultDigestFn} may be {@code null} (no digest
     * captured, matching the 4-arg overload's behavior) but if non-null is only ever invoked for a
     * FRESH successful run, never for a replay.
     */
    public <T> T executeOnce(
            String idempotencyKey,
            String workspaceId,
            String scope,
            Supplier<T> action,
            Function<T, String> resultDigestFn) {
        String reservationKey = compositeKey(idempotencyKey, workspaceId, scope);
        boolean reserved = tryReserveTransactional(reservationKey, workspaceId, scope);
        if (!reserved) {
            // [SEC: Kabir, E2 HIGH-1 -- fixed] A prior attempt that threw mid-effect leaves the row
            // FAILED, not terminal -- atomically reclaim it back to IN_PROGRESS (the UPDATE's WHERE
            // clause is itself the concurrency arbiter for the reclaim race) so this call can retry
            // the effect, instead of every future retry being permanently rejected as
            // "AlreadyInProgress" for a run that will never actually complete.
            boolean reclaimed =
                    repository.reclaimFailedForRetry(
                                    reservationKey,
                                    IdempotencyKeyRecord.Status.FAILED,
                                    IdempotencyKeyRecord.Status.IN_PROGRESS)
                            == 1;
            if (!reclaimed) {
                IdempotencyKeyRecord existing = repository.findByIdempotencyKey(reservationKey).orElse(null);
                if (existing != null && existing.getStatus() == IdempotencyKeyRecord.Status.COMPLETED) {
                    throw new AlreadyCompletedException(idempotencyKey);
                }
                throw new AlreadyInProgressException(idempotencyKey);
            }
        }

        try {
            T result = action.get();
            markCompletedTransactional(reservationKey, resultDigestFn == null ? null : resultDigestFn.apply(result));
            return result;
        } catch (RuntimeException ex) {
            markFailedTransactional(reservationKey);
            throw ex;
        }
    }

    /**
     * Looks up the {@code result_digest} captured by a prior COMPLETED {@link
     * #executeOnce(String, String, String, Supplier, Function)} call for the exact same {@code
     * (idempotencyKey, workspaceId, scope)} triple — the composite-key partitioning means this can
     * never resolve a different workspace's or a different scope's row. Returns {@link
     * Optional#empty()} if the key is unknown, not yet COMPLETED, or was completed without a
     * digest function (e.g. via the 4-arg {@code executeOnce} overload).
     */
    public Optional<String> findCompletedResultDigest(String idempotencyKey, String workspaceId, String scope) {
        return repository
                .findByIdempotencyKey(compositeKey(idempotencyKey, workspaceId, scope))
                .filter(record -> record.getStatus() == IdempotencyKeyRecord.Status.COMPLETED)
                .map(IdempotencyKeyRecord::getResultDigest);
    }

    /**
     * [SEC: Vikram, P3(c)] Composes the ACTUAL reserved primary-key value — {@code scope} and
     * {@code workspaceId} are folded directly into the row identity rather than left as
     * non-participating metadata columns, so two callers can never collide across scopes or
     * workspaces even if they happen to submit the identical raw {@code idempotencyKey} string.
     * {@code workspaceId} is normalized to {@code ""} when {@code null} (many legitimate callers —
     * e.g. {@code ConversionTrackingService}'s legacy unscoped overload — have no workspace
     * identity at all) so the composite stays deterministic either way.
     */
    private static String compositeKey(String idempotencyKey, String workspaceId, String scope) {
        return scope + ":" + (workspaceId == null ? "" : workspaceId) + ":" + idempotencyKey;
    }

    /**
     * NOTE on Spring AOP self-invocation: {@code executeOnce} calls these three methods on
     * {@code this}, which bypasses the transactional proxy — {@code @Transactional} on a method
     * invoked this way is a no-op wrapper (Spring's well-documented self-invocation limitation),
     * so each call below actually runs without an explicit transaction demarcation. This does NOT
     * break the core correctness guarantee: {@code repository.save()} on a fresh row is a single
     * atomic INSERT statement, and the DB's {@code UNIQUE(idempotency_key)} constraint (V15) is
     * still what arbitrates concurrent double-submits, not this method's transaction boundary. The
     * {@code @Transactional(REQUIRES_NEW)} annotations are kept as documentation of intent and to
     * make this correct-by-construction if a future caller reaches these methods through an
     * injected proxy instead of {@code this} (e.g. by extracting them to a dedicated bean).
     * {@code reclaimFailedForRetry} (used directly from {@code executeOnce}, not via one of these
     * {@code this}-invoked helpers) is unaffected by this note — it is a genuine Spring Data
     * repository method, which Spring Data JPA gives its own default transactional wrapping to
     * independent of this class's self-invocation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected boolean tryReserveTransactional(String idempotencyKey, String workspaceId, String scope) {
        try {
            repository.save(
                    IdempotencyKeyRecord.builder()
                            .idempotencyKey(idempotencyKey)
                            .workspaceId(workspaceId)
                            .scope(scope)
                            .build());
            return true;
        } catch (DataIntegrityViolationException alreadyExists) {
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markCompletedTransactional(String idempotencyKey, String resultDigest) {
        repository
                .findByIdempotencyKey(idempotencyKey)
                .ifPresent(record -> record.markCompleted(resultDigest));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markFailedTransactional(String idempotencyKey) {
        repository.findByIdempotencyKey(idempotencyKey).ifPresent(IdempotencyKeyRecord::markFailed);
    }
}
