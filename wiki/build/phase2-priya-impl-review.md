# Phase 2 — Priya (CTO) IMPLEMENTATION Review & Gate

Reviewer: Priya (CTO) · 2026-07-22 · Gate: IMPLEMENTATION REVIEW (Opus, pre-Meera/Kavya)
Branch: `feat/portfolio-view-tracking`
Design-stage sign-off this verifies against: `wiki/build/phase2-priya-review.md` (B1–B5 / F1–F3, Q1–Q5 rulings) + `wiki/build/phase2-ash-review.md`.

Every line below was read against the **real changed code**, not the §8 changes-logs. Where a claim is "confirmed," I quote the file:line I confirmed it at.

---

## VERDICT: **CHANGES-REQUIRED** (one blocking defect, D1 — narrow)

The moat's entire security/grounding spine is implemented correctly. The single blocker is a frontend↔backend serialization-contract mismatch on the `roi === null` case that makes the performance card silently never render for zero-spend / no-revenue campaigns. It is a 1-line fix and touches **no** security or grounding invariant.

**Gate: LOOP AGAIN — but a NARROW, single-item loop (D1 only).** After D1 is fixed, this proceeds straight to Meera/Kavya. Nothing else needs re-work.

---

## 1. Verification of the locked/blocking items (all PASS)

| # | Item | Result | Evidence |
|---|---|---|---|
| 1 | **SR-1 make-or-break: digest joins `RELEASED`, never `FUNDED_STATUSES`** | **PASS** | `EscrowHoldRepository.sumAmountByCampaignIdAndStatus` (`EscrowHoldRepository.java:55-59`) is `WHERE e.status = :status`; both call sites pass `EscrowStatus.RELEASED` (`MeeraContextService.java:198`, `GetCampaignPerformanceExecutor.java:131`). Grep for `FUNDED_STATUSES` across both new classes: **comment-only** (`BrandContextAssembler.java:257`, `GetCampaignPerformanceExecutor.java:41`, `MeeraContextDtos.java:65`). The **only** live use of `FUNDED_STATUSES` is the untouched Phase-1 `past_campaign_summary` proxy (`MeeraContextService.java:68 def, :174 use`) — exactly where Priya-review said it must stay. The digest path is clean. |
| 2 | **k-anon floor = 5, both legs, null below** | **PASS** | `BrandContextAssembler.buildRateBand:418` — `if (distinctCreators < 5 OR distinctWorkspaces < 5) return null;` `RATE_BAND_K_ANON_FLOOR = 5` (`:263`). Both counts computed over the largest tier bucket (`:410-417`); no row is serialized (only min/median/max + 2 counts leave). Query is `status = 'COMPLETED'` only, `DISPUTED`/`CANCELLED` excluded (`CollaborationRepository.java:53`). No backoff ladder — naive single niche+tier, as ruled. |
| 3 | **B1 — roi/responseRate/avgCreatorScore all server-computed, nothing in React/prompt** | **PASS** | `computeRoi` (`GetCampaignPerformanceExecutor.java:189`), `computeResponseRate` (`:205`), `computeAvgCreatorScore` (`:222`) — all pure server derivations off authoritative rows, zero tool-`input` read. Frontend `StagePerformance.tsx` renders only tool-returned fields; doc comment (`:28-32`) and `meera-api.ts:243` explicitly forbid `revenue/spend` in React. |
| 4 | **B2 — `_safe()` on every untrusted digest sub-field** | **PASS** | `assembler.py:_render_outcome_digest` — `type` (`:230`), `niche` (`:251`), `currency` (`:252`) all `_safe()`-wrapped; numerics interpolated directly. Every nested read is `.get()` (Lock-3 gap mitigation), never `[]`. |
| 5 | **B3 — `SensitiveTextRedactor` complete port + real parity test** | **PASS** | `SensitiveTextRedactor.java` ports all 6 patterns incl. bare-JWT (`JWT_SEGMENT_RE:55`, `scrubBareJwts:85`) with the ≥20-char guard mirrored. `redact()` order (`:70-75`) = secret → bare-JWT → PAN → email → phone → bank — byte-identical to `redaction.py::scrub_text:103-108`. `SensitiveTextRedactorTest.java` uses the **verbatim** fixtures from `test_redaction.py` (same `FAKE_PAN`/`FAKE_PHONE`/`FAKE_BANK_ACCOUNT`/`FAKE_EMAIL`/`FAKE_BARE_JWT` and same input strings incl. the over-match guard `v1.2.3 / api.example.com`). |
| 6 | **B4/IDOR — `findByIdAndWorkspaceId`, 404 not 403, PII stripped** | **PASS** | `GetCampaignPerformanceExecutor.java:112-118` — single resolve call, `CAMPAIGN_NOT_FOUND` 404 on miss OR cross-tenant; no separate existence check. `DeliverablePerformanceEntry` (`MeeraToolDtos.java:90-91`) = opaque `milestoneId` + numerics only, no name/handle/caption (`:151-159`). |
| 7 | **CI diff-check — new field + 6th tool on BOTH sides (incl. nested)** | **PASS** | `outcome_digest` in `assembler.py:CONTEXT_PAYLOAD_FIELDS:60` **and** `MeeraContextDtos.ContextResponse` `@JsonProperty("outcome_digest"):136`. `get_campaign_performance` in `schemas.py` (`TOOL_NAMES:44`, `TOOL_TIERS:74`, `TOOL_TO_SPRING_PATH:84`, `TOOL_SCHEMAS:199`), `MeeraToolName.java:10`, `ToolCallValidator.java:44` (R-tier). Nested `OutcomeDigest`/`RateBand`/`CampaignOutcomeEntry` fields are NOT CI-covered by design — mitigated by the Python `.get()` discipline (item 4). |
| 8 | **PROMPT_VERSION → `.9`, audience still in cache key** | **PASS** | `config.py:69 = "meera-2026.07.21.9"` with the bump rationale comment. `cache_key_for` unchanged (`assembler.py:400-412`) = `{prompt_version}:{audience}:{workspace_id}:{session_id}`. |
| 9 | **F1 — frontend wire-name byte-identical** | **PASS** | `stage-config.ts:26` union + `:77` trigger = `'get_campaign_performance'`; `MEERA_FUNCTION_CALLS` gate includes it (per §8 log, MeeraChatPanel). Matches `schemas.py`/`MeeraToolName.java` exactly. Locked-constant comment present (`stage-config.ts:18`). |

**B3 structural (Lock 4):** `CampaignOutcomeEntry`/`RateBand`/`OutcomeDigest` (`MeeraContextDtos.java:74-112`) carry only named scalar `@JsonProperty` components — no `Map`, no `@JsonAnyGetter`, no entity passthrough. Confirmed.

---

## 2. BLOCKING DEFECT

### D1 — `roi === null` is dropped on the wire; performance card never renders for zero-spend / no-revenue campaigns. **BLOCKING.**

**Root cause — a contract mismatch between the two implementations:**
- Backend represents "no ROI" as **field-absent**: `GetCampaignPerformanceResult` carries class-level `@JsonInclude(JsonInclude.Include.NON_NULL)` (`MeeraToolDtos.java:109`), and `computeRoi` returns `null` on zero spend or null revenue (`GetCampaignPerformanceExecutor.java:189-193`). Jackson therefore **omits** `roi` from the JSON entirely in that case.
- Frontend represents "no ROI" as **explicit JSON `null`**: the type guard requires `(d.roi === null || typeof d.roi === 'number')` (`src/lib/meera-api.ts:343`). When `roi` is omitted, `d.roi` is `undefined` → both clauses are false → **`isCampaignPerformancePayload` returns `false`**.

**Manifestation:** `StagePerformance.tsx:56` — on a `false` guard the component returns `<StageLoadingState>` and **stays on the loading spinner forever**. Worse, the dedicated placeholder branch built for exactly this case (`StagePerformance.tsx:69-73`, `roi === null` → "not enough data yet") is **dead code** — the payload never passes the guard to reach it. A campaign with no released escrow spend or no UTM-attributed revenue is a normal, common early-state, so this is not a rare edge.

**No security/grounding impact** — Meera's chat-bubble narrative still streams; only the canvas numbers card is stuck. But a flagship user-facing surface silently failing to render is a blocker for this gate.

**Required fix (pick one; backend preferred — 1 line, keeps both sides' existing `number | null` contract):**
- **Backend (preferred):** force `roi` to serialize as explicit `null`. Annotate the record component: `@JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal roi` in `GetCampaignPerformanceResult` (`MeeraToolDtos.java:117`). Python passes `None`→`null` through unchanged, so the frontend's existing `roi: number | null` guard then works as written. (`verifiedReach` stays NON_NULL-omitted — frontend already types it optional and does not guard on it, so it is unaffected.)
- **OR Frontend (2 lines):** broaden the guard at `meera-api.ts:343` to `(d.roi === null || d.roi === undefined || typeof d.roi === 'number')`, AND change `StagePerformance.tsx:69` from `roi === null` to `roi == null` (loose) so an omitted `roi` also lands on the placeholder branch instead of `fmtRoi(undefined)`.

Do **one** of these, not both. Re-run `tsc --noEmit` (frontend) / the Java build after.

---

## 3. Rulings on Vikram's 3 flagged v1 judgment calls

### (a) `responseRate` derivation — **APPROVE** (derivation is sound; Ash co-signs the scope nuance)
`computeResponseRate` (`GetCampaignPerformanceExecutor.java:205-213`): numerator/denominator over `source == INVITATION` collaborations only; `accepted = status ∉ {INVITED, CANCELLED}`; `null` when no invites.
I checked this against the **real** `CollaborationStatus` enum (`CollaborationStatus.java`): `INVITED, APPLIED, SHORTLISTED, IN_NEGOTIATION, TERMS_AGREED, CONTRACT_PENDING, CONTRACTED, IN_PROGRESS, REVIEW_PENDING, REVISION_REQUESTED, COMPLETED, CANCELLED, DISPUTED`. Critically, **there is no `DECLINED`/`REJECTED` state** — the failure mode I was worried about (a declined invite mis-counted as "accepted") cannot occur: a non-converting invite stays `INVITED` (excluded) or goes `CANCELLED` (excluded). Every status counted as "accepted" (`IN_NEGOTIATION`…`COMPLETED`, and even `DISPUTED`/`REVISION_REQUESTED`) genuinely represents a creator who engaged past the initial invite. The number is server-computed from authoritative rows (no model input) → SR-1-clean, not an orphaned/hallucinated figure.
- **Non-blocking nit:** it is technically an *invitation-acceptance / conversion* rate, not a "response" rate (a `CANCELLED` invite sits in the denominator). "Response rate" is a defensible colloquial label and is not misleading enough to block or to risk a Meera hallucination — but consider relabeling the UI/field to "acceptance rate" in a follow-up for precision.
- **Ash defer:** Ash holds AI-grounding co-authority and flagged this exact item in his B1 ("defined server-derivation OR cut for v1"). I rule the derivation **correct and shippable** on grounding. If Ash prefers to *cut* it for pure lean-scope reasons, that is a scope call, not a correctness one, and it is zero-cost on the frontend (`responseRate` is already typed optional; the tile is skipped when absent — `StagePerformance.tsx:61,77`). **This is the one call where I defer the final ship/cut to Ash;** my grounding verdict is APPROVE.

### (b) `meera_interaction_log.prompt_version` made nullable — **APPROVE**
`OPTIONS_PRESENTED` (Python-originated) stamps a real `PROMPT_VERSION`; the three pure-Java business-state events (`DRAFT_CREATED`/`DRAFT_FUNDED`/`REVISION_REQUESTED`) have no live AI-turn prompt context server-side. An honest `NULL` is strictly better data than a fabricated sentinel forced into a `NOT NULL` column — a sentinel would corrupt any future "which prompt revision drove funding" analysis. The funnel's prompt attribution is **not lost**: `DRAFT_FUNDED` is correlatable back to the `OPTIONS_PRESENTED` row's `prompt_version` via `session_id`, so nullability here costs nothing. Migration documents the departure clearly (`V20260721160000__meera_interaction_log.sql:21-26`). Good call.

### (c) `REVISION_REQUESTED.campaign_id` left null — **APPROVE for v1** (with one non-blocking note)
Acceptable because (i) `REVISION_REQUESTED` is outside the core `OPTIONS_PRESENTED→TAPPED→DRAFT→FUNDED` conversion funnel; (ii) the captured signal — `workspace_id` + redacted `revision_reason` + timestamp — is independently useful ("what do brands ask creators to change"); (iii) v1 is write-only (`MeeraInteractionLogRepository` has no read query), so nothing depends on the join yet.
- **Non-blocking note:** as written, the row stores **no** deliverable/collaboration reference at all, so these events are permanently campaign-**un**attributable (can't be backfilled later). `requestRevision` already has `deliverableId` in hand at the write site — capturing it (nullable column) would preserve future `deliverable→collaboration→campaign` resolution at ~zero cost. Optional; do it only if campaign-level revision-friction analysis is a near-term intent. Not required for v1 sign-off.

---

## 4. Gate decision

**LOOP AGAIN — narrow, D1 only.** Fix D1 (1 line, backend-preferred), re-run the build/`tsc`, then **PROCEED TO MEERA/KAVYA** with no further Priya pass required for the rest. Do **not** re-open B1–B5 / F1–F3 or the DTO/query/redaction work — all verified clean.

Downstream gate chain is unchanged (plan §2.6): Kavya QA → Meera build → **Kabir MANDATORY** (k-anon on `niche_rate_band`, IDOR on `get_campaign_performance`, no PII in flywheel) → Ash eval (`--live`, zero orphaned numbers; Ash also lands his final ship/cut call on `responseRate` per §3a) → my **post-Kabir** close-out (Block-B measured ≤2KB, cache-collision, cost/turn) → Swapnil.

Judgment-call summary: **(a) APPROVE** (derivation sound; Ash owns the ship-vs-cut scope nuance) · **(b) APPROVE** · **(c) APPROVE for v1**.
