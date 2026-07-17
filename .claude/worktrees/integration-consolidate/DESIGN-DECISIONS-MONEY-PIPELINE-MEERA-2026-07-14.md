# Design-Decision Workstream — Money · Pipeline · Meera

**Date:** 2026-07-14 · **Owners:** Priya (CTO — technical options + recommendation) · Swapnil (CEO — priority, sequencing, sign-off)
**Companion to:** `PENDING-WORK-CODE-VERIFIED-GAPS-2026-07-14.md` §Z Bucket 2 (the `needs-arch` set).
**Why this doc exists:** ~48% of the audit is closed by the multi-agent batch, but the remaining work is not a code sweep — it needs product/CFO/architecture decisions *before* anyone writes code. This doc turns each open `needs-arch` item into a **concrete decision with options, trade-offs, a CTO recommendation, and a named decision-owner**, so the calls can actually be made and then implemented. Code anchors reference the audited branch (`feature/analytics-platform`), which is where this work should land.

**Gate rule:** no implementation ticket is opened until its decision below is marked `DECIDED` with an owner sign-off.

---

## How to read each decision
`Problem` (what's broken today + anchor) → `Options` (A/B/C with the trade-off that matters) → `Priya's rec` → `Owner` (who signs) → `Blocks` (sequencing).

---

# PART 1 — MONEY (§B) · owner mix: CEO + CFO Rohan + CTO

### D1 — Escrow funding model *(closes B-1, B-2)*
**Problem:** Two contradictory money paths. Top-up debits a clearing wallet that is never funded (`WalletLedgerService.post` rejects debit > balance; clearing wallet created at ₹0, no mint path) → **every top-up 400s**. Escrow funding *both* requires wallet balance ≥ amount *and* creates a Razorpay order for the same amount *and* debits the wallet on confirm → **brand pays ~2×**. (`WalletTopUpService.confirmCredited`, `EscrowService.initiateFund/confirmFunded`.)
**Options:**
- **A · Gateway-first (recommended):** Brand always pays escrow via a fresh Razorpay order; on `payment.captured` webhook, credit the internal wallet/hold as a bookkeeping mirror. Clearing/settlement account modeled as **external** (exempt from the balance check). One charge, one source of truth = the gateway.
- **B · Wallet-first:** Brand pre-tops-up the wallet (top-up credits via a `GATEWAY` leg exempt from the balance check); escrow funds *only* from wallet balance under a lock, **no second order**. Better UX for repeat brands, worse for first-time.
- **C · Hybrid:** Wallet if sufficient balance, else gateway top-up-to-fund in one flow. Most code, most edge cases.
**Priya's rec:** **A**. Smallest correct surface, matches how Razorpay escrow is normally modeled, and kills both bugs at once. Requires: mark clearing/settlement wallet as external (exempt), remove the double-charge in `initiateFund`, credit-on-capture only.
**Owner:** **CTO** (model) + **CFO Rohan** sign-off (money-movement correctness). **Blocks:** D2, D6.

### D2 — Payout & withdrawal disbursement *(closes B-3, B-4, B-10)*
**Problem:** `PayoutService.doQueuePayout` RazorpayX-pays the **gross** milestone amount using `creatorId` as the `fund_account_id` (placeholder) with **no ledger debit**, while `EscrowService.release` already credited the creator net-of-fee → **double pay + wrong amount**. `WalletService.requestCreatorWithdrawal` returns a fabricated `payoutId` with **no RazorpayX call, no `payouts` row, no job**. `Payout`/`PayoutRepository` are dead; webhook parses `payload.order.entity` for payout events (always null); reversals not re-credited. Idempotency key is raw/optional → double-debit on retry.
**Options (largely sequenced, not either/or):**
- **A · Real fund-account resolution:** Collect + verify creator bank/UPI via RazorpayX fund-account API at onboarding; persist `fund_account_id`. **Required for any real payout.**
- **B · Net-debit at payout:** Debit the creator's internal wallet at payout time; disburse the **net** amount; persist a `payouts` row at queue; parse `payload.payout.entity`; re-credit on `reversed`.
- **C · Require + scope idempotency:** `"creator-withdraw:"+userId+":"+clientKey`, 400 if absent.
**Priya's rec:** Do **A → B → C in order**; none are optional for real money out. This is the single biggest chunk of money work.
**Owner:** **CFO Rohan** (disbursement mechanism, KYC-for-payout) + **CTO** (impl). **Blocked by:** D1. **Blocks:** D4.

### D3 — Fee: single source of truth *(closes B-6)*
**Problem:** Brand fee reads a **hardcoded 15%** (`RazorpayProperties`/`AmountDerivationService`) while creator fee reads DB-backed `PlatformFeeConfig`; `effective_at` is write-only (never consulted) → fees can silently diverge.
**Options:**
- **A · Snapshot-at-fund (recommended):** At escrow fund time, snapshot the effective bps onto the hold; all downstream math reads the snapshot. Immune to later config changes mid-deal.
- **B · Honor `effective_at`:** Every read resolves the row whose `effective_at ≤ now`. Correct but recomputes; risk of mid-deal drift.
**Priya's rec:** **A** + delete the hardcoded 15% path so there is exactly one fee source.
**Owner:** **CFO Rohan** (fee policy) + **CTO** (impl). **Blocked by:** D1.

### D4 — Affiliate settlement disbursement *(closes B-7)*
**Problem:** `AffiliateSettlementJob.doSettleCreator` only flips rows to `SETTLED` — no wallet credit, no RazorpayX; sweep is not period-bounded (`createdAt < periodEnd` missing) → month-boundary mis-batching.
**Priya's rec:** Credit the internal wallet at settlement (reuse D2's payout rail for cash-out), and bound the sweep by `createdAt < periodEnd`. Mostly mechanical **once D2 exists**.
**Owner:** **CFO Rohan** + **CTO**. **Blocked by:** D2.

---

# PART 2 — POST-AGREEMENT PIPELINE (§C) · owner: CTO + Product (Swapnil)

### D5 — Collaboration lifecycle state machine *(closes C-2, C-7)*
**Problem:** Only 4 `transitionTo(` sites exist; the deal dead-ends at `TERMS_AGREED`. `CONTRACT_PENDING/CONTRACTED/IN_PROGRESS/REVIEW_PENDING/REVISION_REQUESTED/COMPLETED` are **never written** → `ReviewService` (requires `COMPLETED`) means **no review can ever be submitted**; `canReject()` lets a **funded, signed** deal be cancelled with no escrow freeze/refund.
**Decision needed:** the **canonical state diagram** — which events drive which transitions, and which are guarded (contract signed, escrow funded).
**Priya's rec:** Define one explicit allowed-transition map + guards; wire execution-phase transitions at contract-activation, deliverable-approval, and completion; block cancel once contract signed / escrow funded (route to dispute/refund instead). This is a **product decision on the deal flow**, then a bounded impl.
**Owner:** **Swapnil/Product** (the flow) + **CTO** (state map + guards). **Blocks:** D6, D7.

### D6 — Approval-gates-payment *(closes C-3)*
**Problem:** `payment_milestones.release_condition` (V52) is dead schema; `BrandDeliverableService.approve` only flips deliverable status; `EscrowService.release` never checks deliverable state → **the core promise (approval releases payment) is unenforced both directions**.
**Priya's rec:** Map the column; trigger `release` from the matching deliverable-approval transition; block manual release until the condition is met. Straightforward **once D5's transitions exist**.
**Owner:** **CTO** (with Product confirming "approval = release" is the intended rule). **Blocked by:** D1, D5.

### D7 — Canonical contract terms + signature integrity *(closes C-4, C-5)*
**Problem:** `contracts.terms` stores only `{tamperHashSha256: sha256(req.toString())}` — not canonical, never verified; usage-rights (V64) + exclusivity dropped (`getUsageRights` has 0 callers). A **brand can record the creator's signature** (`POST /contracts/{id}/sign {"role":"CREATOR"}` drives to ACTIVE without the creator).
**Decision needed:** (a) canonical terms representation (ordered JSON) + verify-before-sign; (b) **policy** on the brand-relay CREATOR signature path — remove entirely vs. keep behind elevated role (the code currently keeps a documented residual risk).
**Priya's rec:** Persist canonical terms JSON, hash *that*, verify before signing, surface in the PDF. **Remove** the brand-relay CREATOR path (a signature integrity hole is not worth the convenience) — but this is a **product/legal call**, so it's a decision, not a default.
**Owner:** **Swapnil/Product + Legal** (signature policy) + **CTO** (canonical terms). **Blocked by:** D5.

---

# PART 3 — MEERA AI (§F) · owner: CTO + Ash (AI)

### D8 — Meera chat reply path *(closes F-5)*
**Problem:** No working reply path. FE `EventSource(?token=)` is GET but Python `/chat` is POST → **405**; fallback `GET …/messages` unmapped → 405; `StreamTokenService.mint()` omits `iss`+`scope` claims Python's verifier requires → **401**; FastAPI has **no CORS**; Spring persists a hardcoded `"placeholder"` reply as a real ASSISTANT row.
**Decision needed:** the **streaming transport contract** — SSE-over-GET with a short-lived query-param ticket, vs. POST + fetch-stream. Pick one and align both ends.
**Priya's rec:** SSE-over-GET with a single-use `iss`+`scope`-bearing stream ticket (browsers can't hold service tokens); add FastAPI CORS; stop persisting placeholder assistant rows. Cross-cutting FE↔BE↔Python contract → needs **one owner** to hold the whole path.
**Owner:** **CTO + Ash (AI)**. **Blocks:** D9.

### D9 — Meera spend governance *(closes F-6)*
**Problem:** `/chat` has **no spend gate and records no spend** (the only routes that call the gate are the dead ones); spend tracker is in-memory (resets on restart, ×N workers).
**Decision needed:** where spend state lives (DB-backed vs Redis) and the **ceiling/kill-switch policy** (per-workspace daily cap, global kill-switch).
**Priya's rec:** Persist spend (DB); gate + `record_spend` in `/chat`; honor `ai_daily_spend_ceiling_usd` + `ai_spend_kill_switch`. **CFO Rohan owns the ceiling numbers.**
**Owner:** **CFO Rohan** (limits) + **CTO/Ash** (impl). **Blocked by:** D8.

### D10 — Analyze-site round-trip *(closes F-8)*
**Problem:** `/analyze-site` is orphaned both directions — no Spring caller, `AnalyzeSiteCallback` referenced nowhere → `BrandProfile.analysisStatus` never leaves `PENDING`; Meera answers with no brand context.
**Priya's rec:** Build the Spring client + async job + callback consumer. Bounded **once D8's service-token/CORS plumbing is settled**. **Owner:** **CTO/Ash.** **Blocked by:** D8.

### D11 — (defer) Sarvam voice *(F-9)*
**Decision:** proxy it through Spring, **or descope** (stop requiring the key at boot; ship browser Web Speech). **Priya's rec:** descope for now — voice is not on the critical revenue path; revisit post-launch. **Owner:** **Swapnil** (product priority).

---

# PART 4 — CROSS-CUTTING (owner: CTO)

### D12 — Access-token storage & socket auth *(E-12)*
**Problem:** access token in `localStorage` (all roles); admin token passed as WebSocket `?token=` query param (logged by proxies/history).
**Priya's rec:** in-memory access token + single-use socket ticket. **Owner:** **CTO.** Security-sensitive; schedule with D8 (shared ticket machinery).

### D13 — Job concurrency / distributed locking *(G-5)*
**Problem:** 12 jobs + the 30s EmailWorker guarded only by per-JVM `AtomicBoolean` → on ≥2 instances: duplicate emails, duplicate metrics/scores, **double Meta spend**.
**Priya's rec:** Add **ShedLock (JDBC)** + atomic outbox claim. Purely technical, no product input. **Owner:** **CTO.** Do this **before** running >1 backend instance.

---

# PART 5 — SWAPNIL (CEO): priority, sequencing & sign-off

**Decision-owner matrix**

| Decision | CEO/Product | CFO Rohan | CTO/Ash |
|---|---|---|---|
| D1 escrow model | — | ✅ sign | ✅ own |
| D2 payout/withdrawal | — | ✅ own | ✅ impl |
| D3 fee source | — | ✅ own | ✅ impl |
| D4 affiliate settle | — | ✅ sign | ✅ impl |
| D5 lifecycle | ✅ own | — | ✅ impl |
| D6 approval-gates-pay | ✅ confirm | — | ✅ own |
| D7 contract/signature | ✅ own (+Legal) | — | ✅ impl |
| D8 Meera reply path | — | — | ✅ own |
| D9 Meera spend | — | ✅ limits | ✅ impl |
| D10 analyze-site | — | — | ✅ own |
| D11 voice | ✅ own | — | — |
| D12 token storage | — | — | ✅ own |
| D13 job locking | — | — | ✅ own |

**Sequencing (CEO view — value & dependency order):**
- **Wave 1 — "money can move" (P0):** D1 → D2 → D3. Nothing about the business works until a brand can fund escrow once and a creator can actually get paid the right amount. Gate: CFO Rohan sign-off on D1–D3 before impl.
- **Wave 2 — "a deal completes" (P0):** D5 → D6 → D7. Product must draw the deal state diagram (D5) and the signature policy (D7) first; impl follows.
- **Wave 3 — "Meera answers" (P1):** D8 → D9 → D10. One owner holds the whole chat path.
- **Wave 4 — hardening (P1, parallelizable):** D4, D12, D13. D13 is a **hard gate before horizontal scaling**.
- **Deferred (P2):** D11 voice.

**Go/no-go gates (CEO):**
1. No production money movement until Wave 1 is `DECIDED` + implemented + CFO-signed.
2. No multi-instance deploy until **D13** ships (double-spend risk).
3. No "Meera is live" claim until D8+D9 close end-to-end.

**What Swapnil signs personally:** D5 (deal flow), D7 (signature policy, with Legal), D11 (voice priority). **Delegated to CFO Rohan:** D1–D4, D9 limits. **Delegated to CTO:** D8, D10, D12, D13.

---

## Decision log (update as calls are made)

| # | Decision | Status | Owner | Decided on | Notes |
|---|---|---|---|---|---|
| D1 | Escrow funding model | DECIDED: gateway-first | Rohan+CTO | 2026-07-14 | owner ratified Priya rec (code-proposal, pending live money-path test) |
| D2 | Payout/withdrawal | OPEN | Rohan+CTO | — | blocked by D1 |
| D3 | Fee source of truth | OPEN | Rohan+CTO | — | Priya rec: snapshot-at-fund |
| D4 | Affiliate settlement | OPEN | Rohan+CTO | — | blocked by D2 |
| D5 | Lifecycle state machine | OPEN | Swapnil+CTO | — | product owns flow |
| D6 | Approval-gates-payment | OPEN | CTO | — | blocked by D1,D5 |
| D7 | Contract terms + signature | DECIDED: remove brand-relay CREATOR | Swapnil+Legal+CTO | 2026-07-14 | owner ratified Priya rec (Legal to confirm) |
| D8 | Meera reply path | OPEN | CTO+Ash | — | SSE-over-GET rec |
| D9 | Meera spend governance | OPEN | Rohan+CTO | — | blocked by D8 |
| D10 | Analyze-site round-trip | OPEN | CTO+Ash | — | blocked by D8 |
| D11 | Sarvam voice | OPEN | Swapnil | — | Priya rec: descope |
| D12 | Token storage/socket auth | OPEN | CTO | — | schedule with D8 |
| D13 | Distributed job locking | OPEN | CTO | — | gate before scale-out |

---

# PART 6 — IMPLEMENTATION STATUS (2026-07-15, branch claude/multi-agent-error-resolution)

Owner ratified D1=gateway-first, D7=remove brand-relay; scope = implement all rec options as code-proposals on the leaner branch. Commit `4b40d6c`. Build compiles, 47/47 tests pass. Money+signature+auth diff passed a Kabir (money/auth) + Kavya (QA) adversarial gate; the core double-entry model survived refutation (balances net to 0, HMAC enforced, no double-pay, fund-account fails closed).

| # | Decision | Implementation |
|---|---|---|
| D1 | Escrow gateway-first | ✅ IMPLEMENTED — single-charge; external gateway-settlement wallet; consistent double-entry |
| D2 | Payout/withdrawal | ✅ IMPLEMENTED (payout) — net-debit, real fund-account (fails closed in prod), V26, reversal + failed re-credit. Creator withdrawal endpoint absent here → N/A |
| D3 | Fee source of truth | ⏸ N/A here — PlatformFeeService/Config absent; fee already single-source (RazorpayProperties). Follow-up when fee subsystem lands |
| D4 | Affiliate settlement | ⏸ N/A here — no AffiliateSettlementJob on this branch |
| D5 | Lifecycle state machine | ✅ IMPLEMENTED (primitives + contract-activation edge); execution-phase services (DealService/ReviewService/DisputeService) absent → their transitions are wired-ready |
| D6 | Approval-gates-payment | ⏸ N/A here — no backend Deliverable subsystem / release_condition. Release gated on strongest existing state |
| D7 | Contract terms + signature | ✅ IMPLEMENTED — brand-relay CREATOR path removed; canonical terms JSON + verified hash (V27) |
| D8 | Meera reply path | ✅ IMPLEMENTED — SSE-over-GET, iss/scope claims, HS256 pinned, CORS, no placeholder row |
| D9 | Meera spend governance | ✅ IMPLEMENTED — gate + tracker (in-memory; shared store needed for true global cap) |
| D10 | Analyze-site round-trip | ✅ IMPLEMENTED — Spring client + async job + callback; analysis_status leaves PENDING |
| D11 | Voice | ✅ DESCOPED — Sarvam key no longer required at boot |
| D12 | Token storage/socket auth | ✅ IMPLEMENTED (in-memory token); admin-WS ticket N/A (no websocket on this branch) |
| D13 | Distributed job locking | ⛔ BLOCKED — ShedLock not in offline cache + premise mismatch (only 1 @Scheduled here). Nothing changed |

## Deferred review findings (must-fix items already applied in `4b40d6c`; these remain open)

| Ref | Severity | Issue | Disposition |
|---|---|---|---|
| Kabir-2 | MEDIUM | Stream ticket not single-use → replay within 60s TTL duplicates a Claude turn (AI spend + duplicate assistant msg) | OPEN — bind writeback idempotency to conversationId+messageId, or track consumed jti in a shared store |
| Kabir-3 | MEDIUM | No fail-closed Spring boot guard on placeholder signing secrets (JWT/stream/internal/webhook) — a prod deploy missing env overrides runs on committed dev keys (token forgery) | OPEN — add a non-dev startup guard. NOT done here: would break local dev boot; needs env wiring. **Required before prod.** |
| Kabir-5 | LOW | `escrow_balance` mirror mutated off the ledger lock, no `@Version` on Wallet → concurrent desync of the *denormalized mirror* (real money unaffected) | OPEN — version/lock the brand wallet or derive from SUM(active holds) |
| Kabir-6 | LOW | Contract "tamper hash" is unkeyed SHA-256 beside the data → no protection vs a DB-write adversary | OPEN — HMAC with a server secret, or drop the "tamper" framing |
| Kabir-7 | FUNCTIONAL | No creator-facing sign endpoint exists (`ContractController.sign` requires a brand workspace) → with the brand-relay path correctly removed, **contracts can no longer reach ACTIVE at all** until a creator sign endpoint is built | OPEN — **key functional follow-up**; add a creator-authenticated signing endpoint so D5's contract-activation transition can fire |

*Companion to `PENDING-WORK-CODE-VERIFIED-GAPS-2026-07-14.md`. Parts 1–5 are a decision framework; Part 6 records the code implementation.*
