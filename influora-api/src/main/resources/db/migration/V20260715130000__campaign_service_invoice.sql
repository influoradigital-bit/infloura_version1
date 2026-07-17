-- D14 Wave 1: Doc#2 — creator service invoice (Creator -> Brand). Money unit is DECIMAL(14,2)
-- rupees, matching escrow_holds/the ledger — NOT the subscription invoice's int-paise convention.
CREATE TABLE campaign_service_invoices (
    id                   CHAR(26) PRIMARY KEY,
    invoice_number       VARCHAR(32) NOT NULL,
    collaboration_id     VARCHAR(26) NOT NULL,
    campaign_id          VARCHAR(26) NOT NULL,
    escrow_hold_id       VARCHAR(26) NOT NULL,
    creator_user_id      VARCHAR(26) NOT NULL,
    brand_workspace_id   VARCHAR(26) NOT NULL,
    gross_amount         DECIMAL(14,2) NOT NULL,
    currency             CHAR(3) NOT NULL DEFAULT 'INR',
    creator_gstin        VARCHAR(15) NULL,
    tcs_amount            DECIMAL(14,2) NULL,
    hsn_sac_code          VARCHAR(10) NULL,
    status                VARCHAR(20) NOT NULL,
    issued_at             TIMESTAMP NOT NULL,
    pdf_r2_key            VARCHAR(500) NULL,
    created_at            TIMESTAMP NOT NULL,
    CONSTRAINT fk_csi_collaboration FOREIGN KEY (collaboration_id) REFERENCES collaborations (id),
    CONSTRAINT fk_csi_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (id),
    CONSTRAINT fk_csi_escrow_hold FOREIGN KEY (escrow_hold_id) REFERENCES escrow_holds (id),
    CONSTRAINT fk_csi_creator FOREIGN KEY (creator_user_id) REFERENCES users (id),
    CONSTRAINT fk_csi_brand_workspace FOREIGN KEY (brand_workspace_id) REFERENCES workspaces (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX uq_csi_invoice_number ON campaign_service_invoices (invoice_number);

-- One invoice per released escrow hold (the money-movement event that triggers issuance) — also
-- doubles as the release-time idempotency-gate lookup (Rohan build-flag #2).
CREATE UNIQUE INDEX uq_csi_escrow_hold ON campaign_service_invoices (escrow_hold_id);

CREATE INDEX idx_csi_collaboration ON campaign_service_invoices (collaboration_id);
CREATE INDEX idx_csi_creator ON campaign_service_invoices (creator_user_id);
CREATE INDEX idx_csi_brand ON campaign_service_invoices (brand_workspace_id);
