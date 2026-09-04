# PHONE-0904 — Sign-off Q&A (10 questions)

**Answered by:** Priya (CTO)
**Date:** 2026-09-04
**Branch:** `fix/f0390-money-flags-build-pipeline` (PHONE-0904 work is **uncommitted / partly untracked** — `UserPhoneService.java`, `src/lib/phone.ts`, `src/lib/phone.test.ts` are `??` in `git status`)
**Standard applied:** every claim below is read off the code or produced by executing it. Where the honest answer is "no" or "partially", it says so.

## Evidence actually executed for this review

| What | Command | Result |
|---|---|---|
| Backend main compile | `mvn -o compile` (influora-api) | BUILD SUCCESS |
| Backend test compile | `mvn -o test-compile` | BUILD SUCCESS, 0 errors |
| Phone-touching Java tests | `mvn -o surefire:test -Dtest='CreatorProfileServiceTest,CreatorOnboardingServiceTest,AuthServiceTest,AdminCreatorServiceTest'` | **Tests run: 70, Failures: 0, Errors: 0** |
| Frontend phone tests | `npx vitest run src/lib/phone.test.ts` | **28 passed** |
| Normalizer probe (Java) | throwaway `PQ.java` against compiled `IndianPhoneUtils` on JDK 21 | table in Q3 |
| Normalizer probe (TS) | node script replicating `src/lib/phone.ts` verbatim | table in Q3, identical to Java |

Caveat recorded honestly: on the first `mvn test` attempt the test sources failed to compile with ~30 `cannot find symbol` errors on unrelated classes (`WooCommerceIntegration`, `CouponRedemption`, `MetaAuthPath`). A clean re-run of `mvn -o test-compile` succeeded with zero errors and the classes exist on disk, so this was a transient state — almost certainly the concurrent session that is known to edit this repo (`project_concurrent_session_write_collisions`). Anyone re-running these gates should expect that flake and re-run rather than treat it as a phone defect.

---

## Q1 — Complete brand write path + proof of read-back after logout/login

**Write path (verified, complete):**

| Step | File:line |
|---|---|
| Field entry (Required) | `src/components/brand/onboarding/onboarding-steps.tsx:700-718` (input), `:453-454` (validation) |
| Client normalize + payload | `src/pages/brand-onboarding.tsx:16`, `:87` — `phone: normalizePhone(data.phone)` |
| Wire type | `src/lib/api.ts:801-811` `BrandRegisterPayload.phone?: string` |
| DTO field | `influora-api/src/main/java/com/influora/web/dto/auth/BrandRegisterRequest.java:27` — `@Size(max = 20) String phone` |
| Controller | `influora-api/src/main/java/com/influora/web/AuthController.java:65-67` |
| Service | `AuthService.java:137` `userPhoneService.normalizeAndValidate(req.phone())` → `:138-143` dup pre-check → `:155-157` `user.setPhoneNumber(normalizedPhone)` → `:183` `userRepository.saveAndFlush(user)` |
| Entity mapping | `domain/entity/User.java:24-25` — `@Column(name = "phone_number", unique = true)` |
| Column / migration | `influora-api/src/main/resources/db/migration/V2__core_auth.sql:6` — `phone_number VARCHAR(20) UNIQUE,` |

**Persistence proof (executed, green):** `AuthServiceTest.testBrandRegisterPersistsNormalizedPhone` (`AuthServiceTest.java:210-233`) captures the saved `User` and asserts `getPhoneNumber() == "9876543210"` from raw input `"+91 98765 43210"`.

**Read-back after logout/login: there is none, and no test can be written for it as built.**
- `UserDtos.UserProfileDto` (`web/dto/user/UserDtos.java:12-24`) has `phoneVerified` but **no `phone` field**. `GET /me` never returns the number.
- Exhaustive grep of `getPhoneNumber()` / `phoneNumber` across `influora-api/src/main/java` returns exactly 6 hits: `UserRepository.java:71` (existsBy), `AdminCreatorService.java:185` and `:523` (admin), `CreatorProfileService.java:230` (creator self), `UserPhoneService.java:111`, `BrandContextAssembler.java:46` (a comment denylisting it). **No brand-facing read path exists.**
- Worse: the number is sent **only** on the very first `brandRegister` call, and that call is guarded by `if (!hasBrandToken())` (`src/pages/brand-onboarding.tsx:61`). If a brand goes back to step 1 and corrects the mobile after the account exists, the edit is silently discarded — there is no second write path to `users.phone_number` for a brand anywhere in the codebase.

So a brand's mobile is **write-once, read-never** in-product. It is genuinely stored; it is not retrievable, editable, or verifiable by the brand or by a test through the API.

**Verdict: PARTIAL** — write path is complete and proven; the read-back the question asks for cannot exist because no brand-facing serializer carries the field and no brand-facing update path exists.

---

## Q2 — Creator changes ONLY the mobile: does a removal actually persist as cleared?

**Yes — removal persists. "Absent" and "explicitly cleared" are distinguished, correctly, at the call site.**

- Distinction: `CreatorProfileService.patchMyProfile` at `CreatorProfileService.java:108` — `if (req.phone() != null) { userPhoneService.applyPhone(user, req.phone()); }`. JSON `phone` absent → Jackson gives `null` → **no-op, field left alone**. JSON `phone: ""` → Jackson gives `""` → falls through to `applyPhone`, which at `UserPhoneService.java:104-107` does `user.setPhoneNumber(null); userRepository.save(user);` → **cleared**.
- Nothing else in `patchMyProfile` is nulled by a phone-only patch: `profile.applySelfEdit(...)` (`:79-91`) receives nulls for every other field and the record's own convention is "null = leave unchanged".
- Client sends the key explicitly: `src/pages/creator-settings.tsx:191` `api.creatorProfile.patchMe({ phone: normalized })` where `normalized` is `''` on removal; `api.ts:3483-3488` passes `payload` straight through as the body, so `{"phone":""}` goes on the wire — an empty string, not `undefined`, so it is not dropped by `JSON.stringify`.
- **Test that removes and re-reads empty:** `CreatorProfileServiceTest.testPatchClearsPhone` (`:290-306`) — seeds `user.setPhoneNumber("9876543210")`, patches with `""`, asserts `response.phone() == null` **and** `user.getPhoneNumber() == null`. Executed green in this review.
- Contract documented authoritatively in one place: `UserPhoneService.java:72-102`.

Minor note, not a defect: the re-read assertion is against the response DTO built from the same managed entity, not a fresh DB fetch (the whole suite is Mockito-based). The behaviour is right; the assertion is unit-level.

**Verdict: POSITIVE**

---

## Q3 — Per-input accept/reject table, backed by a real test file

Table produced by **executing** the real implementations, not by reading the regex: compiled `com.influora.common.IndianPhoneUtils` (JDK 21, against `target/classes`) and a node script replicating `src/lib/phone.ts` verbatim.

| Input | Normalized (canonical stored form) | Java | TS | Agree |
|---|---|---|---|---|
| `9876543210` | `9876543210` | ACCEPT | ACCEPT | yes |
| `+91 98765 43210` | `9876543210` | ACCEPT | ACCEPT | yes |
| `+919876543210` | `9876543210` | ACCEPT | ACCEPT | yes |
| `09876543210` | `9876543210` | ACCEPT | ACCEPT | yes |
| `91-9876543210` | `9876543210` | ACCEPT | ACCEPT | yes |
| `098765 43210 ` (trailing space) | `9876543210` | ACCEPT | ACCEPT | yes |
| `+९८७६५४३२१०` (Devanagari) | `` (empty) | **REJECT** | **REJECT** | yes |
| `9876543210x123` | `9876543210123` | **REJECT** | **REJECT** | yes |
| `5876543210` | `5876543210` | **REJECT** | **REJECT** | yes |
| `987654321012` (12-digit, not `91`-prefixed) | `987654321012` | **REJECT** | **REJECT** | yes |
| `919876543210` (12-digit, `91`-prefixed) | `9876543210` | ACCEPT | ACCEPT | yes |

Behaviour is correct on every input and the two implementations are byte-identical in outcome. Devanagari is safe because Java `[^0-9]` (`IndianPhoneUtils.java:36`) and Java `\d` in `^[6-9]\d{9}$` (`:19`) are both ASCII-only without `UNICODE_CHARACTER_CLASS`, and the JS `[^0-9]` / `\d` are likewise ASCII.

**Where this falls short of the question:** it asks for a *real test file*, and the committed tests do **not** cover 5 of these 11 inputs.
- There is **no `IndianPhoneUtilsTest.java` at all** — the Java normalizer has zero direct unit tests; it is exercised only indirectly through 3 service test classes.
- `src/lib/phone.test.ts` (28 tests, green) covers `+91 98765 43210`, `+919876543210`, `09876543210`, `9876543210`, `5876543210`, and length/leading-digit cases. It does **not** cover: Devanagari digits, `91-9876543210` (hyphen separator), `9876543210x123` (embedded letters), `987654321012`, or the 11-digit-with-trailing-space form.
- The Java side covers only `"+91 98765 43210"`, `"09876543210"`, `"12345"`, `"1234567890"` (`CreatorProfileServiceTest:227-343`, `CreatorOnboardingServiceTest:152-194`, `AuthServiceTest:211-268`).

The table above is real, but it is backed by my throwaway probe, not by a gate that will still be there next quarter.

**Verdict: PARTIAL** — behaviour correct on all 10 inputs, verified by execution; the test files do not lock in 5 of them, and the Java normalizer has no dedicated test file.

---

## Q4 — Is normalization server-side and before the uniqueness check?

**Yes, on all three write paths, and provably before the check — never only in the React input.**

- `AuthService.brandRegister`: `AuthService.java:137` `String normalizedPhone = userPhoneService.normalizeAndValidate(req.phone());` runs **before** `:138` `userPhoneService.isTaken(normalizedPhone)`. The DTO carries no `@Pattern` (`BrandRegisterRequest.java:27` is `@Size(max = 20)` only), so the server is doing the work, not Bean Validation on a client-shaped string.
- `UserPhoneService.applyPhone`: `UserPhoneService.java:110` normalize → `:111` `isTaken(normalized)`. Same ordering, one implementation, used by both creator paths.
- `isTaken` queries the canonical form only: `UserRepository.java:71` `boolean existsByPhoneNumber(String phoneNumber)` — an equality match against the stored 10-digit form.

**Answer to the curl scenario:** POSTing `+919876543210` while another account holds `9876543210` → `normalizeAndValidate` turns it into `9876543210` (proven in the Q3 table), `isTaken("9876543210")` returns true, and you get **`409 PHONE_ALREADY_EXISTS`**, not a silent second row. The two forms cannot both be stored.

Proof executed: `AuthServiceTest.testBrandRegisterPersistsNormalizedPhone` (`:210-233`) asserts the *stored* value is the normalized one; `CreatorProfileServiceTest.testPatchSetsPhoneNormalized` (`:226-249`) asserts `existsByPhoneNumber` is stubbed and consulted on `"9876543210"` after `"+91 98765 43210"` was submitted.

**Verdict: POSITIVE**

---

## Q5 — What enforces one-number-one-account?

**A real DB unique index in a named migration, not only an application check.**

- `influora-api/src/main/resources/db/migration/V2__core_auth.sql:6` — `phone_number VARCHAR(20) UNIQUE,` in the original `CREATE TABLE users`. Grep of every file in `db/migration/` shows no later `ALTER` touching it — this constraint has existed since the table did.
- Entity agrees with the schema: `domain/entity/User.java:24` `@Column(name = "phone_number", unique = true)` — so `ddl-auto=validate` will not crash on a name/constraint mismatch (checked deliberately; that failure mode has bitten this repo before).
- The application `existsByPhoneNumber` check is explicitly documented as a *narrowing* optimisation, not the guarantee: `UserPhoneService.java:90-101`, `AuthService.java:133-136`, `UserRepository.java:68-71`.

**Two concurrent signups:** the DB rejects the loser with a duplicate-key error. Both call sites catch `DataIntegrityViolationException` and translate it — `UserPhoneService.java:119-126` and `AuthService.java:187-201`. Critically, both use `saveAndFlush`, not `save` (`UserPhoneService.java:120`, `AuthService.java:183`): under an open `@Transactional` with a managed entity, a plain `save()` merge emits no SQL and Hibernate defers the INSERT to commit, i.e. *after* the catch scope has exited, letting the violation escape as a raw 500. This is exactly defect **D1** from `wiki/decisions/phone-0904-cto-review.md` — **re-verified closed** at `UserPhoneService.java:120`, with a regression guard asserting the flush in `CreatorProfileServiceTest:243-249` and `CreatorOnboardingServiceTest:162-168`.

Race tests executed green: `AuthServiceTest.testBrandRegisterPhoneRaceLoserGetsFriendly409NotRaw500` (`:291-310`), `CreatorProfileServiceTest.testPatchPhoneRaceReturns409` (`:366-404`).

**Honest limit, disclosed by the team itself:** those race tests inject the `DataIntegrityViolationException` through a Mockito stub. They prove the *translation* (409 not 500) and the *flush* (D1 stays closed); they do **not** prove MySQL's `UNIQUE(phone_number)` actually fires, because there is no Testcontainers/`@DataJpaTest` layer in this project. The test author states this in full at `CreatorProfileServiceTest.java:32-47` rather than letting it read as more than it is. The constraint is declared in the migration and mapped on the entity, so I accept it — but nobody has watched the database refuse the row.

**Verdict: POSITIVE**

---

## Q6 — Duplicate rejection: status, code, inline mapping, enumeration

**Status and code (good):**
- `409 CONFLICT`, code `PHONE_ALREADY_EXISTS`, message "An account with this phone number already exists" — `UserPhoneService.java:112-116` and `:121-126`, `AuthService.java:139-143` and `:193-198`.
- Invalid format: `400 BAD_REQUEST`, code `INVALID_PHONE` — `UserPhoneService.java:58-63`.
- Wire shape `{ code, message }` via `GlobalExceptionHandler.java:42-46` → `ApiErrorBody.of(...)` (`common/ApiErrorBody.java:33-35`). The code **is** machine-readable on the wire, and the client's `ApiError` exposes it (`src/lib/api.ts:274-289`, `public code: string`).

**Inline mapping — 2 of 3 surfaces, and the missing one is the "required" surface:**
- Creator Settings: `src/pages/creator-settings.tsx:196-199` → inline `phoneError` next to the field. Good.
- Creator Onboarding: `src/pages/creator-onboarding.tsx:212-218` → inline `phoneError`, and returns without advancing the step. Good.
- **Brand Onboarding: NOT mapped.** `src/pages/brand-onboarding.tsx:101-102` catches everything from `brandRegister` into `setError(err.message)` — a page-level banner. There is no `phoneError` state and no `409` branch anywhere in that file. Three consequences, all real:
  1. The banner renders on **step 2** (company details) while the mobile input lives on **step 1** (`onboarding-steps.tsx:700-718`), so the message is nowhere near the field it is about.
  2. It is not distinguishable from `EMAIL_ALREADY_EXISTS`, which is the *other* 409 the same call throws (`AuthService.java:123`, `:199-200`) — the user is told a raw server sentence with no field attribution.
  3. Even if the user navigates back and fixes the number, the retry is guarded by `hasBrandToken()` (`brand-onboarding.tsx:61`). On the duplicate-phone path no token was issued, so retry does work — but on any *post-register* failure the corrected phone is never re-sent (see Q1). The recovery story here is accidental, not designed.

Also worth flagging for the two working surfaces: both branch on `err.status === 409`, not `err.code === 'PHONE_ALREADY_EXISTS'`. `PATCH /me/creator-profile` also returns 409 for `USERNAME_TAKEN` (`CreatorProfileService.java:93-96`, `:135`). Today the Settings phone dialog only sends `phone`, so it can't collide — but the mapping is one payload change away from labelling a username conflict "This mobile number is already registered". Since `ApiError.code` is right there, this should key on the code.

**Enumeration — yes, it leaks, to an unauthenticated caller.** `POST /auth/brand/register` returns `409 PHONE_ALREADY_EXISTS` with a message that positively confirms an account exists for that exact number, before any authentication. That is a working enumeration oracle over the user base's mobile numbers. Mitigation, not removal: `AuthRateLimitFilter` covers `/auth/**` at 10 requests per window (`security/AuthRateLimitFilter.java:385-387`, `:123-125`). This is the same posture the pre-existing `EMAIL_ALREADY_EXISTS` already had, so PHONE-0904 did not introduce the pattern — but it did widen it to a second identifier, and the honest answer to "does it leak" is **yes**.

**Verdict: NEGATIVE** — the status/code layer is correct, but the brand surface (the one where the field is mandatory) has no field-adjacent mapping at all, the two that do key on status rather than code, and the endpoint is an unauthenticated enumeration oracle.

---

## Q7 — Brand onboarding transactionality and the self-invocation trap

**Transactional: yes. Self-invocation trap: not present. Half-onboarded state: not reachable via phone.**

- `AuthService.brandRegister` is annotated `@Transactional` at `AuthService.java:120`.
- **Self-invocation checked, not assumed.** Grep for `brandRegister` across `influora-api/src/main/java` returns exactly one call site: `web/AuthController.java:67` `authService.brandRegister(body)`. Every other hit is a javadoc/comment reference (`AuthService.java:131,204`, `CreatorProfileService.java:104`, `UserPhoneService.java:14,27,33,96`, `BrandRegisterRequest.java:13`, `PublicConfigController.java:50`). The call crosses the Spring proxy, so `@Transactional` genuinely applies — this is not a self-call that silently runs unmanaged.
- **The premise "if the phone write fails last" does not hold, by construction.** Phone is validated at `:137` and dup-checked at `:138` *before* a userId or workspace is even allocated (`:146-157`), and the value is set on the entity at `:156`, so it lands in the **same single** `saveAndFlush(user)` at `:183` as the email. There is no separate later phone write to fail. A phone rejection therefore throws before any row exists; a raced phone duplicate throws inside the try and, being an unchecked `ApiException`, rolls back the whole `user`/`workspace`/`workspace_member`/`wallet` group.
- **The real seam is elsewhere and it is handled.** Brand onboarding is two HTTP calls, hence two transactions: `api.auth.brandRegister(...)` then `api.onboarding.saveBrandCompany(...)` (`brand-onboarding.tsx:62-99`). If the second fails, the account + workspace + phone exist and the company detail write did not. That is recoverable rather than a dead end because retry is gated on `hasBrandToken()` (`:61`): the second attempt skips register entirely and re-runs only `saveBrandCompany`. The brand's own partial write cannot conflict with itself.

The one residue, already counted under Q1 and not double-counted here: that same `hasBrandToken()` guard means a phone corrected after a successful register is never transmitted.

**Verdict: POSITIVE**

---

## Q8 — Do all four surfaces go through ONE validator? Does Settings match onboarding?

**Backend — one implementation, no divergence.** All three write paths funnel through `UserPhoneService`, which is the only caller of `IndianPhoneUtils`:

| Surface | Entry | Validator call |
|---|---|---|
| Brand signup | `AuthService.java:121` | `:137` `normalizeAndValidate` + `:138` `isTaken` |
| Creator onboarding | `CreatorOnboardingService.java:91` | `:138` `userPhoneService.applyPhone(user, req.phone())` |
| Creator Settings | `CreatorProfileService.java:64` | `:109` `userPhoneService.applyPhone(user, req.phone())` |
| Admin | `AdminCreatorService.java:185`, `:523` | read-only — no validator needed |

`UserPhoneService.normalizeAndValidate` → `IndianPhoneUtils.normalize` + `isValid` (`UserPhoneService.java:57-58`). Constructor-injected in all three (`AuthService.java:108`, `CreatorOnboardingService.java:56`, `CreatorProfileService.java:49`), and all three test classes wire the **real** `UserPhoneService` over a mocked repository rather than mocking the service (`AuthServiceTest.java:107-125`, `CreatorOnboardingServiceTest.java:60-67`, `CreatorProfileServiceTest.java:66-77`) — so the tests actually exercise the shared code.

**Does Settings enforce the same format AND uniqueness as onboarding?** Yes, literally the same method. `CreatorProfileService.java:109` and `CreatorOnboardingService.java:138` are both `userPhoneService.applyPhone(user, req.phone())`. Same `INVALID_PHONE`/400, same `PHONE_ALREADY_EXISTS`/409, same `saveAndFlush` TOCTOU catch. No divergence.

**Frontend — one implementation.** `src/lib/phone.ts` is imported by all three input surfaces: `brand-onboarding.tsx:16`, `creator-onboarding.tsx:27`, `creator-settings.tsx:53`, plus `onboarding-steps.tsx:24`. The three previously hand-rolled `/^[6-9]\d{9}$/` copies are gone.

**The divergence I did find — required vs optional:**
- Client says **Required** for brand: `onboarding-steps.tsx:453` `if (!data.phone) e.phone = 'Required';`
- Server says **Optional** for brand: `BrandRegisterRequest.java:27` has `@Size(max = 20)` and **no `@NotBlank`**, and `AuthService.java:127-129` documents null/blank as "no phone captured at signup, never an error". `AuthServiceTest.testBrandRegisterSucceedsWithoutPhone` (`:235-258`) locks that in.

So any non-browser client — curl, an older app build, a script — can register a brand with no mobile at all, and the server will accept it. This is a deliberate backward-compatibility decision, documented in the DTO javadoc, not an oversight. But it means "brand mobile is REQUIRED" is a client-side convention, not an enforced invariant, and the four surfaces do **not** agree on requiredness even though they agree on format.

**Verdict: PARTIAL** — one shared normalizer/validator on both tiers, and Settings is byte-identical to onboarding; but brand requiredness is enforced only in React and is not a server invariant.

---

## Q9 — Legacy accounts with no mobile

**Nothing breaks. Verified against the migrations and every write path a legacy brand can reach.**

- **Migration:** `V2__core_auth.sql:6` declares `phone_number VARCHAR(20) UNIQUE,` — **nullable, no `NOT NULL`, no `DEFAULT`**. Grep across the entire `db/migration/` directory found no `ALTER TABLE users` touching `phone_number` at any version, so there was **no backfill, no default, and no migration that could fail on existing rows**. Every pre-existing account simply carries `NULL`.
- **MySQL semantics:** a `UNIQUE` column treats `NULL`s as mutually distinct, so an unlimited number of legacy accounts can coexist at `NULL`. This is explicitly relied on and documented at `UserPhoneService.java:98-101`, which is why the clear-path deliberately uses plain `save()` instead of `saveAndFlush()`.
- **Login:** `AuthService.brandLogin` (`:212-262`) never reads or validates `phoneNumber`. A legacy brand logs in unaffected.
- **Re-saving their profile:** `UserDtos.UpdateProfileRequest` (`:26-31`) has no `phone` field at all — `PATCH /me` cannot touch the column. The brand *settings* phone field maps to the **different** column `workspaces.phone` (`WorkspaceMemberDtos.java:50-52`, `:86-87`, migration `V20260718180000__workspace_phone.sql:12`), whose own pattern is `^$|^[+]?[0-9()\-\s]{7,20}$` — explicitly allowing empty.
- **Is a required validator introduced?** No. `BrandRegisterRequest.java:27` has no `@NotBlank`, and `onboarding-steps.tsx:453`'s Required check only ever runs inside the signup wizard, which a legacy brand never re-enters.

**Consequence worth naming (not a break, but a product fact):** the corollary of Q1's missing brand write-back path is that a legacy brand can never *add* a mobile either — there is no brand-facing endpoint that writes `users.phone_number` outside registration. The brand population is now permanently split into "signed up after PHONE-0904, has a number" and "signed up before, will never have one". If Swapnil expects to eventually reach all brands by SMS, that gap needs its own ticket.

**Verdict: POSITIVE**

---

## Q10 — Admin read: endpoint, guard, and every wider serializer

**Endpoint and guard:**
- `GET /admin/creators` (`web/AdminCreatorController.java:49` class mapping, `:58-59` `list`) → `PagedCreatorsDto` → `CreatorSummaryDto.phone` (`web/dto/admin/AdminCreatorDtos.java:24-28`).
- `GET /admin/creators/{id}` (`:83-84` `getById`) → `CreatorDetailDto.phone` (`AdminCreatorDtos.java:44-45`).
- Source: `AdminCreatorService.java:185` (batch-loaded for the list) and `:523` (detail).
- **Guard:** filter-level, not just service-level — `config/SecurityConfig.java:207-208` `.requestMatchers("/admin/**").hasRole("ADMIN")`. `ROLE_ADMIN` comes from `AuthPrincipal#getAuthorities()` as `"ROLE_" + userType.name()`, so a BRAND or CREATOR JWT is rejected at the filter before any controller runs (`SecurityConfig.java:198-206`). No separate PII tier inside admin — documented as intentional at `AdminCreatorDtos.java:24-26`: any admin who can see the row's email sees its phone.

**Every serializer that could touch it — grepped, not asserted.** `grep -rn "getPhoneNumber()\|phoneNumber" influora-api/src/main/java` returns **6 hits, all accounted for**:

| Hit | Nature |
|---|---|
| `UserRepository.java:71` | `existsByPhoneNumber` — boolean only, no projection |
| `AdminCreatorService.java:185` | admin list (guarded above) |
| `AdminCreatorService.java:523` | admin detail (guarded above) |
| `CreatorProfileService.java:230` | `toSelfResponse` — the creator reading **their own** number via `GET/PATCH /me/creator-profile` |
| `UserPhoneService.java:111` | the self-owned-number dup exemption |
| `BrandContextAssembler.java:46` | a **comment** listing `User.phoneNumber` as never-included |

Specifically **not** present anywhere in the grep: `PublicCreatorService`, `PublicCreatorController`, any discovery/search DTO, any brand-facing creator card, `ExternalCreatorService`, `AdminCreatorConnectionService`'s public surfaces. There is also **no repository projection** selecting `phone_number` — `UserRepository.java:71` is the only reference in the whole `repository/` package.

**AI/Meera payload — checked on both sides of the boundary:**
- Java: `BrandContextAssembler.java:30-50` is a deny-by-default explicit allow-list (Guardrail 3, 03-SECURITY-SPEC §G3). `User.email`, `User.phoneNumber`, `User.passwordHash` are named in the NEVER-included block, and the assembled map (`:60+`) puts only workspace name/industry/websiteUrl and brand-profile fields.
- Python: `influora-ai/app/prompt/assembler.py:70-100` — `"phone"` is a member of `_FORBIDDEN_BRAND_FIELDS`, alongside `"email"`, `"creator_pii"`, and the Phase-A creator-side info-barrier fields. So even a contaminated payload is stripped at the prompt layer.
- `influora-ai/app/security/redaction.py:27-34` additionally scrubs 10-digit phone patterns out of logs.

**Answer to "could a brand read a creator's number before a deal exists": no.** There is no code path from a brand principal to `users.phone_number`.

**Verdict: POSITIVE**

---

## Tally

| Q | Subject | Verdict |
|---|---|---|
| 1 | Brand write path + read-back | **PARTIAL** |
| 2 | Creator removal persists as cleared | **POSITIVE** |
| 3 | Normalizer input matrix + test file | **PARTIAL** |
| 4 | Server-side normalize before uniqueness | **POSITIVE** |
| 5 | One-number-one-account enforcement | **POSITIVE** |
| 6 | Duplicate rejection: code, inline mapping, enumeration | **NEGATIVE** |
| 7 | Transactionality + self-invocation | **POSITIVE** |
| 8 | One shared validator across four surfaces | **PARTIAL** |
| 9 | Legacy accounts with no mobile | **POSITIVE** |
| 10 | Admin read + wider serializer leakage | **POSITIVE** |

**6 POSITIVE · 3 PARTIAL · 1 NEGATIVE**

## Overall: NOT YET

The core engine — `IndianPhoneUtils` + `UserPhoneService` — is genuinely good work. One normalizer, one validator, one write path, a real DB constraint, correct `saveAndFlush` ordering, correct null-vs-blank semantics, and no PII leakage into any brand-facing or LLM-facing serializer. **D1 and D2 from `wiki/decisions/phone-0904-cto-review.md` are both re-verified closed against the code, not against the report** — D1 at `UserPhoneService.java:120` with regression guards at `CreatorProfileServiceTest:243` and `CreatorOnboardingServiceTest:162`; D2 at `CreatorOnboardingService.java:130` with `testSaveProfileBlankPhoneDoesNotTouchUser` at `CreatorOnboardingServiceTest:139-149`. 98 tests executed green in this review.

What blocks sign-off is the **brand surface specifically**, which is the surface where the field is supposedly mandatory:

1. **Q6 (NEGATIVE, blocking).** A duplicate-mobile 409 at brand signup produces a generic page-level banner on the wrong wizard step, indistinguishable from a duplicate-email 409, with no field attribution. Fix: add a `phoneError` state to `src/pages/brand-onboarding.tsx`, branch on `err.code === 'PHONE_ALREADY_EXISTS'` (not `err.status`), and surface it on step 1 next to the input at `onboarding-steps.tsx:718`. **Owner: Ananya.** While there: change `creator-settings.tsx:198` and `creator-onboarding.tsx:216` from `err.status === 409` to `err.code === 'PHONE_ALREADY_EXISTS'`, since `PATCH /me/creator-profile` also 409s for `USERNAME_TAKEN`.

2. **Q1 (PARTIAL, blocking for a "required" field).** A brand's mobile is write-once, read-never: no serializer returns it and no endpoint updates it after registration. A brand who mistypes their number can never see or correct it, and a legacy brand can never add one. Fix: add `phone` to `UserDtos.UserProfileDto` and a phone branch to the `PATCH /me` path (routing through `UserPhoneService.applyPhone`, so uniqueness and format come for free). **Owner: Vikram** (backend) with **Ananya** for the brand settings UI. This is a new small ticket, not a PHONE-0904 patch.

3. **Q3 (PARTIAL, non-blocking).** Behaviour is right on all 10 inputs — I executed both implementations — but there is no `IndianPhoneUtilsTest.java` and 5 of the 10 inputs are locked in by no test anywhere. Fix: add `influora-api/src/test/java/com/influora/common/IndianPhoneUtilsTest.java` covering the Q3 table, and extend `src/lib/phone.test.ts` with the same rows so the parity claim is enforced rather than asserted. **Owner: Vikram** (Java) / **Ananya** (TS).

4. **Q8 (PARTIAL, non-blocking, needs a ruling not a fix).** Brand mobile is Required in React and Optional on the server. This is deliberate backward compatibility and documented, but it means the invariant does not hold for non-browser clients. **Decision for Swapnil:** if the mobile is genuinely required for brands, `BrandRegisterRequest.phone` needs `@NotBlank` and we accept that older app builds break; if not, the UI copy and the ticket should stop calling it required.

5. **Q6 enumeration (PARTIAL, needs a ruling not a fix).** `POST /auth/brand/register` confirms to an unauthenticated caller whether a given mobile is registered, rate-limited to 10/window (`AuthRateLimitFilter.java:385`, `:123`). PHONE-0904 did not invent this posture — `EMAIL_ALREADY_EXISTS` has behaved this way since launch — but it now applies to a second identifier. **Decision for Swapnil / Kabir:** accept as a documented risk, or move signup-time duplicate detection behind the OTP gate. I am not treating this as a PHONE-0904 regression, but it should be on the record rather than discovered later.

**Re-review trigger:** items 1 and 2 land → I re-verify → sign-off. Items 3–5 can follow without blocking.

---
---

# RE-RUN — 2026-09-04 (second pass, zero-context re-verification)

**Answered by:** Priya (CTO)
**Everything above this line is the ORIGINAL first-pass answer and is kept unchanged for the record.**
**Standard applied:** identical to the first pass. Every claim below was read off the current code or produced by executing it. Swapnil wanting ten POSITIVEs is a goal for the code, not a constraint on this answer.

## Evidence actually executed for THIS re-run

| What | Command | Result |
|---|---|---|
| Phone-touching Java tests | `mvn -o test -Dtest='IndianPhoneUtilsTest,UserServiceTest,AuthServiceTest,CreatorProfileServiceTest,CreatorOnboardingServiceTest'` | **106 tests, 0 failures, 0 errors** — BUILD SUCCESS |
| ↳ `IndianPhoneUtilsTest` (new) | — | **29/29** |
| ↳ `UserServiceTest` (new) | — | **10/10** |
| Frontend phone/settings tests | `npx vitest run` on the 5 phone surfaces | **47 passed / 0 failed** across 5 files |
| Brand onboarding 409 test (existence challenged, then run) | `npx vitest run src/pages/brand-onboarding-duplicate-409.test.tsx` | **4/4 passed** — file confirmed to exist |
| Typecheck | `npx tsc --noEmit` | **exit 0**, no output |

Two claims in the hand-off were checked rather than taken: `brand-onboarding-duplicate-409.test.tsx` was searched for repo-wide before being credited (it exists at `src/pages/brand-onboarding-duplicate-409.test.tsx`), and `IndianPhoneUtilsTest`'s "29 cases" was confirmed by execution, not by counting `@Test` annotations.

---

## Q1 — Complete brand write path + proof of read-back after logout/login → **POSITIVE** (was PARTIAL)

The write-once/read-never gap is genuinely closed on all three tiers.

- **Read (server):** `web/dto/user/UserDtos.java:33` — `UserProfileDto.phone`, populated at `service/UserService.java:105` (`user.getPhoneNumber()`), served by `web/UserController.java:27-30` `GET /users/me`.
- **Edit (server):** `UserDtos.java:52` `UpdateProfileRequest.phone` → `UserService.java:76-85`. `PATCH /users/me`, BRAND branch, routed through `UserPhoneService.applyPhone` so format/uniqueness/TOCTOU come from the one shared implementation rather than a second copy.
- **Read (client):** `src/lib/api.ts:3503` `UserProfileMeResponse`, `:3529` `UsersMeUpdatePayload`, `:3567` `api.users.getMe`, `:3573` `api.users.updateMe`.
- **UI:** `src/pages/brand-settings.tsx:888-916` — "Mobile Number" card, Security tab; `:906` renders `+91 {savedPhone}` or `No mobile number on file`; `:252-275` loads it on mount with a retry affordance on failure; edit dialog at `:1102-1157`.
- **Un-clearable guard:** `brand-settings.tsx:1145` — Save is `disabled={isSavingPhone || !normalizePhone(phoneDraft)}`, with `handleSavePhone`'s own check at `:294-301` as defense in depth. Server backstops it with `PHONE_REQUIRED` at `UserService.java:77-80`.

**Read-back is proven, in both halves:** `UserServiceTest.testGetProfileReturnsBrandPhone` (`:73-74`) on the server, and `brand-settings-account-phone.test.tsx:133` "renders the saved number from GET /users/me" on the client. The remaining caveat is the one already disclosed under Q5 and unchanged: this project has no Testcontainers layer, so no single test spans register → logout → login → read against a real database. Applying that project-wide limitation as a downgrade here only would be inconsistent, so I do not.

Round-1 note now obsolete: the `hasBrandToken()` guard no longer strands a mistyped number, because Settings is a second write path.

## Q2 — Creator removal persists as cleared → **POSITIVE** (unchanged)

Re-verified undisturbed by the new endpoint. `UserPhoneService.applyPhone:104-107` still clears on blank via plain `save()`, deliberately (writing NULL into a UNIQUE column has no race to catch — `:98-101`). `CreatorProfileService.java:108` still gates on `req.phone() != null`, so absent is not the same as cleared.

Critically, **the new `/users/me` path cannot disturb this**: `UserService.java:76` applies phone only when `user.getUserType() == UserType.BRAND`, so creator clear-semantics are untouched by construction. `CreatorProfileServiceTest` 13/13 green in this run.

## Q3 — Per-input accept/reject table, backed by a real test file → **POSITIVE** (was PARTIAL)

The round-1 blocker was blunt: *"There is no `IndianPhoneUtilsTest.java` at all — the Java normalizer has zero direct unit tests."* That is decisively fixed.

`influora-api/src/test/java/com/influora/common/IndianPhoneUtilsTest.java` (186 lines, **29/29 executed green**):
- `signoffProbeInputs()` at `:70-95` pins **all 10 rows of the Q3 probe table verbatim**, each asserting *both* the normalized output and `isValid()` on it — including the three I called out as untested: Devanagari input to `""`/false (`:76-79`), `9876543210x123` to `9876543210123`/false (`:80-84`), `987654321012` unchanged/false (`:86-89`), plus the hyphen form `91-9876543210` (`:75`) and the trailing-space 11-digit form (`:80-81`).
- Beyond what I asked for: null/empty/whitespace (`:38-53`), Devanagari again as its own named `@Test` with the ASCII-char-class reasoning (`:113-122`), length boundaries 9/11/12/13+ (`:129-160`), and **every leading digit 0-9** via two `@ValueSource` runs (`:169-184`).

**Named residual, non-blocking:** `src/lib/phone.test.ts` is **unchanged** (still 97 lines / 28 tests) and still does not pin 5 of the 10 rows as single `normalizePhone()` calls — Devanagari, `91-9876543210`, `9876543210x123`, `987654321012`, `098765 43210 `. Two of those are compositionally covered (`filterPhoneInput` strips hyphens and letters at `:94-96`, and the resulting `+919876543210` form is pinned at `:19`), and the remaining gaps are all REJECT rows where the server is the enforcing tier and would refuse anyway. So the cross-tier parity claim is still asserted by my probe rather than gated — worth closing, not worth blocking. **Owner: Ananya**, add the 5 rows to `src/lib/phone.test.ts`.

I am upgrading because the question asked for the table to be backed by a real test file, and on the tier that actually enforces it now comprehensively is.

## Q4 — Server-side normalization before the uniqueness check → **POSITIVE** (unchanged)

Still true on every write path, and the new one inherits it rather than reimplementing it:
- `AuthService.java:147` `normalizeAndValidate` then `:148` `isTaken`.
- `UserPhoneService.java:110` normalize then `:111` `isTaken` — used by creator onboarding, creator Settings, **and now** `UserService.java:85`.
- `UserRepository.java:71` `existsByPhoneNumber` still matches the canonical 10-digit form only.

`UserServiceTest.testUpdateProfilePersistsNormalizedPhoneForBrand` (`:95-96`) extends the existing proof to the new endpoint.

## Q5 — One-number-one-account enforcement → **POSITIVE** (unchanged)

DB `UNIQUE` still the guarantee (`V2__core_auth.sql:6`), entity still agrees (`User.java:24`). **D1 re-verified closed for a third time** at `UserPhoneService.java:120` (`saveAndFlush`, not `save`) and `AuthService.java:198`. The new endpoint gets D1 for free by delegating to `applyPhone`, and `UserServiceTest.testUpdateProfileBrandPhoneRaceReturns409` (`:160-163`) adds a fourth regression guard on it.

Honest limit unchanged and restated: every race test injects `DataIntegrityViolationException` via a Mockito stub. They prove the translation (409 not 500) and the flush ordering. **Nobody has watched MySQL actually refuse the row.**

## Q6 — Duplicate rejection: status, code, inline mapping, enumeration → **PARTIAL** (was NEGATIVE)

Two of the three round-1 reasons are fully fixed. The third is not, and it is part of the question.

**Fixed — inline mapping, now on all four surfaces, all keyed on `code`:**

| Surface | Branch site | Rendered at |
|---|---|---|
| Brand onboarding | `src/pages/brand-onboarding.tsx:113,126,138,145` | `:194` `serverErrors={fieldErrors}` then `onboarding-steps.tsx:446,741` |
| Brand Settings | `src/pages/brand-settings.tsx:314,316,318` | `:1112-1116` `role="alert"` |
| Creator onboarding | `src/pages/creator-onboarding.tsx:221` | `:594-596` |
| Creator Settings | `src/pages/creator-settings.tsx:203` | `:737-739` |

`PHONE_ALREADY_EXISTS`, `EMAIL_ALREADY_EXISTS`, `PHONE_REQUIRED` and `INVALID_PHONE` each get a distinct message, and brand onboarding routes the user **back to step 1** where the field lives (`brand-onboarding.tsx:120-123`). It also resets `emailOtpSent/emailOtpVerified` (`:121`) so step 1 re-renders as an editable form rather than an OTP screen with no phone input — a real dead-end that a naive `setCurrentStep(1)` would have created. That is careful work.

**Fixed — `err.status` is gone.** Every phone surface branches on `err.code`. `creator-settings-phone-409.test.tsx:135` specifically asserts a `USERNAME_TAKEN` 409 does *not* render the phone message — the exact false-labelling I flagged. `fieldErrors` is verified **rendered**, not merely set (`onboarding-steps.tsx:446` then `:741`), so this is not an F-0341-class dead control. 4/4 + 2/2 + 7/7 new tests green.

**NOT fixed — the enumeration oracle, and it is one of the four things this question asks.** `POST /auth/brand/register` still confirms to a fully unauthenticated caller whether a given mobile is registered. Re-verified ordering in this run: the duplicate check throws at `AuthService.java:148-157`, while the email-OTP gate does not run until `:174-176`. So the oracle sits **before** any ownership proof. Mitigation is unchanged and partial: `AuthRateLimitFilter` at 10 requests/window.

**And Q8's own fix made this strictly worse, which nobody has flagged:** before this ticket a brand might have no phone on file, so a probe was inconclusive. Now that brand mobile is REQUIRED, every brand registered after this change has a number in `users.phone_number` — so the oracle's coverage over the new-brand population went from partial to total. The posture is inherited from `EMAIL_ALREADY_EXISTS` and was not invented here, but its blast radius was widened by this ticket.

I will not grade this POSITIVE. The honest answer to "does it enumerate" is still **yes**, and no ruling exists. **This needs a decision from Swapnil/Kabir, not code** — accept as documented risk, or move duplicate detection behind the OTP gate.

## Q7 — Brand onboarding transactionality and the self-invocation trap → **POSITIVE** (re-checked hard, as instructed)

The Required change makes this **stronger**, not weaker, and I verified rather than assumed it.

- **A phone failure cannot produce a partial write, by construction.** `PHONE_REQUIRED` throws at `AuthService.java:135-137`; `INVALID_PHONE` at `:147`; `PHONE_ALREADY_EXISTS` at `:148-157`. All three fire **before** `Ulids.newUlid()` allocates a userId at `:160` and before any `User`/`Workspace` object exists. There is no row, no workspace, no wallet, nothing to half-write. The new abort path is earlier than the round-1 one, not later.
- **Self-invocation re-grepped, not assumed.** `brandRegister` has exactly one call site in `main/java`: `web/AuthController.java:67`. Every other hit is javadoc. The proxy boundary is crossed, so `@Transactional` (`:120`) genuinely applies.
- **The new `/users/me` path passes the same test.** `UserService.updateProfile` is `@Transactional` (`:35`) and is called only from `web/UserController.java:36` — cross-bean, proxy applies. Its own partial-write question: the field mutations at `:45-59` are applied to a *managed* entity, and `applyPhone` either (a) throws `PHONE_ALREADY_EXISTS` at `UserPhoneService.java:112` **before** any flush, or (b) `saveAndFlush`es the whole row including those mutations at `:120`. Either way there is no state where the name saved and the phone did not. The `else` at `UserService.java:87` handles the phone-absent case with a plain `save()`, which is correct — no unique constraint is in play on those fields.

## Q8 — Do all four surfaces go through ONE validator? → **POSITIVE** (was PARTIAL)

The round-1 divergence — Required in React, Optional on the server — is closed **server-side**, which is the side that matters.

- `AuthService.java:135-137` throws `PHONE_REQUIRED`/400 on null-or-blank, **before** `normalizeAndValidate` at `:147`, so "missing" and "malformed" never collapse into one code.
- The deliberate omission of `@NotBlank` on `BrandRegisterRequest.java:37` is **correct engineering, not a shortcut**, and the reasoning at `:8-27` is right: Bean Validation would emit a generic `MethodArgumentNotValidException` body with no machine-readable code, which is precisely what Q6 needed to branch on. I endorse this choice.
- Client and server now agree: `onboarding-steps.tsx:470` `Required` matches the server invariant instead of substituting for it.

All write paths still funnel through the single `UserPhoneService` then `IndianPhoneUtils` implementation — now **four** call sites (`AuthService:147`, `CreatorOnboardingService:138`, `CreatorProfileService:109`, `UserService:85`), with zero copies. Frontend still one `src/lib/phone.ts`. Settings remains byte-identical to onboarding.

Non-blocking observation carried into the D3 ruling below: the *fifth* surface (`PATCH /users/me` for a non-BRAND caller) does not diverge in validation — it does not write at all. That is a separate defect, ruled on below; it does not make this question's answer false.

## Q9 — Legacy accounts with no mobile → **POSITIVE** (strengthened)

Nothing retroactively breaks, and I specifically probed whether the Required change reaches backwards. It does not:

- Column still nullable, no backfill, no `ALTER` (`V2__core_auth.sql:6`); MySQL still treats NULLs as mutually distinct (`UserPhoneService.java:98-101`).
- `PHONE_REQUIRED` exists at exactly two sites — `AuthService.java:136` (registration only) and `UserService.java:78` (only when the caller **explicitly sends** a blank phone). A legacy brand patching their name sends `phone: null`, hits the `else` at `UserService.java:87`, and saves normally. Pinned by `UserServiceTest.testUpdateProfileLegacyNullPhoneUntouchedSavesFine` (`:196-197`).
- `GET /users/me` on a NULL row returns `phone: null` cleanly — `UserServiceTest.testGetProfileLegacyNullPhoneReadsFine` (`:83-84`).
- `brandLogin` still never reads `phoneNumber`.

**The round-1 "permanently split population" concern is now RESOLVED.** `brand-settings.tsx:906` renders `No mobile number on file` with a live Edit button for exactly this case, so a legacy brand can now *add* a number. The gap I said "needs its own ticket" was closed by this work.

## Q10 — Admin read + every wider serializer → **POSITIVE** (re-grepped from scratch, as instructed)

A new read path exists, so I re-ran the full grep rather than trusting the round-1 result. `grep -rn "getPhoneNumber()\|phoneNumber" influora-api/src/main/java` returns **10 hits, all accounted for**:

| Hit | Nature |
|---|---|
| `User.java:25,154-155,167-168,307` | entity field/accessors/soft-delete null-out |
| `UserRepository.java:71` | `existsByPhoneNumber` — boolean, no projection |
| `AdminCreatorService.java:185,523` | admin list/detail, guarded by `SecurityConfig.java:207-208` `hasRole("ADMIN")` |
| `CreatorProfileService.java:230` | creator reading their **own** number |
| `UserPhoneService.java:111` | self-owned-number dup exemption |
| **`UserService.java:105`** | **NEW** — analysed below |
| `BrandContextAssembler.java:46` | a comment denylisting it |

**The new hit is self-only, and I verified the guard rather than reading the javadoc's promise.** `UserProfileDto` is constructed in exactly one place (`UserService.java:94`) and consumed in exactly one place — `UserController.java:29` and `:36`, both passing `principal.getUserId()`. There is no path that accepts a caller-supplied user id. `/users/**` appears in no `permitAll` matcher in `SecurityConfig.java:85-197`, so it falls to `.anyRequest().authenticated()` at `:230`. A caller can therefore only ever read their own row. The client helper is additionally `role: 'brand'` scoped (`api.ts:3569`).

Still absent from the grep, as before: `PublicCreatorService`, any discovery/search DTO, any brand-facing creator card, `ExternalCreatorService`. AI boundary intact on both sides — `BrandContextAssembler.java:30-50` allow-list, `influora-ai/app/prompt/assembler.py` `_FORBIDDEN_BRAND_FIELDS` containing `"phone"`, and log redaction in `app/security/redaction.py`.

**No new PII leak. A brand still cannot read a creator's number.**

---

## CTO rulings

### D3 — The silent drop on `PATCH /users/me` (non-BRAND): **REJECT with a 400. I am overruling Kavya's "acceptable as documented."**

`UserService.java:76` — a CREATOR or ADMIN sending `phone` gets `200 OK`, and the response body (`toDto`, `:105`) echoes their **unchanged** phone. That is not a silent no-op; it is a success response whose body contradicts the request. A client has no way to detect the write was discarded.

Kavya's reasoning for leaving it — creators have their own path with different blank-semantics, and a second write path would be exactly what `UserPhoneService` was built to prevent — is **correct, and it justifies not WRITING the value. It does not justify not ERRORING.** Those are two separate decisions and they have been collapsed into one. Refusing to write and being loud about refusing are fully compatible.

Three reasons this changes:
1. **"No UI sends it today" was true of brand onboarding's discarded phone too, right up until it wasn't.** That silent discard is the entire origin of this ticket. Shipping a new silent phone discard inside the fix for a silent phone discard is not a trade I will sign.
2. **The asymmetry is total.** No client sends it, so a 400 cannot regress anything; leaving it guarantees the bug recurs the day someone wires a creator field to this endpoint.
3. **It is now pinned by a green test.** `UserServiceTest.testUpdateProfileCreatorPhoneIgnored` (`:213-217`) locks the wrong behaviour in, so the next engineer who notices will find a passing test telling them it is intended. That is the "gates greening their own wrong fix" pattern this team has been bitten by before.

**Action:** `UserService.java:76` — when `req.phone() != null` and the caller is not BRAND, throw `ApiException("PHONE_NOT_EDITABLE_HERE", "Update your phone number from your creator profile", HttpStatus.BAD_REQUEST)`. Invert `testUpdateProfileCreatorPhoneIgnored` to assert the throw. **Owner: Vikram.** ~6 lines. **Blocking.**

### Two phone fields on `brand-settings.tsx`: **rename — Kavya's suggestion is right, adopt it. Non-blocking.**

Labelling is *not* currently sufficient. On one settings page there are two `type="tel"` inputs with the **same `+91 98765 43210` placeholder** and directly contradictory instructions: General tab says *"Leave blank to clear"* (`:653`) while Security says *"this field can't be saved empty"* (`:1140`). The General-tab label is the bare word **"Phone"** (`:642`) — maximally ambiguous next to a field called "Mobile Number".

In its favour: they are in different tabs, the DOM ids are already distinct (`phone` vs `account-mobile`, so no duplicate-id or label-association bug), and the helper texts do differ.

**Action:** rename the General-tab field to **"Workspace Phone"**, change its id/htmlFor to `workspace-phone`, and make its helper name the distinction ("Business contact number for billing — not your personal mobile"). **Owner: Ananya.** Cosmetic, no data risk, ship in the same PR if convenient. **Non-blocking.**

### PLAINTEXT phone ruling: **unviolated. ADR: still missing, still mine.**

- `User.java:24-25` carries a plain `@Column(name = "phone_number", unique = true)` with **no `@Convert`, no `AttributeConverter`, no cipher** anywhere in the entity. Migration is `VARCHAR(20)` (`V2__core_auth.sql:6`). Storage is plaintext, exactly as the standing ruling permits — nobody has quietly introduced encryption, and nobody has widened exposure to compensate (Q10).
- The new `/users/me` read does not change the posture: it exposes the number only to the account that owns it.
- **`wiki/decisions/2026-07-10-pii-email-phone-encryption.md` does not exist.** Confirmed by listing `wiki/decisions/` — 17 entries, no PII/encryption ADR. This is **my** debt, not the team's, and it is now overdue by two months and has been carried across two sign-off passes. The plaintext decision is currently defensible only as oral tradition. I own writing it.

---

## RE-RUN tally

| Q | Subject | Round 1 | **Re-run** |
|---|---|---|---|
| 1 | Brand write path + read-back | PARTIAL | **POSITIVE** |
| 2 | Creator removal persists as cleared | POSITIVE | **POSITIVE** |
| 3 | Normalizer input matrix + test file | PARTIAL | **POSITIVE** |
| 4 | Server-side normalize before uniqueness | POSITIVE | **POSITIVE** |
| 5 | One-number-one-account enforcement | POSITIVE | **POSITIVE** |
| 6 | Duplicate rejection: code, mapping, enumeration | NEGATIVE | **PARTIAL** |
| 7 | Transactionality + self-invocation | POSITIVE | **POSITIVE** |
| 8 | One shared validator across surfaces | PARTIAL | **POSITIVE** |
| 9 | Legacy accounts with no mobile | POSITIVE | **POSITIVE** |
| 10 | Admin read + wider serializer leakage | POSITIVE | **POSITIVE** |

**9 POSITIVE · 1 PARTIAL · 0 NEGATIVE** (was 6 / 3 / 1)

## Overall: NOT YET

This is a narrow NOT YET, and it is not a criticism of the work. Every defect I raised in round 1 was fixed properly rather than papered over: the brand read/edit path is real end-to-end, `IndianPhoneUtilsTest` covers more than I asked for, all four surfaces branch on `code` with the messages actually rendered, and the OTP-screen dead-end in the step-1 recovery route was something the team caught on their own. 153 tests executed green in this pass and `tsc` is clean. Q8's server-side `PHONE_REQUIRED` without `@NotBlank` is the right call for the right reason.

I am withholding sign-off on exactly two items:

1. **D3 — the silent drop (blocking, code).** A new silent phone discard, pinned by a green test, inside the ticket that exists because of a silent phone discard. ~6 lines. **Owner: Vikram.**
2. **Q6 enumeration (blocking on a ruling, not on code).** `POST /auth/brand/register` remains an unauthenticated oracle over registered mobile numbers, and making the field Required widened its coverage to the whole new-brand population. Not a PHONE-0904 regression — `EMAIL_ALREADY_EXISTS` has behaved this way since launch — but it is one of the four things Q6 asks and it has no decision on it. **Owner: Swapnil / Kabir.** Accept as documented risk, or move duplicate detection behind the OTP gate.

Non-blocking, should not hold the merge: the 5 unpinned TS parity rows (**Ananya**), the "Workspace Phone" rename (**Ananya**), and my own overdue PII-encryption ADR (**Priya**).

**Re-review trigger:** D3 lands and the enumeration ruling is recorded → I sign off. No further re-verification of Q1–Q5 or Q7–Q10 is needed; they are settled.
