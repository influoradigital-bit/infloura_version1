package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One fixed-window bucket for one abuse-throttle key (T-FESTIVALBOX-0905 phase 9, V20260905190000).
 * See the migration's header for why this table exists and why it is shared across call sites
 * ({@code FestivalEnquiryService}'s per-IP/per-email caps, {@code PortfolioService#contact}'s
 * per-creator-recipient cap) instead of each one inventing its own counter table.
 *
 * <p>Read/written exclusively through {@link com.influora.repository.AbuseThrottleCounterRepository}'s
 * atomic upsert — this entity has no mutator methods on purpose. A plain {@code save()} of a loaded
 * instance would race the same way the pre-fix {@code SELECT COUNT} did; the only correct write
 * path is the repository's native {@code INSERT ... ON DUPLICATE KEY UPDATE}.
 *
 * <p>The {@code uniqueConstraints} below MIRRORS (does not replace) the migration's {@code
 * uq_abuse_throttle_counters_key_window} — Flyway, not Hibernate {@code ddl-auto}, owns the real
 * production schema. It is declared here anyway for two reasons: (1) {@code ddl-auto=validate}
 * checks it against the real table at boot, catching an entity/migration drift the same class of
 * bug F-0341-adjacent column-drift issues slip through otherwise; (2) a test that boots this entity
 * under {@code ddl-auto=create-drop} (no Flyway) — see {@code AbuseThrottleServiceConcurrencyTest}
 * — needs SOME unique constraint on {@code (throttle_key, window_start)} for the native {@code
 * ON DUPLICATE KEY UPDATE} upsert to ever take its UPDATE branch at all; without one, every attempt
 * silently inserts a brand-new row instead of colliding, and the "atomic counter" being tested
 * would not really be a counter. Confirmed by writing that test: {@code
 * FestivalCouponCopyRepositoryConcurrencyTest} (the sibling this pattern was modelled on) has the
 * identical gap on {@link FestivalCouponCopy} and was never actually exercising its upsert either.
 */
@Entity
@Table(
        name = "abuse_throttle_counters",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_abuse_throttle_counters_key_window",
                        columnNames = {"throttle_key", "window_start"}))
public class AbuseThrottleCounter {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "throttle_key", nullable = false, length = 255)
    private String throttleKey;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "request_count", nullable = false)
    private long requestCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AbuseThrottleCounter() {}

    public String getId() {
        return id;
    }

    public String getThrottleKey() {
        return throttleKey;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public long getRequestCount() {
        return requestCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
