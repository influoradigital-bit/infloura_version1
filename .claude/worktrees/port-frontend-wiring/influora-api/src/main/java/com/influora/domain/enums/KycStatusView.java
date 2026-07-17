package com.influora.domain.enums;

/**
 * Admin-facing KYC status shown to the frontend — mirrors {@code KycStatus} in {@code
 * src/admin/types/admin.types.ts} exactly (Jackson serializes this enum by name).
 *
 * <p>NOT the same enum as {@link VerificationStatus} (the real persisted state on {@code
 * workspaces.verification_status}, which has 4 values: UNVERIFIED/PENDING/VERIFIED/REJECTED).
 * {@code AdminBrandService} maps {@link VerificationStatus} → {@link KycStatusView} for the API
 * response only (UNVERIFIED and PENDING both collapse to {@link #PENDING} — the frontend's 3-value
 * union has no "not yet started" state distinct from "pending review").
 */
public enum KycStatusView {
    PENDING,
    APPROVED,
    REJECTED
}
