package com.influora.domain.enums;

/**
 * Mirrors {@code payment_milestones.release_condition} (V52 {@code
 * V52__payment_milestone_release_condition.sql}, DPF-4). Historically write-only at the schema
 * level — the column existed but no entity field ever mapped it (audit B5/M-1: "dead schema —
 * approval never gates/triggers release in either direction, grep 0 refs"). This enum + the
 * {@link com.influora.domain.entity.PaymentMilestone#getReleaseCondition()} mapping close the
 * read/write gap on the entity, AND (B5) {@code EscrowService#release} now actually consults it —
 * a release is blocked with {@code RELEASE_CONDITION_NOT_MET} unless every {@link
 * com.influora.domain.entity.Deliverable} linked to the milestone (via {@code
 * deliverables.milestone_id}) has reached a status that satisfies the condition below. See {@code
 * EscrowService#assertReleaseConditionSatisfied} for the exact status mapping.
 */
public enum ReleaseCondition {
    /** Release is gated on an explicit brand/admin approval action. */
    ON_APPROVAL,
    /** Release is gated on the creator having posted the deliverable (DB default). */
    ON_POSTED,
    /** Release is gated on Meta/verified metrics being available for the deliverable. */
    ON_VERIFIED_METRICS
}
