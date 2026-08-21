# P2-13 — Affiliate Per-Campaign Commission Rate: Model Spec (Rohan sign-off)

**Date:** 2026-07-12 · **Author:** Rohan Sharma (CFO) · **For:** Vikram (impl) · **Packet:** `wiki/reports/2026-07-12/tasks/P2-13-affiliate-rates.md`
**Builds on:** `wiki/decisions/budget-proposals/2026-07-07-affiliate-commission-rate-and-settlement-cost-review.md` (Item 1 — flagged this as near-term backlog, suggested `commission_rate DECIMAL(5,4)` on `campaigns`).

## Current state (verified against code, not docs)
`AffiliateEarningsService.java:73-100` — flat `DEFAULT_COMMISSION_RATE = 0.10` applied to every redemption's `orderAmount` via `validateAndCompute` (line 334-338). No column anywhere stores a per-campaign rate. `Campaign.java` has `budgetMin`/`budgetMax`/`currency` but no commission field.

## Data model
Add one nullable column, on `campaigns` (not `coupon_codes` — a campaign's commission economics should be set once at the campaign level, not per-coupon; a campaign can have many coupons and they must share one rate to avoid the brand accidentally paying different creators different rates on the same campaign):

```sql
-- V50__campaign_commission_rate.sql
ALTER TABLE campaigns ADD COLUMN commission_rate DECIMAL(5,4) NULL;
-- NULL = "not set" -> falls back to platform default (10%). Never defaults to 0 or NOT NULL DEFAULT,
-- because a silent 0.0000 would look like "brand configured zero commission" instead of "unset".
```

`Campaign.java`: add `private BigDecimal commissionRate;` (nullable, no default), getter/setter, column `commission_rate`, `precision = 5, scale = 4`.

## Default / override rule
- **Default:** `campaign.getCommissionRate() == null` → use existing `AffiliateEarningsService.DEFAULT_COMMISSION_RATE` (0.10) unchanged. This is the fallback for every campaign created before this change and every campaign where the brand doesn't set one.
- **Override:** if `campaign.getCommissionRate() != null`, `AffiliateEarningsService.validateAndCompute` uses that value instead of the constant. No coupon-level override in this pass — one rate per campaign, applied uniformly to every coupon/redemption under it. (Coupon-level granularity is a possible future refinement; out of scope here, keeps the schema to the single column Kavya already suggested on 2026-07-07.)
- **Range cap:** `0.00` to `0.30` (30%) inclusive, enforced server-side. Rationale: platform's only existing take-rate precedent is the 15% brand-side `RazorpayProperties.platformFeePercent` (escrow funding fee); 30% is 2x that — generous headroom for high-margin/DTC verticals without an unbounded self-service field. A brand wanting a rate above 30% is a manual/negotiated case, not a self-service one — reject with `400 COMMISSION_RATE_OUT_OF_RANGE` and require Rohan/Swapnil sign-off + a direct DB change for that (rare) case.

## Who can set it
- **Workspace OWNER/ADMIN role only** (brand-side), via the existing campaign create/update endpoint — add optional `commissionRate: BigDecimal` field to the campaign create/update request DTO. Validate range (0.00–0.30) server-side; reject out-of-range with 400 before persisting.
- **Not creator-settable.** Creators never see or set this field — no creator-facing route touches `commission_rate`.
- **Not settable after a campaign has SETTLED earnings retroactively changing past payouts** — a rate change only affects redemptions recorded *after* the change (existing `AffiliateEarning` rows already persisted `commissionAmount` at record time, per `doRecordEarning`, and are never recomputed). No new guard code needed for this — it falls out naturally from `validateAndCompute` reading the campaign's *current* rate only at the moment of a new redemption, but flag it in the endpoint's response/UI copy so brands don't expect a rate change to be retroactive.

## Disclosure (carried over from the 2026-07-07 review, still open)
Brand-facing campaign setup UI should surface the effective commission rate (default 10% or the configured override) at campaign-creation time, per Item 1.2 of the 2026-07-07 cost review. This is a follow-up UI task for Ananya, not blocking this backend acceptance criteria — flagging again since it was flagged once already and never scheduled.

## Audit trail
`AffiliateEarningsService.doRecordEarning`'s existing `auditLogService.recordMoneyEvent(...)` call already logs `commissionAmount`. Add `"commissionRate", ctx.coupon()... campaign rate used` to that event's metadata map so the audit log records *which* rate applied to each earning — cheap addition, closes a reconciliation gap before this becomes a live multi-rate system.

## Acceptance criteria for Vikram
1. `V50__campaign_commission_rate.sql` migration adds nullable `commission_rate DECIMAL(5,4)` to `campaigns`.
2. `Campaign.java` gets the mapped field.
3. Campaign create/update DTO + controller validate `commissionRate` in `[0.00, 0.30]`, 400 `COMMISSION_RATE_OUT_OF_RANGE` otherwise; `null`/absent is always valid (falls back to default).
4. `AffiliateEarningsService.validateAndCompute` resolves `campaign.getCommissionRate()` (via existing `campaignRepository` lookup keyed by `coupon.getCampaignId()`) with fallback to `DEFAULT_COMMISSION_RATE` when null.
5. Audit event includes the rate actually used.
6. Existing flat-10% behavior is unchanged for every campaign that doesn't set an override (regression safety — this is a real-money path).
7. Kavya QA → Meera `mvn -o test` green (including any existing `AffiliateEarningsServiceTest` — extend it with a per-campaign-override-rate test case).

**Sign-off:** Rohan · 2026-07-12 · model approved for Vikram to implement.
