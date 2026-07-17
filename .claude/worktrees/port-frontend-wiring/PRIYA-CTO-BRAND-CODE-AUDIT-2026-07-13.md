# 🏗️ PRIYA (CTO) — Brand Vertical, Code-Only Audit

> **Date:** 2026-07-13 | **Scope:** Brand app ONLY. Pure source read — every claim file-backed. No `.md` trusted.

## Verdict: Brand = ~58% usable end-to-end

**Same disease as Creator, but it hits harder here: the pages a brand uses *every day* — campaign list, campaign detail, contracts, wallet, messages — are all routed but render inline MOCK data.** The backend (17 brand controllers) and the `api.ts` client both exist. This is unwired code, not missing code — but the mock surface is the money/campaign core, so the felt-completeness is low.

---

## Per-page truth table (25 brand pages, tests excluded)

| Page | Routed? | Data source (code) | Status |
|---|---|---|---|
| brand-login | ✅ | live (7) | 🟢 Live |
| brand-register | ✅ | live (13) | 🟢 Live |
| brand-forgot-password | ✅ | live (4) | 🟢 Live |
| brand-onboarding | ✅ | `api.onboarding.*` (12) | 🟢 Live |
| brand-discover | ✅ | `creator-discovery.tsx` api×29, mock fallback | 🟢 Live |
| brand-chat (deal room) | ✅ | messages/proposals wired (17 api) | 🟢 Live |
| brand-meera (AI) | ✅ | Meera SSE panel | 🟢 Live |
| brand-new-campaign | ✅ | `api.campaigns.create` | 🟢 Live |
| brand-new-hype-campaign | ✅ | live (4) | 🟢 Live |
| brand-edit-campaign | ✅ | `campaign-form` → `api.campaigns.update` | 🟢 Live |
| brand-dashboard | ✅ | `dashboard-page.tsx` api×12 + mock×6 | 🟡 Mixed (live w/ mock gaps) |
| brand-creator-profile | ✅ | api×7, mock×3 | 🟡 Mixed |
| brand-settings | ✅ | api×2 | 🟡 Thin |
| **brand-campaigns** | ✅ | **`campaigns-list.tsx:168` renders `[demoHypeCampaign, ...mockCampaigns]`** — `api.campaigns` present but UI ignores it | 🔴 Routed but MOCK |
| **brand-campaign-detail** | ✅ | **`getCampaign()` reads `MOCK_CAMPAIGNS[id]` (`:141`)**; `mockBids`, `mockCollaborators` | 🔴 Routed but MOCK |
| **brand-contracts** | ✅ | **`contracts-and-deliverables.tsx:107` `mockContracts`** drives selection + filter (`:322,338`) | 🔴 Routed but MOCK |
| **brand-wallet** | ✅ | **`mockWalletData` (28 mock refs)**; `api.wallet` called but UI reads mock | 🔴 Routed but MOCK |
| **brand-messages** | ✅ | **`mockConversations`/`mockMessagesByConversation` (`:91,176`)** drive threads | 🔴 Routed but MOCK |
| brand-analytics | ❌ no route | wired (api×3) | 🟠 Built, unreachable |
| brand-campaign-tracking | ❌ no route | wired (UTM/coupons hooks) | 🟠 Built, unreachable |
| brand-creator-analytics | ❌ no route | wired (api×5) | 🟠 Built, unreachable |
| brand-disputes | ❌ no route | `api.brandDisputes` (api×6) | 🟠 Built, unreachable |
| brand-reviews | ❌ no route | `CollaborationReviewsPanel` → `api.brandReviews` | 🟠 Built, unreachable |
| brand-help | ❌ no route | "TODO: final copy from Nisha" | ⚪ Stub |
| brand-deals | ↪ redirect → `/brand/chat` | — | ⚪ Dead page |
| brand-pipeline | ↪ redirect → `/brand/chat` | mock (11 api, unused) | ⚪ Dead page |

**Tally:** 🟢 10 live · 🟡 3 mixed · 🔴 5 routed-but-mock · 🟠 5 built-unreachable · ⚪ 4 dead/stub.

---

## Why Brand feels less done than Creator despite similar %

The 5 mock-primary pages are not peripheral — they are **the brand's daily workspace**:
- **Campaign list** (`/brand/campaigns`) — the home screen after login shows fake campaigns.
- **Campaign detail** — bids and collaborators are mock; accept/reject may fire but the data around it is fake.
- **Contracts** — the legal/deliverable surface is entirely mock.
- **Wallet** — balance, escrow, payouts all fake (this is money).
- **Messages** — the standalone inbox is mock (note: the *deal-room* chat at `/brand/chat` IS live; `/brand/messages` is a separate, still-mock surface).

A brand user logging in today sees a convincing but fake product across its core loop.

---

## Backend is NOT the problem — 17 brand-relevant controllers exist

`CampaignController`, `ContractController`, `DealController`, `EscrowController`, `WalletController`, `BrandDeliverableController`, `BrandDisputeController`, `BrandReviewController`, `BrandPlatformFeeController`, `AnalyticsController`, `CampaignTrackingController` (+ admin/creator/shared). Every mock/unreachable page above has a live endpoint waiting.

---

## Fix order (all frontend, all cheap relative to impact)

1. **🔴 Swap the 5 core pages from inline mock → live** — campaigns-list, campaign-detail, contracts, wallet, messages. Pattern is identical each time: the `api.*` call is often already imported (wallet, campaigns) but the JSX reads the mock constant. Delete the `mock*` object, point the render at the existing call. This is the single biggest lift on perceived brand completeness.
2. **🟠 Register the 5 orphaned routes** — analytics, campaign-tracking, creator-analytics, disputes, reviews. All built + wired; add `<Route>` entries.
3. **🟡 Finish the mixed pages** — replace the residual mock in `dashboard-page.tsx` (6 refs) and `brand-creator-profile`; wire `brand-settings` persistence.
4. **⚪ Clean up** — write `brand-help` copy or drop it; delete dead `brand-deals`/`brand-pipeline` page files (routes already redirect to `/brand/chat`).

**One caveat to flag before wiring wallet/campaign-detail:** confirm the FE↔BE contract on those endpoints (the earlier program audit found escrow/wallet had a `dealId`-vs-`milestoneId` shape drift). Verify request shapes against the controller before deleting the mock, or the swap will 400.

---

*Code-only. No files modified. Companion to `PRIYA-CTO-CODEBASE-AUDIT-2026-07-13.md` and `PRIYA-CTO-CREATOR-CODE-AUDIT-2026-07-13.md`.*
