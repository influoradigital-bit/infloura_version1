# Creator Platform — Priya's Authoritative Task Assignments

> **Author:** Priya Sharma (CTO)
> **For:** Arjun Kapoor (orchestrator) to route/dispatch
> **Basis:** Audit of `CREATOR_PROGRESS.md`, `TASK_INBOX.md`, `wiki/tech/creator/CREATOR_EXEC_PLAN_FINAL.md`, and direct code review of `DealController`/`DealService`/`DealDtos`, `ContractController`/`ContractRepository`, `WalletController`/`WalletService`, `CollaborationRepository`, `creator-chat.tsx`, `creator-wallet.tsx`, `src/lib/api.ts`.
> **Supersedes:** conflicting "Next" pointers scattered across `TASK_INBOX.md` — this file is the single ordered source of truth for who does what next.

---

## 0. Audit finding that changes the plan

`TASK_INBOX.md` (Task #9, last written 12:42 IST) says the build is **RED** on `DealDtos.java:77` — `OkResponse.ok()` static factory colliding with the record's auto-generated `ok()` accessor.

I read the file directly:

```76:80:influora-api/src/main/java/com/influora/web/dto/deal/DealDtos.java
    public record OkResponse(boolean ok) {
        public static OkResponse success() {
            return new OkResponse(true);
        }
    }
```

**The fix is already in the working tree** — the static factory is `success()`, not `ok()`, so the accessor/factory name collision Meera reported is gone. This has **not been re-verified** (no `mvn` in this shell to confirm, and no new Meera entry in `CREATOR_PROGRESS.md`/`SHARED_CONTEXT.md` past 12:42 IST). Treat this as **unverified-green, not confirmed-green**. This is why "gate the shipped DealController" is P0-#1 below — it is very likely a fast rubber-stamp, not a real fix cycle, but it must not be skipped.

Also confirmed by direct read, so the assignments below are not re-litigating already-decided architecture:
- `CollaborationRepository` already has both `findByIdAndCreatorId` and `findByIdAndWorkspaceId` (join-through `Campaign.workspaceId`) — `DealService` already uses both correctly. No IDOR in `DealController` today.
- `ContractRepository` has **only** `findByIdAndWorkspaceId` — no creator-scoped query. `ContractController` is 100% `brandContext.requireBrandWorkspace(...)`-only today. This is Kabir's H-1, still unfixed. **A creator hitting any `/contracts/*` route today gets `WRONG_USER_TYPE`/403 via `BrandContextService`, not a data leak** — so it's a functionality gap blocking Task #10, not a live vulnerability, but it must be fixed via a new scoped query, not by relaxing `BrandContextService`.
- `WalletController` is 100% `brandContext.requireBrandWorkspace(...)`-only today. `WalletService.requireOrCreateUserWallet(userId)` **already exists** and is unused — this was clearly pre-built for the creator path. Wallet's `requireWorkspaceWallet(ownerId)` is already generic on "ownerId" (works for a workspace id or a user id since `Wallet.forUser()` keys by user id) — Vikram does not need a new repository method, just a new controller branch + a thin service method that skips the workspace-membership check for creators.
- `src/lib/api.ts` already ships fully-typed `deals`, `messages`, `contracts`, `deliverables`, `wallet`, `payments` clients matching the current backend DTOs almost field-for-field (per `CREATOR_EXEC_PLAN_FINAL.md` architecture note #3: "api.ts clients already written; backend must catch up"). **Ananya's chat/wallet wiring is a swap of mock state for these existing client calls, not new client-library work.**
- `creator-chat.tsx` and `creator-wallet.tsx` are **100% mock data** today (`mockDealRooms`, `mockTimelineEvents`, `mockEarningsData`, `mockPayouts`, `mockTransactions`, `console.log('[v0] ...')` stand-ins for every mutation). Neither imports anything from `src/lib/api.ts` yet.
- There is **no `DeliverableController`/`DeliverableService` yet** — Week 3 deliverable-upload work is a clean build, not a fix.

---

## 1. Critical path (authoritative order)

```
P0-1  Meera re-verify DealController build (mvn test + V33 Flyway)   ─┐
P0-1  Kavya confirm DealController QA still holds post-fix           ─┼─ PARALLEL, same trigger
P0-1  Kabir confirm no new finding from the OkResponse rename         ─┘
        │
        ▼ (build confirmed green unblocks nothing new — Ananya was never blocked on this)
P0-2  Vikram: Contract H-1 fix (ContractRepository + ContractController + ContractService)
P0-2  Vikram: Wallet creator path (WalletController + WalletService)      ── PARALLEL with each other, PARALLEL with P0-1
        │
        ▼
P0-3  Ananya: creator-chat.tsx wiring to deals/messages API   ── starts NOW, does not wait on P0-1/P0-2
P1-4  Ananya: creator-wallet.tsx wiring to wallet API          ── BLOCKED on Vikram P0-2 wallet half
P1-4  Ananya: contract panel wiring inside creator-chat.tsx    ── BLOCKED on Vikram P0-2 contract half + Kabir re-review
        │
        ▼
Week 3  Vikram: e-sign backend, DeliverableController + upload API
        Ananya: e-sign UI, deliverable upload UI
Week 4  Vikram: withdrawal API, affiliate, analytics backend
        Ananya: withdrawal UI (wallet already wired), affiliate UI, analytics UI
        Kavya: full E2E QA pass (80%+ coverage)
        Kabir: final OWASP audit
        Meera: final build gate
        Priya: final sign-off
```

**Key correction to Arjun's stated order:** Ananya's chat wiring is *not* gated on the Meera/Kavya/Kabir gate cycle at all — `DealController`'s API surface (`GET/POST /deals`, `accept/reject/counter`, `GET/POST /deals/:id/messages`) is already shipped code with an already-matching `api.ts` client. Ananya should start immediately in parallel with P0-1, exactly as Arjun specified in item #3. Only the **wallet** and **contract-signing** panel inside the chat UI are blocked on Vikram #10.

---

## 2. Vikram (Backend) — ordered task list

### V-1 · P0 · Confirm DealController compile fix (sanity check only) — SEQUENTIAL, gates nothing further for you
- **Files:** `influora-api/src/main/java/com/influora/web/dto/deal/DealDtos.java` (already shows `success()`, no `ok()` collision)
- **Action:** No code change expected. If Meera's re-run (V-Meera-1) still fails, this is your P0-interrupt; otherwise skip straight to V-2/V-3.
- **DoD:** Meera reports `mvn test -Dtest=DealServiceTest,DealControllerTest` green (12/12) and full regression green. If red, you own the fix same-day.
- **Depends on:** nothing. **Blocks:** Meera's re-verify only (informationally — you don't block on Meera, you just watch for a bounce-back).

### V-2 · P0 · Task #10 — Wallet creator path — PARALLEL with V-3, PARALLEL with the gate cycle
- **Files to touch:**
  - `influora-api/src/main/java/com/influora/web/WalletController.java` — add creator branch. Do **not** call `brandContext.requireBrandWorkspace(principal)` for a `UserType.CREATOR` principal.
  - `influora-api/src/main/java/com/influora/service/WalletService.java` — add a public method (e.g. `getBalanceForOwner(String ownerId)` / `getSummaryForOwner(String ownerId)`) that reuses the existing `requireWorkspaceWallet(ownerId)` (already ownerId-generic — no rename needed) plus `requireOrCreateUserWallet(userId)` for the lazy-create-on-first-read case. Do not touch `WalletLedgerService` or any balance-mutation logic (Priya architecture rule: money-calculation code is frozen; only the access-branch is new).
  - `GET /wallet` and `GET /wallet/balance` — both endpoints must resolve identity via `principal.getUserId()` for `UserType.CREATOR`, never a request param.
- **Endpoints:** `GET /wallet`, `GET /wallet/balance` (existing paths, new creator branch — no new route).
- **Reference:** Kabir's go/no-go in `wiki/errors/creator-context-service-T11-kabir-redteam.md` — Wallet half is **GO** as-is (Wallet keyed 1:1 by owner id).
- **DoD:**
  - [ ] Creator principal gets their own wallet balance/summary, lazily created if absent
  - [ ] Brand principal behavior unchanged (regression-safe)
  - [ ] Unit tests: creator-own-wallet happy path, creator-cannot-see-another-creator (structurally impossible since keyed by own `userId`, but assert it), brand path untouched
  - [ ] Kavya QA + Kabir security pass (lightweight — Kabir already pre-approved this half)
- **Depends on:** nothing new (Kabir's Task #11 review already green-lit this). **Blocks:** Ananya's `creator-wallet.tsx` wiring (P1-4).

### V-3 · P0 · Task #10 — Contract creator path (H-1 fix) — PARALLEL with V-2
- **Files to touch:**
  - `influora-api/src/main/java/com/influora/repository/ContractRepository.java` — **add** `Optional<Contract> findByIdAndCreatorId(String id, String creatorId)`. `Contract` has no direct `creatorId` column, so this must join through `Collaboration.creatorId` (mirror the exact pattern `CollaborationRepository.findByIdAndWorkspaceId` uses to join through `Campaign.workspaceId` — see lines 38–42 of that file for the `@Query` template).
  - `influora-api/src/main/java/com/influora/web/ContractController.java` — branch every method (`get`, `sign`, `pdfDownloadUrl`) on `principal.getUserType()`. Creator branch calls `creatorContext.requireCreator(principal)` then the new repository method with `principal.getUserId()`. Brand branch is unchanged. **Do not add a creator-facing `generate` branch** — only brands generate contracts per spec.
  - `influora-api/src/main/java/com/influora/service/ContractService.java` — extend `get`/`recordSignature`/`getPdfDownloadUrl` (or add creator-specific overloads) to accept a creator-scoped lookup path instead of assuming workspace scope.
- **Endpoints:** `GET /contracts/{contractId}`, `POST /contracts/{contractId}/sign`, `GET /contracts/{contractId}/pdf-download-url` — all three need a creator branch. `POST /contracts` (generate) stays brand-only.
- **Reference:** `wiki/errors/creator-context-service-T11-kabir-redteam.md` H-1 — **explicitly NO-GO until this lands**; Kabir will re-review this exact diff, not a fresh full audit.
- **DoD:**
  - [ ] New `findByIdAndCreatorId` join-through query added and unit-tested (own contract → found; another creator's contract → empty/404; brand-only contract with no matching collaboration creator → 404)
  - [ ] `ContractController` never trusts a path/body creator id — identity from `CreatorContextService` only
  - [ ] Creator can `sign` only their own contract; signed contract remains immutable (no update path exposed)
  - [ ] PDF download URL scoped the same way — a creator cannot mint a presigned URL for someone else's contract
  - [ ] Route diff to Kabir for the **targeted H-1 re-review** he already promised (not a full re-audit)
- **Depends on:** nothing new. **Blocks:** Ananya's contract-signing panel wiring inside `creator-chat.tsx` (P1-4); Kabir's targeted re-review.

### V-4 · P1 · Pre-prod debt: shared `TextSanitizer` (M-2 ACTIVE + M-9-1) — SEQUENTIAL, before prod deploy of deal room, does not block Week 2 sprint gate
- **Files:** new `influora-api/src/main/java/com/influora/common/TextSanitizer.java` (or similar shared util); wire into `Collaboration.notes` write path (`Collaboration.apply()`/`propose()`) and `DealMessage.content` write path (`DealService.sendMessage`, `persistProposalMessage`, `appendSystemMessage` user-supplied `reason`).
- **Reference:** `wiki/errors/creator-deal-controller-T9-kabir-redteam.md` (M-2 escalated ACTIVE, M-9-1 filed); `wiki/errors/creator-campaign-apply-T7-kabir-redteam.md` (M-2 origin).
- **DoD:** all free-text fields that render in the deal timeline are sanitized server-side before persistence (strip/escape HTML — do not rely solely on React's DOM escaping as the only layer); Kabir re-review closes M-2 + M-9-1.
- **Depends on:** V-2/V-3 not required. **Blocks:** production deploy only — does **not** block Ananya's Week 2 wiring or Week 3 start.

### V-5 · P1 · Task #7 debt: apply rate limit (M-1) — can batch with V-4
- **Files:** `CreatorCampaignController`/`CreatorCampaignService`, mirror whatever rate-limit mechanism protects `CreatorDiscoveryService#invite` (same gap exists there — this is a "make both consistent" fix, not new infra).
- **DoD:** 10/hour per creator on `POST /creator/campaigns/{id}/apply` per spec §7.2; same limiter reused on `invite`.

### V-6 · P0 (Week 3) · E-sign backend — SEQUENTIAL after V-3
- **Files:** extend `ContractController`/`ContractService`; signature capture already partially exists (`recordSignature`) — confirm creator branch (V-3) covers e-sign, add any missing "awaiting signature" list endpoint (`GET /contracts/unsigned` per exec plan) if product wants it, escrow-hold trigger on full signature (`EscrowHoldRepository` already exists — reuse, don't rebuild).
- **DoD:** creator can list unsigned contracts, sign, escrow auto-triggers on dual signature, signed contract immutable (already a repo-wide invariant — verify no update path was added).

### V-7 · P0 (Week 3) · Deliverable upload API — new build, PARALLEL with V-6
- **Files (new):** `influora-api/src/main/java/com/influora/web/DeliverableController.java`, `.../service/DeliverableService.java`, reuse existing `DeliverableMetricRepository`/`DeliverableMetricService` (already exist per `?? DeliverableMetricRepository.java`/`?? DeliverableMetricService.java` in untracked files) — **check these before writing new ones, they may already cover metrics reporting.**
- **Endpoints:** `POST /deliverables` (creator submit), `GET /deals/:dealId/deliverables`, `POST /deliverables/:id/submit`, `/approve`, `/revise` — match `src/lib/api.ts`'s `deliverables` client exactly (already written, same pattern as `deals`).
- **DoD:** creator-only submit scoped to own collaboration; brand-only approve/revise scoped to own workspace; file upload validated (type/size) before persisting a URL — no virus scan infra exists yet, flag as accepted risk to Kabir explicitly rather than silently skipping.

### V-8 · P0 (Week 4) · Withdrawal + affiliate + analytics backend — SEQUENTIAL after Week 3 closes
- **Files:** `WalletController`/`WalletService` — add `POST /wallet/withdraw`, `GET /wallet/transactions` (both already called by `src/lib/api.ts` `wallet.withdraw`/`wallet.transactions` — backend doesn't implement them yet); new `AffiliateEarningsService`/controller (check `?? AffiliateEarningRepository.java` already untracked — likely partially built, audit before greenfielding); analytics backend per `11_CREATOR_ANALYTICS_SPEC.md`.
- **DoD:** withdrawal enforces min ₹1000/₹100 (confirm which — spec says ₹1000, frontend mock says ₹100, **escalate this exact discrepancy to Swapnil/product before shipping**, do not silently pick one), goes through `WalletLedgerService.post()` only (never a direct balance mutation), affiliate + analytics read-only endpoints ship with real data, zero mock fallbacks left in prod code path.

---

## 3. Ananya (Frontend) — ordered task list

### A-1 · P0 · Wire `creator-chat.tsx` to real deal + message API — **START NOW, parallel with the gate cycle**
- **Files:** `src/pages/creator-chat.tsx` (replace `mockDealRooms`, `mockTimelineEvents`, `handleSendMessage`, `handleAcceptProposal`, `handleDeclineProposal`, `handleSubmitCounterForm`, `handleSendCounter`); consume existing `src/lib/api.ts` exports `deals` (list/get/accept/reject/counter) and `messages` (list/send/markRead) — **no new API client code needed**, these already exist and already match backend DTO shapes 1:1 (`Deal`, `DealMessage` interfaces already mirror `DealResponse`/`DealMessageResponse`).
- **Endpoints consumed:** `GET /deals?status=`, `GET /deals/:id`, `POST /deals/:id/accept`, `POST /deals/:id/reject`, `POST /deals/:id/counter`, `GET /deals/:id/messages`, `POST /deals/:id/messages`, `POST /deals/:id/messages/read`.
- **Do not touch:** contract panel (`CreatorDealContractTab`) or payments tab wiring yet — those stay on local/derived state until V-3/A-3 land, to avoid a half-wired panel throwing on a 403.
- **DoD:**
  - [ ] Deal list loads from `deals.list('creator', status)`, replaces `mockDealRooms`
  - [ ] Selecting a deal fetches/refreshes via `deals.get`
  - [ ] Timeline renders from `messages.list`, paginates via `before` cursor
  - [ ] Send message calls `messages.send`, optimistic UI + reconciles with server response
  - [ ] Accept/Reject/Counter call the real endpoints, remove all `console.log('[v0] ...')` stand-ins
  - [ ] Mark-read fires on deal open (`messages.markRead`)
  - [ ] Loading/error/empty states added (matches Ananya's own pattern from Task #8 campaign browse)
  - [ ] Kavya QA pass; Meera build verify
- **Depends on:** nothing (DealController already shipped; only needs Meera's build re-confirm, which is informational, not a blocker for starting UI work against a spec-stable API). **Blocks:** nothing downstream except the contract/payments sub-panels (A-3).

### A-2 · P1 · Wire `creator-wallet.tsx` to real wallet API — **BLOCKED on Vikram V-2**
- **Files:** `src/pages/creator-wallet.tsx` (replace `mockEarningsData`, `mockPayouts`, `mockTransactions`, `handleWithdraw`); consume `src/lib/api.ts` `wallet.get('creator')`, `wallet.withdraw`, `wallet.transactions`.
- **Note:** `mockTaxDocs`/Form 16A tab has no backend spec or endpoint anywhere in the codebase — leave as an explicit "Coming Soon" static section, do not fake-wire it; flag to Arjun as an unscoped Week 4 item if product wants it real.
- **DoD:**
  - [ ] Balance card reads real `availableBalance`/`escrowLocked`/`pendingPayouts`/`runwayDays` (render "—" when `runwayDays` is `null`, matching `WalletService.computeRunwayDays`'s documented null-means-dormant contract — do not fabricate a number)
  - [ ] Withdraw dialog calls `wallet.withdraw`, respects the real min from Vikram (see V-8 min-amount discrepancy note — do not hardcode ₹100 once backend enforces ₹1000)
  - [ ] Transaction history paginates from `wallet.transactions`
  - [ ] Payout list either sourced from a real endpoint or explicitly marked mock-pending-backend (do not silently ship fabricated `mockPayouts` next to real balance data — that's a worse UX than an honest empty state)
  - [ ] Kavya QA pass; Meera build verify
- **Depends on:** V-2. **Blocks:** nothing downstream in Week 2; feeds into A-4 (withdrawal UI, Week 4) which reuses this same wiring.

### A-3 · P1 · Wire contract-signing panel inside `creator-chat.tsx` — **UNBLOCKED** (Priya Task #23 **SHIPPED/CONDITIONAL** ~19:00 IST)
- **Files:** `src/pages/creator-chat.tsx` (`openPanel === 'contract'` branch, `CreatorDealContractTab`, `CreatorContractPanel`, `CreatorContractCard`); consume `src/lib/api.ts` `contracts.get`/`contracts.sign` + new `contracts.listUnsigned`.
- **DoD:**
  - [ ] Contract tab fetches real contract by `deal.contractId` (already returned in `DealResponse.contractId`/`contractStatus` — no new deal-side wiring needed, just stop deriving status from `creator-contract-store.ts` localStorage and read the server value)
  - [ ] Sign action calls `contracts.sign`, updates local status optimistically then reconciles
  - [ ] Escrow-funded state reflects `Deal.escrowFunded` (already in `DealResponse`) instead of the local `getAllContractStatuses()` store
  - [ ] Kavya QA pass; Meera build verify
- **Depends on:** Task #23 (V-6) ✅ gated. **Blocks:** nothing further.

### A-4 · P0 (Week 3) · E-sign UI polish + Deliverable upload UI — SEQUENTIAL after V-6/V-7
- **Files:** `creator-active.tsx`, `DeliverableSubmission` component (already exists in `src/components/creator/deal-room/`, currently only simulates via `setTimeout` in `creator-chat.tsx`'s `handleSubmitDeliverableForm`) — wire to real `deliverables.submit`.
- **DoD:** matches `CREATOR_EXEC_PLAN_FINAL.md` Week 3 Ananya DoD list verbatim (contract PDF displays, e-sign submission works, deliverable upload succeeds, metrics reporting works, active dashboard shows real-time status).

### A-5 · P1 (Week 4) · Withdrawal UI polish, affiliate UI, analytics dashboard — SEQUENTIAL after Week 3
- **Files:** `creator-wallet.tsx` (withdrawal UI already wired in A-2 — this is just min/UX polish), `creator-affiliate-earnings.tsx` (currently ❌ empty per `CREATOR_PROGRESS.md`), new analytics tab.
- **DoD:** matches exec-plan Week 4 Ananya DoD verbatim.

---

## 4. Kabir (Security) — ordered task list

### K-1 · P0 · Gate the DealController fix — PARALLEL with Meera V-Meera-1 and Kavya Kv-1
- **Scope:** You already delivered **PASS WITH FINDINGS** on Task #9 (`wiki/errors/creator-deal-controller-T9-kabir-redteam.md`). The only thing that changed since is the `OkResponse.ok()` → `success()` rename — a naming fix, not an access-control or data-handling change.
- **Action:** Confirm the rename doesn't touch anything you already reviewed (it doesn't — it's a factory method name). **No new red-team pass required.** Append a one-line confirmation to your existing findings doc once Meera's build is green. Do not re-run the full Task #9 review.
- **DoD:** findings doc updated with a timestamped "compile fix confirmed, no new attack surface" line; verdict stays PASS WITH FINDINGS (M-2 ACTIVE, M-9-1 open, both tracked against V-4).

### K-2 · P0 · Targeted H-1 re-review — **BLOCKED on Vikram V-3**
- **Scope:** Exactly what you promised in `wiki/errors/creator-context-service-T11-kabir-redteam.md` — review the specific `ContractRepository.findByIdAndCreatorId` + `ContractController` diff, not a fresh full contract-service audit.
- **DoD:** H-1 closed (GO) or a new numbered finding filed if the join-through query has a gap (e.g. missing null-check on collaboration lookup, wrong join direction). Report verdict in a new findings doc `wiki/errors/creator-contract-wallet-T10-kabir-redteam.md`.

### K-3 · P0 · Wallet-path review — can be folded into K-2, same PR
- **Scope:** Confirm `WalletController`'s new creator branch derives identity exclusively from `principal.getUserId()` (no param), and that `WalletService`'s new owner-generic method doesn't accidentally expose a brand's workspace wallet to a creator or vice versa.
- **DoD:** GO/NO-GO recorded in the same T10 findings doc as K-2.

### K-4 · P1 · Sanitizer PR review (M-2/M-9-1 close-out) — SEQUENTIAL after Vikram V-4
- **DoD:** M-2 and M-9-1 both closed in the T9 findings doc once `TextSanitizer` lands; confirm rate limit (M-1) fix from V-5 also closes cleanly.

### K-5 · P0 (Week 3) · Contracts + uploads security gate
- **Scope:** per `CREATOR_EXEC_PLAN_FINAL.md` Week 3 gate — immutable signed contracts, file upload validation (type/size — flag missing virus-scan as accepted risk, don't block on infra that doesn't exist), deliverable data isolation (creator sees only own).

### K-6 · P0 (Week 4) · Final OWASP audit
- **Scope:** full pass per `12_CREATOR_SECURITY_SPEC.md` and the Week 4 checklist in `CREATOR_EXEC_PLAN_FINAL.md` (auth, authz, input validation, data protection, business-logic/payment-manipulation, race conditions). **Any Critical/High finding blocks Priya's sign-off.**

---

## 5. Kavya (QA) — ordered task list

### Kv-1 · P0 · Confirm DealController QA still holds — PARALLEL with K-1 and Meera
- **Scope:** You already delivered **APPROVED** on Task #13 (`wiki/errors/creator-deal-controller-T9-kavya-qa.md`). Same logic as K-1 — the `OkResponse` rename doesn't invalidate your prior verdict. Confirm once Meera's build is green.
- **Backlog carried forward (not blocking):** replay-test gap (L-1) and symmetric cross-user test matrix you flagged — schedule these into the Week 4 "full E2E pass," don't do them now as a standalone task.
- **DoD:** one-line confirmation appended to your existing findings doc; no new full QA pass required unless Meera's build re-run surfaces a real regression (in which case, re-open Task #13).

### Kv-2 · P0 · QA Task #10 (wallet + contract creator path) — **BLOCKED on Vikram V-2/V-3**
- **Hostile tests to run:**
  - Creator A cannot read/sign/download-PDF for Creator B's contract (even with a guessed/enumerated valid contract id)
  - Creator cannot hit `GET /wallet` and see a brand workspace's balance, and vice versa
  - A creator with no wallet row yet gets a lazily-created zero-balance wallet, not a 404/500
  - Signed contract stays immutable after creator signs (no second sign call succeeds)
- **DoD:** new findings doc `wiki/errors/creator-contract-wallet-T10-kavya-qa.md`, routed to Kabir (K-2/K-3) same as the campaign/deal pattern.

### Kv-3 · P1 · Extend `KAVYA_QA_TEST_PLAN.md` — SEQUENTIAL, can batch with Kv-2
- **Scope:** add §17 (wallet/contract creator path) alongside the existing §16 (campaign browse/apply).

### Kv-4 · P0 (Week 3) · Contracts + deliverables QA
- **Scope:** per exec plan Week 3 — e-sign flow, deliverable upload, metrics reporting, brand approve/reject.

### Kv-5 · P0 (Week 4) · Full E2E QA pass, 80%+ coverage target
- **Scope:** run the entire checklist embedded in `CREATOR_EXEC_PLAN_FINAL.md` Week 4 QA Gate section (auth → profile → campaign → bid → contract → deliverable → payment → analytics), including the replay/symmetric-matrix items carried forward from Kv-1.
- **DoD:** coverage report published; **if coverage < 80%, block sign-off and route specific gaps back to Vikram/Ananya**, per exec plan rule.

---

## 6. Meera (Build/DevOps) — ordered task list

### M-1 · P0 · Re-verify DealController build — **THE literal next action, no dependencies**
- **Commands:**
  - `mvn test -Dtest=DealServiceTest,DealControllerTest` (expect 12/12 — 6 + 6)
  - Full regression `mvn test` (expect the same 689-ish baseline as Task #7's last full run, zero new failures beyond the two pre-existing Testcontainers/MetaOAuth issues)
  - V33 Flyway migration verify (`deal_messages` table applies cleanly against the test DB)
  - `npm run build` (frontend — already passed 32.24s clean per your last run; re-run only if Ananya's A-1 lands in the same window, otherwise this leg is unchanged)
- **DoD:** update `TASK_INBOX.md` Task #9 status from 🔴 to ✅, update `CREATOR_PROGRESS.md` blended % accordingly, update `SHARED_CONTEXT.md` with a new dated entry (same format as your 12:42 IST entry, but VERDICT: PASS). This unblocks nothing new technically (Ananya was never blocked on it) but is the sprint's official record of "Task #9 is actually done."
- **Depends on:** nothing. **Blocks:** the official close of Task #9 in the tracker; does not block any other agent's active work.

### M-2 · P0 · Build-verify Task #10 (wallet/contract creator path) — **BLOCKED on Vikram V-2/V-3**
- **Commands:** scoped `mvn test` for new `WalletService`/`ContractRepository`/`ContractController` tests + full regression; confirm no migration needed (both are query/branch additions, no schema change expected — if Vikram adds one, verify it Flyway-applies).
- **DoD:** same tracker-update pattern as M-1.

### M-3 · P1 · Build-verify Ananya's A-1/A-2/A-3 as each lands
- **Commands:** `npm run build`, `npm run dev` smoke start, existing frontend test suite if any.
- **DoD:** green build confirmed before each is marked shipped in `TASK_INBOX.md`.

### M-4 · P0 (Week 3/4) · Build gates per exec plan
- **Scope:** run the Week 3 and Week 4 build checklists verbatim from `CREATOR_EXEC_PLAN_FINAL.md` (`npm run build/dev/test/lint`, backend test suite, DB migrations, health check). Document results in `SHARED_CONTEXT.md` in the exact format the exec plan specifies.

### M-5 · Ongoing · Progress-tracker hygiene
- **Scope:** after every gate above, update `TASK_INBOX.md`, `CREATOR_PROGRESS.md`, and `SHARED_CONTEXT.md` per the existing protocol (`CREATOR_PROGRESS.md` § Progress Tracking Protocol) so this assignments doc's dependency graph stays accurate for Arjun's next dispatch cycle.

---

## 7. Rohan (CFO) — ordered task list

### R-1 · P1 · Week 2 sprint cost log — PARALLEL with everything above, no code dependency
- **Scope:** log agent-hours and any metered API cost (Meta Graph API calls from the shipped OAuth/campaign work, Razorpay/payment-gateway sandbox calls once Week 4 wallet work starts) against the 4-week creator-platform budget baseline set at kickoff.
- **Files:** `wiki/processes/cost-log.json`, plus a short dated entry wherever the existing daily-standup/cost-report convention lives (mirror the format already used for other sprints in `wiki/processes/`).
- **DoD:** Week 2 cost entry filed; any projected overrun for Week 3/4 (e.g. real Razorpay sandbox usage, PDF generation/storage costs from `ContractPdfService`/`R2StorageService`) flagged to Swapnil **before** Vikram starts Week 4 payment work, not after.

### R-2 · P1 · Flag the withdrawal-minimum discrepancy as a cost-policy question, not just a bug
- **Scope:** the spec (`10_CREATOR_PAYMENTS_SPEC.md`) says ₹1000 minimum withdrawal; the current mock UI in `creator-wallet.tsx` says ₹100. This isn't just an engineering inconsistency (flagged to Vikram/Ananya in V-8/A-2) — a lower minimum means more, smaller payout transactions, which has a direct cost impact if the payout rail (Razorpay/Stripe) charges a flat fee per transaction.
- **DoD:** a one-paragraph cost recommendation delivered to Swapnil alongside the engineering escalation, before Vikram implements V-8.

### R-3 · P2 (Week 4) · Platform fee decision follow-up
- **Scope:** `CREATOR_EXEC_PLAN_FINAL.md` architecture note #7 says the platform fee is "escalated to Swapnil — no hardcoded fee until business decision." Confirm this decision has actually been made before Vikram's Week 4 payments/analytics work needs a real fee percentage; if undecided, this is now a P0 blocker for Week 4, not a footnote.
- **DoD:** written confirmation of the platform fee % (or explicit "still open" status escalated loudly, not silently) filed before Week 4 kickoff.

---

## 8. Priya sign-off checklist (100% done)

I will not sign off until every box below is checked. This is the final gate before reporting "creator platform DONE" to Swapnil.

### Architecture & code quality
- [ ] No file under `influora-api/` bypasses `CreatorContextService`/`BrandContextService` to resolve identity from a path/body param (spot-check every new controller added since this doc, not just the ones listed above)
- [ ] Every money-affecting code path still goes through `WalletLedgerService.post()` — zero direct `Wallet.balance` mutations introduced anywhere in Weeks 2–4 work
- [ ] Zero new entities/tables that duplicate `Collaboration`/`DealMessage` (per locked architecture decision #2 — no separate `Bid`/`CampaignApplication`/`Conversation` entities)
- [ ] `TECH-STACK.md` conventions followed for every new file (Spring Boot 3/MySQL backend patterns, Vite+React frontend patterns — not Node/Prisma/Next.js anywhere)
- [ ] No hardcoded platform fee shipped without Swapnil's explicit decision (R-3)

### Functionality (per `CREATOR_EXEC_PLAN_FINAL.md` Success Metrics)
- [ ] Full creator journey works end-to-end with **zero mock data** in the production code path: signup → profile → campaign browse/apply → deal negotiation → contract e-sign → deliverable upload → payment/withdrawal → analytics
- [ ] All 13 spec files in `wiki/tech/creator/` are implemented, or any deliberate scope cut is written down and approved (not silently dropped)
- [ ] Instagram OAuth confirmed working in prod-equivalent config; YouTube explicitly deferred-with-approval, not silently missing

### Quality gates
- [ ] Kavya: test coverage ≥ 80%, full E2E pass green (Kv-5), all carried-forward gaps (replay tests, cross-user matrix) closed
- [ ] Kabir: zero Critical/High findings outstanding; every Medium (M-1, M-2, M-9-1, H-1) explicitly closed with a findings-doc entry, not just "fixed in code"
- [ ] Meera: `npm run build`, `npm run dev`, `npm run test`, `npm run lint`, backend `mvn test`, and all Flyway migrations green in the same verification pass (not stitched together from separate stale runs)
- [ ] Performance budget met: page load < 2s, API p95 response < 500ms — spot-check the deal-room timeline endpoint and campaign browse endpoint specifically, since both do N+1-shaped repository calls per row today (`toDealResponse` does 3–4 repo calls per collaboration in a list — watch this if `list()` is ever called against a creator/brand with hundreds of deals)

### Financial / business
- [ ] Rohan's Week 2–4 cost log shows no unapproved overrun
- [ ] Withdrawal minimum and platform fee are both confirmed business decisions, not engineering defaults (R-2, R-3)

### Documentation
- [ ] API docs exist for every creator endpoint shipped since this doc
- [ ] Kabir's security audit report and Kavya's coverage report are both linked from `CREATOR_PROGRESS.md`, not just sitting in `wiki/errors/`

**If any box is unchecked: route back to the owning agent with the specific box cited — do not re-run a full gate cycle for a partial gap.**

---

**End of assignments. Arjun: dispatch P0-1 (Meera build re-verify) and P0-2 (Vikram V-2 + V-3) and P0-3 (Ananya A-1) in the same tick — none of the three block each other.**
