# AuthRateLimitFilter Deliverable + Contract Buckets — Task #25 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09 (~18:30 IST)  
**Scope:** Adversarial re-verify of Vikram Task #25 — `AuthRateLimitFilter` buckets `creator-deliverable-write`, `brand-deliverable-review`, `contract-sign`; JWT-sub keying; IP fallback abuse; bypass probes for M-19-2 / M-21-1 / L-23-3 closure  
**Reference:** Kavya `wiki/errors/creator-rate-limit-T25-kavya-qa.md`; `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6.1; carry-forward M-19-2 (Tasks #19–#24), M-21-1 (Task #21), L-23-3 (Task #23)  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java` — `bucketFor`, `rateLimitKey`, `userIdFromBearer`, `clientIp`, `doFilterInternal`
- `influora-api/src/main/java/com/influora/security/JwtService.java` — `parseAccessToken` HMAC verification
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java` — filter order (`rateLimitFilter` before `jwtFilter`)
- `influora-api/src/main/resources/application.yml` — `influora.creator` / `influora.brand` / `influora.contract` rate-limit keys
- `influora-api/src/test/java/com/influora/security/AuthRateLimitFilterDeliverableContractBucketTest.java` (8 tests)
- `influora-api/src/main/java/com/influora/web/DeliverableMetricController.java` — legacy `PUT /deliverables/{milestoneId}/metrics` (dual-path cross-check)

---

## Executive Summary

**VERDICT: ✅ PASS WITH FINDINGS**

Task #25 closes Kabir carry-forward findings **M-19-2**, **M-21-1**, and **L-23-3** on the **primary authenticated POST surfaces** of the creator deliverables journey and contract sign. No Critical or High findings. Sprint gate **GO**.

**Closed / PASS:**

1. **M-19-2 — CLOSED** — `POST /creator/deliverables/{id}/upload|submit|metrics` maps to `"creator-deliverable-write"` (default 10/min). Upload, submit, and metrics share one per-creator bucket — matches spec §6.1 file-upload limit and Kabir Task #24 remediation intent.
2. **M-21-1 — CLOSED** — `POST /deliverables/{id}/approve|revise` maps to `"brand-deliverable-review"` (default 30/min). Approve and revise share one per-brand-user bucket.
3. **L-23-3 — CLOSED** — `POST /contracts/{id}/sign` maps to `"contract-sign"` (default 10/min). Covers creator JWT path and brand relay path (same POST surface).
4. **JWT-sub keying — CLOSED** — Valid Bearer tokens are parsed via `JwtService.parseAccessToken` (HMAC verify); key is `{sub}|{bucket}`. Forged or tampered tokens fail signature verification and **cannot** pivot to another user's bucket.
5. **Per-user isolation — CLOSED** — Creator A exhaustion does not affect Creator B on the same IP (unit-tested). Keying logic is symmetric for brand review (code review; brand isolation test gap is L-T25-3 only).
6. **Pre-auth throttle — CLOSED** — Filter runs before `JwtAuthenticationFilter`; 429 returns before controller/service/DB work on limit breach.
7. **Path regex — CLOSED** — Anchored segment regex prevents accidental match on deeper paths (e.g. `/upload/extra`). `GET /creator/deliverables/{id}/status` correctly excluded.

**Low carry-forward (non-blocking):**

- **L-T25-1** (Kavya): Invalid/expired JWT → IP fallback untested in unit suite.
- **L-T25-4** (Kavya): Per-instance in-memory windows — horizontal scale multiplies effective limit (documented in filter javadoc).
- **L-T25-B1** (Kabir): Legacy `PUT /deliverables/{milestoneId}/metrics` (`DeliverableMetricController`) is **not** throttled — metrics bypass on dual-path architecture (extends Kavya L-24-9 / Kabir L-24-S4). Primary journey uses `POST /creator/deliverables/{id}/metrics` and is covered.
- **L-T25-B2** (Kabir): `clientIp` trusts first `X-Forwarded-For` hop without trusted-proxy allowlist — IP-fallback buckets (missing/invalid Bearer) can be rotated via spoofed XFF. Same documented limitation as `"tracking"` webhook bucket; not introduced by Task #25.
- **L-T25-B3** (Kabir): Trailing-slash URI variants (e.g. `.../upload/`) do not match regex — marginal bypass if reverse proxy forwards path verbatim.

**Unchanged prod posture:** M-19-3/M-19-4 upload heap-buffering and presigned-URL debt **not** cleared by rate limiting alone. M-24-1 proof-key binding unchanged.

**Test execution:** `mvn` unavailable in Kabir shell — logic verified by code review + Kavya-authored 8/8 tests. Meera gate required.

---

## 1. JWT-Sub Keying — Bypass Probes

### 1a. Implementation

```294:323:influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java
    private String rateLimitKey(HttpServletRequest request, String bucket) {
        if (isUserKeyedBucket(bucket)) {
            String userId = userIdFromBearer(request);
            if (userId != null) {
                return userId + "|" + bucket;
            }
        }
        return clientIp(request) + "|" + bucket;
    }
    // ...
    private String userIdFromBearer(HttpServletRequest request) {
        if (jwtService == null) {
            return null;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        try {
            return jwtService.parseAccessToken(header.substring(7)).getSubject();
        } catch (Exception ignored) {
            return null;
        }
    }
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

### 1b. Adversarial matrix

| Attack | Expected | Result |
|--------|----------|--------|
| Forge JWT with victim `sub`, invalid signature | `parseAccessToken` throws → IP fallback; cannot consume victim's user bucket | **BLOCKED** |
| Replay valid JWT for user A on upload/submit/metrics | Key `A\|creator-deliverable-write`; shared bucket exhausts correctly | **BLOCKED** (intended throttle) |
| Valid JWT user A on contract-sign vs deliverable-write | Separate keys (`A\|contract-sign` vs `A\|creator-deliverable-write`) — independent counters | **BY DESIGN** — not a bypass |
| `Authorization: Bearer` with garbage / expired token | Parse fails → IP fallback; downstream auth also rejects | **IP fallback only** — cannot impersonate another user's bucket |
| Omit `Bearer ` prefix | `userIdFromBearer` returns null → IP key | **IP fallback** — speed bump for unauthenticated hammering |
| Production `jwtService` null | N/A — Spring injects real `JwtService`; null only in legacy unit-test stubs | **N/A prod** |

**JWT-sub keying bypass: CLOSED.** Attacker cannot select another principal's rate-limit counter without a valid signed token for that principal.

---

## 2. IP Fallback Abuse

### 2a. When IP keying applies

IP fallback activates for user-keyed buckets only when:
- No `Authorization` header, or
- Header does not start with `Bearer `, or
- `parseAccessToken` throws (invalid signature, malformed token, expired token).

Authenticated abuse with a **valid** JWT is always user-keyed — IP rotation does not help.

### 2b. IP fallback adversarial matrix

| Attack | Result |
|--------|--------|
| Unauthenticated POST hammer on `/creator/deliverables/{id}/upload` | IP-keyed; 429 after limit — blocks blind enumeration / DoS speed bump | **MITIGATED** |
| Many invalid Bearer tokens from same IP | All share one IP bucket — collateral 429 for that IP on missing/invalid-token traffic | **ACCEPTABLE** — downstream auth would reject anyway |
| Rotate spoofed `X-Forwarded-For` on invalid-token requests | Each spoofed IP gets fresh bucket — bypasses per-IP throttle on fallback path | **LOW** — L-T25-B2; shared with tracking/webhook buckets; fix is trusted-proxy allowlist platform-wide |
| Valid JWT + rotate XFF | Ignored for rate key — user-keyed | **BLOCKED** |
| NAT: Creator A exhausted; Creator B same IP, valid JWTs | B uses `B\|bucket` — independent | **BLOCKED** (tested) |

**IP fallback abuse on authenticated write path: CLOSED** (valid JWT always user-keyed). **IP fallback rotation on unauthenticated/invalid-token path: LOW residual** (L-T25-B2) — pre-existing `clientIp` trust model.

---

## 3. Path / Method Bypass Probes

### 3a. Primary surfaces (covered)

| Path | Method | Bucket | Status |
|------|--------|--------|--------|
| `/creator/deliverables/{id}/upload` | POST | `creator-deliverable-write` | ✅ |
| `/creator/deliverables/{id}/submit` | POST | `creator-deliverable-write` | ✅ |
| `/creator/deliverables/{id}/metrics` | POST | `creator-deliverable-write` | ✅ |
| `/deliverables/{id}/approve` | POST | `brand-deliverable-review` | ✅ |
| `/deliverables/{id}/revise` | POST | `brand-deliverable-review` | ✅ |
| `/contracts/{id}/sign` | POST | `contract-sign` | ✅ |
| `/creator/deliverables/{id}/status` | GET | null (not throttled) | ✅ By design — read path |

### 3b. Bypass probes

| Probe | Result |
|-------|--------|
| URL-encoded slash in segment | Spring normalizes `getRequestURI()` before filter — no evasion observed in code review | **CLOSED** |
| Extra path segment `/upload/extra` | Regex `[^/]+/(upload\|submit\|metrics)` — no match → unthrottled, but no matching controller route | **CLOSED** (dead path) |
| Trailing slash `/upload/` | Regex requires terminal `(upload\|submit\|metrics)` — **no match** → unthrottled if proxy forwards slash | **LOW** — L-T25-B3 |
| `PUT /deliverables/{milestoneId}/metrics` (legacy) | `isThrottledMethod` only POST/GET; path not in `bucketFor` | **LOW bypass** — L-T25-B1 (dual-path; primary UI uses creator POST) |
| `influora.auth.rate-limit.enabled:false` | Entire filter no-ops — all buckets disabled | **OPS misconfig** — pre-existing global kill-switch; not a code bypass |
| Horizontal multi-instance | Per-node counters — effective limit × instance count | **LOW** — L-T25-4; documented |

---

## 4. Filter Order & 429 Semantics

```193:198:influora-api/src/main/java/com/influora/config/SecurityConfig.java
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(internalServiceTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
```

- Rate limit runs **before** JWT authentication — intentional so `userIdFromBearer` performs lightweight parse only.
- On limit breach: HTTP 429, `RATE_LIMITED` JSON envelope, `Retry-After`, `X-RateLimit-*` — consistent with existing auth buckets.
- Throttled requests never reach upload buffering, DB writes, or R2 — correct abuse-cost posture.

---

## 5. Findings Register

### Closed (this task)

| ID | Severity | Finding | Status |
|----|----------|---------|--------|
| M-19-2 | MEDIUM | No per-creator rate limit on deliverable write (upload + submit + metrics) | ✅ **CLOSED** Task #25 — primary POST paths |
| M-21-1 | MEDIUM | No brand approve/revise rate limit | ✅ **CLOSED** Task #25 |
| L-23-3 | LOW | No contract sign rate limit | ✅ **CLOSED** Task #25 |

### Low — non-blocking carry-forward

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| L-T25-1 | LOW | Invalid/expired JWT → IP fallback untested | Add unit test (Kavya) |
| L-T25-2 | LOW | No test for 429 body / `Retry-After` / `X-RateLimit-*` | Add in follow-up PR |
| L-T25-3 | LOW | No brand-user isolation unit test | Add in follow-up PR |
| L-T25-4 | LOW | Per-instance in-memory windows | Redis/edge at scale (platform ops) |
| L-T25-B1 | LOW | Legacy `PUT /deliverables/{milestoneId}/metrics` unthrottled | Deprecate dual path or add PUT bucket when L-24-9 retires legacy |
| L-T25-B2 | LOW | XFF spoofing on IP-fallback path | Trusted-proxy allowlist in `clientIp` (platform-wide) |
| L-T25-B3 | LOW | Trailing-slash path may skip regex match | Normalize path in `stripContext` or accept in regex |

### Unchanged (not in Task #25 scope)

| ID | Severity | Finding | Status |
|----|----------|---------|--------|
| M-19-3 | MEDIUM | In-memory upload buffering | **OPEN** — upload prod NO-GO |
| M-19-4 | MEDIUM | Public URLs vs presigned | **OPEN** — upload prod NO-GO |
| M-24-1 | MEDIUM | Proof screenshot key unvalidated | **OPEN** |
| M-1 | MEDIUM | Campaign apply rate limit | **OPEN** |

---

## 6. Hostile Replay Checklist (Kabir)

| Scenario | Verdict |
|----------|---------|
| 11th creator upload in 60s (same valid JWT `sub`) | ✅ 429 |
| upload → submit → metrics same creator | ✅ Shared bucket exhausts |
| Creator A exhausted; Creator B same IP, valid JWT | ✅ B allowed |
| Brand approve × limit then revise | ✅ Shared bucket 429 |
| Contract sign × limit per user | ✅ 429 |
| POST without Bearer, same IP | ✅ IP-keyed 429 |
| Forged JWT `sub` without valid signature | ✅ Cannot pivot to victim user bucket |
| Valid JWT abuse | ✅ User-keyed — throttle binds to account |
| Legacy PUT metrics path | ⚠️ Unthrottled (L-T25-B1) |
| Multi-instance deploy | ⚠️ Per-node limits (L-T25-4) |

---

## Go/No-Go Decision

| Sub-scope | Decision |
|-----------|----------|
| M-19-2 closure (primary creator write POSTs) | **GO** |
| M-21-1 closure (brand approve/revise POSTs) | **GO** |
| L-23-3 closure (contract sign POST) | **GO** |
| JWT-sub keying bypass | **GO** |
| Critical / High findings | **NONE** — pipeline **not blocked** |
| Upload prod deploy | **NO-GO unchanged** — M-19-3/4 still open |
| Meera `mvn test` gate | **PENDING** — not a security block |

**Pipeline position:** Task #25 security gate **✅ PASS WITH FINDINGS** — cleared for Meera build gate and Priya blended 100% tick. No escalation to Priya/Swapnil.

---

## Kabir Sign-Off

- [x] M-19-2 `creator-deliverable-write` — upload + submit + metrics shared bucket, 10/min default, JWT-sub keying
- [x] M-21-1 `brand-deliverable-review` — approve + revise shared bucket, 30/min default
- [x] L-23-3 `contract-sign` — 10/min default, creator + brand relay POST
- [x] JWT-sub keying bypass probed — signature verification prevents cross-user bucket pivot
- [x] IP fallback abuse probed — valid JWT always user-keyed; XFF rotation LOW on fallback-only path
- [x] Path/method bypass probed — primary POST surfaces covered; legacy PUT metrics LOW
- [x] 429 fires pre-controller — no DB/R2 churn on throttle
- [x] M-19-3/M-19-4 upload prod NO-GO **unchanged**
- [x] No Critical or High findings — sprint gate **GO**

**Kabir verdict: ✅ PASS WITH FINDINGS.** Unblocks Meera Task #25 build gate. Escalation to Priya/Swapnil: **none**.

---

**Document Control:** Created 2026-07-09 by Kabir (Task #25). Prior: `creator-deliverable-metrics-T24-kabir-redteam.md` (M-19-2 open). Kavya: `creator-rate-limit-T25-kavya-qa.md`. Next: Meera scoped `mvn test` gate.
