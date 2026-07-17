package com.influora.domain.enums;

/** Task #34 — dispute lifecycle (CEO §1.3): OPEN → UNDER_REVIEW → RESOLVED_* */
public enum DisputeStatus {
    OPEN,
    UNDER_REVIEW,
    RESOLVED_BRAND,
    RESOLVED_CREATOR,
    RESOLVED_SPLIT;

    public boolean isActive() {
        return this == OPEN || this == UNDER_REVIEW;
    }

    public boolean isResolved() {
        return this == RESOLVED_BRAND || this == RESOLVED_CREATOR || this == RESOLVED_SPLIT;
    }
}
