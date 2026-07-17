-- D14 Wave 4: configurable HSN/SAC lookup (Rohan build-flag #4 — never hardcode a tax code).
CREATE TABLE hsn_sac_codes (
    id           CHAR(26) PRIMARY KEY,
    code_type    VARCHAR(20) NOT NULL,
    code         VARCHAR(10) NOT NULL,
    description  VARCHAR(200) NOT NULL,
    applies_to   VARCHAR(50) NOT NULL,
    created_at   TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Only unique per surface, not per code value: a real SAC code can legitimately apply to more
-- than one billing surface (as seeded below, PLATFORM_COMMISSION and the SUBSCRIPTION placeholder
-- both use 998599), so `code` itself is not a uniqueness key.
CREATE INDEX idx_hsn_sac_codes_code ON hsn_sac_codes (code);
CREATE UNIQUE INDEX uq_hsn_sac_codes_applies_to ON hsn_sac_codes (applies_to);

-- Seed per Rohan build-flag #4. Subscription SAC pending CA confirmation — seeded with the
-- generic SaaS/IT-enabled-services code as a placeholder so InvoiceNumberService's subscription
-- GST-breakup path (Wave 9) has something non-null to render; flagged for CA sign-off before this
-- is relied on for a real filed return.
INSERT INTO hsn_sac_codes (id, code_type, code, description, applies_to, created_at)
VALUES
    ('01J0D14HSC000000000000CS01', 'SAC', '998397', 'Creator influencer marketing services', 'CREATOR_SERVICE', NOW()),
    ('01J0D14HSC000000000000PC01', 'SAC', '998599', 'Platform marketplace commission', 'PLATFORM_COMMISSION', NOW()),
    ('01J0D14HSC000000000000SB01', 'SAC', '998599', 'SaaS subscription — PENDING CA CONFIRMATION', 'SUBSCRIPTION', NOW());
