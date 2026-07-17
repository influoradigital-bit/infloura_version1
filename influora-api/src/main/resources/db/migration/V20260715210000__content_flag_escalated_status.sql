-- Adds ESCALATED to content_flags.status (Priya's ruling, 2026-07-15 — see
-- ContentFlagStatus.java javadoc / wiki/errors AUTH-1/INV-2 QA gate thread).
--
-- Timestamp note: latest existing timestamped migration at authoring time was
-- V20260715190000__creator_identity_kyc.sql; Meera is concurrently editing
-- db/migration/ on other files (V20260715130000, V20260715140000, V54, V58, V59)
-- but nothing at or after V20260715200000, so this uses V20260715210000 to stay
-- clear of any collision. Flagging loudly per instructions — if Meera lands a
-- migration in the V202607152xxxxx range before this merges, renumber this one.
--
-- content_flags.status (V34__admin_tables.sql) is a genuinely constrained MySQL
-- ENUM('PENDING','REVIEWED','ACTIONED') column, not VARCHAR — the JPA entity uses
-- @Enumerated(EnumType.STRING), but that only controls how Java talks to the
-- column; the column's own type still rejects any value outside its ENUM list.
-- Without this migration, persisting ContentFlagStatus.ESCALATED would fail at
-- the DB with "Data truncated for column 'status'". MODIFY (not a new column) is
-- required to add a value to a MySQL ENUM; existing rows are unaffected since
-- their values (PENDING/REVIEWED/ACTIONED) are untouched by the widened list.
ALTER TABLE content_flags
  MODIFY COLUMN status ENUM('PENDING','ESCALATED','REVIEWED','ACTIONED') NOT NULL DEFAULT 'PENDING';
