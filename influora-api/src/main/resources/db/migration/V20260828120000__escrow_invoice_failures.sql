-- F-0390 D1: durable failure marker for the D14 Doc#2 creator service invoice
-- (campaign_service_invoices). Escrow release money movement must never roll back on an invoice
-- failure (deliberate -- see EscrowService#safelyCreateServiceInvoice's javadoc) but until this
-- migration that failure was ONLY a log.error() line: no outbox row, no retry, no failure marker,
-- no metric, no backfill tool anywhere in the codebase, and the in-code comment saying "backfill
-- manually" pointed at a tool that did not exist (F-0390 audit).
--
-- Shape deliberately mirrors the existing email_outbox transactional-outbox pattern
-- (V18__email_outbox.sql / EmailOutbox / EmailWorker) so a failed invoice attempt becomes
-- queryable and retryable instead of silently lost, same status/retry_count/next_retry_at columns
-- and PENDING-batch-with-backoff shape.
--
-- One row per escrow_hold_id (UNIQUE) -- CampaignServiceInvoiceService#createAtRelease is already
-- idempotent per hold (see its own findByEscrowHoldId gate), so a hold can only ever be in one of
-- two failure states at a time: INVOICE_CREATE (no campaign_service_invoices row exists yet -- the
-- creator-profile/campaign/workspace lookup or invoice-number mint itself failed) or PDF_RENDER
-- (the invoice row was created and numbered, only the PDF render/R2 store failed -- see
-- CampaignServiceInvoiceService#createAtRelease's own try/catch around pdfService.render/putBytes).
CREATE TABLE escrow_invoice_failures (
  id                   VARCHAR(26) PRIMARY KEY,                    -- ULID
  escrow_hold_id       VARCHAR(26) NOT NULL,
  collaboration_id     VARCHAR(26) NOT NULL,
  ledger_credit_leg_id VARCHAR(26) NULL,                           -- traceability only, same field CampaignServiceInvoice logs
  failure_stage        VARCHAR(32) NOT NULL,                       -- 'INVOICE_CREATE' | 'PDF_RENDER'
  status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',    -- PENDING, RESOLVED, EXHAUSTED
  retry_count          TINYINT NOT NULL DEFAULT 0,
  next_retry_at        TIMESTAMP(3) NULL,
  error_message        VARCHAR(512) NULL,
  created_at           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  resolved_at          TIMESTAMP(3) NULL,
  UNIQUE KEY uq_escrow_invoice_failures_hold (escrow_hold_id),
  INDEX idx_escrow_invoice_failures_pending (status, next_retry_at),
  CONSTRAINT fk_escrow_invoice_failures_hold FOREIGN KEY (escrow_hold_id) REFERENCES escrow_holds(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
