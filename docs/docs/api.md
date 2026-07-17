# API Reference

All endpoints are under the context path **`/api/v1`** (paths below omit it). Responses use the `ApiResponse` envelope `{ success, data, error, meta }` **except admin controllers** (`/admin/*`), which return raw DTOs and paginate via `X-Total-Count`/`X-Page`/`X-Page-Size` headers.

**Auth column legend**: `Public` = no auth · `Brand`/`Creator` = user JWT of that type · `Admin` = admin JWT (MFA-gated per role) · `Internal` = service token + HMAC (Python→Spring) · `Webhook` = HMAC signature (no JWT).

Authorization is enforced in the service layer (workspace/creator context), not by URL role rules. Path-param ids are validated against the caller's tenant; foreign ids return a generic `*_NOT_FOUND`.

---

## Authentication (`AuthController` `/auth`, `AdminAuthController` `/admin/auth`)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/auth/{brand\|creator}/send-email-otp` | Public | Send email OTP |
| POST | `/auth/{brand\|creator}/verify-email` | Public | Verify OTP |
| POST | `/auth/{brand\|creator}/register` | Public | Register (auto-login, sets refresh cookie) |
| POST | `/auth/{brand\|creator}/login` | Public | Login |
| POST | `/auth/refresh` | Cookie | Rotate refresh → new access token |
| POST | `/auth/logout` | Brand/Creator | Revoke all refresh tokens |
| POST | `/auth/forgot-password` / `/reset-password` | Public | Password reset |
| POST | `/admin/auth/login` / `refresh` / `logout` | Admin | Admin session (+ MFA) |
| GET | `/admin/auth/me` | Admin | Current admin |
| POST | `/admin/auth/mfa/setup` / `mfa/verify` | Admin | TOTP enrollment |
| GET | `/.well-known/jwks.json` | Public | EC public key (service/stream tokens) |
| GET | `/health` | Public | Liveness |

Details: [authentication.md](authentication.md).

---

## Workspaces & members

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/workspaces/slug-check` | Public | Slug availability |
| GET | `/workspaces/members` | Brand | List members |
| POST | `/workspaces/members/invite` | Brand (OWNER/ADMIN) | Invite member |
| GET/POST | `/workspaces/invites` | Brand (OWNER/ADMIN) | List/revoke invites |
| POST | `/workspaces/members/{id}/deactivate` | Brand (OWNER/ADMIN) | Deactivate member |

Details: [features/workspaces-members.md](features/workspaces-members.md).

---

## Campaigns

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/campaigns` | Brand | List (page/limit/status/search/sort) |
| POST | `/campaigns` | Brand | Create (201) |
| GET | `/campaigns/{id}` | Brand | Detail |
| PATCH | `/campaigns/{id}` | Brand | Update (DRAFT→ACTIVE charges publish fee) |
| DELETE | `/campaigns/{id}` | Brand (OWNER) | Delete (DRAFT only) |
| POST | `/campaigns/{id}/duplicate` | Brand | Duplicate (201) |
| GET | `/campaigns/{id}/analytics` | Brand | Creator-reported aggregate |
| GET | `/campaigns/{id}/export?format=csv\|pdf` | Brand (Pro) | Export report |
| GET | `/creator/campaigns` | Creator | Browse open campaigns |
| GET | `/creator/campaigns/{id}` | Creator | Detail |
| POST | `/creator/campaigns/{id}/apply` | Creator | Apply (201) |
| GET/POST/DELETE | `/campaign-templates` `/{id}` | Brand (Pro for save) | Templates |
| GET/POST | `/campaigns/{id}/tracking-links` · `/coupons` | Brand | UTM & coupons |
| GET | `/admin/campaigns` | Admin | Ops list |

Details: [features/campaigns.md](features/campaigns.md).

---

## Marketplace / discovery (`CreatorController` `/creators`)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/creators` | Brand | Flat search (13 filters) |
| GET | `/creators/search` | Brand | Search + facets |
| GET | `/creators/featured` | Brand | Featured (by category) |
| POST | `/creators/suggestions` | Brand | AI-lite suggestions for a campaign |
| GET | `/creators/{username}/similar` | Brand | Similar creators |
| GET | `/creators/{creatorId}` · `/creators/profile/{usernameOrId}` | Brand | Profile |
| POST | `/creators/{creatorId}/save` | Brand | Toggle shortlist |
| POST | `/creators/{creatorId}/invite` | Brand | Invite to campaign (201) |

Details: [features/marketplace-discovery.md](features/marketplace-discovery.md).

---

## Deals / collaborations (`DealController` `/deals`)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/deals` | Brand/Creator | List (status filter) |
| GET | `/deals/{id}` | Brand/Creator | Detail |
| POST | `/deals` | Brand | Propose (201) |
| POST | `/deals/{id}/accept` | Brand/Creator | Accept (Idempotency-Key) |
| POST | `/deals/{id}/reject` | Brand/Creator | Reject |
| POST | `/deals/{id}/counter` | Brand/Creator | Counter-offer (Idempotency-Key) |
| GET/POST | `/deals/{dealId}/messages` | Brand/Creator | Deal-room chat |
| POST | `/deals/{dealId}/messages/read` | Brand/Creator | Mark read |
| GET | `/deals/{dealId}/deliverables` | Brand/Creator | Deliverables |
| POST | `/deals/{dealId}/disputes` | Brand/Creator | Open dispute (201) |

Details: [features/collaborations-deals.md](features/collaborations-deals.md).

---

## Contracts (`ContractController` `/contracts`)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/contracts` | Brand | Generate (201, milestones required) |
| GET | `/contracts` | Brand/Creator | List |
| GET | `/contracts/unsigned` | Creator | Unsigned |
| GET | `/contracts/{id}` | Brand/Creator | Detail |
| POST | `/contracts/{id}/sign` | Brand/Creator | Sign (Idempotency-Key) |
| GET | `/contracts/{id}/pdf-download-url` | Brand/Creator | Presigned PDF |

Details: [features/contracts.md](features/contracts.md).

---

## Deliverables

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/deliverables/{id}` | Brand | Detail (presigned files) |
| POST | `/deliverables/{id}/approve` · `/revise` | Brand | Review actions |
| GET | `/creator/deliverables?collaboration_id=` | Creator | List |
| POST | `/creator/deliverables/{id}/upload` | Creator | Upload version (multipart, 201) |
| GET | `/creator/deliverables/{id}/status` | Creator | Status + allowed actions |
| POST | `/creator/deliverables/{id}/submit` | Creator | Submit for review |
| POST | `/creator/deliverables/{id}/metrics` | Creator | Report metrics |
| POST | `/creator/deliverables/{id}/proof` | Creator | Upload proof screenshot (201) |
| POST | `/creator/deliverables/{id}/mark-posted` | Creator | Mark live |
| PUT | `/deliverables/{milestoneId}/metrics` | Creator | Legacy milestone metrics |

Details: [features/deliverables.md](features/deliverables.md).

---

## Reviews

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/brand/reviews` | Brand | Rate creator (201) |
| GET | `/brand/reviews/received` | Brand | Reviews about this brand |
| POST | `/brand/reviews/{id}/flag` | Brand | Flag a review (201) |
| POST | `/creator/reviews` | Creator | Rate brand |
| GET | `/creator/reviews/received` | Creator | Reviews about this creator |
| POST | `/creator/reviews/{id}/flag` | Creator | Flag |

Details: [features/reviews.md](features/reviews.md).

---

## Wallet, escrow & payouts

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/wallet/balance` · `/wallet` | Brand/Creator | Balance / summary |
| POST | `/wallet/topup` | Brand (OWNER/ADMIN) | Razorpay top-up (Idempotency-Key) |
| POST | `/wallet/withdraw` | Creator | Withdraw (queues payout) |
| GET | `/wallet/transactions` | Creator | Ledger |
| POST | `/wallet/escrow/fund` | Brand (OWNER/ADMIN) | Fund escrow (Idempotency-Key) |
| GET | `/wallet/escrow/{id}` | Brand/Creator | Hold detail |
| POST | `/wallet/escrow/release` | Brand | Release milestone |
| POST | `/wallet/escrow/refund` | Brand | Refund hold |
| POST | `/wallet/escrow/payout` | Brand | Queue RazorpayX payout |

Details: [features/wallet.md](features/wallet.md), [features/escrow.md](features/escrow.md), [features/payouts.md](features/payouts.md).

---

## Billing, fees & invoicing

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/billing/plan` · `/invoices` · `/usage` | Brand | Plan / invoices / usage |
| GET | `/billing/invoices/{id}/pdf` | Brand | Invoice PDF |
| POST | `/billing/checkout` | Brand | Start Pro checkout (Razorpay) |
| POST | `/billing/cancel` | Brand | Cancel at period end |
| GET | `/brand/platform-fee` · `/creator/platform-fee` | Brand/Creator | View fee |
| GET | `/billing/campaign-invoices` · `/commission-invoices` (+`/{id}/pdf`) | Brand | Marketplace invoices |
| GET | `/creator/{campaign-invoices,commission-invoices}` | Creator | Creator-side invoices |
| GET/PUT | `/admin/finance/fee-config` (+`/history`) | Admin (SUPER, MFA) | Fee config |
| GET/POST | `/admin/billing/{subscriptions,metrics,comp,override}` | Admin (SUPER) | Subscription admin |

Details: [features/billing-subscriptions.md](features/billing-subscriptions.md), [features/platform-fees.md](features/platform-fees.md), [features/invoicing-gst.md](features/invoicing-gst.md).

---

## Affiliate, coupons & tracking

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/creator/affiliate-earnings` | Creator | Earnings + summary |
| GET | `/creator/coupons` | Creator | Coupons + tracking URLs |
| POST | `/webhooks/redemption` · `/webhooks/conversion` | Webhook | Generic conversion webhooks (HMAC) |
| GET | `/track/click/{utmCampaignId}` | Public | Record click, 302 redirect |
| POST | `/webhook-secret/generate` · DELETE `/webhook-secret` | Brand | Manage conversion secret |
| GET | `/integrations/store/status` · DELETE `/integrations/store/disconnect` | Brand | Store status |

Details: [features/affiliate-coupons.md](features/affiliate-coupons.md), [features/conversion-tracking.md](features/conversion-tracking.md).

---

## Analytics

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/analytics/creators/{creatorId}/{metrics\|scores\|demographics}` | Brand | Creator analytics (usage-capped) |
| GET | `/creator/analytics/me/{metrics\|scores\|demographics\|media}` | Creator | Self analytics |

Details: [features/analytics.md](features/analytics.md).

---

## Notifications

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/notifications` | Any user | List (unread-first) |
| POST | `/notifications/read` | Any user | Mark one read |
| POST | `/notifications/unsubscribe` | Any user | Opt out of an event type |

> Frontend also calls `/notifications/read-all` and `/notifications/preferences` — these do not exist on the backend. See [known-limitations.md](known-limitations.md).

Details: [features/notifications.md](features/notifications.md).

---

## AI / Meera / TrendSpark

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/meera/turn` | Brand | Start a turn → stream token + public chat URL |
| POST | `/internal/meera/{show_creators\|calculate_budget\|create_campaign\|request_payment\|confirm_launch}` | Internal | Tool-call execution |
| POST | `/internal/meera/messages` | Internal | Assistant message write-back |
| GET | `/brand/trendspark/nudge` | Brand | Nudge (200 or 204) |
| POST | `/brand/trendspark/nudge/{id}/click` · `/purchase` | Brand | Nudge callbacks |

Details: [ai.md](ai.md), [features/meera-ai.md](features/meera-ai.md), [features/trendspark.md](features/trendspark.md).

---

## Integrations (OAuth + webhooks)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/meta/oauth/authorize` · `/callback` | Creator | Instagram OAuth |
| GET | `/shopify/oauth/authorize` · `/callback` | Brand | Shopify OAuth |
| POST | `/woocommerce/connect` | Brand | Connect Woo (secret) |
| POST | `/webhooks/razorpay` | Webhook | Payment/payout webhooks |
| POST | `/webhooks/shopify` | Webhook | Order webhooks |
| POST | `/webhooks/woocommerce` | Webhook | Order webhooks |

Details: [external-services.md](external-services.md).

---

## Admin console (`/admin/*`)

Raw DTOs; MFA-gated by role. Groups: `/admin/dashboard/*` (pulse/financial/operations), `/admin/brands`, `/admin/creators` (KYC/suspend/reinstate/tier), `/admin/campaigns`, `/admin/finance/*` (revenue/escrow/payouts/reconciliation/fee-config), `/admin/support/tickets/*`, `/admin/moderation/*` (content flags), `/admin/disputes` (+`/{id}/resolve`), `/admin/billing/*`, `/admin/audit`.

Details: [features/admin-dashboard.md](features/admin-dashboard.md).

---

## Common error codes

| HTTP | Code (examples) | Meaning |
|---|---|---|
| 400 | `INVALID_*`, `WEAK_PASSWORD`, `INVALID_OTP` | Validation / bad input |
| 401 | `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN`, `MFA_REQUIRED`, `INVALID_WEBHOOK_SIGNATURE` | Auth failure |
| 402 | `UPGRADE_REQUIRED`, `INSUFFICIENT_FUNDS`, `CREDITS_EXHAUSTED` | Payment/plan required |
| 403 | `WRONG_USER_TYPE`, `FORBIDDEN`, `EMAIL_NOT_VERIFIED`, `ON_BEHALF_WORKSPACE_MISMATCH` | Authorization |
| 404 | `*_NOT_FOUND` | Missing or cross-tenant (no enumeration oracle) |
| 409 | `EMAIL_ALREADY_EXISTS`, `ALREADY_APPLIED`, `*_CONFLICT`, `ESCROW_NOT_FUNDED` | Conflict / state |
| 429 | rate-limit / `DAILY_ACTION_LIMIT_EXCEEDED` / `WITHDRAWAL_RATE_LIMIT` | Throttled |
| 503 | `STORAGE_UNAVAILABLE`, `RAZORPAY_MISCONFIGURED` | Dependency unavailable |
