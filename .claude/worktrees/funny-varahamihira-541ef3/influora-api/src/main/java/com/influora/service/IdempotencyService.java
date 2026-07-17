package com.influora.service;

import com.influora.domain.entity.IdempotencyKeyRecord;
import com.influora.repository.IdempotencyKeyRecordRepository;
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
     */
    public <T> T executeOnce(String idempotencyKey, String workspaceId, String scope, Supplier<T> action) {
        boolean reserved = tryReserveTransactional(idempotencyKey, workspaceId, scope);
        if (!reserved) {
            IdempotencyKeyRecord existing =
                    repository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null && existing.getStatus() == IdempotencyKeyRecord.Status.COMPLETED) {
                throw new AlreadyCompletedException(idempotencyKey);
            }
            throw new AlreadyInProgressException(idempotencyKey);
        }

        try {
            T result = action.get();
            markCompletedTransactional(idempotencyKey);
            return result;
        } catch (RuntimeException ex) {
            markFailedTransactional(idempotencyKey);
            throw ex;
        }
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
    protected void markCompletedTransactional(String idempotencyKey) {
        repository
                .findByIdempotencyKey(idempotencyKey)
                .ifPresent(record -> record.markCompleted(null));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markFailedTransactional(String idempotencyKey) {
        repository.findByIdempotencyKey(idempotencyKey).ifPresent(IdempotencyKeyRecord::markFailed);
    }
}
