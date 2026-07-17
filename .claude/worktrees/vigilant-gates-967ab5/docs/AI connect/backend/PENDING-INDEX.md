# 📌 PENDING-INDEX — Master Index of All Remaining Work (who owns what)

> **Owner:** Priya (CTO) · **Co-signed:** Swapnil (CEO) · **Date:** 2026-07-05
> **Status:** ACTIVE — single source of truth for everything still open. Every pending item below is connected to the doc that specifies it and the **person who owns it**.
> **Governing law:** *Python proposes, Spring disposes, the human commits money.* No pending item may violate it.
> **Sign-off authority:** `21-SWAPNIL-SIGNOFF.md` (CEO approved the whole plan 2026-07-05).

---

## HOW TO READ THIS

- **Owner** = the one person accountable for that item landing. If it's blocked, it's their job to escalate.
- **Gate** = who must green-light it before it's "done."
- Every row points to the **doc** where the full task spec lives — this index is the map, not the territory.
- Pipeline for every feature: **Vikram/Ananya build → Kavya QA → Meera build-verify → Kabir security gate → Priya sign-off → Swapnil final.**

---

## THE PEOPLE (who's who on pending work)

| Person | Role | What they own in the remaining work |
|---|---|---|
| **Vikram** | Backend Dev | ALL remaining backend code — Python AI service (Domain D), Meera tool executors (Phase 4), notifications (Domain B), security hardening code (Domain E), tests, Razorpay SDK swap |
| **Ananya** | Frontend Dev | Meera workspace wire-up — real SSE client, tool-result renderers, commit-tier confirm controls, voice, credit meter, notifications UI |
| **Kabir** | Red-Team / Security | Specifies + GATES every money/AI surface. Launch-blocking. SsrfGuard, service tokens, tool-call validation, the 500/day credit cap, Domain E hardening |
| **Kavya** | QA Lead | Structural QA on every slice before it moves to build-verify |
| **Meera** | DevOps / Build verifier | Provisions Python container, Redis rate-limit store, **live MySQL DB**; runs migrations + build verification |
| **Priya** | CTO | Architecture, this index, the eval-harness directive, sign-off at each gate |
| **Rohan** | CFO | Cost tracking — folds Python-container + Redis + MSG91 email volume into `cost-log.json` |
| **Swapnil** | CEO | Final sign-off; already approved the plan (doc 21) |
| **Tara** | Ops / Reporting | Run reports after each build loop |

---

## PENDING WORK — MASTER TABLE (connected to owner + doc)

| # | Pending item | **Owner** | Gate | Priority | Blocked by | Spec doc |
|---|---|---|---|---|---|---|
| **P0** | Re-pin Gemini model `2.0-flash` → `gemini-2.5-flash-lite` (deprecated model, build-blocking) | **Vikram** | Priya | 🔴 DO FIRST | none | `20`, `21` |
| **P1** | **Domain D — Python AI service** (`influora-ai/`, ~21 files) — the reasoner that makes Meera think | **Vikram** | Kabir (SsrfGuard+tokens) → Priya | 🔴 Highest | none | `16` §D, `04` |
| **P2** | **Phase 4 — Meera tool executors** (~13 files) — wires `MeeraInternalController` 501 stubs to real executors | **Vikram** | **Kabir (launch-blocking money gate)** → Priya | 🔴 High | Phase 1 (done) | `16` §Phase4, `06`, `11` |
| **P3** | **Eval harness** — golden sets, tenant-isolation regression, prompt-injection evals (built ALONGSIDE Domain D, not after) | **Vikram** + Priya | Priya | 🔴 High (CEO-directed) | rides with P1 | `19` §5 (R-1), `21` |
| **P4** | **500 actions/day hard cap** on "unlimited while live" credits (`AICreditService`) | **Vikram** | Kabir red-teams | 🔴 High | rides with credit logic | `20` §5, `21` |
| **P5** | **Domain E — Security hardening** (~12 net-new + 4 MODIFY + 2 migrations: `idempotency_keys`, `audit_log`) | **Vikram** builds / **Kabir** specifies | **Kabir** → Priya | 🔴 Woven into P1/P2 | none | `16` §E, `09`, `17` |
| **P6** | **Domain B — Notifications** (39 files, 22 event records, MSG91 email) | **Vikram** | Kavya (retry/backoff) → Priya | 🟡 Medium | none — parallel | `16` §B, `07` |
| **P7** | **Automated tests** (≥1 per money/AI service; prioritize `RequestPaymentExecutor` amount-tamper + `WalletService`) | **Vikram** writes / **Kavya** reviews | Kavya | 🟡 Medium, rising risk | code exists | `16` §Tests |
| **P8** | **Razorpay SDK swap** (hand-rolled HTTP → `com.razorpay:razorpay-java`) | **Vikram** | **Kabir re-gate** → Priya | 🟡 Medium | Priya-approved dep | `16` §SDK |
| **P9** | **Live MySQL migration execution** (V8–V17 + 2 — never run against a live DB yet) | **Meera** runs / Vikram logs / Kavya verifies | Meera | 🔴 Blocks real verification | Meera provisions DB | `16` §LiveDB |
| **P10** | **Frontend: real SSE client** (direct Python `/chat`, stream-token, event protocol, reconnect, cancel) | **Ananya** | Kavya → Meera | 🔴 High | mock-SSE now; real needs P1+P2 | `18` §SSE, `04` |
| **P11** | **Frontend: commit-tier confirm controls** (Fund escrow / confirm_launch / envelope / e-signature — human click on PUBLIC endpoint, never via Meera) | **Ananya** | Kabir (security-UX) → Kavya | 🔴 High | needs P2 for real | `18` §Commit, `06` |
| **P12** | **Frontend: resolve doc 02 ↔ 11 endpoint-path discrepancy** (with Vikram) BEFORE real wiring | **Ananya** + Vikram | Priya | 🔴 Do before P10/P11 wire | none | `18` §Discrepancy |
| **P13** | **Frontend: tool-result renderers, voice UI, credit meter, notifications UI** | **Ananya** | Kavya → Meera | 🟡 Medium | mock now; real needs backend | `18` §4/7/8/9 |
| **P14** | **Kabir Phase A gate** — SsrfGuard + service-token + no-PII redaction BEFORE Domain D touches the internet | **Kabir** | Kabir → Priya | 🔴 Blocks P1 go-live | needs P1 code | `17` §Phase A |
| **P15** | **Kabir Phase B gate** — the launch-blocking money gate (RT-G1..G6, MF-1..4, LB-1..9, Rulings A/B/C) | **Kabir** | Kabir → Priya → Swapnil | 🔴 Launch-blocking | needs P2 code | `17` §Phase B |
| **P16** | **Kabir Phase C gate** — Domain E hardening + live JWT `[LIVE-GAP]` + distributed rate limiter | **Kabir** | Kabir → Priya | 🔴 Per-phase | needs P5 code | `17` §Phase C |
| **P17** | **Infra cost lines** — track Python container + Redis rate-limit store + MSG91 email volume in `cost-log.json` | **Rohan** | Rohan → Swapnil (if new tier) | 🟢 Low | Meera provisions | `20` §4 |
| **P18** | **Cap TTS spoken-reply length (~200 chars)** — voice margin control | **Vikram** | Rohan/Priya | 🟡 With voice (P1 §voice) | rides with voice | `20` §3 |

---

## THE ONE MARKED PERSON — WHO CARRIES THE MOST

> **⭐ MARKED: VIKRAM (Backend Developer)** — he owns **13 of the 18 pending items** (P0, P1, P2, P3, P4, P5, P6, P7, P8, P18, plus the build side of P9/P12). He is the critical-path bottleneck for the entire remaining backend and for making Meera actually think. **If Vikram is blocked, the product is blocked.** Everyone else's pending work (Ananya's wiring, Kabir's gates, Meera's live DB) either feeds into or waits on Vikram's output.

**Priya's ruling on this concentration:** it's real and it's a risk. Vikram cannot parallelize himself. The mitigation is strict sequencing (below) — do the two things that unblock Meera first (P1 + P2), let everything with no blocker (P6 notifications, P7 tests, P9 live DB) ride alongside owned by others where possible, and do NOT let notifications or the SDK swap jump the queue ahead of the reasoner. Swapnil's priority order (doc 21) reflects this.

---

## DEPENDENCY GRAPH (what unblocks what)

```
P0 (Gemini re-pin, 5 min)
   └─► P1 (Python AI service) ──┐
   		 │  ├─ P3 (eval harness, alongside)      ├─► P14 (Kabir Phase A gate) ─► Domain D live
   		 │  └─ P18 (TTS cap, with voice)         │
   		 ▼                                       │
   P2 (Phase 4 executors) ◄── needs Phase1 (DONE)─┘
   		 │  ├─ P4 (500/day credit cap)
   		 ▼
   P15 (Kabir Phase B — LAUNCH-BLOCKING MONEY GATE) ─► Priya ─► Swapnil ─► real-money launch

   P5 (Domain E hardening) ──► P16 (Kabir Phase C gate)     [woven through P1/P2]
   P6 (Notifications) ────────────────────────────► Kavya    [NO blocker, parallel]
   P7 (Tests) ────────────────────────────────────► Kavya    [alongside P1–P6]
   P8 (Razorpay SDK swap) ──► Kabir re-gate                  [alongside P2]
   P9 (Live MySQL) — Meera ──► validates P1–P6 schema        [Meera provisions, ASAP]

   FRONTEND (Ananya, mock-first now):
   P12 (resolve 02↔11) ─► P10 (real SSE) ─► P11 (commit controls) ─► P13 (renderers/voice/meter/notifs)
        needs P1+P2 backend to go from mock → real
```

**The critical path to "Meera actually works":** P0 → P1 ∥ P2 → P14 + P15 (Kabir gates) → Priya → Swapnil. Everything else rides alongside.

---

## SWAPNIL-APPROVED PRIORITY ORDER (from doc 21)

1. **P0** — Gemini re-pin (do first, 5 minutes)
2. **P1 ∥ P2** — Domain D + Phase 4 in parallel (the whole point of M2.5)
3. **P3** — Eval harness stood up alongside Domain D (CEO-directed, not deferred)
4. **P12** — Ananya resolves the 02/11 endpoint discrepancy with Vikram, then continues mock-SSE build
5. **P14** — Kabir Phase A gate (SsrfGuard + tokens) before Domain D touches the internet
6. **Everything else** (P5, P6, P7, P8, P9, P4, P18) — parallel, no hard blockers, pick up as bandwidth allows
7. **P15 / P16** — Kabir launch gates before any real-money traffic

---

## THE GATE THAT CANNOT BE SOFTENED

> **No money/commit-tier file ships without Kabir's green re-test** (RT-G1..G6 + MF-1..MF-4 + LB-1..LB-9). Applies to P2, P4, P5, P8, P11. Any red = launch blocker, not a follow-up ticket. If schedule pressure tempts anyone to soften this gate, it goes to Swapnil, not to a workaround (doc 21, CEO ruling).

---

## DOC MAP (where each pending item is fully specified)

| Doc | What it holds |
|---|---|
| `14-REMAINING-TASKS.md` | Original remaining-scope brief (Vikram) |
| `16-VIKRAM-REMAINING-TASKS.md` | Full backend task packet — P0–P9, P18 |
| `17-KABIR-REMAINING-TASKS.md` | Full security gate packet — P14, P15, P16 |
| `18-ANANYA-REMAINING-TASKS.md` | Full frontend packet — P10, P11, P12, P13 |
| `19-AI-ARCHITECT-REVIEW.md` | AI-enablement review — source of P3 (eval harness) |
| `20-ROHAN-COST-REVIEW.md` | Cost cross-check — source of P0, P4, P17, P18 |
| `21-SWAPNIL-SIGNOFF.md` | CEO sign-off + priority order |
| **`PENDING-INDEX.md`** | **This file — the map connecting all of it** |

---

**Priya (CTO):** This index is the single place to see everything open and who owns it. Vikram is the marked critical-path owner. Build order is fixed by Swapnil's ruling. I sign off on this as the coordination map for the remaining build.
**Swapnil (CEO):** Approved. This is what I want on one page. Build it.
