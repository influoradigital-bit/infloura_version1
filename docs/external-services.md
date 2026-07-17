# External Services

Every third-party system Influora integrates with, how it's wired, and its security posture. Integration code lives under `integration/<provider>` with config in `config/*Properties`.

**Common posture** across all integrations: a distinct **AES-256-GCM** key per integration (12-byte random IV prepended, base64), secrets never logged, webhook signatures verified **before** parsing (constant-time compare), fail-closed when a required secret is unconfigured, and deterministic mock stubs in dev when a provider isn't configured.

---

## Razorpay + RazorpayX (payments & payouts)

`integration/razorpay`. Config `RazorpayProperties` (`influora.razorpay`): `key-id`, `key-secret`, `webhook-secret`, `payout-account-number`, API base URLs, `platform-fee-percent=15.00`.

- **`RazorpayClient`** — uses the official `razorpay-java` 1.4.6 SDK. Creates orders (top-up, escrow fund), plans, subscriptions (hosted checkout `short_url`). Amounts converted to paise via `movePointRight(2).longValueExact()`. `isConfigured()` = key-id + key-secret present; `isFullyConfigured()` also needs the webhook secret. When unconfigured, returns deterministic mock stubs.
- **`RazorpayXClient`** — raw `java.net.http.HttpClient`, HTTP Basic auth. Contacts/fund-accounts/payouts (`POST /payouts` with `X-Payout-Idempotency`, `mode:IMPS`). `isConfigured()` additionally requires `payout-account-number`.
- **`WebhookSignatureVerifier`** — HMAC-SHA256 over the **raw body**, lowercase hex, constant-time compare, **fails closed** on missing signature/secret.
- **`RazorpayWebhookController`** (`POST /webhooks/razorpay`, public): binds the raw body String, verifies before parsing (bad signature → 400), then dispatches: `order.paid`/`payment.captured` → top-up credit or escrow-funded confirm (by receipt prefix); `payout.processed`/`payout.reversed` → payout confirm. **No `subscription.*` handler exists** (see [known-limitations.md](known-limitations.md)). Unknown events → 200 ack.

No client-callback signature verification and no Refunds API — payment trust rests on the webhook; "refund" is an internal ledger operation. See [features/wallet.md](features/wallet.md), [features/escrow.md](features/escrow.md), [features/billing-subscriptions.md](features/billing-subscriptions.md).

---

## Meta / Instagram (analytics)

`integration/meta`, Graph API **v25.0**. Creators (CREATOR only) connect an Instagram Business/Creator account via a linked Facebook Page.

- **Entity** `meta_oauth_tokens` (V20): stores only the AES-256-GCM-encrypted access token (no refresh token — Meta long-lived tokens are self-refreshed by re-exchange), `expires_at`, scopes, `revoked`. `UNIQUE(workspace, creator_profile)`.
- **OAuth** (`MetaOAuthController`, `/meta/oauth`): `/authorize` returns a JSON URL (SPA navigates); `/callback` consumes single-use state (`MetaOAuthStateStore`, 10-min TTL, CSRF + user-binding), exchanges code→short→long-lived token. Scopes: `instagram_basic, instagram_manage_insights, pages_show_list, pages_read_engagement`.
- **Token security** (`MetaTokenStorage`): `AES/GCM/NoPadding`, fresh IV per call; key `influora.meta.token-encryption-key` (must be 32 bytes; constructor throws on blank at startup).
- **Insights** (`InstagramInsightsClient`, `FacebookPageClient`): profile, media list, media insights, audience demographics. Rate-limited via `MetaRateLimitTracker` (parses `X-Business-Use-Case-Usage`; pre-flight throttle at 90%).
- **Jobs**: `MetaTokenRefreshService` (daily 02:30, refresh near-expiry), `StaleTokenCleanupJob` (daily 04:00, soft-revoke > 14d expired), plus the analytics polling jobs.

See [features/meta-integration.md](features/meta-integration.md), [features/analytics.md](features/analytics.md).

---

## Shopify (store conversions)

`integration/shopify`. Brand connects a store via OAuth; order webhooks with a creator's discount code redeem a coupon.

- **Entity** `shopify_integrations` (V27): `shop_domain` (unique), encrypted **non-expiring** access token, scopes.
- **OAuth** (`ShopifyConnectController`, `/shopify/oauth`): state bound to userId **and** shop domain, 10-min TTL; SSRF guard `^[a-z0-9][a-z0-9-]*\.myshopify\.com$`. Scopes `read_orders,read_products`. No refresh.
- **Webhook** (`ShopifyWebhookController`, `POST /webhooks/shopify`, public): `ShopifyWebhookSignatureVerifier` (base64 HMAC-SHA256 over raw body, `X-Shopify-Hmac-Sha256`, app-level secret, verify-first), resolve shop by `X-Shopify-Shop-Domain`, topic gate `orders/paid`/`orders/create`, idempotency `sha256(shopDomain|topic|orderId)`, → `RedemptionService.redeem`.

See [features/shopify-integration.md](features/shopify-integration.md).

---

## WooCommerce (store conversions)

`integration/woocommerce`. Same conversion outcome, **no OAuth** — receive-only.

- **Entity** `woocommerce_integrations` (V29): `site_url` (unique), encrypted **webhook secret** (a verification secret, not an API token).
- **Connect** (`WooCommerceConnectController`, `POST /woocommerce/connect`): brand pastes the site URL + per-webhook secret; URL normalized to match `X-WC-Webhook-Source` at webhook time.
- **Webhook** (`WooCommerceWebhookController`, `POST /webhooks/woocommerce`, public): resolve site → decrypt per-integration secret → verify (`X-WC-Webhook-Signature`, base64 HMAC-SHA256) → topic gate `order.created`/`order.updated` → idempotency `sha256(siteUrl|topic|orderId)` → `RedemptionService.redeem`.

See [features/woocommerce-integration.md](features/woocommerce-integration.md).

---

## Generic conversion tracking

`integration/tracking`. Store-agnostic conversion + coupon webhooks and UTM click attribution.

- **Entities**: `conversion_webhook_secrets` (V31, per-workspace server-generated secret), `utm_campaigns` (V23, tracking links + click/conversion/revenue counters).
- **Controllers**: `ConversionWebhookController` (`POST /webhooks/redemption`, `/webhooks/conversion`, HMAC `X-Influora-Signature`; `GET /track/click/{utmId}` unsigned → 302 redirect), `ConversionWebhookSecretController` (brand: generate/revoke), `StoreIntegrationStatusController` (brand: status/disconnect).
- Unknown identifier / bad sig / no secret all collapse to one `INVALID_WEBHOOK_SIGNATURE` (401, no enumeration). Idempotency keys are workspace-namespaced.

See [features/conversion-tracking.md](features/conversion-tracking.md).

---

## MSG91 (transactional email)

`integration/msg91`. **Email only — there is no SMS integration** despite UI toggles.

- `Msg91EmailClient` — JDK `HttpClient`, `POST https://control.msg91.com/api/v5/email/send`, `authkey` header, `{to, from, template_id, variables}`. Templates live in MSG91 (referenced by slug). **Mock mode** when the auth key is blank (dev logs `[MOCK]`, marks outbox SENT).
- Consumed by the `EmailWorker` outbox poller. OTPs, password resets, and lifecycle notifications go through here. Config `influora.msg91.*`.

See [features/notifications.md](features/notifications.md).

---

## Cloudflare R2 (object storage)

`integration/storage`, AWS S3 SDK v2. Config `R2Properties` (`influora.r2`): account/keys, `bucket-name` (default `influora-dev`), endpoint, `public-url`, `presign-expiry-seconds=900`, `max-video-bytes=524288000` (500MB).

- `R2StorageService`: `presignGet` (15-min download links — the workhorse), `putStream` (server-side streaming upload, size-capped), `putBytes` (PDFs), `deleteObject`. `presignPut` exists but is dead code.
- Key schemes: `deliverables/{id}/v{n}/{ulid}-{name}`, `proof/{userId}/{deliverableId}/{ulid}`, `creators/{id}/cover/{ulid}`, contract/invoice `pdf_r2_key`.
- Media bytes never touch MySQL — only R2 keys + metadata. Downloads are always short-lived presigned GETs.

See [features/uploads-storage.md](features/uploads-storage.md).

---

## influora-ai (Python AI service)

The LLM host. Three endpoints: `/chat` (Meera SSE, browser connects directly), `/internal/brand-safety` (GARM), `/internal/trendspark/nudge` (phrasing). Auth: ES256 service/stream tokens (Spring→Python, verified via JWKS) and service token + HMAC (Python→Spring). See [ai.md](ai.md).

---

## Configuration summary

| Provider | Config prefix | Fail mode if unconfigured |
|---|---|---|
| Razorpay | `influora.razorpay` | mock stubs (dev); webhook fails closed |
| Meta | `influora.meta` | token bean throws at startup if key blank |
| Shopify | `influora.shopify` | webhook fails closed |
| WooCommerce | per-integration secret | webhook fails closed |
| Conversion | per-workspace secret | webhook fails closed |
| MSG91 | `influora.msg91` | mock mode (logs only) |
| R2 | `influora.r2` | `STORAGE_UNAVAILABLE` (503) on upload/download |
| influora-ai | `influora.{meera,brand-safety-ai,trendspark-ai,brand-safety-service-token,jwks}` | localhost defaults; JWKS bean throws if PEM blank |

See [environment.md](environment.md) for the full variable list.
