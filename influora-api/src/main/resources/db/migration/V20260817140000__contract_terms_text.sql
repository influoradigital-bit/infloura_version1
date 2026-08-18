-- F-0283 (contract-terms-never-persisted) — `terms_text` on `contracts`.
--
-- The platform never captured, stored or returned contract terms at all.
-- `ContractGenerateRequest` was `{collaborationId, milestones}` and nothing else. The
-- pre-existing column NAMED `terms` (mapped to the `termsJson` field, JSON) is written with
-- `sha256TamperHash(req)` -> `{"tamperHashSha256":"<hex>"}` — a tamper-evidence hash of the
-- request, not the agreed terms. `ContractResponse` exposed no terms field at all. Both parties
-- e-sign a contract under a statutory binding notice ("legally bound ... under the IT Act
-- 2000") over a document that, as far as the server was concerned, had no terms.
--
-- `terms_text` is a NEW, separate column — the pre-existing `terms` (tamper-hash) column and
-- its use are untouched by this migration. Nullable: terms are optional at generation time, and
-- every pre-existing contract row has none on file. No backfill: there is no source of truth to
-- backfill real terms text from for already-generated contracts — fabricating one would be
-- worse than leaving it null (same reasoning V20260817130000 used for signer names). No default
-- clause set is authored here either; deciding what a contract's terms should say is a
-- product/legal decision, not this migration's to make.
--
-- DIALECT: MySQL 8.0 (application.yml -> jdbc:mysql, MySQLDialect; docker-compose -> mysql:8.0).
-- Plain `ADD COLUMN`, no `ALTER COLUMN ... SET NOT NULL` / `CREATE INDEX IF NOT EXISTS` —
-- Postgres/MariaDB grammar that has already broken MySQL boot in this repo before
-- (V20260817120000). No backfill UPDATE here, so no `updated_at = updated_at` pin is needed
-- either — that guard only matters when a migration actually issues an UPDATE against this
-- ON UPDATE CURRENT_TIMESTAMP table, which this one does not.
ALTER TABLE contracts
    ADD COLUMN terms_text TEXT NULL
        COMMENT 'F-0283: free-text contract terms as supplied by the caller at generation time; NULL when none were supplied -- never fabricated server-side';
