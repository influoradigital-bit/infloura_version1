-- D14 Wave 0: creator tax identity (unblocks Doc#2, creator-service-invoice).
-- Timestamp-format version per wiki/tech/adr-flyway-migration-versioning.md (avoids the
-- V40/V41 collision class since multiple sessions touch migrations concurrently).
--
-- Nullable per D14-B (2026-07-15, Swapnil): schema captures GSTIN/PAN, but enforcement is a
-- runtime toggle pending Legal — not a schema-blocking NOT NULL constraint.
ALTER TABLE creator_profiles
    ADD COLUMN gstin VARCHAR(15) NULL,
    ADD COLUMN pan VARCHAR(10) NULL,
    ADD COLUMN tax_registration_status VARCHAR(20) NOT NULL DEFAULT 'UNREGISTERED',
    ADD COLUMN creator_invoice_code VARCHAR(12) NULL;

CREATE INDEX idx_creator_profiles_gstin ON creator_profiles (gstin);

-- Per D14-C, creator_invoice_code anchors each creator's own statutory numbering series — it
-- must be unique once assigned (multiple creators can share NULL pre-onboarding).
CREATE UNIQUE INDEX uq_creator_profiles_invoice_code ON creator_profiles (creator_invoice_code);
