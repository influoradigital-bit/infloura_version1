package com.influora.domain.enums;

/**
 * Persisted state of {@code content_flags.status} (V34__admin_tables.sql, widened by
 * V20260715210000__content_flag_escalated_status.sql) — mirrors {@code ContentFlagStatus} in
 * {@code src/admin/types/admin.types.ts} exactly.
 *
 * <p>Persisted via {@code @Enumerated(EnumType.STRING)} on {@link
 * com.influora.domain.entity.ContentFlag#status} — the DB stores the enum name, not the Java
 * ordinal, so declaration order below is cosmetic only (safe to insert {@code ESCALATED} in
 * PENDING-then-REVIEWED/ACTIONED reading order rather than appending it). The DB column itself
 * IS a constrained MySQL {@code ENUM('PENDING','REVIEWED','ACTIONED')} though (not VARCHAR), so
 * adding this value also required widening that column — see the migration above.
 *
 * <p>{@code ESCALATED}: flag reassigned upward to a higher-priority review queue (Priya's ruling,
 * 2026-07-15). Not a terminal state — it is not "resolved," it still requires a human decision,
 * so it must keep surfacing in the review queue ({@link
 * com.influora.repository.ContentFlagRepository#findPendingReviewQueue}) until a later
 * REMOVE/REJECT/WARN moves it to ACTIONED/REVIEWED.
 */
public enum ContentFlagStatus {
    PENDING,
    ESCALATED,
    REVIEWED,
    ACTIONED
}
