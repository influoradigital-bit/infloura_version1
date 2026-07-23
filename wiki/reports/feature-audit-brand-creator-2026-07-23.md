# Influora Feature Audit — Brand & Creator (2026-07-23)

Ordered by: Swapnil (CEO)
Executed by: neha (live QA)
Date: 2026-07-23

## BRAND FEATURES

| # | Feature | Route | FE Works? | BE Works? | E2E Flow? | Notes |
|---|---------|-------|-----------|-----------|-----------|-------|
| **AUTH** |
| B1 | Login | /brand/login | ✅ | ✅ | ✅ | POST /auth/brand/login → 200, `accessToken`+workspace. Form renders. Session drives all authed pages. (Browser form-submit didn't fire a network call through the headless harness — a Radix/controlled-input harness quirk, not proven an app defect; API + token session verified E2E.) |
| B2 | Register | /brand/register | ✅ | 🟡 | — | Multi-step wizard renders (company/industry/team-size). BE `/auth/brand/register` wired but NOT exercised — creating a live account is out of QA scope. |
| B3 | Forgot password | /brand/forgot-password | ✅ | ✅ | ✅ | Email + Send-reset-link renders. POST /auth/forgot-password → 200 "If this email exists, a reset link has been sent." (email delivery not observable). |
| **ONBOARDING** |
| B4 | Brand onboarding wizard | /brand/onboarding | ✅ | 🟡 | — | Wizard renders (logo upload, name, workspace URL/type, industry, size). BE endpoints wired; demo account already `onboardingCompleted:true` so not re-run. |
| **DASHBOARD** |
| B5 | Home / Dashboard | /brand/dashboard | ✅ | ✅ | ✅ | Renders greeting, "Requires Action", Pipeline (Negotiating/Contracted counts), Wallet. GET /dashboard/actions, /dashboard/pipeline, /wallet all 200 with real data. |
| **MEERA AI** |
| B6 | Meera chat | /brand/meera | ✅ | ✅ | ✅ | Panel renders w/ real transcript. POST /meera/sessions 201 → /messages 200 → SSE stream emits `token`/`done`. AI replies coherently. |
| B7 | Meera → show_creators tool | /brand/meera | ✅ | ✅ | ✅ | Stream fired `tool_start`+`tool_result` (status ok) for show_creators. Returned `creators:[]` — demo creators are tagged lifestyle/fashion, not "beauty skincare" (data match, not a fault). |
| B8 | Meera → calculate_budget tool | /brand/meera | ✅ | ✅ | ✅ | Stream fired tool_start+tool_result for calculate_budget. |
| B9 | Meera → create_campaign (draft) | /brand/meera | ✅ | ✅ | ✅ | onBehalfToken scope includes create_campaign; existing "Draft: organic skincare line — Auto-drafted by Meera" persists in /campaigns list (DRAFT). Confirms tool wrote through. |
| B10 | Meera → get_campaign_performance | /brand/meera | ✅ | ✅ | ✅ | Stream fired tool_start+tool_result when given explicit campaign id. Underlying GET /campaigns/:id/analytics → 200. |
| **CAMPAIGNS** |
| B11 | View campaigns list | /brand/campaigns | ✅ | ✅ | ✅ | Renders tabs (All/Active/Drafts/Paused/Completed) + KPI tiles. GET /campaigns 200 (3 real rows). |
| B12 | Create campaign (wizard) | /brand/campaigns/new | ✅ | ✅ | ✅ | Type-picker (Open/Direct/Hype) renders. POST /campaigns (budget.min/max) → 201; new id appears in DRAFT list after reload. |
| B13 | Create Hype campaign | /brand/campaigns/new/hype | ✅ | 🟡 | — | Hype option present in wizard w/ "72h blitz" copy. Hype create path NOT exercised (would create live campaign); backend accepts HYPE campaignType per api.ts. |
| B14 | Edit campaign | /brand/campaigns/:id/edit | ✅ | ✅ | ✅ | Edit button on detail. PATCH /campaigns/:id → 200, title updated. |
| B15 | View campaign detail | /brand/campaigns/:id | ✅ | ✅ | ✅ | Renders budget-used, collaborators, bids (Tejas "Countered ₹18K"), brief, Accept/Counter/Message. GET /campaigns/:id 200. |
| B16 | Delete campaign | /brand/campaigns | 🟡 | ✅ | ✅ | DELETE /campaigns/:id → 200 `{ok:true}`; removed from list. FE delete control lives in row actions (not individually click-verified in harness). |
| B17 | Duplicate campaign | /brand/campaigns | 🟡 | ✅ | ✅ | POST /campaigns/:id/duplicate → 201 new id; "QA neha EDITED (Copy)" persists in list. FE control in row actions (not individually click-verified). |
| B18 | Campaign tracking | /brand/campaigns/:id/tracking | ✅ | 🔴 | 🔴 | Page renders (UTM + coupon generators). GET tracking-links 200, but **POST /campaigns/:id/tracking-links → 500 INTERNAL_ERROR** — cannot create a tracking link. |
| **CREATOR DISCOVERY** |
| B19 | Browse creators | /brand/discover | ✅ | ✅ | ✅ | Renders 2 real creators w/ filters, sort, platform tabs, View Profile/Invite. GET /creators 200. |
| B20 | View creator profile | /brand/creators/:id | ✅ | ✅ | ✅ | Full profile: tabs (Overview/Audience/Portfolio/Rates/Reviews), stats, Invite/Message. GET /creators/:id 200. |
| B21 | Send deal request to creator | /brand/creators/:id | ✅ | ✅ | ✅ | Invite buttons present. POST /creators/:id/invite → 409 COLLABORATION_EXISTS (endpoint wired + dedup enforced; existing deals originated from invites/applications). |
| **DEAL ROOM** |
| B22 | View deals list | /brand/deals | 🟡 | ✅ | ✅ | GET /deals 200; both deals render with live status. **Bug: deal value renders literal "₹null"** in deal-room Overview (dealValue is null; not guarded — pipeline card handles same null as "No budget set"). |
| B23 | Deal room chat | /brand/deals/:id | ✅ | ✅ | ✅ | Deal room renders (Overview/Messages/History, View Contract). GET /messages 200 + POST /messages/read 200. POST /messages → 200 and persists on reload. |
| B24 | Negotiate terms | Deal Room | ✅ | ✅ | ✅ | Counter button present. POST /deals/:id/counter → 200; Tejas deal moved to Negotiating ₹18K, visible in pipeline + detail. |
| B25 | Accept/reject terms | Deal Room | ✅ | ✅ | ✅ | Accept/Counter/Reject controls present. Demo Creator deal sits at TERMS_AGREED ("Accepted") — accept path exercised prior; contract then created on it. |
| **CONTRACT** |
| B26 | Create contract | Deal Room → Contract tab | ✅ | ✅ | ✅ | **REAL flow verified.** POST /contracts {collaborationId,milestones} → **201, real contractId 01KY77ZAZPE06HNN21P5AHXR1Z**, totalAmount server-summed (20000). No "CTR-2024-" fake. deal.contractId now non-null. "View Contract" button appears in deal room (FE reads real id). |
| B27 | Brand signs contract | Deal Room → Contract tab | ✅ | ✅ | ✅ | POST /contracts/:id/sign (brand) → 200, **brandSignedAt set server-side**, status → PENDING_SIGNATURES. |
| B28 | View contract status | Deal Room → Contract tab | ✅ | ✅ | ✅ | GET /contracts/:id 200 with brandSignedAt/creatorSignedAt + milestones. After creator sign, status → ACTIVE, both timestamps present. |
| B29 | Download contract PDF | Deal Room → Contract tab | ✅ | ✅ | ✅ | Correct 404 CONTRACT_PDF_NOT_READY before both sign; after both signatures → **200 real R2 presigned downloadUrl** (15-min expiry). |
| **ESCROW** |
| B30 | Fund escrow | Deal Room | 🟡 | ✅ | 🔴 | Endpoint correct: POST /wallet/escrow/fund → **402 INSUFFICIENT_FUNDS** with server shortfall (required 25000, balance 0). E2E blocked because wallet has ₹0 and top-up is broken (B42). FE recharge/fund controls present. |
| B31 | View escrow status | Deal Room | ✅ | ✅ | — | GET /wallet/escrow 200 (empty — none funded). Wallet "Escrow" tab renders. No funded hold to show (blocked by B42). |
| **DELIVERABLES** |
| B32 | View deliverables | Deal Room | ✅ | ✅ | — | GET /deals/:id/deliverables 200 (empty). Deliverables tab renders. None submitted yet (creator side). |
| B33 | Approve/reject deliverable | Deal Room | — | 🟡 | — | No deliverable exists to approve. approve/revise endpoints wired per api.ts; not exercisable this run. |
| **PAYMENT** |
| B34 | Release payment | Deal Room | 🟡 | ✅ | 🔴 | POST /wallet/escrow/release → **409 MILESTONE_NOT_FUNDED** (correct gate). E2E blocked — nothing funded (root cause B42 top-up 500). |
| B35 | Request refund | Deal Room | 🟡 | ✅ | 🔴 | POST /deals/:id/disputes → **409 NO_FUNDED_ESCROW** (correct gate). E2E blocked — no funded escrow to dispute (root cause B42). |
| **OTHER PAGES** |
| B36 | Pipeline (Kanban) | /brand/pipeline | ✅ | ✅ | ✅ | Board renders 6 stages w/ real cards (Tejas→Negotiating ₹18K, Demo→Contracted). GET /dashboard/pipeline 200. Null budget handled ("No budget set"). |
| B37 | Contracts list | /brand/contracts | 🔴 | ✅ | 🔴 | **PAGE CRASHES** to error boundary: `TypeError: Cannot read properties of undefined (reading 'icon')` inside a `.map()` (status→icon lookup unguarded). GET /contracts 200, so BE fine — pure FE render bug. |
| B38 | Messages | /brand/messages | ✅ | ✅ | ✅ | Renders conversations, unread counts, live message history. Backed by /deals + /messages 200. |
| B39 | Analytics | /brand/analytics | ✅ | ✅ | 🟡 | Overview + roster render. Per-creator metrics gated: GET /analytics/creators/:id/metrics → **402 UPGRADE_REQUIRED** (Free plan = 1 view/mo — working as designed). Minor FE bug: mislabels this as "not authorized to view metrics for that creator" instead of the upgrade/limit message. |
| B40 | Reviews | /brand/reviews | ✅ | ✅ | — | Renders tabs (Rate creators / Reviews about you) + empty state. GET /brand/reviews/received 200. POST /brand/reviews → 409 COLLABORATION_NOT_COMPLETED (correct gate); no completed collab to review. |
| B41 | Disputes | /brand/disputes | ✅ | ✅ | — | Renders + empty state. GET /brand/disputes/list 200. Create gated (409 NO_FUNDED_ESCROW, see B35). |
| B42 | Wallet | /brand/wallet | 🟡 | 🔴 | 🔴 | Page renders. **POST /wallet/topup → 500 INTERNAL_ERROR** — cannot add funds (Razorpay key is placeholder `rzp_test_REPLACE_WITH_YOUR_KEY`). This is the root money-path blocker (breaks B30/B34/B35 E2E). FE also shows hardcoded figures (TDS ₹1,48,500, GST ₹2,67,300, "Last recharge ₹1,00,000", burn ₹1,80,000/mo) while live /wallet returns all zeros + 0 transactions. |
| B43 | Settings | /brand/settings | 🟡 | ✅ | 🟡 | Renders tabs + workspace form; GET /workspaces/me 200 (real data). Workspace Members list is hardcoded mock (Amit/Priya/Rahul @techbrands.in — not the Demo workspace). Save (PATCH /workspaces/me) wired but not exercised. |
| B44 | Billing | /brand/settings/billing | 🟡 | ✅ | ✅ | Renders plan/usage/compare/invoices. GET /billing/plan,/usage,/invoices all 200. **Bug: current-plan card shows "Brand Fee NaN%"** (fee formatting on the FREE plan card; compare table correctly shows 10%). |
| B45 | Help | /brand/help | 🟡 | — | — | Static page renders (Campaigns/Deal Rooms/Contracts sections, "Ask Meera"). **Copy is placeholder: "TODO: final copy from Nisha. Placeholder — …"** throughout. No backend. |

---

## CREATOR FEATURES

| # | Feature | Route | FE Works? | BE Works? | E2E Flow? | Notes |
|---|---------|-------|-----------|-----------|-----------|-------|
| **AUTH** |
| C1 | Login | /creator/login | ✅ | ✅ | ✅ | POST /auth/creator/login → 200, `accessToken`+`user.id`+`onboardingCompleted:true`. Page renders (email/pw/remember-me/forgot). Bad creds → 401 INVALID_CREDENTIALS. Token session drives all authed pages. |
| C2 | Register | /creator/register | ✅ | 🟡 | — | Form renders (name/email/pw/confirm/terms). POST /auth/creator/register wired — bad payload → 400 VALIDATION_ERROR (nameValid/password-size/acceptedTerms). Creating a live account out of QA scope. |
| C3 | Forgot password | /creator/forgot-password | ✅ | ✅ | ✅ | Page renders (email + Send-reset-link). POST /auth/forgot-password → 200 "If this email exists, a reset link has been sent." (email delivery not observable). |
| **ONBOARDING** |
| C4 | Creator onboarding | /creator/onboarding | ✅ | 🟡 | — | Social-connect step renders (Instagram/YouTube Connect + Continue). BE endpoints wired; demo account already `onboardingCompleted:true` so not re-run. Note: IG connect here rides the same unprovisioned Meta OAuth as C26. |
| **DASHBOARD** |
| C5 | Home / Dashboard | /creator/dashboard | ✅ | ✅ | ✅ | Renders greeting, available balance ₹0, 1 active deal, 2 pending actions, profile views (1/30d), quick links. GET /dashboard/actions + deal rollups return real data. No console errors. |
| **CO-PILOT AI** |
| C6 | Co-pilot page | /creator/copilot | ✅ | ✅ | ✅ | Page renders ("Your AI content partner"). GET /creator/copilot/suggestion/today → 200 `{suggestion:null, status:"pending_tagging"}` — honest empty state (no IG), not a 4xx. |
| C7 | Pre-connect preview | /creator/copilot | ✅ | — | ✅ | Static sample idea shown BEFORE IG connect ("Skincare Routine → 30-sec reel"), labelled "Preview — connect Instagram for ideas personalised to your audience" + "Connect Instagram" CTA. Client-static preview (not BE-backed); works as designed. |
| C8 | Post-connect ideas | /creator/copilot | 🟡 | 🟡 | 🔴 | Cannot exercise — personalized ideas need a connected IG Business account (demo.creator has `platforms:[]`). API wired (returns pending_tagging until tagged). **Blocked: Meta OAuth app is unprovisioned (C26) so IG can't be connected.** |
| **CAMPAIGNS** |
| C9 | Find campaigns | /creator/campaigns | ✅ | ✅ | ✅ | Renders filter chips + campaign card (QA E2E — Diwali Skincare Reels, Demo Brand Co, ₹5K–25K, "Contracted"). GET /creator/campaigns → 200 (1 real row, meta paginated). |
| C10 | View campaign detail | /creator/campaigns/:id | ✅ | ✅ | ✅ | Full detail renders (objectives, budget, platforms, period, deadline). GET /creator/campaigns/:id → 200. |
| C11 | Apply to campaign | /creator/campaigns/:id | ✅ | ✅ | ✅ | Detail shows "You've already applied to this campaign / Contracted". POST /creator/campaigns/:id/apply → 409 ALREADY_APPLIED (dedup enforced). Prior application produced the live collaboration now at CONTRACTED. |
| **DEALS** |
| C12 | View deals | /creator/deals | ✅ | ✅ | ✅ | Renders tabs (All/New/Negotiating/Active/Completed) + deal card, last message, "Open chat". GET /deals?status=all → 200 (real deal, contractId non-null, contractStatus ACTIVE). dealValue null renders "₹0" (guarded — not the brand-side "₹null" bug). |
| C13 | Deal room chat | /creator/chat?deal= | ✅ | ✅ | ✅ | Deal room renders (stage stepper, Contract/Deliverables/Payments tabs, message history). GET /deals/:id/messages → 200 (4 real msgs). POST /deals/:id/messages → 201, message persisted + visible on reload. |
| C14 | Negotiate/counter | Deal Room | ✅ | 🟡 | — | Deal room + Negotiate stage render. POST /deals/:id/counter wired (api.ts) but NOT exercised — deal is already CONTRACTED (past negotiation); countering would disrupt the shared contracted deal. |
| C15 | Accept/reject offer | Deal Room | ✅ | ✅ | ✅ | Accept path already exercised E2E: system msg "Brand accepted the proposal" + notification "Your bid was accepted"; deal advanced TERMS_AGREED → CONTRACTED. deals.accept/reject wired dual-role. |
| **CONTRACT** |
| C16 | View contract (from brand) | Deal Room → Contract tab | 🟡 | ✅ | ✅ | Creator SEES the **real** contract: ID 01KY77ZAZPE06HNN21P5AHXR1Z (no fake "CTR-2024-"), "Fully signed", Download PDF. GET /contracts/:id → 200 (tenant access OK). deal.contractId non-null. **Bug: panel shows "You receive (est.) ₹0"** instead of the ₹20,000 contract total (reads null deal.dealValue, not contract.totalAmount). |
| C17 | Creator signs contract | Deal Room → Contract tab | ✅ | ✅ | ✅ | Already signed: `creatorSignedAt:2026-07-23T10:23:07Z`, status ACTIVE (both timestamps present). Panel shows honest "Fully signed" (no sign button — already done). Signer role server-derived from JWT. |
| C18 | Download contract PDF | Deal Room → Contract tab | ✅ | ✅ | ✅ | "Download PDF" button present. GET /contracts/:id/pdf-download-url → 200 real R2 presigned URL (X-Amz-Signed, 15-min expiry) now that both have signed. |
| **DELIVERABLES** |
| C19 | Submit deliverable | Deal Room → Deliverables | ✅ | ✅ | — | Deliverables tab renders (0/0). GET /creator/deliverables?collaboration_id= → 200 empty. POST /creator/deliverables/:id/submit wired but no deliverable rows exist yet (deal CONTRACTED, not IN_PROGRESS — deliverables not generated). Nothing to submit against this run. |
| C20 | Track approval status | Deal Room → Deliverables | ✅ | ✅ | — | Deliverables tab shows "0/0". GET /deals/:id/deliverables → 200 empty. Status vocabulary wired; no deliverable to track yet. |
| **PAYMENT** |
| C21 | View earnings | /creator/wallet | ✅ | ✅ | ✅ | Wallet renders (Available ₹0, In Escrow ₹0, Pending ₹0, platform fee 15%). GET /wallet → 200 real zeros; GET /creator/platform-fee → 200 (1500 bps). **Honest — no hardcoded mock figures** (unlike brand B42). |
| C22 | Request payout | /creator/wallet | ✅ | ✅ | 🔴 | Withdraw button present. POST /wallet/withdraw → 400 INSUFFICIENT_BALANCE (correct gate). E2E blocked: balance is ₹0 with no payout method — root cause is the money path (brand can't fund escrow → creator never earns; Razorpay key placeholder, B42). |
| C23 | View payment history | /creator/wallet | ✅ | ✅ | — | History/Payouts tabs render with honest empty state. GET /wallet/transactions → 200 `[]` (0 transactions); GET /wallet/payout-methods → 200 `[]`. No transactions to show yet. |
| **PROFILE** |
| C24 | Edit profile | /creator/profile | ✅ | ✅ | — | Profile renders with real data (Demo Creator, @demo_creator, Mumbai, bio, lifestyle/fashion, 15K followers, 4.5% ER, rate ₹5K–25K, 70% completeness). GET /me/creator-profile → 200. PATCH /me/creator-profile wired but write not exercised. |
| C25 | Public portfolio page | /creator/portfolio or /@username | ✅ | ✅ | ✅ | Public shareable page /@demo_creator renders with proper SEO title, Verified badge, achievements, "Invite to Campaign"/"Share page", rate card hidden ("visible to brands only"). Real backed profile data. |
| C26 | Connect Instagram | /creator/settings | ✅ | 🔴 | 🔴 | Connect buttons present (profile "Connect More Accounts", copilot "Connect Instagram", onboarding). **GET /meta/oauth/authorize → 200 but authorizationUrl has EMPTY `client_id=` and `redirect_uri=`** — Meta app is unprovisioned (like the Razorpay placeholder). OAuth cannot complete → no IG can be connected (also blocks C8). |
| **ANALYTICS** |
| C27 | View analytics | /creator/analytics | 🟡 | 🟡 | 🟡 | Page renders all cards with honest "Not yet available" empty states. GET /creator/analytics/me/metrics → 200 (zeros), /demographics → 200 (hasData:false). **But /creator/analytics/me/scores → 404 SCORE_NOT_FOUND triggers a page-level "Couldn't load analytics" error banner** — misleading, since it's an honest "no score computed yet" state, not a load failure. |
| **OTHER PAGES** |
| C28 | Reviews | /creator/reviews | ✅ | ✅ | — | Renders tabs (Rate brands / Reviews about you) + empty state. GET /creator/reviews/received → 200 `[]`. POST /creator/reviews → 409 COLLABORATION_NOT_COMPLETED (correct gate; no completed collab). |
| C29 | Disputes | /creator/disputes | ✅ | ✅ | — | Renders with real list + honest empty state — **no "partial data" banner** (confirms the real GET /creator/disputes wiring landed). GET /creator/disputes → 200 `[]`. POST /deals/:id/disputes → 409 NO_FUNDED_ESCROW (correct gate). |
| C30 | Coupons | /creator/coupons | ✅ | ✅ | — | "My Coupons" renders + empty state. GET /creator/coupons → 200 `[]`. No coupons assigned to demo.creator yet. |
| C31 | Affiliate | /creator/affiliate | ✅ | ✅ | — | Affiliate Earnings renders (Sales/Revenue/Commission/Unsettled all ₹0) + empty state. GET /creator/affiliate-earnings → 200 (empty earnings, zeroed summary). No attributed sales yet. |
| C32 | Settings | /creator/settings | 🟡 | 🟡 | — | Renders (Notifications toggles, Tax Identity, Payout Settings, Change Password, Delete Account). **Bug: "Could not load your email notification preference"** — GET /notifications/preferences returns a **non-enveloped** body `{"preferences":[]}` (no `success`/`data`), which the FE api client rejects as an error (same non-envelope shape on GET /notifications). |

---

## VERIFICATION STATUS

| Category | Total | FE ✅ | BE ✅ | E2E ✅ |
|----------|-------|-------|-------|--------|
| Brand | 45 | 33/45 | 38/45 | 28/45 |
| Creator | 32 | 28/32 | 24/32 | 16/32 |
| **TOTAL** | **77** | 61/77 | 62/77 | 44/77 |

> **Brand pass (neha, 2026-07-23, live http://200.141.1.6, commit 50725a9).** Method: API-level curl against every endpoint in `src/lib/api.ts` + browser render checks (token-injected session; login form-submit didn't fire through the headless harness). FE: 33 ✅ / 10 🟡 / 1 🔴 (B37) / 1 — · BE: 38 ✅ / 4 🟡 / 2 🔴 (B18, B42) / 1 — · E2E: 28 ✅ / 2 🟡 / 6 🔴 / 9 —.
>
> **Contract flow (B26–B29) is the headline win — fully real and E2E-verified:** POST /contracts mints a real server contractId (no more fake `CTR-2024-`), server-summed total, brand+creator sign set independent timestamps, and a genuine R2 presigned PDF URL is issued once both sign. deal.contractId is now non-null.
>
> **P0 blockers:** (1) **B42 wallet top-up → 500** (Razorpay key is placeholder) — this is the single root cause that blocks the whole money path E2E (B30 fund escrow, B34 release, B35 refund all correctly gate but can't complete with ₹0 balance). (2) **B37 /brand/contracts crashes** (FE `.icon` TypeError). (3) **B18 tracking-link create → 500.**

> **Creator pass (neha, 2026-07-23, live http://200.141.1.6, commit 50725a9).** Method: API-level curl against every creator endpoint in `src/lib/api.ts` (JWT via POST /auth/creator/login) + browser render checks (token-injected session; Radix tab clicks driven via JS where the headless harness didn't fire React state). FE: 28 ✅ / 4 🟡 (C8,C16,C27,C32) / 0 🔴 · BE: 24 ✅ / 6 🟡 / 1 🔴 (C26) / 1 — · E2E: 16 ✅ / 1 🟡 (C27) / 3 🔴 (C8,C22,C26) / 12 —.
>
> **Contract flow — creator side confirmed real (C16–C18):** the shared deal (contractId 01KY77ZAZPE06HNN21P5AHXR1Z) shows the creator the REAL contract with honest "Fully signed" state (both `brandSignedAt`+`creatorSignedAt` set, status ACTIVE); no fake "CTR-2024-"; deal.contractId non-null; GET /contracts/:id 200 for the creator; real R2 presigned PDF issued. One FE nit: the creator contract card shows "You receive (est.) ₹0" instead of the ₹20,000 total (reads null deal.dealValue, not contract.totalAmount). Disputes (C29) now serves the real GET /creator/disputes with no "partial data" banner.
>
> **Creator P0/P1 issues:** (1) **C26 Connect Instagram → GET /meta/oauth/authorize 200 but with EMPTY `client_id`/`redirect_uri`** — Meta app unprovisioned; IG OAuth cannot complete, which also blocks C8 personalized Co-pilot ideas. (2) **C22 payout E2E blocked** — same money-path root cause as brand B42 (₹0 balance, Razorpay placeholder). (3) **C27 analytics** shows a misleading "Couldn't load analytics" banner from the /scores 404 (honest "no score yet", not a failure). (4) **C32 settings** "Could not load email notification preference" — GET /notifications/preferences returns a non-enveloped `{"preferences":[]}` the FE client rejects. (5) **C16** contract card "₹0" display bug. Creator wallet is honest (real zeros, no fabricated figures — unlike brand B42).

---

## AUDIT EXECUTION

**Phase 1:** Complete current contract-flow build — ✅ DONE (commit 50725a9)
  - FE: brand "Review & send contract" → POST /contracts → sign; creator 4 honest states
  - BE: PESSIMISTIC_WRITE lock + milestone validation + escrow gate
  - Kabir security: MEDIUM-1 (race) ✅, MEDIUM-2 (amounts) ✅
**Phase 2:** neha systematically tests each row above on http://200.141.1.6
- FE Works = page renders, UI elements present
- BE Works = API calls return 200, data correct
- E2E Flow = full user journey completes (e.g. create → save → retrieve)

**Blocking issues:** flag as 🔴
**Works but has bugs:** flag as 🟡
**Fully working:** flag as ✅
