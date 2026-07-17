# Red-Team Security Review: Wave B Task B2 (Token Refresh + Stale Cleanup) + B1 Timestamp Fix

**Date:** 2026-07-07
**Reviewer:** Kabir (Offensive Security / Red-Team Lead)
**Scope:** Sage Digital / Influora own code (authorized)
**Predecessor:** Kavya QA APPROVED (`wiki/errors/token-refresh-B2-review.md`) — this pass is adversarial, not confirmatory.
**Verdict:** ✅ **SIGN-OFF** — no CRITICAL / HIGH / MEDIUM. 4 LOW + 1 advisory, none blocking.

---

## Files examined (with the seams that matter)

- `job/MetaTokenRefreshService.java` (full, 160 lines)
- `job/StaleTokenCleanupJob.java` (full, 125 lines)
- `job/MetricsPollingJob.java` (parseTimestamp, lines 365-402)
- `integration/meta/oauth/MetaOAuthService.java` (full — the outbound refresh request builder)
- `integration/meta/oauth/MetaTokenStorage.java` (full — encrypt/store seam)
- `integration/meta/client/MetaGraphApiClient.java` (error translation/logging)
- `integration/meta/exception/MetaApiException.java`
- `domain/entity/MetaOAuthToken.java` (JPA entity — plaintext-window check)
- `common/JsonLists.java` (scope (de)serialization)
- `repository/MetaOAuthTokenRepository.java:18` (the sweep query)
- `pom.xml:10` (Spring Boot 3.3.5 → Spring Framework 6.1.x — load-bearing for finding LOW-1)

---

## Attack 1 — Token lifecycle: does the OLD token leak out of the refresh call?

**Trace.** `MetaTokenRefreshService.refreshOne` (line 129) → `MetaOAuthService.refreshLongLivedToken` (line 108) → `exchangeForLongLivedToken` (lines 88-101). Confirmed: the request is a **GET with the current long-lived token AND the app `client_secret` in the URL query string** — `MetaOAuthService.java:88-100`:

```
.../oauth/access_token?grant_type=fb_exchange_token&client_id=...&client_secret=<SECRET>&fb_exchange_token=<OLD_TOKEN>
```

So two secrets ride in the URL. I then chased every place that URL or its cause could surface:

1. **`fetchToken` error log** (`MetaOAuthService.java:116-120`) logs `e.getResponseBodyAsString()` — the **response** body, not the request URL. Meta's OAuth error bodies (`{"error":{"message":"...","type":"OAuthException","code":190}}`) do **not** echo the submitted token. → no request-URL/token leak here. (See LOW-2 for the residual concern.)
2. **`MetaApiException` cause chain.** On HTTP 4xx/5xx, `fetchToken` throws `MetaApiException("Meta OAuth long-lived-exchange failed", e)`. Its `getMessage()` is the static string — no token. `refreshOne`'s inner `catch (MetaApiException e)` logs only `e.getMessage()` (`MetaTokenRefreshService.java:147-150`). The wrapped `RestClientResponseException`'s message is `statusText + body`, **not** the URL. → clean.
3. **IO-error path (the real hole to probe).** `fetchToken` only catches `RestClientResponseException`. A transport failure (timeout/reset) throws `ResourceAccessException`, which is **not** caught in `fetchToken`, **not** caught by `refreshOne`'s `catch(MetaApiException)`, and therefore propagates to the sweep's defensive `catch (Exception e)` at `MetaTokenRefreshService.java:92-98`, which logs the **full exception** (`log.error("...creator {}", creatorProfileId, e)` — message + stack trace). **If** `ResourceAccessException`'s message contained the request URL, both the token and the client_secret would land in ERROR logs, daily, for every failing token.

   **Result: not exploitable on the shipped stack.** Spring Framework 6.1.x (via Spring Boot 3.3.5, `pom.xml:10`) builds the `ResourceAccessException` message with the query string **stripped** (`DefaultRestClient` truncates the URL at the first `?`). The underlying `IOException` cause carries only host/port, never the query. → no active leak.

**Rating: LOW-1 (defense-in-depth, framework-version-dependent).** The safety here is *inherited from the framework*, not from the code. It silently breaks if any of these change: Spring is downgraded/replaced, the HTTP client is swapped, `org.springframework.web.client` TRACE logging is enabled (RestClient logs full URIs at TRACE), or an egress/forward proxy logs full request URLs (OWASP A09/"sensitive data in URL"). Because B2 now drives this GET **daily for every stored token**, the exposure surface is materially larger than the one-shot connect flow it inherits from.
**Fix (non-blocking):** send the token/`client_secret` exchange as a POST with a form body so secrets never touch the URL. Meta's `oauth/access_token` accepts POST. Cite: `MetaOAuthService.java:71-101,112-123`.

---

## Attack 2 — Storage: is encryption actually on the refresh write? Any plaintext window?

**PASS — confirmed secure.**
- Refresh re-persists via `MetaTokenStorage.storeToken(...)` (`MetaTokenRefreshService.java:140-141`) — the **same** seam as first-connect, not a raw `repository.save` of a hand-built entity.
- `storeToken` calls `encrypt(accessToken)` **first** (`MetaTokenStorage.java:84`) and only the resulting ciphertext is ever handed to the entity — `rotateToken(encrypted, …)` (line 91) or the builder's `encryptedAccessToken(encrypted)` (line 99).
- **No plaintext-flush window.** The entity `MetaOAuthToken` has exactly one token field, `encryptedAccessToken` (`MetaOAuthToken.java:32-33`), and no setter that accepts plaintext — `rotateToken` (line 97) takes the already-encrypted string. There is no code path where the managed entity holds plaintext, so JPA dirty-checking can never flush a plaintext token. The entity javadoc's "no plain getter/setter" claim is accurate.
- Crypto itself: AES-256-GCM, random 12-byte IV per encrypt prepended to ciphertext, 128-bit tag, key length enforced to 32 bytes at startup (`MetaTokenStorage.java:60-70,161-177`). Standard and correct for this scope.
- Plaintext lifetime: decrypted token exists only as a local (`currentToken.get()`, `refreshed.accessToken()`), passed straight through; never logged.

---

## Attack 3 — Revocation correctness

**PASS — no false revocation, no infinite refresh.**
- **Can cleanup soft-revoke a still-usable token?** No. The two windows are disjoint: `getValidToken` requires `expiresAt.isAfter(now)` (`MetaTokenStorage.java:129`); `StaleTokenCleanupJob` selects only `expiresAt < now − 14d` (`StaleTokenCleanupJob.java:75,80-81`). A token cleanup can touch is already ≥14 days past *local* expiry — unusable for API calls regardless (getValidToken already filters it out). Revoking it destroys nothing usable; the row is soft-revoked (audit trail kept, `revoke()` sets the flag only, `StaleTokenCleanupJob.java:116-123`).
- **Clock skew / expiresAt drift?** Immaterial. Skew is seconds/minutes against a **14-day** margin. If Meta's real expiry is *longer* than our stored `expiresAt` (e.g. the 60-day fallback in `computeExpiresAt`, `MetaTokenRefreshService.java:156-159`, when Meta omits `expires_in`), the refresh sweep still catches it during the local pre-expiry window and Meta issues a fresh token — self-healing.
- **Can a revoked-at-Meta token be "refreshed" forever?** No — bounded. While our `expiresAt` is still future, `getValidToken` keeps returning it and each daily refresh calls Meta, which rejects it → `MetaApiException` → counted `failed`, nothing stored. Once our `expiresAt` passes, `getValidToken` returns empty → `refreshOne` returns false. 14 days later cleanup revokes it. Total bounded lifetime, not an infinite loop. Matches Kavya's timing analysis.

---

## Attack 4 — Scheduling / DoS

- **Per-item isolation genuinely covers the mapping code (verified, not assumed).** In the refresh sweep, scope deserialization `JsonLists.stringListFromJson(grantedScopesJson)` runs at `MetaTokenRefreshService.java:138`, which is inside the outer `try` (line 86) whose `catch (Exception e)` (line 92) is a true catch-all — the inner `catch` only catches `MetaApiException` (line 144), so a mapping/parse failure escapes the inner catch and is still contained by the outer one. Additionally `stringListFromJson` **cannot** throw: it swallows `JsonProcessingException` and returns `emptyList` (`JsonLists.java:43-45`). Cleanup reads `creatorProfileId` *inside* its try (`StaleTokenCleanupJob.java:87-88`) and only calls a boolean setter + save. → one poisoned/null-field row cannot abort either batch. Kavya's isolation claim holds under adversarial reading.
- **Poisoned huge-JSON DoS?** Bounded. Jackson 2.17 (Spring Boot 3.3.5) enforces default `StreamReadConstraints` (20MB max string, 1000 nesting) → oversized scopes JSON throws, is swallowed to `emptyList`, isolated per item. Not a viable DoS.
- **LOW-4 — unbounded sweep query, no pagination.** `findByExpiresAtBeforeAndRevokedFalse` returns a full `List<MetaOAuthToken>` into heap (`MetaOAuthTokenRepository.java:18`; consumed at `StaleTokenCleanupJob.java:80-81` and `MetaTokenStorage.findTokensExpiringSoon` `:135-137`). Not externally exploitable (one token per connected creator/workspace; an attacker can't inflate the count), but a large install — or a **cleanup backlog** if the job is disabled/failing for a long stretch — loads the entire result set at once. Recommend a batched/`Limit`/`Pageable` sweep. Low.

---

## Attack 5 — The timestamp fix

**PASS — no parsing gadget / no ReDoS.** `parseTimestamp` (`MetricsPollingJob.java:387-401`) uses `Instant.parse` then a fixed `DateTimeFormatter.ofPattern("...Z")` — pattern-based, **not** regex, so no catastrophic-backtracking surface. Both parse attempts throw only `DateTimeException` subtypes (`DateTimeParseException`), both caught (lines 393, 396); unparseable input → `log.warn` + `null`. Never-throws contract holds. Input is a bounded Meta timestamp field. Clean.

---

## Additional findings surfaced during the pass

- **LOW-2 — upstream response body logged at ERROR.** `fetchToken` logs `e.getResponseBodyAsString()` on every OAuth failure (`MetaOAuthService.java:116-120`); `MetaGraphApiClient.translate` does the same for non-401/403/429 (`MetaGraphApiClient.java:105-109`). Meta does not echo tokens in these bodies, but blanket logging of full upstream bodies is a mild info-disclosure habit, and B2 now exercises the OAuth path daily. Pre-existing; advisory. Consider logging a bounded/redacted snippet.
- **LOW-3 — silent scope loss on corrupted `grantedScopesJson` during refresh.** `stringListFromJson` swallows a parse error to `emptyList` (`JsonLists.java:43-45`); refresh then persists empty scopes (`MetaTokenRefreshService.java:138` → `MetaTokenStorage.java:85`, where `toJson([])` returns `null`). **Not an authorization bypass** — I grepped every consumer of `grantedScopes`/`granted_scopes`: it is only stored and surfaced in `MetaCallbackResponse` for display (`web/dto/meta/MetaDtos.java:14`); nothing gates authz on it (Meta enforces scope server-side). Severity is record-accuracy only. Low.
- **ADVISORY (route to Vikram, not security) — silent creator-pipeline death.** When cleanup soft-revokes, `MetricsPollingJob` stops reading that token and metrics silently stop, but there is only a `log.warn` + aggregate audit count (`StaleTokenCleanupJob.java:102-123`) — no per-creator notification/alert that their Meta connection is dead. Product/observability gap, not a vulnerability.

---

## Summary by severity

| Sev | Count | Items |
|-----|-------|-------|
| CRITICAL | 0 | — |
| HIGH | 0 | — |
| MEDIUM | 0 | — |
| LOW | 4 | LOW-1 secrets-in-URL (framework-mitigated, B2-amplified); LOW-2 upstream-body logging; LOW-3 silent scope loss; LOW-4 unbounded sweep query |
| ADVISORY | 1 | silent creator-pipeline death (observability) |

**PASSED adversarial checks:** encryption-on-refresh + no JPA plaintext window (Attack 2); no token value in either new job's logs; disjoint revocation windows / bounded refresh retries (Attack 3); per-item isolation genuinely covers mapping code (Attack 4); timestamp fix has no ReDoS/gadget (Attack 5).

## Routed back to Vikram (non-blocking hardening — do NOT gate B2 on these)
1. **LOW-1:** switch the Meta `oauth/access_token` exchange/refresh from GET-with-query-secrets to POST form body (`MetaOAuthService.java:71-101`) — removes reliance on Spring 6.1's query-stripping and on the absence of URL-logging proxies/TRACE logging.
2. **LOW-4:** batch/paginate `findByExpiresAtBeforeAndRevokedFalse` in both sweeps.
3. **LOW-2 / LOW-3 / advisory:** bound upstream-body logging; decide whether corrupted-scope rows should hard-fail the refresh rather than silently blank scopes; consider a creator-facing "connection lost" signal on soft-revoke.

**Kabir**
Red-Team Lead, Sage Digital — 2026-07-07
