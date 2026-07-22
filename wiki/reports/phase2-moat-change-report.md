# Phase 2 Moat Build — Authoritative Change Report

**Compiled by:** Tara (Operations & Reporting) · 2026-07-22
**Status at compile time:** Code-complete, QA-APPROVED, BUILD-GREEN. Awaiting Kabir mandatory security gate → Ash `--live` eval → Priya post-Kabir close-out → Swapnil.
**Sources reconciled:** `phase2-backend-design.md` §8 (Vikram), `phase2-frontend-design.md` §8 (Ananya), `phase2-priya-review.md`, `phase2-ash-review.md`, `phase2-priya-impl-review.md`, `phase2-kavya-qa.md`, `phase2-meera-build.md`, `SHARED_CONTEXT.md`, and ground-truth `git status` / `git diff --stat`.

> **READ THIS FIRST — branch/commit divergence (see §5.3).** Every design and review doc names the branch `feat/portfolio-view-tracking`. The working tree is actually on **`feat/creator-ai-copilot`**, and the **entire moat changeset is uncommitted** (working-tree modified + untracked). Nothing described below has been committed to any branch yet.

---

## 1. Executive summary — what shipped

Phase 2 ("label → moat") grounds Meera in the brand's own **verified outcome data** so she can answer "how did this campaign do?" from real released escrow, platform-verified reach, and attributed revenue — never a formula, a proxy, or a model-invented number. Four deliverables:

- **2.1 — Outcome digest in the context payload.** A new `outcome_digest` section on `ContextResponse`: per-campaign `campaign_outcomes[]` (spend from `RELEASED` escrow, PLATFORM_VERIFIED reach, UTM-attributed revenue) plus a cross-tenant `niche_rate_band` (min/median/max of real `Collaboration.agreedRate`) gated behind a **k-anonymity floor of 5 on both creators and workspaces**. Every number is server-derived; the digest reuses the existing `PAST_CAMPAIGN_LIMIT=5` campaign list so Block B stays under the 2KB lock (~0.9KB added).

- **2.2 — `get_campaign_performance` R-tier tool.** A read-only, no-money tool returning verified performance for one owned campaign: spend, verified reach, attributed revenue, settled commission, plus **server-computed** `roi`, `responseRate`, `avgCreatorScore` and a single 2-state `provenance` tag. IDOR is closed structurally (single `findByIdAndWorkspaceId` resolve, 404-not-403, no separate existence check). Per-deliverable rows carry opaque ids + numerics only — no creator name/handle/caption.

- **2.3 — Flywheel logging (`meera_interaction_log`).** A new append-only table capturing the `OPTIONS_PRESENTED → OPTION_TAPPED → DRAFT_CREATED → DRAFT_FUNDED → REVISION_REQUESTED` funnel. Fire-and-forget writes (`REQUIRES_NEW`, swallow-and-warn), free text redacted through a new Java `SensitiveTextRedactor` that mirrors `redaction.py` byte-for-byte. `DRAFT_ABANDONED` was **dropped** from the v1 enum (no live trigger — dead code).

- **2.4 — Frontend `StagePerformance`.** A new Living-Canvas stage (6th) rendering the three performance stats via `StatPair` tiles, plus a quiet-by-default `EstimateBadge`. The card carries numbers; the chat bubble carries Meera's narrative — no duplication, and the frontend never computes a number.

**The Swapnil scope decision.** Swapnil scoped this as backend-first (2.1/2.2/2.3), with 2.4 frontend "lower priority, starts once API shapes defined but design it now." Ananya therefore wrote the frontend contract *first* (against a still-unbuilt backend) so Vikram could implement to it — which is why both design docs cross-reference each other's DTO shapes.

**Current gate status.** Design sign-off (Priya + Ash: APPROVED-WITH-CHANGES) → implementation → Priya impl-review (CHANGES-REQUIRED, single defect **D1**, fixed) → Kavya QA (**APPROVED**, after the 3 QA-infra gaps were closed) → Meera build (**BUILD-GREEN**, 70 touched tests pass). **Next and still-pending: Kabir mandatory security gate**, then Ash `--live` eval, Priya post-Kabir close-out, and Swapnil final.

---

## 2. File-by-file change table

Owner = who authored the change per the changes-logs. Deliverable = 2.1 / 2.2 / 2.3 / 2.4 / eval / QA / x-cut.
Every row below was confirmed present in `git status`. Reconciliation notes (including two files the logs do **not** cleanly explain) are in §5.

### Backend — Java (main)

| Path (under `influora-api/src/main/java/com/influora/`) | New/Mod | Owner | What & why | Deliv |
|---|---|---|---|---|
| `web/dto/meera/MeeraContextDtos.java` | Mod | Vikram | New `OutcomeDigest`/`CampaignOutcomeEntry`/`RateBand` records + `outcome_digest` field on `ContextResponse` (scalar-only, no Map/passthrough — Lock 4) | 2.1 |
| `service/meera/BrandContextAssembler.java` | Mod | Vikram | `assembleOutcomeDigest`/`buildOutcomeEntry`/`buildRateBand`/`tierBucket`/`median`; k-anon dual-floor gate; no `FUNDED_STATUSES` import | 2.1 |
| `service/meera/MeeraContextService.java` | Mod | Vikram | Fetches escrow/metric/utm/collab rows for the reused `PAST_CAMPAIGN_LIMIT=5` list; builds rate-band candidate query | 2.1 |
| `repository/EscrowHoldRepository.java` | Mod | Vikram | `sumAmountByCampaignIdAndStatus` — strictly `RELEASED` (SR-1 make-or-break, B4) | 2.1/2.2 |
| `repository/CollaborationRepository.java` | Mod | Vikram | `RateBandCandidateRow` projection + `findRateBandCandidates` native query; `findByCampaignId` | 2.1/2.2 |
| `domain/enums/MeeraToolName.java` | Mod | Vikram | Add `get_campaign_performance` value (6th tool) | 2.2 |
| `service/meera/tool/ToolCallValidator.java` | Mod | Vikram | R-tier mapping for new tool; javadoc 5→6 | 2.2 |
| `service/meera/tool/GetCampaignPerformanceExecutor.java` | **New** | Vikram | R-tier executor; IDOR-closed `findByIdAndWorkspaceId`; server-computed `roi`/`responseRate`/`avgCreatorScore`; PII-stripped deliverables | 2.2 |
| `web/dto/meera/MeeraToolDtos.java` | Mod | Vikram | `GetCampaignPerformanceResult` + `DeliverablePerformanceEntry`; **D1 fix** `@JsonInclude(ALWAYS)` on `roi` | 2.2 |
| `web/MeeraInternalController.java` | Mod | Vikram | `/internal/meera/get_campaign_performance` route (R-tier auth) + `/internal/meera/interaction-log` route (`OPTIONS_PRESENTED`) | 2.2/2.3 |
| `repository/AffiliateEarningRepository.java` | Mod | Vikram | `findByCampaignIdAndStatus` (settled commission sum) | 2.2 |
| `common/SensitiveTextRedactor.java` | **New** | Vikram | Java port of `redaction.py` regex backstop — all 6 patterns, order-preserved (secret→JWT→PAN→email→phone→bank) | 2.3 |
| `domain/entity/MeeraInteractionLog.java` | **New** | Vikram | Immutable append-only flywheel entity (builder-only) | 2.3 |
| `domain/enums/MeeraInteractionEventType.java` | **New** | Vikram | 5-value enum — `DRAFT_ABANDONED` dropped (Q4) | 2.3 |
| `repository/MeeraInteractionLogRepository.java` | **New** | Vikram | Write-only (no read query in v1) | 2.3 |
| `service/meera/MeeraInteractionLogService.java` | **New** | Vikram | Fire-and-forget `REQUIRES_NEW`; redacts `revisionReason` as its own first line | 2.3 |
| `web/dto/meera/MeeraInteractionDtos.java` | **New** | Vikram | `OptionTappedRequest` | 2.3 |
| `web/MeeraInteractionController.java` | **New** | Vikram | `POST /workspaces/{id}/meera/interactions/option-tapped` — workspace resolved from principal (IDOR fix), not path param | 2.3 |
| `service/meera/tool/CreateCampaignExecutor.java` | Mod | Vikram | Log `DRAFT_CREATED` after campaign save | 2.3 |
| `service/meera/tool/ConfirmLaunchExecutor.java` | Mod | Vikram | Log `DRAFT_FUNDED` at the real DRAFT→ACTIVE transition (after fee charge), correcting the plan's write-point drift | 2.3 |
| `service/BrandDeliverableService.java` | Mod | Vikram | Log `REVISION_REQUESTED` in `requestRevision` with redacted feedback, correcting the plan's `DealService` mislocation | 2.3 |

### Backend — Java (tests)

| Path (under `influora-api/src/test/java/com/influora/`) | New/Mod | Owner | What & why | Deliv |
|---|---|---|---|---|
| `common/SensitiveTextRedactorTest.java` | **New** | Vikram | Cross-language parity fixtures — verbatim from `test_redaction.py` (Priya blocking B5) | 2.3 |
| `service/meera/BrandContextAssemblerTest.java` | Mod | Vikram | Updated call sites/mocks for new signatures (no new outcome-digest assertions — QA-5 advisory gap) | 2.1 |
| `service/meera/MeeraContextServiceTest.java` | Mod | Vikram | Updated mocks for new fetch signatures | 2.1 |
| `web/MeeraInternalControllerContextTest.java` | Mod | Vikram | Updated for new controller constructor param | 2.2 |
| `service/meera/tool/ToolCallValidatorTest.java` | Mod | Vikram | 5→6 tool count | 2.2 |
| `service/meera/tool/CreateCampaignExecutorTest.java` | Mod | Vikram | New constructor params (interaction-log dependency) | 2.3 |
| `service/meera/tool/ConfirmLaunchExecutorTest.java` | Mod | Vikram | New constructor params | 2.3 |
| `service/BrandDeliverableServiceTest.java` | Mod | Vikram | New constructor params | 2.3 |

### Migrations

| Path | New/Mod | Owner | What & why | Deliv |
|---|---|---|---|---|
| `influora-api/src/main/resources/db/migration/V20260721160000__meera_interaction_log.sql` | **New** | Vikram | `meera_interaction_log` table; VARCHAR event_type (no DB enum), no FKs (event-log convention), `prompt_version` **nullable** (judgment call, §3) | 2.3 |

### Backend — Python

| Path (under `influora-ai/`) | New/Mod | Owner | What & why | Deliv |
|---|---|---|---|---|
| `app/tools/schemas.py` | Mod | Vikram | New `GET_CAMPAIGN_PERFORMANCE` tool → `TOOL_NAMES`/`TOOL_TIERS`(read)/`TOOL_TO_SPRING_PATH`/`TOOL_SCHEMAS` | 2.2 |
| `app/prompt/persona.py` | Mod | Vikram | Block-A persona guidance for the new tool ("quote only figures the tool returns; if ROI missing, say no data") | 2.2 |
| `app/config.py` | Mod | Vikram | `PROMPT_VERSION` `meera-2026.07.21.8` → `.9` (re-warms cache once) | 2.1 |
| `app/prompt/assembler.py` | Mod | Vikram | `_render_outcome_digest` (every string sub-field `_safe()`-wrapped; every nested read via `.get()`); `outcome_digest` → `CONTEXT_PAYLOAD_FIELDS` | 2.1 |
| `app/clients/spring.py` | Mod | Vikram | New `log_interaction` outbound client method (the one place Python reaches Spring for this build) | 2.3 |
| `app/tools/loop.py` | Mod | Vikram | Wire `present_options` dispatch → `spring.log_interaction` (best-effort, never breaks the turn) | 2.3 |

### Evals

| Path (under `influora-ai/`) | New/Mod | Owner | What & why | Deliv |
|---|---|---|---|---|
| `evals/datasets/outcome_recommendation.jsonl` | **New** | Vikram | 15 prose cases; `expected.provenance.allowed/forbidden_values`; full adversarial coverage (self-reported omit, below-k, in-head ROI, injection ×2, IDOR 404) | eval |
| `evals/datasets/campaign_performance.jsonl` | **New** | Vikram | 10 structured cases; mixed PLATFORM_VERIFIED/CREATOR_REPORTED rows; PII-strip + IDOR-404 fixtures | eval |
| `evals/scorers.py` | Mod | Ash | New `provenance_exact_match` primitive + `extract_figures`/`canonical_number`/`ProvenanceResult` (₹/×/%/lakh/crore aware) | eval |
| `evals/run_eval.py` | Mod | Ash | `score_/aggregate_outcome_recommendation` + `score_/aggregate_campaign_performance`; live caller; `FEATURES` registration | eval |
| `evals/README.md` | Mod | Ash | Field-format contract + threshold rows | eval |
| `tests/evals/test_eval_harness_offline.py` | Mod | Ash | Scorer unit tests proving the gate goes red on orphaned/forbidden/leak/PII; fixtures-pending skip guards | eval |
| `tests/eval/test_prompt_injection.py` | Mod | Vikram | `test_exactly_five_tools…` → six-tools test incl. `get_campaign_performance` | 2.2 |
| `tests/prompt/test_assembler_context_wiring.py` | Mod | Vikram | **+1 line** — adds `"outcome_digest"` to the expected `CONTEXT_PAYLOAD_FIELDS` set. **NOT listed in any §8 changes-log** (see §5.1) | 2.1 |

### Frontend

| Path (under `src/`) | New/Mod | Owner | What & why | Deliv |
|---|---|---|---|---|
| `lib/meera-api.ts` | Mod | Ananya | `CampaignPerformancePayload` interface + `isCampaignPerformancePayload` guard (`roi: number \| null`, single 2-state `provenance`) | 2.4 |
| `data/stage-config.ts` | Mod | Ananya | `'performance'` `MeeraStageId` + `'get_campaign_performance'` trigger (locked wire-name, F1); `STAGE_CONFIG`/`STAGE_ORDER` entries | 2.4 |
| `data/meera-copy.ts` | Mod | Ananya | `performance` titles/subtitles, stat labels, `MEERA_PERFORMANCE_COPY` | 2.4 |
| `data/meera-mock.ts` | Mod | Ananya | `MockCampaignPerformance` + `MOCK_CAMPAIGN_PERFORMANCE` for `!live` branch | 2.4 |
| `components/feature/meera/EstimateBadge.tsx` | **New** | Ananya | Single quiet-by-default provenance badge (`meera-warning` tokens, `role="note"` + sr-only); dormant in v1 | 2.4 |
| `components/feature/meera/StagePerformance.tsx` | **New** | Ananya | 3-tile stat stage (mock/live/loading), `roi===null` placeholder, "See full breakdown" → `/brand/analytics`, no card narrative | 2.4 |
| `components/feature/meera/MeeraChatPanel.tsx` | Mod | Ananya | Add `'get_campaign_performance'` to `MEERA_FUNCTION_CALLS` (the real stage-advance gate) | 2.4 |
| `components/feature/meera/LivingCanvas.tsx` | Mod | Ananya | `performance` render branch; `escrowStateForStage('performance')` → `secured` (F2 fix); prefer real `spendInr` for total label | 2.4 |

### Docs / process

| Path | New/Mod | Owner | What & why | Deliv |
|---|---|---|---|---|
| `wiki/processes/qa-checklist.md` | **New** | Kavya | Money-Path Provenance Checklist (QA-3) | QA |
| `wiki/processes/schema-changes.md` | Mod | Vikram | Migration logged | 2.3 |
| `wiki/build/phase2-backend-design.md` | **New** | Vikram | Backend design + §8 changes-log | x-cut |
| `wiki/build/phase2-frontend-design.md` | **New** | Ananya | Frontend design + §8 changes-log | x-cut |
| `wiki/build/phase2-priya-review.md` | **New** | Priya | Design-gate sign-off (B1–B5/F1–F3, Q1–Q5) | x-cut |
| `wiki/build/phase2-ash-review.md` | **New** | Ash | AI-review + `provenance_exact_match` addendum | x-cut |
| `wiki/build/phase2-priya-impl-review.md` | **New** | Priya | Implementation gate (D1) | x-cut |
| `wiki/build/phase2-kavya-qa.md` | **New** | Kavya | QA gate (APPROVED) | x-cut |
| `wiki/build/phase2-meera-build.md` | **New** | Meera | Build verification (BUILD-GREEN) | x-cut |
| `SHARED_CONTEXT.md` | Mod | Team | Handoff notices across the gate chain | x-cut |

---

## 3. Decision trail — the contested calls and how they resolved

**Provenance: 2-state, agreed by Priya AND Ash.** The plan's §2.1 implied a 2-state tag (`PLATFORM_VERIFIED | SELF_REPORTED`) while §2.4's badge spec implied a 3rd `inferred`/low state (borrowed from the Phase-1 `price_source` lineage). Both reviewers independently ruled **2-state** for the whole outcome/performance contract: it maps 1:1 onto the real server column `DeliverableMetric.source`, and `INFERRED` is meaningless on a measured outcome number. Ash pre-flagged this as his most-likely split with Priya (§Ash "Points where I expect to disagree") — but they **converged**, so no Swapnil escalation was needed. Consequence: v1 surfaces verified numbers only; `provenance` is a single top-level tag; `EstimateBadge` is built but dormant.

**D1 — the `roi === null` serialization mismatch (Priya impl-review, blocking).** Backend represented "no ROI" as field-absent (`@JsonInclude(NON_NULL)` + `computeRoi` returning `null`); frontend's guard required explicit JSON `null`. Result: `isCampaignPerformancePayload` returned `false` for every zero-spend/no-revenue campaign, freezing `StagePerformance` on its loading spinner forever and making its own "not enough data yet" placeholder dead code. No security/grounding impact. Fixed backend-side (preferred, 1 line): `@JsonInclude(ALWAYS)` on the `roi` component. Meera's build and Kavya's QA both confirm the fix is in place and correct.

**The 3 v1 judgment calls Priya approved (Vikram's flagged deviations):**
1. **`responseRate` derivation — APPROVE.** Computed over `source==INVITATION` collaborations, accepted = status ∉ {INVITED, CANCELLED}. Priya verified against the real `CollaborationStatus` enum that there is no `DECLINED`/`REJECTED` state, so a declined invite can't be miscounted as accepted. Non-blocking nit: it's really an *acceptance/conversion* rate. **Priya explicitly deferred the final ship-vs-cut to Ash** (AI-grounding co-authority) — grounding verdict is APPROVE, the cut would be a pure lean-scope call and is zero-cost on the frontend.
2. **`prompt_version` made nullable — APPROVE.** Python-originated `OPTIONS_PRESENTED` stamps a real value; the 3 pure-Java business events log `NULL` rather than a fabricated sentinel. Attribution isn't lost — `DRAFT_FUNDED` correlates back via `session_id`.
3. **`REVISION_REQUESTED.campaign_id` left null — APPROVE for v1.** Outside the core funnel; write-only in v1. Non-blocking note: capturing the in-hand `deliverableId` would preserve future campaign attribution at ~zero cost.

**Plan-drift corrections the implementers caught against real code:**
- **Vikram (§0.6):** the plan mislocated three write points. `DRAFT_FUNDED` belongs in `ConfirmLaunchExecutor` (real DRAFT→ACTIVE), not `CreateCampaignExecutor` (draft-only). `REVISION_REQUESTED` free text lives in `BrandDeliverableService.requestRevision`, not `DealService` (which only has a list-filter status). `DRAFT_ABANDONED` had no trigger at all (`CampaignIntent.abandon()` is dead code) → dropped from v1 (Q4). He also simplified SR-1 to read `EscrowHold.status=RELEASED` directly rather than joining `wallet_transactions` — Priya confirmed this is the *more* correct read, not a deviation.
- **Ananya (§0):** the plan said stage-advancement lives in `ToolResultRenderer` "that advances useMeeraStage." It does not — `ToolResultRenderer` is a pure presentational dispatcher. The real gate is `MEERA_FUNCTION_CALLS` + `isMeeraFunctionCall()` in `MeeraChatPanel.tsx`. Both Priya and Ash verified the correction; it turned a claimed 1–2 file change into the correct 4-file wiring. Ananya also caught that a wire-name mismatch **drops the tool result entirely** (early-return at `MeeraChatPanel.tsx:498`), not just fails to advance — making F1's byte-identical wire-name a hard requirement with no CI protection on the frontend side.
- **Ash cross-doc blocker B1:** the tool result Vikram originally emitted did **not** contain the ROI/response-rate/CreatorScore numbers Ananya's card rendered. The tempting fix (let Meera or React divide revenue÷spend) would manufacture an orphaned number. Resolution: all three are **server-computed deterministic fields** on the DTO. This is the single most important grounding fix in the pair.

---

## 4. Verification record

**Meera — BUILD-GREEN (2026-07-22).** Zero compile errors (Java / TypeScript / Python), zero regressions.

| Suite | Result |
|---|---|
| Phase-2 touched Java test classes | **70 tests, 0 failures, 0 errors** (BrandContextAssemblerTest 6, MeeraContextServiceTest 5, ToolCallValidatorTest 18, SensitiveTextRedactorTest 10, ConfirmLaunchExecutorTest 9, BrandDeliverableServiceTest 20, MeeraInternalControllerContextTest 2) |
| Full backend suite | ~50+ classes, zero failures; Testcontainers integration tests ran (last: `DatabaseConstraintIntegrationTest` 00:22:37) |
| Frontend `tsc --noEmit -p .` | Clean, 0 errors; D1 fix confirmed reflected |
| Python `test_redaction.py` | **13 passed** — Java↔Python redaction parity confirmed |
| Python `test_prompt_injection.py` | **38 passed** — incl. `test_exactly_six_tools_exist_and_tiers_match_spec` (6-tool count + R-tier) |

*Build note:* Meera flagged that the test name in Vikram's §8 log (`test_exactly_six_tools_no_hidden_local`) does not exist — the actual passing test is `test_exactly_six_tools_exist_and_tiers_match_spec`. Log naming slip only; the test passes.

**Kavya — APPROVED (2026-07-22, revised).** Line-by-line logic review found **zero logic bugs** (ROI/responseRate/avgCreatorScore null-safe, median has no off-by-one, k-anon both legs, IDOR single-path, PII stripped, fire-and-forget structurally enforced). Her first pass was CHANGES-REQUIRED on 3 QA-infrastructure gaps (missing eval sets, missing checklist, missing TECH-STACK.md); after the eval sets + checklist were delivered she revised to **APPROVED (code-complete milestone PASS)**, with TECH-STACK.md downgraded to non-blocking (Priya to resolve separately).

**Eval infrastructure — 36 pass / 2 skip.** `python -m pytest tests/evals -q`: the 5 existing datasets stay green; all new `provenance_exact_match` scorer unit tests pass and demonstrate red-ability (orphaned number, forbidden self-reported value, below-k/IDOR wildcard leak, self-reported fold-in, PII key leak). The **2 skips** are the two new end-to-end datasets — cases committed, **live fixtures not yet recorded** (gated on the Anthropic key). This is offline-green; it is **not** the gated `--live` zero-orphaned-numbers eval, which remains a carry-forward.

---

## 5. Reconciliation — git ground truth vs. the changes-logs

I diffed the ground-truth working tree against every §8 changes-log. **Every changes-log entry has a corresponding on-disk change** (no orphaned log claims). Three findings go the other way — on-disk changes the logs don't cleanly cover:

### 5.1 One genuine changes-log omission (benign)
- **`influora-ai/tests/prompt/test_assembler_context_wiring.py`** — a **+1-line** change adding `"outcome_digest"` to the expected `CONTEXT_PAYLOAD_FIELDS` set at line 169. This is a correct and necessary part of deliverable 2.1 (it keeps the assembler-contract test green), but it appears in **no §8 changes-log**. Benign — the change is right and is covered by the passing suite — but the backend changes-log is incomplete by this one file. Worth a one-line addendum to Vikram's log for a clean audit trail.

### 5.2 One unrelated pre-existing edit (out of Phase-2 scope)
- **`docs/reports/Influora-Feature-Audit-2026-07-18.html`** — a 47-line diff that is an **Ash post-audit correction dated 2026-07-21** (streaming-first fix, `MeeraChatAiClient.java` deletion, 500-char voice cap, score 78.4%→80.4%). It predates and is unrelated to the Phase-2 moat build, and is correctly absent from every Phase-2 changes-log. Flagging it only so it isn't mistaken for a moat change if these edits are committed together — **it should not ride in the Phase-2 commit.**

### 5.3 Branch / commit divergence (flag prominently)
- Every doc says branch **`feat/portfolio-view-tracking`**. The working tree is on **`feat/creator-ai-copilot`** (recent commits `c1b44b1`, `2e6970c` are Creator-AI-Copilot work). The **entire moat changeset is uncommitted** — working-tree-modified + untracked, sitting on top of the creator-copilot branch, not on the branch every review verified against. Before merge, someone must decide the intended target branch and stage the moat changeset cleanly. This is consistent with the known `project_branch_worktree_divergence` pattern but is a real pre-merge hazard.

### 5.4 Working-tree clutter (not Phase 2, should not be committed)
- 13 `*.log` files (`ai_dev.log`, `backend_dev.log`, `backend_docker_build*.log`, `backend_docker_run.log`, `frontend_dev.log`), `influora-api/.env.unix`, and `claude-skills/council/` are untracked and unrelated to the moat. The 5 untracked `wiki/ai-review/*.md` files are planning/source docs (the build plan and assessments), not code deliverables — correctly absent from the change table.

---

## 6. Carry-forwards / open items

| # | Item | Owner | Blocking? |
|---|---|---|---|
| CF-1 | **Live eval fixtures** — record `outcome_recommendation` (`--live --record`, needs `ANTHROPIC_API_KEY`) + dump `campaign_performance` fixtures from the Spring executor integration test. Until then the 2 eval skips stand and the `--live` zero-orphaned-numbers gate can't run. Gated on **Phase 0** key provisioning (Swapnil). | Swapnil (keys) → Ash/Vikram (record) | Live gate blocked; **not** merge-blocking per Kavya/Meera |
| CF-2 | **TECH-STACK.md at repo root** — absent on this branch (only in `_to_delete/` + worktrees). Kavya downgraded to non-blocking; Priya owns restoring it (she proposed copying from `integration-consolidate`). | Priya | No (process hygiene) |
| CF-3 | **Changes-log omission** — add `test_assembler_context_wiring.py` to Vikram's §8 log (§5.1). | Vikram | No |
| CF-4 | **Branch/commit divergence** — moat changeset uncommitted on `feat/creator-ai-copilot`, not `feat/portfolio-view-tracking`; strip unrelated HTML/log/env clutter before staging (§5.2–5.4). | Whoever commits | Pre-merge hazard |
| CF-5 | **QA-5 advisory** — outcome-digest logic (funded-from-signum, k-anon null, median even/odd, reachSource) has no dedicated unit tests; eval sets are the primary gate. Cheap follow-up. | Vikram | No |
| CF-6 | **`responseRate` ship-vs-cut** — Priya deferred the final call to Ash (§3). Ash lands it at his `--live` eval per the gate chain. | Ash | No |
| CF-7 | Non-blocking notes: relabel `responseRate` → "acceptance rate"; capture `deliverableId` on `REVISION_REQUESTED`; SR-2 forward-invariant if flywheel free text is ever re-surfaced into a prompt (Ash B4). | Vikram/Ash | No |

---

## 7. Sign-off chain — status and what remains

```
Design gate:   Priya APPROVED-WITH-CHANGES  ✅   +  Ash APPROVED-WITH-CHANGES  ✅   (converged on 2-state; no Swapnil escalation)
Implementation: Vikram (backend) + Ananya (frontend)                              ✅   (all B1–B5/F1–F3 + Q rulings folded in)
Impl review:   Priya  CHANGES-REQUIRED (D1 only) → D1 fixed                        ✅
QA gate:       Kavya  APPROVED (after QA-1/2/3 delivered; QA-4 downgraded)         ✅
Build gate:    Meera  BUILD-GREEN (70 touched tests, tsc clean, redaction parity) ✅
──────────────────────────────────────────────────────────────────────────────────
Security gate: Kabir  MANDATORY — k-anon on niche_rate_band, IDOR on              ⏳  RUNNING / NEXT
                       get_campaign_performance, no PII in flywheel
Eval gate:     Ash    --live, zero orphaned numbers (+ final responseRate call)   ⛔  BLOCKED on CF-1 fixtures/keys
Close-out:     Priya  post-Kabir — Block-B measured ≤2KB, cache-collision, cost   ⏳  PENDING Kabir
Final:         Swapnil                                                            ⏳  PENDING
```

**What remains, in order:**
1. **Kabir** runs the mandatory Phase-2 security audit (build-clean precondition satisfied). This is the immediate next gate.
2. **Priya** post-Kabir close-out: measure Block-B ≤2KB live, confirm no cache collision, sign the cost/turn line.
3. **Ash** `--live` eval — blocked until CF-1 (Anthropic key + recorded fixtures) lands; also lands the final `responseRate` ship-vs-cut call.
4. **Swapnil** final authority.

Merge is not cleared until Kabir passes and the branch/clutter hygiene of §5.3–5.4 is resolved. The single make-or-break invariant Priya named — **real RELEASED escrow, never the `FUNDED_STATUSES` proxy** — is implemented and verified clean (impl-review §1 item 1); everything else is sequencing and the live-key-gated eval.

---
*End of report — Tara, 2026-07-22.*
