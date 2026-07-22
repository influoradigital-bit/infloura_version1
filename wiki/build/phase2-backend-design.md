# Phase 2 Backend Design — Outcome Grounding (2.1 / 2.2 / 2.3)

Author: Vikram (backend) · 2026-07-21 · Status: **DESIGN — no implementation yet**, awaiting Priya + Ash sign-off.
Source of truth: `wiki/ai-review/meera-label-to-moat-build-plan.md` §Phase 2 (2.1/2.2/2.3), guardrails in §1.
Scope (Swapnil): 2.1 outcome digest, 2.2 `get_campaign_performance`, 2.3 `meera_interaction_log`. 2.4 (frontend), 2.5 (QA), 3.x are out of scope for this doc — this is backend-only, but §7 lists what Ananya/Kavya need from this design.

---

## 0. Grounding pass — what the real code says vs. the plan's claims

Verified every file/line the plan cites. Findings:

1. **`FUNDED_STATUSES` landmine confirmed live.** `MeeraContextService.java:56` still has `EnumSet.of(ACTIVE, PAUSED, COMPLETED)` as the `past_campaign_summary.funded` proxy. It is **not touched by this design** (past_campaign_summary is a Phase-1 field, out of scope) — but the new `campaign_outcomes[].funded` field this design adds must **never** reuse that set. See §1.3.
2. **`EscrowHold` already carries `status=RELEASED` as a first-class, narrowly-written field** — `markReleased()` (`EscrowHold.java:159`) is called from exactly one place, `EscrowService` (4 call sites, all paired with a `WalletLedgerService`-posted credit leg — `posting.creditLeg()`). So "join real released escrow" does **not** require joining `wallet_transactions` at all — `EscrowHold.status = RELEASED` IS the server-derived, ledger-backed fact. Simpler than the plan's "via WalletLedgerService txns" phrasing implied; noted as a simplification, not a deviation, in §7.
3. **`DeliverableMetric.source`** (`SOURCE_PLATFORM_VERIFIED` / `SOURCE_CREATOR_REPORTED`) is exactly the provenance field the plan wants, and it has a **fail-closed write path** (`applyVerifiedReport` only ever moves `CREATOR_REPORTED → PLATFORM_VERIFIED`, never back) — safe to trust as SR-1-compliant provenance.
4. **`price_source` precedent confirmed reusable as the pattern to copy**, not the field itself: `BrandContextAssembler.PRODUCT_CATALOG_ALLOWED_FIELDS` + `CalculateBudgetExecutor.resolvePriceSourceFromServerState` show the exact shape — re-derive provenance from persisted state server-side, default fail-safe to the less-trusted label, never accept a caller-supplied provenance claim. `reach_source` below follows this exactly.
5. **CI diff-check mechanics** (`.github/workflows/schema-check.yml`) are more precise than the plan implies: it's a **live extraction-and-diff**, not a manual doc. It already diffs `MeeraContextDtos.ContextResponse`'s `@JsonProperty` set against `assembler.py::CONTEXT_PAYLOAD_FIELDS`, and it already diffs `MeeraToolName` enum values against `schemas.py::TOOL_NAMES`. Adding `outcome_digest` and `get_campaign_performance` is **mechanically required** for this check to keep passing (§4).
6. **Drift in the plan's write-point list for 2.3** (corrected in §3.2):
   - "`CreateCampaignExecutor` (draft funded/abandoned)" is imprecise. `CreateCampaignExecutor` only ever creates a `DRAFT` — it has no funding or abandonment logic. The real `DRAFT → ACTIVE` (funded) transition is in **`ConfirmLaunchExecutor.doExecute()`**, at the line that does `campaign.setStatus(CampaignStatus.ACTIVE)` (`ConfirmLaunchExecutor.java:333`), gated on a verified `EscrowStatus.FUNDED` hold.
   - `CampaignIntent.abandon()` (`CampaignIntent.java:144`) **exists but has zero call sites anywhere in the codebase** — nothing currently marks a draft abandoned. `DRAFT_ABANDONED` has no write point to hook today. Flagged as an open question in §7 — do not silently skip it, but don't invent a new abandonment job under this ticket's scope either.
   - "the `REVISION_REQUESTED` transition (`DealService`)" is the wrong class. The transition that actually carries free-text `revision_reason` is **`BrandDeliverableService.requestRevision(AuthPrincipal, deliverableId, ReviseRequest)`** (`BrandDeliverableService.java:112`), which sanitizes (`TextSanitizer.sanitizePlainText`, HTML-strip only, **not** PII-redaction) and calls `deliverable.applyRevision(feedback)`. `DealService` has a same-named `CollaborationStatus.REVISION_REQUESTED` value used only for a list-filter query (`DealService.java:839`) — no free text there, not a write point.
7. **`app/security/redaction.py` is Python-only and cannot be called from the Java write points above.** `meera_interaction_log` is a Spring/MySQL table written from Java executors (`BrandDeliverableService`, `ConfirmLaunchExecutor`, `CreateCampaignExecutor`). There is no Java-side equivalent of Python's PAN/phone/bank-account/email regex scrub today — `TextSanitizer` only strips HTML. This is a real gap, resolved in §3.4 (new Java utility mirroring the Python regexes), not by routing through Python.
8. **`RateEstimationService`** (`service/scoring/RateEstimationService.java`) is a **formula-based synthetic estimate** (follower tier × category multiplier × quality score), not empirical data. It must **not** be reused or confused with `niche_rate_band` — the latter has to be grounded in real `Collaboration.agreedRate` rows (SR-1: real data, not a formula), per the plan's explicit intent ("data you already own" is the moat's whole thesis). Its 4-bucket tier logic (`AdminCreatorService.deriveTier`, `NANO/MICRO/MID/MACRO` at 10K/50K/500K follower cutoffs) is reused only as the **bucketing convention** — see §1.4.

---

## 1. Item 2.1 — Outcome digest in the context payload

### 1.1 Files to create / modify

| File | Change |
|---|---|
| `influora-api/.../web/dto/meera/MeeraContextDtos.java` | **Modify.** Add 3 new records: `CampaignOutcomeEntry`, `RateBand`, `OutcomeDigest`. Add `outcome_digest` field to `ContextResponse`. |
| `influora-api/.../service/meera/BrandContextAssembler.java` | **Modify.** Add `assembleOutcomeDigest(...)` method; wire its result into `assembleBrandContext(...)`'s `ContextResponse` construction. |
| `influora-api/.../service/meera/MeeraContextService.java` | **Modify.** Fetch the raw rows `assembleOutcomeDigest` needs (escrow/metric/utm/collaboration reads) and pass them in — same "fetch here, shape in BrandContextAssembler" split the class javadoc already documents. |
| `influora-api/.../repository/EscrowHoldRepository.java` | **Modify.** Add `sumAmountByCampaignIdAndStatus(String campaignId, EscrowStatus status)`. |
| `influora-api/.../repository/CollaborationRepository.java` | **Modify.** Add `findRateBandCandidates(String niche)` (native/JPQL projection query — see §1.4). |
| `influora-api/.../repository/UtmCampaignRepository.java` | No change — `findByCampaignId` already exists, sum in Java. |
| `influora-api/.../repository/DeliverableMetricRepository.java` | No change — `findByCollaborationIdIn` already exists. |
| `influora-ai/app/prompt/assembler.py` | **Modify.** Add `"outcome_digest"` to `CONTEXT_PAYLOAD_FIELDS`. Add `_render_outcome_digest(...)` and call it from `build_block_b`. |
| `influora-ai/app/config.py` | **Modify.** Bump `PROMPT_VERSION` (currently `"meera-2026.07.21.8"` — new context field materially changes Block B, per the module's own cache-key discipline). |
| **Migration** | None — pure read, no new table for 2.1. |

### 1.2 DTO shapes (frontend↔backend contract for this slice)

All in `MeeraContextDtos.java`, following the file's existing `@JsonProperty` snake_case convention (this is the Python-consumed seam, not the browser-facing camelCase convention `MeeraToolDtos.java` uses):

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignOutcomeEntry(
        @JsonProperty("type") String type,                          // Campaign.campaignType, same as PastCampaignEntry
        @JsonProperty("creator_count") int creatorCount,             // distinct creatorId over Collaboration rows for this campaign
        @JsonProperty("spend_inr") BigDecimal spendInr,               // SUM(EscrowHold.amount) WHERE campaignId=X AND status=RELEASED
        @JsonProperty("funded") boolean funded,                      // spendInr.signum() > 0 — real released money, NEVER FUNDED_STATUSES
        @JsonProperty("verified_reach") Long verifiedReach,          // SUM(DeliverableMetric.reach) WHERE source=PLATFORM_VERIFIED only; null if none
        @JsonProperty("reach_source") String reachSource,            // "PLATFORM_VERIFIED" when verifiedReach present, else null (see §1.3 open Q)
        @JsonProperty("attributed_revenue_inr") BigDecimal attributedRevenueInr) {} // SUM(UtmCampaign.revenueAttributed) for this campaign

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RateBand(
        @JsonProperty("min") BigDecimal min,
        @JsonProperty("median") BigDecimal median,
        @JsonProperty("max") BigDecimal max,
        @JsonProperty("currency") String currency,
        @JsonProperty("niche") String niche,
        @JsonProperty("sample_size") int sampleSize,        // distinct counterparties (creators) — never the raw n if it's the binding constraint
        @JsonProperty("workspace_sample_size") int workspaceSampleSize) {} // distinct workspaces — the OTHER k-anon leg

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutcomeDigest(
        @JsonProperty("campaign_outcomes") List<CampaignOutcomeEntry> campaignOutcomes,
        @JsonProperty("niche_rate_band") RateBand nicheRateBand) {}   // null (not a sparse object) when below k=5 floor
```

`ContextResponse` gets one new trailing field: `@JsonProperty("outcome_digest") OutcomeDigest outcomeDigest`.

**Why `RateBand` is `null` and not `{"status": "insufficient_data"}`:** matches this DTO file's existing `@JsonInclude(NON_NULL)` convention (see `analysisStatus`/`brandProfile == null` handling in `BrandContextAssembler`) and matches `build_block_b`'s existing pattern of skipping absent sections (`if brand.get(...)`) rather than rendering an empty-state line. Python's renderer just omits the line when `niche_rate_band` is `null` — no new "insufficient data" copy needed in the prompt.

### 1.3 Method signatures and queries

**`MeeraContextService`** — extend `assemble()` to also build the outcome digest from the *same* `recent` campaign list already fetched for `past_campaign_summary` (no extra campaign-selection query):

```java
// existing: List<Campaign> recent = campaignRepository.findByWorkspaceId(...).sorted().limit(5)
// existing: List<Collaboration> collaborations = collaborationRepository.findByWorkspaceId(workspaceId);
OutcomeDigest outcomeDigest = contextAssembler.assembleOutcomeDigest(workspaceId, recent, collaborations, brandProfile);
```

**`BrandContextAssembler.assembleOutcomeDigest`**:

```java
public OutcomeDigest assembleOutcomeDigest(
        String workspaceId,
        List<Campaign> recentCampaigns,
        List<Collaboration> workspaceCollaborations,
        BrandProfile brandProfile) {
    List<CampaignOutcomeEntry> entries = recentCampaigns.stream()
            .map(c -> buildOutcomeEntry(c, workspaceCollaborations))
            .toList();
    RateBand band = buildNicheRateBand(brandProfile);
    return new OutcomeDigest(entries, band);
}
```

Per-campaign entry (`buildOutcomeEntry`) — every number server-derived, zero AI/caller input:

```
collaborations for this campaign  = workspaceCollaborations.filter(campaignId == c.getId())   // reuse, no new query
creatorCount                      = distinct(creatorId)                                        // same pattern as buildPastCampaignSummary
spendInr                          = escrowHoldRepository.sumAmountByCampaignIdAndStatus(c.getId(), EscrowStatus.RELEASED)   // NEW repo method
funded                            = spendInr.signum() > 0
collaborationIds                  = collaborations.map(Collaboration::getId)
verifiedMetrics                   = deliverableMetricRepository.findByCollaborationIdIn(collaborationIds)
                                       .filter(m -> DeliverableMetric.SOURCE_PLATFORM_VERIFIED.equals(m.getSource()))
verifiedReach                     = verifiedMetrics.isEmpty() ? null : sum(verifiedMetrics.map(DeliverableMetric::getReach))
reachSource                       = verifiedReach == null ? null : "PLATFORM_VERIFIED"
utmRows                           = utmCampaignRepository.findByCampaignId(c.getId())
attributedRevenueInr              = sum(utmRows.map(UtmCampaign::getRevenueAttributed))
```

**SR-1 enforcement, line by line:** `spendInr`/`funded` come from `EscrowHold.status` — set exactly once, by `EscrowService`, on a real Razorpay-webhook-verified release (see §0.2). No `FUNDED_STATUSES` symbol is imported into `BrandContextAssembler`. `verifiedReach` is filtered to `SOURCE_PLATFORM_VERIFIED` — a value only `DeliverableVerificationService.applyVerifiedReport` writes, never creator input. `reachSource` is computed from the same filter, never read off any request/tool-call body — it cannot be spoofed because there is no code path that accepts it as input.

**Open design decision on `reachSource` (flag for Ash):** the plan says "only PLATFORM_VERIFIED enters the prompt unflagged," implying self-reported reach *could* enter, flagged. This design takes the more conservative literal reading — `campaign_outcomes[].verified_reach` is filtered to PLATFORM_VERIFIED only, full stop; self-reported reach is simply omitted (`null`), not surfaced with a "self-reported" flag. Rationale: fewer numbers in the prompt that need caveat-copy discipline downstream, and it matches the plan's literal instruction ("filtered SOURCE_PLATFORM_VERIFIED only"). If Ash wants self-reported numbers surfaced (flagged) for campaigns with zero verified data, that's a fast-follow with a second field (`self_reported_reach` + always-SELF_REPORTED tag) — flagged in §7.

### 1.4 `niche_rate_band` — the k-anonymity query

**This is a platform-wide, cross-tenant aggregate** (not workspace-scoped) — that's the entire reason the k-anon floor exists per guardrail 2. The band is computed for the *requesting brand's own niche* (from `BrandProfile.nicheTagsJson`, first tag) so the digest answers "what do creators in my niche actually charge," not an arbitrary global number.

`buildNicheRateBand(BrandProfile brandProfile)`:
1. If `brandProfile == null` or has no niche tags → return `null` (no query run).
2. `niche = brandProfile.nicheTagsJson`'s first tag.
3. Call new `CollaborationRepository.findRateBandCandidates(niche)`.
4. Group results in Java by follower-tier bucket (reusing the `NANO/MICRO/MID/MACRO` thresholds from `AdminCreatorService.deriveTier` — 10K/50K/500K — duplicated as a small private constant table here since that method is `private` on an unrelated admin service; **not** `RateEstimationService`'s 5-bucket MEGA variant, to stay consistent with the tier vocabulary already exposed elsewhere, e.g. `CreatorTier` enum).
5. Within the requesting workspace's own niche+tier bucket with the most rows, compute `min`/`median`/`max` of `agreedRate`, `count(distinct creatorId)` and `count(distinct workspaceId)`.
6. **k-anon gate:** if `distinct(creatorId) < 5 OR distinct(workspaceId) < 5` → return `null`. No partial band, no "n=3" ever serialized.
7. **Backoff ladder (open question, §7):** if the niche+tier bucket is below floor, this design proposes silently trying niche-only (ignore tier) before giving up, since a single-digit sample per (niche × tier) combination is expected pre-scale. Not implemented until Priya/Ash confirm this is acceptable — the naive v1 (single niche+tier grouping, no backoff, return `null` more often) is the safe default and is what ships if this isn't explicitly approved.

`CollaborationRepository.findRateBandCandidates` — new JPQL/native projection (MySQL `JSON_CONTAINS`, requires MySQL 8.0.17+; already assumed available per the `creator_nudge_log` migration's own MySQL-8.0.13+ note):

```sql
SELECT co.agreed_rate AS agreedRate, co.currency AS currency, ca.workspace_id AS workspaceId,
       co.creator_id AS creatorId, cp.total_followers AS totalFollowers, cp.tier_override AS tierOverride
FROM collaborations co
JOIN campaigns ca        ON ca.id = co.campaign_id
JOIN creator_profiles cp ON cp.user_id = co.creator_id
WHERE co.status = 'COMPLETED'
  AND co.agreed_rate IS NOT NULL
  AND JSON_CONTAINS(cp.categories, JSON_QUOTE(:niche))
```

**No row leaves this query into the response** — the repository returns a bounded row set consumed entirely inside `buildNicheRateBand`; only the aggregated `RateBand` (min/median/max + 2 counts) is ever assigned to a `ContextResponse` field. `status = 'COMPLETED'` only (not `>=COMPLETED` — `CollaborationStatus` has no ordinal "greater than" relationship; `DISPUTED`/`CANCELLED` are explicitly excluded so a disputed rate never counts as a clean market signal).

### 1.5 Python: `assembler.py` rendering

New `_render_outcome_digest(outcome_digest: Any) -> str | None`, same shape as `_render_past_campaign_summary`/`_render_template_digest`: every sub-field through `_safe()` before interpolation (campaign `type` is brand-authored via template/campaign_type, so untrusted; numeric fields safe to interpolate directly since they're `BigDecimal`/`int` off the wire, not raw strings). Two lines max: one for `campaign_outcomes` (existing "Past campaigns" line pattern extended with spend/reach/revenue), one for `niche_rate_band` (only rendered when non-null). Called from `build_block_b` right after the existing `past_campaign_line` block.

`CONTEXT_PAYLOAD_FIELDS` gets `"outcome_digest"` appended (alphabetical, matching the tuple's existing sort order) — **this line is what keeps `schema-check.yml`'s live diff passing**; the Java `@JsonProperty("outcome_digest")` on `ContextResponse` is the other half.

---

## 2. Item 2.2 — `get_campaign_performance` R-tier tool

### 2.1 Files to create / modify

| File | Change |
|---|---|
| `influora-ai/app/tools/schemas.py` | **Modify.** Add `GET_CAMPAIGN_PERFORMANCE` constant, append to `TOOL_NAMES`, `TOOL_TIERS[...] = "read"`, `TOOL_TO_SPRING_PATH[...]`, new entry in `TOOL_SCHEMAS`. **Not** added to `IDEMPOTENT_REQUIRED_TOOLS` (read tool, matches `show_creators`/`calculate_budget`). |
| `influora-api/.../domain/enums/MeeraToolName.java` | **Modify.** Add `get_campaign_performance`. |
| `influora-api/.../service/meera/tool/ToolCallValidator.java` | **Modify.** `TIER_BY_TOOL.put(MeeraToolName.get_campaign_performance, MeeraToolTier.R)`. |
| `influora-api/.../service/meera/tool/GetCampaignPerformanceExecutor.java` | **New.** Mirrors `CalculateBudgetExecutor`'s shape (R-tier, no repository write access beyond audit logging). |
| `influora-api/.../web/dto/meera/MeeraToolDtos.java` | **Modify.** Add `GetCampaignPerformanceResult` + `DeliverablePerformanceEntry` records (camelCase — this DTO family is the browser/tool-result convention, not the Block-B snake_case seam; matches `CalculateBudgetResult`/`ShowCreatorsResult` precedent). |
| `influora-api/.../web/MeeraInternalController.java` | **Modify.** Add `@PostMapping("/get_campaign_performance")`, wired exactly like `calculateBudget()` — `resolveForWorkspaceRequiringScope`, `requireTool`, delegate to executor. |
| `influora-api/.../repository/AffiliateEarningRepository.java` | **Modify.** Add `findByCampaignIdAndStatus(String campaignId, AffiliateEarning.Status status)`. |
| `.github/workflows/schema-check.yml` | No change needed — the tool-name diff is a live extraction against `MeeraToolName`/`TOOL_NAMES`, so it self-updates once both files are edited. |

### 2.2 Tool schema (Python)

```python
GET_CAMPAIGN_PERFORMANCE = "get_campaign_performance"
# ... appended to TOOL_NAMES, TOOL_TIERS["read"], TOOL_TO_SPRING_PATH["/internal/meera/get_campaign_performance"]

{
    "name": GET_CAMPAIGN_PERFORMANCE,
    "description": (
        "Return verified performance for ONE of this brand's own campaigns: spend, "
        "reach, engagement, and attributed revenue. Read-only, no money. Only works "
        "for a campaign owned by the current workspace."
    ),
    "input_schema": {
        "type": "object",
        "properties": {"campaign_id": {"type": "string"}},
        "required": ["campaign_id"],
    },
},
```

### 2.3 `GetCampaignPerformanceExecutor` — IDOR, the top risk (guardrail 6)

```java
public GetCampaignPerformanceResult execute(String workspaceId, Map<String, Object> input) {
    String campaignId = stringArg(input, "campaign_id");
    if (campaignId == null || campaignId.isBlank()) {
        throw new ApiException("CAMPAIGN_ID_REQUIRED", "campaign_id is required", HttpStatus.BAD_REQUEST);
    }

    // IDOR fix: resolve-scoped-by-workspace, generic 404 on miss OR cross-tenant id.
    // Never distinguish "doesn't exist" from "exists but isn't yours" — same exception,
    // same message, same status either way (existing precedent: CampaignRepository.findByIdAndWorkspaceId
    // is already the resolve-then-scope pattern UtmCampaignRepository's javadoc documents).
    Campaign campaign = campaignRepository.findByIdAndWorkspaceId(campaignId, workspaceId)
            .orElseThrow(() -> new ApiException("CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));

    List<Collaboration> collaborations = collaborationRepository.findByCampaignId(campaignId);
    List<String> collaborationIds = collaborations.stream().map(Collaboration::getId).toList();

    List<DeliverableMetric> verifiedMetrics = deliverableMetricRepository.findByCollaborationIdIn(collaborationIds)
            .stream().filter(m -> DeliverableMetric.SOURCE_PLATFORM_VERIFIED.equals(m.getSource())).toList();

    BigDecimal spendInr = escrowHoldRepository.sumAmountByCampaignIdAndStatus(campaignId, EscrowStatus.RELEASED);
    List<UtmCampaign> utmRows = utmCampaignRepository.findByCampaignId(campaignId);
    BigDecimal attributedRevenueInr = sum(utmRows, UtmCampaign::getRevenueAttributed);
    List<AffiliateEarning> settledEarnings =
            affiliateEarningRepository.findByCampaignIdAndStatus(campaignId, AffiliateEarning.Status.SETTLED);
    BigDecimal settledCommissionInr = sum(settledEarnings, AffiliateEarning::getCommissionAmount);

    // PII strip (guardrail 6): per-deliverable rows carry NO creator name / IG handle / any
    // free-text creator-authored field — only opaque ids + numeric metrics, mirroring
    // ShowCreatorsResult's precedent of exposing creatorProfileId (an opaque ref) but never a
    // caption/bio/free-text field.
    List<DeliverablePerformanceEntry> deliverables = verifiedMetrics.stream()
            .map(m -> new DeliverablePerformanceEntry(m.getMilestoneId(), m.getReach(), m.getImpressions(), m.getEngagements()))
            .toList();

    auditLogService.recordToolCall(workspaceId, "get_campaign_performance", "R",
            AuditLogService.OUTCOME_ALLOWED, null, null, null, Map.of("campaignId", campaignId));

    return new GetCampaignPerformanceResult(
            campaignId, collaborations.stream().map(Collaboration::getCreatorId).distinct().count(),
            spendInr, sum(verifiedMetrics, DeliverableMetric::getReach), attributedRevenueInr,
            settledCommissionInr, deliverables);
}
```

**404, not 403 — enforced structurally, not by convention:** the *only* repository call that can resolve a `Campaign` is `findByIdAndWorkspaceId`, which returns empty for both "no such campaign" and "campaign belongs to another workspace" — there is no separate existence-check call that could leak a distinguishing 403/404 split. This is the same shape `ConfirmLaunchExecutor`/`CreateCampaignExecutor` already use for campaign-intent resolution.

### 2.4 DTO shapes (`MeeraToolDtos.java`, camelCase)

```java
public record DeliverablePerformanceEntry(
        String milestoneId, Long reach, Long impressions, Long engagements) {}

public record GetCampaignPerformanceResult(
        String campaignId,
        long creatorCount,
        BigDecimal spendInr,
        Long verifiedReach,             // null if zero PLATFORM_VERIFIED rows
        BigDecimal attributedRevenueInr,
        BigDecimal settledCommissionInr,
        List<DeliverablePerformanceEntry> deliverables) {}
```

### 2.5 Routing (`MeeraInternalController`)

```java
@PostMapping("/get_campaign_performance")
public ResponseEntity<ApiResponse<GetCampaignPerformanceResult>> getCampaignPerformance(
        @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @RequestBody Map<String, Object> body) {
    String workspaceId = requireWorkspaceId(body);
    OnBehalfContext ctx = onBehalfAuthResolver.resolveForWorkspaceRequiringScope(
            onBehalfJwt, workspaceId, MeeraToolName.get_campaign_performance.name());
    requireTool(MeeraToolName.get_campaign_performance, workspaceId);
    var result = getCampaignPerformanceExecutor.execute(ctx.workspaceId(), body);
    return ResponseEntity.ok(ApiResponse.ok(result));
}
```

Same auth shape as `calculate_budget`/`show_creators` (R-tier, `resolveForWorkspaceRequiringScope`, not the elevated-role variant C-tier tools use) — no new auth pattern introduced.

---

## 3. Item 2.3 — Flywheel logging (`meera_interaction_log`)

### 3.1 Files to create / modify

| File | Change |
|---|---|
| `influora-api/src/main/resources/db/migration/V20260721160000__meera_interaction_log.sql` | **New.** Timestamp-prefixed per this repo's convention for anything added after the initial `V1..V68` sequence (see `V20260721150000__meta_oauth_workspace_id_nullable.sql`, the latest existing migration). |
| `influora-api/.../domain/entity/MeeraInteractionLog.java` | **New.** Immutable, append-only — mirrors `PortfolioEvent`/`CreatorNudgeLog`'s builder-only, no-mutator shape. |
| `influora-api/.../domain/enums/MeeraInteractionEventType.java` | **New.** `OPTIONS_PRESENTED, OPTION_TAPPED, DRAFT_CREATED, DRAFT_FUNDED, DRAFT_ABANDONED, REVISION_REQUESTED`. |
| `influora-api/.../repository/MeeraInteractionLogRepository.java` | **New.** Write-only for this pass (`save`); no read/analytics query yet — that's a fast-follow once there's data to look at. |
| `influora-api/.../service/meera/MeeraInteractionLogService.java` | **New.** Single fire-and-forget entry point, `REQUIRES_NEW` transaction, swallows its own failures (mirrors `PortfolioService.recordPublicView`'s documented "controller calls this off the response path and swallows any failure" pattern). |
| `influora-api/.../common/SensitiveTextRedactor.java` | **New.** Java port of `influora-ai/app/security/redaction.py`'s regex backstop (PAN/phone/bank-account/email patterns) — see §3.4 for why this can't just call the Python module. |
| `influora-api/.../service/meera/tool/CreateCampaignExecutor.java` | **Modify.** Log `DRAFT_CREATED` at the end of `doExecute()`, after the campaign save succeeds. |
| `influora-api/.../service/meera/tool/ConfirmLaunchExecutor.java` | **Modify.** Log `DRAFT_FUNDED` at the real `DRAFT→ACTIVE` transition (after `brandCampaignFeeService.chargeOnPublish` succeeds, before the method returns) — **not** in `CreateCampaignExecutor`, correcting the plan's drift (§0.6). |
| `influora-api/.../service/BrandDeliverableService.java` | **Modify.** Log `REVISION_REQUESTED` in `requestRevision(...)`, carrying the (redacted) `feedback` text as `revision_reason` — **not** `DealService`, correcting the plan's drift (§0.6). |
| `influora-ai/app/tools/loop.py` (or wherever `present_options` is dispatched) | **Modify (Python side, flagged not designed here — Ash/Ananya's call).** `OPTIONS_PRESENTED` is naturally a Python-side event (the local tool never reaches Spring) — needs either a new lightweight internal write endpoint Python calls, or the event is logged Python-side to its own store. See §3.3. |
| A new brand-facing endpoint for `OPTION_TAPPED` | **New, see §3.3** — this event happens in the browser after render, with no natural Java trigger. |

### 3.2 Migration — `V20260721160000__meera_interaction_log.sql`

Mirrors `portfolio_events`' documented pattern (typed `event_type` discriminator, VARCHAR not DB ENUM so a new event kind is an app-layer change not a migration, append-only, indexed for the hot-path query):

```sql
CREATE TABLE meera_interaction_log (
    id                 VARCHAR(26)  NOT NULL,
    workspace_id       VARCHAR(26)  NOT NULL,
    session_id         VARCHAR(64)  NULL,        -- opaque, Python-assembled session id (assembler.py cache_key_for) — not a Spring FK
    event_type         VARCHAR(32)  NOT NULL,     -- OPTIONS_PRESENTED | OPTION_TAPPED | DRAFT_CREATED | DRAFT_FUNDED | DRAFT_ABANDONED | REVISION_REQUESTED
    tool_name          VARCHAR(32)  NULL,         -- e.g. create_campaign, confirm_launch, present_options — null when not tool-triggered
    recommended_flag   BOOLEAN      NULL,          -- for OPTION_TAPPED: was the tapped option Meera's recommended one?
    campaign_id        VARCHAR(26)  NULL,          -- nullable: OPTIONS_PRESENTED/REVISION_REQUESTED may precede a real campaign row
    revision_reason    TEXT         NULL,          -- redacted via SensitiveTextRedactor before insert (§3.4) — never raw
    prompt_version     VARCHAR(32)  NOT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Hot-path index for the eventual "flywheel funnel" read (options presented -> tapped -> draft -> funded).
CREATE INDEX idx_meera_interaction_workspace_time ON meera_interaction_log (workspace_id, created_at);
CREATE INDEX idx_meera_interaction_campaign ON meera_interaction_log (campaign_id);
```

**Deliberately no FK on `campaign_id`.** Two reasons: (1) `V20260721140000__creator_nudge_log.sql`'s own comment shows this repo has hit MySQL-8 collation mismatches on cross-table FKs before (`trend_id` needed an explicit `COLLATE` override to match `trends.id`'s server-default collation) — a blind FK here risks the same SQL-3780 failure against `campaigns.id`'s actual collation, which must be checked with `SHOW CREATE TABLE campaigns` at migration-write time, not assumed; (2) `campaign_id` is legitimately null for pre-campaign events (`OPTIONS_PRESENTED` during initial chat, before any campaign exists), so a FK would need to be nullable-FK anyway, and this table is a pure event log, not a relational entity graph — same reasoning `utm_campaigns`' own repository javadoc gives for why it has no direct `workspace_id` FK either.

**No workspace_id FK either**, matching `portfolio_events`' precedent of no FK on its scoping column (event-log tables in this codebase consistently skip FKs — application-level integrity via the writing service, not the schema).

### 3.3 Write points

| Event | File : Method | Trigger |
|---|---|---|
| `OPTIONS_PRESENTED` | Python `present_options` dispatch (loop.py) | **Open question — see §7.** `present_options` never reaches Spring (it's a `LOCAL_TOOL_NAMES` entry per `schemas.py`). Two options: (a) new minimal internal endpoint `POST /internal/meera/interaction-log` that Python calls fire-and-forget (adds one more mesh-gated internal route, consistent with the dual-credential pattern every other `/internal/meera/*` route already uses), or (b) log Python-side to its own append-only table, splitting the flywheel data across two databases. This design recommends (a) for a single source of truth, but it means Python (`app/clients/spring.py`) gains a new outbound call — flagged for Priya, since it's the one place this design reaches outside Java. |
| `OPTION_TAPPED` | Frontend → new brand-facing endpoint | No backend trigger exists — tapping a card is a pure browser event with no state mutation. Needs a small new public (auth-required, workspace-scoped) endpoint, e.g. `POST /workspaces/{workspaceId}/meera/interactions/option-tapped { session_id, tool_name, option_key, recommended }`, that calls `MeeraInteractionLogService.record(...)` directly — no on-behalf JWT / HMAC mesh needed since this is a normal authenticated brand request, not a Python→Spring internal call. **Ananya needs this endpoint's exact path+shape before 2.4 can wire the tap handler** — flagged in §7 for a fast decision. |
| `DRAFT_CREATED` | `CreateCampaignExecutor.doExecute()`, after `campaignRepository.save(...)` succeeds (`CreateCampaignExecutor.java:207`) | Real draft row exists. |
| `DRAFT_FUNDED` | `ConfirmLaunchExecutor.doExecute()`, after `brandCampaignFeeService.chargeOnPublish(...)` + `campaignRepository.save(campaign)` succeed (`ConfirmLaunchExecutor.java:333-335`) | Real `DRAFT/PAUSED/PENDING_APPROVAL → ACTIVE` transition, fee already charged — matches the plan's "draft funded" semantics precisely (money actually moved, not just an intent). |
| `DRAFT_ABANDONED` | **No current trigger — open question, §7.** `CampaignIntent.abandon()` exists but is dead code. | Do not invent a new scheduled-abandonment job under this ticket; either scope it in explicitly (new job + Priya sign-off) or drop `DRAFT_ABANDONED` from the v1 enum with a comment explaining why, adding it back when a real trigger exists. |
| `REVISION_REQUESTED` | `BrandDeliverableService.requestRevision(...)`, after `deliverableRepository.save(deliverable)` (`BrandDeliverableService.java:126-127`) | Carries `revision_reason` = the brand's `feedback` text, through `SensitiveTextRedactor.redact(...)` before it reaches `MeeraInteractionLogService.record(...)`. |

### 3.4 `SensitiveTextRedactor` — closing the Python-redaction gap (guardrail 5)

New `influora-api/.../common/SensitiveTextRedactor.java`, a direct Java port of the **regex patterns only** from `influora-ai/app/security/redaction.py` (`_PAN_RE`, `_PHONE_RE`, `_BANK_ACCOUNT_RE`, `_SECRET_RE`, `_EMAIL_RE`) — not the logging-formatter machinery, which is Python-specific. Applied once, at the single call site where free text (`revision_reason`) is about to be persisted:

```java
public final class SensitiveTextRedactor {
    // Same 4 patterns as app/security/redaction.py: PAN, phone, bank-account, email.
    // Kept in explicit lockstep by comment cross-reference — no shared source of truth across
    // languages exists in this repo (same situation as the Python<->Java tool-schema seam,
    // except there is no CI diff-check for regex patterns; a manual "keep these two files in
    // sync" comment on both sides is the mitigation until/unless that's worth automating).
    public static String redact(String input) { ... }
}
```

**This must be called before the row is built, not after** — `MeeraInteractionLogService.record(...)` takes the caller-supplied `revisionReason` and redacts it internally as its own first line, so no call site can accidentally skip it.

### 3.5 `MeeraInteractionLogService` — fire-and-forget contract

```java
@Service
public class MeeraInteractionLogService {
    /**
     * Records one flywheel event. NEVER throws — a logging failure must not fail the turn/request
     * that triggered it (guardrail: "fire-and-forget"). Own REQUIRES_NEW transaction so a caller's
     * later rollback doesn't erase a logged event, mirroring AuditLogService's documented rationale.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String workspaceId, String sessionId, MeeraInteractionEventType eventType,
            String toolName, Boolean recommendedFlag, String campaignId, String revisionReason,
            String promptVersion) {
        try {
            String redactedReason = revisionReason == null ? null : SensitiveTextRedactor.redact(revisionReason);
            repository.save(MeeraInteractionLog.builder()
                    .id(Ulids.newUlid()).workspaceId(workspaceId).sessionId(sessionId)
                    .eventType(eventType).toolName(toolName).recommendedFlag(recommendedFlag)
                    .campaignId(campaignId).revisionReason(redactedReason).promptVersion(promptVersion)
                    .build());
        } catch (RuntimeException e) {
            log.warn("meera_interaction_log write failed, dropping event", e); // never rethrow
        }
    }
}
```

**Call sites never `await`/check a return value** — `record(...)` returns `void`, so there's nothing for a caller to accidentally branch on. This structurally prevents the "logging becomes load-bearing" failure mode.

### 3.6 Retention + access-scope (guardrail 5)

- **Retention:** propose 180 days (matches no existing precedent exactly — `AuditLogEntry`/`portfolio_events` are both undated/unbounded today — so this is a **new decision**, flagged for Priya in §7, not an established convention to copy).
- **Access scope:** this table is written from both BRAND-side flows (`CreateCampaignExecutor`, `ConfirmLaunchExecutor`, `BrandDeliverableService`) and (once §3.3's open question resolves) a CREATOR-facing surface if `present_options`/tap events ever originate from a creator-audience turn. **Until Phase 3's CREATOR allow-list exists, this table only ever receives BRAND-workspace rows** — no code path in this design's scope writes a creator-attributed row. Guardrail 5's "no joined view co-locating both sides' private data" is satisfied by construction (nothing here joins to creator PII at all — `campaign_id`/`workspace_id`/`session_id` are the only relational hooks, and `revision_reason` is brand-authored text about a creator's deliverable, not creator data itself). Re-audit this the moment Phase 3 adds a creator-attributed event type.

---

## 4. CI diff-check — required line items (guardrail 4)

Every new context field / tool from this design needs matching entries on **both** sides, or `schema-check.yml` breaks silently at runtime (per the plan's Risk Landmine #3):

| Change | Java side | Python side |
|---|---|---|
| `outcome_digest` context field | `MeeraContextDtos.ContextResponse` gets `@JsonProperty("outcome_digest")` | `assembler.py::CONTEXT_PAYLOAD_FIELDS` gets `"outcome_digest"` |
| `get_campaign_performance` tool | `MeeraToolName.java` gets `get_campaign_performance` | `schemas.py::TOOL_NAMES` gets `GET_CAMPAIGN_PERFORMANCE` |
| Prompt/Block-B shape change | — | `config.py::PROMPT_VERSION` bumped (new value, e.g. `"meera-2026.07.21.9"`) |

`schema-check.yml` itself needs **no edit** — both diffs it runs (`tool_names` and `context_fields`) are live extractions, so they pick these up automatically once the two-sided edits land in the same PR. That "same PR" discipline is the actual enforcement mechanism; there's no compiler that stops someone from editing only one side.

---

## 5. API / tool-result JSON shapes — frontend↔backend contract summary

For Ananya (2.4, not designed here, but this is what she'll type against):

- **`POST /internal/meera/context` response** gains `outcome_digest: OutcomeDigest | null` (never present for a workspace with no campaigns — `campaignOutcomes` would just be `[]`, `nicheRateBand` would be `null`). This is Python-consumed, not directly browser-consumed — Ananya's surface is what Meera *says* about it in chat, not this payload directly, unless `StagePerformance.tsx` needs the raw numbers too (in which case a browser-facing echo of this same data via `get_campaign_performance`'s tool-result is the more natural fit — see next point).
- **`get_campaign_performance` tool-result** (`GetCampaignPerformanceResult`, §2.4) is what actually reaches the browser via the SSE tool-call round-trip — this is the shape `isCampaignPerformancePayload` (mentioned in the build plan's 2.4) should type-guard against. Field names are camelCase (Jackson default), matching `ShowCreatorsResult`/`CalculateBudgetResult` precedent, **not** the snake_case Block-B convention.
- **No new browser-facing REST endpoint from 2.1/2.2** except the `OPTION_TAPPED` write endpoint flagged in §3.3, which Ananya needs a firm path+shape for before wiring the tap handler.

---

## 6. Guardrail enforcement — line-by-line index

| Guardrail | Where enforced |
|---|---|
| SR-1: real RELEASED escrow, never `FUNDED_STATUSES` | `EscrowHoldRepository.sumAmountByCampaignIdAndStatus(..., EscrowStatus.RELEASED)` — no `FUNDED_STATUSES` import anywhere in `BrandContextAssembler`/`GetCampaignPerformanceExecutor`. `EscrowHold.status` is set exclusively by `EscrowService` on a verified webhook (§0.2). |
| k-anonymity floor n≥5 (workspaces AND counterparties) | `buildNicheRateBand`'s explicit `if (distinct(creatorId) < 5 OR distinct(workspaceId) < 5) return null` — both counts always computed, both checked, `RateBand` DTO carries both counts for auditability even though only the pass/fail matters at runtime. |
| Provenance tag, server-derived, never caller-supplied | `reachSource`/`verifiedReach` computed purely from `DeliverableMetric.source == SOURCE_PLATFORM_VERIFIED` filtering — no tool-call `input` map is ever read for either field, in either `BrandContextAssembler` or `GetCampaignPerformanceExecutor`. |
| CI diff-check line items | §4 table — both sides listed explicitly, `PROMPT_VERSION` bump called out. |
| Structured event codes, not raw bodies; redaction; retention; info-barrier | §3.4 (`SensitiveTextRedactor`), §3.5 (fire-and-forget, `try/catch` swallow), §3.6 (retention proposal + access-scope argument). `meera_interaction_log` never stores a prompt/response body — only enum event types, ids, and one redacted free-text field. |
| `get_campaign_performance` IDOR: 404 not 403, workspace-resolved, PII-stripped | §2.3 — single `findByIdAndWorkspaceId` resolve path, `DeliverablePerformanceEntry` has no name/handle field. |

---

## 7. Open questions / decisions for Priya + Ash

1. **`reachSource` conservatism (§1.3):** ship "PLATFORM_VERIFIED-only, self-reported omitted entirely" for v1, or add a flagged `self_reported_reach` fallback now? Recommend the former for v1.
2. **`niche_rate_band` backoff ladder (§1.4 step 7):** naive single niche+tier grouping (more frequent `null`), or fall back to niche-only when tier-level sample is too small? Recommend shipping naive v1 first, revisit once real data volume is known.
3. **`OPTIONS_PRESENTED` write path (§3.3):** new internal Spring endpoint Python calls (single source of truth, one more mesh-gated route) vs. Python-side logging to its own store (splits the flywheel across two DBs). Recommend the Spring endpoint.
4. **`OPTION_TAPPED` endpoint shape (§3.3):** needs a firm path + auth model decision fast — this is the one piece 2.4 (Ananya) is blocked on. Proposed: `POST /workspaces/{workspaceId}/meera/interactions/option-tapped`, normal brand-session auth (not the internal mesh).
5. **`DRAFT_ABANDONED` (§0.6, §3.3):** no trigger exists today (`CampaignIntent.abandon()` is dead code). Build a new staleness job (new scope, needs its own sign-off) or drop the enum value from v1 with a forward-compat comment? Recommend dropping it from v1 — don't build unscoped infra to satisfy one enum value.
6. **Retention window (§3.6):** 180 days proposed, no existing precedent in this codebase to anchor to — needs an explicit Priya/Rohan (cost) call, not just a backend default.
7. **`SensitiveTextRedactor` sync discipline (§3.4):** manual comment-linked duplication of 4 regexes across Python and Java, no CI diff-check enforcing they stay identical. Worth a lightweight test-fixture-based cross-check (same test strings, same expected redactions, run in both test suites) rather than trusting a comment? Flagging for Kavya more than Priya/Ash, but noting here since it's a design-time call.

---

## 8. Changes log

*(Appended to as implementation proceeds — empty at design time.)*

- 2026-07-21 — Design doc written (Vikram). No code changes yet.
- 2026-07-21 — **IMPLEMENTATION** (Vikram), folding in all Priya/Ash APPROVED-WITH-CHANGES items (B1–B5, Q1–Q4 rulings). Every file touched:

**2.1 — Outcome digest**
- `influora-api/src/main/java/com/influora/web/dto/meera/MeeraContextDtos.java` — new `CampaignOutcomeEntry`/`RateBand`/`OutcomeDigest` records + `outcome_digest` field on `ContextResponse`.
- `influora-api/src/main/java/com/influora/service/meera/BrandContextAssembler.java` — new `assembleOutcomeDigest`/`buildOutcomeEntry`/`buildRateBand`/`tierBucket`/`median`; `assembleBrandContext` takes the digest as a new trailing param.
- `influora-api/src/main/java/com/influora/service/meera/MeeraContextService.java` — fetches the raw escrow/metric/utm/collaboration rows for the SAME reused `PAST_CAMPAIGN_LIMIT`-bounded campaign list (B1), builds the rate-band candidate query, delegates shaping to the assembler.
- `influora-api/src/main/java/com/influora/repository/EscrowHoldRepository.java` — new `sumAmountByCampaignIdAndStatus` (strictly RELEASED, B4).
- `influora-api/src/main/java/com/influora/repository/CollaborationRepository.java` — new `RateBandCandidateRow` projection + `findRateBandCandidates` native query.
- `influora-ai/app/prompt/assembler.py` — new `_render_outcome_digest` (every string sub-field `_safe()`-wrapped incl. niche/currency per B2; every nested field via `.get()` per Lock-3 gap mitigation), `outcome_digest` added to `CONTEXT_PAYLOAD_FIELDS`, wired into `build_block_b`.
- `influora-ai/app/config.py` — `PROMPT_VERSION` `.8` → `.9`.
- `influora-api/src/test/java/com/influora/service/meera/BrandContextAssemblerTest.java`, `MeeraContextServiceTest.java` — updated call sites/mocks for the new signatures (kept compiling; no new assertions added — Kavya's QA pass owns new coverage).

**2.2 — `get_campaign_performance`**
- `influora-ai/app/tools/schemas.py` — new `GET_CAMPAIGN_PERFORMANCE` tool (R-tier), appended to `TOOL_NAMES`/`TOOL_TIERS`/`TOOL_TO_SPRING_PATH`/`TOOL_SCHEMAS`.
- `influora-api/src/main/java/com/influora/domain/enums/MeeraToolName.java` — new `get_campaign_performance` value.
- `influora-api/src/main/java/com/influora/service/meera/tool/ToolCallValidator.java` — tier mapping (R) + updated javadoc (5→6 tools).
- `influora-api/src/main/java/com/influora/service/meera/tool/GetCampaignPerformanceExecutor.java` — **new**. IDOR-closed via single `findByIdAndWorkspaceId`; server-computes `roi`/`responseRate`/`avgCreatorScore` per Priya's Q2-ruled DTO; PII-stripped deliverables.
- `influora-api/src/main/java/com/influora/web/dto/meera/MeeraToolDtos.java` — new `DeliverablePerformanceEntry`/`GetCampaignPerformanceResult` records (exact Q2 contract).
- `influora-api/src/main/java/com/influora/web/MeeraInternalController.java` — new `/internal/meera/get_campaign_performance` route (R-tier auth shape).
- `influora-api/src/main/java/com/influora/repository/AffiliateEarningRepository.java` — new `findByCampaignIdAndStatus`.
- `influora-ai/app/prompt/persona.py` — added persona guidance for the new tool (Block A, tenant-agnostic).
- `influora-api/src/test/java/com/influora/web/MeeraInternalControllerContextTest.java`, `service/meera/tool/ToolCallValidatorTest.java` — updated for the new constructor param / 5→6 tool count.
- `influora-ai/tests/eval/test_prompt_injection.py` — `test_exactly_five_tools...` → `test_exactly_six_tools...`, includes `get_campaign_performance`.

**2.3 — Flywheel logging**
- `influora-api/src/main/resources/db/migration/V20260721160000__meera_interaction_log.sql` — **new** table (DRAFT_ABANDONED dropped per Q4 ruling; `prompt_version` made nullable — see final report for why).
- `wiki/processes/schema-changes.md` — migration logged.
- `influora-api/src/main/java/com/influora/domain/enums/MeeraInteractionEventType.java` — **new** enum (5 values, no DRAFT_ABANDONED).
- `influora-api/src/main/java/com/influora/domain/entity/MeeraInteractionLog.java` — **new** immutable entity.
- `influora-api/src/main/java/com/influora/repository/MeeraInteractionLogRepository.java` — **new**, write-only.
- `influora-api/src/main/java/com/influora/service/meera/MeeraInteractionLogService.java` — **new**, fire-and-forget `REQUIRES_NEW`, redacts via `SensitiveTextRedactor` as its own first line.
- `influora-api/src/main/java/com/influora/common/SensitiveTextRedactor.java` — **new**, complete Java port of `redaction.py`'s regex backstop (secret→JWT→PAN→email→phone→bank order, B3/B5).
- `influora-api/src/test/java/com/influora/common/SensitiveTextRedactorTest.java` — **new**, cross-language parity fixtures mirroring `influora-ai/tests/security/test_redaction.py` verbatim (Priya's blocking B5 requirement).
- `influora-api/src/main/java/com/influora/service/meera/tool/CreateCampaignExecutor.java` — logs `DRAFT_CREATED` after the campaign save succeeds.
- `influora-api/src/main/java/com/influora/service/meera/tool/ConfirmLaunchExecutor.java` — logs `DRAFT_FUNDED` at the real DRAFT→ACTIVE transition (after fee charge + save).
- `influora-api/src/main/java/com/influora/service/BrandDeliverableService.java` — logs `REVISION_REQUESTED` in `requestRevision`.
- `influora-api/src/main/java/com/influora/web/dto/meera/MeeraInteractionDtos.java` — **new**, `OptionTappedRequest`.
- `influora-api/src/main/java/com/influora/web/MeeraInteractionController.java` — **new**, `POST /workspaces/{workspaceId}/meera/interactions/option-tapped` (workspace resolved from principal, IDOR fix).
- `influora-api/src/main/java/com/influora/web/MeeraInternalController.java` — new `/internal/meera/interaction-log` route (`OPTIONS_PRESENTED`).
- `influora-ai/app/clients/spring.py` — new `log_interaction` client method.
- `influora-ai/app/tools/loop.py` — wires `present_options`' dispatch to `spring.log_interaction` (best-effort, never breaks the turn).
- `influora-api/src/test/java/com/influora/service/meera/tool/CreateCampaignExecutorTest.java`, `ConfirmLaunchExecutorTest.java`, `influora-api/src/test/java/com/influora/service/BrandDeliverableServiceTest.java` — updated for new constructor params.

**Cross-cutting**
- `SHARED_CONTEXT.md` — completion notice posted (→ Kavya).

**Points cut/deviated from the literal design for v1 (flagged for QA/Priya):**
1. `responseRate` (2.2) — implemented from real `Collaboration` source/status data (accepted = invited rows not stuck in `INVITED`/`CANCELLED`), since no precedented "response rate" derivation exists elsewhere in the codebase. This is a new policy definition, not just a config choice — worth an explicit QA/Priya blessing on the exact accepted/pending split.
2. `prompt_version` on `meera_interaction_log` — made **nullable** (design sketch implied `NOT NULL`). Python-originated `OPTIONS_PRESENTED` stamps a real value; the 3 pure-Java business-state events (`DRAFT_CREATED`/`DRAFT_FUNDED`/`REVISION_REQUESTED`) have no live AI-turn prompt-version context server-side, so they log `NULL` rather than a fabricated sentinel.
3. `REVISION_REQUESTED`'s `campaign_id` is left `NULL` — resolving it would need a new `Deliverable → Collaboration → Campaign` join not otherwise needed by `BrandDeliverableService`; the column is nullable by design for exactly this kind of event.

- `influora-api/.../web/dto/meera/MeeraToolDtos.java` — D1 fix (Priya impl-review): field-level `@JsonInclude(ALWAYS)` on `roi` so null ROI serializes as explicit JSON null, matching the frontend guard (fixes infinite-spinner on zero-spend campaigns).

- 2026-07-22 — **QA-1/QA-2 eval datasets** (Vikram), closing the two BLOCKING gaps from `wiki/build/phase2-kavya-qa.md` §2 (Kavya's QA gate, per plan §2.5). Grounded on the real `OutcomeDigest`/`CampaignOutcomeEntry`/`RateBand` DTOs (§1.2 above) and the real `GetCampaignPerformanceResult`/`DeliverablePerformanceEntry` DTOs (`MeeraToolDtos.java`, confirmed against the as-implemented file, which added `roi`/`responseRate`/`avgCreatorScore`/`provenance` fields beyond this doc's original §2.4 sketch — Priya's Q2 ruling). Matches the existing `influora-ai/evals/` harness's `{id, input, expected}` JSONL contract (`run_eval.py::load_dataset`) — no runner/scorer code added (Ash owns `provenance_exact_match`, built in parallel; not run live here, per task scope, since ANTHROPIC_API_KEY is unprovisioned).
  - `influora-ai/evals/datasets/outcome_recommendation.jsonl` — **new**, 15 cases. Each case's `input` carries a mock `context.outcome_digest` (snake_case, Python Block-B shape) and an optional mock `tool_result` for `get_campaign_performance` (camelCase, real DTO shape) plus a `user_message`. Each case's `expected.provenance.allowed_values[]` lists every number the response may legally quote, tagged `TOOL_RETURNED | DETERMINISTIC_CALC | CONFIG_VALUE` with the source field path — this is the field-format contract assumed for Ash's `provenance_exact_match` scorer (see task summary). `expected.provenance.forbidden_values[]` flags numbers that must never appear (self-reported/injected/cross-party). Required adversarial coverage per plan §2.5: self-reported number omitted (`or-005`), niche_rate_band below k=5 floor (`or-006`), in-head ROI temptation — model must not self-divide when `attributedRevenueInr` is null (`or-007`), injection string in `campaign_outcomes[].type` neutralized (`or-008`) — plus a second injection case in the user's own chat turn (`or-015`) and a cross-party/IDOR case with a 404-shaped tool error (`or-014`). ROI values in mock `tool_result.output.roi` follow the executor's documented `scale=4, RoundingMode.HALF_UP` (Kavya's QA report §1.1) — verified by script, not eyeballed.
  - `influora-ai/evals/datasets/campaign_performance.jsonl` — **new**, 10 cases. Each case's `input` mocks the raw joined rows `GetCampaignPerformanceExecutor` reads (`collaborations`, `deliverable_metrics` — deliberately carrying mock `creator_name`/`creator_ig_handle`/`creator_caption` PII fields to prove stripping — `escrow_holds`, `utm_campaigns`, `affiliate_earnings`, `creator_scores`); `expected.tool_result` is the exact real DTO shape the executor must return, or `expected.tool_error` (`{status:404, code:"CAMPAIGN_NOT_FOUND"}`) for the IDOR case (`cp-004`, differing `requesting_workspace_id` vs `campaign.workspace_id`). All 10 cases mix `PLATFORM_VERIFIED`/`CREATOR_REPORTED` rows to exercise the filter on every case (plan's "10/10" bar), not just a subset. Covers: zero-verified-rows → `verifiedReach: null` not a self-reported sum (`cp-002`), zero-spend → `roi: null` via the signum guard, not 0 (`cp-003`), PII strip with phone/email embedded in mock captions (`cp-005`), `responseRate` mixed-invite and empty-invite edge cases (`cp-006`/`cp-007`), `avgCreatorScore` partial/none (`cp-008`/`cp-009`), settled-vs-pending affiliate commission (`cp-010`). Every numeric field in every case (`spendInr`, `verifiedReach`, `attributedRevenueInr`, `settledCommissionInr`, `roi`, `responseRate`, `avgCreatorScore`, and the verified-only `deliverables[]` milestone-id set) was cross-checked by script against the mock input rows, not hand-computed.
  - Not touched: `wiki/processes/qa-checklist.md` (Kavya owns this per her report §3) and TECH-STACK.md at repo root (Priya owns this per her report §5) — both remain open QA-3/QA-4 gaps outside this task's scope.
