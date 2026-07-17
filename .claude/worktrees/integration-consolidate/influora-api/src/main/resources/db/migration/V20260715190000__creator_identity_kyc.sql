-- Wave 6 N1: creator onboarding personal-identity KYC (POST /onboarding/creator/kyc).
-- Timestamp-format version per wiki/tech/adr-flyway-migration-versioning.md — trunk is past V68
-- and D14 already used V20260715180000, so this is the next non-colliding slot.
--
-- Distinct from the D14 tax_registration_status / gstin columns (business GST registration): this
-- is the personal identity gate (PAN + last-4 Aadhaar + selfie), deferred to first withdrawal per
-- the creator-onboarding UI. `pan` itself is NOT re-added — it already exists from
-- V20260715120000__creator_tax_identity.sql and is shared (same real-world PAN number).
ALTER TABLE creator_profiles
    ADD COLUMN identity_kyc_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
    ADD COLUMN aadhaar_last4 VARCHAR(4) NULL,
    ADD COLUMN selfie_url VARCHAR(500) NULL;
