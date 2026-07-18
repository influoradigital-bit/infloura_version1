-- Hibernate ddl-auto=validate maps @Column(length=26) to VARCHAR(26). Several migrations used
-- CHAR(26) for ULID primary/FK columns; MySQL treats those as distinct types and boot fails with
-- SchemaManagementException. Align with the house convention (VARCHAR(26) ULIDs everywhere else).

-- FK columns referencing these ids (e.g. subscriptions.plan_id -> plans.id) block MODIFY with
-- "Cannot change column ... used in a foreign key constraint". Both sides of every such FK are
-- retyped in this same migration, so disabling FK checks for the batch is safe and leaves every
-- constraint consistent (VARCHAR(26) on both ends) when re-enabled.
SET FOREIGN_KEY_CHECKS = 0;

ALTER TABLE plans MODIFY COLUMN id VARCHAR(26) NOT NULL;
ALTER TABLE subscriptions MODIFY COLUMN id VARCHAR(26) NOT NULL;
ALTER TABLE subscriptions MODIFY COLUMN plan_id VARCHAR(26) NOT NULL;
ALTER TABLE invoices MODIFY COLUMN id VARCHAR(26) NOT NULL;
ALTER TABLE invoices MODIFY COLUMN subscription_id VARCHAR(26) NOT NULL;
ALTER TABLE usage_counters MODIFY COLUMN id VARCHAR(26) NOT NULL;
ALTER TABLE usage_counter_details MODIFY COLUMN id VARCHAR(26) NOT NULL;
ALTER TABLE workspace_member_invites MODIFY COLUMN id VARCHAR(26) NOT NULL;
ALTER TABLE campaign_service_invoices MODIFY COLUMN id VARCHAR(26) NOT NULL;
ALTER TABLE platform_commission_invoices MODIFY COLUMN id VARCHAR(26) NOT NULL;
ALTER TABLE platform_commission_invoices MODIFY COLUMN ledger_txn_id VARCHAR(26) NOT NULL;
ALTER TABLE invoice_number_sequences MODIFY COLUMN id VARCHAR(26) NOT NULL;
ALTER TABLE hsn_sac_codes MODIFY COLUMN id VARCHAR(26) NOT NULL;

SET FOREIGN_KEY_CHECKS = 1;
