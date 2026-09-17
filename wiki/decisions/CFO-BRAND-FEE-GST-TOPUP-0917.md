# CFO — Brand Fee, GST, Voice, and AI Top-Up Packs (Swapnil's 0917 rulings, formalised)

**Author:** Rohan (CFO) · **Date:** 2026-09-17 · **Status:** Rulings 1–3 recorded as final. Ruling 4 catalogue is **proposed — needs Swapnil**. Section B is a flag, not a decision.

**Sources read:** `wiki/processes/subscription-answers-0917-brand.md` (BR-9..BR-16), `wiki/decisions/CTO-BRAND-PRICING-ANSWERS-0917.md` (Q8-Q14), `wiki/decisions/CFO-TCS-TDS-F0850-0917.md` (F-0850 — TCS/TDS only; not duplicated here), `.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/finance/meera-credit-sheet.md`, `credits-gtm-plan.md`.

---

## A. Rulings table

| # | Ruling (Swapnil, verbatim intent) | Code-level value | Arithmetic |
|---|---|---|---|
| 1 | Brand campaign fee: **10% Free, 7% Pro** | `fee_bps` Free = **1000** (global default, `V42__platform_fee_config_brand_fee_razorpay.sql:24`; Free plan row's own `fee_bps` is NULL per `V57__free_plan_fee_bps_null.sql:14`, so it falls through to the 1000-bps global default), Pro `fee_bps` = **700** (`V55__seed_billing_plans.sql:32`) | Both values **already match** in `BrandCampaignFeeService.java:108-121` — no migration change needed. What's wrong is display/quote code, not config: `PLATFORM_FEE_PERCENT` default **15.00** in `application.yml:407` (read by `AmountDerivationService.java:68`, quoted by Meera's `request_payment`) is a third, unrelated number and must go; `BrandPlatformFeeService.java:35-36,53` hardcodes copy "Platform fee (10%)" for every brand including Pro. |
| 2 | Pro is **₹4,999/month, GST-inclusive** | `price_inr = 499900` (paise), `V55__seed_billing_plans.sql:32` — matches | Base = 499900 ÷ 1.18 = 423,644.07 paise → **₹4,236.44**. GST = 499900 − 423644 = 76,256 paise → **₹762.56**. Check: 4236.44 + 762.56 = **₹4,999.00**. This is the same back-out formula already used in `InvoiceService.java:252-259` for the subscription invoice — ruling 2 just confirms that formula is right, no change needed there. |
| 3 | Brand voice (speak/transcribe) **free on every plan**, no credits | Already true: `MeeraController.java:237-242,279-307` calls no `creditService`. **No code change** — this ruling formalises the status quo. Cost exposure below (§C) is new. |
| 4 | Brands get **AI credit top-up packs** | **Not built** (`BR-14`: no route, no catalogue, no order table). Proposed catalogue in §D. |

---

## B. GST on the campaign fee — flag, not a decision. **Needs Swapnil.**

Ruling 2 settled GST on the *subscription*. It did **not** settle GST on the *campaign fee*, and today's code is inconsistent with itself: the commission invoice prints "fee + 18% GST, PAID" (`CommissionInvoiceService.java:50,117,192`; `CommissionInvoicePdfService.java:139-141`) while the wallet debit is **fee only** (`BrandCampaignFeeService.java:181-227`). Every commission invoice currently overstates what was collected — same defect shape as the TCS line in `CFO-TCS-TDS-F0850-0917.md` §1a/1c (compute-and-print, never collect-and-remit). This also answers into F-0850 §2 Q5 / F-0847, which are the same open question from the tax side.

**On a ₹50,000 campaign:**

| | Free (10%, fee=₹5,000) | Pro (7%, fee=₹3,500) |
|---|---|---|
| **Option 1 — fee is GST-inclusive** (invoice backs GST out of the fee, same formula as ruling 2) | base ₹4,237.29 + GST ₹762.71 = **₹5,000.00 total** (unchanged from today's debit) | base ₹2,966.10 + GST ₹533.90 = **₹3,500.00 total** (unchanged) |
| **Option 2 — fee + 18% GST added on top** (matches what the invoice currently *prints*, but actually collected) | fee ₹5,000.00 + GST ₹900.00 = **₹5,900.00 total** (+₹900 vs today) | fee ₹3,500.00 + GST ₹630.00 = **₹4,130.00 total** (+₹630 vs today) |

**Recommendation: Option 1.** It costs the brand nothing extra (the wallet debit already equals the fee, unchanged), it matches the precedent ruling 2 just set for the subscription, and it only requires fixing the invoice's math (same class of fix as F-0850 §4a's "stop printing a false number" — no new charge, no CA ruling needed to *remove* an overstated line). Option 2 raises what brands actually pay mid-flight and needs its own go-to-market and comms decision. **Marked needs Swapnil** because it changes a statutory-looking PDF's total and, unlike the TCS line, this one is Influora's own GST liability (not a third party's), so Swapnil should confirm before engineering touches `CommissionInvoiceService`.

---

## C. Voice free: cost exposure

No brand-specific voice volume exists in the repo. **Assumption, stated:** brand voice usage tracks the creator persona turn counts in `meera-credit-sheet.md` §1 (same Sarvam STT/TTS integration, same `MeeraController` code path, no brand-specific cost model exists to cite instead).

- Unit cost: **₹1.415/voice turn** (Sarvam STT $0.006 flat + TTS ₹30/10k chars at 300 chars, `meera-credit-sheet.md` §1).
- **Typical** brand (10 voice turns/month, creator "Typical" persona): **≈ ₹14.15/brand/month**.
- **Heavy** brand (40 voice turns/month, creator "Heavy" persona): **≈ ₹56.60/brand/month**.

Against Pro's net revenue per brand (₹4,236.44/month, §A ruling 2), even Heavy voice usage is **1.3% of net Pro revenue per brand** — immaterial today. At 100 Pro brands all at Heavy usage, aggregate voice cost ≈ **₹5,660/month**, still under 0.13% of ₹499,900/month gross Pro revenue at that scale.

**Revisit threshold:** this ruling should be revisited if either (a) per-brand voice cost grows past **~₹400–₹420/brand/month** (≈10% of net Pro revenue per brand — roughly 280-300 voice turns/month, 7x today's Heavy assumption), or (b) Sarvam's per-character/per-minute pricing rises materially. Below that, metering voice is not worth the engineering or the "Meera speak is free" positioning it would break.

---

## D. Brand AI top-up packs — proposed catalogue. **Prices proposed — needs Swapnil.**

**Cost per brand credit:** 1 brand credit = 1 Meera chat turn (`TURN_CREDIT_COST = 1`, `AICreditService.java`; charged at `MeeraSessionService.java:84,388`). No brand-specific unit-cost line exists in the repo; **assumption:** brand turns cost the same as the creator turn figure in `meera-credit-sheet.md` §1 (Sonnet 4.5, 90% cacheable/70% cache-hit input, 40% chance of one tool round-trip) because `MeeraSessionService.doSendTurn` is the same code path for both — **₹0.648/credit**.

**Pro's effective rate**, for comparison: ₹4,999 ÷ 400 credits = **₹12.50/credit** (list, GST-incl.); ₹4,236.44 net ÷ 400 = **₹10.59/credit** (net of GST).

| Pack | Credits | Price (GST-incl.) | ₹/credit | Cost to serve | Gross margin |
|---|--:|--:|--:|--:|--:|
| Starter | 50 | ₹999 | ₹19.98 | ₹32.40 | 96.2% |
| Growth | 150 | ₹2,499 | ₹16.66 | ₹97.20 | 95.4% |
| Scale | 400 | ₹5,999 | ₹15.00 | ₹259.20 | 94.9% |

Every pack, even the largest, prices at **1.2×–1.6× Pro's effective ₹12.50/credit list rate** — Pro stays the better deal for a regular (recurring, high-volume) user, and packs serve the occasional or Free-tier top-up case instead of pulling brands off Pro.

- **Expiry: never**, matching the creator-side ruling (`credits-gtm-plan.md` D2 default, "never expire" — Indian buyers are inconsistent spenders; matches the brief's requested R2 precedent).
- **Consumption order:** purchased credits are drawn down **only after** the monthly plan allotment (Free 100, Pro 400) hits zero — mirrors the creator ledger's two-bucket design (`monthly_remaining` then `purchased_balance`, `credits-gtm-plan.md` §8.2). On the brand side this means adding a `purchasedBalance` bucket to `brand_ai_credits` rather than overloading `monthlyAllotment` (the same column BR-15 already flags as broken for the loyalty bonus — don't repeat that mistake).

---

## E. What engineering must change

1. `BrandPlatformFeeService.java:35-36,53` — make the fee copy plan-aware (read the workspace's actual `fee_bps`, stop hardcoding "10%").
2. `application.yml:407` `PLATFORM_FEE_PERCENT` (15.00) and `AmountDerivationService.java:68` — remove or repoint to the plan-aware fee source; audit the one caller, `RequestPaymentExecutor.java:128`.
3. `CommissionInvoiceService.java` / `CommissionInvoicePdfService.java` — once §B is ruled, make the printed GST match what `BrandCampaignFeeService.java:205-215` actually debits (Option 1: back out; Option 2: add a real GST debit leg).
4. New brand top-up build: `purchasedBalance` bucket + consumption-order logic on `brand_ai_credits`, a `brand_credit_orders` table and pack catalogue migration, Razorpay order/webhook branch (reuse the `credits:` receipt-prefix pattern already specced for creators), `GET/POST` pack routes, and a "Buy credits" CTA next to "Fund a campaign" in `CreditPaywall`/`meera-copy.ts`/`MeeraChatPanel.tsx`.
5. `pricing.tsx` — confirm the brand fee number shown matches ruling 1 per plan (today it shows a flat, non-plan-aware rate per BR-6).
6. No change to `MeeraController.java` voice path (ruling 3 keeps it free) — optionally add a non-billing usage counter so §C's exposure estimate can be checked against real volume later.
