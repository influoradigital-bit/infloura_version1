# Vikram and Meera — remaining Phase B0 work, in three phases

**For:** Swapnil
**From:** Arjun (Engineering Lead)
**Date:** 2026-09-12
**Branch:** `feat/meera-creator-phase-b0` @ `ff4ed40`, in the `influora-b0` worktree
**Done_when:** Priya and the tester verify both engineers' code at the end of each phase.

Between them these two own 20 of the 35 pending tasks. Vikram builds, Meera proves. Their work is sequential inside a phase, not parallel: Meera's tasks are build and test gates, and running Maven in this worktree while another agent is also running it has already produced phantom failures that cost an hour to dismiss. One engineer in the build at a time.

---

## Phase 1 — Make pasting a brief work

The capability the whole feature is named for. Nothing a creator does today involves a brief.

| ID | Task | Owner |
|---|---|---|
| B0-39 | Brief-extraction route in the AI service: register the endpoint scope, offload the synchronous token verify, forced-tool call read from the right field | vikram |
| B0-40 | `MeeraBriefAiClient` — a creator-scoped token passing the profile id, not a service token | vikram |
| B0-41 | `BriefFallbackExtractor` — deterministic regex path so a paste survives an AI outage | vikram |
| B0-42 | `CreatorBriefService` and its controller, plus the paste rate bucket. **No secure-link methods; those are B1** | vikram |
| B0-43 | Offer-history writes at the four deal-service points, sequence derived under the existing row lock | vikram |
| B0-45 | Wave 4 gate: full backend, Python, typecheck, frontend, build | **meera** |

**Dependency outside these two:** a creator cannot see any of it without Ananya's paste card (B0-44). Phase 1 delivers a working backend and an API a test can call, not a usable screen. Say so rather than calling Wave 4 done.

---

## Phase 2 — Drafts, and the cap that stops throttling the heaviest users

| ID | Task | Owner |
|---|---|---|
| B0-46 | `DraftReplyExecutor`: floor-leak refusal, below-floor refusal with strategic override and audit row, holdout withheld, represented refusal, decline text capped at 500 because the reject request is | vikram |
| B0-47 | `CreatorMeeraDraftController`: list, approve, discard, level-up-seen. `sent_message_id` is reply-only and nullable; the replay guard keys on status | vikram |
| B0-48 | Counter request 6 → 7 components and **all 14** construction sites; re-derive the line numbers, the test files have moved | vikram |
| B0-51 | Brief-extraction cap on its own key — the monthly creator gate with a cap override, **not** the daily workspace gate, which accepts no override and would enforce nothing | vikram |
| B0-55 | Brand-visible metadata split. Implement as a separate helper at three viewer-facing points, **not** a signature change to the message mapper, which has nine call sites | vikram |
| B0-53 | Raise the creator cap to $2.00 and add the reset date to the cap error body | **meera** + rohan |
| B0-50 | Wave 5 gate | **meera** |

---

## Phase 3 — Prove it, then measure it

| ID | Task | Owner |
|---|---|---|
| B0-59 | Wave 6 gate plus a barrier re-review with Kabir | **meera** |
| B0-60 | Full backend suite, Maven run from inside the module, never piped | **meera** |
| B0-61 | Full Python, typecheck, build and frontend suites | **meera** |
| B0-62 | Runtime barrier extension: no floor on a brand payload, no tool list in a brand context, no floor key in a quote audit row | **meera** + kabir |
| B0-66 | Instrument the five gate metrics. Metric 3 reads status and the edited column, **not** the two enum values nothing writes | vikram |

**Blocked on Swapnil, not on these two:** B0-02 the Phase A deploy and live smoke, and B0-36 the calibration run against real data, which needs the deploy. Phase 3 can complete everything except proving the schema boots, which no amount of engineering here can substitute for.

---

## The verification gate, every phase

```
vikram builds ──► kavya QA (echo) ──► meera gate (proved) ──► priya sign-off ──► arjun closes
```

Two standing rules. Nothing reaches Meera without Kavya first, and Meera's gate is the row that counts, because she is the only owner on this board whose sign-off can render green. If she cannot run the gate the task is unavailable, not done.

Kabir joins on anything touching a floor, a brand-visible payload or an audit row. Ash joins on anything in the AI service.

---

## What each phase does not prove

Docker is unavailable, so 25 tests stay skipped and nothing in any phase runs against a real database or a real application boot. Every green number below is green against mocks. That does not change until the deploy lands.
