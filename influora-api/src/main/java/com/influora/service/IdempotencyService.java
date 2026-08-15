package com.influora.service;

import com.influora.domain.entity.IdempotencyKeyRecord;
import com.influora.repository.IdempotencyKeyRecordRepository;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

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
 *
 * <p><b>[SEC: Vikram, BL-3 round-4 fix] Reservation/completion writes now go through {@link
 * IdempotencyReservationOps}, a separate bean, not self-invoked methods on this class.</b> See
 * that class's javadoc for the full ambient-transaction defect Priya's round-4 review found and
 * why a separate bean (not just adding {@code REQUIRES_NEW}) was required to fix it — in short,
 * {@code @Transactional} on a method this class called on itself ({@code this.foo(...)}) was a
 * no-op (Spring's self-invocation limitation), which meant callers that invoke {@code
 * executeOnce}/{@code runExclusive} from inside their OWN {@code @Transactional} method (e.g.
 * {@code WalletService#requestCreatorWithdrawal}) never actually got the insert-first-wins
 * guarantee at reservation time.
 *
 * <p><b>[SEC: Vikram, BL-3 round-5 fix — Priya's fresh review] {@code reclaimFailedForRetry} now
 * goes through {@link IdempotencyReservationOps} too, not {@code repository} directly.</b> Round
 * 4 moved {@code tryReserve}/{@code markCompleted}/{@code markFailed}/{@code release} onto that
 * bean but missed this fifth write — {@code executeOnce}/{@code runExclusive} kept calling {@code
 * repository.reclaimFailedForRetry(...)} directly, which carries its own bare {@code
 * @Transactional} (REQUIRED propagation) and so, called from inside a caller's ambient
 * transaction, JOINED it and held the row's write lock for that transaction's full lifetime —
 * deadlocking against {@code markCompleted}/{@code markFailed}'s independent {@code
 * REQUIRES_NEW} update to the SAME row moments later. Every write to the idempotency row now
 * happens inside {@link IdempotencyReservationOps}'s own short-lived {@code REQUIRES_NEW}
 * transaction — the caller's ambient transaction never touches the row directly. See {@link
 * IdempotencyReservationOps#reclaimFailedForRetry} for the full defect writeup. {@code
 * repository.findByIdempotencyKey}-style plain reads stay on {@code repository} directly — a
 * non-locking SELECT has nothing to deadlock against and doesn't need this bean's transactional
 * boundary.
 */
@Service
public class IdempotencyService {

    /**
     * [SEC: Vikram, round-4 Finding 3] {@code idempotency_keys.idempotency_key} is {@code
     * VARCHAR(128)} (V15) and this is the fully-composited primary-key VALUE ({@code scope +
     * workspaceId + idempotencyKey}, see {@link #compositeKey}) — not just the caller-supplied raw
     * token. At least one caller ({@code WalletService#requestCreatorWithdrawal}) namespaces the
     * key twice before it ever reaches here ({@code "wallet.withdraw:" + userId + ":" +
     * "creator-withdraw:" + userId + ":" + <client header>}), which alone consumes ~88 of the 128
     * bytes before the client-supplied portion is even added. Validating up front, in code, turns
     * a silent DB-level "value too long" failure (which the {@code tryReserve} catch below cannot
     * safely distinguish from a genuine duplicate-key collision — see {@link
     * IdempotencyReservationOps} — into a clear, typed {@link IllegalArgumentException} at the
     * point of the call, instead of a misclassified "already in progress" response.
     */
    private static final int MAX_RESERVATION_KEY_LENGTH = 128;

    private final IdempotencyKeyRecordRepository repository;
    private final IdempotencyReservationOps reservationOps;

    public IdempotencyService(
            IdempotencyKeyRecordRepository repository, IdempotencyReservationOps reservationOps) {
        this.repository = repository;
        this.reservationOps = reservationOps;
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
        validateReservationKeyLength(reservationKey);
        boolean reserved = reservationOps.tryReserve(reservationKey, workspaceId, scope);
        if (!reserved) {
            // [SEC: Kabir, E2 HIGH-1 -- fixed] A prior attempt that threw mid-effect leaves the row
            // FAILED, not terminal -- atomically reclaim it back to IN_PROGRESS (the UPDATE's WHERE
            // clause is itself the concurrency arbiter for the reclaim race) so this call can retry
            // the effect, instead of every future retry being permanently rejected as
            // "AlreadyInProgress" for a run that will never actually complete.
            boolean reclaimed = reservationOps.reclaimFailedForRetry(reservationKey) == 1;
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
            reservationOps.markCompleted(
                    reservationKey, resultDigestFn == null ? null : resultDigestFn.apply(result));
            return result;
        } catch (RuntimeException ex) {
            reservationOps.markFailed(reservationKey);
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
     * True if {@code idempotencyKey} (scoped to {@code workspaceId}/{@code scope}) has a COMPLETED
     * row — i.e. {@link #executeOnce} already ran that effect to success for this exact key.
     * Unlike {@link #findCompletedResultDigest}, this does NOT require a digest to have been
     * captured, so it's the right check for a caller using {@code executeOnce} purely as a
     * completion ledger with no result to replay (e.g. {@code AICreditService}'s per-turn
     * charge/release markers — {@code findCompletedResultDigest} would incorrectly return {@code
     * Optional.empty()} for those since they're written via the no-digest overload).
     */
    public boolean isCompleted(String idempotencyKey, String workspaceId, String scope) {
        return repository
                .findByIdempotencyKey(compositeKey(idempotencyKey, workspaceId, scope))
                .map(record -> record.getStatus() == IdempotencyKeyRecord.Status.COMPLETED)
                .orElse(false);
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
     * [SEC: Vikram, round-4 Finding 3] Rejects up front, with a clear typed exception, a composite
     * key that cannot possibly fit {@code idempotency_keys.idempotency_key VARCHAR(128)} — instead
     * of letting it reach {@link IdempotencyReservationOps#tryReserve}, where a DB-level "value too
     * long" error is a {@link org.springframework.dao.DataIntegrityViolationException} exactly like
     * a genuine duplicate-key collision and cannot be cheaply/reliably told apart from one via JPA's
     * exception translation (Hibernate collapses both into the same Spring exception type — see
     * that class's javadoc). Validating length here, on the fully-composed key, closes the practical
     * risk regardless of that ambiguity: a too-long key is refused before any DB call is made, so
     * the ambiguous-catch scenario becomes unreachable for this cause. Deliberately conservative
     * (composite length, not just the caller-supplied raw token) since {@code scope}/{@code
     * workspaceId} prefixes are also attacker/caller-influenced in some call sites (e.g. {@code
     * WalletService} folds a client-supplied header into both {@code scope} and the raw key).
     */
    private static void validateReservationKeyLength(String reservationKey) {
        if (reservationKey.length() > MAX_RESERVATION_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency reservation key exceeds "
                            + MAX_RESERVATION_KEY_LENGTH
                            + " chars ("
                            + reservationKey.length()
                            + "); refusing rather than risk a DB-level truncation/length error being"
                            + " misclassified as a duplicate-key collision");
        }
    }

    /**
     * [BL-3 fix, BrandF.md §99] Mutual-exclusion variant of {@link #executeOnce}: reserves {@code
     * idempotencyKey} (scoped to {@code workspaceId}/{@code scope}) exactly like {@code
     * executeOnce} — same insert-first-wins DB-UNIQUE-constraint arbiter, same FAILED-is-reclaimable
     * retry path — but, unlike {@code executeOnce}, NEVER leaves the reservation {@code COMPLETED}
     * forever. On success the row is deleted outright, so the very next (non-concurrent) call with
     * the same key is free to proceed again.
     *
     * <p>Use this instead of {@code executeOnce} when the guarded effect legitimately needs to be
     * callable again later by the SAME caller once the current call has finished — {@code
     * executeOnce}'s permanent-COMPLETED contract is right for a dedupe-forever ledger (webhook
     * deliveries, tool calls) but wrong for something like a payment-provider checkout call, where a
     * workspace that completes one checkout episode (or cancels and later re-subscribes) must be
     * able to initiate a brand-new one — see {@code SubscriptionService#initiateCheckout}'s BL-3 fix
     * javadoc for the concrete scenario a static {@code executeOnce} key would have broken.
     *
     * <p>A concurrent second caller for the SAME key while {@code action} is still running sees the
     * reservation row still present ({@code IN_PROGRESS}) and is rejected with {@link
     * AlreadyInProgressException} — so exactly one concurrent caller's {@code action} ever runs for
     * a given key at a time. A row left {@code FAILED} (the guarded action threw) is reclaimed
     * exactly like {@code executeOnce} (see that method's javadoc), so a genuinely failed attempt —
     * including one abandoned mid-flight by a crashed process, via {@code
     * IdempotencyReservationReaperJob}'s generic stale-{@code IN_PROGRESS} sweep — can always be
     * retried, never permanently stuck.
     *
     * <p>Because a row is never marked {@code COMPLETED} here, {@link #findCompletedResultDigest}
     * and {@link #isCompleted} never resolve anything for a key only ever used through this method —
     * callers needing result-digest replay should use {@link #executeOnce} instead.
     */
    public <T> T runExclusive(String idempotencyKey, String workspaceId, String scope, Supplier<T> action) {
        String reservationKey = compositeKey(idempotencyKey, workspaceId, scope);
        validateReservationKeyLength(reservationKey);
        boolean reserved = reservationOps.tryReserve(reservationKey, workspaceId, scope);
        if (!reserved) {
            boolean reclaimed = reservationOps.reclaimFailedForRetry(reservationKey) == 1;
            if (!reclaimed) {
                throw new AlreadyInProgressException(idempotencyKey);
            }
        }

        try {
            T result = action.get();
            reservationOps.release(reservationKey);
            return result;
        } catch (RuntimeException ex) {
            reservationOps.markFailed(reservationKey);
            throw ex;
        }
    }
}
