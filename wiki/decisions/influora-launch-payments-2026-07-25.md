# CEO Decision — Influora v1 Launch Payments (Manual-Escrow Interim)

- **Decided by:** Swapnil (CEO) — final
- **Date:** 2026-07-25
- **Rev 2 (2026-07-25):** Reversed the "never hold funds / direct-pay" model below after two questions exposed its flaw — influencer deals have a **7-day production window** that requires a hold. Direct-pay only fits instant transactions. See "Production-window correction."
- **Relationship to CFO memo:** aligns with Rohan's guardrails (`budget-proposals/razorpay-escrow-2026-07-25.md`); Route remains the go-live-2 target.
- **Context:** RazorpayX Route (escrow) account pending (~1–3 wks). Full deal workflow through "deliverable approved" is proven in live QA. Only the money leg is open.

## Production-window correction (why direct-pay was rejected)
Payment moves **per deal**, not per campaign (campaign budget = a cap spent deal-by-deal; only the ₹2,500 platform fee is charged at campaign level, at publish). Each deal runs: **day 0 agree → days 0–7 creator produces → day 7 submit/approve → pay**. During those 7 days someone must be protected:
- pay upfront → brand exposed; pay on approval → creator exposed; **hold in the middle → both protected.**
That middle hold *is* escrow — so a "never touch the money / direct payment link" model does **not** work here. Some hold is mandatory.

## Decision (v1)
**Launch v1 on MANUAL ESCROW — the exact hold→approve→release flow already built and QA-verified; the platform (ops) holds funds for the pilot instead of Razorpay Route.**

1. **Hold on accept:** brand pays the deal amount at acceptance; funds held (pilot: our RazorpayX account, ops-controlled).
2. **Release on approval:** on brand approval, ops releases to creator via RazorpayX payout, minus platform fee + **1% TDS (Sec 194-O)**.
3. **Revenue = publish fee (₹2,500, live/tested) + commission**; books stay **fee-only** (brand gross in / creator payout out — not our revenue).
4. **Route swaps in later:** `ESCROW_ENABLED` flag → when Route approved, the automated escrow path (already coded/tested) replaces manual hold. Zero rework.

## Alternative if we refuse to hold funds
**Split payment: 50% upfront + 50% on approval** via two payment links. No holding, no compliance flag — but each side carries half the risk. Acceptable only with vetted brands; inferior protection.

## Why (CEO rationale)
- **Protects the 7-day window** — the actual reason escrow exists; the only honest interim.
- **Uses code we already tested** — hold-on-accept → release-on-approval verified live via DB seed; manual = same path, human-triggered.
- **Revenue live now** — publish fee QA-verified.
- **Accept:** manual hold reopens the "funds in our account" exposure → **must** carry Rohan's guardrails: capped pilot volume, vetted brands, fee-only books, TDS on, **CA/legal sign-off** before real money moves.

## Rejected alternatives
- **Direct-pay, platform never holds (Rev 1 of this doc):** rejected. Fine for instant transactions; fails the 7-day production window — no protection while the creator produces.
- **Brand money → our current account → pay creators:** rejected as the *mechanism*. RBI PA/nodal + GST-as-principal risk. Manual hold for the pilot uses a **ring-fenced ops/RazorpayX account, fee-only books**, not the operating current account, and is capped + CA-gated.

## Execution (routed — CEO does not code)
`FROM Swapnil → Priya/Vikram/Rohan/Kavya | Influora v1 manual-escrow | FILES: wiki/decisions/influora-launch-payments-2026-07-25.md, EscrowService.java, PublicConfigController | STATUS: approved pending pilot-cap + CA line | NEXT:`
1. **Vikram:** real Razorpay TEST keys → live/staging `.env` + PublicConfig keyId (kills placeholder). Free, instant.
2. **Vikram/Priya:** `ESCROW_ENABLED` flag. v1 = manual hold: brand pays on accept → funds in ops RazorpayX → ops "release" triggers RazorpayX payout on approval. Keep publish fee. Leave automated escrow path intact for flag-on.
3. **Rohan:** fee-only revenue booking + TDS 194-O on payouts (applies escrow or not); set pilot volume cap; obtain CA/legal sign-off before real money moves.
4. **Kavya/QA:** re-run the hold→approve→release leg on the manual path (already passed via DB seed; confirm on real RazorpayX test flow).

## Go-live 2 (later, no rework)
When Route/escrow account is approved → flip `ESCROW_ENABLED=true`. Automated escrow (hold on accept, release on approval — QA-verified via DB seed) replaces the manual hold. Marketed as "Escrow-Protected Payments."
