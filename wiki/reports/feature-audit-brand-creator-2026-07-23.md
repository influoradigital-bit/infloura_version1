# Influora Feature Audit — Brand & Creator (2026-07-23)

Ordered by: Swapnil (CEO)
Executed by: neha (live QA)
Date: 2026-07-23

## BRAND FEATURES

| # | Feature | Route | FE Works? | BE Works? | E2E Flow? | Notes |
|---|---------|-------|-----------|-----------|-----------|-------|
| **AUTH** |
| B1 | Login | /brand/login | | | | Email + password → token → dashboard |
| B2 | Register | /brand/register | | | | New account creation |
| B3 | Forgot password | /brand/forgot-password | | | | Password reset email |
| **ONBOARDING** |
| B4 | Brand onboarding wizard | /brand/onboarding | | | | Company name, industry, etc |
| **DASHBOARD** |
| B5 | Home / Dashboard | /brand/dashboard | | | | KPIs, quick actions, recent activity |
| **MEERA AI** |
| B6 | Meera chat | /brand/meera | | | | AI responds to prompts |
| B7 | Meera → show_creators tool | /brand/meera | | | | "Show me beauty creators" returns list |
| B8 | Meera → calculate_budget tool | /brand/meera | | | | Budget estimation works |
| B9 | Meera → create_campaign (draft) | /brand/meera | | | | Creates DRAFT in /brand/campaigns |
| B10 | Meera → get_campaign_performance | /brand/meera | | | | Returns analytics for a campaign |
| **CAMPAIGNS** |
| B11 | View campaigns list | /brand/campaigns | | | | All/Active/Draft/Paused/Completed tabs |
| B12 | Create campaign (wizard) | /brand/campaigns/new | | | | Step-by-step form |
| B13 | Create Hype campaign | /brand/campaigns/new/hype | | | | Special viral campaign type |
| B14 | Edit campaign | /brand/campaigns/:id/edit | | | | Update existing campaign |
| B15 | View campaign detail | /brand/campaigns/:id | | | | Full campaign view with stats |
| B16 | Delete campaign | /brand/campaigns | | | | Remove campaign |
| B17 | Duplicate campaign | /brand/campaigns | | | | Copy campaign |
| B18 | Campaign tracking | /brand/campaigns/:id/tracking | | | | UTM / tracking links |
| **CREATOR DISCOVERY** |
| B19 | Browse creators | /brand/discover | | | | Filter by niche/platform/followers |
| B20 | View creator profile | /brand/creators/:id | | | | Full creator details |
| B21 | Send deal request to creator | /brand/creators/:id | | | | Initiate collaboration |
| **DEAL ROOM** |
| B22 | View deals list | /brand/deals | | | | All deals with status |
| B23 | Deal room chat | /brand/deals/:id or /brand/chat | | | | Real-time messaging |
| B24 | Negotiate terms | Deal Room | | | | Counter-offers, pricing |
| B25 | Accept/reject terms | Deal Room | | | | Terms agreement |
| **CONTRACT** |
| B26 | Create contract | Deal Room → Contract tab | | | | POST /contracts works |
| B27 | Brand signs contract | Deal Room → Contract tab | | | | Brand signature recorded |
| B28 | View contract status | Deal Room → Contract tab | | | | See who signed |
| B29 | Download contract PDF | Deal Room → Contract tab | | | | Real presigned URL |
| **ESCROW** |
| B30 | Fund escrow | Deal Room | | | | Money goes into escrow |
| B31 | View escrow status | Deal Room | | | | Locked/Released/Refunded |
| **DELIVERABLES** |
| B32 | View deliverables | /brand/contracts or Deal Room | | | | Track submissions |
| B33 | Approve/reject deliverable | Deal Room | | | | Accept creator work |
| **PAYMENT** |
| B34 | Release payment | Deal Room | | | | Pay creator from escrow |
| B35 | Request refund | Deal Room | | | | Dispute-based refund |
| **OTHER PAGES** |
| B36 | Pipeline (Kanban) | /brand/pipeline | | | | Deal stages visualization |
| B37 | Contracts list | /brand/contracts | | | | All contracts |
| B38 | Messages | /brand/messages | | | | Direct messaging |
| B39 | Analytics | /brand/analytics | | | | Campaign performance |
| B40 | Reviews | /brand/reviews | | | | Rate creators, see ratings |
| B41 | Disputes | /brand/disputes | | | | Open/manage disputes |
| B42 | Wallet | /brand/wallet | | | | Balance, transactions |
| B43 | Settings | /brand/settings | | | | Account settings |
| B44 | Billing | /brand/settings/billing | | | | Payment methods |
| B45 | Help | /brand/help | | | | Support/FAQ |

---

## CREATOR FEATURES

| # | Feature | Route | FE Works? | BE Works? | E2E Flow? | Notes |
|---|---------|-------|-----------|-----------|-----------|-------|
| **AUTH** |
| C1 | Login | /creator/login | | | | Email + password → token |
| C2 | Register | /creator/register | | | | New creator account |
| C3 | Forgot password | /creator/forgot-password | | | | Password reset |
| **ONBOARDING** |
| C4 | Creator onboarding | /creator/onboarding | | | | Profile setup, platforms |
| **DASHBOARD** |
| C5 | Home / Dashboard | /creator/dashboard | | | | Earnings, pending, quick links |
| **CO-PILOT AI** |
| C6 | Co-pilot page | /creator/copilot | | | | Daily content ideas |
| C7 | Pre-connect preview | /creator/copilot | | | | Shows sample idea before IG connect |
| C8 | Post-connect ideas | /creator/copilot | | | | Personalized ideas (needs IG) |
| **CAMPAIGNS** |
| C9 | Find campaigns | /creator/campaigns | | | | Browse brand campaigns |
| C10 | View campaign detail | /creator/campaigns/:id | | | | Full campaign info |
| C11 | Apply to campaign | /creator/campaigns/:id | | | | Send application |
| **DEALS** |
| C12 | View deals | /creator/deals | | | | All deals with status |
| C13 | Deal room chat | /creator/chat?deal= | | | | Messaging with brand |
| C14 | Negotiate/counter | Deal Room | | | | Pricing negotiation |
| C15 | Accept/reject offer | Deal Room | | | | Agreement |
| **CONTRACT** |
| C16 | View contract (from brand) | Deal Room → Contract tab | | | | See brand's contract |
| C17 | Creator signs contract | Deal Room → Contract tab | | | | Creator signature |
| C18 | Download contract PDF | Deal Room → Contract tab | | | | Real PDF |
| **DELIVERABLES** |
| C19 | Submit deliverable | Deal Room → Deliverables | | | | Upload content |
| C20 | Track approval status | Deal Room → Deliverables | | | | Pending/Approved/Rejected |
| **PAYMENT** |
| C21 | View earnings | /creator/wallet | | | | Total earned |
| C22 | Request payout | /creator/wallet | | | | Withdraw to bank |
| C23 | View payment history | /creator/wallet | | | | Transaction log |
| **PROFILE** |
| C24 | Edit profile | /creator/profile | | | | Update bio, rates, etc |
| C25 | Public portfolio page | /creator/portfolio or /@username | | | | Shareable creator page |
| C26 | Connect Instagram | /creator/settings | | | | Meta OAuth |
| **ANALYTICS** |
| C27 | View analytics | /creator/analytics | | | | Reach, engagement, scores |
| **OTHER PAGES** |
| C28 | Reviews | /creator/reviews | | | | Rate brands, see ratings |
| C29 | Disputes | /creator/disputes | | | | Open/manage disputes |
| C30 | Coupons | /creator/coupons | | | | My coupons |
| C31 | Affiliate | /creator/affiliate | | | | Affiliate earnings |
| C32 | Settings | /creator/settings | | | | Account settings |

---

## VERIFICATION STATUS

| Category | Total | FE ✅ | BE ✅ | E2E ✅ |
|----------|-------|-------|-------|--------|
| Brand | 45 | 0/45 | 0/45 | 0/45 |
| Creator | 32 | 0/32 | 0/32 | 0/32 |
| **TOTAL** | **77** | 0/77 | 0/77 | 0/77 |

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
