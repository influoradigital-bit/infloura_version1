-- D14 Wave 2: Doc#3 — platform commission invoice (Influora -> Brand / Influora -> Creator),
-- split legs per D14-E. Money unit DECIMAL(14,2) rupees.
CREATE TABLE platform_commission_invoices (
    id                          CHAR(26) PRIMARY KEY,
    invoice_number              VARCHAR(32) NOT NULL,
    leg                         VARCHAR(10) NOT NULL,
    campaign_id                 VARCHAR(26) NOT NULL,
    escrow_hold_id              VARCHAR(26) NULL,
    counterparty_workspace_id   VARCHAR(26) NULL,
    counterparty_user_id        VARCHAR(26) NULL,
    fee_bps_applied             INT NOT NULL,
    commission_amount           DECIMAL(14,2) NOT NULL,
    gst_amount                  DECIMAL(14,2) NOT NULL,
    hsn_sac_code                VARCHAR(10) NULL,
    ledger_txn_id               CHAR(26) NOT NULL,
    status                      VARCHAR(20) NOT NULL,
    issued_at                   TIMESTAMP NOT NULL,
    pdf_r2_key                  VARCHAR(500) NULL,
    created_at                  TIMESTAMP NOT NULL,
    CONSTRAINT fk_pci_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (id),
    CONSTRAINT fk_pci_escrow_hold FOREIGN KEY (escrow_hold_id) REFERENCES escrow_holds (id),
    CONSTRAINT fk_pci_workspace FOREIGN KEY (counterparty_workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_pci_user FOREIGN KEY (counterparty_user_id) REFERENCES users (id),
    CONSTRAINT chk_pci_leg_shape CHECK (
        (leg = 'BRAND' AND counterparty_workspace_id IS NOT NULL AND counterparty_user_id IS NULL AND escrow_hold_id IS NULL)
        OR
        (leg = 'CREATOR' AND counterparty_user_id IS NOT NULL AND counterparty_workspace_id IS NULL AND escrow_hold_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX uq_pci_invoice_number ON platform_commission_invoices (invoice_number);

-- 3a cardinality: exactly ONE BRAND-leg invoice per campaign. A plain UNIQUE(campaign_id, leg)
-- would be wrong here — a campaign with multiple milestones legitimately gets MANY CREATOR-leg
-- rows (one per released hold), which would collide on (campaign_id, 'CREATOR') for the second
-- release. MySQL has no native partial/filtered unique index, so this uses the same
-- generated-column trick as V62 (creator_bank_accounts.primary_marker): brand_leg_campaign_marker
-- is NULL for every CREATOR-leg row (NULLs never collide in a UNIQUE index) and equals campaign_id
-- only for a BRAND-leg row, so a second BRAND-leg attempt for the same campaign hits a
-- duplicate-key violation while CREATOR-leg rows are unconstrained by this index.
ALTER TABLE platform_commission_invoices
    ADD COLUMN brand_leg_campaign_marker CHAR(26)
        GENERATED ALWAYS AS (CASE WHEN leg = 'BRAND' THEN campaign_id ELSE NULL END) STORED;
CREATE UNIQUE INDEX uq_pci_brand_leg_campaign_marker ON platform_commission_invoices (brand_leg_campaign_marker);

-- 3b cardinality: one CREATOR-leg invoice per released escrow hold. escrow_hold_id is already
-- unique-scoped to the CREATOR leg (BRAND-leg rows always have escrow_hold_id NULL, and NULLs
-- never collide), so a plain unique index on (escrow_hold_id, leg) is correct here.
CREATE UNIQUE INDEX uq_pci_escrow_hold_leg ON platform_commission_invoices (escrow_hold_id, leg);

CREATE INDEX idx_pci_campaign ON platform_commission_invoices (campaign_id);
CREATE INDEX idx_pci_brand ON platform_commission_invoices (counterparty_workspace_id);
CREATE INDEX idx_pci_creator ON platform_commission_invoices (counterparty_user_id);
