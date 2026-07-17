# Creator-Self Analytics — Task #35 V6 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09 (~20:45 IST)  
**Verdict:** ✅ **PASS WITH FINDINGS** — **0 Critical, 0 High**; pipeline **GO** for Kavya Kv2 + Ananya A5  
**Scope:** Task #35 V6 — `GET /api/v1/creator/analytics/me/metrics|scores|demographics` (principal-scoped creator-self analytics)  
**Reference:** Meera M2 PASS (`CreatorAnalyticsServiceTest` 3/3 + `CreatorAnalyticsControllerTest` 3/3); brand mirror `AnalyticsController` / `AnalyticsService`; Kabir Task #11 `creator-context-service-T11-kabir-redteam.md`; `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6.3  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/web/CreatorAnalyticsController.java`
- `influora-api/src/main/java/com/influora/service/CreatorAnalyticsService.java`
- `influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java` — `getCreator*ForProfile`, `loadCreator*`
- `influora-api/src/main/java/com/influora/web/AnalyticsController.java` — attack-surface comparison
- `influora-api/src/main/java/com/influora/service/CreatorContextService.java`
- `influora-api/src/main/java/com/influora/service/MetricsAuthorizationService.java`
- `influora-api/src/main/java/com/influora/security/JwtAuthenticationFilter.java`, `JwtService.java`, `AuthPrincipal.java`
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java`
- `influora-api/src/main/java/com/influora/repository/CreatorMetricsRepository.java`
- `influora-api/src/test/java/com/influora/service/CreatorAnalyticsServiceTest.java` (3)
- `influora-api/src/test/java/com/influora/web/CreatorAnalyticsControllerTest.java` (3)
- `influora-api/src/test/java/com/influora/service/analytics/AnalyticsServiceTest.java` — brand gate cross-check
- `influora-api/src/test/java/com/influora/service/CreatorContextServiceTest.java`

**Test execution:** `mvn` unavailable in Kabir shell — logic verified by direct code review + Meera M2 scoped run (6/6 PASS per SHARED_CONTEXT).

---

## Executive Summary

Task #35's creator-self analytics surface is **structurally safer than the brand-facing `AnalyticsController`** because it eliminates the client-supplied `creatorId` path parameter entirely. Identity flows exclusively: verified JWT → `AuthPrincipal` → `CreatorContextService.requireCreatorProfile(principal)` → `creatorProfileRepository.findByUserId(principal.getUserId())` → `AnalyticsService.getCreator*ForProfile(resolvedId)`. Creator A **cannot** read Creator B's metrics, scores, or demographics without possessing B's valid creator JWT.

**Closed / PASS:**

| Probe | Result |
|---|---|
| **IDOR (cross-creator read)** | **CLOSED** — no `creatorId` path/query/body param; profile id derived only from JWT `sub` via `findByUserId`. DB enforces `UNIQUE KEY uq_creator_user (user_id)` on `creator_profiles` (V6). |
| **Principal spoofing / JWT `sub` bypass** | **CLOSED** — `JwtAuthenticationFilter` builds `AuthPrincipal` only after `JwtService.parseAccessToken` HMAC verification; immutable value object; `@AuthenticationPrincipal` not client-writable. |
| **Date-range param injection** | **CLOSED** — `Instant.parse` (strict ISO-8601) in controller; typed `Instant` params to Spring Data JPA finders; no string concatenation/SQL. Invalid input → `400 INVALID_DATE_RANGE` with generic message. |
| **Information disclosure via errors** | **CLOSED** — responses describe caller's own auth/state only (`WRONG_USER_TYPE`, `CREATOR_PROFILE_NOT_FOUND`, `SCORE_NOT_FOUND`, `INVALID_DATE_RANGE`). No foreign-creator existence oracle (unlike brand `FORBIDDEN` on unauthorized workspace/creator pairs). Demographics uses graceful `hasData: false` empty shape, not 404. |
| **Brand/creator boundary** | **CLOSED** — brand JWT on creator routes → `403 WRONG_USER_TYPE` before any repository read. Unauthenticated → `SecurityConfig.anyRequest().authenticated()`. |
| **vs brand `AnalyticsController`** | **Strictly smaller attack surface** — brand must gate every `/{creatorId}` read through `MetricsAuthorizationService.resolveAuthorizedCreatorProfileId`; creator path has no enumerable foreign id. Shared `loadCreator*` pipeline is correct reuse. |

**Low carry-forward only** — test gaps, unbounded trend date window (inherited from brand), public `getCreator*ForProfile` seam discipline, no read rate limit. **None block sprint gate or Priya #35 sign-off.**

---

## 1. IDOR — Can Creator A Read Creator B's Data?

### 1a. Request path (no client-supplied creator id)

```35:57:influora-api/src/main/java/com/influora/web/CreatorAnalyticsController.java
    @GetMapping("/metrics")
    public ResponseEntity<ApiResponse<CreatorMetricsResponse>> getMyMetrics(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        ...
    }

    @GetMapping("/scores")
    public ResponseEntity<ApiResponse<CreatorScoresResponse>> getMyScores(
            @AuthenticationPrincipal AuthPrincipal principal) { ... }

    @GetMapping("/demographics")
    public ResponseEntity<ApiResponse<CreatorDemographicsResponse>> getMyDemographics(
            @AuthenticationPrincipal AuthPrincipal principal) { ... }
```

`@RequestMapping("/creator/analytics/me")` — the `/me` segment is literal, not a variable. There is no overload, query param, or header that accepts another creator's profile id.

### 1b. Service gate chain

```30:46:influora-api/src/main/java/com/influora/service/CreatorAnalyticsService.java
    public CreatorMetricsResponse getMyMetrics(AuthPrincipal principal, Instant startDate, Instant endDate) {
        String creatorProfileId = creatorContext.requireCreatorProfile(principal).getId();
        return analyticsService.getCreatorMetricsForProfile(creatorProfileId, startDate, endDate);
    }
    // getMyScores / getMyDemographics — identical pattern
```

`requireCreatorProfile` (Task #11 PASS) resolves profile by `principal.getUserId()` only:

```28:38:influora-api/src/main/java/com/influora/service/CreatorContextService.java
    public CreatorProfile requireCreatorProfile(AuthPrincipal principal) {
        requireCreator(principal);
        return creatorProfileRepository
                .findByUserId(principal.getUserId())
                .orElseThrow(() -> new ApiException("CREATOR_PROFILE_NOT_FOUND", ...));
    }
```

**Exploit attempts considered and rejected:**

1. **Path manipulation** (`/creator/analytics/me/metrics` with forged `creatorId`) — no such parameter exists.
2. **Query/body injection of profile id** — controller accepts only `startDate`/`endDate` on metrics; scores/demographics have no extra params.
3. **JWT `sub` swap without valid signature** — fails at `JwtService.parseAccessToken` (see §2).
4. **Multiple profiles per user** — blocked by `uq_creator_user (user_id)` DB unique key; `findByUserId` returns at most one row.

Unit tests (`CreatorAnalyticsServiceTest` ×3) verify `getCreator*ForProfile` is called with profile A and **never** profile B. **IDOR: CLOSED.**

---

## 2. Principal Spoofing / JWT `sub` Bypass

```32:41:influora-api/src/main/java/com/influora/security/JwtAuthenticationFilter.java
            try {
                var claims = jwtService.parseAccessToken(token);
                String userId = claims.getSubject();
                ...
                AuthPrincipal principal = new AuthPrincipal(userId, email, userType, workspaceId);
                var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
```

```45:50:influora-api/src/main/java/com/influora/security/JwtService.java
    public Claims parseAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(accessKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
```

- `AuthPrincipal` has no setters — cannot be mutated post-authentication.
- Forged/expired/tampered tokens → `JwtException` caught, context cleared; request hits `authenticated()` matcher → 401.
- Client cannot supply a fake `@AuthenticationPrincipal` in production — Spring injects from `SecurityContextHolder` only.

**Principal spoofing: CLOSED.**

---

## 3. Date-Range Param Injection (`/metrics`)

### 3a. Parsing (controller)

```59:71:influora-api/src/main/java/com/influora/web/CreatorAnalyticsController.java
    private static Instant parseInstant(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw new ApiException(
                    "INVALID_DATE_RANGE",
                    "Invalid " + paramName + " — must be an ISO-8601 instant",
                    HttpStatus.BAD_REQUEST);
        }
    }
```

Identical logic to brand `AnalyticsController.parseInstant` — no divergence.

### 3b. Query usage (service)

```154:170:influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java
        List<MetricDataPoint> trendData = List.of();
        if (startDate != null && endDate != null) {
            List<CreatorMetric> range =
                    creatorMetricsRepository.findByCreatorProfileIdAndTimeBetweenOrderByTimeAsc(
                            authorizedCreatorId, startDate, endDate);
            ...
        }
```

- **SQL injection:** not possible — Spring Data binds `Instant` as typed parameters.
- **Partial range** (only `startDate` or only `endDate`): trend query skipped; returns latest snapshot only — safe.
- **Inverted range** (`startDate > endDate`): JPA `Between` returns empty list — no error, no data leak.
- **Malformed ISO strings:** `400 INVALID_DATE_RANGE` — no stack trace in `GlobalExceptionHandler` response body.

**Finding L-T35-2 (LOW):** No max span on date window — attacker could request a multi-year range and force a large read on their **own** metrics only. Same inherited gap as brand analytics; pre-prod hardening candidate, not IDOR.

**Date-range injection: CLOSED** (no injection vector; L-T35-2 is availability/integrity on self-data only).

---

## 4. Information Disclosure via Error Messages

| Code | HTTP | Message | Oracle risk |
|---|---|---|---|
| `WRONG_USER_TYPE` | 403 | "This endpoint is for creator accounts only" | None — describes caller role |
| `CREATOR_PROFILE_NOT_FOUND` | 404 | "Creator profile not found" | None — caller's own profile missing |
| `SCORE_NOT_FOUND` | 404 | "No computed score yet for this creator" | Self-only — no foreign id in message |
| `INVALID_DATE_RANGE` | 400 | "Invalid {param} — must be an ISO-8601 instant" | Param name only; no internal detail |
| Demographics empty | 200 | `hasData: false`, null breakdowns | Correct — no 404 oracle |

`GlobalExceptionHandler` returns `ApiResponse.fail(ApiErrorBody.of(code, message))` — no exception class names or stack traces to clients.

**Contrast — brand `AnalyticsController`:** unauthorized workspace/creator probe returns `403 FORBIDDEN` — "This workspace is not authorized to view metrics **for that creator**", which can confirm a `creatorId` exists in the platform when paired with discovery. Creator-self endpoints have **no equivalent oracle** because there is no foreign `creatorId` input.

**Information disclosure: CLOSED.**

---

## 5. Comparison to Brand `AnalyticsController` Attack Surface

| Dimension | Brand `GET /analytics/creators/{creatorId}/*` | Creator `GET /creator/analytics/me/*` |
|---|---|---|
| Client-supplied creator id | **Yes** — `{creatorId}` path param | **No** |
| Authorization gate | `BrandContextService` + `MetricsAuthorizationService` (Meta OAuth token link) | `CreatorContextService` (JWT user → own profile) |
| IDOR vector | Mitigated by workspace/creator pairing check | **Eliminated** — no foreign id surface |
| Date parsing | `parseInstant` (shared pattern) | Same |
| Demographics empty state | `hasData: false` graceful | Same via shared `loadCreatorDemographics` |
| Enumeration oracle | Possible on `FORBIDDEN` for known creator ids | None |

`AnalyticsService` refactor correctly splits:
- **Brand path:** `getCreatorMetrics(principal, creatorId, …)` → `resolveAuthorizedCreatorProfileId` → `loadCreator*`
- **Creator path:** `getCreatorMetricsForProfile(creatorProfileId, …)` → `loadCreator*` (caller must have already resolved id from `CreatorContextService`)

Codewide grep: **only** `CreatorAnalyticsService` calls `getCreator*ForProfile` — no stray callers bypassing context resolution today.

---

## 6. Findings Ledger

| ID | Severity | Finding | Blocks deploy? |
|---|---|---|---|
| — | — | **No Critical or High findings** | — |
| L-T35-1 | Low | No negative-auth integration test on `CreatorAnalyticsController` (brand principal → 403, unauthenticated → 401). `CreatorContextServiceTest` covers gate logic; gap is test coverage only. | No |
| L-T35-2 | Low | Unbounded `startDate`/`endDate` window on trend query — self-DoS / heavy read on own data; inherited from brand analytics. Recommend max span (e.g. 365d) in shared `parseInstant` or service layer pre-prod. | No |
| L-T35-3 | Low | `AnalyticsService.getCreator*ForProfile(String)` is public without embedded auth — safe today (single caller) but future services could misuse. Consider package-private or javadoc `@apiNote` + arch-unit test. | No |
| L-T35-4 | Low | No per-creator read rate limit on `GET /creator/analytics/me/*` (platform-wide read-endpoint gap; not unique to T35). | No |
| L-T35-5 | Low | No controller unit test for `INVALID_DATE_RANGE` on malformed `startDate`/`endDate`. | No |

---

## 7. Verdict & Pipeline Gate

**VERDICT: ✅ PASS WITH FINDINGS**

- **Critical: 0**
- **High: 0**
- **Medium: 0**
- **Low: 5** (all non-blocking, pre-prod or test-hardening)

**Pipeline:** **GO** — unblock Kavya Kv2 batch, Ananya A5 frontend wire, Priya Task #35 security sign-off. No code changes required for sprint gate.

**Recommended follow-ups (next sprint / Kv2 test plan):**
1. Add `CreatorAnalyticsController` negative tests: brand JWT → 403, bad ISO date → 400.
2. Shared max date-range span for brand + creator metrics trend queries.
3. Arch-unit or grep CI check: `getCreator*ForProfile` callable only from `CreatorAnalyticsService`.

---

*Kabir Singh — Offensive Security / Red-Team Lead, Sage Digital*
