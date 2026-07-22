package com.influora.domain.enums;

/**
 * Flywheel event types for {@code meera_interaction_log} (Phase 2 item 2.3 — Meera:
 * Label-to-Moat build plan §2.3). Persisted as a {@code VARCHAR(32)} column, NOT a DB enum
 * (see the migration comment) — adding a new event kind later is a pure app-layer change, never
 * a schema migration.
 *
 * <p><b>{@code DRAFT_ABANDONED} is deliberately NOT a member</b> (Priya's Q4 ruling, confirmed by
 * Ash: {@code wiki/build/phase2-priya-review.md} §2 Q4 / {@code phase2-ash-review.md} §Backend
 * open-question rulings). {@code CampaignIntent.abandon()} exists but has zero call sites anywhere
 * in the codebase — there is no real trigger for it today, and shipping a live enum value that
 * never fires would read as "we measured zero abandonment" when the truth is "we measured
 * nothing." Because the underlying column is a VARCHAR, re-adding this value the moment a real
 * staleness/abandonment job exists (a separate, scoped follow-up) needs no migration.
 */
public enum MeeraInteractionEventType {
    /** Meera rendered a set of tappable option cards ({@code present_options}). */
    OPTIONS_PRESENTED,
    /** The brand tapped one of the rendered option cards. */
    OPTION_TAPPED,
    /** {@code CreateCampaignExecutor} created a DRAFT campaign from conversation intent. */
    DRAFT_CREATED,
    /** {@code ConfirmLaunchExecutor} completed the real DRAFT/PAUSED/PENDING_APPROVAL -> ACTIVE transition. */
    DRAFT_FUNDED,
    /** {@code BrandDeliverableService#requestRevision} — carries the (redacted) revision reason. */
    REVISION_REQUESTED
}
