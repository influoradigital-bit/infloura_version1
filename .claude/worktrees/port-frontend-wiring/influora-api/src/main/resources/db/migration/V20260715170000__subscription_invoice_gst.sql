-- D14 Wave 9 (Rohan build-flag #5, live gap pulled forward): Doc#1 (subscription invoice) has no
-- invoice_number, no CGST/SGST/IGST breakup, no HSN — invoices already shipping to real brands are
-- likely non-compliant. Adds the statutory fields WITHOUT touching `amount` (stays int-paise; a
-- full unit migration to DECIMAL(14,2) is a separate, higher-risk change, deliberately out of
-- scope here — see Invoice.java's field javadoc for the unit-mismatch rationale).
ALTER TABLE invoices
    ADD COLUMN invoice_number VARCHAR(32) NULL,
    ADD COLUMN base_amount DECIMAL(14,2) NULL,
    ADD COLUMN cgst_amount DECIMAL(14,2) NULL,
    ADD COLUMN sgst_amount DECIMAL(14,2) NULL,
    ADD COLUMN igst_amount DECIMAL(14,2) NULL,
    ADD COLUMN hsn_sac_code VARCHAR(10) NULL;

CREATE UNIQUE INDEX uq_invoices_invoice_number ON invoices (invoice_number);

-- Backfill EXISTING rows' GST breakup only (base/cgst/sgst/igst) — invoice_number is deliberately
-- NOT backfilled (see Invoice.java javadoc: a real statutory number is immutable once issued, and
-- these historical rows were never actually issued one). Assumes GST-inclusive `amount` (paise) at
-- the standard 18% rate and defaults to IGST treatment (conservative default when the brand's vs.
-- Influora's GSTIN state comparison cannot be reconstructed retroactively in a migration — CA to
-- confirm/correct this backfill before it is relied on for a real filed return).
UPDATE invoices
SET
    base_amount = ROUND((amount / 100.0) / 1.18, 2),
    igst_amount = ROUND((amount / 100.0) - ROUND((amount / 100.0) / 1.18, 2), 2),
    cgst_amount = 0.00,
    sgst_amount = 0.00
WHERE amount IS NOT NULL;
