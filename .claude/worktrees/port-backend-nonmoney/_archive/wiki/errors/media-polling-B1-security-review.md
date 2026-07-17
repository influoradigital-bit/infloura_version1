# Security Review: Wave B Task B1 — Media Metrics Polling

Reviewer: Kabir (Offensive Security / Red-Team Lead)
Date: 2026-07-07
Scope: authorized — Sage Digital's own code (`influora-api/`)
Predecessor gate: Kavya QA APPROVED (`wiki/errors/media-polling-B1-review.md`)
Acceptance gate under review (REMAINING_WORK_PLAN.md, Wave B/B1): "Kabir confirms no cross-workspace leak in the batch."

## VERDICT: SIGN-OFF

No cross-workspace leak. No blocking security defect. B1 is a write-only, system-wide scheduled sweep that reuses the already-reviewed (workspace, creatorProfileId) pairing from the token row and introduces no new read/DTO/authorization surface. Findings below are LOW / INFORMATIONAL defense-in-depth notes and one non-security correctness observation for Vikram — none block merge.

Files reviewed:
- `influora-api/src/main/java/com/influora/job/MetricsPollingJob.java` (the diff, +203/-18)
- `influora-api/src/main/java/com/influora/integration/meta/client/InstagramInsightsClient.java`
- `influora-api/src/main/java/com/influora/integration/meta/client/MetaGraphApiClient.java`
- `influora-api/src/main/java/com/influora/integration/meta/oauth/MetaTokenStorage.java`
- `influora-api/src/main/java/com/influora/integration/meta/service/MetaRateLimitTracker.java`
- `influora-api/src/main/java/com/influora/integration/meta/dto/InstagramMediaResponse.java`
- `influora-api/src/main/java/com/influora/domain/entity/MediaMetric.java`
- `influora-api/src/main/java/com/influora/repository/MediaMetricsRepository.java`
- `influora-api/src/main/java/com/influora/service/MetricsAuthorizationService.java`
- `influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java` (read-path authz confirmation)

---

## Gate 1 — creatorProfileId provenance & cross-contamination: PASS

**Provenance trace (clean):**
- `runPoll` reads BOTH `workspaceId` and `creatorProfileId` from the *same* `MetaOAuthToken` row — `MetricsPollingJob.java:123-124`.
- `pollOne` resolves the token via `tokenStorage.getValidToken(workspaceId, creatorProfileId)` — `MetricsPollingJob.java:155` — which resolves through `findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(...)` requiring BOTH ids to match (`MetaTokenStorage.java:127-128`, backed by the `uq_meta_oauth_workspace_creator` unique key per `MetricsAuthorizationService.java:29-31`). The decrypted token returned therefore belongs to exactly that (workspace, creator) pair.
- The `MediaMetric` row is attributed to that identical `creatorProfileId` — `MetricsPollingJob.java:284` — and the media that populates it is fetched using that same creator's token: `instagramClient.getMedia(creatorProfileId, accessToken, ...)` (`:231`) and `getMediaInsights(mediaItem.id(), accessToken, creatorProfileId)` (`:296`). There is no code path where the id used for the row diverges from the id/token used to fetch the data. A row cannot be attributed to a creator/workspace other than the one whose token produced it.

**Interleaving / shared mutable state (clean):**
- The job cannot run concurrently with itself: `pollMetrics` gates on `running.compareAndSet(false, true)` and only clears it in `finally` (`:104-112`).
- Within a run, `runPoll` iterates tokens in a single-threaded sequential for-loop (`:122`). No parallel streams, no async. Two creators' polls never interleave.
- The only cross-creator shared object is `MetaRateLimitTracker`. It is keyed per-account in a `ConcurrentHashMap<String, RateLimitState>` (`MetaRateLimitTracker.java:27`); all calls in this job key on `creatorProfileId` (`:164, :220, :233, :299`), and `MetaGraphApiClient.get` keys on the `businessAccountId` argument, which is `creatorProfileId` for every Instagram call (`InstagramInsightsClient.java:37, :49, :62`). One creator's usage state cannot be read/written under another creator's key. No metric data is stored in the tracker — only usage percentages — so even a hypothetical key collision could not leak *content*.
- The `MediaMetric.Builder` is a fresh local per media item (`MetricsPollingJob.java:279`); no builder or accumulator is shared across items or creators.

Conclusion: no cross-workspace leak in the batch. The acceptance gate is satisfied.

---

## Gate 2 — Data exposure (logging + brand-facing DTOs): PASS

**No new read/exposure surface.** B1's diff is the write-side job + its test only. It adds no controller, no DTO, no repository finder. The brand-facing read path for media metrics remains `AnalyticsService`, which enforces authorization *before* any metric row is read — `metricsAuthorizationService.resolveAuthorizedCreatorProfileId(workspaceId, creatorId)` at `AnalyticsService.java:73` and `:159`. That seam is the one I reviewed in the prior Phase-2 workspace-isolation pass (`MetricsAuthorizationService.java:13-39`). B1 does not weaken it.

**Logging — no per-creator content leaked into shared logs.** Every log statement the job adds emits only structural identifiers — `mediaId`, `creatorProfileId`, `mediaType`, and the Meta exception `message`: `:256-260`, `:300-303`, `:307-312`, `:379`. Captions, permalinks, and insight values are never logged.

**Caption is fetched but discarded.** `MEDIA_FIELDS` requests `caption` (`InstagramInsightsClient.java:18`) and it is bound on `InstagramMediaResponse.MediaItem.caption` (`InstagramMediaResponse.java:13`), but the job never persists it to `MediaMetric` (no `caption` column) and never logs it. It is dropped after deserialization — not a leak, just a minor over-fetch (see LOW-3).

**INFORMATIONAL (pre-existing, not B1):** `MetaGraphApiClient.translate` logs `e.getResponseBodyAsString()` at ERROR for generic non-429/401/403 failures (`MetaGraphApiClient.java:105-109`). For the B1 insights path this fires only on the unsupported-metric 400, whose Meta error body is structural (`{"error":{"message":"(#100) ..."}}`) and contains no creator content. Low value to a log reader, low leak risk. Out of B1 scope but noted for the shared-logging hygiene backlog.

---

## Gate 3 — Injection / robustness against Meta-sourced input: PASS (with LOW notes)

**LOW-1 — external id concatenated into URI path (defense-in-depth).** `getMediaInsights` builds `"/" + mediaId + "/insights?metric=..."` where `mediaId` originates from Meta's media-list response (`InstagramInsightsClient.java:61`; the same shape as the pre-existing `getProfile`/`getMedia` at `:36, :48`). It is passed to `uriBuilder.path(path).queryParam("access_token", token).build()` (`MetaGraphApiClient.java:73-78`). Meta media ids are numeric, and Meta is a trusted upstream, so real-world risk is negligible. Two theoretical concerns if a compromised/spoofed upstream ever returned a hostile id: (a) `?`/`&`/`#` could alter the query/fragment; (b) Spring's URI-template handling treats `{...}` as a variable placeholder. Not exploitable today, not introduced by B1 (identical to existing calls). Defense-in-depth recommendation for the Meta-client backlog: bind ids as encoded path segments / template variables rather than string concatenation. Non-blocking.

**LOW-2 — external field length vs V21 column sizes (safe, handled).** `MediaMetric` caps `permalink` at 500 (`MediaMetric.java:42`), `media_id` at 50 (`:30`), `media_type` at 20 (`:39`). If Meta ever returned an over-length value, the `save` at `MetricsPollingJob.java:315` would throw a data-truncation `SQLException`. That is caught by the per-item defensive catch-all in `pollRecentMedia` (`:253-261`), so the batch continues and only that one row is dropped. Each `save` is its own default transaction (methods are not `@Transactional`), so a failed insert cannot poison already-persisted rows. Robust — one row lost, no crash, no batch abort. Acceptable.

**LOW-3 — over-fetch of caption.** As noted in Gate 2, `caption` is requested and deserialized but never used. Minimizing the requested field set (drop `caption` from `MEDIA_FIELDS`, or a media-specific field list) would reduce the volume of creator content that transits the service and the size of any buffered response. Data-minimization hygiene, non-blocking.

**Safe by construction:** `firstLongValue` wraps `Long.parseLong` on Meta string values in try/catch returning null (`MetricsPollingJob.java:364-368`); `applyInsights` null-guards `insights`/`insights.data()` (`:321`) and ignores unrecognized metric names via the `default` arm (`:345-347`); `parseTimestamp` never throws into the caller (`:372-381`). No unsafe reflection, no SpEL, no dynamic query construction — all repository access is via derived JPA finders, no string-built JPQL/SQL.

**Non-security correctness observation for Vikram (not a gate finding):** `parseTimestamp` calls `Instant.parse(timestamp)` (`:377`). Meta's Instagram Graph API returns media timestamps in the form `2026-07-01T18:00:00+0000` (numeric offset, no colon), which `Instant.parse` rejects with `DateTimeParseException` (a `DateTimeException` subclass). The catch swallows it and returns `null` (`:378-380`) — so it is security-safe (never throws, never crashes the batch), but the practical effect is that `posted_at` will likely be persisted as `null` for real Meta payloads. Kavya's test used a `Z`-suffixed ISO string, which parses fine, so the suite doesn't surface it. Recommend Vikram parse with `OffsetDateTime.parse(..., DateTimeFormatter with pattern including 'Z' or +HHmm)` / `DateTimeFormatter.ISO_OFFSET_DATE_TIME` tolerant of the `+0000` form. Correctness only — does not affect this security sign-off.

---

## Gate 4 — Resource abuse / rate-limit-budget burn: PASS (with LOW note)

**Bounded call volume.** Per creator: 1 media-list call + up to `RECENT_MEDIA_LIMIT = 25` (`MetricsPollingJob.java:65`) insights calls = 26 calls max. `InstagramInsightsClient.getMedia` additionally hard-caps the requested `limit` at 100 (`InstagramInsightsClient.java:47`).

**Budget is guarded at every layer.**
- Pre-flight before the media-list call: `getCurrentUsage(creatorProfileId) >= 90` defers the whole media sweep (`:220-227`).
- Every single `getMediaInsights` passes through `MetaGraphApiClient.get`, which does its own pre-flight throttle and throws `MetaRateLimitException` at the configured threshold (`MetaGraphApiClient.java:60-64`).
- Each response's `X-Business-Use-Case-Usage` header updates the tracker (`MetaGraphApiClient.java:82-84`), so usage climbing mid-loop is observed on the very next iteration. When it trips, `pollOneMedia` catches the rate-limit exception, marks limited, persists base fields only, and continues (`:298-303`); subsequent iterations then fast-fail at the pre-flight check without spending real budget. There is no way for the 25-item loop to blow past the tracker's threshold undetected.

**Memory.** Each `MediaItem` and `InsightMetric` is small; `firstLongValue` reads only `values().get(0)` (`:357`); no unbounded accumulation across the loop.

**LOW-4 — no client-side cap on returned list size (defense-in-depth).** The for-loop iterates `mediaResponse.data()` in full (`:250`) and trusts that Meta honored `limit=25`. A misbehaving/compromised upstream returning a far larger `data` array would drive one insights call per element (bounded only by the rate-limit tracker, which would throttle quickly) and `RestClient` buffers the entire response body into memory (a pathological payload could pressure heap). Same trust assumption as every other Meta call in the codebase; not introduced by B1. Defense-in-depth recommendation: slice the iteration to `RECENT_MEDIA_LIMIT` regardless of returned size. Non-blocking.

---

## Summary by severity

| Sev | Finding | Location | Blocks merge? |
|-----|---------|----------|---------------|
| — | Cross-workspace leak: NONE. Provenance and isolation verified. | `MetricsPollingJob.java:123-124,155,231,284,296` | Gate satisfied |
| LOW-1 | External media id concatenated into URI path (pre-existing pattern; Meta trusted) | `InstagramInsightsClient.java:61`; `MetaGraphApiClient.java:73` | No |
| LOW-2 | Over-length Meta fields vs V21 column sizes — safely caught per-item, one row dropped | `MediaMetric.java:30,39,42`; `MetricsPollingJob.java:253-261,315` | No |
| LOW-3 | `caption` over-fetched then discarded (data minimization) | `InstagramInsightsClient.java:18` | No |
| LOW-4 | No client-side cap on returned media-list size before iterating | `MetricsPollingJob.java:250` | No |
| INFO | Generic Meta error body logged at ERROR (pre-existing, structural body only) | `MetaGraphApiClient.java:105-109` | No |
| CORRECTNESS (non-security) | `Instant.parse` rejects Meta's `+0000` offset → `posted_at` likely always null | `MetricsPollingJob.java:377` | No (for Vikram) |

## Back to Vikram
Nothing is a security blocker. Two optional, low-cost hardening items (bind ids as path segments; cap the media iteration to `RECENT_MEDIA_LIMIT`) and one non-security correctness fix (`parseTimestamp` should tolerate Meta's `+0000` offset so `posted_at` is populated). All can be scheduled as backlog follow-ups; none gate this merge.

## Next
Signed off — route to Meera for build verification.
