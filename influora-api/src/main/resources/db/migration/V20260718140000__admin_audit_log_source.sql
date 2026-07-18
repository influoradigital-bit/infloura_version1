-- Audit-trail integrity (Kabir red-team 2.1): admin_audit_log had no column distinguishing
-- authoritative server-internal writes (AdminAuditLogService#record, called right after a real
-- mutation) from client-submitted "convenience trail" rows (AdminAuditLogService
-- #recordClientEntry, POST /admin/audit) -- a SUPER_ADMIN could POST a fabricated ESCROW_RELEASE
-- entry and it would render identically to a real one in the audit viewer.
--
-- Backfill default is SERVER_INTERNAL: every existing row was written by #record (the client POST
-- path did not exist, or was not yet distinguishable, before this migration), so defaulting
-- existing rows to SERVER_INTERNAL is correct, not a guess.
ALTER TABLE admin_audit_log
  ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'SERVER_INTERNAL' AFTER ip_address;
