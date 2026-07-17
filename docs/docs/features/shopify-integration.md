# Feature: Shopify Integration

**Business Purpose** — Lets a brand connect its Shopify store so that orders using a creator's discount code are tracked as conversions, driving affiliate earnings. It closes the loop between influencer promotion and actual sales.

**Who uses it** — Brands (connect the store), the redemption pipeline (consume order webhooks).

## User Roles
Brand (connect/disconnect).

## Permissions
OAuth is brand-authenticated; webhooks are trusted by HMAC (no JWT).

## Business Flow
```
Brand → /shopify/oauth/authorize?shop= → Shopify consent → /callback (state consumed) → encrypted token stored
Shopper checks out with a creator's code → Shopify orders/paid webhook → verify → resolve shop → redeem coupon
```

## Frontend
- **Component**: `brand/settings/StoreIntegrationSetup`.
- **API**: `api.storeIntegrations.*`.

## Backend
- **Controllers**: `ShopifyConnectController` (`/shopify/oauth`), `ShopifyWebhookController` (`/webhooks/shopify`).
- **Services/clients**: `integration/shopify/oauth/{ShopifyOAuthService,ShopifyOAuthStateStore}`, `integration/shopify/webhook/ShopifyWebhookSignatureVerifier`.

## Database
`shopify_integrations` (V27; `shop_domain` unique, encrypted non-expiring token, scopes). See [../database.md](../database.md).

## APIs
`GET /shopify/oauth/authorize`, `GET /shopify/oauth/callback`, `POST /webhooks/shopify` (public), `GET /integrations/store/status`, `DELETE /integrations/store/disconnect`.

## AI
Not involved.

## Notifications
None specific (conversions flow to affiliate earnings).

## Dependencies
- **Depends on**: Shopify OAuth + webhooks.
- **Depended on by**: coupons/affiliate earnings, conversion tracking, campaign DIRECT-type gate.

## Connected Files
`ShopifyConnectController`, `ShopifyWebhookController`, `integration/shopify/*`, `domain/entity/ShopifyIntegration`, `service/tracking/RedemptionService`.

## Execution Flow
```
Connect: /authorize → state bound to userId + shopDomain (10-min, single-use), SSRF-guarded shop regex
  → Shopify consent → /callback → token exchange → encrypted store
Webhook: POST /webhooks/shopify → ShopifyWebhookSignatureVerifier (base64 HMAC over raw body, X-Shopify-Hmac-Sha256, verify-first)
  → resolve shop by X-Shopify-Shop-Domain → topic orders/paid|create → parse discount code
  → idempotency sha256(shopDomain|topic|orderId) → RedemptionService.redeem
```

## Error Handling
`SHOPIFY_OAUTH_STATE_INVALID` (400), `SHOP_NOT_CONNECTED` (404), `INVALID_WEBHOOK_SIGNATURE` (401/400). Fails closed if the app-level webhook secret is blank/placeholder.

## Security
SSRF guard on shop domain (`^[a-z0-9][a-z0-9-]*\.myshopify\.com$`); state user+shop bound; token AES-GCM encrypted; verify-before-parse; constant-time HMAC.

## Performance
Idempotent webhook processing; single token per store.

## Testing
Shopify OAuth/webhook tests. Regression risks: signature verification, shop resolution, idempotency.

## Production Readiness
- **Health**: 7/10 · **Completion**: ~78%
- **Known issues**: requires a provisioned Shopify app + secrets; per-shop `webhook_secret` column reserved/unused.
- **Last verified**: 2026-07-15
