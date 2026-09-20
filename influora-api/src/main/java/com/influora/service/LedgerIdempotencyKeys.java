package com.influora.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * [EV-014] The one place that builds a ledger/payout idempotency key out of a caller-supplied
 * token, and the one place that states the column widths every such key has to fit.
 *
 * <p><b>The defect this exists to close.</b> {@code WalletService#requestCreatorWithdrawal} used to
 * build its key by string concatenation — {@code "creator-withdraw:" + userId + ":" + <the client's
 * Idempotency-Key header>}. With a 26-char ULID user id and the UUID the frontend sends, that is
 * {@code 17 + 26 + 1 + 36 = 80} characters, and it is written to THREE different length-bounded
 * places, every one of which it overflows:
 *
 * <ul>
 *   <li>{@code wallet_transactions.idempotency_key} — {@code VARCHAR(64)}
 *       (V8__wallet_transactions.sql:15, {@code WalletTransaction} {@code @Column(length = 64)}),
 *       and {@link WalletLedgerService#post} appends {@code ":D"}/{@code ":C"} to it before writing
 *       either leg, so the real budget is 62.
 *   <li>{@code payouts.idempotency_key} — {@code VARCHAR(64)} (V48__payouts.sql:13, {@code Payout}
 *       {@code @Column(length = 64)}).
 *   <li>The RazorpayX payout {@code reference_id} — {@code RazorpayXClient#initiatePayout} sends
 *       the same key as {@code reference_id} and as the {@code X-Payout-Idempotency} header.
 *       RazorpayX documents {@code reference_id} as at most {@value
 *       #RAZORPAYX_REFERENCE_ID_MAX_LENGTH} characters, a limit no migration of ours can widen —
 *       which is why this class hashes the key instead of widening the two columns.
 * </ul>
 *
 * <p>Against real MySQL in strict mode that is a hard {@code Data too long} on the FIRST creator
 * withdrawal; in non-strict mode it is worse, because the {@code :D}/{@code :C} suffix is what gets
 * truncated away, both legs of the double entry collapse onto the same stored key, and {@code
 * uq_wtx_idem} rejects the posting. Either way no creator withdrawal can ever complete.
 *
 * <p><b>The fix.</b> The same shape {@code PayoutReconciliationService#retryDebitKey} already uses
 * for the same reason (its own {@code VARCHAR(64)} overflow, red-team F2): a short fixed-width
 * scope prefix plus a truncated SHA-256 digest of the identifying parts. Deterministic — the same
 * {@code (userId, clientKey)} always produces the same key, so replay-by-key and the orphan
 * reaper's recompute-from-persisted-state both still work — and bounded by construction, so no
 * caller-supplied token length can ever move it.
 *
 * <p>The digest is over {@code userId + ":" + clientKey}, never {@code clientKey} alone: two
 * different creators submitting the same client UUID must not collide on one key. The prefix stays
 * in the clear (rather than being hashed too) so a human reading {@code wallet_transactions} can
 * still tell what kind of movement a row belongs to.
 */
public final class LedgerIdempotencyKeys {

    private LedgerIdempotencyKeys() {}

    /**
     * {@code wallet_transactions.idempotency_key VARCHAR(64)} — V8__wallet_transactions.sql:15.
     * Asserted against both the migration text and the JPA {@code @Column(length)} by {@code
     * LedgerIdempotencyKeyLengthTest}, so this constant cannot drift away from the schema.
     */
    public static final int WALLET_TRANSACTION_KEY_MAX_LENGTH = 64;

    /**
     * {@link WalletLedgerService#post} writes {@code idempotencyKey + ":D"} on the debit leg and
     * {@code idempotencyKey + ":C"} on the credit leg (WalletLedgerService.java:159, :178), so
     * every key handed to it has two characters less room than the column width suggests.
     */
    public static final int LEDGER_LEG_SUFFIX_LENGTH = 2;

    /** {@code payouts.idempotency_key VARCHAR(64)} — V48__payouts.sql:13. */
    public static final int PAYOUT_KEY_MAX_LENGTH = 64;

    /**
     * RazorpayX Payouts API {@code reference_id} maximum length. Not enforceable from this
     * repository — it is the gateway's own documented limit, recorded here as the tightest of the
     * three budgets rather than discovered in production. Every key this class produces is sized to
     * fit it, which makes the exact value non-load-bearing: the keys are well under it.
     */
    public static final int RAZORPAYX_REFERENCE_ID_MAX_LENGTH = 40;

    /**
     * Hex characters of SHA-256 kept. 32 hex chars = 128 bits, the same width {@code
     * PayoutReconciliationService#RETRY_KEY_DIGEST_LENGTH} settled on; collision probability across
     * every key this platform will ever mint is negligible, and the ledger's own {@code UNIQUE}
     * constraint fails loudly rather than silently double-spending if one ever occurred.
     */
    public static final int DIGEST_LENGTH = 32;

    /** Creator wallet withdrawal — {@code WalletService#requestCreatorWithdrawal}. 4 + 32 = 36. */
    public static final String CREATOR_WITHDRAWAL_PREFIX = "cwd:";

    /**
     * Admin-recorded out-of-band bank payout — {@code AdminFinanceService#recordManualPayout}.
     * 5 + 32 = 37. That path had the SAME defect and is the more urgent of the two in practice: it
     * took the {@code Idempotency-Key} header verbatim into both {@code
     * wallet_transactions.idempotency_key} and {@code payouts.idempotency_key} with no bound at
     * all, and (EV-020) it is the only payout rail switched on today.
     */
    public static final String MANUAL_PAYOUT_PREFIX = "mpay:";

    /**
     * The key for one creator withdrawal attempt. Written to {@code
     * wallet_transactions.idempotency_key} (via {@link WalletLedgerService#post}), to {@code
     * payouts.idempotency_key}, and sent to RazorpayX as {@code reference_id}.
     */
    public static String creatorWithdrawal(String userId, String clientIdempotencyKey) {
        return scopedDigest(CREATOR_WITHDRAWAL_PREFIX, userId, clientIdempotencyKey);
    }

    /** The key for one admin-recorded manual bank payout. Same three destinations. */
    public static String manualPayout(String creatorUserId, String clientIdempotencyKey) {
        return scopedDigest(MANUAL_PAYOUT_PREFIX, creatorUserId, clientIdempotencyKey);
    }

    /**
     * {@code prefix} followed by the first {@link #DIGEST_LENGTH} hex characters of the SHA-256 of
     * the {@code :}-joined parts. {@code SHA-256} is a JCA algorithm every JVM is required to
     * provide, so {@link NoSuchAlgorithmException} is not a runtime condition callers should have
     * to declare — wrapped unchecked, exactly as {@code PayoutReconciliationService} does.
     */
    public static String scopedDigest(String prefix, String... parts) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(String.join(":", parts).getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(hash).substring(0, DIGEST_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
        }
    }
}
