# Brand-surface fixes — backend changes (Vikram)

Source: `wiki/reports/brand-feature-audit.md` (4 real BROKEN findings, brand surface) +
`wiki/reports/brand-ai-feature-audit.md` (Ash's independent AI-feature cross-check, same #1
finding). Orchestrator applied the one-line `chat.py` fix for #1 before this pass; this doc covers
verification of #1 and the full backend implementation of #2/#3/#4.

---

## Fix #1 — Meera outcome digest (VERIFIED, no code change needed beyond orchestrator's)

**Break:** `_fetch_brand_context` in `influora-ai/app/routes/chat.py` built `brand_fields` with 7
keys and omitted `outcome_digest`, so `assembler.py`'s `build_block_b` → `_render_outcome_digest`
always saw `None`. The Phase-2 moat payload was computed + serialized by Java and renderer-ready in
Python, but never reached a live prompt.

**Status:** the orchestrator's one-line addition at `influora-ai/app/routes/chat.py:137` —
`"outcome_digest": context_data.get("outcome_digest")` — is correct and verified end-to-end:

- `assembler.py::_render_outcome_digest` (`:201`) and `build_block_b` (`:301`) already correctly
  read `brand.get("outcome_digest")` — no assembler change was needed, only the seam feeding it.
- `CONTEXT_PAYLOAD_FIELDS` (`assembler.py:51`) already included `outcome_digest` — the CI
  schema-drift check was never the gap.

**Files touched this pass:**
- `influora-ai/app/clients/spring.py` — fixed the stale `get_meera_context` docstring (~line
  285-291) that listed the context-response fields but omitted `outcome_digest`; now lists it and
  cross-references the `_fetch_brand_context` seam that must copy it.
- `influora-ai/tests/routes/test_chat_context_source.py` — added
  `test_fetch_brand_context_carries_outcome_digest_into_assembled_block_b`, a new wiring test that
  drives the REAL seam (mocked Spring response → `_fetch_brand_context` → `build_block_b`) end to
  end, not just the assembler in isolation like the existing
  `tests/prompt/test_assembler_context_wiring.py` coverage. Asserts both the intermediate dict shape
  (`ctx["brand"]["outcome_digest"]`) and the actual rendered Block-B string Meera's live prompt
  receives (campaign-outcomes line + niche-rate-band line).

**Verification run:** `python -m pytest tests/routes/test_chat_context_source.py
tests/prompt/test_assembler_context_wiring.py -q` → 16 passed (ran via the PowerShell tool since
this sandbox's Bash `python` isn't on PATH; `C:\Python313\python.exe` is).

---

## Fix #2 — Contract brand-signing 400 (`ContractController` / `ContractService`)

**Break:** FE posts `{name, agreedAt}` with no `role` (`src/lib/api.ts:1466` `contracts.sign`, via
`src/lib/contract-generator.ts:signContract`). The brand branch of `POST
/contracts/{id}/sign` required `body.role()` non-blank → every brand sign 400'd with
`INVALID_SIGNER_ROLE`. Creator sign (`recordSignatureForCreator`) was unaffected — it already
ignores the body entirely.

**Fix applied — server-derive the role, don't require it:**
- `ContractController.sign` (brand branch) now defaults `role` to `"BRAND"` when the body is
  null/absent/blank, instead of 400-ing. An explicit `role` in the body (e.g. the elevated-member
  `role=CREATOR` relay path documented on `ContractService#recordSignature`'s javadoc, Kabir E2
  LOW-4) is still honored — nothing about that existing, security-reviewed capability was removed,
  it's just no longer REQUIRED for the common self-sign case, which is the FE's only real call
  path today.
- `MoneyDtos.ContractSignRequest` — `role` field is no longer `@NotBlank`; it's optional now.
- `recordSignature`'s own `INVALID_SIGNER_ROLE` validation (anything other than BRAND/CREATOR) is
  untouched — still the safety net if a caller sends garbage.

**Why not "always force role=BRAND, ignore the body" (fully mirroring
`recordSignatureForCreator`):** `ContractService#recordSignature`'s existing javadoc documents a
second, deliberate, Kabir-reviewed capability — a brand OWNER/ADMIN/MANAGER relaying the creator's
out-of-band signature (`role=CREATOR` sent by a BRAND principal, gated behind elevated
`MemberRole`). No FE call site sends this today (`api.ts`'s `contracts.sign('brand', ...)` /
`signContract(id, 'brand', ...)` never populate a business-role `CREATOR` value — the `role`
parameter there is actually the AUTH-role selector picking which JWT to send, a separate concept
from the JSON body's signer `role`), but removing server support for it would be a product decision,
not a bug fix. Kept it reachable via an explicit body, just no longer mandatory.

**Files touched:**
- `influora-api/src/main/java/com/influora/web/ContractController.java`
- `influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java`
- `influora-api/src/test/java/com/influora/web/ContractControllerTest.java` (new) — brand sign with
  no role defaults to BRAND and succeeds; brand sign with null body also defaults to BRAND; brand
  sign with an explicit role still passes it through unchanged; creator sign is unaffected (still
  routes to `recordSignatureForCreator`, body ignored).

---

## Fix #4 — Brand content-performance media route (`AnalyticsController`)

**Break:** FE calls `GET /analytics/creators/{creatorId}/media` (`src/lib/api.ts:2686`
`contentPerformance.list`) but no such route existed on `AnalyticsController` — only the self-scoped
`GET /creator/analytics/me/media` (`CreatorAnalyticsController`). Every brand call 404'd.

**Fix applied:**
- New `GET /{creatorId}/media` on `AnalyticsController`, authz-gated EXACTLY like its siblings
  (`/metrics`, `/scores`, `/demographics`): `creatorId` is routed through
  `MetricsAuthorizationService.resolveAuthorizedCreatorProfileId` before any `MediaMetric` row is
  read.
- `AnalyticsService` — added `getContentPerformance(AuthPrincipal, String creatorId)` (the brand
  path) and refactored the existing self-service `getContentPerformanceForProfile` to share one
  private builder (`buildContentPerformanceResponse`) with it, mirroring the
  metrics/scores/demographics split already in this class.
- **`ContentPerformanceResponse` gained one new field: `engagementRate`** (`BigDecimal`, nullable).
  The FE's `ContentPerformanceItem` type (`src/lib/api.ts:2673`) expects a rate, but the existing
  DTO only had raw counts (`engagement`, `reach`, etc.) — no per-post rate was ever computed at this
  granularity. Added `engagementRate = engagement / reach * 100` (2dp, `null` when `reach` is
  missing/zero — never guessed), computed in `AnalyticsService`, shared by both the brand and
  creator-self routes. This is purely additive — every existing field is unchanged, the creator-self
  response just gains one more field nothing currently reads.

**FE contract reconciliation (for Ananya):** `ContentPerformanceItem` in `src/lib/api.ts` declares
exactly 6 fields (`mediaId, mediaType, postedAt, reach, impressions, engagementRate`). The real
response is a superset — it also includes `permalink, likes, comments, saves, shares, videoViews,
avgWatchTimeSeconds` (all present on `MediaMetric` already, just not in the FE's minimal type). No
FE type change is required for the panel to work (extra JSON fields are ignored by the current
type), but if `ContentPerformancePanel` ever wants those extra stats, they're already on the wire.

**Files touched:**
- `influora-api/src/main/java/com/influora/web/AnalyticsController.java`
- `influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java`
- `influora-api/src/main/java/com/influora/web/dto/analytics/AnalyticsDtos.java`
  (`ContentPerformanceResponse` +`engagementRate`)
- `influora-api/src/test/java/com/influora/web/AnalyticsControllerTest.java` — authorized creator
  returns rows wrapped as-is; a `FORBIDDEN` from the service propagates untouched (foreign creator
  blocked).
- `influora-api/src/test/java/com/influora/service/analytics/AnalyticsServiceTest.java` — foreign
  creator rejected before any `MediaMetric` read; authorized creator gets rows with a correctly
  derived `engagementRate`; `engagementRate` is `null` (not `0`/guessed) when `reach` is missing.

---

## Fix #3 — Deliverable-level brand-safety review (NEW build)

**Gap:** no code path scored SUBMITTED deliverable content. `BrandDeliverableService` had zero
reference to `BrandSafetyScoreService`/`BrandSafetyAiClient` — GARM only scored creators' already-
published Meta posts via the batch `ScoreCalculationJob` (and that job is `enabled=false` by
default besides).

**What was built — reuses the existing hardened classifier, does not build a new one:**

New endpoint `GET /deliverables/{deliverableId}/safety-review` on `BrandDeliverableController`,
backed by a new `DeliverableSafetyReviewService`:

1. Resolves the deliverable via the SAME brand-workspace trust boundary
   `BrandDeliverableService` already uses (`DeliverableRepository.findByIdAndWorkspaceId` —
   collaboration → campaign → workspace join-through). Foreign/unowned deliverable →
   `DELIVERABLE_NOT_FOUND` (404), same as every other deliverable read.
2. Takes the deliverable's `caption` (already `TextSanitizer`-stripped at submit time —
   `Deliverable.applySubmit`), runs it through `SensitiveTextRedactor.redact` (defense-in-depth PII
   scrub before any free text leaves this service), then calls the EXACT SAME forced-tool-choice
   GARM classifier every creator's published post already goes through: `BrandSafetyAiClient
   .classify(workspaceId, [ContentItem])` → influora-ai `POST /internal/brand-safety` →
   `app/routes/brand_safety.py` (`ANALYZE_CREATOR_CONTENT_SCHEMA`, 10 fixed GARM categories, 4 risk
   levels, `_validate_model_result` server-side completeness check). No new Python route, no new AI
   client — one deliverable's caption instead of a batch of `MediaMetric` rows.
3. **Server-interprets** the model's structured output into a verdict — the model itself never
   returns "PASS/FAIL", only per-category risk + sentiment + a 0-100 score:
   - One `SafetyCheck` per GARM category, **always all 10**, never omitted (same "never omit a
     category to imply safety" discipline the Python classifier enforces) — `risk="high"` → `FAIL`,
     `risk="low"/"medium"` → `WARNING`, `risk="floor"` → `PASS`.
   - `overallVerdict`: `FAIL` if any check `FAIL`s, else `REVIEW` if any check `WARNING`s, else
     `PASS`. Worst-check-driven, same principle `BrandSafetyScoreService` already uses for its
     creator-level aggregate (never averaged/diluted).
4. **Advisory only, never blocks submit/approve/reject/revise.** Nothing in
   `CreatorDeliverableController#submit` or `BrandDeliverableService.approve/requestRevision/reject`
   calls or awaits this service — it is a pure, optional GET the brand's review UI can call.
5. **Never a 500 on failure** — any classifier failure (`BrandSafetyAiException`: influora-ai
   unreachable, times out, errors, malformed response) degrades to a typed `503
   SAFETY_REVIEW_UNAVAILABLE`; no caption → `404 SAFETY_REVIEW_NO_CONTENT`; unowned deliverable →
   `404 DELIVERABLE_NOT_FOUND`. The FE hook (`useDeliverableSafetyReview.ts`, already built) treats
   ANY of these uniformly as `review: null` — no error state on top of the approve/reject flow.
6. **Not cached/persisted** — every GET is a fresh Claude call via `BrandSafetyAiClient`. Accepted
   trade-off for this first pass (kept the change additive, no schema migration); flagged below as a
   fast-follow if usage/cost justifies caching the result once per deliverable version.

**Result DTO shape** (`DeliverableSafetyDtos.DeliverableSafetyReviewResponse`, matches
`DeliverableSafetyReview` in `src/lib/api.ts` field-for-field):

```json
{
  "overallVerdict": "PASS | REVIEW | FAIL",
  "checks": [
    {
      "id": "adult_explicit_sexual_content",
      "label": "Adult / explicit sexual content",
      "status": "PASS | WARNING | FAIL",
      "detail": "One-sentence rationale from the classifier, or \"Not scored\"/\"no concern\""
    }
    // ... always all 10 GARM categories, same order every time:
    // adult_explicit_sexual_content, arms_ammunition, crime_harmful_acts_to_individuals,
    // death_injury_military_conflict, hate_speech_acts_of_aggression,
    // illegal_drugs_tobacco_alcohol, obscenity_profanity, spam_or_harmful_content,
    // terrorism, debated_sensitive_social_issues
  ],
  "score": 98.00,       // nullable BigDecimal, 0-100, the classifier's brand_safety_score
  "computedAt": "2026-07-22T10:00:00Z"   // always "now" — not cached, see point 6 above
}
```

**FE contract note for Ananya:** the FE's `getSafetyReview` mock (`src/lib/api.ts:1608-1616`, and
this doc's predecessor comment in `useDeliverableSafetyReview.ts`) used 3 illustrative check ids
(`disclosure`, `brand_mention`, `garm_risk`) that are NOT what the real backend computes — those
would be FTC-disclosure/brand-mention detection, a different feature this classifier doesn't do.
The real `checks[]` is always the 10 fixed GARM category ids listed above. Please reconcile
`DeliverableSafetyCheck`'s mock data / any hardcoded id references against this list — the `id`
field is load-bearing (used for keying/lookup), the `label` is display-only and can change freely.

**SECURITY — flagged for Kabir (explicit, not a self-clearance):** the deliverable caption is
creator-authored free text reaching an LLM call, same trust class as every other caption this
classifier already ingests from Meta polling. Judgment applied here: since the deliverable belongs
to the brand's own paid campaign (the brand commissioned it), forwarding it to be classified for
that same brand does NOT cross the brand/creator info barrier the way an unrelated cross-tenant read
would — but this reasoning has not been independently red-teamed. Model output is consumed as
structured/enum data only (never free text echoed back to the brand verbatim), and the redaction
step is defense-in-depth on top of the already-reviewed `app/prompt/brand_safety.py` untrusted-input
handling. **Please confirm or correct this judgment before this ships live.**

**Files touched:**
- `influora-api/src/main/java/com/influora/web/dto/deliverable/DeliverableSafetyDtos.java` (new)
- `influora-api/src/main/java/com/influora/service/DeliverableSafetyReviewService.java` (new)
- `influora-api/src/main/java/com/influora/web/BrandDeliverableController.java` (new
  `GET /{deliverableId}/safety-review` route + constructor wiring)
- `influora-api/src/test/java/com/influora/service/DeliverableSafetyReviewServiceTest.java` (new) —
  unowned deliverable rejected before any classify call; all-clean caption → all-PASS/PASS verdict;
  one HIGH-risk category → that check FAILs + overall FAIL; one MEDIUM-risk category → WARNING +
  overall REVIEW (not FAIL); no caption → `SAFETY_REVIEW_NO_CONTENT`, no classify call; classifier
  failure → `SAFETY_REVIEW_UNAVAILABLE` (503), never a 500; caption is redacted (email masked)
  before it reaches `BrandSafetyAiClient.classify`.
- `influora-api/src/test/java/com/influora/web/BrandDeliverableControllerTest.java` — updated
  constructor call for the new dependency, added a delegation test for the new route.

---

## Retention purge — `meera_interaction_log` (Priya's PARTIAL-2 hard gate)

**Why:** Priya's PARTIAL-2 ruling (`wiki/build/partials-resolution-plan.md`) locks the 180-day
retention purge as a HARD, MANDATORY predecessor to ANY future read/join/analytics consumer of
`meera_interaction_log` — "No read query merges without it landing first." Kabir's L1 finding
(`wiki/build/phase2-kabir-security.md`) independently flags the same gap under the
MANDATORY-GATE: PASS verdict (redaction + write-only + workspace-scoping means no active leak
today, so retention was the one required fast-follow, not a gate-block). Landing it now — before
any reader exists — is also plain row-growth hygiene. **RETENTION ONLY** — no read consumer,
analytics view, or query beyond the purge's own age predicate was built; that stays out of scope
until a reader is actually proposed (and per Priya's ruling, L2's `campaign_id` ownership check
ships in the same changeset as that future reader, not this one).

**What was built:**
- `MeeraInteractionLogRepository.deleteByCreatedAtBefore(Instant cutoff)` — bulk JPQL `@Modifying`
  `DELETE ... WHERE l.createdAt < :cutoff` (not load-then-delete; no entities materialized).
  Age-only predicate — no workspace/campaign column is ever read or matched, which is what keeps
  this purge from adding any query surface to the write-only info-barrier invariant Kabir verified.
- `MeeraInteractionLogRetentionPurgeJob` — new `@Component`, mirrors `StaleTokenCleanupJob`'s /
  `PayoutOrphanedDebitSweepJob`'s shape: `@Scheduled(cron = "0 0 3 * * *")` (daily 3 AM, offset from
  `DeliverableCleanupJob` 2:00/2:30 AM and `ScoreCalculationJob`/`StaleTokenCleanupJob` 4:00 AM),
  `@SchedulerLock` for multi-instance safety, an `AtomicBoolean` overlap guard. Computes
  `cutoff = now - retentionDays` and calls the bulk delete; logs the deleted count. Javadoc carries
  an explicit info-barrier note: this job only deletes by age, no cross-party read/join.
- `MeeraInteractionLogRetentionProperties` (new `@ConfigurationProperties`, prefix
  `influora.meera-interaction-log-retention`) — `enabled` (default `false`) + `retentionDays`
  (default `180`, matching the migration comment's own stated policy). Registered in
  `InfluoraApiApplication`'s `@EnableConfigurationProperties` list (required — see
  `ConfigurationPropertiesRegistrationTest`, which fails the build if a properties class is
  injected but never registered).

**Config keys added** (`application.yml`, defaults conservative everywhere; `application-prod.yml`
carries a commented opt-in block, same convention as `brand-safety-scoring`/`cleanup.dry-run`):
- `influora.meera-interaction-log-retention.enabled` — env `MEERA_INTERACTION_LOG_RETENTION_ENABLED`
  (default `false`, so no environment starts purging just because the job exists on the classpath)
- `influora.meera-interaction-log-retention.retention-days` — env
  `MEERA_INTERACTION_LOG_RETENTION_DAYS` (default `180`)

**Files touched:**
- `influora-api/src/main/java/com/influora/repository/MeeraInteractionLogRepository.java` — added
  `deleteByCreatedAtBefore`
- `influora-api/src/main/java/com/influora/job/MeeraInteractionLogRetentionPurgeJob.java` (new)
- `influora-api/src/main/java/com/influora/config/MeeraInteractionLogRetentionProperties.java` (new)
- `influora-api/src/main/java/com/influora/InfluoraApiApplication.java` — registered the new
  properties class
- `influora-api/src/main/resources/application.yml` — new config block
- `influora-api/src/main/resources/application-prod.yml` — commented opt-in block
- `influora-api/src/test/java/com/influora/repository/MeeraInteractionLogRepositoryQueryTest.java`
  (new) — pins the JPQL (age-only `<` predicate, no workspace/campaign column, `@Modifying`
  `clearAutomatically=true`, `int` return type), same reflection-based convention
  `BrandAiCreditRepositoryQueryTest` uses (no H2/testcontainers harness wired up offline).
- `influora-api/src/test/java/com/influora/job/MeeraInteractionLogRetentionPurgeJobTest.java` (new)
  — default 180-day cutoff computed correctly; a configured 30-day window is honored (not
  hardcoded); `enabled=false` never calls the repository; overlap guard blocks concurrent runs.

**Not built (deliberately, per scope):** no read consumer, no analytics/funnel view, no query
beyond `deleteByCreatedAtBefore`'s own age predicate.

---

## Not run

Per instructions, `mvn` was not run (Meera's job — local verification). All fixes were reasoned
through against the real, current code (file:line cited throughout) and existing test conventions
were mirrored exactly (constructor-injection Mockito unit tests, no MockMvc/spring-security-test
harness in this codebase — see `AnalyticsControllerTest`'s own javadoc note on that; no
H2/testcontainers `@DataJpaTest` harness either — see `BrandAiCreditRepositoryQueryTest`'s own
javadoc note). The influora-ai (Python) side for fix #1 WAS run — `pytest` is available via
`C:\Python313\python.exe` in this sandbox and all 16 relevant tests pass.
