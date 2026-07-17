package com.influora.domain.enums;

/** Lifecycle for a creator deliverable slot (09_CREATOR_DELIVERABLES_SPEC.md §2). */
public enum DeliverableStatus {
    PENDING,
    DRAFT,
    SUBMITTED,
    REVISION_REQUESTED,
    RESUBMITTED,
    APPROVED,
    REJECTED,
    POSTED,
    METRICS_REPORTED,
    VERIFIED
}
