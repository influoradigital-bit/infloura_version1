# Influora Pricing Proposal — Brand, Creator, Agency

> **Author:** Rohan (CFO)
> **For:** Swapnil (CEO) — approval needed, unblocks Priya/A3 (Billing Engine)
> **Date:** 2026-07-09
> **Revised:** 2026-07-09 — Swapnil ruled out subscriptions at launch ("platform is new, can't start subscription model — can we also take from brand platform fees per campaign"). Section below replaces the original subscription-first recommendation. Original tiers kept further down, marked **DEFERRED — Phase 2**, for reference once volume exists.
> **Context:** `FEATURE_GAP_ANALYSIS.md` A3 flagged — no pricing exists yet, nothing charges the brand today.

---

## Market Benchmark (what competitors charge)

| Platform | Entry Price | Notes |
|---|---|---|
| Modash | $199–499/mo | Self-serve, discovery-only |
| Aspire | ~$2,000/mo | Annual contract required |
| GRIN | ~$2,083/mo ($25k/yr) | Enterprise-only, annual |
| Upfluence | ~$1,276/mo (~$14-16k/yr realistic) | Multi-module |

General market pattern: self-serve SaaS = $200–1,500/mo; pay-per-use = 10–20% marketplace fee or $30–100/collab; managed service = $3,000–15,000+/mo.

**Read for us:** Western tools price for US/EU ad budgets. Our buyer is Indian D2C/export brands — we need to price in ₹, well under Aspire/GRIN, and let our 15% creator-side platform fee (already locked, see `10_CREATOR_PAYMENTS_SPEC.md`) do real revenue work instead of leaning only on subscriptions.

---

## REVISED Recommended Model: Zero subscription at launch — both sides pay a transaction fee

Swapnil's call is the right one for a brand-new marketplace: asking an unproven brand to pay a monthly fee before they've run a single campaign is a conversion killer. Every serious two-sided marketplace at launch (Upwork, Fiverr) makes exactly this trade — **free to join, take a cut only when real money moves.**

**New model — two transaction fees, no subscription:**

1. **Brand Platform Fee** — charged on top of the campaign budget, at the moment the brand funds escrow. Brand explicitly sees "Creator payout: ₹X + Platform fee: ₹Y = Total charged: ₹X+Y" before confirming — fully transparent, no surprise.
2. **Creator Platform Fee** — unchanged, the 15% default already locked in `10_CREATOR_PAYMENTS_SPEC.md` §1A, deducted at escrow release so the creator's wallet always shows net.

Both fees reuse the **exact same architecture** already built for the creator fee (`PlatformFeeConfig`, basis-points storage, admin-only mutation, audit log, frozen-at-transaction-time) — we just add a second `FeeScope.BRAND` alongside the existing `GLOBAL`/`PLAN`/`CREATOR` scopes. This is a small extension, not new engineering.

### Brand Platform Fee — recommended default: 10%, volume-tiered (admin-configurable, same as creator side)

| Trailing 30-day campaign spend (brand) | Brand fee |
|---|---|
| Under ₹1,00,000/mo | **10%** (default) |
| ₹1,00,000 – ₹5,00,000/mo | 8% |
| ₹5,00,000 – ₹20,00,000/mo | 6% |
| Above ₹20,00,000/mo | 5% |

Worked example — a ₹20,000 creator milestone:
- Brand funds escrow: ₹20,000 + 10% (₹2,000) = **₹22,000 charged to brand**
- Creator receives on release: ₹20,000 − 15% (₹3,000) = **₹17,000 net to creator**
- **Influora revenue on this one transaction: ₹5,000 (25% blended take)** — in line with Fiverr's ~25% combined buyer+seller take, and we charge it with zero fixed cost to either side.

This tiering does double duty: it rewards high-spend brands automatically (retention lever) and it's exactly what makes the **Agency tier work below without needing its own subscription** — an agency's spend simply rolls up across all its managed clients.

**Nothing blocks on Priya/A3 for this to ship.** No `Subscription`/`Invoice`/recurring-billing engine needed for launch — just the `BrandPlatformFee` extension to the existing fee service, charged synchronously at escrow-funding time. This is materially faster to build than the original subscription plan.

---

## Brand Tiers — DEFERRED to Phase 2 (revisit once transaction volume + retention data exists)

> Kept for reference. Do not build yet — launch on the pure transaction-fee model above.

| | **Starter** | **Pro** | **Enterprise** |
|---|---|---|---|
| Price | **₹4,999/mo** (~$60) | **₹14,999/mo** (~$180) | **Custom, ₹49,999+/mo** |
| Billing | Monthly or annual (2mo free annual) | Monthly or annual | Annual only |
| Tracked/active creators | Up to 10 | Up to 50 | Unlimited |
| Team seats | 1 | 5 | Unlimited |
| Campaigns/month | 3 | Unlimited | Unlimited |
| UTM/coupon tracking | ✅ | ✅ | ✅ |
| Shopify/WooCommerce integration | ✅ | ✅ | ✅ + custom webhooks |
| Analytics dashboard | Basic | Full (Meta metrics, scoring) | Full + custom reports |
| PDF/CSV export (A4 gap) | ❌ | ✅ | ✅ white-labeled |
| Affiliate/revenue-share campaigns | ❌ | ✅ | ✅ |
| Dedicated support | Email | Priority email | Slack/WhatsApp + CSM |
| API access | ❌ | Limited | Full |

**When to revisit:** once brands are consistently running ₹5L+/mo through the platform, a Pro/Enterprise subscription can bundle in export, API, and dedicated support as an *upsell on top of* the transaction fee — not a gate to participate. That's the natural Phase 2 move, not a launch requirement.

---

## Creator Side — Free to join, fee on earnings only

- **No subscription fee for creators.** Signup, profile, discovery, bidding — all free.
- Revenue comes from the **15% platform fee** already specced (admin-configurable 0–30%, per-creator/plan overrides, audit-logged — see `10_CREATOR_PAYMENTS_SPEC.md` §1A).
- Optional **Creator Pro add-on — ₹299/mo**: priority placement in brand discovery, advanced growth-AI coaching (spec 11), lower effective fee (e.g. 12% instead of 15% — uses the existing plan-level override, no new engineering).

This keeps creator-side acquisition frictionless (free-to-join is a growth lever, per Tejas's B1/B2 gaps) while monetizing through volume.

---

## Agency Tier — REVISED: no monthly base, volume roll-up instead

No subscription here either, for the same reason. Agencies get their advantage automatically: **an agency's aggregate spend across ALL managed client brands rolls up into one volume tier**, so they land in the 6% or 5% brand-fee bracket far sooner than any single brand would alone — that's the entire agency incentive, no separate pricing needed.

| | **Agency** |
|---|---|
| Price | **₹0 base** — same transaction-fee model as brands, applied to combined spend |
| Volume roll-up | All client workspaces' campaign spend combined for tier calculation |
| Team seats | Unlimited (agency staff) |
| White-label reports | ✅ (client-facing PDF exports carry agency branding — flagged as a build item, not blocking launch) |
| Client billing pass-through | Agency can mark up and re-bill clients directly (Phase 2 — needs the deferred `Invoice` entity) |
| Cross-client analytics rollup | ✅ — aggregate ROI across all managed brands |
| Creator platform fee | Same 15% default; agency-negotiated reduced rate available via the existing per-plan override, no new engineering |

**Why this works:** an agency running ₹25L/mo across 10 client brands automatically qualifies for the 5% brand-fee tier — better economics than any individual client could get alone, which is the actual reason an agency would choose to run all its clients through Influora instead of managing them separately. Zero fixed cost, pure incentive alignment.

---

## What This Requires From Engineering (launch-blocking — much smaller than original A3 scope)

**For launch (small, unblocks fast):**
- Extend `PlatformFeeConfig`/`PlatformFeeService` with a new `FeeScope.BRAND` (reuses 100% of the creator-fee architecture — basis points, admin-only mutation, audit log, frozen-at-transaction)
- Charge the brand fee synchronously when escrow is funded (`EscrowFundingService` — add fee calc + combined charge, single Razorpay order for budget+fee, no recurring billing needed)
- Trailing-30-day spend rollup query (per brand, and per agency across its client workspaces) to resolve the correct volume tier
- Brand-facing fee transparency UI (Ananya): show the ₹budget + ₹fee = ₹total breakdown before the brand confirms funding

**Deferred to Phase 2 (do NOT build for launch):**
- `Subscription` + `Plan` + `Invoice` entities, Razorpay Subscriptions API, dunning logic — all off the table now that launch doesn't need recurring billing
- Agency client-billing pass-through (needs the deferred `Invoice` entity)
- `AgencyWorkspace` → `BrandWorkspace` RBAC model — still needed for multi-client login/isolation regardless of pricing, so this stays real engineering work; Kabir must review before agencies get access to multiple clients' data under one login

---

## Ask of Swapnil

1. **Approve brand fee tiers** (10% under ₹1L/mo → 5% above ₹20L/mo) or adjust the bands/percentages.
2. **Confirm brand fee is charged on top of budget** (brand sees budget + fee, creator's rate untouched) rather than split out of the creator's rate.
3. **Confirm agency gets no separate pricing** — just volume roll-up across clients — or if you want a distinct agency signup/verification step regardless.
4. Once approved, this is a **much smaller and faster build** than the original subscription plan — Priya/Vikram can scope it as an extension of the already-locked creator fee work, not a new billing engine.

I'll track actual take-rate revenue and brand drop-off-at-funding once live, and revisit these percentages in 60 days — first pricing pass should not be treated as permanent. The deferred subscription tiers stay on the shelf as a Phase 2 upsell once we have retention data to justify asking brands to pay before they've seen ROI.

Sources: [Modash 2026 platform pricing](https://www.modash.io/blog/influencer-marketing-platforms), [Aspire pricing breakdown](https://www.ugcroster.com/blog/brands/aspire-influencer-platform-pricing-roi-breakdown), [Upfluence pricing review](https://www.creator-hero.com/blog/upfluence-pricing-and-review), [Influencer platform pricing 2026 overview](https://stackinfluence.com/blog/influencer-marketing-platform-pricing)
