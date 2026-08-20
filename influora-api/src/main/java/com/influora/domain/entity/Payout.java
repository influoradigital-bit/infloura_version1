package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * RazorpayX payout tracking (P2-12). Records every payout initiated via {@link
 * com.influora.service.PayoutService}, reconciled via webhook in {@code confirmExecuted}.
 *
 * <p>Status values mirror RazorpayX payout states: {@code queued}, {@code pending}, {@code
 * processing}, {@code processed}, {@code reversed}, {@code cancelled}, {@code rejected}.
 */
@Entity
@Table(name = "payouts")
public class Payout {

    @Id
    @Column(length = 26)
    private String id;

    /**
     * [B10] Nullable — {@code WalletService.requestCreatorWithdrawal} persists a {@code Payout} row
     * for a lump-sum wallet withdrawal that is not tied to any single {@code payment_milestones}
     * row (unlike {@code PayoutService.queuePayout}'s milestone-linked payouts, which always set
     * this). See {@code V20260715180000__payout_milestone_nullable.sql}.
     */
    @Column(name = "milestone_id", length = 26)
    private String milestoneId;

    @Column(name = "creator_user_id", nullable = false, length = 26)
    private String creatorUserId;

    @Column(name = "razorpay_payout_id", nullable = false, unique = true, length = 64)
    private String razorpayPayoutId;

    @Column(name = "fund_account_id", nullable = false, length = 64)
    private String fundAccountId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /**
     * RazorpayX payout status: {@code queued}, {@code pending}, {@code processing}, {@code
     * processed}, {@code reversed}, {@code cancelled}, {@code rejected}.
     */
    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(name = "webhook_payload", columnDefinition = "TEXT")
    private String webhookPayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** {@link #METHOD_GATEWAY} or {@link #METHOD_MANUAL} — V71. */
    @Column(name = "payout_method", nullable = false, length = 16)
    private String payoutMethod = METHOD_GATEWAY;

    /** Bank UTR / transaction reference. Non-null only for {@link #METHOD_MANUAL} rows — V71. */
    @Column(name = "bank_reference", length = 64)
    private String bankReference;

    /**
     * TDS deducted at source, in the payout currency — V71. {@code null} means no TDS was applied;
     * {@code 0.00} means it was considered and none was due. The two are different statements and
     * the column keeps them distinct on purpose.
     */
    @Column(name = "tds_amount", precision = 12, scale = 2)
    private BigDecimal tdsAmount;

    /**
     * [P1 fix, SEC: Kabir 2b -- orphaned-debit sweeper] Our own pre-gateway-call marker status --
     * deliberately UPPERCASE and distinct from every value RazorpayX itself ever reports (always
     * lowercase: {@code queued}/{@code pending}/{@code processing}/{@code processed}/{@code
     * reversed}/{@code cancelled}/{@code rejected}), so a sweep query can never accidentally match
     * a row RazorpayX has already put into its own "pending" state. Set the instant a payout intent
     * is persisted -- BEFORE the wallet debit and BEFORE the RazorpayX call -- so a crash/failure
     * anywhere in that window leaves a durable, sweep-able record instead of an invisible orphaned
     * debit. See {@code PayoutService#doQueuePayout} and {@code
     * PayoutReconciliationService#reconcileOrphanedPendingPayout}.
     */
    public static final String STATUS_PENDING = "PENDING";

    /**
     * Terminal status for a payout that a human made from the company bank account and recorded
     * afterwards (see {@link #createManualPaid}).
     *
     * <p>UPPERCASE for the same reason as {@link #STATUS_PENDING}: RazorpayX's own vocabulary is
     * always lowercase, so this can never be confused for a gateway-reported state by a sweep or
     * reconciliation query. It is deliberately NOT {@code "processed"} — that value means "the
     * gateway told us the money landed", and nothing here was told anything by a gateway. The
     * evidence for a manual payout is {@link #bankReference}, not a webhook.
     */
    public static final String STATUS_MANUAL_PAID = "MANUAL_PAID";

    /** {@link #payoutMethod} for a RazorpayX payout — the default for every pre-V71 row. */
    public static final String METHOD_GATEWAY = "GATEWAY";

    /** {@link #payoutMethod} for an out-of-band bank transfer recorded by an admin. */
    public static final String METHOD_MANUAL = "MANUAL";

    protected Payout() {}

    /**
     * [P1 fix] Persists the durable "a payout is about to happen" record BEFORE the wallet debit
     * or the RazorpayX call. {@code razorpayPayoutId} is not yet known (RazorpayX has not been
     * called), so a unique placeholder derived from this row's own id is stored instead and
     * overwritten by {@link #markGatewayConfirmed} once the gateway responds. On a reclaimed retry
     * (idempotency key was FAILED, caller calls {@code queuePayout} again), {@code PayoutService}
     * looks this row up by {@code idempotencyKey} and reuses it rather than calling this factory a
     * second time for the same key (the column is {@code UNIQUE}).
     */
    public static Payout createPending(
            String id,
            String milestoneId,
            String creatorUserId,
            String fundAccountId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            Instant now) {
        Payout p = new Payout();
        p.id = id;
        p.milestoneId = milestoneId;
        p.creatorUserId = creatorUserId;
        p.razorpayPayoutId = "pending:" + id;
        p.fundAccountId = fundAccountId;
        p.amount = amount;
        p.currency = currency;
        p.status = STATUS_PENDING;
        p.idempotencyKey = idempotencyKey;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    /**
     * [P1 fix] Transitions a {@link #createPending} row to its real RazorpayX identity/status once
     * the gateway call returns successfully. Deliberately NOT {@link #confirmStatus} -- that method
     * is reserved for the later, webhook-driven terminal-state transition and stamps {@code
     * confirmedAt}; this is only "the gateway accepted the request," not a confirmed final outcome.
     */
    public void markGatewayConfirmed(String razorpayPayoutId, String status) {
        this.razorpayPayoutId = razorpayPayoutId;
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public static Payout createQueued(
            String id,
            String milestoneId,
            String creatorUserId,
            String razorpayPayoutId,
            String fundAccountId,
            BigDecimal amount,
            String currency,
            String status,
            String idempotencyKey,
            Instant now) {
        Payout p = new Payout();
        p.id = id;
        p.milestoneId = milestoneId;
        p.creatorUserId = creatorUserId;
        p.razorpayPayoutId = razorpayPayoutId;
        p.fundAccountId = fundAccountId;
        p.amount = amount;
        p.currency = currency;
        p.status = status;
        p.idempotencyKey = idempotencyKey;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    /**
     * A payout that ALREADY HAPPENED, in a bank, recorded after the fact by an admin.
     *
     * <p>Unlike {@link #createPending}, this is not an intent — there is no gateway call to follow
     * and nothing to confirm later, so the row is terminal at creation. Three consequences, each
     * of which is load-bearing:
     *
     * <ul>
     *   <li>{@code confirmedAt} is stamped NOW. It cannot be left null: {@code
     *       PayoutRepository#sumAmountByCreatorUserIdAndConfirmedAtIsNull} treats a null
     *       {@code confirmedAt} as "still in flight to the bank" and it is what feeds the creator
     *       wallet's "Pending Payouts" tile. Leaving it null would tell a creator who has the money
     *       in their account that it is still on its way — permanently.
     *   <li>{@code status} is {@link #STATUS_MANUAL_PAID}, never {@link #STATUS_PENDING}, so the
     *       orphaned-debit sweeper ({@code findByStatusAndCreatedAtBefore}) can never pick this row
     *       up and "reclaim" a debit that corresponds to real money that really left the bank.
     *   <li>{@code razorpayPayoutId} carries a {@code manual:} prefix over this row's own id. The
     *       column is {@code NOT NULL UNIQUE} and no RazorpayX id exists, so a synthetic one is
     *       required; the prefix makes it unmistakably not-a-gateway-id to anyone reading the table.
     * </ul>
     */
    public static Payout createManualPaid(
            String id,
            String creatorUserId,
            BigDecimal amount,
            String currency,
            String bankReference,
            BigDecimal tdsAmount,
            String idempotencyKey,
            Instant now) {
        Payout p = new Payout();
        p.id = id;
        p.milestoneId = null;
        p.creatorUserId = creatorUserId;
        p.razorpayPayoutId = "manual:" + id;
        // NOT NULL on the column and there is no fund account — the destination is recorded as the
        // bank reference instead, which is the only thing that can be reconciled against a statement.
        p.fundAccountId = METHOD_MANUAL;
        p.amount = amount;
        p.currency = currency;
        p.status = STATUS_MANUAL_PAID;
        p.idempotencyKey = idempotencyKey;
        p.payoutMethod = METHOD_MANUAL;
        p.bankReference = bankReference;
        p.tdsAmount = tdsAmount;
        p.createdAt = now;
        p.updatedAt = now;
        p.confirmedAt = now;
        return p;
    }

    public String getId() {
        return id;
    }

    public String getPayoutMethod() {
        return payoutMethod;
    }

    public String getBankReference() {
        return bankReference;
    }

    public BigDecimal getTdsAmount() {
        return tdsAmount;
    }

    public String getMilestoneId() {
        return milestoneId;
    }

    public String getCreatorUserId() {
        return creatorUserId;
    }

    public String getRazorpayPayoutId() {
        return razorpayPayoutId;
    }

    public String getFundAccountId() {
        return fundAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getWebhookPayload() {
        return webhookPayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    /**
     * Webhook-driven state update ({@code PayoutService.confirmExecuted}) — flips status from
     * {@code queued}/{@code pending} to a terminal state ({@code processed}/{@code reversed}/etc).
     */
    public void confirmStatus(String newStatus, String rawWebhookPayload) {
        this.status = newStatus;
        this.webhookPayload = rawWebhookPayload;
        this.confirmedAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
