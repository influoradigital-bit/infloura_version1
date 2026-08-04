# Influora — Whole-Project Deep Audit (Code-Truth)

**Date:** 2026-08-04
**Method:** Static source analysis only. Four independent fresh-context audit agents (one per domain) each traced every feature from the UI → API client → backend controller route → service logic, reading primary source only (no prior audit doc trusted). Cross-checked by an independent connection reconciliation: every typed `request()` call diffed against all 277 backend routes by method + path.
**Not verified:** Nothing was run live. "Working" = code-complete + correctly wired, **not** runtime-proven. The AI service refuses to boot without provisioned model keys, so all AI verdicts are code-truth only.

---

## 1. Executive Summary

| Domain | ✅ Working | 🟡 Partial | 🔴 Broken | ⬜ Missing | Total |
|---|---:|---:|---:|---:|---:|
| **Admin** | 17 | 3 | 2 | 15 | 37 |
| **Brand** | 53 | 6 | 0 | 10 | 69 |
| **Creator** | 46 | 1 | 0 | 10 | 57 |
| **AI / Meera** | 27 | 6 | 0 | 1 | 34 |
| **TOTAL** | **143** | **16** | **2** | **36** | **197** |

**Completion metrics (transparent, code-truth):**
- Fully working: **143 / 197 = 72.6%**
- Wired to a real backend (working + partial): **159 / 197 = 80.7%**
- Weighted (partial = ½): **151 / 197 = 76.6%**

### The one-line story
**The API wiring is excellent — the problems are not connection bugs.** All 140 typed `request()` calls in the main frontend client resolve to a real backend method+path (**zero phantom endpoints**). The real gaps are elsewhere: an unbuilt admin finance/escrow console (backend is ready, no UI), money flows that are code-complete but blocked on **infra/config** (live Razorpay keys) or **deliberate security scope exclusions**, analytics/scoring placeholders, and a handful of genuinely unbuilt user flows.

---

## 2. API Connection Reconciliation (independent oracle check)

- **Main client (`src/lib/api.ts` + `src/lib/meera-api.ts`) — brand / creator / AI:** 140 typed `request()` calls → **140 resolve. 0 phantom endpoints.** Path-level wiring is sound. Any "broken" feature here is logic / runtime / env, not a missing route.
- **Admin client (`src/admin/services/api-contracts.ts`) — separate wrapper:** **2 confirmed phantom endpoints** (both also dead code with 0 UI consumers, so they 404 only if ever called):
  - 🔴 `GET /admin/finance/payouts` — no controller exposes it (`financeApi.getPayoutQueue`).
  - 🔴 `GET /admin/marketing/referrals` — `AdminMarketingController` only exposes `/reputation` (`marketingApi.getReferrals`).
- **Orphan backend routes:** 277 backend routes vs ~200 frontend calls. The remainder are webhooks, `/internal/meera/*`, JWKS, health, and OAuth callbacks (correctly no frontend), plus the "Missing" backend routes listed per-domain below.

---

## 3. What's BROKEN (true defects) — 2

| # | Domain | Feature | Why | Evidence |
|---|---|---|---|---|
| 1 | Admin | Finance payout queue | Frontend calls `GET /admin/finance/payouts`; no such route exists → 404. Also dead code (0 consumers). | `api-contracts.ts:360` |
| 2 | Admin | Marketing referrals | Frontend calls `GET /admin/marketing/referrals`; controller only has `/reputation` → 404. Dead code. | `api-contracts.ts:744` vs `AdminMarketingController.java:38` |

Both are low blast-radius (unreachable dead code), but they are genuine phantom endpoints and should be deleted or backed by a real route.

---

## 4. What's PARTIAL (wired, but stub / placeholder / gated) — 16

### Admin (3)
- 🟡 **Dashboard CEO pulse** — core metrics real, but week-over-week `*Change` delta fields return `null` pending a `kpi_daily_snapshot` table; `ESCROW_LOW`/`SLA_BREACH` alerts unimplemented. `AdminDashboardService.java:47-53`.
- 🟡 **Email send-bulk** — backend deliberately returns `501 BULK_SEND_DISABLED` pending abuse controls / rate-limit / security review. `AdminEmailController.java:75-87`.
- 🟡 **Moderation process-approval** — works for `BRAND_KYC` + `CREATOR_APPLICATION`; throws `501 APPROVAL_ACTION_NOT_IMPLEMENTED` for `CONTENT_MODERATION` items (read-only queue). `ApprovalWorkflowService.java:172-178`.

### Brand (6)
- 🟡 **Campaign analytics** — real endpoint, but every metric is `CREATOR_REPORTED`, never platform-verified (honest by design). `api.ts:1246-1250`.
- 🟡 **Creator analytics — metrics / demographics** — real, but return an empty typed shape until a metric snapshot is computed (never 404).
- 🟡 **Creator analytics — scores** — `QualityScoreService` audienceMatch is a hardcoded neutral 50; `FakeFollowerDetectionService` deliberately not implemented. `QualityScoreService.java:23,38`.
- 🟡 **Brand analytics roster page** — falls back to `demoCreators` in mock mode; live needs real creator IDs. `brand-analytics.tsx:49,97`.
- 🟡 **TrendSpark nudge** — returns fallback templated placeholder copy when the AI client is unavailable. `CreatorNudgeService.java:215`.

### Creator (1)
- 🟡 **Deal-room shipment** — routes match, but `items` / `estimatedDelivery` are demo placeholders even in live mode (no backend field). `creator-chat.tsx:1135,2302`.

### AI / Meera (6)
- 🟡 **Meera streaming generation** — the browser SSE path to Python `/chat` is fully built but short-circuited: the frontend returns the sync `reply` and `return`s **before** `stream.open`. Also key-gated. `MeeraChatPanel.tsx:487-495`.
- 🟡 **Tool `get_campaign_performance`** — data flows and the Living-Canvas advances, but there is no inline chat-bubble renderer branch. `ToolResultRenderer.tsx:421-438`.
- 🟡 **Tool `request_payment`** + 🟡 **Tool `confirm_launch`** — executors are fully built but **deliberately excluded from the minted on-behalf JWT scope** → `403 ON_BEHALF_SCOPE_INSUFFICIENT` before the executor runs (awaiting security sign-off). `OnBehalfTokenService.java:63-66`.
- 🟡 **TrendSpark LLM recovery tagger** — `/internal/trendspark/tag` has no in-repo Java caller; depends on an external n8n workflow and uses static shared-secret auth (tracked tech-debt). `trend_tag.py:11-36`.
- 🟡 **Whole AI service boot** — `require_boot_secrets` refuses to boot without `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `SARVAM_API_KEY`, JWKS, `INTERNAL_HMAC_KEY`, `SERVICE_TOKEN_SIGNING_KEY`. Every model feature is code-complete but only runs when those are provisioned. `config.py:434-452`.

---

## 5. What's MISSING — 36

**Important distinction:** most "Missing" items are **orphan backend routes** (backend built, no UI wired) — often intentional (email-link handlers, admin/Meera-only paths, alternate endpoints). Only a few are genuinely unbuilt user-facing features. The genuinely-missing UI is what matters:

### Genuinely unbuilt user-facing features (build these)
- ⬜ **Admin Finance / Escrow / Revenue console UI** — backend endpoints `/admin/finance/revenue`, `/admin/finance/escrow`, `/admin/escrow/flagged`, `/admin/dashboard/financial`, `/admin/campaigns/at-risk`, `/admin/campaigns/hype/ops`, `/admin/moderation/suspensions`, `/admin/marketing/reputation`, `/admin/audit/entity/*` all **exist and are real**, but the only finance UI is `FeeControlPanel.tsx`. No escrow/payout/revenue/at-risk console consumes them.
- ⬜ **Admin marketing analytics** (acquisition/growth) + **appeal review** + **escrow mutate** — parked with typed `unavailable()`, no network call, no backend.
- ⬜ **Creator KYC (PAN/Aadhaar)** — `submitCreatorKyc` wrapper exists (`api.ts:1036`) but is **never called**; no KYC capture UI. Backend `POST /onboarding/creator/kyc` orphaned.
- ⬜ **Creator onboarding payout method** — `saveCreatorPayout` (`api.ts:1047`) never invoked; "deferred to first withdrawal" but withdrawal never calls it. (Payout methods *are* handled separately via `/wallet/payout-methods`.)

### Orphan backend routes (built, no consumer — lower priority)
Creator deliverable `metrics` / `status` / `proof` / `mark-posted`; `POST /creator|brand/reviews/{id}/flag`; `GET /creator/analytics/me/media`; `GET /wallet/balance`; `POST /wallet/escrow/refund|payout`; `GET /contracts/unsigned`; `POST /deliverables/{id}/reject`; campaign-template create/delete; `POST /notifications/read-all|unsubscribe` (email-link handlers); extra creator-discovery routes (`/search`, `/featured`, `/{u}/similar`, `/suggestions`); most workspace-member management ops (accept/switch/remove/invites/revoke); `meera-help ?ask=` pre-seed constant.

---

## 6. Bugs from prior tracking — status now (code-truth)

| Prior issue | Status in code today | Evidence |
|---|---|---|
| Portfolio-public `toFixed` crash | ✅ **FIXED** — all `.toFixed` calls null-guarded | `creator-portfolio-public.tsx:446,761` |
| Contract brand-sign 400 | ✅ **FIXED** — signer role server-derived from JWT | `api.ts:2147` |
| Meera on-behalf read-only scope (create_campaign 403) | ✅ **FIXED** — `create_campaign` now in `SCOPE_DEFAULT` | `OnBehalfTokenService.java:68` |
| Meera outcome-digest dropped in chat.py | ✅ **FIXED** — copied into brand_fields + rendered | `chat.py:154`, `assembler.py:301` |
| Meera blank-turn (~28%) / max_tokens truncation | ✅ **FIXED** — max_tokens 384→1536, truncation retry | `claude.py`/`loop.py`/`chat.py` |
| Escrow fund 500 on live | ⚠️ **INFRA** — code correct; placeholder Razorpay keys cause 500 | `EscrowController.java:83-88` |
| Meera money tools E2E | 🟡 **SCOPE-BLOCKED** — `request_payment`/`confirm_launch` excluded from minted scope | `OnBehalfTokenService.java:63-66` |

---

## 7. What the audit could NOT see (honesty / limits — proof-os law 5)

- **Nothing was run.** This is static analysis. A route matching by method+path does not prove the handler returns correct data at runtime.
- **The AI service is key-gated** and won't boot without provisioned keys, so no AI feature was live-exercised.
- **Money paths** (escrow, wallet, billing) are code-complete but their live behaviour depends on Razorpay live keys + webhooks — untested here.
- **Mock-mode fallbacks** (`isApiLive()`) are dev conveniences (blocked in production builds); a feature reading demo data in dev is not necessarily a production defect.

---

## 8. Recommended priority order

1. **Delete or back the 2 phantom admin endpoints** (`/admin/finance/payouts`, `/admin/marketing/referrals`) — cheap, removes broken dead code.
2. **Build the Admin Finance/Escrow/Revenue console UI** — highest-value gap; the backend is already done and sitting idle.
3. **Provision Razorpay live keys** — unblocks the entire money E2E (escrow fund/release, wallet, billing checkout) which is code-complete.
4. **Security sign-off + scope for `request_payment`/`confirm_launch`** — unblocks Meera's money tools.
5. **Build Creator KYC + onboarding-payout UI** — the only two genuinely missing creator flows.
6. **Finish analytics/scoring** — replace `QualityScoreService` audienceMatch placeholder and implement `FakeFollowerDetectionService`.

---

*Generated by a 4-agent fresh-context audit + independent connection reconciliation. Every verdict cites primary source. See `PROJECT-DEEP-AUDIT-2026-08-04.html` for the interactive, filterable dashboard.*
