# Meera Final Verification — Creator AI Co-pilot Tier-1

Date: 2026-07-21
Branch: feat/portfolio-view-tracking
Verifier: Meera (DB/DevOps + local run verifier)

## Fix #5 (migration hygiene) — DONE

`influora-api/src/main/resources/db/migration/V20260721140000__creator_nudge_log.sql:1-2` carried
a "DRAFT — reviewable artifact only, NOT applied" banner while sitting in the live Flyway path.
Struck the two banner lines, kept the rest of the R2-supersession rationale comment intact (now
starts the same way the sibling V20260721150000 does — no misleading banner). Column list
re-confirmed against R2's canonical list: `id, creator_profile_id, trend_id, match_score, theme,
headline, content_idea, message_source, prompt_version, shown_at, dismissed_at, acted_at,
created_at` — matches.

**Not fixed (flagged only, out of scope for fix #5):** `V20260721120000__creator_profile_theme_tags.sql:1-2`
and `V20260721130000__creator_captions.sql:1-2` carry the identical stale "DRAFT — reviewable
artifact only, NOT applied" banner while also sitting in the live Flyway path. Same defect, same
fix needed — left alone since only V140000 was in scope this pass. Recommend a follow-up to strike
both.

## 1. Java (influora-api)

Environment note: no `mvn`/`mvnw` on PATH; used the cached Maven 3.9.6 distribution at
`C:\Users\Sage world\.m2\wrapper\dists\apache-maven-3.9.6-bin\3311e1d4\apache-maven-3.9.6\bin\mvn.cmd`
with `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot`, `-o` (offline).

### `mvn -o -q compile`
**PASS** — exit 0, no output (quiet mode suppresses success noise), 0 errors.

### `mvn -o test`
**FAIL — BUILD FAILURE.** `Tests run: 1402, Failures: 2, Errors: 4, Skipped: 0`.

#### NEW, build-blocking — routes back to whoever owns V20260721140000 (Vikram/Priya call, not mine to fix)

`DatabaseConstraintIntegrationTest` (real `@SpringBootTest` + Testcontainers MySQL 8.0.40, not the
persistent docker-compose DB — no data at risk) — **3/3 errors**, all one root cause:

```
Migration V20260721140000__creator_nudge_log.sql failed
SQL State  : HY000
Error Code : 3780
Message    : Referencing column 'trend_id' and referenced column 'id' in foreign key constraint
             'fk_creator_nudge_log_trend' are incompatible.
Location   : db/migration/V20260721140000__creator_nudge_log.sql, Line 17
```

**Root cause:** `creator_nudge_log` (V20260721140000) is created with explicit
`COLLATE=utf8mb4_unicode_ci`. Its FK target `trends.id` (`V51__trendspark.sql:4-19`) was created
with only `DEFAULT CHARSET=utf8mb4` and **no explicit COLLATE**, so MySQL 8 assigned it the
engine's own default collation, `utf8mb4_0900_ai_ci` — a genuine collation mismatch on the FK.
The sibling FK `fk_creator_nudge_log_profile` → `creator_profiles.id` (`V6__creators_collaborations.sql`,
no charset/collate clause at all) succeeds only because that table inherits the *schema-level*
default (`utf8mb4_unicode_ci`), which happens to match — pure luck, not a designed-consistent
convention.

**This means the migration as written cannot be applied to a real database at all** — this is not
a test artifact, it's the actual Flyway engine refusing the actual SQL. Any real deploy attempt
would hit the identical `SQLException`. This is a genuine, NEW P0 in the migration under review,
not a pre-existing/unrelated failure.

Fix options (for Priya/Vikram to choose, not applied here — out of scope for "migration hygiene
fix #5" and touching V51 is a bigger call):
- Add `COLLATE=utf8mb4_unicode_ci` explicitly to the `trend_id` column (or whole table) in
  V20260721140000 to match `trends`'s actual runtime collation (`utf8mb4_0900_ai_ci`), **or**
- Add a follow-up migration that ALTERs `trends` to `utf8mb4_unicode_ci` for project-wide
  consistency (bigger blast radius — touches an already-shipped table).

#### Pre-existing, unrelated to this batch (verified via `git log`/`git status` — none of these files were touched by today's 4-track build)

- `MeeraVoiceAiClientTest.testSpeakSendsBearerTokenAndBody` — expects
  `"http://localhost:8000/voice/speak"`, got `"/voice/speak"`. File last touched in `0b725dd`
  (2026-07-20, hands-free voice mode) — predates this session.
- `NotificationEventContractTest.everyEventHasAPublisherOrADocumentedReason` — stale allowlist
  entry: `["SubscriptionPaymentFailedEvent"]` now has a real publisher (per the recent money-path
  commits `ad89937`/`eb2f0cc`) but the `KNOWN_MISSING_PUBLISHERS` entry (test file untouched since
  `8900bbc`, 2026-07-17) was never removed.
- `WalletControllerTest.testTransactionsDelegatesToService` — NPE (`Workspace.getId()` on null).
  The test never stubs `principal.getUserType()`, so the controller's userType branch (added in
  `b63ccf3`, 2026-07-20) falls through to the brand/workspace path and calls the unmocked
  `brandContext.requireBrandWorkspace(principal)`. Both files predate this session.

All three are real bugs worth separate tickets, but none were introduced or touched by the Creator
AI Co-pilot Tier-1 build being verified here.

#### Boot-blocker check — PASS
`ConfigurationPropertiesRegistrationTest`: **1/1 pass.** Confirms the two new
`@ConfigurationProperties` classes (`CreatorCopilotProperties`, `CreatorSuggestionAiProperties`)
are properly registered — no repeat of the earlier `BrandSafetyAiProperties`-class boot failure.

### Migration sequencing
No `flyway-maven-plugin` is configured in `influora-api/pom.xml`, so `mvn flyway:info` isn't
available offline. Verified manually instead:
- The 4 new versions (`20260721120000` / `130000` / `140000` / `150000`) are strictly sequential,
  no collisions with each other or with any other `V*` file in the migration directory (repo-wide
  duplicate-prefix scan: none found).
- Whether they actually **apply** was answered definitively (and negatively, for V140000) by the
  real Testcontainers run above — a stronger signal than `flyway:info` would have given anyway.

## 2. Frontend (repo root)

`npx tsc --noEmit`: **PASS** — exit 0, 0 errors.

`npm run build` (vite build + postbuild prerender): **PASS** — exit 0.
- vite: 4753 modules transformed, built in 1m10s. One pre-existing cosmetic esbuild warning
  (duplicate `"baseUrl"` key in `tsconfig.json:20,21`) — not a new regression, does not fail the
  build.
- postbuild prerender: **16/16 routes snapshotted successfully.**

## 3. Python (influora-ai)

`pytest -q`: **433 passed, 2 failed.** The 2 failures are exactly the known pre-existing ones in
`tests/routes/test_voice.py::TestTruncateForTts` (`test_truncation_adds_ellipsis`,
`test_max_chars_constant_is_200`) — confirmed **no new breakage** from the creator route or the
extract-first refactor.

## 4. Route/service registration on boot

- Java: see `ConfigurationPropertiesRegistrationTest` above — **PASS**.
- Python: `app/main.py:99-106` registers `creator_suggestion.router` under the same defensive
  try/except pattern already used for `trendspark`/`brand_safety`/`trend_tag` (import failure logs
  loud and degrades gracefully instead of taking down the whole app). Import succeeded — no
  exception logged, and the full pytest run (433/435, only the 2 known pre-existing voice failures)
  confirms no import-time regression.

## VERDICT

**FAIL — one NEW P0 blocker.** `V20260721140000__creator_nudge_log.sql` does not apply against a
real MySQL 8 engine (FK collation mismatch against `trends.id`, error 3780). Compile, frontend
build/typecheck, and Python suite are otherwise clean; 3 Java test failures are confirmed
pre-existing/unrelated to this batch. Do not ship until the FK collation issue is resolved and
`DatabaseConstraintIntegrationTest` passes for real against MySQL.

---

## RE-VERIFY — 2026-07-21 (same day, after FK-collation fix applied to working tree)

Fix under re-test: `V20260721140000__creator_nudge_log.sql:20` now declares `trend_id VARCHAR(26)
COLLATE utf8mb4_0900_ai_ci NOT NULL` (explicit per-column collation matching `trends.id`'s
server-default `utf8mb4_0900_ai_ci`, since V51 left it charset-only). Stale "DRAFT — NOT applied"
banners on V20260721120000/130000 also struck (confirmed by direct read — both files now open with
the same non-banner rationale-comment style as V150000). No commit; working tree only.

### 1. `mvn -o -q compile` — **PASS**, exit 0.

### 2. `DatabaseConstraintIntegrationTest` (real `@SpringBootTest` + Testcontainers MySQL 8.0.40, throwaway container, nothing persistent touched)

**Flyway migration — PASS, FK-collation P0 is CLOSED.** Full log confirms:

```
Migrating schema `influora_it` to version "20260721120000 - creator profile theme tags"
Migrating schema `influora_it` to version "20260721130000 - creator captions"
Migrating schema `influora_it` to version "20260721140000 - creator nudge log"
Migrating schema `influora_it` to version "20260721150000 - meta oauth workspace id nullable"
Successfully applied 92 migrations to schema `influora_it`, now at version v20260721150000 (execution time 00:58.009s)
```

All 4 creator migrations applied cleanly, in order, including `fk_creator_nudge_log_trend` — **no
SQL Error 3780, no collation mismatch.** This is the exact root cause fixed and it is confirmed
fixed against a real MySQL 8 engine, not a mock. The per-column `COLLATE utf8mb4_0900_ai_ci` on
`trend_id` resolves the FK-target collation match against `trends.id`.

**Test outcome — still 3/3 errors, but a DIFFERENT and unrelated cause.** Once Flyway succeeds, the
Spring context goes further into startup and now fails at `secretsStartupValidator`
(`SecretsStartupValidator.validateEnvConsistency`, `com/influora/config/SecretsStartupValidator.java`):

```
Caused by: java.lang.IllegalStateException: Secrets startup validation failed (env=dev):
  - active Spring profile is not 'dev' but influora.env (APP_ENV) resolved to 'dev' — refusing to start...
  - influora.jwt.access-secret is still the committed dev default
  - influora.jwks.private-key-pem is missing
  ... (12 total fail-closed findings)
```

**Root cause is a pre-existing test-harness gap, NOT a regression from today's fix and NOT related
to trend_id/collation:** `AbstractIntegrationTest` (`influora-api/src/test/java/com/influora/testsupport/AbstractIntegrationTest.java:87-98`)
deliberately boots `@SpringBootTest` with no `@ActiveProfiles`, so it runs under the default
(non-"dev") profile by design (documented as "INV-2" in that file's own comment) — specifically to
exercise constraint enforcement the way a real non-dev deploy would. Its `@DynamicPropertySource`
only pre-seeds `influora.company.gstin` to satisfy the older `CompanyTaxStartupValidator`; it was
never updated to also satisfy the newer, more comprehensive `SecretsStartupValidator` (added/hardened
2026-07-12 P1 hardening, 2026-07-18 "I8" fix — both predate this session). Confirmed via `git log`
that both `SecretsStartupValidator.java` and `AbstractIntegrationTest.java` were last touched in
commits `9d22e4c`/`8900bbc` — neither was touched by today's 4-migration creator-copilot batch.

**Conclusion: this was always going to fail the same way once the FK bug was fixed** — the FK
collation error was simply masking this deeper, pre-existing test-infra gap by failing first, one
layer earlier in Spring context startup. It is a real, separate defect (every test extending
`AbstractIntegrationTest` is likely affected, not just this one), but it is out of scope for the FK
fix under review and not a regression caused by it.

### 3. Three previously-flagged pre-existing failures — re-run in isolation, confirmed UNCHANGED

```
Tests run: 15, Failures: 2, Errors: 1, Skipped: 0
```
- `MeeraVoiceAiClientTest.testSpeakSendsBearerTokenAndBody` — identical: expected
  `http://localhost:8000/voice/speak`, got `/voice/speak`.
- `NotificationEventContractTest.everyEventHasAPublisherOrADocumentedReason` — identical: stale
  `KNOWN_MISSING_PUBLISHERS` entry `["SubscriptionPaymentFailedEvent"]`.
- `WalletControllerTest.testTransactionsDelegatesToService` — identical NPE on
  `Workspace.getId()` (null `principal.getUserType()` stub).

No new regression among these three; all match the prior pass's exact error text/stack traces.

### VERDICT (re-verify)

**FK-collation P0: CLOSED.** Flyway applies all 4 creator migrations, including
`fk_creator_nudge_log_trend`, cleanly against a real MySQL 8 engine — SQL Error 3780 is gone.

**`DatabaseConstraintIntegrationTest`: still FAILS, but on a NEW-TO-THIS-TEST-RUN, unrelated,
pre-existing defect** (`AbstractIntegrationTest` doesn't satisfy `SecretsStartupValidator` when
booting under a non-dev profile with no `@ActiveProfiles`). This is not caused by, and is out of
scope for, the FK-collation fix. Recommend a separate ticket: either add `@ActiveProfiles("dev")`
to `AbstractIntegrationTest` (if non-dev boot is not actually load-bearing for what E3 wanted to
prove) or extend its `@DynamicPropertySource` to also stub every `SecretsStartupValidator` input
the same way it already stubs `influora.company.gstin`.

`mvn -o -q compile`: PASS. The 3 previously-identified pre-existing failures: confirmed unchanged,
no masking of a new regression.
