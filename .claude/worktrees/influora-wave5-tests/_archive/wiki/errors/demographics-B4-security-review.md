# Security Review: Wave B Task B4 — Audience Demographics

**Date:** 2026-07-07
**Reviewer:** Kabir (Offensive Security / Red-Team Lead)
**Prior QA:** Kavya — APPROVED (`wiki/errors/demographics-B4-review.md`)
**Verdict:** ✅ **SIGN-OFF** — 0 CRITICAL / 0 HIGH / 0 MEDIUM; 4 LOW / informational hardening notes, none blocking.
**Scope:** authorized review of Sage Digital's own code. Review only — no code changed, Maven not run.

---

## Attack 1 — Cross-workspace leak (the acceptance gate): PASS

Traced the full endpoint path adversarially. The isolation holds.

- **Endpoint is authenticated.** `/analytics/**` is NOT in the `permitAll()` list in `SecurityConfig.java:72-100`; it falls through to `.anyRequest().authenticated()` (`SecurityConfig.java:101-102`). The new `GET /{creatorId}/demographics` (`AnalyticsController.java:76-81`) inherits the same auth as `/metrics` and `/scores`.
- **Workspace is server-derived, never client-supplied.** `AnalyticsService.getCreatorDemographics` line 221: `workspaceId = brandContext.requireBrandWorkspace(principal).getId()` — resolved from the authenticated principal, not from any request field.
- **Resolve-then-scope gate fires before any read.** Line 222-223 calls `metricsAuthorizationService.resolveAuthorizedCreatorProfileId(workspaceId, creatorId)`, which (`MetricsAuthorizationService.java:66-74`) does `metaOAuthTokenRepository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(...)` and throws `FORBIDDEN` if no active token pairs this workspace to this creator. Only the returned id reaches `findFirstByCreatorProfileIdOrderByTimeDesc` (line 225-227).
- **id-guessing defeated.** `creatorId` is an attacker-controlled path var, but a guessed/foreign creator id yields no `(workspaceId, creatorProfileId)` token row for the caller's workspace → `FORBIDDEN`. The token pairing (`uq_meta_oauth_workspace_creator` on `(workspace_id, creator_profile_id)`, V20) is the DB-enforced entitlement.
- **Resolve-then-scope not bypassable.** `resolve...` throws *before* returning; the service uses the return value (not the raw path var) for the repository call. No path reaches the finder with an unauthorized id.
- **No unguarded second reader.** Grep of the whole tree: `findFirstByCreatorProfileIdOrderByTimeDesc` on `AudienceDemographicsRepository` is called from exactly one non-test site — `AnalyticsService.java:226` (guarded). The job only ever calls `save()`. No raw finder is reachable without the authz resolve.
- **Job provenance matches B1.** `AudienceDemographicsJob.runPoll` iterates `tokenRepository.findByRevokedFalseAndExpiresAtAfter(...)` and attributes each written row with `creatorProfileId = tokenRow.getCreatorProfileId()` (lines 114-116, 198) — token-derived, never user-supplied, identical provenance to `MetricsPollingJob`. No user-controlled attribution.
- **Shared row across two authorized brands is by-design, not a leak.** `audience_demographics` has no `workspace_id` column (ADR: creator metrics are creator-owned facts). If a creator is connected to two workspaces, both authorized brands read the same single snapshot row. Both passed the token gate; both are entitled to that creator's aggregate audience. Consistent with `/metrics` + `/scores`.
- **Revocation is honored per-read.** The gate requires a *non-revoked* token at read time. A workspace that revokes loses read access immediately (rows persist but become unreadable to it). No stale-access leak.

---

## Attack 2 — JSON deserialization (`readBreakdown`, 4 paths): PASS (well-defended)

`AnalyticsService.readBreakdown` (lines 243-254) is the flagged surface. It is robust:

- **No 500 on hostile/malformed JSON.** `catch (Exception e)` → `log.warn(... e.getMessage())` → `return null` (lines 250-253). A malformed column degrades to a null breakdown, never an unhandled exception.
- **Per-column isolation.** Each of the 4 breakdowns is parsed independently (lines 236-239). One poisoned column nulls only *that* breakdown; the other three still return, `hasData` stays `true`. **A single poisoned row cannot take down the dashboard endpoint** — worst case one breakdown renders null.
- **The persisted content is self-produced, not raw external input.** The only writer of these columns is `AudienceDemographicsJob` via `JsonLists.toJsonObject(Map<String,Long>)` (job lines 200-203, `JsonLists.java:26-35`). Meta's raw response is first bound into `Map<String,Long>` by Jackson (`AudienceDemographicsResponse.DemographicValue.value`, DTO line 18) and normalized to a flat `{string:long}` map in `extractBreakdown` (job 236-249) *before* serialization. Hostile *structural* JSON from Meta (deep nesting, type confusion) is flattened away before it can ever reach the column. The read path parses JSON our own serializer produced.
- **Type confusion is caught.** Reading into `Map<String,Long>`: a stringified number coerces; a non-numeric or fractional value throws `InvalidFormatException` → caught → null. Graceful.
- **Nesting/size DoS mitigated by the framework.** Boot 3.3.5 → Jackson 2.17 enforces `StreamReadConstraints` (default max nesting 1000, max string 20 MB) that convert pathological documents into `StreamConstraintsException` (an `Exception`, so caught). No `StackOverflowError` from deep nesting under the current stack. Map size is bounded by Meta's aggregate cardinality (~195 countries, bounded locales/cities/age-gender buckets) and MySQL's `max_allowed_packet`.
- **Log hygiene.** The warn logs only `e.getMessage()`, not the JSON payload — no demographic data or PII spilled into logs.

**LOW-1 (defense-in-depth, not currently reachable):** `catch (Exception e)` does not catch `Error` (`StackOverflowError`/`OutOfMemoryError`). This is safe today because (a) the content is self-produced and flat, and (b) Jackson's `StreamReadConstraints` turn pathological input into `Exception`s. It would only become reachable if a future change disabled `StreamReadConstraints` on the local `MAPPER` (`AnalyticsService.java:60`) or if these columns were ever populated from a less-trusted path. No action required for B4; note for whoever next touches the mapper config.

---

## Attack 3 — PII posture: PASS

- **Only aggregate distributions are requested.** `InstagramInsightsClient.getAudienceDemographics` (line 70-73) requests `AUDIENCE_METRICS = "audience_city,audience_country,audience_gender_age,audience_locale"` with `period=lifetime` (line 23). These are bucket→count distributions. **No follower ids, usernames, handles, or any row-level/identifying field is fetched or stored.** The 4 persisted JSON columns hold exactly those aggregate maps.
- **Sub-100-follower skip cannot fabricate.** Meta withholds audience insights server-side for accounts under its 100-follower privacy threshold. The job double-guards: empty/null response → skip (`AudienceDemographicsJob.java:171-179`), and all-four-breakdowns-empty → skip (lines 186-192). Both `return false` without calling `save()` — no fabricated zero row.

**LOW-2 (informational, Meta-side):** The skip relies entirely on Meta's server-side threshold; there is no app-side follower-count gate. If Meta ever returned a *partial* non-empty payload for a sub-threshold account, the job would persist it. Also, very-low-count buckets (e.g. `country: {"VA": 1}`) are theoretically de-anonymizing, but k-anonymity/minimum-cell enforcement is Meta's responsibility, not something B4 can or should re-derive. Out of scope, no change needed — flagged for awareness only.

---

## Attack 4 — Migration V25 (FK/cascade, DDL surprises): PASS (consistent)

`V25__audience_demographics.sql`:

- **FK behavior is consistent with siblings.** `fk_audience_demographics_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id)` (line 52) has **no `ON DELETE` clause → MySQL InnoDB default RESTRICT/NO ACTION.** Identical to `fk_creator_metrics_creator` (V21:35), `fk_media_metrics_creator` (V21:63), and `fk_creator_scores_creator` (V22:52) — all no `ON DELETE`. So deleting a `creator_profile` that has demographics rows is **blocked (ER_ROW_IS_REFERENCED_2 / 1451), not cascaded, not orphaned** — same as every sibling. No surprise.
- **Column types/defaults match siblings.** 4× `JSON NULL`, `DATETIME(6)` for `time`/`fetched_at`, `TIMESTAMP DEFAULT CURRENT_TIMESTAMP` for `created_at`, `utf8mb4 / utf8mb4_unicode_ci`, InnoDB — all mirror V21/V22.
- **Read is index-supported.** `INDEX idx_audience_demographics_creator_time (creator_profile_id, time)` (line 51) supports `findFirstByCreatorProfileIdOrderByTimeDesc` (ref on `creator_profile_id`, backward scan on `time`) — no full table scan on the brand read path.
- **No UNIQUE constraint (intentional).** Immutable-snapshot design means rows accumulate; there is no retention/pruning policy. Not a security issue — ops/growth note only.

---

## Findings summary

| ID | Severity | Area | Blocking? |
|----|----------|------|-----------|
| LOW-1 | LOW / defense-in-depth | `readBreakdown` `catch(Exception)` doesn't catch `Error`; safe today via Jackson `StreamReadConstraints` + self-produced content | No |
| LOW-2 | LOW / informational | Sub-100 skip trusts Meta's server-side threshold entirely; no app-side follower gate / cell-minimum | No |
| LOW-3 | LOW / ops | No UNIQUE / no retention on immutable snapshot table → unbounded row growth | No |
| INFO | — | Endpoint auth, resolve-then-scope, per-column JSON isolation, token-derived provenance all verified correct | — |

No CRITICAL, HIGH, or MEDIUM. B4 is cleared from a security/workspace-isolation standpoint.

---

## Probes for Meera's live-MySQL check

Beyond Kavya's structural checklist, on the throwaway DB specifically verify:

1. **FK is RESTRICT, not CASCADE (the isolation-adjacent one).** Insert a `creator_profiles` row + an `audience_demographics` row for it, then `DELETE FROM creator_profiles WHERE id=<that id>` → **expect error 1451 (ER_ROW_IS_REFERENCED_2)**. Confirm the delete is *blocked*, not silently cascading away demographics rows. Cross-check that `information_schema.REFERENTIAL_CONSTRAINTS.DELETE_RULE` for `fk_audience_demographics_creator` = `NO ACTION` / `RESTRICT`, matching the sibling FKs.
2. **JSON typing.** `SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_NAME='audience_demographics' AND DATA_TYPE='json'` → expect exactly 4 rows (age_gender/country/city/locale).
3. **Read uses the index, not a scan.** After inserting a couple of snapshots for one creator, `EXPLAIN SELECT * FROM audience_demographics WHERE creator_profile_id=? ORDER BY time DESC LIMIT 1` → expect `ref` on `idx_audience_demographics_creator_time` with a backward index scan, **not** `ALL` (full table scan).
4. **`explicit_defaults_for_timestamp` sanity.** Confirm `created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP` behaves as intended on the *target server's* mode/version (siblings share this, but validate once on the real server, not just H2/test).
5. **utf8mb4 round-trip.** Insert a `city_breakdown` / `locale_breakdown` value with non-ASCII keys (e.g. `{"São Paulo, BR": 12}`, `{"日本": 5}`) and read it back through the entity → confirm no mojibake / no truncation under `utf8mb4_unicode_ci`.
