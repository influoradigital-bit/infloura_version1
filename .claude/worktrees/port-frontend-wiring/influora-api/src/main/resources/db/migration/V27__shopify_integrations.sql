-- Wave D task D1 (wiki/tech/REMAINING_WORK_PLAN.md) -- Shopify store connection for a brand
-- workspace, via Shopify's FREE OAuth "custom app" / public-app path (no $99/mo Shopify Plus or
-- paid-app-listing fee -- this is the standard "install this app on your store" OAuth handshake
-- every Shopify app, free or paid, uses to obtain a store access token).
--
-- [SEC: Kabir sign-off gate -- mirrors V20 meta_oauth_tokens discipline exactly] encrypted_access_token
-- is AES-256-GCM ciphertext produced by ShopifyTokenStorage; this table must NEVER receive a
-- plaintext token via any other code path. shop_domain is the *.myshopify.com domain Shopify
-- issues per store -- it is the only caller-supplied identifier ShopifyWebhookController trusts to
-- resolve a workspace (see that controller's javadoc for the full trust model), so it must be
-- unique per connection and indexed for fast webhook-time lookup.
--
-- [CTO RULING -- wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same MySQL
-- translation discipline as V20/V23/V24 -- ULID VARCHAR(26) primary keys, DATETIME(6)/TIMESTAMP
-- app-set via Instant.now() (no DB-side NOW() default on the ones the app controls), JSON column
-- for the scope list, InnoDB/utf8mb4.
CREATE TABLE shopify_integrations (
  id                       VARCHAR(26) PRIMARY KEY,               -- ULID
  workspace_id             VARCHAR(26) NOT NULL,                  -- FK workspaces(id) -- owning brand workspace
  shop_domain              VARCHAR(255) NOT NULL,                 -- e.g. "my-store.myshopify.com"
  encrypted_access_token   TEXT NOT NULL,                          -- AES-256-GCM ciphertext, base64 (IV prepended)
  granted_scopes           JSON NULL,                              -- e.g. ['read_orders','read_products']
  webhook_secret           VARCHAR(255) NULL,                      -- per-shop webhook signing secret, if Shopify ever issues one distinct from the app's shared secret; NULL uses the app-level secret (ShopifyProperties)
  revoked                  BOOLEAN NOT NULL DEFAULT FALSE,
  connected_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  UNIQUE KEY uq_shopify_shop_domain (shop_domain),                 -- one active connection per store; also the webhook-time lookup key
  INDEX idx_shopify_workspace (workspace_id),
  CONSTRAINT fk_shopify_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
