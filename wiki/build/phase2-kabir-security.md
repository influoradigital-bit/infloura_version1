# Phase 2 — Kabir (Red-Team) MANDATORY Security Gate

Reviewer: Kabir (Offensive Security / Red-Team Lead) · 2026-07-22 · Gate: MANDATORY per build-plan §2.6
Branch: `feat/portfolio-view-tracking`
Scope: the ACTUAL changed Phase-2 code (outcome digest, `get_campaign_performance`, flywheel logging). Audited against the SR-1 / SR-2 / Info-Barrier invariants (build-plan §1). Priya's impl-review (`phase2-priya-impl-review.md`) was independently RE-verified on the security-critical items, not taken on trust.

Note: 10 sibling worktrees under `.claude/worktrees/` carry stale copies of these files. Every finding below is against the MAIN working tree only (`influora-api/src/main/...`, `influora-ai/app/...`).

---

## MANDATORY-GATE: **PASS**

No cross-party leak. No IDOR. No SR-1/SR-2 violation. All six mandatory checks PASS. Three LOW/INFO hardening items are recorded (none gate-blocking); one is a real follow-up owed against Info-Barrier guardrail 5 (retention).

| # | Invariant | Verdict |
|---|---|---|
| 1 | IDOR + PII on `get_campaign_performance` | **PASS** |
| 2 | k-anonymity on `niche_rate_band` | **PASS** |
| 3 | Flywheel PII / redactor / info-barrier | **PASS** |
| 4 | SR-1 — no self-reported trust | **PASS** |
| 5 | SR-2 — no untrusted content in instruction position | **PASS** |
| 6 | Cache-key — audience component, no global key | **PASS** |

---

## 1. IDOR on `get_campaign_performance` — PASS

Exploit attempted: caller (or an injected model) proposes a `campaign_id` belonging to another workspace, or a fabricated id, to read a foreign campaign's spend/reach/revenue or an existence oracle.

**The exploit fails, structurally:**
- Workspace is NEVER taken from the tool arg. `MeeraInternalController.getCampaignPerformance` (`MeeraInternalController.java:226-236`) resolves `ctx = resolveForWorkspaceRequiringScope(onBehalfJwt, workspaceId, ...)`, and `OnBehalfAuthResolver.resolveForWorkspace` (`OnBehalfAuthResolver.java:72-95`) asserts `token.workspaceId == body.workspace_id` (403 `ON_BEHALF_WORKSPACE_MISMATCH` otherwise). The executor is then called with `ctx.workspaceId()` — the JWT-verified value (`:234`), never the raw body.
- The ONLY resolve path is `campaignRepository.findByIdAndWorkspaceId(campaignId, workspaceId)` (`GetCampaignPerformanceExecutor.java:112-118`). It returns empty for BOTH "no such campaign" AND "exists but belongs to another workspace" → a single `CAMPAIGN_NOT_FOUND` **404** in both cases. There is no separate existence check, so **no 403/404 oracle** distinguishes the two. A cross-tenant probe and a typo are byte-identical.
- Every downstream read is keyed off the already-workspace-scoped `campaign.getId()` (collaborations `:120`, verified metrics `:123-129`, escrow `:131`, UTM `:136`, affiliate `:139-140`, creator scores `:222-251`). No repository call in this executor accepts an unscoped caller id.
- **No per-deliverable PII.** `DeliverablePerformanceEntry` (`MeeraToolDtos.java:89-91`) carries only `milestoneId` (opaque) + `reach/impressions/engagements` (numeric). No creator name, IG handle, caption, or any free-text field is constructed anywhere in the result (`:153-159`). `avgCreatorScore` is an aggregate mean, not a per-creator row.

**Attack surface swept:** no `stringArg` value other than `campaign_id` is read; `campaign_id` is used exclusively as a scoped-lookup key, never trusted as a value. The 400 `CAMPAIGN_ID_REQUIRED` fires before any lookup, so a blank id is not an oracle either.

## 2. k-anonymity on `niche_rate_band` — PASS

Exploit attempted: force a band to render over n=1..4 (a per-person rate disclosure in aggregate costume), or leak a raw candidate row.

**The exploit fails:**
- `buildRateBand` (`BrandContextAssembler.java:398-442`) computes distinct-creator AND distinct-workspace counts **over the same bucket the rates are drawn from** (the largest tier bucket, `:404-417`), then `if (distinctCreators < 5 || distinctWorkspaces < 5) return null` (`:418-420`, `RATE_BAND_K_ANON_FLOOR = 5`, `:263`). BOTH legs are enforced — n=1..4 on either leg returns `null`, never a partial band. Mentally walking n=1 creator / 5 workspaces → creators<5 → null; 5 creators / 1 workspace → workspaces<5 → null. Both closed.
- The counts are computed over the SAME `largestBucket` list that feeds min/median/max (`:422-441`) — there is no window where the k-count is drawn from a wider set than the rates (a classic k-anon bypass). Verified consistent.
- **No raw row escapes.** `RateBandCandidateRow` is a scalar-only projection (`CollaborationRepository.java:22-32`, no name/handle) and the grep for its usage confirms it is consumed ONLY inside `buildRateBand`; nothing serializes a row. Only the aggregated `RateBand` (min/median/max + niche + currency + two counts) leaves. Python renders only min/max/median/niche/currency into Block B (`assembler.py:249-260`) — the two sample-size counts are not even surfaced to the model.
- **Niche is server-derived, not caller-chosen.** `niche = brandProfile.nicheTagsJson`'s first tag (`MeeraContextService.java:216-218`) — a caller/model cannot pass an arbitrary niche to probe a rival. A rare self-set niche just returns `null` under the floor.
- Query is `status = 'COMPLETED'` only; `DISPUTED`/`CANCELLED` excluded (`CollaborationRepository.java:53`). Cross-tenant by design (that is WHY the floor exists) and correctly gated.

Accepted-risk note (not blocking, design-ruled): `min`/`max` are extreme individual rates within the ≥5-creator/≥5-workspace set; they are not attributable to a specific identity from the band alone. This is the intended range-statistic semantics, ruled acceptable by Priya/Ash.

## 3. Flywheel PII (`meera_interaction_log`) + `SensitiveTextRedactor` + info-barrier — PASS

- **Only one free-text column** (`revision_reason`) exists; every other field is an enum event-type, an id, a bool, or a version string (`MeeraInteractionLog` / migration `V20260721160000`). **No raw prompt/response body is ever stored** — verified across all five write points (`CreateCampaignExecutor` DRAFT_CREATED, `ConfirmLaunchExecutor` DRAFT_FUNDED, `BrandDeliverableService.requestRevision` REVISION_REQUESTED, `interaction-log` OPTIONS_PRESENTED, `option-tapped` OPTION_TAPPED).
- **Redaction is structural, not per-call-site.** `MeeraInteractionLogService.record` (`MeeraInteractionLogService.java:64`) redacts `revisionReason` via `SensitiveTextRedactor.redact` as its own first line; every write point calls only `record(...)`, never builds/saves the entity directly (grep-confirmed). No call site can skip it.
- **Redactor parity verified byte-for-byte against `redaction.py`.** All six patterns identical (PAN `\b[A-Z]{5}[0-9]{4}[A-Z]\b`; PHONE `(?<!\d)(?:\+?\d{1,3}[-.\s]?)?\d{10}(?!\d)`; BANK `\b\d{9,18}\b`; SECRET `sk-…|Bearer …|[A-Za-z0-9+/]{40,}={0,2}`; bare-JWT three-segment with the ≥20-char guard; EMAIL). Application ORDER identical: secret → bare-JWT → PAN → email → phone → bank (`SensitiveTextRedactor.java:70-75` == `redaction.py:103-108`). The greedy `\d{9,18}` bank pattern correctly runs LAST so 10-digit phone runs are claimed by PHONE first; PAN carries letters so it never collides with a digit-run pattern. No ReDoS (all quantifiers linear, no nested-quantifier catastrophe). The bounded `revision_reason` (HTML-stripped TEXT) is not a backtracking vector.
- **Info-barrier holds by construction.** `MeeraInteractionLogRepository` is the empty `JpaRepository` — **write-only, no read/analytics query exists**, so there is no joined view that could co-locate both sides' private data. The table only ever receives BRAND-workspace rows in this phase; `option-tapped` resolves workspace from the authenticated principal (see below), `interaction-log` persists under the JWT-verified `ctx.workspaceId()` (`MeeraInternalController.java:332-341`).
- **SR-2 forward-invariant honored:** `SensitiveTextRedactor`'s javadoc (`:20-27`) and the service (`:47-51`) both state redaction is PII-defense, NOT injection-defense, and that any future re-surfacing of this free-text into a prompt MUST additionally pass Python `_safe()`/`wrap_untrusted()`. Correct and documented at the exact seam.

## 4. SR-1 — no self-reported trust — PASS

- `spendInr` = `EscrowHoldRepository.sumAmountByCampaignIdAndStatus(campaignId, EscrowStatus.RELEASED)` — strictly `WHERE status = :status` (`EscrowHoldRepository.java:55-59`), passed `RELEASED` at BOTH call sites (`MeeraContextService.java:198`, `GetCampaignPerformanceExecutor.java:131`). **Never `FUNDED_STATUSES`.** Grep confirms the `FUNDED_STATUSES` EnumSet is defined and used ONLY in the untouched Phase-1 `past_campaign_summary` (`MeeraContextService.java:68,174`); every other occurrence is a comment. Neither new class imports it.
- `funded` = `spendInr.signum() > 0` (server-derived from released escrow), not a status proxy.
- `verifiedReach` / `reachSource` / `deliverables[]` filtered to `SOURCE_PLATFORM_VERIFIED` only (`BrandContextAssembler.java:343`, `GetCampaignPerformanceExecutor.java:128`). Self-reported reach is omitted (`null`), never surfaced.
- `roi`/`responseRate`/`avgCreatorScore` all pure server derivations off authoritative rows (`GetCampaignPerformanceExecutor.java:189-251`); zero tool-`input` read. `computeRoi` returns `null` on zero spend / null revenue — no divide-by-zero guess (`:189-193`).
- `provenance` tag is a server-set constant `PLATFORM_VERIFIED` (`:184`), never caller/LLM-supplied. The result DTO trusts no `amount` from the body (only `campaign_id`, an identifier).

## 5. SR-2 — no untrusted content in an instruction position — PASS

- `_render_outcome_digest` (`assembler.py:201-264`) wraps EVERY untrusted string sub-field through `_safe()`: `type` (`:230`), `niche` (`:251`), `currency` (`:252`). Numerics (`spend_inr`, `verified_reach`, `attributed_revenue_inr`, `min/median/max`) are server-computed `BigDecimal`/`int` off the wire (Jackson serializes as JSON numbers) — no injection surface — and are interpolated directly, consistent with `past_campaign_summary`. No raw-concatenation path found for any brand-authored string.
- Every nested field read via `.get()` (`:224-256`), never `[]` — a nested Java rename degrades to a missing line, not a `KeyError`-500 (Lock-3 gap mitigation), and cannot be steered by input.
- The flywheel free-text re-surfacing invariant is honored (see check 3): it is write-only today and the code documents the `_safe()` requirement if that ever changes.

## 6. Cache-key — audience component, no global key — PASS

`cache_key_for` (`assembler.py:400-412`) = `{prompt_version}:{audience}:{workspace_id}:{session_id}` — never global. `audience` is a component (build-plan Info-Barrier requirement), so a future CREATOR-audience turn cannot collide with a BRAND turn on the same workspace/session. `audience` is derived from `brand_context` (defaults BRAND) and, on the Spring side, `MeeraContextService.assemble` rejects any non-BRAND audience today (`MeeraContextService.java:107-114`) — a caller/model cannot smuggle a different audience into the key. `PROMPT_VERSION` bumped to `.9` (`config.py`), correctly invalidating the prior Block-B cache after the new field landed.

---

## LOW / INFO findings (none gate-blocking)

- **L1 (LOW, hardening) — retention not implemented.** Info-Barrier guardrail 5 says "set retention + access-scope day one." Access-scope is satisfied (BRAND-only, write-only). Retention is NOT: no purge/TTL job exists, so `meera_interaction_log` (incl. redacted `revision_reason`) grows unbounded. Given redaction + write-only + workspace-scoping there is no active leak, so this is a fast-follow, not a gate-block. **Required follow-up:** land the 180-day retention purge (design §3.6, owner Priya/Rohan) before the flywheel read query is ever built — the moment a read/join is added, retention stops being optional. File: `V20260721160000__meera_interaction_log.sql` + a scheduled purge.
- **L2 (LOW, hardening) — `interaction-log` `campaign_id` unvalidated.** `MeeraInternalController.interactionLog` (`:328-343`) persists a caller-supplied `campaign_id` with no ownership check. Because the row is scoped to the JWT-verified `ctx.workspaceId()` and the table is write-only, the worst case is a workspace polluting its OWN analytics — no cross-party leak, no read path. Add an ownership check when the funnel read is built.
- **INFO — comment drift in `PHONE_RE`.** Java comment says "10 digits" (`SensitiveTextRedactor.java:42`), Python says "10-15 digits" (`redaction.py:27`); both regexes are byte-identical (`\d{10}`). Cosmetic only — no functional parity gap. Align the comment when convenient.

---

## Gate decision

**MANDATORY-GATE: PASS.** The moat's security spine holds under active attack: `get_campaign_performance` is IDOR-closed and PII-free with no existence oracle; `niche_rate_band` cannot render below the n≥5 dual floor and leaks no raw row; the flywheel log stores no prompt bodies, redacts its one free-text field structurally, and is write-only so no cross-party join exists; every money/provenance value is server-derived from RELEASED escrow / PLATFORM_VERIFIED metrics; every untrusted string crosses into the prompt via `_safe()`; the cache key keeps `audience`. Proceed to Ash (`--live`, zero orphaned numbers) → Priya post-Kabir close-out → Swapnil. L1 (retention) is a required fast-follow, tracked but not gating.
