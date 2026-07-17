# 🚫 Influora — NOT-Required / Deferred Work Register

> **Date:** 2026-07-12 · **Owner:** Priya (CTO) · **Basis:** code-verified (brand/creator/admin FE↔BE alignment audits, this session)
> **Purpose:** the counterpart to `INDEX.md`. Everything here is **intentionally NOT to be built now** — either a locked design decision (never build), a later-phase deferral (not now), or dead code (safe to leave/remove). **Agents: do NOT pick these up as tasks.** If a row's trigger fires, it moves onto `INDEX.md` with a real owner.
> Companion files: work to DO → `INDEX.md`; status → `PRIYA-CTO-CONSOLIDATED-REPORT-2026-07-12.md`.

---

## A. 🔒 BY DESIGN — never build (locked rulings)
Do not "fix" these — they are deliberate. Parked with the decision owner.

| Item | Why it is NOT required | Decision owner |
|---|---|---|
| Python `campaign_type` enum **excludes `STANDARD`** (`schemas.py:100-109`) | STANDARD is a server-side default; exposing it would let the AI propose it. Drift is intentional; CI schema-check is report-only for this reason. | **Priya** (locked) |
| Meera `sendTurn` writes a **placeholder echo**, does not call Python from Spring (`MeeraSessionService.java:137-142`) | Architecture is browser-direct SSE (browser → Python `/chat` → Spring writeback). Spring is not meant to initiate the AI call. P1-5 accepted this. | **Priya / Arjun** (accepted) |
| No cross-instance shared spend ledger; no Slack/email paging; no hard per-workspace AI block (`app/costs/*`) | Phase-1 per-process counter is the agreed scope (P2-17 spec §4). | **Rohan / Priya** |

---

## B. ⏸️ DEFERRED — later phase, not now
Frontend client (`src/admin/services/api-contracts.ts`) declares these, but **no backend controller exists by design** (Phase 2). They are FE shells, not broken wiring. Resume only on a Swapnil/Priya priority call.

| Module / item | State | Parked-with (owner when resumed) |
|---|---|---|
| **Admin Finance** — revenue, escrow summary, payout queue, retry payout, reconciliation + resolve, TDS report (`api-contracts.ts:293-323`) | FE declared, no controller | **Vikram** (BE) + **Ananya** (FE already built) · trigger: Priya/Swapnil Phase-2 go |
| **Admin Escrow ops** — flagged / release / hold / refund (`api-contracts.ts:409-431`) | FE declared, no controller | **Vikram** + **Kabir** (money path) |
| **Admin Email** — queue, retry, templates, bulk send, stats (`api-contracts.ts:581-603`) | FE declared, no controller | **Vikram** / **Dev** |
| **Admin Error-log** — recent, by-id, resolve, stats (`api-contracts.ts:559-568`) | FE declared, no controller | **Vikram** / **Meera** (observability) |
| **Admin Marketing** — acquisition, growth, reputation, referrals (`api-contracts.ts:617-628`) | FE declared, no controller; formulas pending | **Vikram** + **Rohan/Tejas** (metric formulas) |
| **Admin dashboard** financial + marketing summaries (`api-contracts.ts:125,137`) | FE declared, no controller | **Vikram** · depends on Finance/Marketing above |
| **Admin list/secondary endpoints** — list brands/creators/campaigns, campaign detail/at-risk/hype-ops, override budget, adjust tier, pending applications, escalate ticket, support stats, moderation approvals/suspensions/appeals (`api-contracts.ts` various) | FE declared, no controller | **Vikram** + **Ananya** · trigger: when that admin screen is scheduled |
| Store-integration **click→order UTM attribution** (Shopify/Woo webhooks) | Documented scope cut | **Vikram** |
| Creator **KYC full verification** (`submitCreatorKyc` — placeholder, deferred to first withdrawal) | Placeholder by spec | **Vikram** · trigger: withdrawal flow build |
| Monthly **$300 AI informational cap** — manual tracking, not code-enforced (P2-17) | Documented per-process limitation | **Rohan** (manual) |

**Scale note:** the admin frontend declares ~70 API methods; **28 are backed and wired (live), 42 are the deferred FE-only shells above.** That is why admin reads "100% aligned for what's wired, 40% built" — the 42 here are *not* a gap to close now.

---

## C. 🧹 DEAD CODE — safe to leave, remove when touched
Not required to complete; harmless today. Whoever next edits the area should delete or wire.

| Item | Where | Note | Owner (opportunistic) |
|---|---|---|---|
| `saveCreatorPayout` → `POST /onboarding/creator/payout` | `src/lib/api.ts:444` | FE call, no backend route; never invoked in current flow (comment: "deferred to first withdrawal"). Dead, not a live break. | **Ananya** (remove) / **Vikram** (build when payout-onboarding step is scheduled) |
| Admin FE-ONLY method stubs (the 42 in §B) | `src/admin/services/api-contracts.ts` | Callable client methods with no backend — leave as the FE contract for Phase 2; do not delete. | — |

---

## D. ⚠️ NOT "not-required" — these ARE required (do not park here)
Recorded so nobody mistakes them for deferrable. These stay on `INDEX.md` / open:

- **P2-12 payout FAILED-key reclaim availability bug** (Kabir advisory) — real money-path fix or explicit ops-runbook acceptance. **Required.**
- **Brand escrow/payout contract drift** (FE `/deals/:id/escrow/fund` vs BE `/wallet/escrow/fund`) — money path. **Required.**
- **Creator deliverable-submit path drift** (FE `/deliverables/:id/submit` vs BE `/creator/deliverables/:id/submit`) — 404 risk on a core flow. **Required (confirm-then-fix).**
- **Live pre-prod verification** for P1-5 + P2-14 (never run in sandbox). **Required before ship.**
- **20 baseline backend test failures** (890/11F/9E) — decision needed: green them or formally accept. **Required call.**

---
*Register by Priya (CTO), 2026-07-12. A row leaves this file only two ways: its trigger fires (→ moves to `INDEX.md` with an owner) or it is deleted as done. Do not start any §A/§B/§C item without a Priya/Swapnil priority decision.*
