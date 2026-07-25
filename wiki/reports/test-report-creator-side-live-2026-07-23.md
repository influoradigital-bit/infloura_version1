# Creator-Side Live QA Report — Influora

- **Environment:** http://200.141.1.6 (live deploy)
- **Account:** demo.creator@influora.com (Creator)
- **Date:** 2026-07-23
- **Method:** Real-user browser walkthrough (in-app browser), console-error checks per page
- **Tester:** Claude Code (automated live walkthrough) — pending team sign-off (neha, tester -> swapnil, priya)

## Summary

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| 1 | Login / Auth | PASS | Demo creator login works |
| 2 | Deals list | PASS | Filters (All/New/Negotiating/Active/Completed); 1 negotiating deal |
| 3 | Deal Room (bid/accept/sign) | PASS | Pipeline Negotiate->Contract->Escrow->Deliver->Pay; live message box |
| 4 | Wallet | PASS | Balances, 15% fee, Withdraw/Settings, Payouts/History/Invoices/Tax Docs |
| 5 | Co-pilot (AI) | PARTIAL | Gated behind "Connect Instagram"; not testable without IG OAuth |
| 6 | Profile | PASS | Bio, niches, stats (15K/4.5%), 60% completeness, rate card, languages |
| 7 | Public Page (@handle) | **FAIL** | App links to `/@creator` -> "We could not find this creator" |

**Overall: creator core (deals, deal room, wallet, profile) works. 1 hard FAIL (public page), 1 PARTIAL (AI co-pilot gated). 0 console errors.**

## Detail

### 1. Login / Auth — PASS
- `/creator/login` renders; demo creator credentials authenticate -> lands on Deals.

### 2. Deals — PASS
- Counters: All 1, New 0, Negotiating 1, Active 0, Completed 0.
- Deal card: Demo Brand Co / QA E2E — Diwali Skincare Reels / Rs 0 / "Negotiating" / "Brand accepted the proposal" / Open chat.
- Search + status filters present.

### 3. Deal Room (bid / accept / sign) — PASS
- Stepper from creator view: Negotiate -> Contract -> Fund escrow -> Deliver -> Pay. Status "Contracted".
- Tabs: Contract, Deliverables (0/0), Payments. Live "Type a message..." box for negotiation.
- **For Ash (AI review of bid/accept/sign flow):** verify how proposal accept + contract signing is driven, whether any AI assists negotiation, and the escrow-funding handoff.

### 4. Wallet — PASS
- Available Rs 0, In Escrow Rs 0, Pending Payouts Rs 0. Platform fee 15% (released from escrow).
- Withdraw + Settings. Tabs: Payouts / History / Invoices / Tax Docs. No console errors.

### 5. Co-pilot (AI) — PARTIAL
- "Get your first daily idea — Link Instagram to unlock Co-pilot" with a Connect Instagram CTA.
- **Cannot fully test** without completing Instagram OAuth (external auth — not performed in automated test; requires user authorization).
- **Finding CP-1 (for Ash):** Co-pilot is hard-gated on IG connection with no fallback/preview. Review whether a demo/preview idea can render pre-connect so the value is visible.

### 6. Profile — PASS
- Public page banner, bio, niches (lifestyle, fashion), connected accounts (none), stats (15.0K followers, 4.5% engagement), profile completeness 60%, rate card Rs 5,000–25,000, languages en/hi.

### 7. Public Page — FAIL
- **Finding P-1 (route to vikram/ananya):** Profile states "Your public page is live — influora.com/@creator" and links to `/@creator`. Visiting `http://200.141.1.6/@creator` returns **"We could not find this creator. The handle @creator doesn't seem to belong to anyone on Influora."**
- The app's own generated public-page link is broken (wrong/placeholder handle or public lookup fails). A brand clicking through hits a dead page. **Blocking for the "share in your IG bio" value prop.**

## Items routed for fixes
- **P-1** (vikram/ananya): Public creator page `/@creator` returns not-found though profile says it's live. Fix handle resolution / link generation. **[blocking]**
- **CP-1** (Ash): Co-pilot fully gated behind IG connect — add pre-connect preview/fallback. **[AI review]**
- Bid/accept/sign flow: hand to Ash for AI-flow review (real-person negotiation walkthrough).

## Next
- Ash creator AI review (Co-pilot + bid/accept/sign flow, 15 questions).
- neha + tester sign-off -> swapnil + priya final.
- Fix P-1 + M-1 (brand) -> redeploy -> re-verify -> final report.
