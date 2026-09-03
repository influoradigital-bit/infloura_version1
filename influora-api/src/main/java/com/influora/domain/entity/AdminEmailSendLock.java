package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Singleton mutex row (T-ADMINMAIL-0903 round 3, B1 — REVIEW-R2.md ship-blocker, V20260903130000)
 * for serializing {@code AdminCustomEmailService#send}. Exactly one row exists, seeded by the
 * migration at {@link #SINGLETON_ID} — nothing in this codebase ever inserts a second one.
 *
 * <p>{@code AdminEmailSendLockRepository#lockForUpdate} takes a {@code SELECT ... FOR UPDATE} on
 * this row at the start of {@code send()}'s transaction; MySQL/InnoDB blocks a second transaction
 * from acquiring that same lock until the first commits or rolls back, and a locking read always
 * sees the latest COMMITTED data regardless of isolation level — <b>but only for the row(s) that
 * locking read itself locks</b> (this singleton row). That does NOT, by itself, guarantee that a
 * later PLAIN (non-locking) read of a different table in the same transaction — {@code
 * enforceRateLimit()}'s read of {@code admin_email_campaigns} — also sees the latest committed
 * data: under MySQL/InnoDB's default REPEATABLE READ, a transaction's read view is pinned at its
 * FIRST plain SELECT, which runs before this lock is even acquired, so a later plain SELECT on an
 * unrelated table could still see a stale, pre-lock snapshot. That is exactly what {@code
 * send()}'s round-3, A2 fix (REVIEW-R3.md) addresses: {@code send()} is annotated {@code
 * @Transactional(isolation = READ_COMMITTED)}, which gives every plain SELECT its own fresh
 * snapshot as of that statement's start — see that method's javadoc for the full reasoning. WITH
 * that isolation override in place, the serialized caller's own read of {@code
 * admin_email_campaigns} runs after this lock is acquired and IS then guaranteed to see whatever
 * the previous holder just committed — but that guarantee comes from the isolation level, not from
 * this locking read alone. This is what makes the persisted rate limit ({@code enforceRateLimit})
 * an actual mutual exclusion instead of a read-then-write race.
 *
 * <p>{@code updatedAt} is not currently read by any query — it exists only so this row is a real,
 * inspectable table row (last-touched timestamp) rather than a bare id with no other columns.
 */
@Entity
@Table(name = "admin_email_send_lock")
public class AdminEmailSendLock {

    /** The one row this table will ever hold — see migration V20260903130000's INSERT. */
    public static final String SINGLETON_ID = "SINGLETON";

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdminEmailSendLock() {}

    /** Test-only convenience constructor — production code never inserts a row here (see class javadoc). */
    public static AdminEmailSendLock singleton() {
        AdminEmailSendLock lock = new AdminEmailSendLock();
        lock.id = SINGLETON_ID;
        lock.updatedAt = Instant.now();
        return lock;
    }

    public String getId() {
        return id;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
