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

    @Column(name = "milestone_id", nullable = false, length = 26)
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

    protected Payout() {}

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
