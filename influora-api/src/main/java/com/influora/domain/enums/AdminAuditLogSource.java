package com.influora.domain.enums;

/**
 * Provenance discriminator for {@code admin_audit_log} rows (V20260718140000__admin_audit_log_source.sql,
 * Kabir red-team 2.1). Without this, a client-submitted "convenience trail" row (POST
 * /admin/audit, {@code AdminAuditLogService#recordClientEntry}) rendered identically to an
 * authoritative server-internal row ({@code AdminAuditLogService#record}, always called
 * immediately after a real mutation has already been persisted) — letting a SUPER_ADMIN fabricate
 * e.g. an {@code ESCROW_RELEASE} entry with no way for the audit viewer or an investigator to tell
 * it apart from the real one.
 */
public enum AdminAuditLogSource {
    /** Written by {@code AdminAuditLogService#record}, always immediately after the real mutation it documents. */
    SERVER_INTERNAL,
    /** Written by {@code AdminAuditLogService#recordClientEntry} (POST /admin/audit) — client-supplied, not proof a mutation actually happened. */
    CLIENT_REPORTED
}
