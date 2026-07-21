# Meera Verification — Creator AI Co-pilot Tier-1 (pre-code, verify/draft only)

**Verifier:** Meera (DB/DevOps) · **Date:** 2026-07-21 · **Mode:** read-only verification + draft
artifacts. No migrations applied, no shipping code touched, no writes to any real DB.

**UPDATE — R2 re-verify (same date):** Priya ruled R2 in favor of Option (b) (§1.1 below is now
resolved). `creator_nudge_log` migration rebuilt to the R2 column list. **Overall verdict flips to
✅ PASS — package is code-ready.** See "§4. R2 re-verify result" at the bottom for the full check;
everything above this notice is the original R1 pass, kept for the record (the P0 it found is what
prompted R2).

**Read:** `wiki/build/creator-copilot-priya-review-r1.md`, `wiki/build/creator-copilot-API-CONTRACT.md`,
`wiki/build/creator-copilot-be-services-plan.md`, `wiki/ai-review/creator-ai-copilot-tier1-build-spec.md`,
plus the live tree (`influora-api/src/main/resources/db/migration/`, `MetaOAuthController.java`,
`MetaOAuthToken.java`, `GlobalExceptionHandler.java`, `NudgeLog.java`, `Trend.java`,
`CreatorProfile.java`, `AuthPrincipal.java`, `application.yml`, `docker-compose.yml`).

---

## Overall verdict

| Area | Verdict |
|---|---|
| **Migration V-numbering** | ✅ PASS — no collision |
| **DDL buildability / repo convention fit** | ✅ PASS |
| **Cross-plan entity ↔ API-contract consistency** | ❌ **FAIL — 1 real P0 gap** (below), 1 minor (P2) |
| **Live-bug claim (V20 NOT NULL + null workspaceId)** | ✅ VERIFIED, confirmed real |
| **Prod blast-radius check** | SQL drafted, NOT run — see §2. One methodology limitation found. |
| **Migration drafts** | Written to `wiki/build/migrations-draft/`, not applied |

**Bottom line:** my three deliverables (migration drafts + prod-check SQL) are ready. But I will
not silently build `CreatorNudgeLog`'s `toDto()` mapping against a contradiction — flagging it back
to Vikram/Priya per §1.1 below rather than guessing a resolution, since API-CONTRACT.md is frozen
and any fix to this gap is itself a new frozen version, not something I can pick a side on.

---

## 1. Logical verification

### 1.1 ❌ P0 — `creator_nudge_log`'s canonical columns cannot produce the frozen `SuggestionDto`

Priya's Conflict-2 ruling (`priya-review-r1.md` lines 38-52) froze `creator_nudge_log` as:
```
id, creator_profile_id, trend_id, match_score, message, message_source,
prompt_version, shown_at, dismissed_at, acted_at, created_at
```
This mirrors `NudgeLog` 1:1 (verified — `domain/entity/NudgeLog.java:47-52` has exactly one
`message TEXT` column, no separate headline/body split; `TrendSparkDtos.NudgeResponse` also carries
a single `message` field, confirmed by grep — the brand-side precedent this mirrors is genuinely
single-field).

But `API-CONTRACT.md` §2 (frozen the same day) requires:
```ts
DailySuggestion { id, theme, headline, contentIdea, expiresAt }
```
— three fields (`theme`, `headline`, `contentIdea`) that have **no corresponding column** in the
frozen entity shape. And `be-services-plan.md` §4's `CreatorSuggestionAiClient.requestSuggestion`
already returns `SuggestionCopy(String headline, String contentIdea)` — two structured fields from
the model — with nothing specifying how those two fields collapse into the single `message` TEXT
column, or how `theme` (not a column at all — only `trend_id`/`match_score` are) gets derived.

This isn't cosmetic. §1.1 of `API-CONTRACT.md` states dismissed/acted suggestions **keep returning
the same suggestion object for the rest of the day** via a plain re-read of the stored row (no
recomputation) — `CreatorNudgeService` step 2 (`be-services-plan.md` §1) returns
`toDto(existingRow)` directly on the idempotent-read path. `toDto()` as specified **cannot**
reconstruct `theme`/`headline`/`contentIdea` from a row that only has `trend_id`, `match_score`,
and one `message` string:
- `theme` — the specific matched theme is computed transiently inside
  `themeMatchService.score(trend, creatorThemeTagsJson)` and never persisted anywhere (not on
  `Trend` — confirmed via `Trend.java`, which has `themesJson` (its own JSON array), not a
  singular `theme` — and not on `CreatorNudgeLog`).
- `headline` + `contentIdea` — two AI-returned fields with no column to round-trip through.

**This blocks BE-services code**, not just my migration. I'm building the migration to the ruling's
literal column list as directed (see `wiki/build/migrations-draft/V20260721140000__creator_nudge_log.sql`,
which flags this inline), but the fix is one of:
(a) shrink the DTO to a single `message`/`copy` field (matches the entity, matches the brand
    precedent, contradicts the "confirmed default: 2 fields" note in the ruling), or
(b) add `theme`/`headline`/`content_idea` columns to `creator_nudge_log` (contradicts the ruling's
    "mirror NudgeLog 1:1" rationale, and is itself a new frozen contract version per
    `API-CONTRACT.md`'s own closing rule — "a wire-shape change... requires a new frozen version,
    not a silent amendment").
Recommend routing back to Priya/Vikram before BE-services code starts — this is exactly the kind
of cross-track contradiction Stage-2 reconciliation exists to catch, and it slipped through because
Conflict 2 (columns) and the API contract were each frozen against their own source doc without a
final cross-check against each other's exact field list.

### 1.2 P2 (minor) — `CreatorCopilotProperties.dailyCap` vs. yaml key name

`be-services-plan.md` §2.5 declares `private int dailyCap = 1;` bound at prefix
`influora.creator-copilot`, but the build spec's own yaml sketch (§4) uses the key
`max-suggestions-per-creator-per-day`. Spring Boot relaxed binding maps yaml keys to field names
positionally (`max-suggestions-per-creator-per-day` → `maxSuggestionsPerCreatorPerDay`), so as
written these two won't bind to each other — the property will silently fall back to the
hardcoded default (`1`) regardless of the env var. Confirmed clean (no existing
`creator-copilot` block in `application.yml` today, so this is a fresh mismatch, not a live bug).
Low-stakes (default happens to be `1` either way) but worth a one-line fix before Vikram writes the
class: either rename the field to `maxSuggestionsPerCreatorPerDay` or rename the yaml key to
`daily-cap`.

### 1.3 Migration V-numbering — ✅ no collision

Verified the real `db/migration/` directory (not just the plan's claim): the highest existing
timestamp-style migration is `V20260718190000__campaign_hype_config.sql` — matches the build spec's
own claim ("latest is V20260718190000"). The three proposed filenames
(`V20260721120000`, `V20260721130000`, `V20260721140000`) are all higher and none exist yet. Also
confirmed the legacy sequential-`V<n>` numbering (`V20__meta_oauth_tokens.sql`, `V65`, `V68`) is a
separate, older convention that stopped once the timestamp convention started — no risk of a
sequential `V20`/`V21`-style collision either, since new migrations don't use that scheme anymore.

### 1.4 Everything else checked and consistent
- `creator_profiles` has no pre-existing `theme_tags` column (checked `V6__creators_collaborations.sql`
  + the live entity) — clean additive `ALTER`, exact precedent already exists at
  `brand_profiles.theme_tags` (`V51__trendspark.sql:21-22`, `JSON NULL`, same shape).
- Generated-column + unique-key pattern for the daily cap (`be-services-plan.md` §5) is not novel —
  already used in this exact repo (`V62__creator_bank_account_primary.sql:36-41`,
  `V20260715140000__platform_commission_invoice.sql`) and MySQL 8.0 (`docker-compose.yml`, `mysql:8.0`
  image) supports generated columns since 8.0.13 — no engine-version risk.
- Endpoint paths: all three routes in `API-CONTRACT.md` §1 match `CreatorCopilotController`'s three
  `@*Mapping` methods 1:1 (`be-services-plan.md` §5) and match Priya's Conflict-1 ruling. No
  `ig/connect` route anywhere, consistently, across all four docs — the one place the spec's
  original §2.5 mention could have caused a stray build, it didn't.
- `creator_captions` naming: Vikram's plan explicitly reconciles the spec's `creator_caption_cache`
  label vs. the actual SQL table name (`creator_captions`) already — not a live contradiction, just
  flagged-and-resolved prose, matches what I drafted.

---

## 2. Live-bug prod check (READ-ONLY — specified, NOT executed against any real DB)

**Confirmed against the live tree** (not just the plan's citation):
- `V20__meta_oauth_tokens.sql:7` — `workspace_id VARCHAR(26) NOT NULL`. Confirmed.
- `MetaOAuthToken.java:26-27` — `@Column(name = "workspace_id", nullable = false, ...)`. Confirmed.
- `MetaOAuthController.java` `/callback` (lines ~66-99) — creator-only (`requireCreator`), resolves
  `CreatorProfile`, calls `tokenStorage.storeToken(creatorProfile.getId(), principal.getWorkspaceId(), ...)`.
  Confirmed, unchanged from the ruling's citation.
- `AuthPrincipal.getWorkspaceId()` (`security/AuthPrincipal.java:36-38`) is a plain field getter —
  the `AnalyticsUsageCapInterceptor.java:42` comment ("a CREATOR principal has no `workspaceId`")
  confirms this resolves to `null` for a CREATOR principal at construction time.
- **Net: verified real.** Every creator completing `/meta/oauth/callback` passes `null` into a
  `NOT NULL` column → insert fails. This path cannot have ever succeeded for a creator.

**One methodology limitation found, worth flagging before anyone runs this:**
`GlobalExceptionHandler.java` has a **dedicated** `@ExceptionHandler(DataIntegrityViolationException.class)`
(lines 92-97) that returns a clean 409 and only does `log.error(...)` (application/container log,
not a DB table). It does **not** flow through `handleGeneric`'s catch-all (line 114+), which is the
*only* path that persists into the queryable `error_log` table
(`V20260718170000__admin_error_log.sql`). **This means the failed-insert history for this specific
bug is NOT recoverable from any SQL query — it only ever existed in server logs**, which may or may
not still be retained. The SQL below can confirm "no anomalous rows exist today" (sizing whether
cleanup is needed) but **cannot** size "how many creators have attempted IG-connect to date" from
the DB alone, contra what a naive read of the ruling's directive might assume. If that historical
attempt count matters, it needs a log-aggregator search (outside my read-only-DB scope), not SQL.

### Exact read-only SQL (for Swapnil/whoever authorizes prod DB access — NOT run by me)

```sql
-- 1. Baseline: total rows in the table at all
SELECT COUNT(*) AS total_rows FROM meta_oauth_tokens;

-- 2. Rows tied to any creator_profile_id (any creator-owned attempt that actually committed)
SELECT COUNT(*) AS creator_linked_rows
FROM meta_oauth_tokens
WHERE creator_profile_id IS NOT NULL;

-- 3. Of those, split by whether workspace_id is NULL (the shape the bug would have produced,
--    which the NOT NULL constraint should make impossible today) vs non-NULL (rows that must have
--    come through some other write path -- flag for investigation if this bucket is non-empty)
SELECT
  (workspace_id IS NULL) AS workspace_id_is_null,
  COUNT(*) AS row_count
FROM meta_oauth_tokens
WHERE creator_profile_id IS NOT NULL
GROUP BY (workspace_id IS NULL);

-- 4. Sample for manual eyeballing if #3 shows anything unexpected -- safe to view, no plaintext
--    token ever appears in this table (encrypted_access_token is AES-256-GCM ciphertext per the
--    V20 migration's own header comment)
SELECT id, creator_profile_id, workspace_id, revoked, created_at, updated_at
FROM meta_oauth_tokens
WHERE creator_profile_id IS NOT NULL
ORDER BY created_at DESC
LIMIT 50;

-- 5. Non-revoked creator-linked rows -- would indicate a creator that could show "connected"
--    despite MetaConnectionService's own separate WHERE workspace_id = NULL bug reporting
--    "disconnected" regardless (that service bug is a false-negative direction, not a false
--    positive, so this is a belt-and-suspenders check, not the primary signal)
SELECT COUNT(*) AS non_revoked_creator_rows
FROM meta_oauth_tokens
WHERE creator_profile_id IS NOT NULL AND revoked = FALSE;
```

**Expected result, per Priya's own directive #3 ("failed inserts leave no rows... confirm, don't
assume")**: queries 2-5 should all return 0 / empty. If any return non-zero, escalate before the
OAuth-flip migration ships — do not assume it's benign.

**I did not run this against any database, local or prod.** This is the specification only, per the
task's explicit instruction.

---

## 3. Migration drafts (NOT applied)

Written to `wiki/build/migrations-draft/`:
- `V20260721120000__creator_profile_theme_tags.sql` — additive, clean, no open questions.
- `V20260721130000__creator_captions.sql` — additive, clean, no open questions.
- `V20260721140000__creator_nudge_log.sql` — built to Priya's Conflict-2 canonical column list as
  directed; **carries an inline comment flagging the §1.1 DTO-shape gap** so nobody applies it
  believing the schema already supports `theme`/`headline`/`contentIdea` round-tripping.

All three follow repo DDL convention verified against recent precedent
(`V47__creator_bank_accounts.sql`, `V62__creator_bank_account_primary.sql`,
`V20260718190000__campaign_hype_config.sql`): `VARCHAR(26)` ids, `TIMESTAMP` (not `DATETIME`) for
audit columns, `idx_`/`uq_`/`fk_` naming prefixes, `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`
on every `CREATE TABLE`, and a rationale header comment on every file.

None of these three files have been applied — no `flyway:migrate`, no local DB touched, no prod
touched.

---

## Recommendation to Arjun (superseded by §4 below — kept for the record)

1. ~~Route the §1.1 P0 gap back to Vikram + Priya before BE-services code starts~~ — **done, Priya
   ruled R2 Option (b).**
2. §1.2 (yaml key mismatch) is a one-line fix, non-blocking, flag to Vikram in passing. **Still
   open** — R2 didn't touch this, unrelated to the nudge-log column question.
3. The prod-check SQL in §2 is ready for whoever Priya/Swapnil authorizes to run it — I have not
   and will not run it myself without that authorization, per the task's read-only/specify-don't-execute
   instruction. **Still standing, unaffected by R2.**
4. ~~Migration drafts in §3 are reviewable now; once §1.1 resolves, the `creator_nudge_log` draft
   may need a revision pass~~ — **done, see §4.**

---

## 4. R2 re-verify result (Priya ruling: Option (b) — grow the table)

**Scope of this pass:** re-check the entity↔`API-CONTRACT.md` reconciliation only, per Arjun's
routing. `API-CONTRACT.md` itself is unchanged (still v1, no re-freeze) — R2 only changed which
side of the R1 contradiction bends: the entity now grows to match the already-frozen DTO, instead
of the DTO shrinking.

### 4.1 Migration rebuilt

`wiki/build/migrations-draft/V20260721140000__creator_nudge_log.sql` rebuilt to Priya's R2 canonical
column list:
```
id, creator_profile_id, trend_id, match_score, theme, headline, content_idea,
message_source, prompt_version, shown_at, dismissed_at, acted_at, created_at
```
Changes from the R1 draft: `message TEXT` → split into `theme VARCHAR(32)` + `headline VARCHAR(255)`
+ `content_idea TEXT`. Everything else (`message_source`, `prompt_version`, timestamps, FKs) is
byte-identical to R1.

- `theme VARCHAR(32)` — checked against `influora-api/src/main/resources/trendspark/theme-taxonomy.json`
  (the actual closed vocabulary `ThemeMatchService`/`CreatorNudgeService` draw from): 40 entries,
  longest is `togetherness`/`authenticity` at 13 chars. 32 gives headroom without being wide enough
  to invite free text — consistent with `message_source`/`prompt_version`'s existing short-VARCHAR
  convention on this same table.
- `headline VARCHAR(255)` — matches the repo's existing title-column precedent
  (`notifications.title`, `V17__notifications.sql:7`, also `VARCHAR(255) NOT NULL`).
- `content_idea TEXT` — free-form, same type the `message` column it replaces used.
- **Restored `idx_cnl_creator_day`** (build spec §4's original index, which the R1 draft had
  dropped as redundant with the unique key's leading column) per this task's explicit instruction to
  keep both the index and the unique constraint — now `CREATE INDEX idx_cnl_creator_day ON
  creator_nudge_log (creator_profile_id, shown_at)` sits alongside the generated-column
  `uq_creator_nudge_day (creator_profile_id, shown_day)` unique key, unchanged from R1.

DDL convention (VARCHAR(26) ids, TIMESTAMP audit columns, `idx_`/`uq_`/`fk_` naming,
`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`) unchanged from the R1 draft,
still matches `V47`/`V62`/`V20260718190000` precedent. Still NOT applied — draft only.

### 4.2 P0 closure check — ✅ CLOSED

Walking `toDto(CreatorNudgeLog row)` → `SuggestionDto` field-by-field against the rebuilt schema:

| `SuggestionDto` field (API-CONTRACT.md §2, frozen v1 — unchanged) | Source under the R2 schema |
|---|---|
| `id` | `row.id` — direct column, no gap |
| `theme` | `row.theme` — direct column, no gap (previously: no column existed at all) |
| `headline` | `row.headline` — direct column, no gap (previously: only a single `message` existed) |
| `contentIdea` | `row.contentIdea` (`content_idea` column) — direct column, no gap (previously: same `message` collision as headline) |
| `expiresAt` | **Not a column, by design** — computed at `toDto()` time as end-of-UTC-day derived from `row.shownAt`. This matches `API-CONTRACT.md` §2's own stated semantics verbatim ("end of the creator's current UTC day... display-only for Tier-1... not actively consumed by the hook") and `be-services-plan.md`'s confirmed default ("`expiresAt` = end of the creator's current UTC day"). No persistence needed for a value that's never queried/filtered on — this was never part of the P0, just confirming it stays a non-issue under the new schema too. |

All 5 DTO fields are now producible from a single row with no reconstruction ambiguity, on both the
write path (`CreatorSuggestionAiClient.SuggestionCopy(headline, contentIdea)` writes straight into
the two new columns, `theme` is the already-computed best-match value from
`themeMatchService.score`) and the idempotent same-day re-read path (`API-CONTRACT.md` §1.1 — GET
`.../today` returns the same suggestion all day, now a straight column read, no recomputation
needed for any of the 5 fields). **The §1.1 P0 is closed.**

One implementation-level note, non-blocking (not a schema gap, flagging for whoever writes
`CreatorNudgeService.templatedFallback`): the fallback-copy method currently mirrors
`TrendSparkNudgeService.templatedFallback`'s single-string return (be-services-plan §1 step 6) and
will need to return a `(headline, contentIdea)` pair instead, since both columns are `NOT NULL`.
Straightforward split, no new open question, not a re-verify blocker.

### 4.3 Final verdict

| Area | R1 | R2 |
|---|---|---|
| `creator_nudge_log` ↔ `API-CONTRACT.md` reconciliation | ❌ FAIL (P0) | ✅ **PASS — closed** |
| Migration V-numbering / collision | ✅ PASS | ✅ PASS (unchanged) |
| DDL convention fit | ✅ PASS | ✅ PASS (unchanged) |
| Live-bug verification | ✅ PASS | ✅ PASS (unchanged, not in R2 scope) |
| P2 yaml-key mismatch (§1.2) | flagged | **still open, non-blocking** |

**Overall: ✅ PASS. Package is code-ready** for BE-services (Vikram) to build against. `API-CONTRACT.md`
correctly stayed v1 — this was an entity-side fix, not a wire-shape change, so no re-freeze was
needed, consistent with the contract's own rule ("a wire-shape change... requires a new frozen
version" — the wire shape never changed, only which table columns back it).
