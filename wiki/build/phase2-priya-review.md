# Phase 2 — Priya (CTO) Architecture Review & Sign-off

Reviewer: Priya (CTO) · 2026-07-21 · Gate: DESIGN REVIEW (pre-implementation)
Source of truth: `wiki/ai-review/meera-label-to-moat-build-plan.md`
Docs reviewed:
- `wiki/build/phase2-backend-design.md` (Vikram) → **APPROVED-WITH-CHANGES**
- `wiki/build/phase2-frontend-design.md` (Ananya) → **APPROVED-WITH-CHANGES**

My tech decisions here are final unless Swapnil overrides. Every claim below marked "verified" was read against the real code on `feat/portfolio-view-tracking`, not rubber-stamped.

---

## 0. Verdicts at a glance

| Doc | Verdict | Blocking items | Nice-to-have |
|---|---|---|---|
| Backend (Vikram) | **APPROVED-WITH-CHANGES** | B1, B2, B3, B4, B5 | B6, B7, B8 |
| Frontend (Ananya) | **APPROVED-WITH-CHANGES** | F1, F2, F3 | F4, F5, F6 |

No REJECT. Both designs did the grounding pass I require — they caught the plan's own drift (Vikram §0.6 write-points, Ananya §0 routing) against real code, which is exactly why this gate exists. Implementation may start on the non-blocked slices (2.3 flywheel table, 2.1 backend) the moment the blocking items below are folded in.

---

## 1. Architecture-lock verification (the locks the plan names as mine)

### LOCK 1 — Block-B size ≤2KB/brand after outcome digest; cost/turn — **PASS (with a bound)**
The digest = `campaign_outcomes[]` (last-N, 7 numeric/short fields each) + one `niche_rate_band` object. Vikram's design reuses the existing `recent` list (`MeeraContextService.java:119-123`, `PAST_CAMPAIGN_LIMIT = 5`), so N is already bounded at 5. Worst case ≈ 5 × ~150B + ~150B ≈ **~0.9KB added** — under 2KB with headroom.
- **LOCKED CONSTRAINT (B1, blocking):** the digest MUST reuse `PAST_CAMPAIGN_LIMIT` (=5). No separate, larger limit for `assembleOutcomeDigest`. No free-text creator-authored field may enter the digest (campaign `type` is brand-authored template text, `_safe()`-wrapped — that's the only string; everything else is numeric). If either changes, the 2KB lock is void and this comes back to me.
- **Cost/turn:** ~+250–300 input tokens on a cache *miss*. Block B is prompt-cached and the key already includes audience+workspace+session (verified below), so steady-state marginal cost is the cached-read rate. The `PROMPT_VERSION` bump re-warms every cache once — a one-time, acceptable cost. Approved.

### LOCK 2 — Prompt-cache key never global; audience a component; PROMPT_VERSION bump — **PASS**
Verified `assembler.py:330-342`: `cache_key_for` returns `f"{prompt_version}:{audience}:{workspace_id}:{session_id or 'no-session'}"`. Audience is a first-class key component — a CREATOR turn can never collide with a BRAND turn's cache. Never global. `PROMPT_VERSION` is currently `"meera-2026.07.21.8"` (`config.py:69`); Vikram commits to the bump (§1.1, §4). **Approved as-is.**

### LOCK 3 — CI diff-check: every new field + tool has an explicit entry in BOTH sides — **PASS (mechanism verified) + one required change**
Verified `.github/workflows/schema-check.yml`: both diffs are **live extractions, BLOCKING**:
- Tool names: `TOOL_SCHEMAS` (Python) vs `MeeraToolName.java` grep — fails CI on mismatch (line 105, 165-170).
- Context fields: `CONTEXT_PAYLOAD_FIELDS` (`assembler.py`) vs `ContextResponse` `@JsonProperty` awk-extract — fails CI on mismatch (line 202-209).
The workflow needs no edit; the two-sided source edits self-trigger it. Vikram's §4 table commits to both sides for `outcome_digest` and `get_campaign_performance`. **Confirmed both designs commit.**
- **GAP (B2, blocking):** the CI diffs only the **top-level** context-field set and the **tool-name** set. It does **not** cover (a) nested DTO fields inside `OutcomeDigest`/`CampaignOutcomeEntry` (e.g. `spend_inr`, `reach_source`), nor (b) the frontend `MeeraFunctionCall` union name. So a nested Java rename or a frontend-name typo breaks silently at runtime, not build. Required mitigations:
  - Python `_render_outcome_digest` MUST access every nested field via `.get()`, never `[]` — a nested rename then degrades to a *missing line*, never a `KeyError`-500 at conversation start.
  - Frontend wire-name is a locked constant — see F1.

### LOCK 4 — Deny-by-default allow-list is the only gate; no column auto-flows — **PASS**
Verified `CONTEXT_PAYLOAD_FIELDS` (positive tuple, `assembler.py:51-66`) + `_FORBIDDEN_BRAND_FIELDS` (deny-list, defense-in-depth, `:68+`). Vikram adds `OUTCOME_DIGEST` as a new allow-listed section following the per-section pattern. A new DeliverableMetric/EscrowHold column does **not** auto-flow — only the explicitly named record fields leave.
- **REQUIRED (B3, blocking):** the three new records (`CampaignOutcomeEntry`, `RateBand`, `OutcomeDigest`) carry only their named scalar fields. **No `Map`, no `@JsonAnyGetter`, no entity passthrough.** The allow-list is the gate; keep it structural.

### LOCK 5 — SR-1 RELEASED-escrow: is `EscrowHold.status=RELEASED` SR-1-compliant without a `wallet_transactions` join? — **ACCEPTED. Vikram is right.**
Verified `EscrowHold.java`: `markReleased(releaseTxnId)` (`:159-164`) is the sole writer of `status=RELEASED`, and it records `releaseTxnId` — the ledger credit leg. Status is server-set exclusively on a real release; `amount` is persisted (`precision=14,scale=2`), never caller-supplied. **`EscrowHold.status = RELEASED` IS the server-derived, ledger-backed fact.** A `wallet_transactions` join would be redundant — the status field already encodes "the credit leg posted." Vikram's simplification is not a deviation; it's the *more* correct read (single authoritative field, fewer joins, one write path). **This satisfies SR-1.**
- **REQUIRED (B4, blocking):** the sum query is `SUM(amount) WHERE campaign_id = X AND status = RELEASED` — strictly `RELEASED`, never including `FUNDED`/`PENDING`/`FROZEN`/`REFUNDED`. And `funded = spendInr.signum() > 0` (real released money), with **zero import of `FUNDED_STATUSES`** into `BrandContextAssembler` or `GetCampaignPerformanceExecutor`. (`FUNDED_STATUSES` at `MeeraContextService.java:56` is the Phase-1 `past_campaign_summary` proxy — verified still live; it must not leak into the moat. This is the SR-1 landmine at the core of the whole build — CI/QA must assert the symbol is absent from both new classes.)

### LOCK 6 — k-anonymity floor = 5 query shape — **PASS (with a rejection)**
Vikram §1.4: two legs — `distinct(creatorId) ≥ 5 AND distinct(workspaceId) ≥ 5`, else return `null`; both counts always computed and both carried on `RateBand` for auditability; `status = 'COMPLETED'` only (excludes `DISPUTED`/`CANCELLED`); no row leaves the query — only the aggregate is assigned to a response field. **Shape approved.** This is a **cross-tenant, platform-wide** aggregate — the single most security-sensitive query in the design; the k-anon floor on BOTH legs is the only thing standing between it and a per-counterparty disclosure. It stays a **mandatory Kabir gate** (already in plan §2.6) — blocking for *merge*, not for design sign-off.
- **DECISION on the backoff ladder (Vikram §1.4 step 7 / open Q2): REJECTED for v1.** Ship the naive single `(niche × tier)` grouping; return `null` more often. A niche-only fallback mixes nano and macro rates into one "market" band (misleading) and every fallback widens the re-identification surface. If a fallback is ever added later, the k-anon floor must re-check on the *fallback* bucket, not the original — but not now.

### LOCK 7 — Java `SensitiveTextRedactor` port vs the plan's `redaction.py` instruction — **ARCHITECTURALLY SOUND. Approved.**
Verified `redaction.py`: it is a **Python logging formatter** (`RedactionJsonFormatter`, `configure_logging`) — it cannot be called from the Java write points (`BrandDeliverableService`, `ConfirmLaunchExecutor`). The plan's "run free-text through `app/security/redaction.py`" is genuinely unworkable cross-language. Vikram's Java port of the regex backstop is the correct resolution.
- **REQUIRED (B5, blocking):**
  - Port **all** patterns, not just the 4 PII ones: include `_SECRET_RE` and `_JWT_SEGMENT_RE` (bare JWT) — a brand can paste anything into a revision reason.
  - **Preserve `scrub_text`'s exact ordering** (`redaction.py:101-109`): secret → bare-JWT → PAN → email → phone → bank-account. Order is load-bearing — `_BANK_ACCOUNT_RE` (`\d{9,18}`) is greedy and will eat PAN/phone digits if run first.
  - **Cross-language parity test is REQUIRED, not nice-to-have** (Vikram's own §7.7, which he flagged as "more Kavya than Priya" — I'm ruling it blocking). There is no CI diff-check for regex parity; a silent divergence means PII persists into a stored analytics table. Same fixture strings + same expected redactions, run in both the Python and Java suites. Cheap; blocking for merge of 2.3.

---

## 2. Cross-cutting decisions (the 5 open questions — decisions, not maybes)

### Q1 — Provenance enum: 2-state vs 3-state → **2-STATE: `PLATFORM_VERIFIED | SELF_REPORTED`. For the whole outcome/performance contract.**
Grounded in the actual data source: `DeliverableMetric` (`:33-36`) defines exactly two source values (`CREATOR_REPORTED`, `PLATFORM_VERIFIED`) with a fail-closed write path (`applyReport` never downgrades a verified row; `applyVerifiedReport` only moves toward verified). Escrow `RELEASED` and UTM revenue are likewise binary server-facts. **There is no such thing as an "inferred" outcome number — you do not infer an escrow release or a platform-fetched reach.** The 3-state `scraped/structured/inferred` vocabulary (from `price_source`, `d3d1ab7`) belongs to **intake** (product prices), a different domain that does not gate this contract.
- Consequence: per Vikram §1.3, v1 surfaces **only** `PLATFORM_VERIFIED` numbers; `SELF_REPORTED` is omitted (`null`), not surfaced-with-flag. Approved (Vikram open Q1 → ship the conservative reading).
- Consequence for Ananya: `roiSource`/`responseRateSource`/`avgCreatorScoreSource` are typed `'PLATFORM_VERIFIED' | 'SELF_REPORTED'` — **drop `'INFERRED'`**. See Q2 for how this collapses to a single tag.

### Q2 — `GetCampaignPerformanceResult` DTO shape (Ananya's blocker) → **RULED. This is the contract.**
Vikram's §2.4 returns raw aggregates; Ananya's §3 needs display metrics. They didn't line up — here's the reconciliation, and it holds SR-1 by computing every derived number **server-side** (the frontend never divides money):

```java
public record DeliverablePerformanceEntry(
        String milestoneId, Long reach, Long impressions, Long engagements) {}

public record GetCampaignPerformanceResult(
        String campaignId,
        long creatorCount,
        BigDecimal spendInr,                 // SUM(EscrowHold.amount) WHERE status=RELEASED
        Long verifiedReach,                  // SUM over DeliverableMetric SOURCE_PLATFORM_VERIFIED only; null if none
        BigDecimal attributedRevenueInr,     // SUM(UtmCampaign.revenueAttributed)
        BigDecimal settledCommissionInr,     // SUM(AffiliateEarning SETTLED)
        BigDecimal roi,                      // SERVER-COMPUTED = attributedRevenueInr / spendInr (ratio, 1.4 = +40%); null if spend==0 or revenue null
        Double responseRate,                 // SERVER-COMPUTED 0..1 = accepted invites / total invites; null if no invites
        Double avgCreatorScore,              // 0..100, avg CreatorScore over campaign creators; null if none
        String provenance,                   // "PLATFORM_VERIFIED" | "SELF_REPORTED" — single top-level tag (v1 always PLATFORM_VERIFIED)
        List<DeliverablePerformanceEntry> deliverables) {}
```
Rulings inside this:
- **`roi` is server-computed, not frontend-derived.** Dividing revenue by spend is a money-adjacent derivation — SR-1 discipline extends to it. Ratio shape (Ananya Q2 answered: **ratio**, `1.4` = 140% return).
- **`responseRate` is `0..1`** (Ananya Q3 answered). Server-computed from Collaboration invite/accept state.
- **`avgCreatorScore` is `0..100`** (Ananya's confirm-request answered: yes, same scale as existing `CreatorScoresResponse`).
- **`provenance` is a SINGLE top-level 2-state tag, not per-field.** Because v1 surfaces only verified numbers (self-reported omitted), every surfaced number is `PLATFORM_VERIFIED` by construction — per-field tags would be redundant. The badge therefore renders **quietly (nothing)** for the whole performance card in v1; it activates only if/when `SELF_REPORTED` numbers are ever surfaced (a future decision). Ananya: type it as one field, not three `*Source` fields.
- **`narrative` is NOT in the DTO.** Drop it (Ananya Q on §3). The one-sentence narrative is **Meera's own LLM turn** streaming in the chat bubble, grounded on these numbers — not a deterministic server string echoed into the card. This is what makes Ash's provenance eval work: the LLM quotes numbers that appear verbatim in this tool result. "Card carries numbers (this DTO), bubble carries narrative (LLM text)" — exactly the plan's split.
- `campaignId` **is** round-tripped (for the deep link). Confirmed needed.

### Q3 — Where `OPTIONS_PRESENTED` / `OPTION_TAPPED` get written from → **RULED.**
- **`OPTIONS_PRESENTED`: Option (a) — new mesh-gated internal Spring endpoint** `POST /internal/meera/interaction-log`, called fire-and-forget from Python. Verified `present_options` is a Python LOCAL tool (`schemas.py:58-59`, `LOCAL_TOOL_NAMES`) that never reaches Spring, so a write path is genuinely needed. **One store (`meera_interaction_log`), one source of truth** — Vikram's option (b) splits the funnel across two DBs and defeats the "options→tapped→draft→funded" funnel query. Constraints: inherits the same dual-credential mesh (service token + HMAC) as every other `/internal/meera/*` route; `workspace_id` is derived from the on-behalf principal, **never** trusted from the Python-supplied body; the Python call is wrapped in try/except with a short timeout and **must never fail or block the turn**.
- **`OPTION_TAPPED`: normal authenticated brand endpoint** `POST /workspaces/{workspaceId}/meera/interactions/option-tapped` (Vikram's proposal approved), **with the IDOR fix:** the service resolves `workspace_id` from the **authenticated principal**, not the path param — a brand must not be able to log against another workspace's id. Fire-and-forget to `MeeraInteractionLogService.record(...)`. No internal mesh (this is a real browser request, not Python→Spring). This is the endpoint Ananya is blocked on — path + shape are now firm.

### Q4 — `DRAFT_ABANDONED` (no trigger today) → **DROP from the v1 enum.**
Verified via Vikram §0.6: `CampaignIntent.abandon()` is dead code (zero call sites). Do **not** build an unscoped staleness/abandonment job under this ticket. Because `event_type` is a `VARCHAR` (not a DB enum, per §3.2), re-adding the value later is a pure app-layer change — **no migration**. v1 enum = `OPTIONS_PRESENTED, OPTION_TAPPED, DRAFT_CREATED, DRAFT_FUNDED, REVISION_REQUESTED`. Add a forward-compat comment. Rationale: a live enum value that never fires is worse than absent — it reads as "we measured zero abandonment" when we measured nothing. A real staleness trigger is a separate, scoped follow-up ticket.

### Q5 — Frontend routing: `MEERA_FUNCTION_CALLS` vs `ToolResultRenderer` → **Ananya's correction CONFIRMED.**
Verified in `MeeraChatPanel.tsx`: `MEERA_FUNCTION_CALLS` (`:101-107`) is the real advancement gate; `onFunctionCall`→`advance` fires from the `onToolResult` handler (`:529-531`). `ToolResultRenderer` is a pure presentational dispatcher and never touches the stage machine. The plan's "add a case in `ToolResultRenderer` that advances" was **wrong**; Ananya's corrected 4-file change (`stage-config.ts`, `meera-copy.ts`, `MeeraChatPanel` gate array, `LivingCanvas` branch) is **the correct mechanism.** Approved.
- **Critical, verified (feeds F1):** `onToolResult` early-returns at `:498` (`if (!isStageCall && event.name !== 'present_options') return`) — a wire-name mismatch **drops the tool result entirely**, it doesn't just fail to advance. And the CI diff-check covers Python↔Java only, **not** the frontend union. So the frontend name has zero automated protection.

---

## 3. Required changes — Backend (Vikram)

**BLOCKING:**
- **B1** — `assembleOutcomeDigest` MUST reuse `PAST_CAMPAIGN_LIMIT` (=5); no separate/larger N; no free-text creator field in the digest. (Lock 1.)
- **B2** — `_render_outcome_digest` accesses every nested field via `.get()`, never `[]`, so a nested rename degrades to a missing line, not a 500. (Lock 3 gap.)
- **B3** — `CampaignOutcomeEntry`/`RateBand`/`OutcomeDigest` carry only named scalar fields; no `Map`/`@JsonAnyGetter`/entity passthrough. (Lock 4.)
- **B4** — Escrow sum strictly `status = RELEASED`; `funded = spendInr.signum() > 0`; zero `FUNDED_STATUSES` import in either new class; add a CI/unit assertion that the symbol is absent. (Lock 5 / plan landmine #1.)
- **B5** — `SensitiveTextRedactor`: port all patterns incl. `_SECRET_RE` + `_JWT_SEGMENT_RE`; preserve `scrub_text` ordering exactly; add the cross-language parity test (blocking for 2.3 merge). (Lock 7.)
- Adopt the ruled `GetCampaignPerformanceResult` shape from Q2 (adds server-computed `roi`, `responseRate`, `avgCreatorScore`, single `provenance` tag; no `narrative`).
- Implement the two Q3 write paths with the stated auth/IDOR constraints; drop `DRAFT_ABANDONED` per Q4.

**NICE-TO-HAVE:**
- **B6** — `reachSource` conservatism (Vikram Q1): ship PLATFORM_VERIFIED-only. **Ruled: yes** (follows Q1). Not a change, a confirmation.
- **B7** — Retention (Vikram Q6): **180 days approved as policy**, Rohan signs the storage-cost line. The purge **job** is a fast-follow, not a v1 blocker — the table won't overflow in 180 days of early traffic. Document the policy in the migration comment now.
- **B8** — `niche_rate_band` backoff ladder: **rejected for v1** (Lock 6) — ship naive. Not a change to make; a decision to record.

## 4. Required changes — Frontend (Ananya)

**BLOCKING:**
- **F1** — Treat the tool wire-name as a locked constant, byte-identical across `schemas.py` / `MeeraToolName.java` / the `MeeraFunctionCall` union: **`get_campaign_performance`** (the §2.2 spelling, not the §2.4 shorthand `campaign_performance`). A mismatch silently drops the result (`MeeraChatPanel.tsx:498`) with no CI protection on the frontend side. Add a source comment pointing at the CI-covered pair so a future edit knows the frontend name rides along uncovered.
- **F2** — `EscrowPill` for the `performance` stage: add `'performance'` to `escrowStateForStage()` returning the **released/secured** state, **not** the `'unfunded'` fallback (Ananya §5.4 / Q4). Showing "unfunded" on a completed, funds-released campaign is a correctness bug, not cosmetic.
- **F3** — Adopt the ruled DTO/types from Q2: `roi` ratio, `responseRate` 0..1, `avgCreatorScore` 0..100, single 2-state `provenance` (drop the three `*Source` fields and `'INFERRED'`), no `narrative` field (narrative comes from the chat bubble / LLM turn).

**NICE-TO-HAVE:**
- **F4** — One badge component (`EstimateBadge` with a `confidence` prop), not two — **approved** (Ananya Q3). Matches `ThemeProvenanceBadge` precedent. Location `src/components/feature/meera/` is fine. Note it is **dormant** in v1 (nothing to flag while all numbers are PLATFORM_VERIFIED) — build it quiet, don't over-invest.
- **F5** — Skip the inline `ToolResultRenderer` card for this tool — **approved** (Ananya Q5). Canvas card + chat narrative cover it.
- **F6** — "See full breakdown" link: **link to `/brand/analytics`** for v1 (Ananya Q6 option a); a campaign-scoped `/brand/analytics/campaign/:id` view is a separate follow-up ticket. Non-blocking either way.

---

## 5. CTO-domain follow-ups I own (not blocking either doc)

- **TECH-STACK.md is absent from the repo root on `feat/portfolio-view-tracking`** (Ananya flagged, top of her doc + §7.7) — it lives only in `_to_delete/` and worktree copies. This is a branch/worktree-divergence artifact (consistent with the `project_branch_worktree_divergence` memory), not an intentional removal. **I will restore `TECH-STACK.md` to the working-branch root** so it stops being a per-ticket "which convention doc do I trust" tax. Ananya did the right thing grounding conventions in live sibling components instead of the `_to_delete/` copy — that's the correct call and does not block Phase 2.

---

## 6. Sign-off

- **Backend design (Vikram): APPROVED-WITH-CHANGES** — fold in B1–B5 and the Q2/Q3/Q4 rulings. The grounding pass was excellent (escrow-status simplification is the *right* read of SR-1; the write-point drift corrections are verified accurate).
- **Frontend design (Ananya): APPROVED-WITH-CHANGES** — fold in F1–F3. The routing-drift correction is verified and is the correct mechanism; do not implement against the plan's `ToolResultRenderer` framing.
- **Gate chain unchanged** (plan §2.6): Kavya QA → Meera build → **Kabir MANDATORY** (k-anon on `niche_rate_band`, IDOR on `get_campaign_performance`, no PII in flywheel) → Ash eval (--live, zero orphaned numbers) → **me** (post-implementation: Block-B measured ≤2KB, cache-collision, cost/turn) → Swapnil.
- The single make-or-break item remains SR-1 at the moat's core: **real RELEASED escrow, never the status proxy** (B4). Everything else is sequencing.

Implementation may begin on 2.3 (flywheel table/service, once B5 lands) and 2.1 backend in parallel. 2.4 frontend unblocks the moment the Q2 DTO is implemented against F1–F3.
