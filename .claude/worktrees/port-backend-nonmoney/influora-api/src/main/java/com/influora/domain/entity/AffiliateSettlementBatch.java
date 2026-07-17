package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One monthly affiliate settlement run (V27 {@code affiliate_settlement_batches}) -- Wave D task D4
 * (wiki/tech/REMAINING_WORK_PLAN.md). {@link com.influora.job.AffiliateSettlementJob} creates one of
 * these per {@code periodYearMonth} it attempts, and every {@link AffiliateEarning} it settles (or
 * attempts to settle) is linked back to it via {@code settlementBatchId} -- this is what makes "which
 * batch paid this earning" a real, auditable join instead of a bare boolean on the earning row.
 *
 * <p><b>Status is the recovery mechanism [mirrors PayoutService/IdempotencyService's FAILED-is-
 * retryable pattern]</b> -- a batch starts {@code IN_PROGRESS}, and moves to {@code COMPLETED} only
 * once every earning it touched has been durably marked {@code SETTLED} (or intentionally skipped).
 * If the process crashes or a per-creator settlement throws mid-run, the batch is left {@code
 * FAILED} rather than silently stuck {@code IN_PROGRESS} forever -- {@code FAILED} is NOT terminal;
 * the next scheduled run (or a manual retry) is expected to create a fresh batch for the same period
 * and pick up whatever {@link AffiliateEarning} rows are still {@code PENDING}, exactly mirroring how
 * {@code IdempotencyService} treats FAILED idempotency keys as re-runnable rather than a permanent
 * wedge. Never re-derive "is this creator paid" from batch status alone -- always check the
 * individual {@link AffiliateEarning#getStatus()}, since a FAILED batch may have partially settled
 * some creators before failing on others.
 */
@Entity
@Table(name = "affiliate_settlement_batches")
public class AffiliateSettlementBatch {

    public enum Status {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "period_year_month", nullable = false, length = 7)
    private String periodYearMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "total_creators", nullable = false)
    private int totalCreators;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "started_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant startedAt;

    @Column(name = "completed_at", columnDefinition = "DATETIME(6)")
    private Instant completedAt;

    protected AffiliateSettlementBatch() {}

    public String getId() {
        return id;
    }

    public String getPeriodYearMonth() {
        return periodYearMonth;
    }

    public Status getStatus() {
        return status;
    }

    public int getTotalCreators() {
        return totalCreators;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    /** Records one more creator settled for {@code amount} -- called once per successfully-settled creator. */
    public void recordCreatorSettled(BigDecimal amount) {
        this.totalCreators += 1;
        this.totalAmount = this.totalAmount.add(amount);
    }

    public void markCompleted() {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** Not terminal -- see class javadoc. The next scheduled run creates a fresh batch and retries PENDING earnings. */
    public void markFailed() {
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final AffiliateSettlementBatch b = new AffiliateSettlementBatch();

        public Builder id(String id) {
            b.id = id;
            return this;
        }

        public Builder periodYearMonth(String periodYearMonth) {
            b.periodYearMonth = periodYearMonth;
            return this;
        }

        public AffiliateSettlementBatch build() {
            b.status = Status.IN_PROGRESS;
            b.totalCreators = 0;
            b.totalAmount = BigDecimal.ZERO;
            b.startedAt = Instant.now();
            return b;
        }
    }
}
