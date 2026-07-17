# Red-Team Review: DPF-6 — Platform-Verified Analytics (Token/PII Handling)

**Reviewer:** Kabir | **Date:** 2026-07-13 | **Gate:** post-Ash mandatory AI-review, pre-Kavya

## VERDICT: PASS

No exploitable token or PII leak found. Routes to Kavya -> Meera -> DPF-6 closes (per `wiki/processes/DPF-agent-assignments.md`).

Files read in full: `service/verification/DeliverableVerificationService.java`, `service/verification/PostUrlIdentifier.java`, `job/DeliverableVerificationJob.java`, `domain/entity/MetaOAuthToken.java`, `integration/meta/oauth/MetaTokenStorage.java`, `integration/meta/oauth/MetaOAuthService.java`, `integration/meta/client/MetaGraphApiClient.java`, `integration/meta/client/InstagramInsightsClient.java`, `integration/meta/service/MetaRateLimitTracker.java`, `integration/meta/dto/InstagramMediaResponse.java`, `integration/meta/dto/InstagramInsightsResponse.java`, `integration/meta/exception/*.java`, `repository/MetaOAuthTokenRepository.java`, `domain/entity/DeliverableMetric.java` (`applyVerifiedReport`), plus a repo-wide grep pass (not scoped to `verification/`) for decode calls and `postUrl` in log statements.

---

## 1. Token storage — encrypted, no plaintext exposure found

- `MetaOAuthToken.encryptedAccessToken` is `TEXT`, populated only via `MetaTokenStorage.encrypt()` (AES-256-GCM, random 12-byte IV prepended to ciphertext, 128-bit tag) — confirmed by reading the actual cipher code (`MetaTokenStorage.java:161-177`), not just the class javadoc's claim. The entity class (`MetaOAuthToken.java:14-17`) has no plaintext getter/setter — matches its own doc comment.
- `getValidToken` (`MetaTokenStorage.java:126-131`) decrypts in-memory only, returns `Optional<String>`, never persisted back, never logged.
- Grepped every log statement across `integration/meta/**` (`MetaGraphApiClient`, `InstagramInsightsClient`, `MetaTokenStorage`, `MetaOAuthService`, `MetaRateLimitTracker`, `InstagramMetricsFetcher`) — zero log lines reference the access token, decrypted or encrypted. All log statements use `businessAccountId`/`creatorProfileId`/status codes/response *bodies* (Meta's own error text on failure), never the token.
- `MetaOAuthService.fetchToken` catch block (`MetaOAuthService.java:112-122`) logs `status` + `e.getResponseBodyAsString()` on a failed code/token exchange — this is Meta's **response** body (an error payload on non-2xx), not the request URL that carries `client_secret`/`code`/`shortLivedToken` as query params. Confirmed the log statement never touches the request URI. No secret leak on this path.
- `MetaGraphApiClient.get` appends `access_token` as a query param (`MetaGraphApiClient.java:79`, required by Meta's Graph API design) but never logs the built URI — only `businessAccountId`, HTTP status, and (on the generic-error branch only) Meta's response body (`MetaGraphApiClient.java:107-112`). Checked `application.yml`/`application-dev.yml` for any `org.springframework.web.client` / Apache HttpClient wire-logging override that could expose the outbound URI at DEBUG/TRACE — none configured (only `com.influora: DEBUG` and `org.hibernate.SQL: WARN`). No framework-level leak path.
- Exception messages (`MetaTokenExpiredException`, `MetaRateLimitException`, `MetaApiException`, `MetaPermissionDeniedException`) are all fixed, hardcoded strings ("Meta access token expired or invalid", etc.) — none interpolate the token or the request URI. `DeliverableVerificationService`'s fallback-reason strings append `e.getMessage()` from these exceptions, so the fixed strings are all that ever reaches the app log via that path — confirmed by reading every exception class, not assumed.
- `MetaTokenStorage`'s own audit trail (`AuditLogService.recordToolCall` for `META_OAUTH_TOKEN_STORED`/`_REVOKED`) passes a detail map of `creatorProfileId` + `scopeCount` only — verified by reading the actual `Map.of(...)` call, matches the class javadoc's claim.

## 2. Token scope/leakage across creators — correctly scoped, no batch-loop bug

- `verifyInstagram` (`DeliverableVerificationService.java:153-171`) looks up the token via `findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(creatorProfileId)` (deliverable's own `creatorProfileId`), then calls `metaTokenStorage.getValidToken(tokenRow.get().getWorkspaceId(), creatorProfileId)` — the `workspaceId` used is read off the row just fetched for *that same creator*, not a stale/shared variable. Both lookups key off the same `creatorProfileId` parameter passed into the method. Independently re-derived the same conclusion Ash reported — this is correct by inspection, not just by construction-argument.
- `DeliverableVerificationJob.sweep()` (`DeliverableVerificationJob.java:68-91`) is a plain synchronous `for (Deliverable deliverable : candidates)` loop calling `verificationService.verify(deliverable)` once per iteration — no `CompletableFuture`/thread-pool/async dispatch, no shared mutable "current token" field on the service or job, no closure capturing a loop variable by reference. Each iteration's token lookup is fresh and local to that call frame. There is no code path by which creator A's decrypted token could still be in scope when creator B's deliverable is processed.
- `MetaRateLimitTracker` (the only cross-request shared/mutable state touched by this flow) keys exclusively on `businessAccountId` (== `creatorProfileId`/IG user id here), an in-memory `ConcurrentHashMap` — it stores usage percentages, never tokens or response payloads. No cross-creator data mixing possible through this cache.

## 3. API response caching/logging — no over-collection, no full-payload logging

- `InstagramMediaResponse`/`InstagramInsightsResponse` (full Jackson DTOs) are deserialized in memory inside `verifyInstagram` but only two things are ever extracted: the matched item's `id()` (`platformMediaId`) and three named metric values (`reach`, `impressions`, `engagement`) via `metricValue()`. The rest of the response — `caption`, `mediaUrl`, `likeCount`, `commentsCount`, `permalink`, insight `title`/`description` — is read into memory, used for shortcode matching, then discarded. Confirmed by reading `persistVerified` (`DeliverableVerificationService.java:231-268`): `DeliverableMetric.applyVerifiedReport(reach, impressions, engagements, platformMediaId)` (`DeliverableMetric.java:186-194`) is the only write, and it sets exactly those 4 fields + `source`/`verifiedAt`. No raw JSON, no full DTO, ever persisted verbatim anywhere (no `@Column(columnDefinition = "json")` blob for this data, unlike `MetaOAuthToken.grantedScopesJson` which is a deliberate, narrow JSON column for scope strings only).
- No log statement in the service or its callees logs a full `mediaResponse`/`insights` object — confirmed both log call sites (`DeliverableVerificationService.java:263-267` success, `:308-314` fallback) pass only `deliverable.getId()`, the `Outcome` enum, a fixed reason string, and (on success) `platformMediaId`.
- `DeliverableVerificationJob`'s summary log (`DeliverableVerificationJob.java:89-90`) logs only a `Map<Outcome, Integer>` tally count and candidate count — no per-deliverable data, no PII.

## 4. Token refresh/expiry handling — fails closed cleanly

- Expired/missing/revoked token all collapse to the same two outcomes (`FALLBACK_NO_TOKEN` if no row or `getValidToken` returns empty; `FALLBACK_TOKEN_EXPIRED` only if Meta itself returns 401 mid-call) — both are plain enum values with a log-only reason string containing `creatorProfileId` (an internal ULID, not a name/email/handle). Neither outcome, nor the reason string, nor the `MetaTokenExpiredException` ever reaches any HTTP response — this method is invoked exclusively from `DeliverableVerificationJob`'s `@Scheduled` sweep, not from any controller, so there is no client-facing surface for it to leak through even in principle. Confirmed by grep: `DeliverableVerificationService.Outcome` appears in exactly 3 files repo-wide (the service, its test, and the job) — never referenced by any `web/` controller or DTO.

## 5. Rate-limit response handling — no leak of Meta's internal headers

- `FALLBACK_RATE_LIMITED` is produced either by the pre-flight check (`rateLimitTracker.getCurrentUsage(creatorProfileId) >= 90`) or by catching `MetaRateLimitException` after Meta's 429 — in both cases the only data carried forward is the fixed enum + a reason string built from `creatorProfileId` and (for the caught-exception path) the exception's fixed message text ("Meta API rate limit exceeded for account " + id) or `e.getMessage()` from the pre-flight-derived exception. Meta's actual `X-Business-Use-Case-Usage` header content is parsed by `MetaRateLimitTracker.update()` into three plain ints (`callCount`/`totalCpuTime`/`totalTime`) and stored in-memory — the raw header string itself is never logged or persisted (only logged, at WARN, if it *fails* to parse — and even then only `e.getMessage()` from the JSON parse exception, not the header value itself: `MetaRateLimitTracker.java:62`). As established in §4, `Outcome` never escapes the service/job to reach any creator/brand-facing surface, so there's no exposure route for this outcome regardless.

## XSS carry-forward (Priya's gate) — independently reconfirmed

Ran my own repo-wide checks rather than trusting Ash's report:
- `grep -rn "decodeURIComponent\|URLDecoder\|\.decode(" influora-api/src/main/java` (not scoped to `verification/`): only hit is `AuthRateLimitFilter.java:358` (`URLDecoder.decode(path, ...)`), which decodes the *HTTP request path* for a rate-limit bucket key — unrelated to `postUrl`/deliverable data. The two hits inside `verification/` are doc-comments naming the constraint, not decode calls. Confirmed: zero actual decode operations touch `postUrl` anywhere in the backend, not just in this one service.
- `grep -rn -A3 "log\.(info|warn|error|debug|trace)" influora-api/src/main/java | grep -i postUrl`: zero matches anywhere in the codebase. `postUrl` is never interpolated into any log statement, repo-wide.
- `PostUrlIdentifier.extract` rejects `%3C|%3E|%22|%27|%60` before any platform match (`PostUrlIdentifier.java:36-37, 55-57`), confirmed by reading the actual regex and the order of operations in `extract()`.

**Verdict on this gate: independently reconfirmed PASS.**

---

## Notes carried forward (not mine to fix, tracking only)

- Ash's two P1s (aggregate `source` hardcoded to `CREATOR_REPORTED`; silent indefinite `FALLBACK_NOT_FOUND` retry with no brand/creator visibility) are UX/data-integrity issues, not token/PII/security issues — outside my remit, already correctly routed to DPF-7/a follow-up per Ash's note. Nothing in either P1 changes this PASS.
- One observation for defense-in-depth (non-blocking, P3/informational): `MetaOAuthService.fetchToken`'s error log (`MetaOAuthService.java:116-120`) logs Meta's raw response body on OAuth exchange failure. I confirmed today this cannot contain the app's own secrets (it's Meta's response, not the outbound request), but if Meta's error-response shape ever changed to echo back request parameters (some OAuth providers do this on malformed-request errors), that log line would need a body-scrub. Not exploitable today, not part of the `verification/` package this gate covers, flagging only because I read the file while tracing token flow.

## Summary for routing

**PASS — no token/PII leak found.** Token storage is properly encrypted at rest with no plaintext code path; no log statement anywhere in the token/verification flow logs a raw token, full API response, or raw `postUrl`; token lookups are correctly scoped per-creator with no batch-loop cross-contamination risk; over-collection is not present (only reach/impressions/engagement + platformMediaId persisted); fail-closed holds on every expiry/revocation/rate-limit branch with zero external surface for any of it to leak through.

Route to **Kavya** for QA.
