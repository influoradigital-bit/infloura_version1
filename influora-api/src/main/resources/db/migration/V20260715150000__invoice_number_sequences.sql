-- D14 Wave 3: statutory invoice numbering series (D14-C, 2026-07-15 Rohan).
-- One row per (series_type, fiscal_year, creator_invoice_code); creator_invoice_code is NULL for
-- the three platform-wide series and set for CAMPAIGN_SERVICE (one series PER creator, seeded
-- on-demand by InvoiceNumberService when a creator's first Doc#2 invoice is issued).
CREATE TABLE invoice_number_sequences (
    id                    CHAR(26) PRIMARY KEY,
    series_type           VARCHAR(20) NOT NULL,
    fiscal_year           VARCHAR(7) NOT NULL,
    creator_invoice_code  VARCHAR(12) NULL,
    next_seq              INT NOT NULL DEFAULT 1,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- MySQL treats NULL as distinct across rows in a UNIQUE index, so the three platform-wide series
-- (creator_invoice_code IS NULL) each get exactly one row per fiscal year, while per-creator rows
-- are independently unique per (series_type, fiscal_year, creator_invoice_code).
CREATE UNIQUE INDEX uq_invoice_number_sequences
    ON invoice_number_sequences (series_type, fiscal_year, creator_invoice_code);

-- Seed the three platform-wide series for the current Indian FY (Apr 2026 - Mar 2027).
-- Per-creator CAMPAIGN_SERVICE rows are NOT seeded here — created on-demand at first issuance.
INSERT INTO invoice_number_sequences (id, series_type, fiscal_year, creator_invoice_code, next_seq, created_at, updated_at)
VALUES
    ('01J0D14SUB0000000000000001', 'SUBSCRIPTION', '2026-27', NULL, 1, NOW(), NOW()),
    ('01J0D14CMB0000000000000001', 'COMMISSION_BRAND', '2026-27', NULL, 1, NOW(), NOW()),
    ('01J0D14CMC0000000000000001', 'COMMISSION_CREATOR', '2026-27', NULL, 1, NOW(), NOW());
