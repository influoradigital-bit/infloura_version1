-- [SEC: Kabir Wave E4 capstone red-team, HIGH -- re-escalated finding, wiki/errors/wave-e4-full-redteam-signoff.md
-- Part D / Condition 1] `ConversionWebhookController`'s `/webhooks/conversion` and
-- `/webhooks/redemption` endpoints were left with NO signature verification -- Wave D1/D2 only
-- added HMAC verification to their OWN new routes (`/webhooks/shopify`, `/webhooks/woocommerce`),
-- never to this pre-existing controller, even though the E2 review's accepted-risk downgrade of
-- the amount-entropy pre-poisoning HIGH was explicitly conditioned on D1 doing exactly that here.
--
-- Trust model chosen: unlike Shopify (single app-level secret, OAuth app-install) or WooCommerce
-- (brand generates their OWN secret in a third-party admin panel we have no relationship with),
-- `/webhooks/conversion` and `/webhooks/redemption` are called by an arbitrary brand's OWN
-- commerce backend / custom checkout / server-side integration -- there is no third-party admin
-- panel to configure a webhook against, and no OAuth app-install flow exists for a "generic
-- checkout." The correct shape here is closer to an API key: WE mint a per-workspace secret and
-- hand it to the brand once (see ConversionWebhookSecretController), and they configure their own
-- backend to send it back as an HMAC signature on every call -- same per-workspace-secret model as
-- WooCommerce's `woocommerce_integrations.encrypted_webhook_secret` (V29), just server-generated
-- instead of brand-generated, since there is no brand-side admin UI to copy a secret out of here.
--
-- One row per workspace (a brand has exactly one active conversion-webhook secret at a time, same
-- "rotate in place" shape as woocommerce_integrations/shopify_integrations), never revoked by
-- deleting the row (audit trail is append-only by convention -- see `revoked` boolean, mirroring
-- V27/V29).
--
-- [CTO RULING -- wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same MySQL
-- translation discipline as V27/V29 -- ULID VARCHAR(26) primary keys, TIMESTAMP app-managed,
-- InnoDB/utf8mb4.
CREATE TABLE conversion_webhook_secrets (
  id                  VARCHAR(26) PRIMARY KEY,               -- ULID
  workspace_id        VARCHAR(26) NOT NULL,                  -- FK workspaces(id) -- owning brand workspace
  encrypted_secret    TEXT NOT NULL,                          -- AES-256-GCM ciphertext, base64 (IV prepended) -- server-generated, given to the brand once at creation time
  revoked             BOOLEAN NOT NULL DEFAULT FALSE,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  UNIQUE KEY uq_conversion_webhook_secrets_workspace (workspace_id),  -- one active secret per workspace
  CONSTRAINT fk_conversion_webhook_secrets_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
