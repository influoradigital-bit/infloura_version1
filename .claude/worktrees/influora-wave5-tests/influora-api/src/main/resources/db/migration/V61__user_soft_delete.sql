-- Real account deletion, implemented as soft-delete (Vikram/CEO decision 2026-07-14).
-- The frontend "Delete Account" button previously called nothing server-side -- pure UX lie.
-- Hard-deleting the users row would cascade into (or orphan) deals/contracts/messages that the
-- OTHER party to a transaction still needs for their own records and for compliance retention, so
-- we anonymize PII in place instead: User.softDelete() blanks email/phone/password/name/avatar and
-- stamps deleted_at, but leaves id/status/userType untouched so existing FKs keep resolving.
--
-- Nullable, no default -- existing rows are all "not deleted" (NULL), and NULL is also the
-- steady-state value for every future row until softDelete() runs.
--
-- Next free Flyway slot after V60 (payout_methods, a parallel in-flight task -- that task actually
-- claimed V60, not V59 as originally assumed when this migration was drafted, so this is V61 to
-- avoid a real collision). Owner: Vikram (Backend).

ALTER TABLE users ADD COLUMN deleted_at DATETIME(6) NULL COMMENT 'Set by User.softDelete() at account-deletion time; NULL means the account is active';
