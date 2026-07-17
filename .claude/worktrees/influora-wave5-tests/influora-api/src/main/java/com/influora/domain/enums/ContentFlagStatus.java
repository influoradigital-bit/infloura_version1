package com.influora.domain.enums;

/**
 * Persisted state of {@code content_flags.status} (V34__admin_tables.sql) — mirrors {@code
 * ContentFlagStatus} in {@code src/admin/types/admin.types.ts} exactly.
 */
public enum ContentFlagStatus {
    PENDING,
    REVIEWED,
    ACTIONED
}
