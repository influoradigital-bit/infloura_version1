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

    public String getId() {
        return id;
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
