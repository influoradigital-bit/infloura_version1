# API Connection Blueprint

> How the frontend, Spring API, and AI service talk. The full endpoint map + contract. Sourced from code.
> Lead: Vikram.

---

## 1. The contract (from `src/lib/api.ts`)

- **Base:** `VITE_API_BASE_URL` (default `http://localhost:8080/api/v1`). All endpoints prefixed `/api/v1`.
- **Transport:** REST / JSON over HTTPS.
- **Auth:** `Authorization: Bearer <jwt>` — `brand_token` or `creator_token` (admin uses its own).
- **Response envelope:**
  ```json
  { "success": true, "data": { }, "error": { "code": "", "message": "" },
    "meta": { "page": 1, "limit": 20, "total": 0, "hasMore": false } }
  ```
- **Pagination:** `?page=1&limit=20` → `meta`.
- **Idempotency:** mutating calls accept an `Idempotency-Key` header.
- **Realtime:** SSE on `/api/v1/stream` — events: `proposal.received`, `message.new`, `contract.signed`, `payment.released`, `deliverable.submitted`, `wallet.low_balance`.
- **Mode switch:** `VITE_API_MODE=live` uses real `fetch`; anything else uses mock fixtures. A prod build with a misconfigured mode throws `MockAuthDisabledError` (fail-closed).

### Frontend clients
| Client file | Purpose |
|---|---|
| `src/lib/api.ts` (2,331 LOC) | Main client — 31 resource groups |
| `src/lib/meera-api.ts` (384 LOC) | AI + escrow (Meera screen) |
| `src/admin/services/api-contracts.ts` (636 LOC) | Admin client |
| `src/admin/services/websocket.ts` | Admin realtime |

The 31 `api.*` groups: `auth, workspaces, onboarding, campaigns, creators, deals, messages, contracts, deliverables, wallet, creatorProfile, payments, dashboard, notifications, uploads, portfolio, analytics, creatorAnalytics, contentPerformance, campaignTracking, storeIntegrations, creatorReviews, brandReviews, metaOAuth, creatorCoupons, affiliateEarnings, creatorCampaigns, creatorDeliverables, creatorDisputes, brandDisputes, trendspark`.

---

## 2. Full backend endpoint map (55 controllers, 181 endpoints)

**Public / health**
- `HealthController` `/health`
- `JwksController` `/.well-known/jwks.json`

**Auth & identity**
- `AuthController` `/auth`: brand+creator `send-email-otp`, `verify-email`, `register`, `login`; `refresh`, `logout`, `forgot-password`, `reset-password`
- `UserController` `/users`: `/me` (get/update)
- `WorkspaceController` `/workspaces`: `/slug-check`
- `OnboardingController` `/onboarding`: brand `company|kyc|complete`; creator `socials|profile|kyc|complete`

**Campaigns & discovery**
- `CampaignController` `/campaigns`: CRUD, `/{id}/duplicate`, `/{id}/analytics`
- `CampaignTrackingController` `/campaigns/{id}`: `tracking-links`, `coupons`
- `CreatorController` `/creators`: `search`, `featured`, `suggestions`, `{id}`, `{id}/save`, `{id}/invite`, `profile/{usernameOrId}`

**Deals, contracts, deliverables**
- `DealController` `/deals`: `{id}`, `accept`, `reject`, `counter`, `{id}/messages`, `messages/read`, `{id}/deliverables`, `{id}/disputes`
- `ContractController` `/contracts`: `unsigned`, `{id}`, `{id}/sign`, `{id}/pdf-download-url`
- `BrandDeliverableController` `/deliverables`: `{id}`, `approve`, `revise`
- `CreatorDeliverableController` `/creator/deliverables`: `status`, `submit`, `metrics`, `mark-posted`
- `DeliverableMetricController` `/deliverables`: `{milestoneId}/metrics`

**Money**
- `WalletController` `/wallet`: `balance`, `topup`, `withdraw`, `transactions`
- `EscrowController` `/wallet/escrow`: `fund`, `release`, `refund`, `payout`, `{id}`
- `RazorpayWebhookController` `/webhooks/razorpay`
- `BrandPlatformFeeController` `/brand/platform-fee`, `CreatorPlatformFeeController` `/creator/platform-fee`

**Analytics**
- `AnalyticsController` `/analytics/creators/{id}`: `metrics`, `scores`, `demographics`, `media`
- `CreatorAnalyticsController` `/creator/analytics/me`: same, self-scope
- `DashboardController` `/dashboard`: `actions`, `pipeline`

**Affiliate / coupons / tracking**
- `CreatorCouponController` `/creator/coupons`, `CreatorAffiliateEarningController` `/creator/affiliate-earnings`
- `ConversionWebhookController`: `/webhooks/redemption`, `/webhooks/conversion`, `/track/click/{utmCampaignId}`
- `ConversionWebhookSecretController` `/webhook-secret/generate`

**Reviews & disputes**
- `BrandReviewController` `/brand/reviews`, `CreatorReviewController` `/creator/reviews` (list, `received`, `{id}/flag`)
- `BrandDisputeController` `/brand/disputes/list`, `CreatorDisputeController` `/creator/disputes`

**Notifications & profile**
- `NotificationController` `/notifications`: list, `read`, `unsubscribe`
- `MeCreatorProfileController` `/me/creator-profile`; `PortfolioController` `/portfolio/*`, `/me/portfolio` (`sync`, `cover`, `analytics`)

**Integrations**
- `MetaOAuthController` `/meta/oauth`: `authorize`, `callback`
- `ShopifyConnectController` `/shopify/oauth`, `ShopifyWebhookController` `/webhooks/shopify`
- `WooCommerceConnectController` `/woocommerce/connect`, `WooCommerceWebhookController` `/webhooks/woocommerce`
- `StoreIntegrationStatusController` `/integrations/store`: `status`, `disconnect`

**AI**
- `MeeraController` `/meera`: `sessions`, `sessions/{id}/messages`, `credits`, `brand-profile`
- `MeeraInternalController` `/internal/meera` (service-token only): `show_creators`, `calculate_budget`, `create_campaign`, `request_payment`, `confirm_launch`, `messages`
- `TrendSparkController` `/brand/trendspark`: `nudge`, `nudge/{id}/click`, `nudge/{id}/purchase`

**Admin** — see `03-ADMIN-CODE.md`.

---

## 3. Frontend↔backend wiring — verified

Every path the client calls resolves to a real controller base. Spot-check:

| Client call | Controller | ✔ |
|---|---|---|
| `/auth/brand/login` | `AuthController` | ✔ |
| `/campaigns`, `/campaigns/{id}` | `CampaignController` | ✔ |
| `/creator/campaigns/{id}/apply` | `CreatorCampaignController` | ✔ |
| `/deals/{id}/accept|counter` | `DealController` | ✔ |
| `/wallet/escrow/fund|release` | `EscrowController` | ✔ |
| `/meta/oauth/authorize|callback` | `MetaOAuthController` | ✔ |
| `/shopify/oauth`, `/woocommerce/connect` | Shopify/Woo controllers | ✔ |
| `/me/portfolio/sync|cover|analytics` | `PortfolioController` | ✔ |
| `/notifications/read-all` | `NotificationController` | ✔ |

**Result: no orphaned client calls.**

---

## 4. Service-to-service (Spring → AI)

The browser never calls the AI service for privileged work. Flow (from `influora-ai/app/clients/spring.py` + `MeeraInternalController`):

```
SPA → POST /api/v1/meera/sessions/{id}/messages (JWT)
  → Spring mints short-lived service token (aud=influora-internal, TTL ≤ 5m)
  → Spring → AI service (signed: HMAC of method+path+body + timestamp + nonce)
  → AI runs tool loop; calls back to /api/v1/internal/meera/* with the same signed scheme
  → AI streams tokens; Spring relays to browser via SSE (/api/v1/stream)
```
Replay protection via `NonceCache`; every internal call is idempotency-keyed (`idempotency_key_for(tool_use_id, workspace_id)`).
