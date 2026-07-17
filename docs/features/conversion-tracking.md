# Feature: Conversion Tracking (Generic + UTM)

**Business Purpose** — A store-agnostic way to attribute sales and clicks to creators: a per-workspace signed webhook for conversions/coupon redemptions, and UTM tracking links with click/conversion/revenue counters. It complements the Shopify/WooCommerce integrations for brands on other platforms, and powers ROI reporting.

**Who uses it** — Brands (generate secret + tracking links, view ROI), external systems (post signed webhooks), shoppers (click tracked links).

## User Roles
Brand (manage secret/links), Public (click redirect), External (signed webhooks).

## Permissions
Secret management is brand-authenticated; webhooks are HMAC-trusted; the click redirect is intentionally unsigned.

## Business Flow
```
Brand generates a webhook secret + creates UTM tracking links per campaign+creator
Shopper clicks tracked link → /track/click/{utmId} → count click → 302 to destination
External system posts signed conversion/redemption webhook → verify → count conversion / redeem coupon
```

## Frontend
- **Components**: `campaigns/tracking/*` (tracking links, coupons, ROI card).
- **API**: `api.campaignTracking.*`, `api.storeIntegrations.*`.

## Backend
- **Controllers**: `ConversionWebhookController` (`/webhooks/*`, `/track/click`), `ConversionWebhookSecretController` (`/webhook-secret`), `StoreIntegrationStatusController` (`/integrations/store`), `CampaignTrackingController`.
- **Services**: `service/tracking/{RedemptionService,ConversionTrackingService,CampaignTrackingService}`, `integration/tracking/webhook/ConversionWebhookSignatureVerifier`.

## Database
`conversion_webhook_secrets` (V31, per-workspace server-generated secret), `utm_campaigns` (V23, counters + attributed revenue), `coupon_codes`/`coupon_redemptions` (V24). See [../database.md](../database.md).

## APIs
`POST /webhooks/redemption`, `POST /webhooks/conversion`, `GET /track/click/{utmCampaignId}`, `POST /webhook-secret/generate`, `DELETE /webhook-secret`, `GET /integrations/store/status`, `DELETE /integrations/store/disconnect`.

## AI
Not involved.

## Notifications
None specific (conversions accrue affiliate earnings).

## Dependencies
- **Depends on**: coupons, campaigns.
- **Depended on by**: affiliate earnings, ROI reporting, DIRECT-campaign gate (`IntegrationHealthService`).

## Connected Files
`ConversionWebhookController`, `ConversionWebhookSecretController`, `StoreIntegrationStatusController`, `service/tracking/*`, `ConversionWebhookSignatureVerifier`, `domain/entity/{ConversionWebhookSecret,UtmCampaign,CouponCode,CouponRedemption}`.

## Execution Flow
```
Redemption: POST /webhooks/redemption (X-Influora-Signature) → verify per-workspace secret (verify-first)
  → RedemptionService.redeem (validateCode workspace-scoped, discount, idempotent) → CouponRedemption
Conversion: POST /webhooks/conversion → ConversionTrackingService (incrementConversionCount + addRevenue, idempotent)
Click: GET /track/click/{utmId} (unsigned) → incrementClick → 302 to utm.fullTrackingUrl
```

## Error Handling
Unknown identifier / bad signature / no secret all collapse to one `INVALID_WEBHOOK_SIGNATURE` (401, no enumeration); `UTM_NOT_FOUND` (404). Idempotency keys workspace-namespaced (fixes cross-tenant leak on the globally-unique redemption key).

## Security
Per-workspace server-generated secret (one-time plaintext reveal); constant-time HMAC; verify-before-parse; uniform error to prevent enumeration; workspace-scoped coupon validation.

## Performance
Idempotent counters; click redirect is a single indexed lookup.

## Testing
Tracking/redemption tests. Regression risks: workspace-scoped idempotency, signature verification, double-count prevention.

## Production Readiness
- **Health**: 6/10 · **Completion**: ~72%
- **Known issues**: store webhooks do not feed UTM (documented cut — the ULID never rides an order object); affiliate accrual from redemptions is delayed (see [affiliate-coupons.md](affiliate-coupons.md)). See [../known-limitations.md](../known-limitations.md).
- **Last verified**: 2026-07-15
