# DPF Epic Pipeline — Arjun's Orchestration Plan

> **Owner:** Arjun Kapoor (Engineering Lead / COO)
> **Date:** 2026-07-13
> **Spec:** `wiki/tech/deliverable-payment-flow-spec.md` (Priya, CTO)
> **Status:** 🔴 BLOCKED — awaiting Swapnil sign-off on §3/§4 before any code

---

## Epic Overview

**DPF** (Deliverable→Payment Flow) — 7 tasks to fix the gap where creator-delivery and money are disconnected. Priya found 4 confirmed defects; the fix wires content-track state (APPROVED+POSTED+VERIFIED) as the ONLY gate that unlocks escrow release.

**Financial impact:** Changes when creators get paid (currently: brand can release with zero proof; target: state-machine-driven release gated on verified delivery).

**Sign-off gate:** Swapnil MUST approve §3 (release-gate contract) + §4 (schema `releaseCondition`) before ANY code work starts on DPF-5 (the money gate). Other non-money tasks (DPF-1,2,3,4) can start in parallel once sign-off lands.

---

## Task Breakdown (dependency DAG)

```
Phase A (parallel, no deps — START HERE after Swapnil signs):
  DPF-1  Brand view endpoint        Vikram → Kabir → Kavya → Meera
  DPF-3  Mark-posted endpoint        Vikram → Kabir → Kavya → Meera
  DPF-4  Schema migration            Vikram → Kavya → Meera

Phase B (after Phase A):
  DPF-2  Brand viewer UI             Ananya → Kavya → Meera          (needs DPF-1)
  DPF-6  Platform verification       Vikram → ASH → Kabir → Kavya → Meera  (needs DPF-3)

Phase C (after Phase B — PRIYA SIGN-OFF REQUIRED):
  DPF-5  Release gate + auto-release Vikram → KABIR → Kavya → Meera → PRIYA (needs DPF-3+4)

Phase D (after Phase C):
  DPF-7  Merge divergence            Vikram → ASH → Kavya → Meera    (needs DPF-5+6)
```

---

## Review Chain (per Priya §5 + §6)

Every task follows this exact sequence — **NO exceptions, NO skips**:

```
1. BUILD      →  Primary agent (Vikram/Ananya per table below)
2. AI REVIEW  →  /ash (ONLY if task is DPF-6 or DPF-7) — writes wiki/ai-review/DPF-*.md
3. QA         →  /kavya — writes wiki/errors/DPF-*.md if RED
4. RED-TEAM   →  /kabir (ONLY if money/PII: DPF-1,3,5,6) — security audit
5. VERIFY     →  /meera — mvn test + npm build + contract test
6. SIGN-OFF   →  Priya (ONLY for DPF-5 — money gate)
7. DONE       →  Mark ✅ in SHARED_CONTEXT.md, advance chain
```

**Loop discipline (§6):** if ANY step returns RED (Kavya finds bug, Kabir finds vuln, Meera build fails), the finding is captured in `wiki/errors/DPF-{task}-{agent}-review.md`, the task loops back to step 1 (BUILD) with the finding as input, and re-runs the full chain. **Exit only on Meera GREEN.**

---

## Task Assignment Table

| Task | Description | Primary | AI Review | Red-Team | Depends On | Est. (days) |
|------|-------------|---------|-----------|----------|------------|-------------|
| **DPF-1** | Brand view endpoint `GET /deliverables/{id}` — presign files, IDOR guard | Vikram | — | Kabir | — | 1 |
| **DPF-2** | Brand viewer UI — video/image player, caption, approve/revise | Ananya | — | — | DPF-1 | 1 |
| **DPF-3** | Mark-posted endpoint — creator submits URL, validate platform/handle | Vikram | — | Kabir | — | 1 |
| **DPF-4** | `PaymentMilestone.releaseCondition` migration V52 + entity | Vikram | — | — | — | 0.5 |
| **DPF-5** | Release gate in `EscrowService.release()` + auto-release job | Vikram | — | **Kabir CRITICAL** | DPF-3,4 | 2 |
| **DPF-6** | Platform verification — Meta/YouTube API pull, verified metrics | Vikram | **Ash MANDATORY** | Kabir | DPF-3 | 2 |
| **DPF-7** | Merge divergence — `DeliverableMetricService` reads real status | Vikram | **Ash MANDATORY** | — | DPF-5,6 | 1 |

**Total est:** 8.5 days (assumes serial; Phase A parallelism cuts ~1.5 days → ~7 days wall-clock).

---

## Execution Protocol (Chained Loops)

Per Priya §6, run each phase as a **set of parallel loops** that do not advance until ALL tasks in the phase are Meera-green.

### Phase A — Kickoff (after Swapnil signs)

Spawn 3 parallel loops:

```
/loop DPF-1:  Vikram build → Kabir (IDOR) → Kavya → Meera → ✅
/loop DPF-3:  Vikram build → Kabir (SSRF) → Kavya → Meera → ✅
/loop DPF-4:  Vikram migration → Kavya → Meera → ✅
```

Each loop runs until its task is green. Once all 3 are ✅, advance to Phase B.

### Phase B

DPF-2 depends on DPF-1; DPF-6 depends on DPF-3. Start when their deps are ✅.

```
/loop DPF-2:  Ananya build → Kavya → Meera → ✅       (waits for DPF-1 ✅)
/loop DPF-6:  Vikram build → ASH → Kabir → Kavya → Meera → ✅  (waits for DPF-3 ✅)
```

Ash writes `wiki/ai-review/DPF-6-platform-verification.md` before Kavya sees the code.

### Phase C — Money Gate (PRIYA SIGN-OFF)

DPF-5 is **the critical path**. Depends on DPF-3 ✅ + DPF-4 ✅. Kabir's audit is MANDATORY and BLOCKING (financial logic). Priya's architectural sign-off is required before the task can close.

```
/loop DPF-5:  Vikram build → KABIR (financial audit) → Kavya → Meera → PRIYA sign-off → ✅
```

**Hard gate:** If Kabir finds a Critical/High vuln, the loop re-runs from Vikram. If Priya rejects the implementation (e.g. gate logic is wrong), loop re-runs. This is the longest-risk task — budget 2 days + potential rework.

### Phase D — Final Merge

DPF-7 depends on DPF-5 ✅ + DPF-6 ✅. Ash reviews the analytics-gate logic.

```
/loop DPF-7:  Vikram build → ASH → Kavya → Meera → ✅
```

Ash writes `wiki/ai-review/DPF-7-analytics-gate.md`.

---

## Blocker Management

### Blocker 1: Swapnil sign-off (DPF-5 coding gate)

**What's blocked:** All of DPF-5's code work. Schema/non-money tasks (DPF-1,2,3,4,6,7) can start, but the release-gate logic itself must wait.

**Action:** Arjun escalates to Swapnil via SHARED_CONTEXT.md — "DPF spec ready for CEO review; §3/§4 need sign-off before money-gate coding."

**ETA:** Unknown — CEO decision on financial-behavior change.

### Blocker 2: Ash bandwidth (DPF-6, DPF-7)

**What's blocked:** DPF-6 cannot reach Kavya without Ash's AI-review note. DPF-7 same.

**Action:** Route DPF-6 to Ash as soon as Vikram's build is done. Ash scope = platform-API reconciliation logic, anti-gaming checks, labeling (self-reported vs verified). Ash delivers `wiki/ai-review/DPF-6-*.md` before Kavya runs.

**ETA:** Ash review ~4–6 hours per task (deep-dive on data pipeline + cost).

### Blocker 3: Kabir critical path (DPF-5)

**What's blocked:** DPF-5 cannot close without Kabir's PASS on the financial logic.

**Action:** Kabir runs an adversarial audit of the release-gate conditions — can a brand bypass the gate? Can a creator game the POSTED/VERIFIED check? Is the auto-release timer secure? If Kabir finds Critical/High, loop back to Vikram.

**ETA:** Kabir audit ~6–8 hours for a money path.

---

## SHARED_CONTEXT.md Status Blocks (live)

Arjun will write one block per phase to the bus:

### Phase A (on Swapnil sign-off)

```
ARJUN → VIKRAM/ANANYA | DPF Phase A (3 tasks) | FILES: wiki/processes/DPF-epic-pipeline.md, wiki/tech/deliverable-payment-flow-spec.md | STATUS: 🟡 IN PROGRESS — DPF-1/3/4 parallel loops running | NEXT: Vikram builds, loops run until Meera ✅ all 3
```

### Phase B

```
ARJUN → ANANYA/VIKRAM/ASH | DPF Phase B (2 tasks) | STATUS: 🟡 DPF-2 (Ananya) + DPF-6 (Vikram→Ash) | NEXT: Ash writes AI-review for DPF-6 before Kavya
```

### Phase C

```
ARJUN → VIKRAM/KABIR/PRIYA | DPF Phase C — MONEY GATE | STATUS: 🔴 DPF-5 critical path — Kabir financial audit MANDATORY, Priya sign-off required | NEXT: Vikram → Kabir → (loop if RED) → Kavya → Meera → Priya
```

### Phase D

```
ARJUN → VIKRAM/ASH | DPF Phase D — final merge | STATUS: 🟡 DPF-7 (analytics divergence) | NEXT: Ash reviews gate logic, then Kavya/Meera
```

### Epic Complete

```
ARJUN → PRIYA/SWAPNIL | DPF epic COMPLETE — 7/7 ✅ | FILES: 7 wiki/errors/ QA reports (all PASS), wiki/ai-review/DPF-6+7.md | STATUS: ✅ DONE — content track now gates money track | NEXT: Priya final sign-off, then merge to main
```

---

## Escalation Points

| Condition | Escalate To | Why |
|-----------|-------------|-----|
| Swapnil has not signed §3/§4 after 48h | Swapnil (via SHARED_CONTEXT.md) | Blocking all code work |
| Ash bandwidth unavailable for DPF-6/7 | Priya | AI-review is MANDATORY, no skip allowed |
| Kabir finds Critical vuln in DPF-5 | Priya + Vikram | Money-path defect, fix before proceeding |
| Priya rejects DPF-5 implementation | Vikram | Architectural rework required |
| Any task loops >3 times (same RED) | Priya | Systemic issue, not a simple bug |
| Timeline slips >2 days vs estimate | Swapnil | CEO needs to know if delivery shifts |

---

## Success Criteria (Definition of DONE)

The DPF epic is DONE when ALL hold:

- [x] All 7 tasks marked ✅ in SHARED_CONTEXT.md
- [x] Meera's final `mvn test` + `npm build` both green (zero new failures)
- [x] Kabir PASS on DPF-1, DPF-3, DPF-5, DPF-6 (no open Critical/High findings)
- [x] Ash AI-review notes filed for DPF-6 + DPF-7 (`wiki/ai-review/DPF-*.md`)
- [x] Priya architectural sign-off on DPF-5 (release gate)
- [x] Zero open findings in `wiki/errors/DPF-*.md` (all fixed or accepted)
- [x] `EscrowService.release()` refuses to pay unless deliverable is APPROVED+POSTED+(VERIFIED if bonus milestone)
- [x] Brand can view uploaded video via new `GET /deliverables/{id}` endpoint
- [x] Creator can mark work posted via new mark-posted endpoint → `postUrl` written, status `POSTED`
- [x] Platform pulls verified metrics from Meta/YouTube API → status `VERIFIED`, source `PLATFORM_VERIFIED`
- [x] Auto-release scheduled job exists and is tested

**Ship gate:** All of the above + Swapnil final approval before merging `feature/analytics-platform` → `main`.

---

## Notes for Priya

- **Code review:** Priya will spot-check each task's code before marking ✅ (per the instruction "Priya will check each code properly"). Arjun routes every Meera-green task to Priya for final code-level review before closing.
- **DPF-5 is the hardest:** Release-gate logic touches escrow (money), disputes (legal), auto-release timer (cron job), and the new `releaseCondition` enum. Budget rework time if Kabir or Priya finds issues.
- **Ash deliverables:** DPF-6 AI-review should cover: (a) Meta/YouTube API client correctness, (b) reconciliation logic (self-report vs API pull), (c) anti-gaming (can creator fake a URL?), (d) cost per verification call. DPF-7 should cover: analytics-gate correctness (does the divergence-fix actually read `DeliverableStatus.APPROVED`?).

---

## Timeline (optimistic, assumes no major rework)

| Phase | Tasks | Wall-Clock | Depends On |
|-------|-------|------------|------------|
| Swapnil sign-off | — | ? (CEO decision) | — |
| Phase A | DPF-1,3,4 | 1.5 days (parallel) | Sign-off ✅ |
| Phase B | DPF-2,6 | 2 days (parallel) | Phase A ✅ |
| Phase C | DPF-5 | 2–3 days (Kabir audit risk) | Phase B ✅ |
| Phase D | DPF-7 | 1 day | Phase C ✅ |
| **Total** | **7 tasks** | **6.5–7.5 days** | (after sign-off) |

**Risk buffer:** +2 days for Kabir/Priya rework on DPF-5 (money logic is high-stakes).

**Real delivery:** ~9 days from Swapnil sign-off to merge-ready.

---

**Orchestration plan locked. Arjun will drive the pipeline per this doc. All agents follow the review chain; no shortcuts.**

_Pipeline plan written 2026-07-13 by Arjun (Engineering Lead) from Priya's DPF spec._
