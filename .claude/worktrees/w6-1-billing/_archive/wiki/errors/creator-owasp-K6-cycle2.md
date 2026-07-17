# Creator Platform — OWASP K6 Cycle 2 (Kabir)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-10 (~14:35 IST)  
**Task:** K6-2 · P0 (Tick #34) · OWASP cycle 2 kickoff + Vikram #39/#40 re-spot  
**Verdict:** ⚠️ **PASS WITH FINDINGS** — **0 Critical, 0 High, 5 Medium, 8 Low**  
**Scope:** Spec 12 §1–§2 + §8 Authentication / OAuth probes; adversarial re-spot of Task #39 (`AuthRateLimitFilter`) and Task #40 (`CreatorDeliverableService`)  
**Reference:** Cycle 1 `wiki/errors/creator-owasp-K6-kickoff.md`; `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md`  
**Note:** Cycle 2 advances auth/OAuth/session coverage. **#39 and #40 re-spots PASS** (no Critical/High). M-K6-2 Redis remains **P1 documented, non-blocking**. Security matrix ~48% → **~62%**.

---

## Executive Summary

Vikram’s Tick #34 landings close the systemic HTTP rate-limit blind spots filed in cycle 1 (**M-K6-1 / M-K6-3 flood / M-K6-4 / M-K6-5**) and the deliverable upload hardening debt (**M-19-3 / M-19-4 / M-24-1**). Adversarial re-read of the filter, K6 bucket tests, and deliverable service confirms the fixes hold — **no Critical or High** on either slice.

Cycle 2 auth/OAuth/session probes find **no ship-blockers**. Refresh rotation, reset single-use + session revoke, OTP hash-at-rest, Meta state CSRF, and BCrypt(12) are sound. **All five Cycle 2 Mediums are CLOSED** (C2-1…5) after Tick #35 V-GA-2…5 + A-GA-2 re-spots.

**#39 / #40 gates:** cleared for Meera scoped verify → Priya. **Creator GA** still requires M-K6-2 (P1) and cycles 3–4 (PII / malware / dependency CVE).

---

## Priority 1 — Re-spot Vikram #39 (`AuthRateLimitFilter`)

**Files:** `AuthRateLimitFilter.java`, `AuthRateLimitFilterK6BucketTest.java`

| Bucket | Limit / window | Keying | Status |
|--------|----------------|--------|--------|
| `review-write` | 10/min | JWT `sub` | ✅ CLOSED (M-K6-1) |
| `review-flag` | 5/min | JWT `sub` | ✅ CLOSED flood (M-K6-3) |
| `dispute-open` | 5/min | JWT `sub` | ✅ CLOSED (M-T34-2 / M-K6-1) |
| `discovery-invite` | 10/min | JWT `sub` | ✅ CLOSED (M-K6-1) |
| `discovery-search` | 60/min GET `/creators` + `/creators/search` | JWT `sub` | ✅ CLOSED (M-K6-5) |
| `campaign-apply` | 10/min | JWT `sub` | ✅ CLOSED (M-K6-1) |
| `creator-withdraw` | **5/hr** (`withdrawWindowSeconds=3600`) | JWT `sub` | ✅ CLOSED (M-K6-4) |

**Probes:**
- Path matchers cover creator + brand review create/flag, dispute open, invite, apply, withdraw, discovery list/search only (featured excluded) — **PASS**.
- `isUserKeyedBucket` includes all K6 buckets; Bearer parse via `JwtService.parseAccessToken` → `sub`; IP fallback without JWT — **PASS** (tested).
- Withdraw `Retry-After` uses hour window — **PASS** (tested).
- Bucket independence (deliverable-write vs review-write; write vs flag) — **PASS** (tested).
- **M-K6-2** in-memory / per-instance — still **P1**, explicitly documented in filter javadoc — **do not block**.

**#39 verdict:** ✅ **PASS** — Meera may run `AuthRateLimitFilterK6BucketTest`; Priya may sign after Meera green.

**Residual (not #39 scope):** M-K6-3 uniqueness / `flagged_by_user_id` — ✅ **CLOSED** via V-GA-5 — see **M-K6-C2-5**.

---

## Priority 2 — Re-spot Vikram #40 (`CreatorDeliverableService`)

**File:** `CreatorDeliverableService.java` (+ `ProofObjectKeys`, `R2StorageService.presignGet`)

| Finding | Probe | Status |
|---------|-------|--------|
| **M-19-3** | `streamToR2` uses `getInputStream` + `LimitedInputStream` + `DigestInputStream`; **no** `MultipartFile#getBytes()` | ✅ CLOSED |
| **M-19-4** | Persist R2 **object keys**; responses via `presignGet` / `resolveDownloadUrl` | ✅ CLOSED |
| **M-24-1** | `ProofObjectKeys.build` + `requireOwnedProofKey` rejects foreign / absolute / `..` / URL keys | ✅ CLOSED |
| **M-2** | `TextSanitizer` on Review / Dispute / Deal / Collaboration / deliverable caption-notes-hashtags | ✅ CONFIRMED |

**Adversarial notes (non-blocking):**
- `resolveDownloadUrl` falls back to stored value when R2 unavailable — acceptable for local/dev; prod must have R2 (**L-K6-C2-6**).
- Unit coverage for stream / presign / proof-bind paths is thin vs Task #25 pattern — Meera/Vikram backfill recommended (**L-K6-C2-5**).
- Portfolio upload still uses `getBytes` + `publicUrl` — **out of #40 scope**; track for cycle 3 content pass.

**#40 verdict:** ✅ **PASS** — cleared for Meera build gate → Priya.

---

## OWASP Cycle 2 — Auth / OTP / Session / OAuth Probes

Mapped to Spec 12 §8 Authentication + OAuth (cycle 1 deferred items).

### Authentication checklist

| Probe | Result | Evidence |
|-------|--------|----------|
| Brute force login | ✅ Mitigated | `AuthRateLimitFilter` `"sensitive"` 10/min + `"otp"` 5/min |
| OTP enumeration | ✅ **CLOSED** | V-GA-2 uniform success — **M-K6-C2-1** |
| Password reset reuse | ✅ CLOSED | `findByTokenHashAndUsedFalse` + `markUsed` + `revokeAllForUser` |
| Session hijacking (refresh) | ✅ Mitigated | Rotation burns presented refresh; HttpOnly cookie + `withoutRefresh()` on login/register |
| JWT manipulation | ✅ CLOSED (prior) | HMAC verify in `JwtAuthenticationFilter`; spoof fails signature |

### OTP deep-dive (Spec §1.2)

| Control | Spec | Code | Status |
|---------|------|------|--------|
| 6 digits SecureRandom | Required | `SecureRandom.nextInt` | ✅ |
| TTL 5 min | Required | `OTP_TTL_SECONDS = 300` | ✅ |
| Max 3 verify attempts | Required | `MAX_ATTEMPTS = 3` | ✅ |
| 3 sends / email / hour | Required | `otp-send-per-email-per-hour:3` | ✅ |
| Hash at rest | Required | `JwtService.hashToken` (SHA-256) | ✅ |
| Delete after verify | Required | Sets `verified=true`, does not delete | ⚠️ **L-K6-C2-1** |
| MSG91 delivery | Prod | TODO; plaintext OTP logged in **dev only** | ⚠️ **L-K6-C2-8** |

### Session (Spec §1.4)

| Control | Status |
|---------|--------|
| Refresh rotate on use | ✅ `AuthService.refresh` revoke + mint |
| Invalidate all on password change | ✅ `resetPassword` → `revokeAllForUser` |
| Logout revoke | ✅ `logout` → `revokeAllForUser` |
| List / revoke specific sessions | ⬜ Not built — **L-K6-C2-7** |
| Access token storage | ✅ **CLOSED** — memory + `sessionStorage` interim; legacy LS scrubbed — **M-K6-C2-3** |
| Refresh in JS | ✅ Cookie-only for SPA (`withoutRefresh`, HttpOnly) |

### Password (Spec §1.1)

| Control | Status |
|---------|--------|
| Min 8 chars | ✅ `@Size(min = 8)` |
| Upper / lower / number | ✅ **CLOSED** — `PasswordPolicy` — **M-K6-C2-2** |
| Top-10k common denylist | ✅ **CLOSED** — `common-passwords.txt` classpath — **M-K6-C2-2** |
| BCrypt cost 12 | ✅ `BCryptPasswordEncoder(12)` |

### OAuth (Spec §2 + §8)

| Probe | Result |
|-------|--------|
| CSRF on callback | ✅ `MetaOAuthStateStore` ULID state, user-bound, single-use, 10 min TTL |
| Token encryption at rest | ✅ AES-256-GCM (`MetaTokenStorage`) — cycle 1 confirmed |
| PKCE | ✅ **CLOSED** — S256 `code_challenge` on authorize + `code_verifier` on exchange; verifier bound in state store — **M-K6-C2-4** |
| Scope minimization | ✅ Hardcoded `REQUIRED_SCOPES` (no publishing) |
| Token in error logs | ⚠️ Meta failures log status/body — watch for token leakage in Meta error payloads (**carry into cycle 3**) |
| State store cluster | Same class as M-K6-2 — in-memory — **L-K6-C2-4** |

### Spec 12 §8 coverage update

| Area | Cycle 1 | Cycle 2 |
|------|---------|---------|
| Authentication | 2/5 | **5/5 probed** (OTP enum + password policy + access-token storage **CLOSED**) |
| OAuth | 1/4 | **4/4 probed** (PKCE **CLOSED**) |
| Authorization / Payments / Content | Unchanged | Cycle 3–4 |

---

## Findings Register (Numbered)

### Blockers

**None.** No Critical / High. #39 and #40 do **not** block.

### Medium — pre-prod / GA hardening

| ID | Finding | Action |
|----|---------|--------|
| **M-K6-C2-1** | **OTP email enumeration** — ~~`sendOtp` throws `EMAIL_ALREADY_EXISTS`~~ | ✅ **CLOSED** (V-GA-2) — `BrandEmailOtpService.sendOtp` always returns identical success shape; rate limit first; registered emails persist challenge (quota) but skip delivery; tests `testSendOtpRegisteredEmailUniformSuccess` + shape-identical |
| **M-K6-C2-2** | **Password policy incomplete** — ~~only `@Size(min=8)`~~ | ✅ **CLOSED** (V-GA-3) — `PasswordPolicy` upper/lower/digit + classpath denylist; wired `AuthService` brand/creator register + `resetPassword`; `PasswordPolicyTest` |
| **M-K6-C2-3** | **Access JWT in `localStorage`** — ~~any XSS steals short-lived access token~~ | ✅ **CLOSED** (A-GA-2) — `setAccessToken` → memory + `sessionStorage` only; legacy `brand_token`/`creator_token` scrubbed on read/write; refresh stays HttpOnly; prod CSP `script-src 'self'` (`vite.config.ts` + `public/_headers`). Residual: same-origin XSS can still read `sessionStorage` (accepted interim vs pure memory; CSP reduces script injection) |
| **M-K6-C2-4** | **Meta OAuth missing PKCE** — ~~spec §2.2 requires `code_verifier`/`code_challenge`~~ | ✅ **CLOSED** (V-GA-4) — `generatePkce` SecureRandom 32-byte verifier + S256 challenge; authorize URL sends `code_challenge`/`code_challenge_method=S256`; `MetaOAuthStateStore` binds verifier to user+state (single-use consume); callback `exchangeCodeForToken(code, codeVerifier)`. Tests: `MetaOAuthServiceTest` + `MetaOAuthControllerTest` |
| **M-K6-C2-5** | **M-K6-3 residual** — ~~no `flagged_by_user_id` + unique `(content_id, user)`~~ | ✅ **CLOSED** (V-GA-5) — Flyway `V46__content_flag_user_uniqueness.sql` adds `flagged_by_user_id` + `uq_content_flag_user`; `ReviewService.saveFlag` exists-check → 409 `ALREADY_FLAGGED` + `DataIntegrityViolationException` race catch; `ContentFlag.userFlag` sets user id. Test: `creatorFlagDuplicateRejected` |

### Documented P1 (non-blocking this gate)

| ID | Finding | Action |
|----|---------|--------|
| **M-K6-2** | In-memory rate limiter (and Meta state store) not cluster-global | Redis / edge WAF before multi-node GA |

### Low — next sprint / cycles 3–4

| ID | Finding | Action |
|----|---------|--------|
| L-K6-C2-1 | OTP challenge not deleted after verify | Delete or expire row on success |
| L-K6-C2-2 | `require-email-otp-before-register` defaults `false` | Prod default `true` |
| L-K6-C2-3 | Access JWT HS256 vs spec RS256 | Document ADR or migrate |
| L-K6-C2-4 | `MetaOAuthStateStore` in-memory | Bundle with M-K6-2 Redis move |
| L-K6-C2-5 | #40 stream/presign/proof unit tests thin | Vikram/Meera backfill |
| L-K6-C2-6 | `resolveDownloadUrl` fallback to stored URL if R2 down | Fail closed in prod |
| L-K6-C2-7 | No list/revoke-specific-session API (spec §1.4) | Session management slice |
| L-K6-C2-8 | MSG91 OTP send TODO (P1 queue) | Wire before prod email OTP |

**Cycle 1 Low carry-forwards** (L-K6-1…12) remain open except L-K6-9/10 closed by #40 and rate-limit items closed by #39.

---

## Files Reviewed (Cycle 2)

**#39 / #40:**
- `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java`
- `influora-api/src/test/java/com/influora/security/AuthRateLimitFilterK6BucketTest.java`
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java`
- `influora-api/src/main/java/com/influora/common/ProofObjectKeys.java`
- `influora-api/src/main/java/com/influora/integration/storage/R2StorageService.java`
- `influora-api/src/main/java/com/influora/common/TextSanitizer.java`
- `influora-api/src/main/java/com/influora/domain/entity/{Review,Dispute,Collaboration}.java`
- `influora-api/src/main/java/com/influora/service/{ReviewService,DealService}.java`

**Auth / OTP / Session / OAuth:**
- `influora-api/src/main/java/com/influora/service/{AuthService,BrandEmailOtpService}.java`
- `influora-api/src/main/java/com/influora/web/AuthController.java`
- `influora-api/src/main/java/com/influora/security/JwtService.java`
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java`
- `influora-api/src/main/java/com/influora/web/dto/auth/{Brand,Creator}RegisterRequest.java`
- `influora-api/src/main/java/com/influora/integration/meta/oauth/{MetaOAuthService,MetaOAuthStateStore,MetaTokenStorage}.java`
- `src/lib/{auth-session.ts,api.ts}`

---

## Pipeline Routing

```
K6-2 (this doc) ──► PASS WITH FINDINGS (0C/0H/5M→0M open/8L)
        │
        ├──► #39 ✅ PASS → Meera AuthRateLimitFilterK6BucketTest → Priya
        ├──► #40 ✅ PASS → Meera scoped deliverable tests/build → Priya
        ├──► Vikram: M-K6-C2-1/2/4/5 ✅ CLOSED
        ├──► Ananya: M-K6-C2-3 ✅ CLOSED (A-GA-2)
        ├──► Kabir K-GA-2/3: ✅ C2-3/4/5 re-spot CLOSED; K6-3 NOT started this tick
        └──► Priya: Final GA security still needs cycles 3–4 + M-K6-2 (P1)
```

**Security matrix:** ~68% → **~75%** (C2-3/4/5 closed on Tick #35 K-GA-2/3 re-spot).

---

## Tick #35 K-GA-2/3 re-spot addendum (2026-07-10 ~15:45 IST)

| ID | Ship | Verdict |
|----|------|---------|
| M-K6-C2-3 | A-GA-2 | ✅ **CLOSED** — no access JWT writes to `localStorage`; CSP prod hardened |
| M-K6-C2-4 | V-GA-4 | ✅ **CLOSED** — RFC 7636 S256 end-to-end (authorize → state store → token exchange) |
| M-K6-C2-5 | V-GA-5 | ✅ **CLOSED** — app gate + DB unique; race → 409 |

**K6-3:** NOT started this tick (per Arjun dispatch).

---

*Kabir Singh, Offensive Security / Red-Team Lead — Sage Digital — Tick #35 K-GA-2/3 re-spot*
