-- Wave D task D2 (wiki/tech/REMAINING_WORK_PLAN.md) -- WooCommerce store connection for a brand
-- workspace. Unlike Shopify (V27, free OAuth "custom app" install flow with a single app-level
-- webhook-signing secret), WooCommerce has NO OAuth app-install flow and NO app-level secret: a
-- brand creates a webhook directly in their own WooCommerce admin panel (WooCommerce ->
-- Settings -> Advanced -> Webhooks) pointing at our endpoint, and manually copies/generates the
-- webhook's own per-site "Secret" into our connect flow. That secret is therefore PER-INTEGRATION,
-- not a single app-wide value like Shopify's `influora.shopify.webhook-signing-secret` -- see
-- WooCommerceConnectController for the brand-facing setup endpoint that captures it.
--
-- [SEC: Kabir sign-off gate -- mirrors V27 shopify_integrations discipline exactly] encrypted_webhook_secret
-- is AES-256-GCM ciphertext produced by WooCommerceIntegrationService (same cipher, distinct key
-- from every other secret -- influora.woocommerce.token-encryption-key); this table must NEVER
-- receive a plaintext secret via any other code path. site_url is the only caller-supplied
-- identifier WooCommerceWebhookController trusts (from the `X-WC-Webhook-Source` header WooCommerce
-- sends with every delivery) to resolve which integration's secret to verify against -- see that
-- controller's javadoc for the full trust model -- so it must be unique per connection and indexed
-- for fast webhook-time lookup.
--
-- [CTO RULING -- wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same MySQL
-- translation discipline as V20/V23/V24/V27 -- ULID VARCHAR(26) primary keys, DATETIME(6)/TIMESTAMP
-- app-set via Instant.now() (no DB-side NOW() default on the ones the app controls), InnoDB/utf8mb4.
CREATE TABLE woocommerce_integrations (
  id                          VARCHAR(26) PRIMARY KEY,               -- ULID
  workspace_id                VARCHAR(26) NOT NULL,                  -- FK workspaces(id) -- owning brand workspace
  site_url                    VARCHAR(500) NOT NULL,                 -- e.g. "https://my-store.example.com" -- normalized (scheme+host, no trailing slash) at connect time
  encrypted_webhook_secret    TEXT NOT NULL,                          -- AES-256-GCM ciphertext, base64 (IV prepended) -- the brand's own WooCommerce webhook "Secret"
  revoked                     BOOLEAN NOT NULL DEFAULT FALSE,
  connected_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  UNIQUE KEY uq_woocommerce_site_url (site_url),                     -- one active connection per site; also the webhook-time lookup key
  INDEX idx_woocommerce_workspace (workspace_id),
  CONSTRAINT fk_woocommerce_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
