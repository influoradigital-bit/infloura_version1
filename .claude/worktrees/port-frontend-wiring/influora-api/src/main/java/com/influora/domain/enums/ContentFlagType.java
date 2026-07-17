package com.influora.domain.enums;

/**
 * Persisted value of {@code content_flags.content_type} (V34__admin_tables.sql) — mirrors {@code
 * ContentFlag['contentType']} in {@code src/admin/types/admin.types.ts} exactly. {@code PROFILE}
 * is the value used when {@code content_id} points at a {@code creator_profiles.id} row — the
 * join {@link com.influora.service.admin.AdminCreatorService} uses for a creator's {@code
 * flaggedContentCount} rollup.
 */
public enum ContentFlagType {
    DELIVERABLE,
    PROFILE,
    MESSAGE,
    /** Collaboration review row ({@code reviews.id}) — Task #29 / CEO §1.2. */
    REVIEW
}
