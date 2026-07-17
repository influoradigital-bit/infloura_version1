# Kabir Red-Team — Wave 1 "Trust & Money" — OWASP Security Gate

**Date:** 2026-07-14
**Reviewer:** Kabir (Red-Team / OWASP audit)
**Scope:** Post-Kavya-QA, post-Meera-local-verify security gate on the Wave 1 batch (creator bank/UPI primary-selection, PayoutService fund-account resolution, WalletController endpoints, AccountController soft-delete, User.softDelete(), brand_safety.router mount, frontend payout-methods client).
**Predecessors:** Kavya QA PASS (`wiki/errors/wave1-trust-money-qa-FINAL-PASS.md`), Meera local-verify PASS (SHARED_CONTEXT.md "MEERA → KABIR | WAVE 1 LOCAL VERIFICATION — PASS").

## VERDICT: **PASS** (after H-1 re-test fix, verified below). Merge-clear to Priya. L-1/L-2/L-3 remain as recommended, non-blocking hardening.

The core money-path logic this pass added (pessimistic-lock primary selection, DB uniqueness backstop, explicit-primary fund-account resolution, 24h cool-down re-enforcement at the actual money-movement point) is **solid and correctly closes the races it claims to close**. The originally-blocking issue was a session-invalidation gap on `DELETE /me/account`'s soft-delete — see the re-test addendum immediately below for the fix and independent verification.

---

## RE-TEST ADDENDUM (2026-07-14, same day) — H-1 fix verified, gate now PASSES

Vikram fixed H-1 by adding a `deletedAt` check at the two shared authorization gates every creator/brand-scoped endpoint already calls, rather than adding a DB round-trip to `JwtAuthenticationFilter` (correctly scoped — the filter runs on every request including high-frequency/public paths; the narrower, already-mandatory choke points are the right place). Independently verified, not just re-reading the diff:

- **`CreatorContextService.requireCreator`** (`influora-api/src/main/java/com/influora/service/CreatorContextService.java:34-45`) now does `userRepository.findById(principal.getUserId()).map(u -> u.getDeletedAt() != null).orElse(true)` and throws `401 ACCOUNT_DELETED` if true. **Fail-closed confirmed**: a missing user row (`orElse(true)`) is treated as deleted, not silently skipped.
- **`BrandContextService.requireBrand`** (`influora-api/src/main/java/com/influora/service/BrandContextService.java:41-52`) — identical fail-closed logic, for parity (deletion isn't creator-specific).
- **`AccountController.java:29-38`** javadoc corrected — no longer claims refresh-token revocation alone is what makes deletion "immediate"; now correctly attributes the guarantee to the two context-service gates.
- Confirmed `BrandContextService` is never constructed directly outside Spring DI anywhere in the codebase (`grep -rn "new BrandContextService(" src/` → zero hits), so Vikram's claim that no other test needed touching for it is correct. `CreatorContextService` is only directly constructed in its own test, which is the one that was updated.

**Independently re-ran the targeted tests myself** (not reusing Vikram's numbers) via `mvn -o -Dtest=CreatorContextServiceTest,CreatorBankAccountServiceTest,PayoutServiceTest,WalletControllerTest test`, reading `target/surefire-reports/*.txt` directly:

| Test class | Result |
|---|---|
| `CreatorContextServiceTest` | ✅ 5/5 pass (incl. the 2 new H-1 tests: deleted-user rejected `ACCOUNT_DELETED`/401, missing-user-row treated as deleted) |
| `CreatorBankAccountServiceTest` | ✅ 7/7 pass |
| `PayoutServiceTest` | ✅ 9/9 pass |
| `WalletControllerTest` | ✅ 2/2 pass |

**23/23 pass**, matching Vikram's reported numbers exactly. Also ran `mvn -o clean compile` myself → clean compile, no errors, confirming the new `UserRepository` dependency wiring in both context services doesn't break anything else.

**Residual note (not blocking):** each call to `requireCreator`/`requireBrand` now costs one extra `SELECT` — fine for these endpoints' traffic profile, but a request path that calls `requireBrandWorkspace` and then `requireMember` (both call `requireBrand` internally) now does 2 redundant deleted-checks per request. Not worth blocking on; flag for a future pass if it shows up in profiling.

**H-1 is closed. Gate PASSES. Routing to Priya for final sign-off.**

---

## ORIGINAL AUDIT (below, for the record — findings as first reported before the H-1 fix)

---

## HIGH

### H-1: Soft-delete does not invalidate already-issued access tokens — contradicts the "immediate" claim in `AccountController`

**Files:** `influora-api/src/main/java/com/influora/web/AccountController.java:47-49`, `influora-api/src/main/java/com/influora/security/JwtAuthenticationFilter.java:25-47`, `influora-api/src/main/java/com/influora/service/AuthService.java:355-358`, `influora-api/src/main/java/com/influora/service/CreatorContextService.java:21-26`

`AccountController.deleteAccount` javadoc (lines 47-49) states: *"invalidates their session (revokes all refresh tokens + clears the refresh cookie) so the deletion takes effect immediately rather than waiting for access-token expiry."* This claim is false for the access token itself.

- `AuthService.logout(userId)` (AuthService.java:356-358) only calls `refreshTokenRepository.revokeAllForUser(userId)` — it revokes refresh tokens, never touches access tokens.
- `JwtAuthenticationFilter.doFilterInternal` (JwtAuthenticationFilter.java:26-47) builds the `AuthPrincipal` **entirely from the JWT claims** (`claims.getSubject()`, `email`, `userType`, `workspaceId`) with **no DB lookup** against the `users` table — no check of `deletedAt`/`status` at all.
- Confirmed via grep: `isDeleted`/`deletedAt` are referenced in exactly two files in the whole codebase — `AccountController.java` and `User.java` itself. No filter, no `CreatorContextService`/`BrandContextService` gate, and no access-token blocklist/deny-list anywhere in `influora-api/src/main/java` checks it.
- Access-token TTL default is `900` seconds (`application.yml:88`, `JWT_ACCESS_EXPIRY`).

**Exploit scenario:** A user's session is compromised (token exfiltrated via XSS, a shared/stolen device, etc.). The user (or support) hits "Delete Account" as an incident-response reaction, expecting it to lock the attacker out. `softDelete()` runs, refresh tokens are revoked, the refresh cookie is cleared — but the attacker's already-issued access token, cached client-side, keeps working for **up to 15 minutes** against every authenticated endpoint, including the money-adjacent ones this pass ships: `POST /wallet/payout-methods` (add a bank/UPI instrument), `PUT /wallet/payout-methods/{id}/primary` (redirect where future payouts go), and `POST /wallet/withdraw`. `CreatorContextService.requireCreator` (CreatorContextService.java:21-26) checks only `principal.getUserType()` from the token — it never re-resolves the user row, so it has no opportunity to reject a deleted user either.

**Why High and not Critical:** requires a pre-existing valid, unexpired access token (i.e., this doesn't grant new access — it fails to promptly revoke access that already existed), and the window is bounded to ≤15 minutes. It is High rather than Medium because (a) it directly contradicts an explicit, load-bearing security claim in the code's own javadoc, (b) the reachable surface during that window includes real payout-redirection and withdrawal endpoints, and (c) account deletion is the canonical "lock the attacker out now" control users and incident response rely on.

**Fix options (pick one, does not require redesigning JWT scheme):**
1. Add a `deletedAt IS NULL` (or `status != 'DELETED'`) check on the hot path — cheapest: a short-TTL cache-backed "deleted user id" set checked in `JwtAuthenticationFilter` or in a `requireActiveUser` gate called from `CreatorContextService`/`BrandContextService`, populated by `AccountController.deleteAccount`.
2. Shorten access-token TTL further and accept the bounded window explicitly (update the javadoc to stop claiming "immediate").
3. Add a per-user "tokens valid after" timestamp claim check (store `deletedAt`/`credentialsChangedAt` and compare against `iat` in the filter) — standard stateless-revocation pattern, no DB lookup needed on steady-state requests only on this specific check.

Any of these closes the gap. At minimum, fix the javadoc to not overclaim if the team accepts the 15-minute window as a conscious tradeoff — but given this ships alongside new money-movement endpoints, closing the gap is the right call before merge.

---

## MEDIUM

None.

## LOW

### L-1: `AddBankAccountRequest` is a Java record — auto-generated `toString()` includes raw PII
**File:** `influora-api/src/main/java/com/influora/web/dto/wallet/BankAccountDtos.java:18-22`

`AddBankAccountRequest(String type, String accountOrVpa, String ifsc, String displayMask)` is a record; Java generates a `toString()` that prints every field, including the raw account number/UPI VPA and IFSC. I found **no current call site** that logs this object (checked `GlobalExceptionHandler.java` — `handleValidation`/`handleGeneric` never log the request; no `CommonsRequestLoggingFilter` or request-body logging aspect exists anywhere in `influora-api/src/main/java`). So there is no live leak today. It is latent risk: a future `log.debug("addBankAccount body={}", body)` (easy to add during debugging, easy to forget to remove) would put raw bank/UPI PII into plaintext logs, defeating the entire encrypt-before-ship guarantee (`CreatorBankAccountService` M-K6-C3-2) at the logging layer instead of the persistence layer.

**Recommendation:** Override `toString()` on `AddBankAccountRequest` to redact `accountOrVpa`/`ifsc` (e.g. `"AddBankAccountRequest[type=...]"`), the same discipline already applied to caption redaction in `influora-ai/app/routes/brand_safety.py` (`shape_of`, never-log-raw).

### L-2: `PayoutService.resolveFundAccountForCreator`'s fallback path re-implements the exact selection logic this migration was designed to eliminate
**File:** `influora-api/src/main/java/com/influora/service/PayoutService.java:330-346`

The primary path (`findByCreatorUserIdAndPrimaryTrue`) is correct and is what `CreatorBankAccountService` maintains as an invariant (exactly one primary once ≥1 row exists, enforced by locking + the V62 unique index). The defensive `.orElseGet(...)` fallback, however, reverts to `findByCreatorUserIdOrderByCreatedAtDesc().stream().findFirst()` — i.e., "most recently created wins" — which is precisely the pre-fix implicit-primary behavior the migration's own comment (V62, lines 1-5) says caused the original risk (a newly-added backup instrument silently becoming the payout destination).

I could not construct a reachable path that triggers this fallback today: `CreatorBankAccountService` is the only writer of `CreatorBankAccount` rows in this codebase, and its invariant (locked before every write) guarantees the primary flag is never absent once a row exists. This is why it's Low, not Medium/High. But:
- It is **untested** — `PayoutServiceTest.mockFundAccountResolution()` (PayoutServiceTest.java:101-117) only stubs the `findByCreatorUserIdAndPrimaryTrue` present-case; the fallback branch has zero test coverage.
- It is a landmine for any future write path (an admin backfill tool, a data-migration script, a restored backup, a bulk-import) that inserts a `CreatorBankAccount` row outside `CreatorBankAccountService` and forgets to set `is_primary` — such a path would silently reactivate the exact vulnerability this pass fixed, with no test to catch it and no log/metric to flag that the fallback ever fired.

**Recommendation:** Add a `log.warn(...)` (or a metric) when the fallback branch executes, and add a unit test exercising it, so a future regression is loud instead of silent.

### L-3: No integration test exercises real concurrency against the pessimistic lock
**File:** `influora-api/src/test/java/com/influora/service/payout/CreatorBankAccountServiceTest.java`

The 7 unit tests correctly lock in the *code path* (locked-row-set resolution, ownership, 409-on-conflict), but `CreatorBankAccountRepository` is mocked throughout — none of them actually prove `@Lock(PESSIMISTIC_WRITE)` serializes two real concurrent transactions against a real database. This is standard/acceptable for unit tests, but there is currently no integration test (e.g., two threads/two transactions racing `setPrimary` against an embedded/test MySQL) that would catch a regression if `lockAllForCreatorUpdate`'s `@Query`/`@Lock` annotation were ever silently dropped or miscompiled by a future Hibernate/JPA version. Not blocking; flagging as a coverage gap given how load-bearing this lock is (it's the only thing standing between "clean UX" and "every double-click surfaces a 409," with the DB unique index as the sole true backstop).

---

## PASS (no findings)

- **`setPrimary` ownership enforcement** (`CreatorBankAccountService.java:128-164`): `lockAllForCreatorUpdate(profile.getUserId())` scopes the locked row set to the *authenticated* creator's own `creatorUserId` before searching for `bankAccountId` — a crafted id belonging to another creator simply isn't in the locked list and 404s (`BANK_ACCOUNT_NOT_FOUND`), never leaking existence of another creator's account. Verified by `setPrimary_unknownId_throws404` test.
- **`setPrimary`/`addInstrument` race closure**: the pessimistic lock closes the race for the case that matters (≥1 existing row); the one genuinely unclosable case (zero rows, nothing to lock, two concurrent first-adds) is correctly caught by the V62 `uq_creator_bank_accounts_primary_marker` unique index and translated to a clean 409, not silent corruption or a raw SQL exception leak.
- **`listForCreator`**: returns entities mapped through `BankAccountResponse.from()` (`WalletController.java:150-154`, `BankAccountDtos.java:25-36`) which only exposes `id/type/displayMask/isPrimary/usable` — never `accountCiphertext`/`ifscCiphertext`. No decryption occurs on this path (`@Transactional(readOnly = true)`, no `cipher.decrypt` call in `listForCreator`).
- **V62 migration**: backfill's `ORDER BY created_at DESC, id DESC LIMIT 1` correlated subquery is injection-safe (no string concatenation, static SQL) and deterministically ties to exactly one winner per creator (ULIDs are creation-order monotonic) — cannot leave a creator with zero primary rows if they previously had ≥1 row. The generated-column + unique-index backstop is a correct MySQL partial-unique-index workaround (NULL-for-non-primary rows never collide).
- **`PayoutService.resolveFundAccountForCreator` cannot be redirected cross-creator**: `creatorUserId` is derived from `collaboration.getCreatorId()` (trusted internal data, resolved after workspace-ownership validation in `validateForPayout`), never from attacker input. `RazorpayFundAccountService.resolveFundAccountId` (RazorpayFundAccountService.java:56-66) additionally re-validates via `findByIdAndCreatorUserId(bankAccountId, creatorUserId)`, so even a hypothetically-wrong `bankAccountId` can't cross creator boundaries.
- **24h cool-down is NOT bypassable via `setPrimary`**: initially looked like a gap (`setPrimary` doesn't check `isUsableAt`), but `RazorpayFundAccountService.resolveFundAccountId` (line 69) re-checks `bankAccount.isUsableAt(Instant.now())` on **every** call, including the cache-hit path check ordering (cool-down checked before the cached-fund-account-id short-circuit) — so setting a still-cooling-down instrument primary makes the *next real payout attempt fail closed* (`BANK_COOLDOWN_ACTIVE`, 425) rather than silently succeed. This is defense-in-depth done correctly.
- **`WalletController`**: all 3 payout-method endpoints correctly gate `creatorContext.requireCreator(principal)` first; `AddBankAccountRequest`'s raw fields are never logged anywhere in the controller or `CreatorBankAccountService.addInstrument` (verified no `log.*` call references `accountOrVpa`/`ifsc`/`body` in that method).
- **`User.softDelete()` itself** (User.java:258-269): deterministic anonymized email (`deleted-{id}@...`) cannot collide with the unique `email` constraint; `phoneNumber` set to `null` (MySQL doesn't collide NULLs in a unique index); `id`/`status`/`userType` deliberately untouched so FKs from deals/contracts/messages keep resolving — no orphaned-reference risk. `id` reuse is not possible (new signups always mint a fresh ULID). This method is correct in isolation; the gap is entirely in H-1 (session invalidation), not in the anonymization logic itself.
- **`brand_safety.router` mount** (`influora-ai/app/main.py:40`): reuses the existing hardened `service_token` auth path — `scope="service"` only (no `chat:stream` token can call it), `workspace_id` tenant-match enforced, asymmetric-alg-only JWKS verification (`ALLOWED_ALGS = RS256/ES256`, HS256 dev-fallback structurally gated to `env=dev` by two independent assertions), spend-gate checked before any provider call, per-field length caps on `content_id`/`caption`/`media_type`/`posted_at` (prompt-injection/DoS mitigation), caption never logged raw (`shape_of` only). No SSRF surface — this service never fetches from Meta/any external URL; captions are supplied by the Java caller. Clean.
- **Frontend (`src/lib/api.ts`, `src/pages/creator-wallet.tsx`)**: no `console.*` call anywhere in either file; raw `accountOrVpa`/`ifsc` values live only in React component state (cleared on success via `resetAddMethodForm`) and are never persisted to `localStorage`/`sessionStorage` (confirmed only auth tokens and a Meta-connection flag use `localStorage`, unrelated to bank/UPI PII) or rendered back — the render path only ever displays `method.displayMask` via plain JSX interpolation, never `dangerouslySetInnerHTML`.

---

## Existing test suite gaps (not blocking, noted for follow-up)
- `CreatorBankAccountServiceTest.java`: solid unit coverage of the locking *logic*, but no real-DB concurrency test (see L-3).
- `PayoutServiceTest.java`: `mockFundAccountResolution()` only ever stubs the primary-present case — the fallback branch (L-2) and a cooldown-active-on-primary rejection test are both untested gaps worth adding alongside the H-1 fix.

## Required before merge (original ask — now satisfied, see re-test addendum at top)
~~Fix **H-1** (soft-delete access-token invalidation gap) and re-request this gate.~~ **DONE — verified above.** L-1/L-2/L-3 remain recommended hardening, not blocking. **Final verdict: PASS, routed to Priya.**
