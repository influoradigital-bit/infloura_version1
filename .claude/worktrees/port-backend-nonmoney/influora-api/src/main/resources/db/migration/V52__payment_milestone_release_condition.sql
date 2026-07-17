-- DPF-4: Add release_condition to payment_milestones for data-driven gate
-- See wiki/tech/deliverable-payment-flow-spec.md §4

ALTER TABLE payment_milestones
ADD COLUMN release_condition ENUM('ON_APPROVAL','ON_POSTED','ON_VERIFIED_METRICS')
NOT NULL
DEFAULT 'ON_POSTED';
