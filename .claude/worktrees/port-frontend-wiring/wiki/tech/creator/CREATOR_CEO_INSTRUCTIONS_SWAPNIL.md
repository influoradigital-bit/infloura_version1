# Creator Platform — CEO Instructions (Swapnil)

> **Author:** Swapnil Maruti (CEO)
> **Date:** 2026-07-09
> **Basis:** Direct audit of `wiki/tech/PENDING_TASKS_REPORT.md` (Priya, 2026-07-09) cross-referenced against `wiki/tech/creator/CREATOR_PROGRESS.md` (923 lines), `TASK_INBOX.md`, `wiki/tech/creator/CREATOR_TASK_ASSIGNMENTS_PRIYA.md`, `wiki/tech/creator/CREATOR_DEVELOPMENT_PROCESS.md`, and a direct grep/read pass over `influora-api/src/main/java` + `src/pages` + `src/lib/api.ts`. Every "open" item below was independently verified against code, not taken on the report's word.
> **Scope:** Creator surface only. Brand/Admin items in `PENDING_TASKS_REPORT.md` are out of scope for this doc (see Rohan/Priya's cross-cutting blockers note — those get their own CEO doc if needed).
> **Supersedes:** any conflicting "creator ~72%/~70% backend/frontend" framing in `PENDING_TASKS_REPORT.md` for the specific items audited below — see §1.

---

## 0. Headline numbers

- **Creator pending items audited from `PENDING_TASKS_REPORT.md`:** 14 (7 backend, 7 frontend)
- **Confirmed genuinely open:** 8
- **Confirmed STALE (report says "not started"/"deferred" but code already ships it):** 4
- **Confirmed resolved-by-existing-architecture (not a real gap):** 2
- **Top 5 P0s to dispatch this tick:** see §5

---

## 1. CEO Decision Log (rulings that unblock stalled work)

These were listed in `PENDING_TASKS_REPORT.md` §"What I Need From Swapnil" as blocking creator-adjacent specs. Ruling now, in writing, so Priya can spec and Vikram can build without waiting another cycle:

### 1.1 Platform fee % — **APPROVED**
Rohan's recommendation is approved as-is: **10% brand-side / 15% creator-side, Option A (Influora absorbs Razorpay's gateway fee, does not pass it through as a separate line item)**. This unblocks `PlatformFeeConfig` + `PlatformFeeService` for the creator escrow-release path. No further business sign-off needed before Vikram builds §2 V-1 below.

### 1.2 Review/rating policy (creator↔brand) — **APPROVED**
- Both parties may rate each other, **only after a collaboration reaches `COMPLETED`** (no pre-completion reviews — prevents leverage/retaliation during an active negotiation or deliverable dispute).
- Rating shape: 1–5 stars + optional text (max 1000 chars, run through the existing `TextSanitizer`).
- **No anonymous reviews** — reviewer identity is visible to the reviewed party (this is a B2B collaboration platform, not a consumer marketplace; anonymity invites bad-faith ratings with no accountability).
- Moderation: either party can flag a review for admin review (reuse the admin `ContentFlag` pattern already built for the admin portal — do not invent a new moderation entity). Admin can hide a flagged review pending review; no auto-delete.
- A brand or creator cannot review the same collaboration twice (unique constraint on `collaboration_id + reviewer_type`).
- This unblocks Priya spec'ing `Review` entity + `creator-reviews`/`brand-reviews` endpoints and pages.

### 1.3 Dispute/refund policy (creator-facing scope) — **APPROVED, interim policy**
This is a legal-exposure item and deserves a full policy doc, but the team cannot stay blocked on escrow's missing exit path. Interim ruling, effective now:
- **Admin arbitrates.** No automatic brand-vs-creator resolution; a dispute always creates an admin-facing case.
- **Default refund rule pending arbitration:** any **unreleased** milestone amount stays in escrow (frozen, not auto-refunded, not auto-released) until admin resolves. Any **already-released** milestone amount is not clawed back automatically — disputes on released funds go through admin-mediated resolution outside the payment rail (this is a policy stance, not a technical one; do not build an automatic clawback mechanism).
- **Who can open a dispute:** either party, only on a collaboration with a `FUNDED` escrow hold and no unresolved dispute already open on it (one active dispute per collaboration).
- This is enough for Priya to spec a v1 `Dispute` entity (status: `OPEN → UNDER_REVIEW → RESOLVED_BRAND / RESOLVED_CREATOR / RESOLVED_SPLIT`) and an admin resolution console. Full legal policy review (refund percentages, SLA, appeals) is a **follow-up**, not a blocker for v1 shipping.

### 1.4 Week 3 "100% blended" vs. real creator-platform completion — **CLARIFIED, not a conflict**
`TASK_INBOX.md`/`CREATOR_PROGRESS.md` both say "Blended progress 100%" and "loop STOPPED." **This is correct and not stale** — it means **Week 3 sprint scope** (auth, profile, campaigns, deals, wallet, deliverables upload→metrics, e-sign, rate limits) is 100% done and gated. It does **not** mean the full 13-spec creator platform is done. Priya's `PENDING_TASKS_REPORT.md` number (~71% blended against full spec) is the correct frame for "is the creator platform finished," and both numbers are simultaneously true. Arjun: stop citing "100%" as if it means "nothing left to build" — it means "Week 3 backlog is empty," which is why the loop stopped and needs re-arming for Week 4/5 scope below.

### 1.5 YouTube OAuth — **DEFERRED from Creator GA v1** (written sign-off)

> **Authority:** Swapnil Maruti, CEO, Sage Digital  
> **Date:** 2026-07-10  
> **Closes:** Priya reconciliation gap — informal tracker deferral without CEO sign-off (`CREATOR_REPORT_RECONCILIATION_PRIYA.md` § gap 3 / P1 YouTube OAuth)

**Ruling:** YouTube OAuth is **explicitly deferred** from Creator GA v1 scope. Do **not** build `YouTubeOAuthController` / YouTube API client / YouTube connect UI for GA v1. Zero YouTube OAuth code is expected at GA; 0% on the tracker is **accepted**, not a silent drop.

**In scope (unchanged — shipped):** Instagram + Facebook via Meta OAuth (`MetaOAuthController` / PKCE path). That remains the GA v1 social-connect surface.

**Out of scope for GA v1:** YouTube OAuth connect, token refresh, channel metrics pull, and any YouTube-specific onboarding step.

**Revisit triggers (any one is enough to reopen):**
1. **Post-GA** — after Creator GA v1 ships and P0/P1 GA queue is clear; or
2. **Partner / brand demand** — a signed partner or paying brand requires YouTube-verified creators as a deal-blocker; or
3. **Priya/Arjun escalate** with a scoped build estimate and capacity (not before then).

**What this is not:** a permanent kill. TikTok remains "Future" per spec; YouTube is **deferred with written CEO sign-off**, not cancelled. Arjun/Priya: cite this §1.5 (or the one-pager below) whenever an audit re-flags YouTube OAuth 0% as an unapproved gap.

**One-pager copy:** `wiki/tech/creator/CREATOR_YOUTUBE_OAUTH_DEFERRAL_SWAPNIL.md`

---

## 2. Audit Table — every creator item from `PENDING_TASKS_REPORT.md`

| # | Item (as reported) | Report said | **Verified status** | Evidence |
|---|---|---|---|---|
| B1 | `PlatformFeeService` — deduct 15% at escrow release | Not started, P0 | ✅ **CONFIRMED OPEN** | No `PlatformFeeConfig`/`PlatformFeeService` class anywhere in `influora-api/src/main/java` (grep, zero hits) |
| B2 | Creator-facing coupon-read endpoint | Not started, P1 | ✅ **CONFIRMED OPEN** | `CampaignTrackingController` only has brand-driven `POST/GET /campaigns/{id}/coupons` (brand adds creator to program); no `GET /creator/coupons` self-service read. `creator-coupons.tsx` has zero `api.ts` calls — 100% static mock |
| B3 | `GET /creator/platform-fee` (transparency endpoint) | Not started, P1 | ✅ **CONFIRMED OPEN** | Depends on B1; no endpoint exists |
| B4 | Creator OTP signup flow | "~80%, P0" | ❌ **STALE — ACTUALLY DONE** | `AuthController.sendCreatorEmailOtp`/`verifyCreatorEmail` fully implemented server-side; `creator-register.tsx` calls `api.auth.sendCreatorEmailOtp`/verify end-to-end with resend + 6-digit input UI. This is shipped, not 80%. **Reclassify: DONE.** |
| B5 | `BrandSafetyScoreService` | "Deferred (Phase 4 epic), P2" | ❌ **STALE — ACTUALLY BUILT** | `service/scoring/BrandSafetyScoreService.java` fully implemented (156 lines, GARM risk mapping, graceful degradation, wired into `ScoreCalculationJob`). **Reclassify: DONE (backend).** Not creator-self-facing yet (no frontend surface), but not "deferred" — it shipped in a prior wave. |
| B6 | `AudienceDemographicsJob` | "Not started, P2" | ❌ **STALE — ACTUALLY BUILT** | `job/AudienceDemographicsJob.java` fully implemented (weekly Meta insights sweep, V25 `audience_demographics` table, rate-limit-aware). **Reclassify: DONE (backend).** |
| B7 | Creator growth-AI endpoints (spec 11) | "0%" | ⚠️ **PARTIALLY STALE** | `AnalyticsController` already exposes `/analytics/creators/{id}/metrics|scores|demographics` backed by real `CreatorScore`/`AudienceDemographics`/`MediaMetric` data (B5/B6 above feed it). Backend data pipeline is real, not 0%. What's actually missing: a **creator-self** endpoint variant (today's controller reads by `{creatorId}` path param, brand/admin-shaped, not `principal`-scoped for "my own growth data") and any frontend surface. **Reclassify: backend ~60% built, creator-self endpoint + frontend page = 0%.** |
| F1 | `creator-dashboard` (main landing after login) | Missing, P0 | ✅ **CONFIRMED OPEN** | No route/page exists. Creator login lands on `/creator/inbox` → redirects to `/creator/deals?status=new` (a deal list, not a summary dashboard). Real gap: no at-a-glance earnings/active-deals/pending-actions view. |
| F2 | `creator-bids` (view/manage own bids) | Missing, P0 | 🟡 **RESOLVED BY EXISTING UI — not a build gap, a naming/nav gap** | `creator-deals.tsx` already shows status-chip-filtered bids/negotiations (`New`/`Negotiating`/`Active`/`Completed`) backed by `DealService` — this **is** the bids UI, same "different name, same function" pattern Priya already found on the backend. No new page needed; at most a nav-label/IA clarity pass. |
| F3 | `creator-deliverables` (submit content) | Missing, P0 | 🟡 **RESOLVED BY EXISTING UI — architecture decision, not a gap** | Deliverable upload/submit/metrics is fully wired **inside** `creator-chat.tsx`'s deal room (Tasks #19–#24b, all SHIPPED/CONDITIONAL). This matches Priya's locked architecture (single deal-room UI, not fragmented per-feature pages). A standalone page would duplicate this. **No action needed unless product explicitly wants a cross-deal deliverables list view** (flagged as optional P2, not P0). |
| F4 | `creator-contracts` (review/sign) | Missing, P0 | 🟡 **RESOLVED BY EXISTING UI — same as F3** | E-sign (Task #23/A-3) is wired inside `creator-chat.tsx`'s contract tab/panel, SHIPPED/CONDITIONAL. Same reasoning as F3. |
| F5 | `creator-analytics` (growth tracking) | Missing, P2 | ✅ **CONFIRMED OPEN** (see B7) | No page exists; backend data exists but isn't exposed to the creator themselves yet. |
| F6 | `creator-reviews` (rate brand) | Missing, P0, blocked on Review entity | ✅ **CONFIRMED OPEN** — now **UNBLOCKED** | Review entity confirmed missing (§1.2 ruling unblocks the spec). |
| F7 | Fee transparency in `creator-wallet` | Missing, P1 | ✅ **CONFIRMED OPEN** | Depends on B1/B3; `creator-wallet.tsx` shows `availableBalance`/`escrowLocked`/`pendingPayouts` but no fee-deducted breakdown line anywhere in the component. |

**Net effect of this audit:** the "missing pages" count in `PENDING_TASKS_REPORT.md` drops from 7 to **4 real gaps** (F1, F5, F6, F7) — F2/F3/F4 are architecture-resolved, not backlog. The "backend not started" count drops from 7 items to **3 real gaps** (B1, B2, B3) plus **1 partial** (B7) — B4/B5/B6 were already shipped in prior waves and mis-tracked in the report.

---

## 3. Conflict resolution vs. `CREATOR_PROGRESS.md`

`CREATOR_PROGRESS.md` and `TASK_INBOX.md` both say **"Blended 100%, loop STOPPED"** for Week 3. `PENDING_TASKS_REPORT.md` says creator is **~71% blended** against the full 13-spec platform. **Both are correct — see §1.4.** No actual conflict, only a scope-framing mismatch. Resolution: this document's audit table (§2) is now the authoritative list of what's left; `CREATOR_PROGRESS.md`'s Week 3 100% stands unchanged as a historical sprint record and should not be reopened or "corrected."

One real discrepancy found: `CREATOR_PROGRESS.md`/`TASK_INBOX.md` never logged that `BrandSafetyScoreService` and `AudienceDemographicsJob` shipped (B5/B6) — these came from an earlier wave (pre-dates the Week 1–3 creator sprint numbering) and were never cross-referenced into the creator tracker. **Action for Meera (§4):** add a one-line backfill entry to `CREATOR_PROGRESS.md`'s changelog crediting these as already-shipped backend capability, so the next audit doesn't re-flag them as "not started" again.

---

## 4. Per-employee CEO instructions

### Vikram (Backend)

**P0-V1 — `PlatformFeeConfig` + `PlatformFeeService` (creator leg)**
- **What:** New `PlatformFeeConfig` entity (fee % configurable, not hardcoded — §1.1 sets the default to 15% creator-side but it must be a DB-backed config row, not a Java constant). `PlatformFeeService.deductAtRelease(...)` hooks into the existing escrow-release path in `EscrowService` — deduct 15% of the released milestone amount before it hits `WalletLedgerService.post()`. Money-calculation code is otherwise frozen per Priya's architecture rule — this is the one sanctioned new money-path addition.
- **DoD:** unit tests proving (a) exactly 15% deducted at release, not at funding; (b) fee lands in a platform-owned ledger account, traceable per txn; (c) zero direct `Wallet.balance` mutations outside `WalletLedgerService.post()`; (d) config is read from DB, changing it doesn't require a redeploy.
- **Deps:** none (fee % already approved, §1.1). **Blocks:** V2, V3, Ananya's F7.
- **Do NOT:** touch the brand-side fee logic in the same PR (that's Brand-surface scope, different ticket) — keep this creator-scoped. Do NOT hardcode 15% as a literal in code.

**P0-V2 — `GET /creator/platform-fee` transparency endpoint**
- **What:** Read-only endpoint returning the current fee %, scoped via `principal` (no path param). Sequential after V1.
- **DoD:** returns current config row's %, 200 for any authenticated creator, no PII/other-creator leakage (it's a global config, not per-creator, so this is low-risk but still route through `CreatorContextService` for identity, not skip auth).
- **Deps:** V1. **Blocks:** Ananya F7.

**P0-V3 — Creator-facing coupon-read endpoint**
- **What:** `GET /creator/coupons` — list the authenticated creator's own coupon codes across all campaigns they're in (self-scoped via `CreatorContextService`, reuse `CouponCodeRepository`, add a `findByCreatorId` query if missing).
- **DoD:** creator sees only their own coupons; unit test for cross-creator isolation (same IDOR-discipline pattern as every other creator endpoint this sprint).
- **Deps:** none. **Blocks:** Ananya wiring `creator-coupons.tsx` off mock data.
- **Do NOT:** add write/create capability here — creation stays brand-driven (`addCreatorToCampaign`), this is read-only.

**P1-V4 — `Review` entity + `POST /creator/reviews` + `POST /brand/reviews`**
- **What:** Per §1.2 policy: `Review` entity (collaboration-scoped, 1–5 stars + sanitized text, `reviewer_type` enum, unique per collaboration+reviewer_type, gated on `Collaboration.status == COMPLETED`). Endpoints for both directions since brand-reviews is the same entity, different reviewer_type — build both in one PR, do not duplicate the entity.
- **DoD:** cannot review before COMPLETED; cannot double-review; text sanitized via existing `TextSanitizer`; flagging endpoint reuses admin `ContentFlag` pattern, not a new moderation entity.
- **Deps:** none (policy approved §1.2). **Blocks:** Ananya's `creator-reviews`/`brand-reviews` pages.

**P2-V5 — `Dispute` entity v1 + admin resolution stub**
- **What:** Per §1.3 interim policy. `Dispute` entity + `POST /deals/{id}/disputes` (either party) + admin-only resolve endpoint. This is legal-exposure work — prioritize correctness over speed, but do not let it block V1–V4.
- **DoD:** one active dispute per collaboration max; unreleased escrow freezes on dispute open (does not auto-refund); admin resolution transitions status; no automatic clawback of released funds.
- **Deps:** §1.3 ruling (done). **Blocks:** admin dispute console (Admin-surface, separate ticket, not yours to build).

**P2-V6 — Creator-self analytics endpoint**
- **What:** New `principal`-scoped variant of `AnalyticsController`'s existing metrics/scores/demographics reads (today's controller is `{creatorId}` path-param shaped for brand/admin viewing). Add `GET /creator/analytics/me/*` or similar, reusing the exact same service methods — do not rebuild the data pipeline, it already exists (B5/B6/B7).
- **DoD:** creator can only ever read their own data via `principal.getUserId()`, never a path param.
- **Deps:** none. **Blocks:** Ananya's `creator-analytics` page (P2, can slip to next sprint).

**What NOT to do (all of Vikram's items):** do not touch `WalletLedgerService`'s core posting logic outside V1's sanctioned fee-deduction hook. Do not create a second `Bid`/`CampaignApplication`/`Conversation`-style entity — the locked architecture decision (`Collaboration` + `DealMessage`) stands; Reviews/Disputes are net-new concepts, not a bids/deals re-architecture. Do not skip `CreatorContextService`/`BrandContextService` identity resolution on any new endpoint.

---

### Ananya (Frontend)

**P0-A1 — `creator-dashboard` home page**
- **What:** New landing page at `/creator/dashboard` (creator login should redirect here going forward, not straight to `/creator/inbox`). Summary cards: available balance (reuse `wallet.get`), active deal count, pending-action count (unread messages + awaiting-signature contracts + submittable deliverables — all data already available via existing `deals`/`contracts`/`deliverables` clients, this is a rollup view, not new data), quick links to deals/campaigns/wallet.
- **DoD:** zero new backend dependency — every number on this page is derivable from already-shipped endpoints; honest empty states for a brand-new creator with zero deals.
- **Deps:** none. **Blocks:** nothing, but update the login redirect (`creator-login.tsx`) once this ships.

**P1-A2 — Fee transparency in `creator-wallet.tsx`**
- **What:** Add a "platform fee (15%)" line item wherever net earnings are shown, sourced from Vikram's V2 endpoint — do not hardcode 15% in the frontend either.
- **Deps:** Vikram V1+V2. **Blocks:** nothing downstream.

**P1-A3 — Wire `creator-coupons.tsx` off mock data**
- **What:** Replace the fully-static mock in `creator-coupons.tsx` with `GET /creator/coupons` (Vikram V3), same loading/error/empty pattern as every other creator page this sprint.
- **Deps:** Vikram V3. **Blocks:** nothing.

**P1-A4 — `creator-reviews` page**
- **What:** New page for a creator to rate a brand post-`COMPLETED` collaboration (star + text), and to view reviews left about them. Reuse the deal-room's existing patterns (loading/error/empty, `isApiLive()` gating).
- **Deps:** Vikram V4. **Blocks:** nothing.

**P2-A5 — `creator-analytics` page**
- **What:** New page surfacing growth metrics/scores/demographics via Vikram's V6 self-scoped endpoint. Can slip behind A1–A4 — this is P2, not P0.
- **Deps:** Vikram V6.

**What NOT to do:** do **not** build standalone `creator-deliverables` or `creator-contracts` pages (§2 F3/F4) — that functionality is intentionally inside `creator-chat.tsx`'s deal room per locked architecture; building a duplicate page would fragment the UX and contradict Priya's own design decision. Do not rename/rebuild `creator-deals.tsx` into a separate "bids" page (§2 F2) — if a nav-label change is wanted, that's a one-line copy edit, not a new build item; ask Priya before spending a cycle on it.

---

### Kabir (Security)

**P0-K1 — Review/Dispute entity security review (once Vikram V4/V5 land)**
- **Scope:** IDOR on review creation (can a creator review a collaboration they're not party to? can they review before COMPLETED?), double-review prevention, dispute-freeze-on-open race condition (does opening a dispute reliably freeze the escrow before a concurrent release request can slip through?), admin-only gating on dispute resolution.
- **DoD:** standard findings doc pattern (`wiki/errors/creator-review-<task#>-kabir-redteam.md`), PASS/PASS WITH FINDINGS/FAIL verdict, no rubber-stamping given this touches money (dispute) and reputation (review) surfaces — full targeted review required, not a one-line confirmation, per the doc's own re-verification rule (§5 of `CREATOR_DEVELOPMENT_PROCESS.md`).

**P0-K2 — `PlatformFeeService` review (Vikram V1)**
- **Scope:** confirm the fee deduction is the *only* new money-path code, confirm no double-deduction race (concurrent release calls), confirm config is read fresh per calculation (not cached stale across a fee-% change).
- **DoD:** same findings-doc pattern. This is LOAD-BEARING per `TECH-STACK.md` §5 (anything touching money) — no shortcut review.

**P1-K3 — Coupon-read + fee-transparency endpoint reviews**
- **Scope:** lightweight — confirm `CreatorContextService` scoping on both, no IDOR. Can batch into one findings doc with K2.

**What NOT to do:** do not defer the Review/Dispute review to a "lightweight confirmation" the way the `OkResponse` rename got one — this is genuinely new attack surface (money-freezing on dispute, reputation-affecting reviews), it needs the full red-team pass every time, per the process doc's own rule that money/state-machine/access-control changes never get a rubber stamp.

---

### Kavya (QA)

**P0-Kv1 — QA gate for V1–V4 (fee, coupon endpoint, reviews) as each lands**
- **Scope:** standard hostile-path suite per the established pattern — cross-creator isolation, double-submission/race tests, empty/error states on the frontend side. Route to Kabir after, per the fixed gate order (§5 of the process doc — Kavya always before Kabir).

**P1-Kv2 — Extend `KAVYA_QA_TEST_PLAN.md`**
- **Scope:** add sections for Review/Dispute/Fee-transparency/Coupon-read, following the existing §16/§17 numbering convention.

**P2-Kv3 — Full E2E pass (carried forward from Week 3/4 backlog)**
- **Scope:** this was already on the books (`CREATOR_PROGRESS.md` "Week 4+ backlog") — still applies, still P2 relative to the P0 items above, do not let it consume Kavya's cycle before the fee/review/dispute gates clear.

**What NOT to do:** do not skip the hostile cross-creator test on the coupon-read endpoint just because it "looks read-only and low-risk" — every creator-scoped endpoint this sprint has gotten this test, no exceptions for this one.

---

### Meera (Build/DevOps)

**P0-M1 — Backfill `CREATOR_PROGRESS.md` changelog**
- **What:** Per §3, add a one-line dated entry crediting `BrandSafetyScoreService` + `AudienceDemographicsJob` as already-shipped (from an earlier, differently-numbered wave), so future audits stop re-flagging them as "not started."
- **DoD:** entry added, does not change the blended % (they were never in this creator sprint's denominator to begin with — this is a bookkeeping fix, not a progress bump).

**P1-M2 — Build-verify V1–V6 as each lands**
- **Scope:** standard `mvn test` scoped + full regression + `npm run build`, per the fixed gate order (Meera after Kabir, before Priya). No shortcuts on the new money-path code (V1).

**P2-M3 — Re-arm the creator agent loop**
- **What:** `TASK_INBOX.md`/`CREATOR_PROGRESS.md` both say the loop is **STOPPED**. Re-arm `AGENT_LOOP_WAKE_CREATOR.ps1` (verify the PID is actually alive first, per the known recurring dead-heartbeat failure mode documented in `CREATOR_DEVELOPMENT_PROCESS.md` §2.2) now that this doc hands Arjun a fresh P0 backlog (§5).

**What NOT to do:** do not mark any of V1–V6 "build verified" from a stale/cached test run — the process doc's own Cycle-7 admin-portal lesson (Meera vetoed twice, caught a stale-artifact false alarm) applies here too: always verify with a clean build.

---

### Arjun (Orchestrator)

**Directive: dispatch all open creator P0s in the next loop tick.** See §5 for the ranked list. Specifically:
1. Re-arm the loop (Meera M3) if it's confirmed dead.
2. Dispatch Vikram V1 (fee service) and V3 (coupon-read) in parallel — they don't depend on each other.
3. Dispatch Ananya A1 (creator-dashboard) immediately — it has zero backend dependency, same "start now, don't wait for the gate cycle" pattern Priya established for chat wiring back in Week 2.
4. V4 (Review entity) can start in parallel with V1/V3 — policy is approved (§1.2), no reason to sequence it behind the fee work.
5. Do **not** dispatch anyone to build `creator-bids`/`creator-deliverables`/`creator-contracts` as new pages (§2 F2/F3/F4) — those are closed as "resolved by existing architecture," not open backlog. If you see them re-appear in a future audit, cite this doc.
6. Update `TASK_INBOX.md` with new task numbers for V1–V6/A1–A5/K1–K3/Kv1–Kv3/M1–M3 following the existing numbering convention (continue from #26).

---

### Priya (CTO)

- **P0:** Write the actual spec docs for `Review` (per §1.2) and `Dispute` v1 (per §1.3) — these were blocked purely on policy, which is now resolved. Do this before Vikram starts V4/V5 so he's not building against a verbal ruling with no written spec.
- **P0:** Sign off on §1.4's framing (Week 3 100% ≠ full platform 100%) in your own words in the next `CREATOR_PROGRESS.md` entry, so this doesn't get re-litigated by a future audit.
- **P1:** Add the creator-self analytics endpoint (V6) to whatever exec-plan doc tracks Week 4+ scope — it's new scope this audit surfaced, not previously on `CREATOR_EXEC_PLAN_FINAL.md`.
- **What NOT to do:** do not re-audit or re-litigate the Week 3 100% number itself — it's correct for its stated scope (§1.4). Spend your cycle on the new specs, not re-verifying old ones.

---

### Rohan (CFO)

- **P0:** Your fee recommendation (10%/15%, Option A) is **approved** (§1.1) — no further action needed on that specific item, it's closed.
- **P1:** Cost-model the Review/Dispute moderation load (admin time cost of `ContentFlag`-pattern review moderation, once volume exists) — not urgent, but flag it before it becomes a support-cost surprise post-launch.
- **P2:** Continue the existing withdrawal-minimum cost analysis (₹500 vs ₹1000 discrepancy) already tracked in prior cycles — unrelated to this audit, still open, still yours.

---

## 5. Top 5 P0s — dispatch this tick

| Rank | Item | Owner(s) | Why top 5 |
|---|---|---|---|
| 1 | `PlatformFeeService` + creator escrow-release fee deduction | Vikram (V1) | Blocks 100% of creator revenue integrity; policy now approved, zero excuse to delay |
| 2 | `GET /creator/platform-fee` transparency endpoint + wallet fee UI | Vikram (V2) + Ananya (A2) | Legal/trust requirement — creators must see the fee before it's deducted, not discover it after |
| 3 | `Review` entity + `creator-reviews`/`brand-reviews` build | Vikram (V4) + Ananya (A4) | Was blocked for an entire report cycle purely on policy — now unblocked, should not sit another cycle |
| 4 | `creator-dashboard` home page | Ananya (A1) | Zero backend dependency, ships immediately, closes a real "no landing experience" UX gap |
| 5 | Creator-facing coupon-read endpoint + live-wire `creator-coupons.tsx` | Vikram (V3) + Ananya (A3) | Smallest, fastest win on the list — a mock page sitting next to 16 real ones is an inconsistency worth closing now |

---

## 6. What NOT to do (cross-cutting, applies to everyone)

- Do not build standalone `creator-bids`/`creator-deliverables`/`creator-contracts` pages — closed per §2/§4.
- Do not re-litigate the Week 3 "100% blended" number — it's correct for its scope, see §1.4.
- Do not hardcode the 15%/10% fee anywhere in code (backend or frontend) — it's a DB-backed config per §1.1, not a literal.
- Do not build a second bid/negotiation entity — `Collaboration`/`DealMessage` is the locked architecture.
- Do not skip the full Kabir red-team pass on Review/Dispute/Fee work by treating it as a "naming fix" style rubber-stamp — this is genuinely new money/reputation attack surface.
- Do not mark anything "done" without a clean (not cached) Meera build-verify pass.
- Do **not** build YouTube OAuth for Creator GA v1 — **DEFERRED** with written CEO sign-off (§1.5 / `CREATOR_YOUTUBE_OAUTH_DEFERRAL_SWAPNIL.md`). Meta (IG/FB) OAuth remains the shipped GA surface.
