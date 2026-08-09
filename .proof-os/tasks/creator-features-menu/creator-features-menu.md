# Creator — Features & Menus
**Source:** `src/components/creator/creator-layout.tsx` + all `src/pages/creator-*.tsx` + `src/lib/application-status.ts`  
**Date:** 2026-08-09  
**Branch:** fix/brand-audit-remediation  
**Reviewed by:** Priya (fresh-context, 2026-08-09) — 11 errors corrected, 7 surfaces added

---

## 1 · Sidebar Navigation — MAIN group

| # | Menu Label | Route | Icon | Feature Description |
|---|---|---|---|---|
| 1 | **Home** | `/creator/dashboard` | 🏠 Home | Dashboard rollup. Stats: active deals, wallet balance, pending deliverables. Quick-action cards link to: **Deals · Campaigns · Wallet · Affiliate earnings · Your public page**. |
| 2 | **Deals** | `/creator/deals` | 💼 Briefcase | Unified deal management (replaces legacy Inbox / Active / Deal Room). Status chips: **All · New · Negotiating · Active · Completed · Disputed**. "Active" chip sends `status=contracted,in_progress,review` (CR-13). Per-deal actions: **Accept · Counter · Decline** (visible button labels; internal handlers are `handleAccept/handleCounter/handleReject`). Unread-message badge on nav item + top-header bell (`useCreatorUnreadCount`). |
| 3 | **Campaigns** | `/creator/campaigns` | 📢 Megaphone | Browse open brand campaigns. **Search** by keyword. **Filter panel**: niche (Fitness / Fashion / Beauty / Tech / Food / Travel / Lifestyle / Gaming), platform (Instagram / YouTube / TikTok), budget range slider ₹10 K – ₹10 L. Apply directly from the campaign card. Campaign detail opens at `/creator/campaigns/:id`. |
| 4 | **Applications** | `/creator/applications` | 📋 ClipboardList | Track submitted campaign applications. Filter tabs: **All · Applied · Shortlisted · In negotiation · Active · Completed · Closed**. Note: "Rejected" is NOT a valid label — a withdrawn/declined application renders as "Closed" (CTO arbitration, Kabir R5). Status badge per card. |
| 5 | **Co-pilot** | `/creator/copilot` | ✨ Sparkles | AI content partner. Delivers a **daily content suggestion** from connected Instagram. Pre-connect: shows illustrative `CopilotPreviewCard` + Connect Instagram CTA (`IGConnectPrompt`). Post-connect: live `DailySuggestionSection`. |
| 6 | **Analytics** | `/creator/analytics` | 📊 BarChart3 | Creator's own 30-day performance. Panels: **Metrics** (followers, reach, impressions), **Engagement Rate Gauge**, **Fake Follower Indicator**, **Quality Score**, **Brand Safety Badge**, **Audience Demographics**, **Content Performance** (per-post via `GET /me/media`), **Received Reviews**. All data via `GET /creator/analytics/me/*`. |
| 7 | **Wallet** | `/creator/wallet` | 👛 Wallet | Balance header card: available / escrow-locked / pending payouts. **Tabs (in order): Payouts · History · Invoices · Tax Docs**. Payouts tab: payout-method management + withdraw-funds dialog. History tab: full transaction ledger. Invoices: campaign service invoices + platform commission invoices (downloadable). Tax Docs: GST/TDS documents. |

---

## 2 · Sidebar Navigation — MANAGE group

| # | Menu Label | Route | Icon | Feature Description |
|---|---|---|---|---|
| 8 | **Reviews** | `/creator/reviews` | ⭐ Star | **Give:** rate a brand after a deal reaches COMPLETED. **Receive:** view all reviews left about the creator by brands (`GET /creator/reviews/received`). Shared `CollaborationReviewsPanel` (role=`creator`). |
| 9 | **Disputes** | `/creator/disputes` | ⚠️ AlertTriangle | Raise and track disputes on funded deals. Open a dispute: select eligible deal, pick reason, submit. Status labels: **Open · Under Review · Resolved — brand's favour · Resolved — creator's favour · Resolved — split**. v1: status-only (no money release UI). Backed by `GET /creator/disputes`. |
| 10 | **Coupons** | `/creator/coupons` | 🎟 Ticket | View coupon codes and tracking links assigned across campaigns. One-click copy to share with audience. `GET /creator/coupons`. Demo mode returns labeled illustrative rows when API is offline. |
| 11 | **Affiliate** | `/creator/affiliate` | 📈 TrendingUp | Per-sale affiliate commission rows + summary total. `GET /creator/affiliate-earnings` (Wave D backend, AffiliateEarningsService + settlement job). |

---

## 3 · Avatar Dropdown (sidebar bottom — desktop / header right — mobile)

| # | Item | Route | Feature Description |
|---|---|---|---|
| 12 | **Profile** | `/creator/profile` | Edit creator identity: display name, bio, city, **rate range (rateMin + rateMax)**. Connect / display social accounts (Instagram, YouTube). Profile completion progress bar. Avatar camera button present (⚠️ `onClick` not wired — dead control in current build). `PATCH /me/creator-profile`. |
| 13 | **Public Page** | `/creator/portfolio` | Portfolio editor — the page brands see when they discover the creator. Add / reorder portfolio items. Public URL is **`/@{username}`** (not `/c/{handle}`). Backed by `GET/PUT /me/portfolio`. |
| 14 | **Settings** | `/creator/settings` | **Notifications:** global email toggle only (real, `GET/POST /notifications/preferences`); per-category switches and SMS toggle are rendered but disabled (UI-only, no push channel). **Security:** change password. **KYC Identity** (`KycIdentityForm`). **Tax Identity** PAN/GST (`TaxIdentityForm`). **Payout / Banking:** row navigates to `/creator/wallet`. **Help & Support / Contact / Terms links:** rendered but all `onClick: () => {}` (no-ops in current build). **Log out.** **Delete account.** |
| 15 | **Help & Support** | `https://help.influora.com` | Opens external help centre in new tab (avatar dropdown shortcut). |
| 16 | **Log out** | → `/creator/login` | Confirms via `AlertDialog`, then clears JWT + `creator_email` / `creator_display_name` session keys, redirects to login. |

---

## 4 · Top Header (always visible, every page)

| # | Element | Feature Description |
|---|---|---|
| H1 | **Search bar** *(desktop only)* | Placeholder "Search collaborations…" — collapses to search icon button on mobile. |
| H2 | **Notifications bell** | Real-time unread deal-message count badge (red dot, `9+` cap). Keyed on `pathname` via `useCreatorUnreadCount`. |
| H3 | **Avatar / user menu** *(mobile)* | Dropdown: handle / email skeleton, then **Profile · Public Page · Settings · Log out** (same 4 items as desktop sidebar bottom; Help & Support not repeated here). |

---

## 5 · Auth / Onboarding pages (standalone — no sidebar shell)

| # | Page | Route | Description |
|---|---|---|---|
| A1 | **Login** | `/creator/login` | Email + password login. Links to Register + Forgot Password. |
| A2 | **Register** | `/creator/register` | New creator sign-up. |
| A3 | **Forgot Password** | `/creator/forgot-password` | Password reset email trigger. |
| A4 | **Onboarding** | `/creator/onboarding` | Post-registration guided setup. |
| A5 | **Meta OAuth Callback** | `/creator/settings/meta/callback` | Handles redirect from Instagram / Facebook Business account connection. ⚠️ Route is `/creator/settings/meta/callback` — other paths will not receive Meta's real redirect. |

---

## 6 · Additional routes (real, wired in `App.tsx`)

| # | Page | Route | Description |
|---|---|---|---|
| R1 | **Campaign Detail** | `/creator/campaigns/:id` | Full campaign detail — description, requirements, brand profile, apply CTA. |
| R2 | **Deal Room Chat** | `/creator/chat` (+ `/creator/inbox`, `/creator/active`) | Deal room messaging + deliverable lifecycle. `/creator/inbox` and `/creator/active` are legacy redirects (`?status=new` / `?status=in_progress`). |
| R3 | **Public Portfolio** | `/@{username}` | Brand-facing public profile page. Not inside the `/creator/*` namespace. (`App.tsx` route literal is `/:handle`; the `@` prefix is stripped inside the component — user-facing URL is `/@{username}`.) |

---

## 7 · Shell-level UX (always present inside the CreatorLayout)

| # | Element | Where | Description |
|---|---|---|---|
| S1 | **Sidebar logo** | Desktop sidebar top | Clicking the Influora logo navigates to `/creator/deals` (not dashboard). |
| S2 | **Mobile hamburger Sheet** | Top-left header | Opens a `<Sheet>` titled "Navigation" with the full `navGroups` list (same items as desktop sidebar). |
| S3 | **Logout confirmation dialog** | Triggered from Log out item | `<AlertDialog>` — requires a second confirm click before `handleLogout()` fires. |

---

## Summary

| Location | Items |
|---|---|
| Sidebar — Main nav | 7 |
| Sidebar — Manage nav | 4 |
| Avatar dropdown | 5 (Profile / Public Page / Settings / Help / Log out) |
| Top header | 3 |
| Auth / standalone | 5 |
| Additional routes | 3 |
| Shell-level UX | 3 |
| **Total surfaces** | **30** |

---

## Known limitations (not checked by this document)

- Avatar upload camera button (`/creator/profile`) — rendered, onClick not wired
- Settings per-category notification switches — rendered, disabled (no backend)  
- Settings Help / Contact / Terms links — rendered, all no-ops
- Wallet Withdraw and Payout-method dialogs — wired to live API but not verified end-to-end in this pass

---

*Sources read (law 3):*  
`src/components/creator/creator-layout.tsx` · `src/lib/application-status.ts` · `src/pages/creator-dashboard.tsx` · `src/pages/creator-deals.tsx` · `src/pages/creator-campaigns.tsx` · `src/pages/creator-applications.tsx` · `src/pages/creator-copilot.tsx` · `src/pages/creator-analytics.tsx` · `src/pages/creator-wallet.tsx` (L560-577) · `src/pages/creator-reviews.tsx` · `src/pages/creator-disputes.tsx` · `src/pages/creator-coupons.tsx` · `src/pages/creator-affiliate-earnings.tsx` · `src/pages/creator-profile.tsx` · `src/pages/creator-settings.tsx`
