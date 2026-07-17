# Feature: Billing & Subscriptions

**Business Purpose** — A two-tier SaaS subscription (FREE / PRO) for brand workspaces, via Razorpay Subscriptions. The plan gates entitlements (AI credits, seats, tracked-creator and analytics caps, export/templates) and sets the brand-side fee (Pro 7% vs global 10%). It's the recurring-revenue lever.

**Who uses it** — Brands (subscribe/cancel), admins (comp/override/metrics).

## User Roles
Brand (checkout/cancel), Admin SUPER_ADMIN (comp/override/metrics, MFA).

## Permissions
Checkout/cancel → brand workspace. Comp/override → SUPER_ADMIN.

## Business Flow
```
Brand → /billing/checkout (PRO) → Razorpay hosted checkout (short_url) → pay
  → [intended] subscription.* webhook → local ACTIVE Pro row → entitlements apply
Cancel → cancel_at_period_end (stays ACTIVE until period end)
Renewal/dunning jobs advance/halt periods
```
`SubscriptionStatus`: ACTIVE → PAST_DUE → HALTED / CANCELLED (no TRIALING).

## Frontend
- **Pages**: `pricing`, `brand-billing-settings`.
- **Hooks**: `brand/useBilling` (react-query).
- **API**: `api.billing.*`.

## Backend
- **Controllers**: `BillingController` (`/billing`), `AdminBillingController`.
- **Service**: `service/billing/SubscriptionService`, `UsageCounterService`.
- **Jobs**: `SubscriptionDunningJob`, `SubscriptionRenewalResetJob`, `AICreditResetJob`.

## Database
`plans` (V54/V55 seed/V57), `subscriptions` (V54/V56/V63), `invoices` (V54, Doc#1 GST), `usage_counters` (V54), `usage_counter_details` (V58). See [../database.md](../database.md).

## APIs
`GET /billing/{plan,invoices,usage}`, `GET /billing/invoices/{id}/pdf`, `POST /billing/checkout`, `POST /billing/cancel`, `GET/POST /admin/billing/{subscriptions,metrics,comp,override}`.

## AI
Plan sets AI credit allotments (Free 100 / Pro 400) consumed by Meera; separate from the `UsageCounter` plan-cap mechanism.

## Notifications
`SubscriptionHaltedEvent`, `SubscriptionPaymentFailedEvent` (**both currently have no listener** — see [../known-limitations.md](../known-limitations.md)).

## Dependencies
- **Depends on**: Razorpay subscriptions, workspaces, plan gating.
- **Depended on by**: plan gating, platform fees (Pro override), analytics caps, seats, AI credits.

## Connected Files
`BillingController`, `AdminBillingController`, `SubscriptionService`, `UsageCounterService`, `domain/entity/{Plan,Subscription,Invoice,UsageCounter}`, subscription jobs.

## Execution Flow
```
Checkout: POST /billing/checkout → SubscriptionService.initiateCheckout (PRO only, fail-closed if misconfigured,
  ensureRazorpayPlanId, create subscription with notes.workspaceId, total_count=120, writes NO local row) → shortUrl
Webhook (intended): applySubscriptionWebhookUpdate (staleness guard, changePlan/setStatus/renewPeriod,
  saveAndFlush for @Version, reconcile AI credits)
```

## Error Handling
`RAZORPAY_MISCONFIGURED` (503, fail-closed), optimistic-lock retry (via `saveAndFlush` + idempotency FAILED → Razorpay retries), unparseable planCode silently defaults to PRO.

## Security
Abandoned checkout grants nothing (no local row until webhook); comp subscriptions excluded from revenue metrics; SUPER_ADMIN + MFA on admin ops.

## Performance
DB-only jobs; usage caps use race-safe dedup inserts; reset is implicit on period advance.

## Testing
Subscription service tests. Regression risks: webhook idempotency, plan resolution, usage caps.

## Production Readiness
- **Health**: 5/10 · **Completion**: ~65%
- **Known issues**: **subscription `*` webhooks are not routed** in `RazorpayWebhookController` — real Pro purchases never create a local ACTIVE row; `cancel` is unreachable for real customers; ACTIVE→PAST_DUE never triggers. The renewal/dunning jobs are effectively the only state mutators. `comp_expires_at` not enforced; `/billing/usage` period may diverge from counter period. See [../known-limitations.md](../known-limitations.md).
- **Last verified**: 2026-07-15
