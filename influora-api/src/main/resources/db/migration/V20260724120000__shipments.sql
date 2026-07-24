-- Shipment / Confirm-Receipt backend (wiki/decisions/shipment-backend-design-2026-07-24.md).
-- Product-seeding deals: brand ships a physical product, creator confirms receipt.
--
-- Cardinality: 1:1 with collaborations (UNIQUE(collaboration_id)). Re-ships do NOT create a new
-- row -- a DAMAGED shipment is re-shipped by overwriting tracking and transitioning back to
-- SHIPPED on the same row (design doc §1). No hard FK on collaboration_id -- Priya's design doc
-- calls this a soft ref by convention for this table; enforced at the app layer via the existing
-- CollaborationRepository.findByIdAndCreatorId / findByIdAndWorkspaceId trust-boundary finders
-- (ShipmentService never loads a Shipment by its own id from a client-supplied param, only by
-- collaboration_id after the collaboration ownership check passes).
--
-- Address is flat columns, not embedded JSON -- a fixed, bounded value object with per-field
-- validation (pincode/phone format, required recipient/address), matching the
-- creator_bank_accounts / creator tax-identity flat-column precedent (design doc §1).
CREATE TABLE shipments (
    id                 VARCHAR(26)  NOT NULL,
    collaboration_id   VARCHAR(26)  NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    recipient_name     VARCHAR(200) NOT NULL,
    address_line1      VARCHAR(300) NOT NULL,
    address_line2      VARCHAR(300) NULL,
    city               VARCHAR(120) NOT NULL,
    state              VARCHAR(120) NOT NULL,
    pincode            VARCHAR(12)  NOT NULL,
    phone              VARCHAR(20)  NOT NULL,
    product_name       VARCHAR(300) NULL,   -- brand-supplied on POST /deals/{id}/shipment; the
                                             -- only source of truth for the event productName
                                             -- (no product field exists on Campaign/Collaboration)
    carrier            VARCHAR(120) NULL,   -- nullable until SHIPPED
    tracking_number    VARCHAR(120) NULL,   -- nullable until SHIPPED
    tracking_url       VARCHAR(500) NULL,
    condition_note     TEXT         NULL,   -- creator's note on receipt, esp. when DAMAGED
    received_condition VARCHAR(16)  NULL,   -- ShipmentCondition: GOOD|DAMAGED, null until receipt
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_shipments_collaboration (collaboration_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
