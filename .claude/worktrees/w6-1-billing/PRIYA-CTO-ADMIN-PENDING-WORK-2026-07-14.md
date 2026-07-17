# Influora — ADMIN Pending Work

**Author:** Priya (CTO) · **Date:** 2026-07-14 · **Branch (as read on disk):** `feature/analytics-platform`
**Method:** Code-verified — direct source read of `src/admin/**`, `src/pages/admin-*.tsx`, `src/App.tsx`, admin hooks + `services/api-contracts.ts`. `.md` planning docs cross-referenced, not trusted over source.
**Scope:** What is PENDING for the admin panel only. Companion to the working-state audit of the same date.

> **`%` = share of that feature's end-to-end functionality that works today** (mounted + Spring backend running), not effort. `Pending %` = `100 − Working %`.

---

## 0. The blocker that gates everything (P0)

The real modular admin panel is **built but mounted on no route.**

- `/admin` (`App.tsx:295`) renders the **old demo** `src/pages/admin-dashboard.tsx` (fed by `lib/demo-data.ts`) — buttons are dead no-ops. ~10% working.
- The real console (`src/pages/admin-console.tsx` → all `src/admin/**` modules) and `src/pages/admin-login.tsx` are **imported by nothing in `App.tsx`**. The `AdminProtectedRoute` their headers reference **does not exist**.

**Result: the working admin panel is ~0% reachable by a user right now.** Every percentage below is the module's internal state the moment routing is added.

**Fix (P0-A):** add to `App.tsx` — `/admin/login` → `AdminLoginPage`; `/admin/*` → `AdminConsolePage` wrapped in a new `AdminProtectedRoute` (checks `localStorage.admin_token`, redirects to `/admin/login`). Retire or redirect the old demo dashboard. **Owner:** Ananya + Vikram (routing) · **Security gate:** Kabir (mandatory — auth). ~15 lines; flips overall ~0% → ~70%.

---

## 1. Pending work by feature (most-broken first)

| # | Feature | Working | Pending | What's left | Owner | Sec |
|---|---------|:------:|:------:|-------------|-------|-----|
| 1 | **Billing console** | 15% | **85%** | `AdminBillingController` doesn't exist. `MRR/ARR/churn` + subscriptions table are `MOCK_SUBSCRIPTIONS`/`DEMO_METRICS`; Comp Pro + Override modals are honest no-ops. Build backend (`GET /admin/billing/metrics`, `/subscriptions`, `POST /comp`, `/override`), add `adminBillingApi` group, swap mock → `useQuery`, wire 2 modal submits. | Vikram + Ananya | Kabir (money) |
| 2 | **Support — Tickets** | 55% | **45%** | List + thread view work. Drawer is **read-only** — no reply / assign / escalate UI, though `supportApi.reply/assign/escalate` already exist. Add action controls to `TicketDetailDrawer` + wire to those methods. | Ananya | — |
| 3 | **Moderation — Flag Queue** | 58% | **42%** | Client fully wired (`moderationApi.actionFlag` via react-query). Backend write-path to `content_flags` unconfirmed on this branch — list may be empty, actions may 404. Confirm/build `AdminModerationController`, re-verify tests vs live. | Vikram + Ananya | Kavya |
| 4 | **Campaigns monitor** | 68% | **32%** | Lists real campaigns via `campaignApi.listAll`, but `AdminCampaignService` hardcodes `spent`, `creatorCount`, `deliverablesPending/Approved`, `slaBreachRate` → `0`. Implement those aggregates; fold `listAll` into paginated `list()` with server-side filters. | Vikram | — |
| 5 | **Dashboard (CEO Pulse)** | 70% | **30%** | Live `/dashboard/pulse`, but `AdminDashboardService` returns hardcoded `0` for `revenue`, `campaignsAtRisk`, `reviewBacklog` (needs SLA def + moderation-queue read). No live socket refresh — `PulseDashboard` never subscribes to `useAdminSocket`. Implement metrics; wire `DASHBOARD_PULSE` socket event. | Vikram + Ananya | — |
| 6 | **Admin shell / nav** | 80% | **20%** | Renders + a11y correct. Topbar **notification bell** and **user menu / logout** are unwired no-op buttons. Wire bell → notifications, menu → `useAdminAuth().logout()`. | Ananya | — |
| 7 | **Auth / Login / RBAC** | 82% | **18%** | `authApi.login` + `/auth/me` + role matrix all real. Pending is **routing only** (P0-A) + confirm server MFA enforcement end-to-end. | Ananya + Vikram | Kabir |
| 8 | **Finance — Fee Config** | 85% | **15%** | Fully wired (MFA-gated, confirm dialog, audit, optimistic-lock). Pending: server-side enforcement of `expectedEffectiveDate` token (today only Hibernate `@Version` — misses the realistic "two admins minutes apart" race); new rate **not yet applied to the brand-billing path**. | Vikram | Kabir (maker-checker) |
| 9 | **Disputes** | 85% | **15%** | List + resolve (escrow release/refund/split) fully wired. Pending: no `GET /admin/disputes/:id`, so pre-resolve detail = the clicked row only. Add single-dispute read endpoint. | Vikram | Kabir (money) |
| 10 | **Users — Creators** | 85% | **15%** | List + application review + suspend/reinstate wired via `useCreatorDetail`. Pending: minor filter parity (`niche/tier/followers` unsupported server-side) + Kabir KYC pass. | Ananya + Vikram | Kabir (KYC) |
| 11 | **Users — Brands** | 88% | **12%** | Strongest module — KYC approve/reject + suspend/reinstate all real mutations w/ mandatory reason + audit. Pending: `industry/size` filters have no backend param; final Kabir KYC sign-off. | Ananya + Vikram | Kabir (KYC) |

**Overall admin (code-readiness, mounted + backend up): ~70%.**
**Overall as-it-runs today: ~0–10%** (panel unmounted; only dead demo reachable).

---

## 2. Cross-cutting / infra pending (not per-feature)

| Item | Status | Pending | Owner |
|------|--------|---------|-------|
| **`/admin` route + guard** | 🔴 | P0-A above — mount real console + login + `AdminProtectedRoute` | Ananya/Vikram · Kabir |
| **Backend admin metrics** | 🟠 | Dashboard + Campaign hardcoded-`0` aggregates (items 4, 5) | Vikram |
| **`AdminBillingController`** | 🔴 | Entire billing backend (item 1) | Vikram · Kabir |
| **TypeScript health** | 🔴 | Tree shows **117 `tsc` errors** (mostly creator/brand analytics; admin files read clean). Add `tsc --noEmit` build gate. | Ananya / Meera |
| **Stale docs** | 🟡 | `PulseDashboard`/`useFlagQueue`/`TicketList`/`useTicketList` headers still say "mocked for now" though hooks are live-wired — clean up. | Ananya |
| **`VITE_API_BASE_URL`** | 🟡 | Still `localhost:8080`; set prod URL before deploy (`VITE_API_MODE=live` already set). | Meera |
| **Backend build** | ⛔ | Java 21 jar not verified (`mvn verify` blocked upstream); can't smoke-test admin endpoints live. | Vikram + Meera |

---

## 3. Recommended sequence

1. **P0-A — mount the panel + login + guard** (Kabir gate). Unblocks all testing; ~0% → ~70%.
2. **Backend metrics** (items 5, 4) — Dashboard + Campaigns real numbers.
3. **Support reply/assign/escalate UI** (item 2) — API already exists, pure frontend.
4. **Confirm moderation write-path** (item 3).
5. **Build `AdminBillingController` + wire console** (item 1, Kabir gate).
6. **Finance server-side lock + fee→billing application** (item 8, Kabir gate).
7. Housekeeping: drive `tsc` to 0 + gate, clean stale docs, set prod API URL.

---

*Pipeline for every code item: agent → Kavya (QA) → Kabir (security, money/KYC/auth) → Meera (build/local verify) → Priya (sign-off) → Tara (reporting). Honest stubs only — never fake success on an unwired mutation.*
