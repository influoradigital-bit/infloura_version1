# PENDING WORK AND STAGE — Meera Phase B0

**For:** Swapnil
**From:** Priya (CTO)
**Date:** 2026-09-12
**Branch:** `feat/meera-creator-phase-b0` @ `c22b00e`, in the `influora-b0` worktree
**Sources:** `TASKS-B0.md` (70 tasks) · `QA-SWAPNIL-0912.md` · `QA-TECH-0912.md` · `SPEC.md` §14 · `DECISIONS-0904.md`

Stage vocabulary, used consistently below:

| Stage | Means |
|---|---|
| **READY** | Unblocked. An owner can start today. |
| **BLOCKED** | Named dependency must clear first. |
| **RULING** | Waiting on Swapnil. A default ships if he stays silent. |
| **UNPROVEN** | Code is written and green, but the only evidence is a mock. |

---

## 1. Above the line — these outrank the remaining waves

| # | Item | Owner | Stage | Why it outranks a wave |
|---|---|---|---|---|
| P-01 | **Push the branch.** Seven commits sit on no remote: all four B0 waves plus two fixes. Wave 3 alone is 86 files. | meera | **RULING** | Single-disk loss risk. Cheapest risk on the sheet to close. Needs your authorisation to push. |
| P-02 | **Marketing page overclaims.** `src/pages/meera-for-creators.tsx` is routed and says Meera "drafts the reply" three times and "Every reply is labelled: drafted with Meera". Neither exists — Waves 5 and 6. Not on `origin/main`; it IS on the pushed `phase-e` branch. | ananya + nisha | **READY** | We are promising in public what we cannot deliver. Either cut the claims or mark the page clearly as forthcoming. |
| P-03 | **Regulated-category flag cannot fire at all.** Fourteen rules ship; this one is unreachable today. | vikram | **READY** | It is the rule that keeps a creator out of trouble with a financial or health advertiser. A launch blocker in my judgement. |
| P-04 | **No flag can be dismissed.** Three are correctly marked non-dismissible, but no screen supplies a dismiss handler, so all fourteen behave as permanent. | ananya | **READY** | Turns the panel into noise, which is the failure mode we fixed the vague-deliverables rule to avoid. |
| P-05 | **Phase A deployment and live smoke.** No Docker host, no VPS access. | meera | **RULING** | Blocks Wave 8 entirely and every UNPROVEN row below. Oldest open item on the feature. |

---

## 2. Defects confirmed by the technical audit

| # | Defect | Owner | Severity | Stage |
|---|---|---|---|---|
| D-01 | The info-barrier test bans exactly one repository import and nothing else, so putting a creator's floor on a brand-readable payload turns **no test red**. | kabir + vikram | **HIGH** (missing control) | READY |
| D-02 | The spec instructs minting the send permission into every level-1 token. The day that route lands, tokens already issued become live send grants, and the context test goes green on that commit. | vikram | **HIGH** (process) | READY — fix before Wave 4 |
| D-03 | Deal rows can render blank: two fields are required in TypeScript but nullable in Java, and omitted fields arrive undefined. | ananya | MEDIUM | READY |
| D-04 | A malformed quote renders "Not available yet · undefined revisions · Based on ." because the type guard admits any object. | ananya | MEDIUM | READY — cheapest fix here |
| D-05 | Risk evaluation prices a floor but writes no pricing audit row, so the calibration report systematically under-counts. The allowed tool-call row is not a substitute. | vikram | MEDIUM | READY |
| D-06 | The rate limiter collapses every creator into one shared bucket when the verify-failure budget is exhausted. Triggered by our own expired tokens or a key rotation, not by an attacker. | vikram | MEDIUM | READY |

**My priority order:** D-02 first, because Wave 4 is the wave that starts adding routes and the current comment tells the next author that minting the full ceiling is free. It is free only while no route exists. Then D-01, then D-04 as a quick win, then D-03, D-05, D-06.

---

## 3. Remaining board waves

| Wave | Tasks | Owners | Stage | Note |
|---|---|---|---|---|
| 4 — paste and briefs | 8 (B0-39, 39a, 40-45) | vikram, ananya, kavya, meera | **READY** | This is the wave that makes "paste a brief" work at all. The feature is named for it. |
| 5 — drafts and approvals | 5 (B0-46-50) | vikram, ananya, kavya, meera | BLOCKED on 4 | Adds the first thing that reaches a brand. |
| 6 — cap, authorship, chat | 9 (B0-51-59) | vikram, ananya, meera, rohan, kabir | BLOCKED on 5 | Carries the brand-facing label and the cap behaviour. |
| 7 — verify | 6 (B0-60-65) | meera, kabir, priya, neha, tara | BLOCKED on 6 | Neha's live run needs P-05. |
| 8 — measure | 4 (B0-66-69) | vikram, kavya, tara, swapnil | BLOCKED on P-05 | Cannot start without a deployment and real creators. |
| 3 leftover — B0-36 calibration against real data | 1 | meera + rohan | BLOCKED on P-05 | Endpoint is built; it returns nulls until there is production data. |

Carried-over work not numbered on the board: the three read executors deferred from Wave 2 are now two, since estimate-rate and check-risks landed in Wave 3. Brief-reading rides with Wave 4.

---

## 4. Rulings I am handing back

| # | Decision | My recommendation | Default if silent |
|---|---|---|---|
| R-01 | Push authorisation, and where the Phase A smoke runs | Push today; run the smoke on the VPS rather than waiting for a local Docker fix | Nothing is pushed |
| R-02 | Standing rule for cutting prices | Auto-cut a tier when the median quote exceeds the median stated budget by more than 25%; hard-block a band above 50% Meera-anchored share | You rule brief by brief |
| R-03 | Holdout ratio | One in ten for the first cohort, not one in five | One in five |
| R-04 | Brand-facing Meera label | Keep it shown, always with the creator's name; never a bare "Sent by Meera" | Shown |
| R-05 | Benchmark-mode anchor | Cap or suppress the lift while provenance is benchmark-only | Uncapped in own/tier mode, clamped in benchmark |
| R-06 | Off-platform payment consequence | A goodwill position and a brand-side consequence, written before launch | Logged, creator warned, nothing else |
| R-07 | No compensation for a wrong estimate, stated in terms | Put it in the creator terms before a single quote goes out | Unstated, which is the risky option |

Two more for the record: the public pricing poll on the live page contradicts the finance model roughly four-fold, and none of the five gate metrics measures a deal closed, a fee earned, or a creator retained.

---

## 5. Unproven, not pending

These are written and green. They are listed so nobody records them as verified.

| Item | What would prove it |
|---|---|
| Four migrations apply and the app boots with schema validation | `MeeraPhaseB0BootValidationTest` reporting 0 skipped on a Docker host |
| Any creator tool executing end to end, prompt to Spring to card | One live turn against a deployed build |
| Any of the 32 creator-context fields non-null for a real creator | A context fetch on real data |
| The rate-limit bucket degrade under a real expired token | A live load probe |
| Column defaults, collation and index ordering on the new tables | The same boot test |

All 25 skipped backend tests are Docker-gated. Nothing in Phase B0 has run against a real MySQL or a real Spring boot cycle.

---

## 6. Minor residuals, deliberately parked

| Item | Owner | Why parked |
|---|---|---|
| One deliverable constant maps differently in two taxonomies | vikram | Inert and unreachable through any public surface; changing it is an ungateable behaviour change |
| The draft-reply schema description names tools that may not be offered | vikram | Lands with Wave 5, which is when that tool is wired |
| `graphify-out/` is absent in this worktree, so agents cannot follow the graph-first rule | meera | They read source directly, which is the documented fallback |
