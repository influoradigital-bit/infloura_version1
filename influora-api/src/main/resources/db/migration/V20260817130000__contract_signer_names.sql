-- F-0292 (signature-name-discarded-server-side) — `brand_signer_name` / `creator_signer_name`
-- on contracts.
--
-- The brand (and creator) contract e-sign UI (contracts-and-deliverables.tsx and the shared
-- signContract() helper, src/lib/contract-generator.ts) gates its Sign button on a non-empty
-- typed full name and tells the user: "By typing your full name and clicking 'Sign Contract',
-- you agree to be legally bound ... under the IT Act 2000." The client sends `{name, agreedAt}`
-- to POST /contracts/:id/sign, but `ContractSignRequest` previously only declared `role` --
-- Jackson silently dropped `name`, and `Contract.recordBrandSignature()`/
-- `recordCreatorSignature()` wrote only a timestamp. The value the copy calls the binding act
-- was discarded before it ever reached a column.
--
-- Additive + nullable, matching this table's own `brand_signed_at`/`creator_signed_at`
-- convention (also nullable, since a contract is often only half-signed) -- a signature can be
-- recorded before a name is known to exist for it, and every pre-existing signed contract in
-- this table has no name on file. No backfill: fabricating a name for an already-signed
-- historical contract would be worse than leaving it null, and there is no source of truth to
-- backfill it from (the typed name was never captured anywhere, which is the defect itself).
--
-- DIALECT: MySQL 8.0 (application.yml -> jdbc:mysql, MySQLDialect; docker-compose -> mysql:8.0).
-- Plain `ADD COLUMN`, no `ALTER COLUMN ... SET NOT NULL` / `CREATE INDEX IF NOT EXISTS` --
-- Postgres/MariaDB grammar that has already broken MySQL boot once in this repo
-- (V20260817120000). No backfill UPDATE here, so no `updated_at = updated_at` pin is needed
-- either -- that guard only matters when a migration actually issues an UPDATE against this
-- ON UPDATE CURRENT_TIMESTAMP table, which this one does not.
ALTER TABLE contracts
    ADD COLUMN brand_signer_name VARCHAR(255) NULL
        COMMENT 'F-0292: typed full name the brand signed with; the value the e-sign UI calls legally binding',
    ADD COLUMN creator_signer_name VARCHAR(255) NULL
        COMMENT 'F-0292: typed full name recorded for the creator signature (own sign, or an elevated brand member relaying it)';
