# Creator Platform — Development Process (Authoritative)

> **Author:** Priya Sharma (CTO)
> **Date:** 2026-07-09
> **Purpose:** This is the single, exact, step-by-step description of how creator-platform work actually moves from idea to shipped code in this repo — not an aspirational process, a description of the pipeline as it has run for 21+ tasks so far.
> **Live numbers live elsewhere:** this doc does not duplicate percentages — those change every tick. Source of truth for current % is always `wiki/tech/creator/CREATOR_PROGRESS.md`. Source of truth for current task queue is `TASK_INBOX.md`.

---

## 1. Planning layer — where the work is defined

Three documents, in this precedence order (highest wins on conflict):

1. **`TECH-STACK.md`** (repo root) — locked by Priya. Defines the real stack (Vite+React SPA, Spring Boot 3/MySQL `influora-api`, FastAPI `influora-ai`). Any spec that assumes Next.js/Prisma/Postgres is wrong for this repo — treat spec entity/endpoint *shapes* as a reference, not literal instructions.
2. **`wiki/tech/creator/CREATOR_EXEC_PLAN_PRIYA.md`** — Priya's CTO architecture plan. Locks the real data-model decisions (single `Collaboration` state machine + unified `DealMessage` timeline, not the specs' separate `Bid`/`CampaignApplication`/`Conversation` entities). This is the "why we build it this way" doc.
3. **`wiki/tech/creator/CREATOR_EXEC_PLAN_FINAL.md`** — Arjun's 4-week sprint schedule (Week 1 Auth+Profile+OAuth → Week 2 Campaigns+Deals → Week 3 Contracts+Deliverables → Week 4 Payments+Analytics+QA), merged from Priya's architecture + the master plan. This is the "in what order" doc.

Underneath these, `wiki/tech/creator/0X_CREATOR_*_SPEC.md` (14 files, 01–13 + master) are the **original product specs** — read for feature intent and acceptance criteria, but never followed literally where they conflict with the exec plans above.

**Kickoff artifact:** `wiki/processes/creator-sprint-kickoff.md` — written once at Week 1 start, documents the first wave of tasks assigned. Not re-written per sprint; superseded in practice by `TASK_INBOX.md` + `CREATOR_TASK_ASSIGNMENTS_PRIYA.md` for everything after Week 1.

**Priya's task-routing override:** `wiki/tech/creator/CREATOR_TASK_ASSIGNMENTS_PRIYA.md` — written when Arjun's routing needs a direct CTO audit (e.g. a build-gate conflict or an ambiguous "who's blocked on whom"). This file explicitly states it **supersedes conflicting "Next" pointers** scattered across `TASK_INBOX.md`. It is not regenerated every tick — only when Priya intervenes.

---

## 2. Orchestration layer — Arjun's loop, sentinel, heartbeat

**Orchestrator:** Arjun Kapoor. Arjun does not write code — Arjun reads progress, decides what's next, dispatches Vikram/Ananya/Kavya/Kabir/Meera (as subagents or direct instructions), and updates the tracker files.

### 2.1 The two heartbeat mechanisms (both run in parallel, by design)

1. **Primary — monitored background shell inside the live Cursor session.** A backgrounded shell with sentinel string `AGENT_LOOP_WAKE_CREATOR {"prompt":...}` piped through `notify_on_output`. This is what actually wakes the *live agent* mid-conversation — it fires a tick prompt back into the current session every ~30 minutes.
2. **Fallback — durable OS-level heartbeat.** `wiki/tech/creator/AGENT_LOOP_WAKE_CREATOR.ps1`. Launches a fully **detached** `Start-Process -WindowStyle Hidden` PowerShell child (not `Start-Job` — a `Start-Job` child dies when the parent terminal/session closes, which is exactly the failure mode that killed the loop 3 times before the 2026-07-09 11:53 IST fix). Writes a timestamp line to `AGENT_LOOP_WAKE_CREATOR.log` every 30 minutes even if no Cursor session is attached — this is the audit trail, not the wake mechanism itself (a dead Cursor session can't act on a log line).

### 2.2 Sentinel / PID file

- `wiki/tech/creator/AGENT_LOOP_WAKE_CREATOR.pid` holds the PID of the detached heartbeat process.
- `Test-LoopAlive` (inside the `.ps1`) checks: does the PID file exist, and does `Get-Process -Id <pid>` resolve? If both true, refuse to start a second instance (prevents heartbeat duplication).
- **If the PID in the file is dead** (process no longer running — this happens whenever the machine restarts or the detached process is killed), the loop is silently dead until someone notices `CREATOR_PROGRESS.md` hasn't updated in >30–60min and re-runs the `.ps1` to re-arm it. **This is a known, recurring failure mode** — check `Get-Process -Id <pid-from-file>` first before assuming the loop is alive.

### 2.3 What "a loop tick" does, mechanically

See §7 for the full step list. In short: read `TASK_INBOX.md` + `CREATOR_PROGRESS.md` → audit what actually shipped since last tick (verify against code, not just against agent claims) → route the next P0/P1 → dispatch agents → update the three tracker files → re-arm the 30-min wake.

---

## 3. Task routing — how work gets assigned

### 3.1 Files involved, in order of authority

1. **`TASK_INBOX.md`** (repo root) — the live task queue. Every task has a number (`#7`, `#19`, `#19b`, `#21`, …), an owner, a priority (P0/P1/P2), a deadline, a status, a Definition-of-Done checklist, and a `**Next:**` pointer. Sub-letters (`19b`, `19c`, `20b`) denote a task split across backend/frontend/list-endpoint slices of the same feature that ship and gate independently but share one parent number.
2. **`wiki/tech/creator/CREATOR_TASK_ASSIGNMENTS_PRIYA.md`** — written only when Priya does a direct CTO audit of the dependency graph (as opposed to Arjun's routine routing). States explicitly which tasks are truly parallel vs. sequential-blocked, corrects any wrong "blocked on X" assumption Arjun made, and gives Vikram/Ananya/Kabir/Kavya/Meera/Rohan each an **ordered task list with explicit Depends-on/Blocks** for every item — this is the actual dependency graph, more precise than the prose "Next" pointers in `TASK_INBOX.md`.
3. **`CREATOR_PROGRESS.md`** § Priority Order (Week-by-Week) — the coarse-grained plan (which week targets which %). Individual task numbers are not tracked here; only the dated changelog entries at the bottom are.

### 3.2 Priority definitions (used consistently across all three files)

- **P0** — blocks the current sprint's target %; must ship this week. Includes: the active critical-path feature slice, and any security/build finding marked "must fix before prod" for a slice that's about to ship.
- **P1** — must ship this sprint, but not the literal next action; typically pre-prod hardening debt (rate limits, sanitizers) or a feature one step behind the critical path.
- **P2** — can defer to a later sprint/backlog (Analytics, Affiliate, Coupons, full test-coverage pass as of today).

### 3.3 Task numbering and status vocabulary

Status values seen consistently in `TASK_INBOX.md`: 🟡 *waiting on dependency* → *in progress* (no explicit emoji, just prose) → ✅ **SHIPPED** (code lands, own unit tests pass) → ✅ **SHIPPED/CONDITIONAL** (Priya signed off, but explicit pre-prod conditions remain open — see §5) → occasionally ❌ **FAIL** / 🔴 (a gate rejected it and it bounces back to the owner).

---

## 4. Implementation pipeline — Vikram → Ananya sequence

The backend-first, frontend-second sequence is not incidental — `src/lib/api.ts` was written **ahead of the backend** for almost every creator endpoint (typed client functions already exist, pointing at endpoints that return `NOT_IMPLEMENTED` in live mode until the backend catches up). So the actual sequence per feature slice is:

1. **Vikram reads the relevant numbered spec** (`0X_CREATOR_*_SPEC.md`) + the locked architecture in `CREATOR_EXEC_PLAN_PRIYA.md`, then builds the backend: entity/migration (if new) → repository → service → controller → DTOs → unit tests (service + controller). Vikram checks `src/lib/api.ts` for the exact shape the frontend client already expects — the contract is usually already defined there.
2. **Vikram self-reports** in a `CREATOR_PROGRESS.md` dated entry (§6) and flips the task to "backend shipped" in `TASK_INBOX.md`, listing every file touched and the test count (e.g. "17/17 service + 4/4 controller = 21/21").
3. **Ananya wires the frontend** — this is described in the docs as "a swap of mock state for existing client calls, not new client-library work," because the `api.ts` client for that feature was already fully typed. Ananya replaces `mock*` data/handlers in the relevant `creator-*.tsx` page with real `api.ts` calls, adds loading/error/empty states, and — critically — adds an **honest gap banner** (not a silent mock fallback) for any sub-feature the backend hasn't shipped yet.
4. **Ananya self-reports** the same way Vikram does.

**Exception to strict sequencing:** per `CREATOR_TASK_ASSIGNMENTS_PRIYA.md` §1, Ananya's UI wiring for an *already-shipped* backend surface starts immediately and in parallel with that backend slice's QA/security/build gate cycle — it is not blocked on the gate cycle, only on the backend code existing. Only a sub-panel that depends on a *not-yet-shipped* backend half (e.g. the contract-signing panel blocked on the H-1 fix) waits.

---

## 5. Quality gates — exact order, every task, no shortcuts

This is the single most consistent rule across every one of the 21+ shipped tasks in `TASK_INBOX.md`:

```
Owner (Vikram or Ananya) ships code
        │
        ▼
1. Kavya (QA)      — reviews test quality + isolation logic + hostile-path coverage.
                      Verdict: APPROVED / findings routed back to owner.
                      Writes: wiki/errors/<slice>-<task#>-kavya-qa.md
        │
        ▼
2. Kabir (Security) — red-team review, LOAD-BEARING on anything touching money, PII,
                      or a public/webhook surface (per TECH-STACK.md §5 rule).
                      Verdict: PASS / PASS WITH FINDINGS (Medium+ findings tracked,
                      does not always block) / FAIL (Critical/High blocks).
                      Writes: wiki/errors/<slice>-<task#>-kabir-redteam.md
        │
        ▼
3. Meera (Build)   — runs the actual commands: `npm run build`, scoped `mvn test
                      -Dtest=<TestClasses>`, and (when a migration is new) a Flyway
                      apply check. Reports exact pass counts (e.g. "11/11 PASS,
                      BUILD SUCCESS in 3.7s"). Verdict: PASS / FAIL with root cause.
                      If FAIL — routes back to the owner, does NOT proceed to Priya.
        │
        ▼
4. Priya (CTO sign-off) — architecture review against TECH-STACK.md + the locked
                      exec-plan decisions. Verdict is one of:
                      • ✅ SHIPPED            — no conditions, done.
                      • ✅ SHIPPED/CONDITIONAL — ships now, but explicit pre-prod
                        conditions are logged (e.g. "M-2 TextSanitizer required
                        before brand-review prod") and must be tracked until closed.
                      • ❌ route back           — architecture violation found.
```

**Order is fixed and has never been observed to vary**: Kavya always reviews before Kabir, Kabir always reviews before Meera's *final* build-verify pass (though Meera may also run an earlier informal build check), and Priya is always last. A gate failing at any step sends the task back to the owner — it does **not** skip forward.

**Re-verification rule:** if a later fix only touches something a prior gate already reviewed and the fix is narrowly scoped (e.g. a compile-error rename that doesn't touch access-control logic), that gate may issue a **one-line confirmation** rather than a full re-run (see `CREATOR_TASK_ASSIGNMENTS_PRIYA.md` K-1/Kv-1 pattern). A fix that touches access-control, money logic, or a state machine always gets a full targeted re-review, not a rubber stamp.

**"Conditional" is a real status, not a soft pass.** Every `SHIPPED/CONDITIONAL` verdict has named, tracked conditions (e.g. M-19-2/3/4 = upload rate limit / no in-memory buffering / presigned URLs, all three are an explicit **prod NO-GO** on the upload endpoint even though the slice is "shipped" for continued integration work). Conditions are carried forward in every subsequent `CREATOR_PROGRESS.md` entry's "Pre-prod debt" line until a named Kabir/Vikram entry closes them.

---

## 6. Progress tracking — blended % formula and the tick log

### 6.1 The blended % formula (Swapnil/Priya methodology)

```
Blended % = (Backend journey % × 0.50) + (Frontend API-wired % × 0.35) + (Quality gates % × 0.15)
```

- **Backend journey (50% weight)** — % of the full creator backend journey (auth → profile → OAuth → campaigns → deals → contracts → deliverables → payments → analytics) that is real, tested, non-mock backend code.
- **Frontend API-wired (35% weight)** — % of creator-facing pages that call the *real* API in live mode (not mock data), with honest gap states for anything not yet backed.
- **Quality gates (15% weight)** — % of shipped slices that have a clean Kavya + Kabir + Meera gate record (no open Critical/High, no failing build).

Each of the three components is itself a judgment call made per-tick by whoever is auditing (usually Arjun, sometimes Priya) — it's not a mechanical average of individual feature %, it's "does the aggregate journey/wiring/quality feel like X% done," cross-checked against the Feature Completion Matrix. **This is why Priya periodically re-audits and corrects the number** (e.g. the very first tick corrected an initial "50%" estimate down to "28%" after direct code review found far less than claimed).

### 6.2 Where the number lives

`wiki/tech/creator/CREATOR_PROGRESS.md`, top of file:
```
## Overall Progress: ~XX% (Swapnil Blended)
| Component | Weight | Score | Contribution |
| Backend journey | 50% | XX% | ... |
| Frontend API-wired | 35% | XX% | ... |
| Quality gates | 15% | XX% | ... |
```

### 6.3 The tick/changelog protocol

Every completed subtask — a gate pass, a shipped backend/frontend slice, a loop-tick audit — gets a **dated entry appended** to `CREATOR_PROGRESS.md` in this exact format:

```markdown
### [Date Time] — [Feature/Task] [Status Change]
- **What:** [what happened, specific enough to re-derive the % change]
- **Who:** [Agent name]
- **Files changed:** [list]
- **New %:** [old% → new%, broken down by which component moved]
- **Next:** [next action + owner]
```

Entries are **never edited retroactively** — they're an append-only log, newest at the top of the changelog section (oldest ticks are further down). The Feature Completion Matrix table and the header % are the only parts of the file that get overwritten in place each tick; everything below "Progress Tracking Protocol" is additive history.

---

## 7. Loop tick workflow — what happens every ~30 minutes

When the heartbeat fires (§2), the agent (Arjun, or Swapnil/Priya standing in when explicitly noted in the log) runs this sequence:

1. **Read** `TASK_INBOX.md` and `CREATOR_PROGRESS.md` bottom-of-file changelog to see what changed since the last tick.
2. **Verify, don't trust** — spot check the claims against actual code (this has caught real problems, e.g. Priya's audit in `CREATOR_TASK_ASSIGNMENTS_PRIYA.md` §0 catching that a reported build fix was "unverified-green, not confirmed-green" and had to be explicitly re-checked, not rubber-stamped).
3. **Identify the next P0** — usually "what's blocking the critical path right now" (see the ordered dependency graph in `CREATOR_TASK_ASSIGNMENTS_PRIYA.md` §1 as the canonical example of this reasoning).
4. **Dispatch** — either directly instruct/act as the next agent in-line, or spin up a subagent per role (Vikram/Ananya/Kavya/Kabir/Meera) for parallel-safe work. Tasks that don't block each other (e.g. a build re-verify + a brand-new backend feature + a frontend wiring task against an already-stable API) are dispatched in the **same tick**, not serialized needlessly.
5. **Update the three tracker files** — `TASK_INBOX.md` (task status + Definition-of-Done checkboxes), `CREATOR_PROGRESS.md` (new % + dated changelog entry), and `SHARED_CONTEXT.md` (cross-team daily-standup-style note, when the change is significant enough other teams should know).
6. **Write a "Loop Tick #N" entry** — a special changelog entry distinct from a feature-ship entry, summarizing what was audited this tick and what's next, so the next tick (or a human) can pick up context in one read.
7. **Re-arm the heartbeat** — confirm the PID file still points at a live process (§2.2); re-run the `.ps1` if it died.
8. **Stop condition:** the loop continues indefinitely until blended % = 100% AND the full Priya sign-off checklist (§8 of `CREATOR_TASK_ASSIGNMENTS_PRIYA.md`, mirrored in §9 below) is fully checked. Hitting a weekly target (e.g. "82%, Week 2 target hit") does **not** stop the loop — only 100% + full sign-off does.

---

## 8. Employee roles — who does what, when

| Role | Primary responsibility in this pipeline | Typically acts... |
|---|---|---|
| **Arjun** (Orchestrator) | Runs the loop tick (§7), routes tasks, keeps trackers in sync | Every ~30min heartbeat, or on-demand between ticks |
| **Vikram** (Backend) | Spring Boot/MySQL implementation — entities, repositories, services, controllers, unit tests | After Arjun/Priya routes a backend task; self-reports on completion |
| **Ananya** (Frontend) | Vite/React wiring — replaces mock state with real `api.ts` calls, loading/error/gap states, Framer Motion per the repo's motion skills | After Vikram's backend for that slice exists (parallel-safe once the API contract is stable — see §4 exception) |
| **Kavya** (QA) | First gate after code lands — test quality, isolation logic, hostile-path coverage, extends `KAVYA_QA_TEST_PLAN.md` | Immediately after Vikram/Ananya ship; writes findings doc |
| **Kabir** (Security) | Second gate — red-team review, load-bearing on money/PII/public surfaces | After Kavya; writes findings doc; can issue "PASS WITH FINDINGS" (non-blocking Medium) or block on Critical/High |
| **Meera** (Build/DevOps) | Third gate — actually runs `npm run build` / `mvn test` / Flyway checks, reports exact pass counts | After Kabir; blocks progression to Priya on any build failure |
| **Priya** (CTO, this doc's author) | Final gate — architecture sign-off against `TECH-STACK.md`; owns this process doc and `TECH-STACK.md` | After Meera; issues SHIPPED / SHIPPED/CONDITIONAL / route-back |
| **Rohan** (CFO) | Cost tracking (agent-hours, metered API costs), flags business-impacting engineering decisions (e.g. withdrawal-minimum choice) as cost questions, not just bugs | Parallel to engineering work, no code dependency; escalates money-policy questions to Swapnil |
| **Swapnil** (CEO) | Final business-decision authority; occasionally stands in as loop-tick auditor | Escalation-only (§10) or when explicitly running a tick himself (seen in the log) |

---

## 9. Definition of Done — 100% checklist

A feature slice is "done" only when **all** of the following are true (mirrors `CREATOR_TASK_ASSIGNMENTS_PRIYA.md` §8, the authoritative sign-off checklist Priya will not skip):

**Architecture & code quality**
- [ ] No controller resolves identity from a path/body param — always `CreatorContextService`/`BrandContextService` off the authenticated principal
- [ ] Every money-affecting path goes through `WalletLedgerService.post()` — zero direct balance mutations
- [ ] Zero duplicate entities for what `Collaboration`/`DealMessage` already model (no resurrecting `Bid`/`CampaignApplication`/`Conversation`)
- [ ] `TECH-STACK.md` conventions followed in every new file
- [ ] No hardcoded platform fee without Swapnil's explicit decision

**Functionality**
- [ ] Full creator journey works end-to-end with **zero mock data** in the production code path
- [ ] All 13 numbered spec files implemented, or any scope cut is written down and approved — not silently dropped
- [ ] Instagram OAuth confirmed prod-ready; YouTube explicitly deferred-with-approval, not silently missing

**Quality gates**
- [ ] Kavya: test coverage ≥ 80%, full E2E pass green, all carried-forward gaps closed
- [ ] Kabir: zero Critical/High outstanding; every Medium (tracked by code, e.g. M-2, M-9-1, M-19-2/3/4) explicitly closed with a findings-doc entry
- [ ] Meera: `npm run build` + `npm run dev` + `npm run test` + `npm run lint` + backend `mvn test` + all Flyway migrations green **in the same verification pass**
- [ ] Performance budget met: page load < 2s, API p95 < 500ms (explicit watch item: N+1-shaped repo calls in deal-room timeline / campaign browse list endpoints)

**Financial / business**
- [ ] Rohan's cost log shows no unapproved overrun
- [ ] Withdrawal minimum and platform fee are confirmed business decisions, not engineering defaults

**Documentation**
- [ ] API docs exist for every creator endpoint shipped
- [ ] Kabir's security audit report and Kavya's coverage report are linked from `CREATOR_PROGRESS.md`, not just sitting in `wiki/errors/`

**Priya's rule for partial gaps:** *"If any box is unchecked: route back to the owning agent with the specific box cited — do not re-run a full gate cycle for a partial gap."*

---

## 10. Escalation — when it goes to Swapnil

Two categories of decision are explicitly **not** engineering's to make — they are logged as open business questions and routed to Swapnil, never silently defaulted by Vikram/Ananya/Arjun:

1. **Platform fee model** (`PlatformFeeConfig`/take-rate) — money is already flowing through escrow with an implicit, undocumented 0% platform take today. This is architecture note #7 in `CREATOR_EXEC_PLAN_FINAL.md` and R-3 in `CREATOR_TASK_ASSIGNMENTS_PRIYA.md`: **"no hardcoded fee until business decision."** Blocks Week 4 payments/analytics work if still undecided when that work starts.
2. **Withdrawal minimum discrepancy** — the spec (`10_CREATOR_PAYMENTS_SPEC.md`) says ₹1,000 minimum; the original mock UI said ₹100; the shipped backend (`WalletService.requestCreatorWithdrawal`) currently enforces ₹500. Rohan's task R-2 explicitly frames this as a **cost-policy question** (lower minimum → more, smaller payout transactions → more flat per-transaction payout-rail fees), not just an engineering inconsistency — flagged to Swapnil before treating ₹500 as final.

**General escalation rules** (from `CREATOR_EXEC_PLAN_FINAL.md` § Communication Protocol)://
- **Arjun → Priya**: architecture decision needed, performance issue, `TECH-STACK.md` clarification needed.
- **Arjun → Swapnil**: timeline at risk, scope change needed, resource conflict.
- **Vikram/Ananya → Arjun**: task ambiguous, dependency blocked, need help from another agent.
- **Kavya/Kabir → Arjun**: code quality issues persist, security findings not being fixed.
- **Rohan → Swapnil**: any money-policy question that has a cost-structure dimension (§10.2 is the running example).

---

**End of process document.** For live completion %, open tasks, and the current critical path, always read `wiki/tech/creator/CREATOR_PROGRESS.md` and `TASK_INBOX.md` directly — this document describes *how* the pipeline runs, not *where it is today*.
