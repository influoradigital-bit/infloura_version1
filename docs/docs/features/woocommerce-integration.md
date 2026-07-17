# Feature: WooCommerce Integration

**Business Purpose** — Same conversion-tracking outcome as Shopify, for brands on WooCommerce, but **receive-only** (no OAuth). The brand creates a webhook in WooCommerce admin and pastes the secret; Influora then receives order webhooks and redeems creator coupons for affiliate tracking.

**Who uses it** — Brands (connect), the redemption pipeline (consume).

## User Roles
Brand (connect/disconnect).

## Permissions
Connect is brand-authenticated; webhooks trusted by per-integration HMAC secret.

## Business Flow
```
Brand creates a webhook in WooCommerce → pastes site URL + secret → /woocommerce/connect (encrypted secret stored)
Order placed with a creator's coupon → WooCommerce webhook → resolve site → verify per-integration secret → redeem
```

## Frontend
- **Component**: `brand/settings/StoreIntegrationSetup`.
- **API**: `api.storeIntegrations.*`.

## Backend
- **Controllers**: `WooCommerceConnectController` (`/woocommerce/connect`), `WooCommerceWebhookController` (`/webhooks/woocommerce`).
- **Services/clients**: `integration/woocommerce/webhook/WooCommerceWebhookSignatureVerifier`, `WooCommerceSiteUrl` (normalize).

## Database
`woocommerce_integrations` (V29; `site_url` unique, encrypted webhook secret). See [../database.md](../database.md).

## APIs
`POST /woocommerce/connect`, `POST /webhooks/woocommerce` (public), `GET /integrations/store/status`, `DELETE /integrations/store/disconnect`.

## AI
Not involved.

## Notifications
None specific.

## Dependencies
- **Depends on**: WooCommerce webhooks.
- **Depended on by**: coupons/affiliate earnings, conversion tracking, DIRECT-campaign gate.

## Connected Files
`WooCommerceConnectController`, `WooCommerceWebhookController`, `integration/woocommerce/*`, `domain/entity/WooCommerceIntegration`, `RedemptionService`.

## Execution Flow
```
Connect: POST /woocommerce/connect {siteUrl, webhookSecret} → WooCommerceSiteUrl.normalize (match X-WC-Webhook-Source)
  → store encrypted secret per workspace
Webhook: POST /webhooks/woocommerce → resolve site (findBySiteUrlAndRevokedFalse) → decrypt per-integration secret
  → verify (X-WC-Webhook-Signature, base64 HMAC, constant-time) → topic order.created|updated → parse coupon
  → idempotency sha256(siteUrl|topic|orderId) → RedemptionService.redeem
```
Note the ordering differs from Shopify: resolve site → decrypt secret → verify (Woo uses a per-integration secret, not an app-level one).

## Error Handling
`INVALID_SITE_URL` (400), `WEBHOOK_SECRET_REQUIRED` (400), `SITE_NOT_CONNECTED` (404), `INVALID_WEBHOOK_SIGNATURE` (401). Fails closed on blank secret.

## Security
Per-integration encrypted secret; URL normalization prevents source-mismatch; verify-before-parse; constant-time HMAC.

## Performance
Idempotent; single secret per site.

## Testing
Woo webhook tests. Regression risks: URL normalization, signature verification, idempotency.

## Production Readiness
- **Health**: 7/10 · **Completion**: ~78%
- **Known issues**: none material beyond requiring the brand to configure the webhook manually.
- **Last verified**: 2026-07-15
