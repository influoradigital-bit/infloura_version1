# Influora Admin + Creator — Blind-Spot Audit

**Date:** 2026-08-04
**Scope:** Admin domain (primary focus) + Creator domain. This report re-checks every Admin and Creator claim in `PROJECT-DEEP-AUDIT-2026-08-04.md` — one by one — against primary source, the same way `BRAND-BLINDSPOT-AUDIT-2026-08-04.md` re-checked the Brand claims.
**Method:** Two independent **fresh-context checkers** (one Admin, one Creator), each given only the audit file + the done_when, each re-tracing every row FE-file → typed api wrapper → backend controller route → service. Every checker finding was then **re-verified by a deterministic grep** by the dispatcher before being listed.
**Done_When:** The blind-spot report is compared against the .md file for Creator + Admin only.

---

## 1. Headline — the audit's Admin & Creator picture holds up

Unlike Brand (where re-tracing found **7 rows mislabeled WORKING**), the Admin and Creator sections of the deep audit are **accurate**. Every "Working / Partial / Broken / Missing" claim traced cleanly to primary source. The known blind spot — **call-site reachability** (a route + wrapper exist but nothing calls them) — was tested on every row and holds:

| Domain | Deep audit | Corrected | Blind-spot errors found |
|---|---|---|---|
| **Admin** | 17 ✅ / 3 🟡 / 2 🔴 / 15 ⬜ = 37 | **unchanged** | **0** |
| **Creator** | 46 ✅ / 1 🟡 / 0 🔴 / 10 ⬜ = 57 | **unchanged** | **1 wording imprecision** (no bucket change) |

**Verdict ceiling: BELIEVED** — static call-site analysis; nothing exercised over live HTTP.

---

## 2. Admin — every claim re-checked (focus)

### 2.1 The 2 "Broken" phantom endpoints ✅ CONFIRMED
Both are genuine phantom routes **and** dead code (0 UI consumers), exactly as the audit says:

- 🔴 **`GET /admin/finance/payouts`** (`financeApi.getPayoutQueue`, `api-contracts.ts:360`) — no controller maps it. The whole `/admin/finance` + `/admin/escrow` namespace only exposes `/escrow` (`AdminFinanceController.java:38`), `/revenue` (`AdminRevenueController.java:36`), `/flagged` (`AdminEscrowController.java:41`), `/fee-config`. **0 callers** outside `api-contracts.ts` (re-grepped).
- 🔴 **`GET /admin/marketing/referrals`** (`marketingApi.getReferrals`, `api-contracts.ts:744`) — `AdminMarketingController.java:38` maps only `/reputation`. `AdminMarketingDtos.java:9` javadoc states referrals has "no backing data" and is deliberately not served. **0 callers** outside `api-contracts.ts`.

*Nuance (not a defect):* because both wrappers have zero callers, they never actually 404 at runtime — the audit is transparent about this ("they 404 only if ever called"). Calling them "Broken" vs "dead/Missing" is a judgment call the audit already discloses.

### 2.2 The 3 "Partial" rows ✅ CONFIRMED (cited lines accurate)
- 🟡 **Dashboard CEO pulse** — `AdminDashboardService.java:47-54`: the three `*Change` WoW-delta fields (`gmvChange`/`revenueChange`/`activeCampaignsChange`) return `null` pending a non-existent `kpi_daily_snapshot` table; `ESCROW_LOW`/`SLA_BREACH` alerts "Not implemented this cycle." Exact match.
- 🟡 **Email send-bulk** — `AdminEmailController.java:85` returns `501 BULK_SEND_DISABLED`. Exact match.
- 🟡 **Moderation process-approval** — `ApprovalWorkflowService.java:174` throws `501 APPROVAL_ACTION_NOT_IMPLEMENTED` for `CONTENT_MODERATION`; `BRAND_KYC` + `CREATOR_APPLICATION` handled. Endpoint is genuinely reachable via `useApprovalQueue.ts:48,60`. Exact match.

### 2.3 The "Missing" Finance/Escrow/Revenue console ✅ CONFIRMED
All 8 named backend routes exist and are real — `/admin/finance/revenue` (`AdminRevenueController.java:36`), `/admin/finance/escrow` (`AdminFinanceController.java:38`), `/admin/escrow/flagged` (`AdminEscrowController.java:41`), `/admin/dashboard/financial` (`AdminDashboardController.java:62`), `/admin/campaigns/at-risk` (`AdminCampaignController.java:85`), `/admin/campaigns/hype/ops` (`:96`), `/admin/moderation/suspensions` (`AdminModerationController.java:88`), `/admin/marketing/reputation` (`AdminMarketingController.java:38`), `/admin/audit/entity/*` (`AuditLogController.java:84`) — and **none is consumed by any admin UI**. The wrappers appear only in `api-contracts.ts`; corroborated by the frontend's own contract test `api-contract.test.ts:92-94` documenting the no-caller set. Only `FeeControlPanel.tsx` is live finance UI. Accurate.

### 2.4 The 17 "Working" rows ✅ REACHABILITY CONFIRMED
Every working admin wrapper traces to a real hook/page caller AND a real controller route — no working-but-unreachable mislabel found. Sample: `dashboardApi.getPulse`→`usePulseData.ts:43`; `brandApi.list`→`useBrandList.ts:71`; `moderationApi.processApproval`→`useApprovalQueue.ts:60`; `billingApi.*`→`useBillingData.ts:179,234`; `disputeApi.resolve`→`useDisputeResolve.ts`; `errorApi.*`→`useErrorLog.ts:65`.

**Admin blind-spot errors: 0. Tally 17 / 3 / 2 / 15 stands.**

---

## 3. Creator — every claim re-checked

### 3.1 KYC + payout "Missing" rows ✅ CONFIRMED
- ⬜ **Creator KYC** — `submitCreatorKyc` (`api.ts:1036`) has **0 real callers**; only javadoc comment at `creator-onboarding.tsx:35`. Backend `POST /onboarding/creator/kyc` orphaned. Genuinely missing, as claimed.
- ⬜ **Onboarding payout method** — `saveCreatorPayout` (`api.ts:1047`) has **0 real callers**; only comment at `creator-onboarding.tsx:36`. The audit's parenthetical is also correct: payout methods are handled separately via `/wallet/payout-methods` (`creator-wallet.tsx`).

### 3.2 Orphan creator routes ✅ CONFIRMED
No frontend caller for deliverable `metrics`/`status`/`proof`/`mark-posted`, `POST /creator/reviews/{id}/flag`, or `GET /creator/analytics/me/media` (re-grepped; the `deliverableStatus` hits are brand-side UI state, unrelated). Real deliverable path is a different endpoint set (`api.deliverables.submit`, `api.creatorDeliverables.*`) — reachable.

### 3.3 Prior toFixed crash fix ✅ CONFIRMED
`creator-portfolio-public.tsx:446,761` both null-guard `.toFixed`. Fixed as claimed.

### 3.4 The 46 "Working" rows ✅ REACHABILITY CONFIRMED
No mislabeled/unreachable creator row. Deals, deliverables, wallet, portfolio, disputes, campaigns, analytics hooks, coupons, reviews, affiliate — all resolve to real api wrappers with real call sites.

### 3.5 🟠 The one blind-spot correction — Deal-room shipment `items`
The audit (§4) says: *"`items` / `estimatedDelivery` are demo placeholders **even in live mode**."*

- **`estimatedDelivery` — correct.** Hardcoded in both modes: `creator-chat.tsx:2313` = `new Date(Date.now() + 3*24*60*60*1000)`, never sourced from backend. This alone justifies the PARTIAL verdict.
- **`items` — imprecise.** In the live branch (`creator-chat.tsx:1140`), `items` is derived from the real backend field: `items: [{ name: liveShipment?.productName || 'Product', quantity: 1 }]`. Only the mock branch (`:1148`) hardcodes `"Summer Dress…"`. So in live mode `items` reflects real `productName` (flattened to qty 1) — it is **not** a pure demo placeholder.

**Impact: none on the tally.** The shipment row is correctly **PARTIAL** on the strength of `estimatedDelivery` alone. This is a wording fix to the audit's evidence sentence, not a re-bucketing.

**Creator blind-spot errors: 0 mislabels; 1 wording imprecision. Tally 46 / 1 / 0 / 10 stands.**

---

## 4. What this report still could not see (law 5)

- **No live HTTP was exercised** — call-site reachability and route existence are proven statically; runtime responses are not (needs deployed backend + provisioned keys). Verdict ceiling is therefore **BELIEVED**, not PROVED.
- Backend service *logic* behind verified routes was not re-audited line-by-line (route + DTO + cited-line existence only).
- The two checkers sampled FE citations for WORKING rows and grepped call-sites for PARTIAL/MISSING rows; the UI was not run.

---

## 5. Bottom line for the human

- **The Admin and Creator sections of `PROJECT-DEEP-AUDIT-2026-08-04.md` are correct.** Every error the audit reports (2 phantom admin endpoints, 3 admin partials, the missing admin console, the 2 missing creator flows) is a real, correctly-classified defect — re-verified against primary source.
- **Contrast with Brand:** the Brand section had 7 rows mislabeled WORKING. Admin and Creator have **none** — their "Working" rows are genuinely reachable.
- **Only fix to the audit doc:** soften the Creator shipment §4 line to "`estimatedDelivery` is a demo placeholder even in live mode; `items` reflects the real `productName` in live mode" (`creator-chat.tsx:1140,2313`).

---

*Produced under proof-os task `admin-creator-blindspots-0804`. Judgment: 2 fresh-context checkers (Admin + Creator), each re-verified by deterministic grep by the dispatcher. Scored via `validate.py` → BELIEVED ceiling (static analysis; nothing exercised over live HTTP). See §5b scored rows in `.proof-os/tasks/admin-creator-blindspots-0804/report.json`.*
