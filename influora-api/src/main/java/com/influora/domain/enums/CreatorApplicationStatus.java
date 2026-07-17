package com.influora.domain.enums;

/**
 * Persisted state of {@code creator_profiles.application_status} (V38) — mirrors {@code
 * CreatorApplicationStatus} in {@code src/admin/types/admin.types.ts} exactly (3 values, no
 * collapsing needed, unlike {@link KycStatusView}'s 4-to-3 collapse for brand KYC). Jackson
 * serializes this enum by name, so the frontend union type and this enum must stay in lockstep.
 */
public enum CreatorApplicationStatus {
    PENDING,
    APPROVED,
    REJECTED
}
