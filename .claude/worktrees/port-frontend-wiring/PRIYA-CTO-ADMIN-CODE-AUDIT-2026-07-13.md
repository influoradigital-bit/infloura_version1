# 🏗️ PRIYA (CTO) — Admin Vertical, Code-Only Audit

> **Date:** 2026-07-13 | **Scope:** Admin panel ONLY (`src/admin/**` + backend `Admin*Controller`). Pure source read. No `.md` trusted.

## Verdict: Admin = ~60% usable end-to-end

**Different failure mode from Brand/Creator — admin has NO mock-data problem. Every hook is live-wired.** The block is 100% routing: the console hardcodes the dashboard and mounts nothing else, so 6 fully-built, live-wired screens are unreachable. Backend is strong (10 controllers). This is the cheapest vertical to finish — it's one nested router away.

---

## The core finding: 1 of 6 screens is reachable

`src/pages/admin-console.tsx:27-28` renders a **hardcoded** `<PulseDashboard/>` inside `AdminLayout` — not a nested `<Routes>`. And `src/App.tsx` registers only two admin routes: `/admin` and `/admin/login`. But `AdminLayout.tsx:44-49` ships a 6-item nav:

| Nav item | Target | Route registered? | Screen component | Live hook |
|---|---|---|---|---|
| Dashboard | `/admin` | ✅ | `PulseDashboard` | `usePulseData` 🟢 |
| Users | `/admin/users` | ❌ **dead link** | `BrandProfile` / `CreatorProfile` | `useBrandDetail`, `useCreatorDetail` 🟢 |
| Campaigns | `/admin/campaigns` | ❌ **dead link** | `CampaignTable` | `useCampaignList` 🟢 |
| Finance | `/admin/finance` | ❌ **dead link** | `FeeControlPanel` | `useFeeConfig` 🟢 |
| Support | `/admin/support` | ❌ **dead link** | `TicketList` | `useTicketList` 🟢 |
| Moderation | `/admin/moderation` | ❌ **dead link** | `FlagQueue` | `useFlagQueue` 🟢 |

**Clicking any nav item except Dashboard lands on an unrouted path (blank screen).** Verified: `CampaignTable`, `FeeControlPanel`, `FlagQueue`, `TicketList`, `BrandProfile` are rendered in **zero** files outside their own definition. The components and their live hooks are complete — they're just never mounted.

---

## Hooks are all LIVE — the old "admin on mock" claim is dead

All 8 admin hooks import a real HTTP client from `services/api-contracts.ts` (no mock branch):

```
useAdminAuth   → authApi        useFeeConfig   → financeApi
usePulseData   → dashboardApi   useFlagQueue   → moderationApi
useBrandDetail → brandApi       useTicketList  → supportApi
useCreatorDetail → creatorApi   useCampaignList → campaignApi
```

`api-contracts.ts:58,64-70`: `API_BASE='/api/v1/admin'`, real `fetch`, `Authorization: Bearer ${localStorage.admin_token}`. The `mock` strings that grep finds in `CampaignTable.tsx`/`FlagQueue.tsx`/`TicketList.tsx` are **stale header comments** ("mocked for now") that no longer match the live hooks wired beneath them.

---

## Backend: 10 controllers, strong — but 3 FE groups are phantom

Real controllers: `/admin/auth`, `/admin/brands`, `/admin/campaigns`, `/admin/creators`, `/admin/dashboard`, `/admin/disputes`, `/admin/moderation`, `/admin/support/tickets`, `PlatformFeeAdminController` (fee-config), `AuditLogController`.

But `api-contracts.ts` declares **13** api groups — several have **no backend at all**, so any screen wired to them will 404 the moment it's mounted:

| FE group | Backend? | Risk |
|---|---|---|
| authApi, dashboardApi, brandApi, creatorApi, campaignApi, supportApi, moderationApi | ✅ | fine |
| financeApi (fee-config) | ✅ `PlatformFeeAdminController` | fine |
| financeApi (`/finance/revenue`,`/payouts`,`/reconciliation`) | ⚠️ likely absent | 404 when finance dashboard mounts |
| **errorApi** (`:558`) | ❌ 0 files | phantom |
| **emailApi** (`:581`) | ❌ 0 files | phantom |
| **marketingApi** (`:616`) | ❌ 0 files | phantom |
| escrowApi (`:408`), auditApi (`:536`) | ⚠️ thin/1 file | verify before use |

---

## Disputes: missing end-to-end

No admin dispute **FE** component, hook, or `disputeApi` group exists — and backend `AdminDisputeController` is a self-described status-only stub (no escrow movement). The only admin feature that's genuinely absent on both ends, not just unrouted.

---

## Fix order (cheapest vertical to finish)

1. **🔴 Turn `admin-console.tsx` into a nested router** — replace the hardcoded `<PulseDashboard/>` with `<Routes>` mapping `/admin`, `/admin/users`, `/admin/campaigns`, `/admin/finance`, `/admin/support`, `/admin/moderation` to the 6 existing components, and change the `/admin` route in `App.tsx` to `/admin/*`. This single change makes all 6 built screens reachable. **~80% of the admin gap closes here.**
2. **🟠 Delete or guard the 3 phantom FE groups** (`errorApi`, `emailApi`, `marketingApi`) and the unbacked `financeApi` endpoints, so no screen silently 404s. Either build the controllers or remove the client stubs.
3. **🟡 Build Disputes end-to-end** (or hide it) — FE screen + `disputeApi` + finish `AdminDisputeController` beyond the status stub.
4. Update the stale "mocked for now" header comments so future audits don't misread live screens as mock.

---

## Note for the security lead

`admin_token` lives in `localStorage` (`api-contracts.ts:64`) — XSS-readable, same accepted-risk as the brand/creator tokens. And `useBrandDetail`/`useCreatorDetail` still carry "PENDING Kabir review before ship" annotations; server-side MFA/role re-auth is asserted but not verified in this pass. Flagging before these PII-heavy screens go live.

---

*Code-only. No files modified. Companion to the Brand and Creator code audits + `PRIYA-CTO-CODEBASE-AUDIT-2026-07-13.md`.*
