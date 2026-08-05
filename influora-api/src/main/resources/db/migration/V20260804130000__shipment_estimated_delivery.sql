-- creator-shipment-eta-0804: real brand-supplied estimated delivery date.
-- Before this, the FE fabricated an ETA (now + 3 days) because the Shipment entity had no
-- ETA column. Brand supplies it (optional) on mark-shipped, alongside carrier/tracking.
ALTER TABLE shipments ADD COLUMN estimated_delivery DATE;
