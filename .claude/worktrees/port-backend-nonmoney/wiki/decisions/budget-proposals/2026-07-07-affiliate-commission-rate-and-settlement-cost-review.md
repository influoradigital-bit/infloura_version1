# Rohan Cost Review — Wave D Task D4 (Affiliate Earnings + Settlement)

**Date:** 2026-07-07
**Reviewer:** Rohan Sharma (CFO)
**Trigger:** `wiki/tech/REMAINING_WORK_PLAN.md` standing responsibility ("D4 ... Money — Kabir load-bearing + Rohan cost review"); Kavya's QA gate (`wiki/errors/wave-d-task-d4-affiliate-earnings-qa-review.md`) flagged two items requiring explicit sign-off before production.
**Files reviewed:** `AffiliateEarningsService.java`, `AffiliateSettlementJob.java`, `V28__affiliate_earnings_settlement.sql`, `RazorpayProperties.java`, `AmountDerivationService.java`, `application.yml`, `wave-d-task-d4-affiliate-earnings-qa-review.md`

---

## VERDICT: NEEDS MORE WORK BEFORE PRODUCTION LAUNCH (not blocking merge to feature branch)

Both items Kavya flagged are real cost/financial-control gaps, not just documentation nits. Recommendation splits: **ship the flat rate as a documented placeholder is acceptable for a bit longer, but the settlement/disbursement gap needs an explicit decision now, before this reaches a live creator.**

---

## ITEM 1 — Flat 10% affiliate commission rate

### What the platform already charges (ground truth from code, not docs)

Grepped every fee constant in `influora-api`. The **only live, wired take-rate** in this codebase is:

- `RazorpayProperties.platformFeePercent` = **15.00%**, default in `application.yml:133` (`PLATFORM_FEE_PERCENT:15.00`) and hardcoded fallback in `RazorpayProperties.java:24`.
- Applied in `AmountDerivationService.deriveForCampaignIntent` (line 68-72): `fee = base * feePercent / 100`, added on top of `base` (product price × creator count) to produce the brand's `total` chargeable amount at escrow-fund time.
- This is a **brand-side fee** — charged when a brand funds escrow for a campaign, on the `product_price × creatorCount` base. It has nothing to do with the affiliate/coupon-redemption flow structurally, but it IS the platform's only existing precedent for "what percentage does Influora already take out of a brand's spend."

Note: `docs/BACKEND-API-SPEC.md:1869` shows an example payload with `"platformFeePercent": 10` and `docs/BACKEND-API-SPEC.md:2603` shows `PLATFORM_FEE_PERCENT=5` — these are **stale/inconsistent doc artifacts**, not what ships. The authoritative value is the live `application.yml` default: **15%**. Docs should be corrected but that's a Vikram/doc-hygiene item, not a blocker here.

### Does 10% affiliate commission stack into an uncompetitive total?

The two fees are structurally separate line items on different money flows:

- **Brand pays:** `product_price × creatorCount × 1.15` into escrow (15% platform fee, brand-side, at campaign-funding time).
- **Affiliate commission (D4):** 10% of `orderAmount` on a qualifying coupon redemption — this is a **separate downstream commerce event** (a customer buying a product via the creator's coupon code on the brand's own store, tracked via Shopify/UTM), not a cut of the same escrow-funded campaign spend. It is paid **out of the brand's own store revenue**, not deducted from the campaign budget the 15% fee already applies to.

So today, these do **not literally stack into one combined percentage on one transaction** — a brand doesn't pay `15% + 10% = 25%` on the same dollar. They're two different economic contexts: platform SaaS/matchmaking fee (15%, on campaign spend) vs. affiliate revenue-share (10%, on incremental sales the creator drove). That said, from the brand's *total cost of running an influencer program* perspective, both are real costs of using Influora, and a brand comparing this to competitors (typical affiliate/influencer platforms run 5-20% commission depending on vertical, and SaaS platform fees in the 10-20% range) would evaluate them together. At 15% platform fee + 10% affiliate commission, Influora is not undercut by either number in isolation, but a brand running BOTH a funded campaign AND a heavy affiliate/coupon program simultaneously is paying into two separate 10-15% buckets — worth knowing, not alarming at current scale.

**Bottom line:** 10% is not obviously mispriced relative to the platform's own 15% precedent (it's actually lower), and it doesn't mechanically compound with the escrow fee since they tax different money flows. There's no evidence 10% pushes total brand cost into "uncompetitive" territory.

### Recommendation: ship with the flat placeholder, revisit later — WITH ONE CONDITION

- **Proceed as-is** for the flat 10% `DEFAULT_COMMISSION_RATE` placeholder. It is conservative, sits below the platform's own 15% precedent, is clearly documented as `TODO(follow-up)` in javadoc, and does not require an unreviewed schema addition to ship.
- **Condition:** this must be revisited before the platform has more than a handful of active affiliate/coupon campaigns running concurrently, because:
  1. Different verticals (FMCG vs. high-ticket electronics vs. subscription) have very different sane commission ranges — a universal flat rate will eventually be wrong for some segment.
  2. There is currently no way for a brand to negotiate or see this rate before it applies (it's invisible until a payout/report surfaces it) — that's a trust/disclosure risk, not just a pricing risk. Recommend Nisha/product surface the 10% rate explicitly in brand-facing campaign setup copy even while it's still a global constant, so it's disclosed rather than silently applied.
  3. Per-campaign configurability (a `commission_rate DECIMAL(5,4)` column on `campaigns` or `coupon_codes`, per Kavya's suggested schema shape) should be scheduled as a follow-up task in the next wave's backlog, not left indefinite. I'd flag this for Arjun to add to `wiki/tech/REMAINING_WORK_PLAN.md` explicitly rather than let it live only in javadoc TODOs.

**Not a blocker for going live with D4**, but flagging as a near-term backlog item, not a someday item.

---

## ITEM 2 — Ledger-only settlement (no RazorpayX disbursement wiring)

### What "ledger-only" means today

`AffiliateSettlementJob.doSettleCreator` (line 256-260) only calls `earning.markSettled(batch.getId())` + `affiliateEarningRepository.save(earning)`. It never calls `PayoutService.queuePayout`, `RazorpayXClient`, or any external gateway. A `SETTLED` status change is purely an internal bookkeeping event.

### Cost/cash-flow impact of leaving this unwired vs. wiring it now

**Leaving it unwired (current state):**
- **Engineering cost to wire later:** low-to-moderate. `PayoutService.queuePayout` already exists and is proven (E2-hardened, idempotent, validate-before-executeOnce). Wiring `AffiliateSettlementJob` to call it after `doSettleCreator` is a bounded, well-scoped follow-up — not a new gateway integration, just a new caller of an existing one. Rough estimate: half a day of Vikram's time + Kavya QA + Kabir load-bearing re-review (money-moving code gate), similar size to this D4 task itself.
- **Cash-flow impact:** none directly — no money moves either way in this slice, so there's no float/liquidity exposure from the code itself.
- **Real risk is NOT financial-loss risk, it's reconciliation/trust risk:**
  - **False expectation of payment:** `SETTLED` is a status name that strongly implies "this has been paid" to anyone reading the ledger (support staff, a creator-facing UI if one gets built on top of this table, a future finance dashboard). If a creator-facing surface ever displays `status: SETTLED` and a creator reasonably reads that as "money is on its way," and it in fact sits in that state indefinitely with no disbursement, that is a support/trust problem, not a bug — but it is 100% predictable given the naming, not a hypothetical.
  - **Reconciliation risk:** if `SETTLED` rows accumulate over multiple monthly batches with no corresponding RazorpayX transfer, `affiliate_settlement_batches.total_amount` becomes a growing "owed but undisbursed" liability that has no accounting home yet — no `WalletTransaction` row, no ledger entry mirroring `PLATFORM_FEE`/`PAYOUT` types. At small volume (current stage, few campaigns) this is a rounding error to track manually. At any real scale it becomes an audit problem: someone eventually has to reconcile "sum of SETTLED affiliate_earnings" against "what we've actually wired via RazorpayX" with no system-of-record link between the two today.
  - **No accounting/tax exposure yet** — because no money has actually moved, there's no misstated revenue/liability on a real balance sheet from this code alone. The risk is entirely operational (support confusion, manual reconciliation burden) until real payouts start flowing through it.

**Wiring it to `PayoutService.queuePayout` now:**
- Converts `SETTLED` into a true "money is moving" state, matching what the name already implies — closes the trust gap.
- Small additional engineering cost now (bounded, known pattern) vs. deferred cost later (same work, plus however many `SETTLED`-but-undisbursed rows have piled up needing a one-time backfill payout run once wiring lands).
- Requires one open product decision Kavya's review already surfaced: same wallet/RazorpayX path as milestone payouts, or a dedicated affiliate payout flow? This is a 30-minute product/Priya/Kabir decision, not a big design exercise — the mechanics are already proven by `PayoutService`.

### My recommendation

**Do not treat "ledger-only" as acceptable to ship live-to-creators as-is.** The architecture (separate ledger settlement from disbursement) is correct and I'm not asking to redesign it. But before any real creator ever sees a `SETTLED` earning:

1. **Minimum bar to go live:** either (a) wire `AffiliateSettlementJob` to call `PayoutService.queuePayout` for the batch total per creator immediately after `doSettleCreator`, so `SETTLED` and "payout queued" happen together, or (b) if disbursement is deliberately deferred to a later wave, there must be NO creator-facing surface that displays `SETTLED` status until disbursement is wired — keep it an internal-only ledger table until the two are joined. Shipping the ledger silently (internal-only, no creator UI reads this table yet) is fine and low-risk; shipping a creator-facing "your earnings are settled" screen backed by this table without real disbursement is the actual risk, and today nothing in the codebase prevents that from happening in a future UI task without someone remembering this gap.
2. Given `PayoutService.queuePayout` already exists and is hardened, I'd lean toward recommending **(a) wire it now** rather than defer — the marginal cost is low, it removes an entire class of future reconciliation debt, and it's a natural extension of a task already touching this exact code path. But this is ultimately Priya's architectural call and Kabir's security-gate call, not purely a cost question — I'm flagging the cost/risk tradeoff, not overriding their authority on the disbursement mechanism.
3. Regardless of (a) or (b), add a **money-owed dashboard line item**: `SUM(affiliate_earnings.commission_amount WHERE status = 'SETTLED' AND not yet disbursed)` should be visible somewhere (even a manual query for now) so this liability doesn't silently grow unmonitored between now and whichever wave wires disbursement.

---

## SUMMARY FOR SWAPNIL / PRODUCT

| Item | Verdict | Blocking? |
|---|---|---|
| Flat 10% commission rate | Ship as documented placeholder; below platform's own 15% precedent, doesn't stack onto the same transaction | Not blocking — schedule per-campaign config as near-term backlog |
| Ledger-only settlement (no RazorpayX wiring) | Architecturally correct but must not reach a creator-facing surface until disbursement is wired or explicitly deferred with a monitored liability line | **Blocking for any creator-facing "your earnings" UI**; not blocking for continuing to accrue/settle internally |

No new tool subscription or budget-limit implications from this task — this is a code/architecture cost review, not an infra spend item. Logged against today's cost report below.
