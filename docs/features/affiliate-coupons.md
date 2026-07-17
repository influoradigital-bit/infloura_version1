# Feature: Affiliate Earnings & Coupons

**Business Purpose** — Performance marketing: a creator gets a unique discount coupon for a campaign; when a shopper uses it at the brand's checkout, the redemption is tracked and the creator accrues commission. This rewards creators for actual sales, complementing flat campaign fees.

**Who uses it** — Creators (view coupons + earnings), brands (create coupons via campaign tracking, receive conversions), the settlement/reconciliation jobs.

## User Roles
Creator (view own coupons/earnings), Brand (create coupons, connect store), System (accrue/settle).

## Permissions
Coupon creation is brand-driven (`CampaignTrackingController`). Earnings view is creator-self.

## Business Flow
```
Brand creates coupon (per campaign+creator) → shopper uses code at checkout
  → store webhook → RedemptionService.redeem (validate, discount, CouponRedemption, idempotent)
  → [intended sync] AffiliateEarning accrued (PENDING)   ← actually created by hourly reconciliation job
  → monthly AffiliateSettlementJob → SETTLED (internal only, no real disbursement yet)
```

## Frontend
- **Pages**: `creator-coupons`, `creator-affiliate-earnings`; brand `campaigns/tracking/*`.
- **Components**: `creator/AffiliateEarningsView`.

## Backend
- **Controllers**: `CreatorCouponController`, `CreatorAffiliateEarningController`, `CampaignTrackingController`.
- **Services**: `AffiliateEarningsService`, `service/tracking/RedemptionService`, `CouponCodeService`.
- **Jobs**: `AffiliateEarningReconciliationJob` (hourly), `AffiliateSettlementJob` (monthly).

## Database
`coupon_codes` (V24), `coupon_redemptions` (V24), `affiliate_earnings` (V28), `affiliate_settlement_batches` (V28). See [../database.md](../database.md).

## APIs
`GET /creator/coupons`, `GET /creator/affiliate-earnings`, coupon creation via `POST /campaigns/{id}/coupons`, store webhooks feed redemption.

## AI
Not involved.

## Notifications
None specific.

## Dependencies
- **Depends on**: store integrations (Shopify/Woo/conversion webhooks), campaigns (commission rate), wallet (money-event recording).
- **Depended on by**: creator earnings view.

## Connected Files
`AffiliateEarningsService`, `RedemptionService`, `CouponCodeService`, `CreatorCouponController`, `CreatorAffiliateEarningController`, `domain/entity/{CouponCode,CouponRedemption,AffiliateEarning,AffiliateSettlementBatch}`, affiliate jobs.

## Execution Flow
```
Redeem: store webhook (HMAC) → RedemptionService.redeem → validateCode (workspace-scoped, expiry, usage_limit)
  → calculateDiscount (percentage/fixed) → CouponRedemption (idempotent) → recordMoneyEvent
Accrue: AffiliateEarningReconciliationJob (hourly :15) → orphaned redemptions >30min → recordEarning
  → commission = orderAmount * rate (campaign.commissionRate or DEFAULT 0.10) HALF_UP → PENDING
Settle: AffiliateSettlementJob (monthly 05:00 1st) → per creator sum PENDING/FAILED → SETTLED
```

## Error Handling
`UNSUPPORTED_DISCOUNT_TYPE` (500), duplicate redemption blocked by `UNIQUE(redemption_id)`; idempotency keys workspace-namespaced (cross-tenant fix).

## Security
Workspace-scoped code validation (codes are only unique per workspace); redemption idempotent; earnings replay-guarded by `redemption_id` unique.

## Performance
Reconciliation backfill is DB-only; settlement batches per creator.

## Testing
Affiliate/redemption tests. Regression risks: commission math, workspace-scoped validation, idempotency.

## Production Readiness
- **Health**: 5/10 · **Completion**: ~65%
- **Known issues**: the advertised **synchronous accrual does not exist** — earnings are created only by the hourly backfill job (≥30min lag; WARNs every run); settlement is **not period-bounded** (sweeps entire backlog, misstating the month); currency hardcoded `INR`; settlement is internal-ledger only (no real disbursement). See [../known-limitations.md](../known-limitations.md).
- **Last verified**: 2026-07-15
