# BE Implementation Plan: Creator AI Co-pilot Tier-1 — Service/Entity/Endpoint/AI-client layer

**Author:** Vikram (Backend) · **Status:** DRAFT FOR PRIYA REVIEW — no code written yet
**Source of truth:** [creator-ai-copilot-tier1-build-spec.md](../ai-review/creator-ai-copilot-tier1-build-spec.md) §1, §2, §5
**Scope boundary:** I own service/entity/endpoint/AI-client (Java, `influora-api`). A second BE
agent owns the influora-ai Python route (`/internal/creator-suggestion`), prompt wiring, and the
security-control checklist (`service_token.py` scopes, guardrail tests) — coordination points
called out explicitly in §6, I do not write Python here.
**Gate:** nothing starts until Priya certifies money-path stability (per spec header). This is the
plan to execute the day that signoff lands.
**Related:** [creator-copilot-fe-components-plan.md](creator-copilot-fe-components-plan.md)
(Ananya, FE) — cross-checked against this plan in §3 and §5/§6, one real inconsistency found and
resolved below.

**R2 update (this pass):** Priya's R2 ruling (Option b) closes the `creator_nudge_log` P0 Meera
found (missing `prompt_version` in the R1 migration sketch, §2.3). Canonical column list, `toDto()`
as a pure read, and the `CreatorCopilotProperties` field rename are all updated below — search for
"R2" to find every touched spot. `wiki/build/creator-copilot-API-CONTRACT.md` stays v1, unchanged —
none of this affects the wire contract (`DailySuggestion` already had separate `theme`/`headline`/
`contentIdea` fields; R2 only changes how those are stored/computed server-side).

---

## 0. A verified pre-existing bug that reframes §3 (read this before §3)

The build spec's architecture diagram (§1) describes the IG OAuth ownership flip as forking a
brand-owned flow into a new creator-owned one. Reading the actual current tree, that is not quite
the situation:

- `web/MetaOAuthController.java:66-103` (`/meta/oauth/callback`) is **already creator-only**
  (`requireCreator(principal)` at line 71, resolves `CreatorProfile` by `principal.getUserId()` at
  lines 78-86) and already calls
  `tokenStorage.storeToken(creatorProfile.getId(), principal.getWorkspaceId(), ...)` at lines
  95-100.
- A CREATOR-type `AuthPrincipal` has **no** `workspaceId` — confirmed explicitly in the codebase's
  own comment at `security/AnalyticsUsageCapInterceptor.java:42` ("a CREATOR principal has no
  `workspaceId`"). So that call passes `workspaceId = null`.
- But `domain/entity/MetaOAuthToken.java:26-27` declares
  `@Column(name = "workspace_id", nullable = false, ...)`, and the underlying DDL
  (`db/migration/V20__meta_oauth_tokens.sql:7`) is `workspace_id VARCHAR(26) NOT NULL`.
- **Net effect: every creator who has ever completed `/meta/oauth/callback` gets a
  `DataIntegrityViolationException` on the insert.** This path cannot have ever succeeded.
- It gets worse one layer up: `service/MetaConnectionService.java:51-111` (`getStatus`/
  `disconnect`, its own javadoc says "for creators") also takes a `workspaceId` and calls
  `MetaOAuthTokenRepository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(workspaceId, ...)`
  — with `workspaceId = null` this compiles to `WHERE workspace_id = NULL`, which never matches in
  SQL, so this method would report "disconnected" even if a row somehow existed.
- Additionally: **`MetaConnectionService` is not called from any `@RestController` today**
  (grepped `web/*.java` for the class name — zero hits outside its own file and its DTO file). It's
  a written, unwired service.

So §3 below is not "fork brand → creator." It's "finish and fix a creator-ownership path that was
started and left broken," while adding the pieces (theme-tagging, suggestion log) that never
existed. I've written the fix to be additive to the brand path exactly as the spec's invariant
demands, but I want Priya and Meera aware this is a live-system finding, not just a Tier-1 nicety —
see the open question in §6.3 about whether this needs its own bug ticket independent of Tier-1.

---

## 1. `CreatorNudgeService` — fork plan

New file: `service/creatorcopilot/CreatorNudgeService.java` (new package, sibling to
`service/trendspark/`). Forks `service/trendspark/TrendSparkNudgeService.java`.

### What's copied (structure/discipline, not literal code)
- The `@Service` + `@Transactional` entry-point shape.
- The "load profile → fail-closed empty" idiom (`TrendSparkNudgeService.java:84-88`).
- The "score every active `Trend`, keep best above threshold" loop (lines 91-104), reusing
  `trendRepository.findActive(Instant.now())` and `themeMatchService.score(trend, themeTagsJson)`
  verbatim — `ThemeMatchService.score` (`service/trendspark/ThemeMatchService.java:58-71`) is
  already parametric on a raw JSON tags string, exactly as the spec says; zero changes needed
  there.
- `callAiSafely()`'s try/catch-never-throws wrapper (lines 197-223) — same discipline, new target.
- The `*Log.builder()...save()` idiom from `NudgeLog.Builder` (`domain/entity/NudgeLog.java:139-197`).
- `requireOwnedNudge`'s resolve-then-check pattern (lines 188-195) for dismiss/acted.

### What's stripped (per spec §2.2, "NO BrandProfile / catalog / gap-check / SNAPSBY")
- `BrandProfileRepository` / `WorkspaceRepository` → replaced by `CreatorProfileRepository`.
  `CreatorProfile.getDisplayName()` is a plain field (`domain/entity/CreatorProfile.java:29-30`), so
  unlike the brand path there's no `Workspace` join needed at all to get a display name
  (`TrendSparkNudgeService.brandDisplayName`, lines 242-251, has no creator-side analog needed).
- `ContentGapService` (gap-check) → removed. No creator analog in Tier-1 (spec §8: "coaching /
  brand-safety-rationale surfacing" explicitly deferred).
- `CatalogMatchService` / `SnapsbyCatalogVideo` / `NudgeMode` (SNAPSBY vs OWN_CONTENT branching) →
  removed. No catalog, no mode concept, no `videoCards`/`chosenVideoIds` marshaling.

### Method signatures
```java
@Service
public class CreatorNudgeService {

    public CreatorNudgeService(
            TrendRepository trendRepository,                       // REUSE
            CreatorProfileRepository creatorProfileRepository,     // NEW dependency vs brand path
            CreatorNudgeLogRepository creatorNudgeLogRepository,    // NEW (§2.3)
            ThemeMatchService themeMatchService,                   // REUSE
            CreatorSuggestionAiClient aiClient,                    // NEW (§4)
            CreatorCopilotProperties props) { ... }                // NEW (§2.5)

    @Transactional
    public SuggestionResult getSuggestion(String creatorProfileId) { ... }

    @Transactional
    public void markDismissed(String creatorProfileId, String suggestionId) { ... }

    @Transactional
    public void markActed(String creatorProfileId, String suggestionId) { ... }
}
```

`SuggestionResult` is a new record carrying the 3-way status the FE contract needs (spec §3.5) —
`TrendSparkNudgeService.getNudge` only has a binary present/absent (`Optional<NudgeResponse>`,
204-vs-200 at the controller), which isn't expressive enough here:
```java
public record SuggestionResult(String status, SuggestionDto suggestion) {
    public static SuggestionResult pendingTagging() { return new SuggestionResult("pending_tagging", null); }
    public static SuggestionResult noSuggestionToday() { return new SuggestionResult("no_suggestion_today", null); }
    public static SuggestionResult ready(SuggestionDto dto) { return new SuggestionResult("ready", dto); }
}
```

### Orchestration order inside `getSuggestion(creatorProfileId)`

One deliberate deviation from the spec's literal §2.2 step 1 wording ("no profile → stay silent,
mirrors `TrendSparkNudgeService:84-88`"): a `CreatorProfile` **cannot** be missing the way a
`BrandProfile` can — every creator gets exactly one row at signup
(`CreatorProfile.newForUser`, `domain/entity/CreatorProfile.java:194-211`), and the caller
(`CreatorContextService.requireCreatorProfile`, `service/CreatorContextService.java:47-57`) already
404s before this method is ever invoked. So "fail-closed empty" for creators is really about
**`theme_tags` not existing yet**, not the profile row. Order:

1. Load `CreatorProfile` by id (`creatorProfileRepository.findById(creatorProfileId).orElseThrow(...)`
   — defensive, the caller contract guarantees presence but this method never assumes it).
2. **Idempotent-read-first — this IS the cap mechanism, not a separate check:**
   `creatorNudgeLogRepository.findByCreatorProfileIdAndShownAtAfter(creatorProfileId, startOfUtcDay())`.
   If present → `SuggestionResult.ready(toDto(existingRow))`, return immediately. No re-scoring, no
   AI call, on any same-day repeat — this alone satisfies AC-4 ("2nd call same day → no model
   call"). Paired with the DB constraint in §5, this is what closes Kabir's flagged race (two
   concurrent *first*-of-the-day calls both miss this read; see §5 step-by-step).
3. If `creatorProfile.getThemeTagsJson()` is null/blank → `SuggestionResult.pendingTagging()` (the
   nightly tagger hasn't produced a rollup for this creator yet).
4. Else iterate `trendRepository.findActive(Instant.now())` → best
   `themeMatchService.score(trend, creatorProfile.getThemeTagsJson())` above
   `props.getScoreThreshold()`. None clears it → `SuggestionResult.noSuggestionToday()`
   (**no log row written** — nothing to cap, since there's nothing to show).
5. `callAiSafely()` → `CreatorSuggestionAiClient.requestSuggestion(...)` (§4) → `SuggestionCopy
   (headline, contentIdea)` or `null`.
6. **Updated per Priya R2:** `(headline, contentIdea)` = the AI copy tuple if non-null, else
   `templatedFallback(creatorProfile, bestTrend)` — the fallback returns the *same two-value tuple*
   shape (new template text — Ash/Tejas own final copy per spec §7, I stub a placeholder mirroring
   `TrendSparkNudgeService.templatedFallback`, lines 227-240, but returning `(headline,
   contentIdea)` instead of one formatted string). `theme` is set separately, straight from
   `bestTrend`'s matched theme (step 4) — it never comes from either the AI copy or the fallback,
   so this assignment happens regardless of which of the two branches above fired.
7. Build + save `CreatorNudgeLog` (§2.3) with `theme`/`headline`/`contentIdea`/`matchScore`/
   `trendId` all set, `promptVersion` stamped from `props.getPromptVersion()`.
8. **Race handling:** wrap the save in `try { ... } catch (DataIntegrityViolationException e)` — on
   catch, re-run step 2's read (the concurrent winner's row now exists by the time this runs) and
   return that instead of propagating a 500. See §5 for the DB constraint this depends on.
9. Return `SuggestionResult.ready(toDto(saved))` — `toDto` is now a pure column read plus a computed
   `expiresAt` (§2.3), no branching left in it.

`markDismissed`/`markActed` mirror `TrendSparkNudgeService.markClicked`/`markPurchased`
(lines 174-186): resolve via a new `creatorNudgeLogRepository.findByIdAndCreatorProfileId(id,
creatorProfileId)`, 404 (`ApiException("SUGGESTION_NOT_FOUND", ..., NOT_FOUND)`) if absent —
`creatorProfileId` always comes from the controller's already-resolved principal, never trusted
from the path param alone (same discipline as `requireOwnedNudge`).

---

## 2. Entity changes

### 2.1 `CreatorProfile.theme_tags` (`domain/entity/CreatorProfile.java`)
Mirrors `BrandProfile.themeTagsJson` (`domain/entity/BrandProfile.java:58-61, 134-141) — a plain
JSON column + getter/setter, **not** routed through `applySelfEdit`
(`CreatorProfile.java:418-442`, the user-facing partial-edit method) since this is system/batch
written, exactly like `BrandProfile.setThemeTagsJson` is a plain setter separate from
`applyAnalysisResult`:
```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "theme_tags", columnDefinition = "json")
private String themeTagsJson;

public String getThemeTagsJson() { return themeTagsJson; }

public void setThemeTagsJson(String themeTagsJson) {
    this.themeTagsJson = themeTagsJson;
    touch(); // existing private method, CreatorProfile.java:213-215
}
```
Placed near the other JSON columns (`categoriesJson` etc., lines 47-57). Never touches
`applicationStatus`/`suspended`/`tierOverride` — it's a wholly separate field with its own setter.

### 2.2 `CreatorCaptionCache` entity (NEW) — `domain/entity/CreatorCaptionCache.java`
`@Table(name = "creator_captions")`. **Naming note:** the spec's architecture diagram (§1) calls
this concept `creator_caption_cache`; Meera's migration section (§4) names the actual SQL table
`creator_captions`. I'm building the entity against the SQL name so nobody goes looking for a table
that doesn't exist; the entity *class* keeps the spec's descriptive name.

Fields (matches Meera's §4 DDL sketch, which is fully specified and fine as-is):
- `id` (String, 26, `@Id`)
- `creatorProfileId` (String, 26, not null) — FK `creator_profiles(id)`
- `igMediaId` (String, ~64, not null) — Graph API media id, from
  `InstagramMediaResponse.MediaItem.id()` (surfaced via
  `integration/meta/service/InstagramMetricsFetcher.java`'s `MediaWithInsights.mediaItem()`)
- `captionText` (TEXT, nullable — media items can have no caption)
- `taggedThemesJson` (JSON, nullable — null until tagged)
- `tagStatus` (new enum `CaptionTagStatus { PENDING, TAGGED, FAILED }`, `@Enumerated(STRING)`,
  default `PENDING`)
- `postedAt` (Instant, nullable), `taggedAt` (Instant, nullable), `createdAt` (Instant, not null)
- `UNIQUE(creator_profile_id, ig_media_id)`, `INDEX idx_captions_status(tag_status)` (Meera's §4)

One mutator, never partial/null-guarded (system-only writer, the batch job):
`applyTagResult(String taggedThemesJson)` → sets `tagStatus=TAGGED`, `taggedAt=now()`.

`CreatorCaptionCacheRepository`:
```java
Optional<CreatorCaptionCache> findByCreatorProfileIdAndIgMediaId(String creatorProfileId, String igMediaId);
List<CreatorCaptionCache> findByTagStatusOrderByCreatedAtAsc(CaptionTagStatus status, Pageable pageable);
```

### 2.3 `CreatorNudgeLog` entity (NEW) — `domain/entity/CreatorNudgeLog.java`

**RESOLVED — Priya R2 ruling (Option b), supersedes the R1 column-list conflict below.** The
column-conflict table further down this section is left in place as a record of what was disputed,
but it is no longer live — the canonical column list is now:

```
id, creator_profile_id, trend_id, match_score, theme, headline, content_idea, message_source,
prompt_version, shown_at, dismissed_at, acted_at, created_at
```

This drops the single `message` TEXT blob from my R1 draft in favor of three separate columns:
- **`theme`** — sourced from the trend/theme-match step (§1 step 4), i.e. **server-side, not AI**.
  This is `bestTrend`'s matched theme string, the same closed-vocabulary value
  `themeMatchService.score` matched against — never round-tripped through the AI client.
- **`headline`** / **`content_idea`** — the AI route's already-structured
  `{headline, content_idea}` response (§4's `SuggestionCopy` record already has exactly this shape —
  no AI-side change needed, R2 confirms my §4 assumption was right). The templated fallback
  (§1 step 6) returns the same two-value tuple, never a single blob, so `CreatorNudgeService` never
  branches into "AI gives 2 fields, template gives 1" — one shape throughout.

Meera is rebuilding her migration draft from this exact list in parallel — no more divergence to
reconcile.

```java
@Entity
@Table(name = "creator_nudge_log")
public class CreatorNudgeLog {
    @Id @Column(length = 26) private String id;
    @Column(name = "creator_profile_id", nullable = false, length = 26) private String creatorProfileId;
    @Column(name = "trend_id", nullable = false, length = 26) private String trendId;
    @Column(name = "match_score", nullable = false) private Integer matchScore;
    @Column(name = "theme", nullable = false, length = 100) private String theme; // server-sourced, NOT AI — see note above
    @Column(name = "headline", nullable = false, length = 300) private String headline;
    @Column(name = "content_idea", nullable = false, columnDefinition = "TEXT") private String contentIdea;
    @Enumerated(EnumType.STRING)
    @Column(name = "message_source", nullable = false, length = 16)
    private NudgeMessageSource messageSource; // REUSE domain/enums/NudgeMessageSource.java verbatim — AI|FALLBACK, no creator-specific values needed
    @Column(name = "prompt_version", nullable = false, length = 32) private String promptVersion;
    @Column(name = "shown_at", nullable = false) private Instant shownAt;
    @Column(name = "dismissed_at") private Instant dismissedAt;
    @Column(name = "acted_at") private Instant actedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    // + Builder (mirrors NudgeLog.Builder, NudgeLog.java:139-197)
    // + markDismissed()/markActed(): idempotent set-once, mirrors NudgeLog.markClicked (lines 115-119)
}
```

**`toDto()` becomes a pure column read** (Priya R2, item 2) — no derivation, no branching:
```java
private SuggestionDto toDto(CreatorNudgeLog row) {
    return new SuggestionDto(
        row.getId(), row.getTheme(), row.getHeadline(), row.getContentIdea(), expiresAt(row.getShownAt()));
}

private String expiresAt(Instant shownAt) {
    // End of UTC day containing shownAt — NOT a stored column, computed on every read.
    return shownAt.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
}
```
`expiresAt` was never a candidate column in either R1 draft — it's derived purely from `shown_at`
at read time, matching the assumption I already flagged in §6.10 (now confirmed, not just assumed).

**R1 column-conflict record (superseded, kept for history only):**

| | §2.1 (Vikram, R1) | §4 (Meera's R1 DDL sketch) |
|---|---|---|
| trend reference | `trend_id` + `match_score` | `theme_matched` (single column) |
| copy | `message` | `suggestion_text` |
| source | `message_source` | `suggestion_source` |
| interaction timestamps | `dismissed_at`, `acted_at` | `clicked_at` only |
| prompt audit | *(required by invariant #5)* | **missing entirely** |

Neither R1 side's shape survives as-is — R2's ruling above is the canonical one now.

`CreatorNudgeLogRepository`:
```java
Optional<CreatorNudgeLog> findByCreatorProfileIdAndShownAtAfter(String creatorProfileId, Instant after);
Optional<CreatorNudgeLog> findByIdAndCreatorProfileId(String id, String creatorProfileId);
```

### 2.4 New enum `domain/enums/CaptionTagStatus.java`
`{ PENDING, TAGGED, FAILED }`.

### 2.5 New config `config/CreatorCopilotProperties.java`
Mirrors `config/TrendSparkProperties.java` exactly, including its defensive floor-on-bad-value
setters (`setScoreThreshold`, lines 23-25):
```java
@ConfigurationProperties(prefix = "influora.creator-copilot")
public class CreatorCopilotProperties {
    private boolean enabled = false;
    private int scoreThreshold = 2;
    private int maxSuggestionsPerCreatorPerDay = 1; // documents the intent; the DB constraint in §5 is what actually enforces it
    private String promptVersion = "creator-copilot-v1";
}
```
**Renamed per Priya R2 (item 3):** the field is `maxSuggestionsPerCreatorPerDay`, not `dailyCap` —
Spring relaxed binding maps this to the yaml key `max-suggestions-per-creator-per-day` (kebab-case
of the field name) automatically, so the yaml key itself and the `CREATOR_COPILOT_DAILY_CAP` env
var Meera's §4 already drafted stay exactly as-is; only the Java field name changes to match.
`model` and `theme-tag-batch-cron` belong to the AI-service config and the `@Scheduled` cron string
respectively, not this class.

### 2.6 `CreatorThemeTaggingJob` (NEW) — `job/CreatorThemeTaggingJob.java`
Skeleton of `job/ScoreCalculationJob.java` (per-item try/catch so one creator's failure never
aborts the batch, `@SchedulerLock` on the existing `shedlock` table from V68):
```java
@Scheduled(cron = "${influora.creator-copilot.theme-tag-batch-cron:0 0 3 * * *}", zone = "UTC")
@SchedulerLock(name = "CreatorThemeTaggingJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
public void tagCaptions() { ... }
```
Per run: pull a page of `CreatorCaptionCacheRepository.findByTagStatusOrderByCreatedAtAsc(PENDING, ...)`
→ `themeMatchService.themesForText(captionText)` (REUSE, `ThemeMatchService.java:98-120`, pure Java
keyword-match against the taxonomy file — **no model call in tagging at all**, worth noting for §4's
security-scope discussion) → `applyTagResult(json)` per caption → group by `creatorProfileId` →
union each creator's newly-tagged themes into `CreatorProfile.theme_tags` via
`setThemeTagsJson(...)`. Batch, not real-time, OFF by default (`enabled` flag gates whether this
`@Scheduled` method's body runs at all, same pattern as `BrandSafetyScoringProperties.isEnabled()`
in `ScoreCalculationJob.java:141-144`).

**Open question folded into §6.7:** this job assumes captions already exist in `creator_captions` —
population of that table (calling `InstagramMetricsFetcher` per connected creator and writing rows)
is a *second* batch concern the spec's §1 diagram bundles into the same line item but doesn't fully
spec. I'd split it into its own `CreatorCaptionSyncJob` rather than overload this one job with two
responsibilities (sync-from-Meta vs tag-from-cache) — flagged for Priya's call.

---

## 3. IG OAuth ownership flip

Given §0's finding, this is a repair-and-extend job, not a fork. All changes are additive to the
existing files; nothing brand-facing changes.

1. **Migration (hand to Meera):**
   ```sql
   ALTER TABLE meta_oauth_tokens MODIFY COLUMN workspace_id VARCHAR(26) NULL;
   ```
   The existing `UNIQUE KEY uq_meta_oauth_workspace_creator (workspace_id, creator_profile_id)`
   (`V20__meta_oauth_tokens.sql:16`) does **not** need to change — MySQL unique keys treat multiple
   NULLs as non-conflicting, which is exactly the semantics wanted here (many creators, each with
   one `workspace_id=NULL` row, must not collide with each other).

2. **`domain/entity/MetaOAuthToken.java:26-27`:** drop `nullable = false`:
   ```java
   @Column(name = "workspace_id", length = 26)
   private String workspaceId;
   ```

3. **`repository/MetaOAuthTokenRepository.java`:** ADD (existing brand method at lines 12-13
   untouched):
   ```java
   Optional<MetaOAuthToken> findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(String creatorProfileId);
   ```

4. **`integration/meta/oauth/MetaTokenStorage.java`:** ADD creator-owned sibling methods (existing
   `storeToken`/`getValidToken`/`revoke`, lines 78-159, untouched):
   ```java
   @Transactional
   public void storeCreatorToken(String creatorProfileId, String accessToken, Instant expiresAt, List<String> grantedScopes) {
       // same encrypt() + audit-log shape as storeToken; builder omits .workspaceId(...) (stays null);
       // lookup via findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse
   }
   @Transactional(readOnly = true)
   public Optional<String> getValidCreatorToken(String creatorProfileId) { ... }
   @Transactional
   public void revokeCreatorToken(String creatorProfileId) { ... }
   ```
   `encrypt()`/`decrypt()` (lines 161-194, AES-256-GCM) are reused unchanged — this is purely a
   keying change, no crypto touched.

5. **New `service/creatorcopilot/CreatorMetaOAuthService.java`** — thin orchestrator wrapping
   `MetaOAuthService.exchangeCodeForToken`/`exchangeForLongLivedToken` (both already fully generic,
   nothing brand-specific to fork — re-reading `integration/meta/oauth/MetaOAuthService.java`, it's
   stateless URL-building/token-exchange with zero workspace/creator awareness) +
   `MetaTokenStorage.storeCreatorToken` + the NO_BUSINESS_ACCOUNT check (next item). Gives Kabir one
   seam to audit instead of three call sites inline in the controller.

6. **`web/MetaOAuthController.java:88-102` fix:** replace
   `tokenStorage.storeToken(creatorProfile.getId(), principal.getWorkspaceId(), ...)` with a call
   into `creatorMetaOAuthService.connect(creatorProfile.getId(), code)`. `principal.getWorkspaceId()`
   must not appear in this file again.

7. **`service/MetaConnectionService.java` fix:** drop the `workspaceId` parameter from
   `getStatus`/`disconnect` entirely (the class is creator-only per its own javadoc), switch to
   `getValidCreatorToken`/`revokeCreatorToken`/the new repository method. **Needs a controller** —
   none calls this class today (§0). See §6.8 for whether that's in scope here or Ananya's
   `connected-accounts.tsx` already has a working status read I haven't found.

8. **NO_BUSINESS_ACCOUNT detection (spec §3.3):** after storing the token,
   `CreatorMetaOAuthService.connect` calls `FacebookPageClient.resolveConnectedInstagram(accessToken)`
   (already used by `MetaConnectionService.getStatus:79-88` and `DeliverableVerificationService`) —
   `null`/no linked IG business account → return a distinct `NO_BUSINESS_ACCOUNT` code in
   `MetaCallbackResponse` instead of silently succeeding with a token that can never fetch captions.
   Also persist the resolved `igAccount.id()` into the `ig_business_account_id` column
   (`V65__meta_oauth_ig_business_account_id.sql` — added for exactly this class of bug, H-9, on the
   brand/polling side; the creator path should populate it too rather than repeat that history).

9. **Service-token note (flag for Kabir, elaborated in §4):** the token
   `CreatorSuggestionAiClient` mints for influora-ai can't carry a `workspace_id` claim the way
   `BrandSafetyServiceTokenService.mint(workspaceId)` does
   (`service/integration/BrandSafetyServiceTokenService.java:63-83`) — there's no workspace. Needs a
   claim-shape decision with the Python-route-owning agent before this can round-trip. Not the same
   concern as items 1-8 above (those are the Spring↔Meta direction; this is the Spring↔influora-ai
   direction), just flagging both live under "OAuth ownership flip" conceptually.

**Reconciliation with the spec's §2.5 line "`POST /api/creator/ig/connect` → routes to the flipped
OAuth path":** I'm **not** building this as a new route. Ananya's FE plan
(`wiki/build/creator-copilot-fe-components-plan.md:71-94`) already commits to reusing
`connected-accounts.tsx`'s existing `handleConnect` → `api.metaOAuth.authorize()` →
full-page-redirect → the existing `/meta/oauth/callback` — explicitly "Do NOT fork the OAuth logic"
per spec §3.2 itself. Building a second, parallel `/creator/copilot/ig/connect` endpoint that no FE
code calls would be dead weight. I read the spec's §2.5 line as describing this same existing
route conceptually (now creator-owned per the fix above), not literally demanding a new path —
flagged as an explicit open question in §6.2 rather than silently assumed.

---

## 4. `CreatorSuggestionAiClient`

New file `integration/ai/CreatorSuggestionAiClient.java` (sibling to `TrendSparkAiClient.java`,
same package). Mirrors its contract exactly:
- `@Component @Lazy`, lazily-built `java.net.http.HttpClient` (same rationale as
  `TrendSparkAiClient.java:53-57`).
- **Never throws** — every failure mode (transport, non-200, malformed body) returns `null`; caller
  falls back to template (mirrors the class javadoc, lines 34-37).
- `PATH = "/internal/creator-suggestion"`.
- No hallucination kill-switch over video ids (no catalog in Tier-1) — the equivalent structural
  re-validation here is closed-vocab: any `theme` string the model echoes back must be a member of
  `themeMatchService.knownThemes()` or gets dropped, same "never trust model output structurally"
  discipline as `TrendSparkAiClient`'s video-id filter (lines 180-193), applied to a different set.

```java
public record SuggestionCopy(String headline, String contentIdea) {}

public SuggestionCopy requestSuggestion(
        String creatorProfileId,
        String creatorDisplayName,
        String theme,
        String trendText) { ... }
```

**Scope note worth being explicit about (this is a coordination point, not a decision I can make
alone):** by the time `CreatorNudgeService` calls this client, the caption text has already been
reduced to a closed-vocab `theme` string by `CreatorThemeTaggingJob` — and that tagging pass
(`ThemeMatchService.themesForText`) is pure Java keyword-matching, **no model call at all**. So this
client only ever sends a `theme` (our own taxonomy) and `trendText` (our own `trends` table) into
the phrasing prompt — neither is creator-controlled free text. The P0 invariant "captions wrapped
`<untrusted_content>` before ANY model call" therefore has **no code path through this client** to
enforce — it's either already moot (tagging is non-LLM today) or entirely the other BE agent's/
Ash's territory if tagging is ever upgraded to an LLM call later. Flagging this plainly so nobody
assumes I'm wrapping captions somewhere in this class, and nobody double-implements the wrapping on
both sides.

Config `config/CreatorSuggestionAiProperties.java` mirrors `config/TrendSparkAiProperties.java`
verbatim (`baseUrl`/`connectTimeoutSeconds`/`requestTimeoutSeconds`), prefix
`influora.creator-copilot-ai`.

DTOs `integration/ai/dto/CreatorSuggestionAiDtos.java` mirror `TrendSparkAiDtos.java` shape:
```java
public record SuggestionRequest(
    @JsonProperty("creator_profile_id") String creatorProfileId,
    @JsonProperty("display_name") String displayName,
    String theme,
    @JsonProperty("trend_text") String trendText) {}

public record SuggestionResponse(boolean success, Data data) {
    public record Data(String headline, @JsonProperty("content_idea") String contentIdea) {}
}
public record ErrorResponse(Detail detail) { public record Detail(String code, String message) {} }
```
**Open question (§6.5):** the FE contract (spec §3.5, Ananya's plan) wants `{headline,
contentIdea}` as two fields; the brand path's `TrendSparkAiDtos.NudgeResponse.Data` only has one
`message` string. I've assumed 2 structured fields from the model above — that's a joint call for
Ash (prompt owner) and the Python-route-owning agent to confirm, not mine alone.

---

## 5. REST endpoints + the per-creator/day cap

New `web/CreatorCopilotController.java`, `@RequestMapping("/creator/copilot")` (external
`/api/v1/creator/copilot/*` — confirmed `server.servlet.context-path: /api/v1` in
`influora-api/src/main/resources/application.yml:71`, and `src/lib/api.ts:48-49`'s
`API_BASE_URL` already includes `/api/v1`, so this matches Ananya's plan's `api.creator.copilot.*`
calls with no extra prefixing needed).

```java
@RestController
@RequestMapping("/creator/copilot")
public class CreatorCopilotController {

    private final CreatorContextService creatorContext; // REUSE, service/CreatorContextService.java:47
    private final CreatorNudgeService nudgeService;

    @GetMapping("/suggestion/today")
    public ResponseEntity<ApiResponse<SuggestionTodayResponse>> getToday(
            @AuthenticationPrincipal AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        return ResponseEntity.ok(ApiResponse.ok(toResponse(nudgeService.getSuggestion(profile.getId()))));
    }

    @PostMapping("/suggestion/{id}/dismiss")
    public ResponseEntity<ApiResponse<Void>> dismiss(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        nudgeService.markDismissed(profile.getId(), id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/suggestion/{id}/acted")
    public ResponseEntity<ApiResponse<Void>> acted(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        nudgeService.markActed(profile.getId(), id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
```
(No `ig/connect` route — see §3's reconciliation note.)

All three routes resolve identity via `creatorContext.requireCreatorProfile(principal)` — never a
`creatorProfileId` from a path/body param — exactly the discipline `TrendSparkController.java:43`
already uses (`brandContextService.requireBrandWorkspace(principal)`) and exactly what the spec's
P0 IDOR control demands. The `{id}` path param on dismiss/acted is the *suggestion* id only;
ownership is enforced inside `CreatorNudgeLogRepository.findByIdAndCreatorProfileId` (§1), never by
trusting the path.

### The cap, concretely

The spec frames this as "`existsByCreatorProfileIdAndCreatedAtAfter(startOfDay)` checked BEFORE
the AI call" plus a separate DB constraint. My design folds both into one mechanism: `GET
/suggestion/today` is naturally idempotent (read-if-exists-today, else generate-and-persist), and
persistence is protected at the DB level:

```sql
ALTER TABLE creator_nudge_log
  ADD COLUMN shown_day DATE GENERATED ALWAYS AS (DATE(shown_at)) STORED,
  ADD UNIQUE KEY uq_creator_nudge_day (creator_profile_id, shown_day);
```
(MySQL 8.0.13+ generated-column + unique-key — `shown_at` is always set at insert via the builder,
same pattern as `NudgeLog.Builder.build()`, `NudgeLog.java:191-196`.)

Two concurrent first-of-day requests both miss the idempotent read (§1 step 2), both build a row,
both call `save()`. The unique constraint lets exactly one `INSERT` through; the loser's `save()`
throws `DataIntegrityViolationException`, caught in `CreatorNudgeService` (§1 step 8) and converted
into a fresh read of the winner's now-existing row — the loser's caller gets today's real
suggestion back, never a 500. **This closes the double-write race completely.**

**What it does NOT close by itself: double AI-spend.** Both concurrent requests may already have
called `CreatorSuggestionAiClient` before either `INSERT` resolves — only one row ever gets shown/
persisted, but the AI call itself could fire twice under a true race. Closing that too needs a
MySQL advisory lock (`GET_LOCK('creator_nudge:' || creatorProfileId, 0)` around §1 steps 3-7,
released in a `finally`) — I've deliberately not decided this for the plan; it's a real cost/
complexity tradeoff (advisory locks add a failure mode of their own — lock-acquire timeout —that
needs its own fallback), not a correctness gap. Flagged in §6.6 for Priya.

---

## 6. Open questions for Priya + API contract to freeze for Ananya

1. ~~`creator_nudge_log` column-list conflict (§2.3)~~ — **RESOLVED, Priya R2 (Option b).**
   Canonical columns are `id, creator_profile_id, trend_id, match_score, theme, headline,
   content_idea, message_source, prompt_version, shown_at, dismissed_at, acted_at, created_at`
   (§2.3). Meera is rebuilding her migration draft from this same list in parallel.
2. **No new `/creator/copilot/ig/connect` route (§3 reconciliation)** — I'm reading spec §2.5's
   mention as describing the existing (now-fixed) `/meta/oauth/callback`, matching Ananya's FE plan
   which already assumes reuse of `api.metaOAuth.authorize()`. Confirm I'm not missing a reason a
   distinct route is actually wanted.
3. **The already-broken creator IG-connect path (§0) is a live-system finding, not just a Tier-1
   planning detail** — recommend Meera run a one-off prod data check (how many `meta_oauth_tokens`
   rows have `creator_profile_id` set at all, and whether any creator anywhere shows as
   "connected") before Tier-1 build starts, independent of whether this ships as part of Tier-1 or
   its own bug ticket.
4. **`CreatorSuggestionAiClient`'s service-token claim shape (§3.9)** — no `workspace_id` to carry.
   Needs joint design with the Python-route-owning agent (mint on a `creator_profile_id` claim
   instead? new scope name in `ENDPOINT_SCOPES`, e.g. `creator_suggestion`, mirroring the existing
   `trendspark`/`brand_safety` service-scope entries in `influora-ai/app/auth/service_token.py:43-54`?)
   before either side can write code that round-trips.
5. ~~`CreatorSuggestionAiDtos.SuggestionResponse` shape (§4)~~ — **RESOLVED, Priya R2 (item 1):**
   confirmed 2 fields, `{headline, content_idea}`, matching my original assumption exactly — no
   change needed to §4's DTOs. The templated fallback returns the same tuple shape (§1 step 6),
   so `CreatorNudgeService` never branches into "AI gives 2 fields, template gives 1."
6. **AI double-spend under the OAuth race (§5)** — DB constraint closes the double-write for
   certain; whether to also add a distributed lock to close the double-spend is a cost/complexity
   call for Priya.
7. **Zero-posts/zero-themes "silence vs. post-first message"** — same explicit Ash+Tejas blocker
   the spec already calls out (§6/§8 of the build spec). My `pendingTagging()`/`noSuggestionToday()`
   split (§1 steps 3-4) is my best mechanical guess at how to compute the distinction — the actual
   copy is still blocked on that ruling, same as Ananya's plan already notes.
8. **`MetaConnectionService` needs a controller (§3.7)** — currently orphaned. Is wiring
   `GET/POST /creator/copilot/ig/status|disconnect` in scope for Tier-1, or does
   `connected-accounts.tsx` already read connection state from some other working endpoint I
   haven't found? Worth confirming there isn't a third, currently-functional Meta-status surface
   before assuming mine is the first one.
9. **`CreatorCaptionSyncJob` split (§2.6)** — spec's §1 diagram bundles "fetch captions from Meta"
   and "tag cached captions" into one line item; I'd split them into two scheduled jobs rather than
   overload `CreatorThemeTaggingJob` with both. Confirm or reject.
10. ~~`expiresAt` on the suggestion DTO~~ — **RESOLVED, Priya R2 (item 2):** confirmed computed
    from `shown_at` (end of UTC day) at read time in `toDto()`, not a stored column (§2.3).

### API contract to freeze for Ananya (matches her plan's existing assumptions)
```
GET  /api/v1/creator/copilot/suggestion/today
  → 200 { suggestion: { id, theme, headline, contentIdea, expiresAt } | null,
          status: 'pending_tagging' | 'ready' | 'no_suggestion_today' }

POST /api/v1/creator/copilot/suggestion/:id/dismiss  → 200 {}
POST /api/v1/creator/copilot/suggestion/:id/acted    → 200 {}
```
IG connect/disconnect/status: no new copilot-specific routes (§3 reconciliation) — Ananya's plan
should keep using the existing `api.metaOAuth.authorize()` path pending §6.2/§6.8's answers, and
`accountType` on `MetaConnectionState` (spec §3.5, Ananya's plan §6-item-7) is a `src/lib/api.ts`
change on the FE data-layer agent's side reflecting whatever `MetaConnectionService`/its future
controller (§6.8) ends up exposing — not a new field I'm adding to the contract above yet, pending
that open question.

---

## 7. Files summary

**New:**
- `service/creatorcopilot/CreatorNudgeService.java`
- `service/creatorcopilot/CreatorMetaOAuthService.java`
- `domain/entity/CreatorCaptionCache.java`, `domain/entity/CreatorNudgeLog.java`
- `domain/enums/CaptionTagStatus.java`
- `repository/CreatorCaptionCacheRepository.java`, `repository/CreatorNudgeLogRepository.java`
- `config/CreatorCopilotProperties.java`, `config/CreatorSuggestionAiProperties.java`
- `integration/ai/CreatorSuggestionAiClient.java`, `integration/ai/dto/CreatorSuggestionAiDtos.java`
- `job/CreatorThemeTaggingJob.java` (+ `CreatorCaptionSyncJob.java` pending §6.9)
- `web/CreatorCopilotController.java`
- `web/dto/creatorcopilot/CreatorCopilotDtos.java` (suggestion response shapes)

**Modified (additive only, brand path behavior unchanged):**
- `domain/entity/CreatorProfile.java` (theme_tags field)
- `domain/entity/MetaOAuthToken.java` (workspace_id nullable)
- `repository/MetaOAuthTokenRepository.java` (new overload)
- `integration/meta/oauth/MetaTokenStorage.java` (new creator-owned methods)
- `web/MetaOAuthController.java` (fix the broken `storeToken` call, §0/§3)
- `service/MetaConnectionService.java` (drop `workspaceId` param, §3.7)

**Not mine (second BE agent / Ash):**
- `influora-ai/app/routes/creator_suggestion.py` (or wherever the new route lands)
- `influora-ai/app/auth/service_token.py` `ENDPOINT_SCOPES` entry (§6.4)
- Prompt file + guardrail tests (spec §7/§6 of the build spec)
