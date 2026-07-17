# DPF — Deliverable → Payment Flow (Target Architecture + Work Assignment)

> **Owner:** Priya Sharma (CTO) · **Authority:** architecture LOCKED after sign-off
> **Date:** 2026-07-13
> **Status:** 🟡 SPEC — awaiting Swapnil sign-off before code
> **Branch:** `feature/analytics-platform`
> **Epic ID:** `DPF` (tasks `DPF-1` … `DPF-7`)

---

## 0. Why this exists

A CTO trace of the live code found the creator-delivery flow and the money flow are **two
disconnected subsystems**. Four confirmed defects, each compounding the next:

| # | Defect | Evidence (file:line) |
|---|--------|----------------------|
| **#1** | Brand cannot view the creator's uploaded video/image — no brand endpoint returns the files. `toListItem` omits files; `BrandDeliverableController` only exposes approve/revise. | `CreatorDeliverableService.java:686`, `BrandDeliverableController.java`, `DealService.java:334` |
| **#2a** | `Deliverable.postUrl` is **dead** — no code path ever writes it. `POSTED` status is never assigned. | `Deliverable.java:80` (no writer anywhere in backend) |
| **#2b** | Analytics are 100% self-typed by the creator. The captured link (`metric.link`) is optional, unvalidated, and **nothing fetches from it**. Source is always `SOURCE_CREATOR_REPORTED`. | `DeliverableMetricService.java:28`, `:134`, `:199` |
| **#3** | `EscrowService.release()` checks **none** of: deliverable uploaded, brand-approved, posted, URL, or metrics. A brand can release full payment with zero proof of delivery. | `EscrowService.java:274` |

**Root cause:** `DeliverableMetricService` uses milestone `FUNDED` as a *proxy* for "approved"
(`DeliverableMetricService.java:34`) even though a real `Deliverable`/`APPROVED` workflow already
exists. The two must merge.

---

## 1. The governing principle (LOCKED)

> **The content track gates the money track.** A deliverable reaching
> `APPROVED + POSTED + VERIFIED` is the ONLY thing that unlocks escrow release. Analytics stop
> being a decorative after-the-fact report and become the **verification signal that releases
> payment.**

---

## 2. Target flow (8 stages)

```
1 · Deal agreed              → milestone + deliverable slot        [SYSTEM]
2 · Brand funds escrow       → FUNDED (money parked, work not paid)[BRAND · money]
3 · Creator uploads + submits→ SUBMITTED (files on R2)             [CREATOR]
4 · Brand VIEWS + approves   → APPROVED  (+ fires event)          [BRAND]      ← fixes #1
     └─ revision loop (max 2) → REVISION_REQUESTED
5 · Creator posts live + URL → POSTED (postUrl written+validated)  [CREATOR]   ← fixes #2a
6 · Platform verifies post   → VERIFIED (Meta/YouTube API pull)    [SYSTEM]    ← fixes #2b
7 · 🔒 RELEASE GATE          → requires APPROVED+POSTED+VERIFIED,
                                 no active dispute, auto-release timer
                               → escrow RELEASED, wallet credited  [money]     ← fixes #3
8 · Payout → RazorpayX        → PROCESSED (bank/UPI paid)          [money]
```

Full diagram: rendered in the CTO design session (Influora target deliverable-payment flow).

---

## 3. Release-gate contract (LOCKED)

`EscrowService.release()` MUST refuse unless ALL hold:

1. Linked `Deliverable.status ∈ { APPROVED, POSTED, VERIFIED }` per the milestone's
   `releaseCondition` (see §4).
2. No active dispute on the collaboration (already enforced — keep).
3. Escrow hold is `FUNDED` (already enforced — keep).

Release is **state-machine-driven**, not a free brand click. The manual "pay now" button and any
API caller route through the **same guarded path** — the gate lives inside `release()`, so nothing
can bypass it.

**Creator-protection auto-release:** once `APPROVED + POSTED`, a review window opens (default
**7 days**). If the brand neither disputes nor releases, auto-release fires. Runs on the existing
scheduled-job harness (`ScoreCalculationJob` pattern). Prevents a brand from taking a live post and
never paying.

---

## 4. Schema change (LOCKED — data-driven gate)

Add to `PaymentMilestone`:

```
releaseCondition ENUM('ON_APPROVAL','ON_POSTED','ON_VERIFIED_METRICS')  NOT NULL  DEFAULT 'ON_POSTED'
```

- **Fixed-fee deliverable** (common): `ON_POSTED` — creator can't control reach; do not withhold
  fixed fees on performance.
- **Performance/bonus milestone**: `ON_VERIFIED_METRICS` — step 6's verified numbers become the
  payment condition.

Gate reads this field — never hardcode the condition.

---

## 5. WORK ASSIGNMENT

> Task routing & sequencing is owned by **`/arjun`** (Engineering Lead / pipeline orchestrator).
> All AI-touching work is reviewed by **`/ash`** (AI/ML expert & AI code reviewer) BEFORE Kavya QA.
> All code → `/kavya` (QA) → `/kabir` (red-team, money/PII paths) → `/meera` (local build+verify).

| Task | Description | Build (primary) | Help / Review | Depends on |
|------|-------------|-----------------|---------------|------------|
| **DPF-1** | Brand deliverable **view** endpoint `GET /deliverables/{id}` — presign files via `resolveDownloadUrl`/`toFileResponse`, workspace→collaboration trust boundary | `/vikram` | `/kabir` (IDOR/cross-tenant), `/kavya` | — |
| **DPF-2** | Deal-room brand **viewer UI** (video/image player, caption, approve/revise actions) | `/ananya` | `/kavya` | DPF-1 |
| **DPF-3** | **Mark-posted** endpoint — creator submits live URL → validate platform/handle → write `postUrl`, set `POSTED` | `/vikram` | `/kabir` (URL validation/SSRF), `/kavya` | — |
| **DPF-4** | `PaymentMilestone.releaseCondition` migration + entity + repo | `/vikram` → `/meera` (migration) | `/kavya` | — |
| **DPF-5** | **Release gate** in `EscrowService.release()` + `PayoutService` path + auto-release scheduled job | `/vikram` | **`/kabir` MANDATORY** (financial), `/kavya`, Priya sign-off | DPF-3, DPF-4 |
| **DPF-6** | **Platform verification** — pull metrics from `postUrl` via Meta/YouTube Graph clients; status `VERIFIED`, source `PLATFORM_VERIFIED`; self-report becomes labeled fallback | `/vikram` (integration) | **`/ash` MANDATORY** (AI/data pipeline review), `/kabir` (token/PII), `/kavya` | DPF-3 |
| **DPF-7** | Merge divergence — `DeliverableMetricService` reads real `DeliverableStatus`, not milestone-`FUNDED` proxy | `/vikram` | `/ash` (analytics logic), `/kavya` | DPF-5, DPF-6 |
| **DPF-8** | R2 lifecycle job — delete **superseded revisions + abandoned drafts ONLY** (NOT approved content). Dispute/escrow guard before any delete. See `wiki/tech/deliverable-retention-policy.md` | `/vikram` | **`/kabir` MANDATORY** (deletion-guard: can it ever delete evidence?), `/kavya` | — (independent) |

> **DPF-8 scope guard:** Approved-deliverable deletion is EXPLICITLY OUT of scope until Swapnil + legal/CA sign the compliance retention number (`deliverable-retention-policy.md` §5). DPF-8 touches only superseded/abandoned media — no paid/approved evidence.

### 5.1 AI parts → route to `/ash`
The following are **AI/data-pipeline** work and MUST get `/ash` review before QA:
- **DPF-6** — verified analytics ingestion (platform API reconciliation vs. self-reported;
  confidence/labeling of the source; anti-gaming on creator-supplied numbers).
- **DPF-7** — analytics gate logic correctness.
- Any future auto-verification heuristic (e.g. matching post content to the approved draft).

`/ash` deliverable: AI-review note in `wiki/ai-review/DPF-analytics-verification.md` — model/data
choice, cost, integration-with-current-code check, and a concrete improvement plan.

### 5.2 Employee task assignment → route to `/arjun`
`/arjun` owns the human/agent task pipeline for this epic:
- Break `DPF-1…7` into subtasks, assign to the agents in the table, track status in
  `SHARED_CONTEXT.md`, and report completion to Priya → Swapnil.
- Enforce the review chain: **build → (`/ash` if AI) → `/kavya` → `/kabir` (money/PII) →
  `/meera` verify**. No task is DONE until Meera's build+verify is green.
- Escalate to Priya on any architectural ambiguity; escalate to Swapnil on cost/scope.

---

## 6. Chained-loop execution protocol (use the `loop` skill)

Run the epic as a **chain of loops** — each task is a loop that does not exit until it is green,
and the chain advances only when dependencies are satisfied.

```
CHAIN (respect the dependency DAG in §5):

  Phase A (parallel, no deps):  DPF-1 ▸ DPF-3 ▸ DPF-4
  Phase B (after A):            DPF-2 (needs DPF-1) ▸ DPF-6 (needs DPF-3, /ash-gated)
  Phase C (after B):            DPF-5 (needs DPF-3+DPF-4, /kabir-gated, Priya sign-off)
  Phase D (after C):            DPF-7 (needs DPF-5+DPF-6)

PER-TASK LOOP (the inner loop each task runs until green):
  /loop  →  /arjun route  →  build agent  →  [/ash if AI]  →  /kavya QA
         →  /kabir (if money/PII)  →  /meera build+verify
         →  if RED: capture finding in wiki/errors/, loop again
         →  if GREEN: mark DONE in SHARED_CONTEXT.md, advance chain
```

- Use `/loop` to drive each task's build→review→verify cycle without manual re-prompting.
- The loop **exits only on Meera-green**; a red QA/red-team/verify result feeds back into the same
  loop with the finding doc as input.
- Chain advances phase-by-phase; Phase C (`DPF-5`, the money gate) requires explicit Priya sign-off
  inside the loop before it can close.

---

## 7. SHARED_CONTEXT handoff block

```
FROM Priya → TO Arjun
TASK  DPF epic — deliverable→payment gate (7 tasks, see spec)
FILES wiki/tech/deliverable-payment-flow-spec.md
STATUS SPEC ready · awaiting Swapnil sign-off on §3/§4 (financial gate + schema)
NEXT  Arjun: on sign-off, break DPF-1..7, run chained loops (§6), report green to Priya
```

---

## 8. Sign-off

- [ ] **Swapnil (CEO)** — approve §3 release-gate contract + §4 schema (financial behaviour change)
- [ ] **Priya (CTO)** — architecture LOCKED on sign-off
- [ ] **Arjun** — pipeline scheduled, agents assigned
- [ ] **Ash** — AI-review plan for DPF-6/DPF-7 filed

_Do not begin DPF-5 (money gate) coding before Swapnil sign-off — it changes when creators get paid._

_Spec authored 2026-07-13 by Priya (CTO) from a full-codebase trace of the live deliverable and
escrow services._
