# Performance Collabs (Barter + Affiliate) — GTM & Positioning

**Owner:** Tejas (CMO) · **Date:** 2026-09-17 · **Status:** Rules + pricing RULED by Swapnil 2026-09-17 (this thread); copy gated on build proofs below
**Inputs:** Creator Barter Platform Analysis (Swapnil's doc), CEO store audit `.proof-os/tasks/T-CEO-STOREAUDIT-0908/`, code audit + 380-test verification 2026-09-17, mockup artifact "Barter Campaign Screens"

---

## 1. Naming (binding on all copy)

| Audience | Name | Never |
|---|---|---|
| Creator-facing | **Performance Collabs** | "barter" in headers, "free products for posts", "gifting program" |
| Brand-facing | **Product Seeding + Affiliate** | "escrow" (standing ban), any custody/licence claim (F-0851) |
| Internal/eng | barter+affiliate / T-BARTER | — |

## 2. Positioning statements

**Brand:** "Ship product instead of budget. Pay only when creators sell. Keep the content either way."
Sub: a ₹1,500 kit + commission replaces a ₹3,000 flat fee; downside capped at product cost, upside tracked to the rupee on your own store.

**Creator:** "Keep the product. Earn on every sale. **Applying is free — always.**"
Sub: your unique code and link are issued the moment you're accepted; you keep **100% of your commission**.

The two load-bearing phrases — "free to apply, always" and "creators keep 100% of commission" — are strategy, not copy garnish. They are what separates us from the pay-to-collab scam pattern and from commission-skimming platforms. No A/B test removes them.

## 3. Pricing (ruled 2026-09-17)

### Brands (module on the existing workspace; stacks with the campaign publish fee, separate line)
| | Seed | Grow | Scale |
|---|---|---|---|
| Price | ₹0 | ₹2,499/mo | ₹9,999/mo |
| Active collabs | 5 | 15 | Unlimited |
| Fee on tracked sales | 7% | 4% | 3% |
| Seeding automation, dynamic coupons, courier tracking, Integrity Index | manual/static | full | full + priority |

Upgrade math for sales copy: Grow beats Seed above ~₹83,000/month tracked sales; Scale beats Grow above ~₹7.5L.

### Creators
| | Free (forever) | Creator Plus ₹499/mo |
|---|---|---|
| Apply to collabs | **Unlimited, free** | Unlimited, free + priority in brand queues |
| Concurrent active collabs | 3 | 5 |
| Meera credits/mo | 40 | ~200 |
| Commission kept | 100% | 100% |

**Hard rule:** nothing anywhere reads as pay-to-apply. Plus sells throughput to power creators, never access. One-time packs (₹149/249/649) unchanged.

## 4. Program rules the copy leans on (ruled; see thread for full B1–B10 / C1–C8)

- Min bundle value **₹1,500 by live selling price, not MRP** (validated against the connected store).
- Commission 10–25%, set at create; brand funds commissions from its own wallet float (min ₹5,000) — settlement debits that brand, never a pool.
- Ship in 7 days; content window default 10 days; ASCI gifted+commission disclosure mandatory; posts live ≥30 days.
- Creators: apply-based, 1K+ followers, connected Meta; Integrity Score gates bundle value (start ≤₹3,000); strikes for ghosting; no self-redemptions.
- UGC rights: organic repost default; paid-ads reuse only if declared at create (shown to creator pre-apply).

## 5. Claims gated on build proofs (DO NOT PRINT EARLY)

| Claim | May print only after |
|---|---|
| "paid to your Influora wallet on the 1st" | settlement ENUM migration deployed to live + one real creator payout proven |
| "set your own commission rate" | `commission_rate` writer shipped (field on campaign create) |
| "your code works instantly" | coupon→store auto-sync shipped |
| anything comment-to-DM | Meta grants the permission (currently rejected) — no "coming soon" either, per Swapnil |
| "Creators keep 100% of every commission" (creator landing hero) | same gate as wallet claim — payout must be real first |

## 6. Launch sequence

1. **Woo-first private beta** (store connect is live): 3 brands, 15 creators, film the end-to-end — seeded kit → Reel → code redemption → dashboard GMV. This is the demo asset.
2. Settlement fix deploys → first real commission payout → screenshot/testimonial → creator landing goes live with §2 copy.
3. Pricing page adds the Grow/Scale tiers + Creator Plus (needs billing nav fix shipped — the page must be reachable).
4. Shopify wave once keys + live smoke test pass.
5. Momentum score ("N creators earned on this offer this week") turns on when ≥25 offers have real earning rows — never fake it, it's computed from `AffiliateEarning` only.

## 7. Copy bank (Nisha starts from these; CMO approval per piece)

- Brand H1: "Your product is your ad budget."
- Brand proof line: "₹1,500 of product. 50 creators. Every sale tracked on your own store."
- Creator H1: "Keep the kit. Earn on every sale."
- Creator trust line: "Free to apply. No fees, ever. You keep 100% of your commission."
- Offer card momentum: "{n} creators earned commission on this offer this week."
- Exhausted-window nudge (WhatsApp/in-app): "Your Glow Ritual content is due in {d} days — post and your code starts earning."

## 8. Out of scope for launch copy

Comment-to-DM, Shopify (until smoke test), manager seats, any regional-language pages (phase 2 — vernacular creators are the market, but launch English + Hinglish social assets first), paid acquisition (organic + existing brand base first).
