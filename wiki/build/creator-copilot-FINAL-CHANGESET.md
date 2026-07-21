# Creator AI Co-pilot Tier-1 — FINAL CHANGE-SET (actual working tree)

**Author:** Tara (Operations & Reporting) · **Date:** 2026-07-21 · **Status:** Post-build, post-verify. Read-only reporting.
**Branch:** `feat/portfolio-view-tracking`
**Purpose:** Documents the change-set that *actually exists in the working tree* after code, review, and verification — not the planned manifest. Enumerated directly from `git status` / `git diff --stat`, cross-checked against the planned [`creator-copilot-CHANGESET.md`](creator-copilot-CHANGESET.md).

**Actual roll-up: 44 code files — 28 CREATE, 16 MODIFY.**
FE 8 (6C / 2M) · BE-Java 23 (15C / 8M, incl. 1 test) · AI-Python 9 (3C / 6M) · DB migrations 4 (4C).
(Plan predicted 34 files / 25C / 9M. Actual is higher: +3 unplanned Java files, the actual FE mount file, and the test edit; offset by 2 planned Java files not built and 3 planned "net-zero" MODIFYs correctly touched zero.)

> Paths: FE relative to repo root (`src/...`). BE-Java relative to `influora-api/src/main/java/com/influora/`. AI-Python relative to `influora-ai/`. Migrations in `influora-api/src/main/resources/db/migration/`.

---

## 1. Frontend (React / TypeScript) — 6 CREATE, 2 MODIFY

| File path | C/M | Area | Verified by |
|---|---|---|---|
| `src/components/creator/copilot/DailySuggestionCard.tsx` | C | FE | tsc PASS · vite build PASS · Kavya QA §1.1 |
| `src/components/creator/copilot/IGConnectPrompt.tsx` | C | FE | tsc PASS · vite build PASS |
| `src/components/creator/copilot/BusinessAccountRequired.tsx` | C | FE | tsc PASS · vite build PASS |
| `src/components/creator/copilot/SuggestionEmptyState.tsx` | C | FE | tsc PASS · vite build PASS (placeholder copy — Ash+Tejas gate) |
| `src/components/creator/copilot/DailySuggestionSection.tsx` | C | FE | tsc PASS · vite build PASS · Kavya §1.2 |
| `src/hooks/useDailySuggestion.ts` | C | FE | tsc PASS · vite build PASS |
| `src/lib/api.ts` | M | FE | tsc PASS · vite build PASS · code-review base-path check |
| `src/pages/creator-deals.tsx` | M | FE | tsc PASS · vite build (16/16 routes prerendered) — **actual mount point** (see delta) |

## 2. Backend — Java (`influora-api`) — 15 CREATE, 8 MODIFY

### 2a. CREATE
| File path | C/M | Area | Verified by |
|---|---|---|---|
| `config/CreatorCopilotProperties.java` | C | BE-Java | `mvn -o -q compile` PASS · `ConfigurationPropertiesRegistrationTest` PASS |
| `config/CreatorSuggestionAiProperties.java` | C | BE-Java | compile PASS · registration test PASS |
| `domain/entity/CreatorCaptionCache.java` | C | BE-Java | compile PASS |
| `domain/entity/CreatorNudgeLog.java` | C | BE-Java | compile PASS · maps V140000 R2 columns |
| `domain/enums/CaptionTagStatus.java` | C | BE-Java | compile PASS |
| `integration/ai/CreatorSuggestionAiClient.java` | C | BE-Java | compile PASS · code-review GREENLIGHT |
| `integration/ai/dto/CreatorSuggestionAiDtos.java` | C | BE-Java | compile PASS · snake_case wire frozen |
| `job/CreatorThemeTaggingJob.java` | C | BE-Java | compile PASS (OFF by default) |
| `repository/CreatorCaptionCacheRepository.java` | C | BE-Java | compile PASS |
| `repository/CreatorNudgeLogRepository.java` | C | BE-Java | compile PASS (IDOR-safe ownership methods) |
| `service/creatorcopilot/CreatorMetaOAuthService.java` | C | BE-Java | compile PASS · Kabir gate §3 PASS |
| `service/creatorcopilot/CreatorNudgeService.java` | C | BE-Java | compile PASS · Kabir gate §5 (cap race closed by DB constraint) |
| `service/integration/CreatorSuggestionServiceTokenService.java` | C | BE-Java | compile PASS · code-review "F-5 minter" GREENLIGHT — **UNPLANNED in inventory (see delta)** |
| `web/CreatorCopilotController.java` | C | BE-Java | compile PASS · code-review base-path `/api/v1/creator/copilot/*` |
| `web/dto/creatorcopilot/CreatorCopilotDtos.java` | C | BE-Java | compile PASS · wire-identical to FE |

### 2b. MODIFY
| File path | C/M | Area | Verified by |
|---|---|---|---|
| `InfluoraApiApplication.java` | M | BE-Java | compile + registration test PASS — registers the 2 config props · **UNPLANNED in inventory (see delta)** |
| `domain/entity/CreatorProfile.java` | M | BE-Java | compile PASS (`theme_tags` JSON, system-written) |
| `domain/entity/MetaOAuthToken.java` | M | BE-Java | compile PASS · Flyway PASS (`workspace_id` nullable flip) |
| `integration/meta/oauth/MetaTokenStorage.java` | M | BE-Java | compile PASS · Kabir F-1 storage PASS (creator-owned siblings) |
| `repository/MetaOAuthTokenRepository.java` | M | BE-Java | compile PASS (creator/null-workspace finder) |
| `web/MetaOAuthController.java` | M | BE-Java | compile PASS · Kabir gate §3 OAuth CSRF PASS (live-bug OAuth flip) |
| `web/dto/meta/MetaDtos.java` | M | BE-Java | compile PASS — adds `accountType` to `MetaCallbackResponse` (API-CONTRACT §4.2) · **UNPLANNED in inventory (see delta)** |
| `src/test/java/com/influora/web/MetaOAuthControllerTest.java` | M | BE-Java (test) | compiles; runs green in scope · **test edit, not in inventory** |

## 3. AI service — Python (`influora-ai`) — 3 CREATE, 6 MODIFY

| File path | C/M | Area | Verified by |
|---|---|---|---|
| `app/routes/creator_suggestion.py` | C | AI-Python | `pytest -q` 433 pass · Kavya §1.4 · registered in `main.py:99-106` |
| `app/prompt/creator_suggestion.py` | C | AI-Python | pytest 433 pass · Kavya marketplace-regex check |
| `app/prompt/validators.py` | C | AI-Python | pytest 433 pass — extract-first shared module |
| `app/routes/trendspark.py` | M | AI-Python | pytest 433 pass — imports from `validators.py`, zero behavior change |
| `app/auth/service_token.py` | M | AI-Python | pytest 433 pass · Kabir bidirectional scope segregation PASS |
| `app/config.py` | M | AI-Python | pytest 433 pass (creator model + char caps; reuses global `PROMPT_VERSION`) |
| `app/costs/pricing.py` | M | AI-Python | pytest 433 pass (creator model rate fallback) |
| `app/security/redaction.py` | M | AI-Python | pytest 433 pass (`caption`/`captions`/`ig_handle` redact keys) |
| `app/main.py` | M | AI-Python | pytest 433 pass — router registered under defensive try/except |

## 4. DB migrations (Flyway) — 4 CREATE

| File path | C/M | Area | Verified by |
|---|---|---|---|
| `V20260721120000__creator_profile_theme_tags.sql` | C | DB | Testcontainers MySQL 8.0.40 applied · DRAFT banner struck during FK fix |
| `V20260721130000__creator_captions.sql` | C | DB | Testcontainers applied · DRAFT banner struck during FK fix |
| `V20260721140000__creator_nudge_log.sql` | C | DB | Testcontainers applied · **FK P0 fix: `trend_id VARCHAR(26) COLLATE utf8mb4_0900_ai_ci`** |
| `V20260721150000__meta_oauth_workspace_id_nullable.sql` | C | DB | Testcontainers applied (all 4 in order, v20260721150000 final) |

> 3 migration files were edited directly during the FK-collation fix pass: V120000 + V130000 (stale "DRAFT — NOT applied" banner strikes) and V140000 (the `trend_id COLLATE` fix that closed the P0).

---

## 5. Plan-vs-Actual delta

### 5a. Planned but NOT created (build correctly narrowed scope)
| Planned file | Plan ref | Why absent |
|---|---|---|
| `job/CreatorCaptionSyncJob.java` | CHANGESET §2a (CREATE) | Second sync job not built in this pass — caption ingestion deferred; only the tagging job (`CreatorThemeTaggingJob`, OFF by default) shipped. |
| `service/MetaConnectionService.java` | CHANGESET §2c (MODIFY) | **Left unwired.** Not touched. The OAuth flip landed entirely through `MetaOAuthController` + `CreatorMetaOAuthService`; the orphaned `ig/status`+disconnect routes (R1 BE bind 5) were not folded in. Carry-forward item. |
| `web/CreatorMetaStatusController.java` | CHANGESET §2c note (optional CREATE) | Not created — consequence of `MetaConnectionService` staying unwired. |
| `src/components/creator/creator-layout.tsx` | CHANGESET §1 (MODIFY, net-zero) | Correctly touched zero — nav entry was dropped per R1 bind 2. |
| `src/components/creator/connected-accounts.tsx` | CHANGESET §1 (MODIFY, net-zero) | Correctly touched zero — `onConnected` prop dropped per R1 Conflict 4. |

### 5b. Created but NOT in the planned inventory
| Actual file | C/M | Note |
|---|---|---|
| `service/integration/CreatorSuggestionServiceTokenService.java` | C | The **F-5 token minter** — referenced in API-CONTRACT/security as F-5 and greenlit in code-review, but never listed as a file in the CHANGESET inventory table. |
| `InfluoraApiApplication.java` | M | Registers the 2 new `@ConfigurationProperties` classes — required for boot (verified by `ConfigurationPropertiesRegistrationTest`), but not enumerated in the plan. |
| `web/dto/meta/MetaDtos.java` | M | Adds `accountType` to `MetaCallbackResponse` — specified in API-CONTRACT §4.2 but not carried into the CHANGESET file table (the **igBusinessAccountId / account-type mapping** surface). |
| `src/pages/creator-deals.tsx` | M | The **actual dashboard mount** (`<DailySuggestionSection/>` above HypeInboxCard). Plan predicted the mount would be located at build time; it resolved to this page, not the planned `creator-layout.tsx`. |
| `src/test/java/.../MetaOAuthControllerTest.java` | M | Test updated for the OAuth flip; not in the (non-test) inventory. |

### 5c. Transient / naming deltas
- `src/types/creator-copilot.ts` was created during the build, flagged CHANGES-REQUESTED by code-review (R1 rejected a separate types module), and **deleted** — not present in the final tree. Net zero.
- Migration V150000 filename is `..._meta_oauth_workspace_id_nullable.sql` (actual) vs `..._meta_oauth_workspace_nullable.sql` (plan's proposed name). Cosmetic.
- Python CREATE count is **3** (route + prompt + `validators.py`), not the plan roll-up's "2" (the plan's own §3 table already listed 3; the roll-up line undercounted).

---

## 6. Verification scorecard (final)

| Check | Command / harness | Result |
|---|---|---|
| FE typecheck | `npx tsc --noEmit` | **PASS** — exit 0, 0 errors |
| FE build | `npm run build` (vite + prerender) | **PASS** — 4753 modules, 16/16 routes snapshotted (1 pre-existing cosmetic `baseUrl` warning) |
| Java compile | `mvn -o -q compile` | **PASS** — exit 0 |
| Java boot-blocker | `ConfigurationPropertiesRegistrationTest` | **PASS** — both new config props registered |
| Python suite | `pytest -q` | **433 passed, 2 failed** (2 failures pre-existing, see §7) |
| Real-MySQL Flyway | `DatabaseConstraintIntegrationTest` (Testcontainers MySQL 8.0.40) | **PASS** — all 4 creator migrations applied in order incl. `fk_creator_nudge_log_trend`; **FK-collation P0 CLOSED** (SQL Error 3780 gone) |
| Security gate | Kabir | **PASS** — no Critical/High; 6 Medium/Low hardening items (non-blocking) |
| Final QA | Kavya | **CONDITIONAL PASS** — 0 blockers; 1 fast-follow (formal pytest suite before pilot) |
| Code review | (reviewer) | **GREENLIGHT** — 2 minor cleanups; merge gated on money-path |

## 7. Known pre-existing failures — NOT ours (separately ticketed)

**Java (confirmed via `git log`/`git status` — none touched by this batch):**
1. `MeeraVoiceAiClientTest.testSpeakSendsBearerTokenAndBody` — URL base mismatch (`/voice/speak` vs `http://localhost:8000/voice/speak`); last touched commit `0b725dd` (2026-07-20).
2. `NotificationEventContractTest.everyEventHasAPublisherOrADocumentedReason` — stale `KNOWN_MISSING_PUBLISHERS` allowlist entry (`SubscriptionPaymentFailedEvent` now has a publisher post money-path commits); test untouched since `8900bbc`.
3. `WalletControllerTest.testTransactionsDelegatesToService` — NPE on `Workspace.getId()`; test never stubs `principal.getUserType()`; predates session.
4. `DatabaseConstraintIntegrationTest` — after the FK fix, now fails one layer later at `SecretsStartupValidator` because `AbstractIntegrationTest` boots a non-dev profile with no `@ActiveProfiles` and doesn't stub the secrets inputs. Pre-existing test-infra gap (`SecretsStartupValidator`/`AbstractIntegrationTest` last in `9d22e4c`/`8900bbc`); the FK error had merely been masking it. Recommend its own ticket.

**Python (pre-existing, known):**
5. `tests/routes/test_voice.py::TestTruncateForTts::test_truncation_adds_ellipsis`
6. `tests/routes/test_voice.py::TestTruncateForTts::test_max_chars_constant_is_200`

---

## 8. Gates still open before ship

1. **8-test pytest fast-follow** — Kavya's MUST-WRITE creator_suggestion test list (happy path, provider-fail fallback, IDOR, scope-mismatch, spend-gate, closed-vocab, zero-state, registration) must land as a formal suite before the pilot goes live. Non-blocking for merge, blocking for pilot.
2. **Money-path merge gate (governs all)** — parent money-path signoff still governs; nothing in this change-set merges until it lands.
3. **Live Meta / Anthropic keys** — live E2E has never run (OAuth token exchange + Haiku generation gated on unprovisioned keys). All AI/OAuth verification to date is code + Testcontainers only.
4. **Ash + Tejas zero-state copy** — `SuggestionEmptyState.tsx` ships placeholder copy for `no_suggestion_today` / zero-themes. Final copy is a one-line swap; it blocks Kavya's zero-state assertion and the ship, not the build.

**Carry-forward (from §5a):** `MetaConnectionService` left unwired — the `ig/status`+disconnect routes (R1 BE bind 5) are not in Tier-1 as shipped. Track separately if the dashboard needs a live connection-status read.

**Next:** → Kabir for the closing pass.
