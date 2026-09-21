# Rohan (CFO) → Tejas: how Influora makes money, from code only

Author: Rohan | Date: 2026-09-18 | No production data used. Every number below is a per-unit code/config/migration/ruling value, never a volume or a total. Repo root `New Influora`, branch `feat/meera-creator-phase-e`.

## 1. What a brand is charged today

| Charge | Value | Label | Source |
|---|---|---|---|
| Pro subscription | ₹4,999.00/mo, GST-inclusive | IN-CODE | `V55__seed_billing_plans.sql:32` (`price_inr=499900`); GST back-out formula same as `InvoiceService.java:252-259` |
| Free subscription | ₹0 | IN-CODE | `V55__seed_billing_plans.sql:22` |
| Campaign fee, Free | 10% (1000 bps), global default | IN-CODE | `V42__platform_fee_config_brand_fee_razorpay.sql:24`; Free plan's own `fee_bps` is NULL (`V57__free_plan_fee_bps_null.sql:14`) so it falls through; resolved in `BrandCampaignFeeService.java:108-121` |
| Campaign fee, Pro | 7% (700 bps) | IN-CODE | `V55__seed_billing_plans.sql:32`; resolved same method, `BrandCampaignFeeService.java:113-119,124-125` |
| GST on the campaign fee | Wallet debit = fee only, no GST leg moved | IN-CODE (debit) / print is wrong | Debit: `BrandCampaignFeeService.java:181-227` (only `fee`/`delta` posted). Invoice PDF prints fee+18% GST as PAID (`CommissionInvoiceService.java:50,117,192`) with nothing collected — flagged open in `wiki/decisions/CFO-BRAND-FEE-GST-TOPUP-0917.md` §B |
| Top-up fee | None | IN-CODE (absence) | `WalletTopUpService.java:47` — "No fee is ever charged here" |
| Brand AI credit top-up packs | Not built | RULED-NOT-BUILT | `CFO-BRAND-FEE-GST-TOPUP-0917.md` §A row 4, §D (catalogue proposed, needs Swapnil) |

A stray third fee number, `PLATFORM_FEE_PERCENT=15.00` (`application.yml:407`, read by `AmountDerivationService.java:68`, quoted by Meera's `request_payment` tool), is dead/wrong and not one of the two real rates — noted as a bug to fix in the same ruling, §E item 2.

## 2. Rs 1,00,000 campaign — arithmetic from code

Fee base = `campaign.getBudgetMax()` (`BrandCampaignFeeService.java:159-161`), bps formula `budget × feeBps / 10000` (`BrandCampaignFeeService.java:224-228`). GST is not added to the debit (§1 above). No TDS/TCS coded on the brand leg.

- **Free:** fee = 100,000 × 1000/10000 = **₹10,000** debited from brand wallet on top of budget (`chargeOnPublish`). Creator side (below) still applies to the ₹1,00,000 pool.
- **Pro:** fee = 100,000 × 700/10000 = **₹7,000**, same mechanics.

Creator-side leg, applied separately at escrow release (`PlatformFeeService.java:40-57`), rate = `PlatformFeeConfig.defaultFeeBps`, seeded **1500 bps = 15%** (`V41__platform_fee_config.sql:21-22`, admin-editable, not re-verified against live DB): fee = 15,000, creator net = **₹85,000**. TCS "1%" is computed and printed on the creator invoice but **never deducted from this net and never remitted** — RULED-NOT-BUILT, report-only (`CampaignServiceInvoiceService.java:207-223`, confirmed in `CFO-TCS-TDS-F0850-0917.md` §1a). TDS is admin-typed metadata only on manual payouts, not automatic, not coded on the standard rail (`AdminFinanceService.java:179-226`, same ruling §1b).

Influora keeps, per ₹1,00,000 campaign: **₹10,000 (Free) or ₹7,000 (Pro)** brand-side + **₹15,000** creator-side = ₹25,000/₹22,000 gross platform take before any GST remittance obligation on the fee itself (unsettled, §1).

## 3. AI cost to Influora — brand and creator

- **Brand credits:** Free 100/mo, Pro 400/mo (+50 loyalty, stacking per Swapnil's ruling) — IN-CODE: `V55__seed_billing_plans.sql:23,33` (`ai_monthly_allotment`), loyalty split `V20260917120000__brand_ai_credit_plan_loyalty_split.sql`. **Cost per brand credit: UNKNOWN in code** — no brand-specific unit-cost constant exists. `CFO-BRAND-FEE-GST-TOPUP-0917.md` §D uses an *assumption* (₹0.648/credit, borrowed from the creator persona cost sheet, same `MeeraSessionService.doSendTurn` code path) — this is a documented estimate, not a cited cost constant. Needed to de-UNKNOWN it: an actual per-model-call token/cost log keyed to brand turns.
- **Creator chat cap:** US$0.75/creator/month, IN-CODE and BLOCKING (`influora-ai/app/config.py:456`, `AI_CREATOR_MONTHLY_CAP_USD`), enforced with HTTP 429 in `influora-ai/app/routes/chat.py`.
- **Creator brief cap:** US$0.25/creator/month — this exists only on the unmerged `feat/meera-creator-phase-b0` branch (`influora-ai/app/config.py:545-547` on that branch), not in this checkout. Label: **RULED-NOT-BUILT** (built on b0, not merged into `feat/meera-creator-phase-e` or main — confirmed via `.proof-os/tasks/T-B0-CREATOR-QA-0917/answers.md` Q15, and `git merge-base --is-ancestor` check recorded there).

Both caps are spend ceilings, not cost figures — they say what Influora will not exceed, not what it actually spends per creator; actual spend per creator is UNKNOWN without production logs.

## 4. Creator-side revenue today and Phase B packs

- **Charged to creators today: nothing.** Chat turns from creators are not credit-charged — the charge path only fires for brand users (`MeeraSessionService.java:374-389`). Pasting a brief is not charged either (`CreatorBriefService.java:199-209`, on b0). No `CreatorCreditService` or `CREATOR_CREDITS_ENABLED` flag exists anywhere in either checkout (confirmed by search).
- **Phase B packs — SPEC-ONLY, nothing built:** Starter 50 credits/₹149, Standard 100/₹249, Power 300/₹649 (`.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/CREDITS-SPEC.md:126-128,134`). Free allotment would be 30 at signup (never expiring) + 40/month (`CREDITS-SPEC.md:52-53,266-267`), switch defaults OFF (`CREATOR_CREDITS_ENABLED=false`, `CREDITS-SPEC.md:44,294`). No route, no order table, no Razorpay wiring exists for any of this.
- Per-unit revenue if built: Starter ₹2.98/credit, Standard ₹2.49/credit, Power ₹2.16/credit — arithmetic only, not a forecast; cost-to-serve per creator credit is UNKNOWN (same gap as brand credits, §3).

## 5. Proven live vs code-only

- **PROVEN live:** a real ₹1,000 wallet credit, brand-side, 2026-09-13 (`project_meera_razorpay_live_0913` memory note; wallet top-up rail only — not a campaign-fee charge or a creator payout).
- **Webhook reconciliation:** no fallback exists if the Razorpay webhook is missed — same project note. This directly threatens the fee amounts above ever landing in the platform wallet in production, independent of whether the bps math is correct.
- **Code-only / never run live:** the entire creator-side fee split (`PlatformFeeService`), the brand campaign fee (`BrandCampaignFeeService`), TCS/TDS printing, GST invoice math, and all Phase B creator-credit numbers — none of these have a cited live-transaction proof in this repo; treat every ₹ figure above as "what the code would do," not "what happened."

## 6. Break-even framing (per-unit only — no volumes invented)

Let `C_AI` = actual monthly AI cost to serve one active creator (UNKNOWN, §3 — cap is $0.75 ≈ ₹62-65 at typical FX, not actual spend).

- **Pro brands needed to cover one creator's AI cap:** `N = C_AI ÷ (₹4,236.44 net-of-GST Pro revenue per brand)`. At the $0.75 cap as a ceiling proxy: N ≈ 65 ÷ 4,236 ≈ **0.015 Pro brands** — i.e. well under one Pro brand's monthly net revenue covers one creator's capped AI spend, IF the cap equals actual spend (it is a ceiling, not measured cost).
- **Campaign fees needed to cover one creator's AI cap**, Free-tier 10% fee: `N = C_AI ÷ (fee on one campaign)`. On a ₹50,000 campaign (fee ₹5,000): N ≈ 65 ÷ 5,000 ≈ **0.013 campaigns**, i.e. one modest campaign's Free-tier fee alone covers dozens of creators' AI caps at the ceiling figure.
- These ratios use the $0.75 **cap**, not a measured cost — treat them as an upper bound on Influora's exposure, not a break-even proof, until an actual per-creator AI spend log exists (§3 UNKNOWN).

Answers file: `C:/Users/Sage world/Downloads/New Influora Ai/New Influora/.proof-os/tasks/T-PHASEB-LIVE-0918/rohan-answers.md`
