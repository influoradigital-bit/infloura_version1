# Brand-Side Live QA Report — Influora

- **Environment:** http://200.141.1.6 (live deploy)
- **Account:** demo.brand@influora.com (Brand)
- **Date:** 2026-07-23
- **Method:** Real-user browser walkthrough (in-app browser), console-error checks per page
- **Tester:** Claude Code (automated live walkthrough) — pending team sign-off (neha, tester → swapnil, priya)

## Summary

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| 1 | Login / Auth | PASS | Demo brand login works; SPA routes render |
| 2 | Home / Dashboard | PASS | Loads; pipeline + wallet widgets render; no console errors |
| 3 | Meera (AI cofounder) | PARTIAL | Live text + voice TTS work; **cannot create campaigns** (write tools gated) |
| 4 | Campaigns list | PASS | Seed campaign renders; counts + budget correct |
| 5 | New Campaign wizard | PASS | 5-step flow renders (Basics->Content->Budget->Requirements->Review) |
| 6 | Creators discovery | PARTIAL | 2 creators render; 1 has 0 followers / empty engagement % (data gap) |
| 7 | Deals / Deal Room | PASS | Full pipeline renders (Proposal->Negotiate->Contract->Escrow->Deliver->Pay) |
| 8 | Wallet | PASS | Balances, escrow, TDS/GST tracking, Add Funds/Export all render |

**Overall: brand surface renders and functions. 0 hard failures, 0 console errors observed. 2 partials + optional items below.**

## Detail

### 1. Login / Auth — PASS
- `/brand/login` renders email + password form (SPA keeps URL at root).
- Demo brand credentials authenticate -> lands on dashboard.

### 2. Home / Dashboard — PASS
- Greeting, "Requires Your Action" (0 pending), Pipeline (Negotiating: 1), Wallet widget.
- Wallet shows Rs 0 / "Critical" / "0d runway" — **expected for fresh demo account (optional: fund to clear)**.
- No console errors.

### 3. Meera (AI cofounder) — PARTIAL
- Live message sent during test -> correct streamed reply ("Your money's held safe until the creator delivers...").
- Header showed "Speaking..." -> voice TTS active. Voice-conversation + voice-replies toggles present.
- **Finding M-1 (for Ash / AI review):** Meera explicitly refuses to create a campaign: *"I can't create the campaign from this session — you'll need to build it directly in the dashboard."* The core "AI runs your campaigns end to end" promise is not wired to a write action. Route to Ash for AI-flow review + vikram to wire the create-campaign tool.
- Business snapshot panel stuck on "Analysing your business... / Unfunded" — verify snapshot pull triggers on link paste.

### 4. Campaigns — PASS
- Totals: 1 campaign, 1 active, 0 drafts, Rs 25K total budget.
- Seed "QA E2E — Diwali Skincare Reels" (Rs 5K–25K, Active, 0/5 creators, deadline Aug 15 2026).
- Filters (All/Active/Drafts/Paused/Completed) + Sort present.

### 5. New Campaign wizard — PASS
- Type picker: Open Campaign, Direct Deal, Hype Campaign (new, "LIVE · 72h").
- Business verification prompt is **explicitly optional** ("won't block your campaign") — good.
- Open Campaign -> 5-step wizard: Basics (title, description, 8 objectives, private toggle) -> Content -> Budget -> Requirements -> Review.
- **Not submitted end-to-end** (would mutate live data; full fund step needs wallet balance — optional/deferred).

### 6. Creators — PARTIAL
- Discover Creators, platform toggle (Instagram/YouTube/LinkedIn), Filters, Sort.
- 2 creators: Demo Creator (15K followers, 4.5% eng, Rs 15K), Tejas Creater (**0 followers, empty engagement %**, Rs 8K).
- **Finding C-1:** Tejas Creater missing followers/engagement data — data-completeness gap. Route to vikram (creator profile aggregation) — non-blocking.

### 7. Deals / Deal Room — PASS
- Active deal with Demo Creator on QA E2E campaign, "Contracted" status.
- Stepper: Send Proposal -> Negotiate -> Contract -> Fund escrow -> Deliver -> Pay.
- Tabs: Contract, Deliverables (0/0), Payments. "Brand accepted the proposal" logged.

### 8. Wallet — PASS
- Available Rs 0, Escrow Rs 0, Pending Rs 0, Runway 0d (burn Rs 1,80,000/mo).
- TDS this FY Rs 1,48,500 (Sec 194-O), GST this FY Rs 2,67,300.
- Tabs: Transactions / Escrow / Payouts. Add Funds + Export present.
- Note: "Last recharge Rs 1,00,000 on 18 Jul" vs Rs 0 available — plausible demo drawdown; verify ledger reconciliation (optional).

## Items routed for fixes
- **M-1** (Ash + vikram): Meera cannot create campaigns — wire AI create-campaign write tool. *[AI review]*
- **C-1** (vikram): Creator profile missing followers/engagement for Tejas Creater. *[non-blocking]*
- Optional/expected: Rs 0 wallet balance (fund demo), business snapshot "Unfunded" state.

## Next
- Ash 15-question AI review (Meera chat + campaign-creation AI + analytics).
- neha + tester sign-off on this report -> swapnil + priya final.
- Then repeat cycle for creator side.
