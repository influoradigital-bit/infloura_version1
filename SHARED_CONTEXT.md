# SHARED_CONTEXT.md — ACTIVE TASK

> Pipeline bus (Arjun owns). Holds the ACTIVE task only. Terse handoffs:
> `FROM → TO | TASK | FILES | STATUS | NEXT`.

---

## TASK: Campaign HYPE config persistence (hype_config JSON column) — local verification

```
FROM Kavya → Meera | Local run verification (BE+FE+migration+AI redis P1) | Campaign.java, CampaignMapper.java, CampaignService.java, CampaignValidator.java, CampaignDtos.java, CampaignServiceTest.java, V20260718190000__campaign_hype_config.sql, deliverable-review-panel.tsx | ✅ ALL PASS | see matrix below
```

**Meera Verification — 2026-07-18 (branch `feat/portfolio-view-tracking`)**

| Stack | Command | Result |
|---|---|---|
| Backend full suite | `.tools/apache-maven-3.9.10/bin/mvn -o test` | ✅ BUILD SUCCESS — Tests run: 1349, Failures: 0, Errors: 0, Skipped: 3 (Docker unavailable, testcontainers-gated). Matches reported claim exactly. |
| Backend targeted | grep for `CampaignServiceTest` in the run | ✅ Tests run: 15, Failures: 0, Errors: 0 |
| Frontend typecheck | `npx tsc --noEmit` | ✅ exit 0, 0 errors, empty output |
| Frontend build (1st run) | `npm run build` | ❌ exit 1 — `vite build` itself succeeded (48.38s, 4739 modules) but `postbuild` (`scripts/prerender.mjs`) failed 1/16 routes: `/blog: Waiting failed: 15000ms exceeded` |
| Frontend build (retry, prerender only) | `node scripts/prerender.mjs` | ✅ exit 0 — 16/16 routes snapshotted on retry, including `/blog` |
| AI service — redis P1 (Ash) | `grep -n redis influora-ai/requirements.txt influora-ai/requirements-dev.txt influora-ai/Dockerfile` | **CONFIRMED** — zero hits in all three; `redis.asyncio` imported in `app/auth/replay_guard.py` and `app/costs/spend_tracker.py` |
| AI service — crash check | `.venv/Scripts/python.exe -c "import app.main"` | ✅ imports clean (only unrelated pydantic warning) — both redis imports are `try/except ImportError`-guarded with in-memory fallback, so this is a silent feature degradation, not a boot crash |

**Migration verdict — `V20260718190000__campaign_hype_config.sql`: ✅ SOUND**
- Naming/sequencing: timestamp `20260718190000` sorts immediately after `V20260718180000__workspace_phone.sql` (the current head) — correct Flyway order. `ls | sed -E 's/^(V[0-9]+)__.*/\1/' | sort | uniq -d` on all 88 migration files → zero duplicate version numbers, chain intact.
- Content: `ALTER TABLE campaigns ADD COLUMN hype_config JSON NULL AFTER campaign_type;` — additive, nullable, no default, no backfill, no destructive ops.
- Entity mapping consistent: `Campaign.java` adds `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "hype_config", columnDefinition = "json") private String hypeConfigJson;` — column name/type/nullability match the DDL exactly.

**Discrepancy flagged (not blocking, but real):** the "reportedly `npm run build` success" claim is only true on a retry. First invocation failed (exit 1) on a flaky 15s headless-Chrome timeout prerendering `/blog` — unrelated to this branch's diff (no blog/prerender files touched), reproduced clean on immediate retry (16/16). Recommend Vikram/whoever owns `scripts/prerender.mjs` bump the per-route timeout or add a retry-once, since CI will hard-fail on this flake with no code change required to "fix" it.

**AI redis gap detail:** not a hard crash (both call sites degrade gracefully to in-memory), but `spend_tracker.py`'s own comment claims "redis is a pinned dep (requirements.txt)" — that's false in the current tree, so the cross-instance Redis-backed daily-spend ceiling (H-25) and the stream-token replay guard can never actually activate in the shipped Docker image; every worker silently runs a per-process-only fallback even if `REDIS_URL` is set. Matches Ash's P1 exactly.

**VETO: not exercised for backend/migration (clean pass). Frontend build flake and AI redis gap are real but non-blocking per the graceful-degradation design — flagging both for Arjun to route (prerender flake → Vikram/build owner; redis → Ash's existing P1 ticket, add package to requirements.txt + Dockerfile if cross-instance spend ceiling / replay guard are meant to actually work in prod).**

### Re-run — direct request from Priya (prior dual-gate run interrupted before verdict) — 2026-07-18 19:xx IST

```
FROM Priya → Meera | Re-run Hype dual gate cleanly (FE build + BE full suite + migration) | Campaign.java, CampaignMapper.java, CampaignService.java, CampaignValidator.java, CampaignDtos.java, CampaignServiceTest.java, V20260718190000__campaign_hype_config.sql, src/lib/api.ts | ✅ ALL PASS | cleared to score
```

| Check | Command | Result |
|---|---|---|
| Backend full suite | `.tools/apache-maven-3.9.9/bin/mvn -o test` (note: bundled dir is `3.9.9`, not `3.9.10` as previously logged) | ✅ BUILD SUCCESS — Tests run: 1353, Failures: 0, Errors: 0, Skipped: 3, total 1m12s |
| Backend — CampaignServiceTest | grep in run | ✅ Tests run: 19, Failures: 0, Errors: 0 — includes all 4 requested Hype cases (`testHypeCampaignRoundTripsConfig`, `testStandardCampaignUnaffectedByHypeStorage`, `testMalformedHypeConfigRejected`, `testHypeCampaignMissingConfigRejected`) + 4 PATCH-path Hype tests (full patch, partial merge, malformed patch rejected, ignored-for-non-HYPE), all green |
| Frontend build | `npm run build` (vite build + postbuild prerender) | ✅ exit 0 — built in 1m49s, 16/16 marketing routes prerendered, no errors (only pre-existing duplicate-`baseUrl` tsconfig warning) |
| Migration | static read + ordering/dedup check | ✅ `ALTER TABLE campaigns ADD COLUMN hype_config JSON NULL AFTER campaign_type;` — additive, nullable, no default/backfill; sorts immediately after `V20260718180000__workspace_phone.sql`; zero duplicate version numbers across all migrations |

**Note on the interrupted prior attempt:** stale logs from the interrupted run showed a broken `node_modules` (missing `@tailwindcss/node/dist/esm-cache.loader.mjs`) and `vite` not on PATH — both transient/mid-install artifacts, not real defects. Confirmed clean on this re-run with no code changes. Logs cleared (`.meera_*_clean.log` written this pass, temp files removed after).

**VERDICT: ✅ FE build + BE full suite + migration all PASS. Hype dual gate cleared to score. VETO not exercised.**

---

## Meera Verification — deliverable-review-panel.tsx (direct request from Priya) — 2026-07-18

```
FROM Priya → Meera | Local verify: deliverable Approve/Request-Revision now call real deliverablesApi (no more mock setTimeout) | src/components/brand/timeline/panels/deliverable-review-panel.tsx | ✅ PASS | cleared to score
```

| Check | Command | Result |
|---|---|---|
| Typecheck | `npx tsc --noEmit` | ✅ exit 0, 0 errors |
| Build | `npm run build` (vite build + postbuild prerender) | ✅ exit 0, built in 1m7s, 4739 modules, 16/16 routes prerendered. Only pre-existing >500kB chunk-size warning + pre-existing duplicate-`baseUrl` tsconfig warning — neither new. |
| Wired into bundle (not dead code) | grep `DeliverableReviewPanel` | ✅ imported by `src/components/brand/timeline/event-cards/deliverable-card.tsx` — live in the timeline render tree |
| Mount spot-check | `npm run dev` + browser → `/brand/campaigns/test-campaign-id` (no backend up) | ⚠️ PARTIAL — confirmed Ananya's note: the campaign-detail page's own parent data fetch (`GET/OPTIONS .../campaigns/:id`, `.../deals?status=all`, `.../campaigns/:id/analytics`) fails first with `net::ERR_CONNECTION_REFUSED` (network log confirms), so the deliverable-review-panel itself never mounts without live campaign/deal data — page falls through to a 404/error state before the panel's Approve/Request-Revision buttons are reachable. Could not directly exercise the panel's new network-call behavior in this environment. |

**VERDICT: ✅ BUILD PASS (authoritative gate) — cleared to score.** Mount could not be fully exercised (pre-existing environment limitation, not a defect in this diff) — build + bundle-inclusion evidence stand in per Priya's guidance. VETO not exercised.

---

## TASK: Brand Settings workspace-info (name/email/phone/website) — DUAL gate

```
FROM Kavya → Meera | Final DUAL gate (FE+BE+migration) | WorkspaceController.java, WorkspaceService.java, Workspace entity, WorkspaceMemberDtos.java, V20260718180000__workspace_phone.sql, WorkspaceServiceTest.java, WorkspaceControllerTest.java, src/lib/api.ts, src/pages/brand-settings.tsx | ✅ ALL PASS | cleared to score
```

**Meera Verification — 2026-07-18**

| Check | Command | Result |
|---|---|---|
| FE build | `npm run build` (vite build + postbuild prerender) | ✅ PASS — built in 1m8s, 16/16 routes prerendered, exit 0. Only pre-existing >500kB chunk-size warning, no new errors. |
| BE targeted | `mvn -o test -Dtest=WorkspaceServiceTest,WorkspaceControllerTest` | ✅ PASS — Tests run: 13 (11+2), Failures: 0, Errors: 0 |
| BE full suite | `mvn -o -DskipITs test` | ✅ PASS — Tests run: 1345, Failures: 0, Errors: 0, Skipped: 3. BUILD SUCCESS |
| Migration sanity | static read of `V20260718180000__workspace_phone.sql` | ✅ PASS — `ALTER TABLE workspaces ADD COLUMN phone VARCHAR(30) NULL AFTER billing_email;` — additive, nullable, no default needed, no backfill. Timestamp sorts after `V20260718170000__admin_error_log.sql` — Flyway ordering correct. |
| FE mount spot-check | `npm run dev` + browser, `/brand/settings` (fake `brand_token`, no backend up) | ✅ PASS — General tab mounts, shows "Could not load workspace information." on fetch failure (caught cleanly, no crash), Save Changes button present+enabled, all 4 fields (Workspace Name/Email/Phone/Website) render. Console shows expected caught `TypeError: Failed to fetch` from `getMe`/`updateMe`/`getPreferences`, no uncaught exceptions. |

**CANNOT-VERIFY:** live GET/PATCH round-trip against a real backend + DB — no DB-backed server was running in this environment; only the graceful-failure path was exercised.

**VERDICT: ✅ ALL PASS — FE build + BE tests (targeted & full) + migration all clear. Settings workspace-info feature cleared to score. VETO not exercised.**

---

## ⚠️ TASK: Portfolio view tracking + auth fix (PIPELINE CORRECTION)

**Owner:** Arjun (re-routing) · **Issue:** Priya wrote the entire feature herself, bypassing Vikram/Kavya/Meera/Kabir. Routing through proper gates now.

**Branch:** `feat/portfolio-view-tracking` (commit `fa411e8`)

### What was built (by Priya)

| Component | Files | Status |
|---|---|---|
| Migration | `V20260718120000__portfolio_events.sql` | ✅ written |
| Entity/Enum | `PortfolioEvent.java`, `PortfolioEventType.java` | ✅ written |
| Repository | `PortfolioEventRepository.java` | ✅ written |
| Service | `PortfolioService.java` (modified) | ✅ written |
| Controller | `PortfolioController.java` (modified) | ✅ written |
| Security | `SecurityConfig.java` (**auth gate fix**) | ✅ written |
| Tests | `PortfolioServiceTest.java` (7 tests), `SecurityConfigMatcherTest.java` (21 tests) | ✅ green (1256 suite) |
| Scope doc | `wiki/tech/MEDIA-KIT-SCOPE.md` | ✅ written |

**Test status (Priya):** 1256 tests, 0 failures, 0 errors

### PIPELINE RE-ROUTING

```
FROM Arjun → Kavya | QA review of 10 files on feat/portfolio-view-tracking | SecurityConfig.java + 9 others | NEXT | check auth changes, TECH-STACK compliance, transaction isolation, test coverage
FROM Kavya → Kabir | Security audit (CRITICAL: auth-touching code) | SecurityConfig.java + full changeset | BLOCKED on Kavya | OWASP audit mandatory for auth changes
FROM Kabir → Meera | Local build verify | full suite + curl public portfolio GET | BLOCKED on Kabir | after Kabir PASS
FROM Meera → Priya | Final sign-off | — | BLOCKED on Meera | after all gates pass
```

**Why this matters:**
- Kabir's security audit is **mandatory for auth-touching code** (this modified `SecurityConfig.java`)
- Pipeline separation ensures knowledge distribution (Vikram learns patterns)
- QA/verify gates catch issues Priya might miss working alone

**Open decision (media-kit PDF):** Swapnil needs to answer `wiki/tech/MEDIA-KIT-SCOPE.md` §7 — do clients need downloadable PDF or is shareable link enough? Unrelated to this pipeline; tracked separately.

### Meera Verification — direct request from Priya, uncommitted tree — 2026-07-18

```
FROM Priya → Meera | Build/test verify UNCOMMITTED changes (out-of-band, ahead of formal Kabir gate above) | full working tree on feat/portfolio-view-tracking (influora-api/, influora-ai/, root frontend) | ✅ ALL PASS | see below
```

**Scope note:** `git status` on this branch shows uncommitted changes far broader than the portfolio-tracking table above — nearly every service is touched (60+ Java files, 15 Python files, root frontend config/docs). Verified all three services as a whole; did not scope to only the portfolio-tracking file list.

**Toolchain used:** no `mvnw` in `influora-api` (confirmed, matches prior note); found a local offline Maven at `C:\Users\Sage world\.maven\apache-maven-3.9.6\bin\mvn.cmd` with a populated `~/.m2` — ran `-o` (offline). JDK 21. Node v22.15.0. Python 3.13.3 via `influora-ai/.venv`.

| Service | Check | Command | Result |
|---|---|---|---|
| influora-api | compile | `mvn -o -DskipTests compile` | ✅ exit 0 |
| influora-api | full test suite | `mvn -o -DskipITs test` | ✅ **BUILD SUCCESS** — Tests run: 1343, Failures: 0, Errors: 0, Skipped: 3 |
| Frontend | typecheck | `npx tsc --noEmit` | ✅ exit 0, 0 errors |
| Frontend | production build | `npm run build` (vite build + prerender postbuild) | ✅ built in 52.4s, 16/16 marketing routes prerendered; only pre-existing chunk-size warning (>500kB main bundle, not new) |
| influora-ai | targeted pytest on changed test dirs | `pytest tests/costs tests/eval/test_trendspark_nudge.py tests/routes/test_chat_conversation_binding.py tests/routes/test_voice_spend_gate.py tests/security/test_redaction.py tests/tools/test_loop_usage.py` | ✅ 85 passed |

**CANNOT-VERIFY (scoped out, not blocking):** live `npm run dev` + curl against `/api/...` endpoints and Docker-backed services (Postgres/n8n/Postiz) — Docker Desktop is not running in this environment (`docker ps` → "cannot connect to the Docker API"). No DB-backed end-to-end smoke test was possible here; compile + full unit/integration test suites are the verification basis instead.

**VETO: not exercised — clean PASS across all three services.** Safe to commit from a build/test standpoint. No build-breaking issues found in the diff.

---

## MEERA FIX SPRINT — 2026-07-18

**Context:** Priya verified 8 defects and locked 2 decisions: (1) Meera fix = restore REAL browser SSE streaming (gateway returns stream handle, browser streams with on-behalf JWT so tools + Living Canvas stages work); (2) localStorage→httpOnly token migration DEFERRED to separate task.

**Gate loop:** Implement → Kavya QA → (fail→owner) → Meera build/verify → Kabir security (I1/I4/I8) → Priya sign-off.

### Wave 1: Safe/Parallel (no dependencies)

| ID | Description | Owner | Files | Verify |
|----|-------------|-------|-------|--------|
| I1 | SPA missing CSP/HSTS/Referrer/Permissions — docker/nginx.conf ignores public/_headers (Cloudflare file); nginx add_header replace-not-merge drops nosniff from /assets/ + index.html | Vikram | docker/nginx.conf | Kabir + Meera |
| I2 | Creator self metrics/scores 404 — useCreatorMetrics.ts:52 & useCreatorScores.ts:37 pass __me__ literally; must branch to api.creatorAnalytics.getMyMetrics/getMyScores like useCreatorDemographics.ts:42-45 | Ananya | src/hooks/analytics/useCreatorMetrics.ts, src/hooks/analytics/useCreatorScores.ts | Kavya |
| I8 | APP_ENV footgun — SecretsStartupValidator + application-prod.yml let SPRING_PROFILES_ACTIVE=prod + missing APP_ENV boot on committed dev secret defaults | Vikram | influora-api/src/main/java/.../config/SecretsStartupValidator.java, influora-api/src/main/resources/application-prod.yml | Kabir + Meera |

### Wave 2: Meera streaming (money path)

| ID | Description | Owner | Files | Dependencies | Verify |
|----|-------------|-------|-------|--------------|--------|
| I3 | Meera SSE dormant — MeeraChatPanel.tsx:288 early-returns on turnRes.reply!=null; gateway MeeraController/doSendTurn returns synchronous full reply. Restore streaming-first contract. | Vikram (backend contract) + Ananya (frontend gate) | influora-api/.../web/MeeraController.java, src/components/feature/meera/MeeraChatPanel.tsx | I1,I2,I8 complete | Kavya + Meera + Kabir |
| I4 | Meera tools 401 — MeeraChatAiClient.java:44 sends onbehalf_jwt="". Under streaming-first, browser carries JWT; decide fate of synchronous server path. | Vikram | influora-api/.../meera/MeeraChatAiClient.java | I3 | Kabir |
| I5 | Living Canvas stages no live data — falls out of I3/I4 | Ananya | src/components/feature/meera/Living*.tsx, src/components/feature/meera/Stage*.tsx | I3 + I4 | Kavya |

### Wave 3: Mock pages (post-streaming)

| ID | Description | Owner | Files | Dependencies | Verify |
|----|-------------|-------|-------|--------------|--------|
| I6 | creator-chat.tsx / brand-deals.tsx / brand-pipeline.tsx / brand-messages.tsx are 100% mock | Ananya (+ Vikram if endpoints missing) | src/pages/creator-chat.tsx, src/pages/brand-deals.tsx, src/pages/brand-pipeline.tsx, src/pages/brand-messages.tsx | I5 complete | Kavya + Meera |
| I7 | brand-settings.tsx & creator-settings.tsx save is no-op alert()/local-only | Ananya (+ Vikram if endpoints missing) | src/pages/brand-settings.tsx, src/pages/creator-settings.tsx | I5 complete | Kavya |

**Routing:**
- FROM Arjun → Vikram | Wave 1: I1, I8 | docker/nginx.conf, SecretsStartupValidator.java, application-prod.yml | ASSIGNED | parallel with I2
- FROM Arjun → Ananya | Wave 1: I2 | useCreatorMetrics.ts, useCreatorScores.ts | ASSIGNED | parallel with I1/I8
- FROM Vikram/Ananya → Kavya | Wave 1 QA | all Wave 1 files | BLOCKED on Wave 1 complete | gate before Wave 2
- FROM Kavya → Meera | Wave 1 build/verify | — | BLOCKED on Kavya | curl checks for I1, build for I8, analytics endpoints for I2
- FROM Meera → Kabir | Wave 1 security audit | I1 + I8 only | BLOCKED on Meera | OWASP for nginx + secrets
- FROM Kabir → Arjun | Wave 1 gate | — | BLOCKED on Kabir | Wave 2 launch gate
- FROM Arjun → Vikram+Ananya | Wave 2: I3 (backend+frontend) | MeeraController.java, MeeraChatPanel.tsx | BLOCKED on Wave 1 Kabir PASS | streaming-first contract
- FROM Vikram/Ananya → Kavya | Wave 2 I3 QA | — | BLOCKED on I3 | SSE smoke test
- FROM Kavya → Vikram | Wave 2: I4 | MeeraChatAiClient.java | BLOCKED on I3 Kavya PASS | JWT + server path decision
- FROM Vikram → Ananya | Wave 2: I5 | Living*.tsx, Stage*.tsx | BLOCKED on I4 | wire live data
- FROM Ananya → Kavya | Wave 2 I5 QA | — | BLOCKED on I5 | Living Canvas stages functional
- FROM Kavya → Meera | Wave 2 verify | — | BLOCKED on Kavya | npm run dev, test Meera chat + tools + stages
- FROM Meera → Kabir | Wave 2 security | I4 only | BLOCKED on Meera | JWT + server-side tools 
- FROM Kabir → Arjun | Wave 2 gate | — | BLOCKED on Kabir | Wave 3 launch gate

**Vikram → Kavya | Wave 2 backend done: streaming-first doSendTurn + charge-on-success credits | influora-api/src/main/java/com/influora/service/meera/MeeraSessionService.java, AICreditService.java, influora-api/src/main/java/com/influora/web/MeeraController.java, web/dto/meera/MeeraDtos.java, InfluoraApiApplication.java (comment only) | READY for QA | I3 (backend half) + I4 both resolved — see notes below**

- `doSendTurn` no longer calls Python synchronously: credit-gates via new non-decrementing `AICreditService.assertAvailable`, persists USER message (`creditsCharged=0`), mints stream token, returns `TurnResult` with `assistantMessageId=null`/`placeholderReply=null`. `SendTurnResponse` gained a `workspaceId` field (frontend's `MeeraTurnResponse.workspaceId` in `src/lib/meera-api.ts` already expected it) so `MeeraChatPanel.handleLiveSend` can build the SSE body.
- Charge-on-success: `TURN_CREDIT_COST` decrement moved into `persistAssistantWriteback`/`doPersistAssistantWriteback` (calls `creditService.tryConsume`), which is now the SOLE writer of the ASSISTANT row (`creditsCharged=TURN_CREDIT_COST`). Idempotent by construction — the decrement runs inside the `IdempotencyService.executeOnce` supplier, so a replayed write-back never re-enters it (proven in `MeeraSessionServiceTest`).
- I4 resolved: `MeeraChatAiClient`/`MeeraChatAiException`/`MeeraChatAiClientTest` deleted (zero remaining production callers — confirmed by grep). `MeeraChatAiProperties` kept (still read by `SecretsStartupValidator`'s prod-localhost guard).
- Write-back auth (`MeeraInternalController#persistTurnWriteback` → `OnBehalfAuthResolver`) unchanged and already correct — it validates the real browser on-behalf JWT against the conversation's workspace, no weakening needed.
- Tests: `MeeraSessionServiceTest` (19), `AICreditServiceTest` (13) — new coverage for 0-credit rejection (no USER row persisted), charge-exactly-once on success, no-double-charge on replay, and no-charge on send. Full suite: `mvn -o test` → 1257 run, 0 failures, 0 errors. `mvn -o compile` clean.
- Assumption flagged for Priya/Kabir: if `creditService.tryConsume` fails inside the write-back (a race where credits ran out between `sendTurn`'s check and the write-back arriving), the whole write-back throws 402/429 and no ASSISTANT message is ever persisted for that turn — the generated reply is effectively dropped rather than persisted-but-uncharged. Flagging as an edge case worth a product decision, not fixed unilaterally.
- FROM Arjun → Ananya+Vikram | Wave 3: I6, I7 | 6 mock pages | BLOCKED on Wave 2 Kabir PASS | implement real endpoints + wire
- FROM Ananya/Vikram → Kavya | Wave 3 QA | — | BLOCKED on Wave 3 | settings save + chat/deals/pipeline/messages real data
- FROM Kavya → Meera | Wave 3 verify | — | BLOCKED on Kavya | smoke test all 6 pages
- FROM Meera → Priya | Final sign-off | — | BLOCKED on Wave 3 Meera | production gate

## Meera Verification — Wave 2 Meera streaming money-path — 2026-07-18 13:59 IST

```
FROM Meera → Priya | Wave 2 local verification (4 checks) | influora-api/src/test/java/com/influora/service/EscrowServiceTest.java, .../web/MeeraControllerTest.java, .../service/meera/{MeeraSessionServiceTest,AICreditServiceTest}.java, influora-ai/app/routes/chat.py, app/clients/spring.py, app/tools/loop.py, src/components/feature/meera/MeeraChatPanel.tsx | ALL PASS | not blocking — headline is CHECK #1 below
```

**CHECK #1 (decisive) — Vikram's "module-wide test-compile fails on EscrowServiceTest/MeeraControllerTest, pre-existing+unrelated" claim: NOT REPRODUCIBLE right now.**
- `mvn -o clean test-compile` on current tree: **BUILD SUCCESS** — 624 main sources + 157 test sources, 0 errors (only pre-existing deprecation/unchecked warnings in unrelated files).
- File mtimes at snapshot time (13:56): `EscrowService.java` 13:46, `MeeraController.java` 13:48, `EscrowServiceTest.java` 13:48, `MeeraControllerTest.java` (untracked/new) 13:49 — all edited in the ~10min before my clean build. Matches the flagged concurrent background-agent activity; whatever Vikram saw was most likely already fixed by those edits, or the claim didn't hold to begin with. Re-run against the exact commit Vikram tested if this needs airtight reproduction.
- Content check regardless of the above: **neither file is Wave-2 collateral.** `EscrowServiceTest`'s new tests (`listForWorkspaceReturnsPagedHolds`, `listForWorkspaceClampsPageAndLimit`) cover `EscrowService.listForWorkspace`/`PagedEscrowHolds` — escrow list pagination (task N4), unrelated to Meera. `MeeraControllerTest` (new file) only tests `MeeraController#speak` (voice `/speak` endpoint via `MeeraVoiceAiClient`) — zero references to `sendTurn`, old `reply`/`assistantMessageId` contract, or deleted `MeeraChatAiClient`.
- Grep confirms zero remaining references anywhere in `src/` to deleted `MeeraChatAiClient`/`MeeraChatAiException` except 2 explanatory comments (`InfluoraApiApplication.java:42`, `MeeraSessionService.java:31`). The one `assistantMessageId()` assertion left in `MeeraSessionServiceTest.java:438` asserts it's **null** — that's the new streaming-first contract, not the old synchronous one.
- **Verdict: Wave 2 is NOT blocked by this.** Either fixed-in-flight or a false alarm; content-wise these two files were never Wave-2 collateral in the first place.

**CHECK #2 — money-path tests: PASS.**
`mvn -o test -Dtest=MeeraSessionServiceTest,AICreditServiceTest` → **Tests run: 32 (19+13), Failures: 0, Errors: 0.** Saw the credit-race path exercise live: `WARN ... credit race: assistant turn persisted uncharged` during the run. DisplayName coverage confirms all 5 required behaviors: 0-credit rejected pre-repository-touch (`sendTurn: rejects a null/blank idempotencyKey...`), charge-exactly-once on success (`persistAssistantWriteback: first call persists exactly one AiMessage`), no double-charge on replay, abandoned-stream not charged, and credit-race persists uncharged (`AICreditServiceTest`: `tryConsume: credits exhausted -> 402`, atomic decrement, unlimited bypass, P4 429 cap).

**CHECK #3 — Python I4 end-to-end auth: PASS.**
- `chat.py:76`: `onbehalf_jwt = body.get("onbehalf_jwt") or _strip_bearer(authorization)` — real JWT, not hardcoded empty.
- `tools/loop.py:216`: forwards `ctx.onbehalf_jwt` into every tool call → `spring.py:120` places it in `X-Onbehalf-Authorization` header sent to Spring `/internal/meera/*` → tools now authorize instead of 401.
- Write-back (`chat.py:276-286`) passes the same `onbehalf_jwt` into `spring.persist_assistant_message` → `spring.py:200-226` forwards it through `call_tool_endpoint` with the same header → `persistAssistantWriteback` authorizes.
- Frontend confirmed as the source of a real token: `MeeraChatPanel.tsx:327` — `onbehalf_jwt: localStorage.getItem('brand_token') ?? ''`. Empty-string only as a last-resort fallback if the browser has no session (logged-out edge case, not a code bug). No place sends a hardcoded/empty jwt.

**CHECK #4 — frontend build sanity: PASS.**
`npx tsc --noEmit -p tsconfig.json` → exit 0, zero errors.

**VETO: not exercised — full PASS across all 4 checks.** Cleared to proceed to Kabir (I4 security) per the Wave 2 gate loop.

---

## TASK: Brand contracts partial integration fix — QA complete (Ananya → Kavya → Meera)

```
FROM Priya → Kavya | Verify Ananya's interrupted contract integration work (read CURRENT code, no git diff) | src/lib/contract-generator.ts, src/components/brand/deal-room/deal-contract-tab.tsx, src/components/brand/timeline/panels/contract-panel.tsx, src/components/creator/deal-room/creator-contract-panel.tsx, src/components/creator/deal-room/creator-deal-contract-tab.tsx, src/components/brand/contracts/contracts-and-deliverables.tsx, src/lib/api.ts | ✅ PASS | ready for Meera build verification
```

**Kavya QA Verdict — 2026-07-18 20:00 IST**

| Check | Result | Evidence |
|-------|--------|----------|
| 1. signContract calls REAL api.contracts.sign | ✅ PASS | src/lib/contract-generator.ts:225 — calls `api.contracts.sign(signedBy, contractId, { name, agreedAt })` with real contractId, signerName, ISO timestamp; error handling present (lines 234-237); NO setTimeout/simulation |
| 2. All callers pass real contract ID + signer name | ✅ PASS | 4 callers verified: (1) deal-contract-tab.tsx:79 — contractId from props, signerName trimmed (line 75); (2) contract-panel.tsx:69 — contractId from event.metadata, guarded (line 66), disabled if missing (line 216); (3) creator-contract-panel.tsx:84 — same guard pattern (lines 81,271,291); (4) creator-deal-contract-tab.tsx:71 — contractId from props. NO placeholders, NO fake IDs. |
| 3. contracts-and-deliverables.tsx live mode | ✅ PASS | Line 416: `liveApi ? [] : mockContracts` — live starts EMPTY, not seeded. Line 447: fetchContracts early-returns if `!liveApi`. Comments 414-415, 332-333, 444-445 document NO mock merge in live mode. |
| 4. Backend API chain exists | ✅ PASS | src/lib/api.ts:1389 → POST `/contracts/:id/sign` → influora-api/.../web/ContractController.java:78 (confirmed via grep) |
| 5. Code quality | ✅ PASS | No `any` (grepped all 5 TS files, zero hits). No console.log (grepped contract-generator.ts, zero). All callers catch ApiError, surface to user via toast. |

**Notes:**
- Contracts page deliverables section may show empty in live mode if backend list endpoint doesn't return deliverables/clauses — ACCEPTABLE (honest empty, no mock leak).
- All callers guard missing contractId (early return or button disable).
- Full review report: `wiki/errors/contract-generator-review.md`

**NEXT:** Meera runs `npm run build` to verify frontend builds cleanly with this integration.

---

## TASK: GEO Audit Follow-up — Rank on All Platforms (SEO/AEO/GEO)

**Owner:** Aditya (SEO Lead) · **Context:** GEO-TECHNICAL-AUDIT.md (tech score 47/100)
**Goal:** Make Influora rank/cite across Google, Bing, ChatGPT, Perplexity, Claude, Gemini

### Quick wins shipped (Items 1-4, <3h total)
```
✅ index.html — fixed head (correct brand, meta, OG, JSON-LD, real favicon refs, static crawler fallback)
✅ public/sitemap.xml — removed 6 dead URLs (/features/contracts, /kyc, /tds, /refund-policy, /guidelines/*), added /contact + 3 blog posts
✅ public/llms.txt — removed /features/contracts reference
✅ public/_redirects — SPA fallback (/*  /index.html  200)
✅ public/_headers — added HSTS + Permissions-Policy
⚠️ public/og-image.png — placeholder note created; Zara assigned via Aditya's brief
```

### Handoffs (Aditya coordinating)
```
FROM Aditya → Ishaan | AEO content rewrites (5 pages) + comparison post | src/pages/landing.tsx, pricing.tsx, features/escrow.tsx, how-it-works-brands.tsx, how-it-works-creators.tsx + new blog post | ASSIGNED | see §Ishaan brief below
FROM Aditya → Ananya | Quick-win code verification | index.html, sitemap.xml, _redirects, _headers | DONE | code shipped, review optional
FROM Aditya → Zara | og-image.png design (1200×630) | public/og-image-placeholder.txt | ASSIGNED | specs in placeholder file
FROM Aditya → Vikram | RR7 prerender (item #5, 1-2d) | vite.config.ts → react-router.config.ts, prerender 16 marketing routes | DONE (via alt approach, see below) | items 1-4 confirmed shipped, unblocked
FROM Vikram → Kavya | Prerender 16 marketing routes to static HTML | scripts/prerender.mjs, package.json (postbuild script), public/_redirects | READY | see notes below — approach deviated from audit's RR7 framework-mode suggestion; verified 16/16 routes emit real HTML
```

**Vikram's notes — prerender implementation (approach deviation from audit):**

Audit assumed "repo is already on RR7, no migration needed" for framework-mode `prerender`. That's wrong: `package.json` has `react-router-dom` (library mode, `BrowserRouter`/`<Routes>` in `src/App.tsx`, mounted via `createRoot` in `src/main.tsx`) — no `@react-router/dev` anywhere in the tree. Migrating ~60 routes (including protected `/brand/*`, `/creator/*`, `/admin/*`) to framework mode for 16 marketing routes is exactly the invasive move the task brief warned against. Also, the brief assumed Playwright was a dev dependency — it isn't (`@playwright/test` absent from `package.json`; only an orphaned partial install in `node_modules`). Went with **option (B): post-build snapshot script**, using `puppeteer-core` (a real, already-approved devDependency — see `ci/lighthouse-meera.mjs` for the same resolve-local-Chrome pattern, reused here with zero new installs).

- `scripts/prerender.mjs` (new): after `vite build`, spawns `vite preview`, drives headless Chrome to each of the 16 marketing routes, waits for the `Seo` component's React-19-hoisted `<title>`/meta to land, de-dupes singleton head tags (title/description/canonical/OG/Twitter — React doesn't strip the static defaults baked into `index.html`, so a naive snapshot doubles them), and writes `dist/<route>/index.html`.
- `package.json`: added `"postbuild": "node scripts/prerender.mjs"` — runs automatically after `npm run build`.
- `public/_redirects`: SPA-fallback wildcard now points at `dist/app-shell.html` (a pristine, content-free copy of the pre-prerender bootstrap shell that the script writes every build) instead of `dist/index.html`. Needed because `dist/index.html` is now real prerendered "/" content, and `robots.txt` has a blanket `Allow: /` with no disallow for `/brand/*`/`/creator/*`/`/admin/*` — without this fix a crawler hitting a private-zone URL with no physical file would get served the landing page's title/canonical/JSON-LD mislabeled under that URL.
- **Verified:** clean `npm run build` → 16/16 routes prerendered, exit 0. Every route has exactly 1 `<title>`, 1 canonical, 1 meta description (spot-checked `/pricing`, `/features/escrow`, 3 blog posts). Real body H1/prose present (e.g. "Every Influora deal is paid through escrow" on `/features/escrow`, FAQPage/Article JSON-LD present, 2 schema blocks per page). `dist/brand`, `dist/creator`, `dist/admin` do not exist — SPA zones untouched. `npx tsc --noEmit` clean. No leaked `vite preview` processes after run (Windows process-tree kill via `taskkill /T`).
- **Known gap, not in scope here:** `/terms`, `/privacy`, `/support` prerender fine but keep the generic site-wide title/description because those pages have no `<Seo>` component (`StaticPage` placeholder shells) — that's GEO-TECHNICAL-AUDIT.md **H3** / action-plan item **#9** (wire orphaned `LegalPage.tsx`), already tracked separately, not part of this handoff.

**§ Ishaan brief — AEO content rewrites (~8h total)**

**Why:** AI Overviews (Google's AI answer blocks) pull 73% of citations from pages with question-formatted H2s + direct 40-60 word answers. Right now only `/pricing` is ready (11-question FAQ with schema). Need to fix 4 more high-value pages + create the highest-volume orphan-query comparison post.

**Tasks (approve each with Nisha before implementing):**

1. **landing.tsx** (~1.5h) — Add FAQ section before footer (line ~472):
   - 5-question accordion: "What is Influora?", "How does escrow work?", "What is a Deal Room?", "Do I need a subscription?", "How long does payout take?"
   - Each answer: 40-60 words, direct, CTA-driven per audit guidance
   - Add `getFaqPageSchema([...])` below the accordion (already imported line 20)

2. **features/escrow.tsx** (~30min) — Reframe 2 H2s to question format:
   - Line 140: "Why escrow matters" → "Why does escrow matter for influencer deals?"
   - Add new H2 before line 169 security section: "Is escrow safe for large payments?" (40-word answer re: licensed payment partner)
   - Keep line 185 "What happens in a dispute?" (already good)

3. **how-it-works-brands.tsx** (~45min) — Wrap step groups in question H2s:
   - Steps 1-2 under "How do I create a campaign on Influora?"
   - Steps 3-4 under "How does payment work through escrow?"
   - Steps 5-6 under existing headings as appropriate
   - Pattern: `<section><FadeUp><h2>Question?</h2></FadeUp><StaggerContainer>{STEPS.slice(a,b)...}</StaggerContainer></section>`

4. **how-it-works-creators.tsx** (~45min) — Same treatment as brands page, group 6 steps under 3 question H2s

5. **Blog: "Best Influencer Marketing Platforms in India"** (~5h research + write) — NEW FILE
   - Path: `src/content/blog/best-influencer-marketing-platforms-india-2026.md`
   - Length: 2,000 words
   - Target query: "best influencer marketing platforms india" (1,100/mo volume per audit)
   - Format: Honest comparison listicle (compare 5-7 platforms including Influora + 4-6 competitors/agencies)
   - Tone: Per pricing.tsx "When does Pro make sense?" framing — not pure sales pitch, help reader choose
   - Include: comparison table (pricing, escrow yes/no, creator tiers, min spend), backlink to /pricing
   - Frontmatter: title, description (<160 chars), publishedAt, updatedAt, author, tags
   - SEO: Primary keyword in H1, secondary in H2s, internal links to /features/escrow + /pricing + /how-it-works/brands

**Approval flow:** Draft → Nisha review (tone + brand voice) → Aditya review (SEO check: keyword density, H2 structure, internal links, FAQ schema correct) → implement → Kavya QA → sitemap auto-update (add new blog post URL).

**Output:** 5 modified pages + 1 new blog post. Expected impact: AI Overviews citations 0→3-5 in 90 days (for "how does escrow work influencer", "how to run influencer campaign india"); organic position 3-5 for comparison post in 60-90d post-prerender (200-300 monthly visits).

---

## TASK: TrendSpark "smart AI" — LLM Recovery Tagger

**Owner:** Arjun (Eng Lead) · **Feature:** recover trends the deterministic n8n
tagger drops, by mapping free text onto the closed theme/campaign vocabulary
with a cheap Haiku call. Spans Backend + Security + Frontend.

**Why:** `theme-tagger.js` returns `themes:[]` on any unseen text → n8n drops the
row → good trends silently lost. This pass rescues them onto the SAME closed
vocab; it can only add correctly-tagged trends, never write garbage.

### Handoffs

```
FROM Arjun → Vikram | Build recovery tagger (Python FastAPI) | app/routes/trend_tag.py, app/prompt/trend_tag.py, app/config.py, app/main.py | DONE | Haiku, static-secret auth, spend gate, closed-vocab validation, fail-closed drop
FROM Vikram → Kabir | Security audit of /internal/trendspark/tag | docs/security/trendspark-recovery-tagger-audit.md | PASS | static-secret = accepted v1 debt (T-DEBT-1); rotate quarterly + network-bind
FROM Arjun → Ananya | AI-recovered trend transparency chip | src/components/trendspark/ThemeProvenanceBadge.tsx, src/lib/api.ts (optional themeSource), TrendSparkNudgeCard.tsx | DONE | backward-compatible optional field; renders only for AI_RECOVERED
FROM Ananya → Meera | Verify | tests | PASS | influora-ai: 274 passed (21 new tagger tests); frontend badge Vitest: 3 passed; tsc clean
FROM Kabir → Arjun | Security gate | — | APPROVED | cleared for production; debt tracked
```

### Pipeline status

| Stage | Owner | Status |
|-------|-------|--------|
| Architecture fits closed-vocab schema-lock | Priya (via existing lock) | ✅ conforms |
| Backend — recovery tagger + prompt + config | Vikram | ✅ DONE |
| Frontend — provenance badge | Ananya | ✅ DONE |
| QA / build | Meera | ✅ 274 + 3 tests green, tsc clean |
| Security audit (OWASP + adversarial) | Kabir | ✅ PASS |
| Sign-off | Arjun | ✅ ready |

---

## TASK: TrendSpark n8n pipeline fixes (Dev, Priya-approved)

```
FROM Dev → Priya   | n8n review: 6 fixes need arch/schema sign-off | trendspark/n8n/trend-pull-workflow.json | APPROVED | see rulings below
FROM Priya → Dev   | Sign-off | V20260716120000__trends_theme_source.sql, Trend.java, TrendThemeSource.java | APPROVED | timestamp migration; theme_source additive+defaulted; DB unique key DEFERRED (needs legacy cleanup); UTC standard confirmed
FROM Dev → Kavya   | n8n pipeline fixes | trendspark/n8n/trend-pull-workflow.json (Normalize, Theme Tagger, INSERT, DELETE, 3x HTTP), + migration/entity/enum | READY | simulated green (5 cases); theme-tagger self-test PASS
```

**What Dev shipped:**
- **Recovery tagger WIRED:** Theme Tagger node now POSTs `themes:[]` trends to
  `/internal/trendspark/tag` (Bearer `$TREND_TAG_INGEST_SECRET`, base `$INFLUORA_AI_INTERNAL_URL`),
  fail-closed + capped at `$TREND_TAG_MAX_RECOVERY_PER_RUN` (40); on `recovered:true`
  writes `themes/campaign_type/peak_window_days` + `theme_source='AI_RECOVERED'`.
- **NewsAPI category pollution fixed** (`'entertainment'` → `''`).
- **Within-run dedup** by `region|detected_date|lower(trend_text)`.
- **UTC everywhere** (row stamps `getUTC*`; DELETE `UTC_TIMESTAMP(6)`).
- **HTTP source resilience** (`onError: continueRegularOutput` on TMDb/NewsAPI/YouTube).
- **Schema:** `trends.theme_source VARCHAR(16) NOT NULL DEFAULT 'KEYWORD'`; entity + `TrendThemeSource` enum.

### Deferred / ops (tracked)

- **DB uniqueness** on `trends` natural key (`region + detected_date + normalized trend_text`):
  deferred to a follow-up migration after a one-time legacy-dup cleanup (Priya).
- **Ops:** set `TREND_TAG_INGEST_SECRET` + `INFLUORA_AI_INTERNAL_URL` in the n8n env;
  network-bind the endpoint; add the secret to the quarterly rotation table.
- **Guide reconciliation:** the backend guide says "never use legacy V51 style" but the
  repo has both — recent migrations use timestamps (current convention). Update the guide.
- **Kavya/Meera:** run the workflow in n8n staging once the env vars are set (live n8n
  run can't be exercised from here).

---

## TASK: fix/remaining-partial-broken — full-stack local verification

**Owner:** Arjun (Eng Lead) · **Scope:** independent verification of uncommitted
fixes spanning influora-ai (chat.py, loop.py), influora-api (ScoreCalculationJob,
MeeraController, application-prod.yml), and frontend (Meera chat/orb components,
useMeeraStream, creator affiliate-earnings/coupons).

### Handoffs

```
FROM Arjun → Meera | Independent local verification, branch fix/remaining-partial-broken | influora-ai/, influora-api/, src/ (21 modified + 3 new files) | ✅ VERIFIED | see report below; 1 pre-existing unrelated frontend gap flagged separately
```

### Meera Verification Report — 2026-07-17

| Step | Command | Exit | Result |
|------|---------|------|--------|
| 1. Python tests | `PYTHONUTF8=1 python -m pytest tests -q` (influora-ai) | 0 | ✅ 301 passed, 0 failed (initial run) → re-ran after concurrent P1 edits landed (config.py, pricing.py, gemini.py, voice.py, brand_safety.py, evals/): **316 passed, 0 failed**, no churn/regressions |
| 2. Frontend typecheck | `npx tsc --noEmit -p tsconfig.json` | 0 | ✅ 0 errors |
| 3. Frontend build | `npm run build` | 0 | ✅ built in 51.5s (only pre-existing chunk-size warnings, no errors) |
| 4. Frontend unit tests | `npx vitest run --reporter=basic --exclude '**/.claude/**' src` | 1 | ⚠️ 17/19 files passed, 188/193 tests passed. 5 failures in `BrandProfile.test.tsx` (4) + `creator-wallet.test.tsx` (1) — **confirmed pre-existing, unrelated to this diff** (`git status` shows neither file touched by the changeset). Root cause: relative-URL `fetch()` in `src/admin/services/api-contracts.ts` throws under jsdom/undici. Flagged as separate follow-up task (not blocking this pipeline). |
| 5a. Java compile | `mvn -o compile` | 0 | ✅ BUILD SUCCESS |
| 5b. Java test-compile | `mvn -o test-compile` | 0 | ✅ BUILD SUCCESS |
| 5c. Java targeted tests | `mvn -o test -Dtest=ScoreCalculationJobTest,SpringJwksKeyServiceTest,ConfigurationPropertiesRegistrationTest,AnalyticsServiceTest -DfailIfNoTests=false` | 0 | ✅ Tests run: 25, Failures: 0, Errors: 0, Skipped: 0 |
| 6. n8n JSON sanity | `python -c "json.load(...trend-pull-workflow.json...)"` | 0 | ✅ valid json |

### VERDICT: ✅ VERIFIED — all changed-file-relevant checks pass.

Note: step 4's 5 failing tests are a pre-existing baseline gap (unrelated files,
untouched by this diff) — spawned as a separate follow-up, not routed back to a
developer for this task.

## 2026-07-17 — dev: AI eval harness (P1, offline golden sets)
NEW influora-ai/evals/ — golden-set eval loop for the 3 live AI features (GARM brand-safety, analyze-site classify, trend-tag). Offline mode green out of the box (`PYTHONUTF8=1 python evals/run_eval.py --offline all`), CI gate at tests/evals/ (14 tests pass; full suite 330 pass). Live Sonnet-vs-Haiku GARM A/B procedure + parity bar (F1 within 2pts AND zero unsafe->safe misses) documented in influora-ai/evals/README.md. No app/ files touched.

---

## TASK: GEO Audit Follow-Up — AEO Content Rewrites + Comparison Post

**Owner:** Aditya (SEO Lead) → **Assignee:** Ishaan (Content Writer)

**Context:** GEO-TECHNICAL-AUDIT.md shows current technical score 47/100. While Vikram ships prerender (item #5, unblocks Bing/ChatGPT/AI Overviews), Ishaan rewrites 5 pages for AI Overviews readiness + creates 1 new comparison post for the highest-value orphan query.

### Handoff

```
FROM Aditya → TO Ishaan | AEO content rewrites (5 pages) + comparison post | GEO-TECHNICAL-AUDIT.md §4-5, src/pages/*.tsx, src/content/blog/ | ASSIGNED | see tasks below
```

### Tasks (total effort: ~8h 15min)

#### 1. Question-H2 + FAQ rewrites (5 pages, 2h 15min)

**Why:** Google AI Overviews and ChatGPT search prioritize question-formatted content. Pages with question H2s and FAQPage schema rank 3× higher in AI answer blocks.

**Per-page work:**

##### `/` (landing page) — 1h
- **File:** `src/pages/landing-page.tsx` (or wherever landing H2s live)
- **Add:** 5-question FAQ accordion at bottom of page, BEFORE footer
- **Questions to add:**
  1. "What is Influora?" → one-sentence escrow platform answer
  2. "How does escrow protection work for influencer payments?" → brand funds held until deliverables approved
  3. "Is Influora free to use?" → Free tier yes, Pro tier ₹4,999/mo
  4. "What happens if a creator doesn't deliver?" → dispute resolution, brand recovers funds
  5. "Which platforms does Influora support?" → Instagram, YouTube (check current platform coverage)
- **Add to code:** Import `getFaqPageSchema` from `src/lib/seo/schema.ts`, pass the 5 Q&A pairs to generate JSON-LD, include in `Seo` component
- **Existing H2s:** review and reframe any that can become questions (e.g., "Platform Benefits" → "Why use Influora for influencer marketing?")

##### `/features/escrow` — 15min
- **File:** `src/pages/features/escrow-page.tsx`
- **Current:** 1 of 3 H2s is question-formatted ("Why escrow matters for brands" section exists but NOT phrased as question)
- **Rewrite H2:** "Why escrow matters" → **"Why does escrow matter for influencer deals?"**
- **Add H2 + FAQ entry:** **"Is escrow safe for large payments?"** → answer: yes, funds held in regulated accounts, dispute resolution process, cite any compliance/security standards we have
- **Existing FAQPage schema:** already present; add the new Q&A to it

##### `/how-it-works/brands` — 30min
- **File:** `src/pages/how-it-works/brands-page.tsx`
- **Current:** 0 question H2s; step-by-step flow with descriptive headings
- **Reframe:** Wrap the step groups in question H2s:
  - "How do I create a campaign on Influora?" (covers discovery → brief → outreach steps)
  - "How does payment work through escrow?" (covers funding → deliverable approval → payout)
  - "What happens after a creator delivers content?" (covers approval flow)
- **Add:** FAQPage schema with these 3 Q&As (import `getFaqPageSchema`)

##### `/how-it-works/creators` — 30min
- **File:** `src/pages/how-it-works/creators-page.tsx`
- **Current:** 0 question H2s
- **Reframe:** Same treatment:
  - "How do I join a brand deal on Influora?" (covers signup → pitch → negotiation)
  - "When do I get paid?" (covers deliverable submission → approval → payout timing)
  - "What if a brand rejects my deliverable?" (covers revision/dispute flow)
- **Add:** FAQPage schema with these 3 Q&As

##### `/pricing` — 0h (already AEO-ready)
- **Current state:** 11 question H2s, FAQPage schema present
- **Action:** NONE — audit confirmed this is the best AEO page on the site

#### 2. llms.txt cleanup — 5min
- **File:** `public/llms.txt`
- **Remove:** Line 26 (`/features/contracts` URL reference) — page is 404, removed from sitemap
- **Note:** Aditya verified the rest of llms.txt is exemplary (covers escrow model, Deal Room, Hype Campaigns, pricing tiers, India focus, all key facts present). No other changes needed.

#### 3. New comparison post (highest-value orphan) — 6h
- **Target keyword:** "best influencer marketing platforms india" (~1,100 search volume/month, B2B intent)
- **Format:** 2,000-word listicable comparison post
- **File:** Create `src/content/blog/best-influencer-marketing-platforms-india-2026.md`
- **Structure:**
  - **Title:** "Best Influencer Marketing Platforms in India 2026: Comparison Guide"
  - **Meta description:** "Compare the top influencer marketing platforms in India — features, pricing, creator networks, and escrow protection. Updated 2026."
  - **H1:** Same as title
  - **Intro (200 words):** What to look for in a platform (escrow, creator quality, pricing transparency, campaign tools), why India market is unique
  - **Comparison table:** Platform | Escrow | Pricing | Creator Tiers | Best For
  - **Platform deep-dives (5-7 platforms, 250 words each):**
    1. **Influora** (us — lead with this, most detail) — escrow model, Deal Room, Hype Campaigns, Free vs Pro, unique angle: only platform with mandatory escrow on every deal
    2. **Qoruz** — focus on data/analytics
    3. **Plixxo** (if still active) — network size
    4. **IPLIX** — agency hybrid
    5. **TACK** — UGC focus
    6. *Add 1-2 more if research finds them credible*
  - **Per platform:** what they do well, pricing (if public), gaps/downsides, best use case
  - **Question H2s throughout:**
    - "Which platform has the best creator network in India?"
    - "Do all platforms offer escrow protection?" (answer: no, only Influora mandates it)
    - "What's the cheapest influencer marketing platform in India?" (answer: most are % commission; Influora Free tier is pay-per-deal)
    - "How do I choose the right platform for my brand?"
  - **Conclusion (150 words):** Summary table, recommendation by use case
  - **Add FAQPage schema** with the 4 question H2s above
- **Research:** Check each competitor's current site for factual accuracy (don't rely on memory; verify pricing/features from their live pages if public)
- **Link internally:** to `/pricing`, `/features/escrow`, `/how-it-works/brands` where relevant
- **Tone:** Neutral comparison (we're one option, not "the best" — let the escrow differentiator speak for itself)

#### 4. Sitemap addition (0min — Aditya will handle)
Once the comparison post is written, Aditya will add it to `public/sitemap.xml` along with the other 2 blog posts and `/contact`.

### Deliverables

1. **Updated pages** (4 files): landing, escrow, how-it-works/brands, how-it-works/creators — with question H2s and FAQPage schema
2. **Updated llms.txt**: `/features/contracts` reference removed
3. **New blog post**: `best-influencer-marketing-platforms-india-2026.md` (2,000 words, comparison table, question H2s, FAQ schema)

### Approval flow

- Submit all rewrites to **Nisha** for content approval
- Once approved, flag to **Aditya** for final SEO review (meta descriptions, keyword placement, schema validation)
- After Aditya's sign-off, Nisha queues for publishing

---

## TASK: GEO Audit Follow-Up — Quick-Win Code Fixes (Items 1-4)

**Owner:** Aditya (SEO Lead) → **Assignee:** Ananya (Frontend Developer)

**Context:** GEO-TECHNICAL-AUDIT.md items #1-4 are <3hr quick wins that ship today and stop the bleeding for non-JS AI crawlers. Item #5 (RR7 prerender) is a 1-2 day task routed to **Vikram** (not you — he'll handle that separately).

### Handoff

```
FROM Aditya → TO Ananya | GEO quick-win code fixes (items 1-4, <3hr total) | GEO-TECHNICAL-AUDIT.md, index.html, public/sitemap.xml, public/_redirects, public/og-image.png | ASSIGNED | see tasks below
```

### Tasks (total effort: <3h)

#### 1. Replace bare `index.html` head + static fallback content — 1h (item #1, C1c)

**Why:** Currently all AI crawlers see a blank shell with wrong title "Creator OS - Brand Dashboard", no meta/OG, dead favicon `/vite.svg`. This fix gives them correct brand info and fallback content TODAY (prerender later makes this perfect).

**File:** `index.html`

**Changes:**

##### Head section (lines 1-17) — replace entirely:
```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Influora — Escrow-Protected Influencer Marketing Platform for India</title>
    <meta name="description" content="Connect with verified Indian creators and protect every collaboration with escrow payments. Influora is the influencer marketing platform built for D2C brands and SMBs in India." />
    <link rel="canonical" href="https://influora.in/" />
    <meta property="og:title" content="Influora — Escrow-Protected Influencer Marketing" />
    <meta property="og:description" content="India's influencer marketing platform with mandatory escrow on every deal. Brands pay only for delivered work." />
    <meta property="og:url" content="https://influora.in/" />
    <meta property="og:type" content="website" />
    <meta property="og:image" content="https://influora.in/og-image.png" />
    <meta name="twitter:card" content="summary_large_image" />
    <meta name="twitter:title" content="Influora — Escrow-Protected Influencer Marketing" />
    <meta name="twitter:description" content="India's influencer marketing platform with mandatory escrow on every deal." />
    <meta name="twitter:image" content="https://influora.in/og-image.png" />
    <!-- Favicon will be added by build process or via public/ assets -->
    <script type="application/ld+json">
    {
      "@context": "https://schema.org",
      "@type": "Organization",
      "name": "Influora",
      "url": "https://influora.in",
      "logo": "https://influora.in/logo.png",
      "description": "Escrow-protected influencer marketing platform for India",
      "foundingDate": "2024",
      "sameAs": []
    }
    </script>
  </head>
```

##### Body `<div id="root">` — add static fallback content (non-JS crawlers will see this):
Find `<div id="root"></div>` and replace with:
```html
    <div id="root">
      <noscript>
        <h1>Influora — Escrow-Protected Influencer Marketing for India</h1>
        <p>Connect with verified creators and protect every collaboration with escrow-held payments. Brands pay only for delivered work. Creators are guaranteed payment for completed deliverables.</p>
        <nav>
          <a href="/how-it-works/brands">For Brands</a> | 
          <a href="/how-it-works/creators">For Creators</a> | 
          <a href="/pricing">Pricing</a> | 
          <a href="/features/escrow">Escrow Protection</a>
        </nav>
      </noscript>
    </div>
```

**Note:** React will replace this on client-side render; non-JS crawlers see the fallback.

#### 2. Sitemap cleanup — 30min (items #2, C2, C3)

**File:** `public/sitemap.xml`

**Remove these 6 dead URLs** (verified 404s or soft-404s per audit):
- `https://influora.in/features/contracts`
- `https://influora.in/kyc`
- `https://influora.in/tds`
- `https://influora.in/refund-policy`
- `https://influora.in/guidelines/creators`
- `https://influora.in/guidelines/brands`

**Add these 4 missing URLs:**
```xml
  <url>
    <loc>https://influora.in/contact</loc>
    <lastmod>2026-07-17</lastmod>
    <changefreq>monthly</changefreq>
    <priority>0.6</priority>
  </url>
  <url>
    <loc>https://influora.in/blog/how-to-pay-influencers-safely-india-2026</loc>
    <lastmod>2026-07-17</lastmod>
    <changefreq>monthly</changefreq>
    <priority>0.7</priority>
  </url>
  <url>
    <loc>https://influora.in/blog/what-is-escrow-in-influencer-marketing</loc>
    <lastmod>2026-07-17</lastmod>
    <changefreq>monthly</changefreq>
    <priority>0.7</priority>
  </url>
  <url>
    <loc>https://influora.in/blog/micro-influencer-pricing-guide-india-2026</loc>
    <lastmod>2026-07-17</lastmod>
    <changefreq>monthly</changefreq>
    <priority>0.7</priority>
  </url>
```

**Result:** 18 URLs → 16 URLs (6 removed, 4 added).

#### 3. Add `_redirects` SPA fallback — 15min (item #3, H4)

**Why:** On Netlify/Vercel-style hosts, deep links like `/pricing` currently hard-404 because there's no physical `pricing.html` file. This tells the host to serve `index.html` for all routes (SPA fallback).

**File:** Create `public/_redirects`

**Content:**
```
/*  /index.html  200
```

**That's it.** One line. Netlify/Vercel/Cloudflare Pages all respect this format.

#### 4. Ship `og-image.png` placeholder — 1h (item #4, H2)

**Why:** Every page's `og:image` and Article schema points to `https://influora.in/og-image.png` but the file doesn't exist (404). This breaks social sharing and AI engine image display.

**File:** **Do NOT create the actual image** — route this to **Zara** (Graphics Designer).

**Your task:**
1. Check if `public/og-image.png` exists (it shouldn't per the audit).
2. Write a task handoff to **Zara** in SHARED_CONTEXT.md:
   ```
   FROM Ananya → TO Zara | Create og-image.png (1200×630) | public/og-image.png | ASSIGNED | Brand: Influora; text: "Influora — Escrow-Protected Influencer Marketing"; tagline: "Built for India"; brand colors; must be 1200×630 PNG
   ```
3. For NOW (so the site doesn't 404), create a temporary **solid color placeholder**:
   - Use any simple image tool or code to generate a 1200×630 PNG
   - Solid color (e.g., Influora brand primary color if you know it, or neutral gray #1a1a1a)
   - Save as `public/og-image.png`
   - Commit with message "Add temporary og-image.png placeholder (pending Zara's design)"
   - Zara will replace this with the real branded image

**Alternative if you prefer code:** Use this inline in a scratch file to generate a placeholder PNG via canvas:
```js
// Run this in Node or browser console to generate a placeholder
const { createCanvas } = require('canvas'); // npm install canvas
const fs = require('fs');
const canvas = createCanvas(1200, 630);
const ctx = canvas.getContext('2d');
ctx.fillStyle = '#1a1a1a';
ctx.fillRect(0, 0, 1200, 630);
ctx.fillStyle = '#ffffff';
ctx.font = 'bold 48px sans-serif';
ctx.textAlign = 'center';
ctx.fillText('Influora', 600, 300);
const buffer = canvas.toBuffer('image/png');
fs.writeFileSync('public/og-image.png', buffer);
```

But honestly a solid-color 1200×630 PNG from any tool is fine for now.

---

## MEERA VERIFICATION — admin_audit_log source column (Kabir red-team 2.1) — 2026-07-18 (CORRECTED)

```
FROM Arjun → Meera | Local verify: AdminAuditLogSource enum + V20260718140000 migration + AuditLogControllerTest edits | influora-api/src/main/resources/db/migration/V20260718140000__admin_audit_log_source.sql, .../domain/enums/AdminAuditLogSource.java, .../domain/entity/AdminAuditLog.java, .../web/dto/admin/AdminAuditLogDtos.java, .../service/admin/AdminAuditLogService.java, influora-api/src/test/java/.../AuditLogControllerTest.java, src/admin/** | ✅ FULL PASS — frontend + backend both green | CORRECTION: earlier "no Maven" claim was wrong — repo-bundled offline Maven exists at influora-api/.tools/apache-maven-3.9.10/bin/mvn.cmd and was used successfully
```

**CORRECTION NOTE:** the 2026-07-18 entry above this one incorrectly reported backend as BLOCKED-no-maven. That was a miss — this repo bundles its own Maven at `influora-api/.tools/apache-maven-3.9.10/bin/mvn.cmd`, runnable fully offline (`-o` flag) against the local `~/.m2` cache. Re-ran with the bundled binary; results below are real compiler/JUnit output, not manual read-through.

**Results:**
- Frontend `tsc --noEmit`: PASS (exit 0) — unchanged from prior report
- Frontend `npm run build`: PASS (built 1m3s + postbuild prerender 16/16 routes) — unchanged from prior report
- Frontend `vitest run src/admin`: PASS (5 files, 145/145 tests) — unchanged from prior report
- Backend `mvn -o compile` (bundled Maven, offline): **PASS**, exit 0, zero errors. Compiles the new `AdminAuditLogSource` enum, `AdminAuditLog.source` field, `AuditLogEntryDto.source` field, `@NotNull expectedEffectiveDate` on `UpdatePlatformFeeConfigRequest`, and the unconditional check in `PlatformFeeAdminService` cleanly.
- Backend `mvn -o test -Dtest=AuditLogControllerTest,PlatformFeeServiceTest,CreatorPlatformFeeServiceTest,CreatorPlatformFeeControllerTest,AdminDashboardServiceTest,AdminDashboardStatsCacheTest` (offline): **PASS** — Tests run: 20, Failures: 0, Errors: 0, Skipped: 0 (AuditLogControllerTest 8, PlatformFeeServiceTest 6, CreatorPlatformFeeServiceTest 2, AdminDashboardServiceTest 1, AdminDashboardStatsCacheTest 2, CreatorPlatformFeeControllerTest 1). All 6 matched classes are plain `@ExtendWith(MockitoExtension.class)` unit tests — none are `@SpringBootTest`, so no Docker/testcontainers dependency; nothing BLOCKED-no-docker this pass.
- Migration sanity: PASS by manual read (unchanged from prior report) — not executed against a live DB, no DB available here; DDL is correct MySQL syntax and version-numbered without collision.

**VETO: not exercised — full PASS.** Frontend and backend both compile and test green under the bundled offline Maven. Cleared to proceed to Priya/Kabir per pipeline (this note only covers Meera's local-verification gate, not security or product sign-off).


#### 5. Item #5 (RR7 prerender) — NOT YOUR TASK

**Note:** GEO-TECHNICAL-AUDIT.md item #5 (React Router 7 prerender, 1-2 days) is routed to **Vikram** (Backend Developer), not you. He'll handle `react-router.config.ts` with `ssr:false` + `prerender` list of marketing routes. That's the big fix that unblocks Bing/ChatGPT/AI Overviews. Your 4 tasks above are the quick wins that ship TODAY.

### Deliverables

1. **Updated `index.html`**: correct brand meta + static fallback content in `#root`
2. **Updated `public/sitemap.xml`**: 6 dead URLs removed, 4 real URLs added (net: 18→16)
3. **New `public/_redirects`**: one-line SPA fallback
4. **Temp `public/og-image.png`**: 1200×630 placeholder (Zara will replace with real design)
5. **Handoff to Zara**: og-image design task written to SHARED_CONTEXT.md

### QA flow

- After your changes, submit to **Kavya** for QA review
- After Kavya's approval, **Meera** will verify build (`npm run build`, local preview)
- After Meera's sign-off, **Aditya** will verify sitemap integrity and meta tags via curl/WebFetch

---

- **Kabir (Red-Team, 2026-07-18):** Security design for enabling Meera on-behalf tool auth -> `docs/security/meera-onbehalf-auth-security-design.md`. Verdict: design GO, NO-GO to flip `VITE_API_MODE=live`/write tools until 3 must-fixes land. Top risk: on-behalf credential is the full user access token read from XSS-readable `localStorage.brand_token` (`MeeraChatPanel.tsx:327`), regressing the H-30 in-memory-token control (`token-store.ts`) — this is also the likely real reason tools currently degrade to text. Also: stream-token single-use documented-not-enforced (`service_token.py`); `conversation_id` not tenant-checked on the tool path (`MeeraInternalController.java`).

---

## MEERA VERIFICATION — Brand deal-room rebuild — 2026-07-18

```
FROM Meera → Priya | Local build verify (post-Kavya QA) | src/App.tsx, src/components/brand/deals/deal-room-dashboard.tsx | ✅ PASS | branch feat/portfolio-view-tracking, main tree — cleared to score aligned
```

**Results:**
- `npm run build` (production, authoritative typecheck gate): **PASS**, exit 0. Built in 55.64s + postbuild prerender 16/16 marketing routes. Zero TS/bundler errors. Only pre-existing non-blocking warnings: duplicate `baseUrl` key in `tsconfig.json` (unrelated, pre-existing), chunk-size warning on `index-*.js` (2.5MB, pre-existing, not from this diff).
- Dev-server spot-check (`npm run dev`, port 3000) → `/brand/deals?demo=true`: **mounted cleanly**. Rendered: heading "Deal Rooms", badge/subtitle "All campaigns · All offers", descriptive line "Every creator offer, across all campaigns — accept, counter, or reject in one place.", "Start New Deal" button, search box, "All Status" filter combobox. No backend running → deal list correctly falls to expected empty/error state ("Could not load deal rooms. Check your connection and retry.") — matches spec, not a crash.
- Console: **zero errors** (`read_console_messages` onlyErrors: "No console logs."). Only vite HMR/dev noise on the unfiltered log.

**VETO: not exercised — full PASS.** Deal-room rebuild cleared to score aligned.


---

## MEERA VERIFICATION — Wave-3 frontend mock-page wiring — 2026-07-18

```
FROM Meera → Priya | Wave-3 frontend local verification (4 checks) | src/pages/brand-pipeline.tsx, brand-chat.tsx, creator-chat.tsx, creator-active.tsx, brand-messages.tsx, src/components/brand/deals/deal-room-dashboard.tsx | ALL PASS + 1 scope note | not blocking
```

**CHECK #1 — scope discipline: PASS, with a note.** `git diff --stat -- src/` shows 52 modified/added/deleted files, not just the 4 named pages — but the 6 files matching Wave-3 (`brand-pipeline.tsx` +180/-, `brand-chat.tsx` +157/-, `creator-chat.tsx` +262/-, `creator-active.tsx` +335/-, `brand-messages.tsx` +294/-, `deal-room-dashboard.tsx` +624/-) are exactly the expected wiring targets. The rest of the working-tree diff belongs to other in-flight tasks already tracked elsewhere on this bus (Wave-2 Meera streaming: `useMeeraStream.ts`, `meera-api.ts`, `MeeraChatPanel.tsx`, `Stage*.tsx`; portfolio-view-tracking: `deal-room-dashboard.tsx` overlap + `api.ts` escrow additions; marketing mobile-nav: `SiteHeader.tsx`; admin audit-log: `src/admin/**`) — none of it is Ananya's Wave-3 work.
`src/lib/api.ts` **is** modified (34 lines) — content-checked: adds `EscrowHoldRow` type + `wallet.escrowList()` and removes the dead `portfolio.mediaKitUrl()` (404 endpoint, per `wiki/tech/MEDIA-KIT-SCOPE.md`). This is escrow/wallet + media-kit-removal work tied to the portfolio-view-tracking thread, not a Wave-3 page touching a shared file. No edits to `api.ts` are attributable to the 4 Wave-3 pages' diffs themselves (verified each page-file diff independently, none touch `api.ts`).
**Net: no ownership-rule violation found for Wave-3 itself** — but flagging that the working tree is not isolated per-task, so this diff-stat check alone can't prove attribution; it's a content-based inference, not a per-commit one.

**CHECK #2 — typecheck: PASS.** `npx tsc --noEmit -p tsconfig.json` → exit 0, 0 errors.

**CHECK #3 — tests: PASS.** `npx vitest run` → 19 test files, **193/193 passed**, 0 failures. Confirmed no dedicated test files exist for the 4 wired pages (`brand-pipeline`, `brand-chat`, `creator-chat`, `creator-active`) — expected, none regressed anything elsewhere. New `src/hooks/useMeeraStream.test.ts` (7 tests) included and green — that's Wave-2 collateral, not Wave-3, but passing.

**CHECK #4 — build: PASS.** `npm run build` → built in 1m7s, 0 errors (only pre-existing >500kB chunk-size warnings). `postbuild` prerender: 16/16 marketing routes snapshotted successfully.

**VETO: not exercised — full PASS.** Headline: Wave-3 frontend is green. Scope is clean for the 4 target pages; the one shared file in the tree (`api.ts`) traces to a different task's escrow/media-kit work by content, not to Ananya's page wiring.

---

## MEERA VERIFICATION — admin+voice build (creator-tier-override + admin-error-log) — 2026-07-18

```
FROM Arjun → Meera | Local verify: full-stack build/test after admin+voice landing | influora-api (compile+targeted tests), src/ (tsc+build+vitest), V20260718160000__creator_tier_override.sql, V20260718170000__admin_error_log.sql | ✅ ALL PASS | cleared for next gate
```

| Check | Command | Result |
|---|---|---|
| Frontend tsc | `npx tsc --noEmit` | ✅ PASS, exit 0, 0 errors |
| Frontend build | `npm run build` | ✅ PASS, built in 55.10s + postbuild prerender 16/16 routes. Only pre-existing >500kB chunk-size warnings, no errors |
| Frontend tests | `npx vitest run src/admin src/hooks` | ✅ PASS, 6 files, 152/152 tests |
| Backend compile | `mvn -o -q compile` (bundled offline Maven) | ✅ PASS, exit 0, no errors |
| Backend tests | `mvn -o test -Dtest=<49 classes matching Admin/Support/Campaign/Brand/Creator/Error/Email/Meera/Voice/PlatformFee/AuditLog>` | ✅ PASS, **373 tests run across 49 classes, 0 failures, 0 errors, 0 skipped**. Verified none of the 49 matched classes carry a real `@SpringBootTest` annotation (grep hits were doc-comments only) — nothing BLOCKED-no-docker this pass |
| Migration sanity | manual read | ✅ PASS — `V20260718160000__creator_tier_override.sql` and `V20260718170000__admin_error_log.sql` have unique version numbers, no collision with each other, V20260718140000, V20260718150000, or any V5x/V6x/timestamped migration. Correct `V<ts>__name.sql` naming. Valid MySQL DDL. `creator_profiles.application_rejection_reason` (referenced AFTER-anchor) confirmed in V38; `admin_users` FK target confirmed in V34 (runs before, safe ordering). Confirmed no `V20260718180000` email migration exists — feature reused existing `email_outbox` table per plan |

---

## MEERA VERIFICATION — Brand pipeline route + a11y fix — 2026-07-18

```
FROM Meera → Priya | Local build + mount verify (post-Kavya a11y QA) | src/App.tsx, src/pages/brand-pipeline.tsx | ✅ PASS | branch feat/portfolio-view-tracking, main tree — cleared to score aligned
```

**Results:**
- `npm run build` (production, authoritative gate): **PASS**, exit 0. Built in 51.34s + postbuild prerender 16/16 marketing routes. Zero TS/bundler errors. Only pre-existing non-blocking warnings: duplicate `baseUrl` key in `tsconfig.json`, chunk-size warning on `index-*.js` (2.6MB) — both unrelated to this diff.
- Dev-server mount check (`npm run dev`, port 3000) → `/brand/pipeline?demo=true`: **mounted cleanly** via the new `BrandLayoutWrapper` route in `App.tsx`. Rendered: "Pipeline" header, subtitle "Track all collaborations across stages", "New Collaboration" button, Board/List/Timeline tabs. No backend reachable in preview (`GET http://localhost:8080/api/v1/deals?status=all` → `net::ERR_CONNECTION_REFUSED`, confirmed via network log) → correctly fell to expected error card "Could not load the pipeline. Check your connection and retry." — matches spec (`VITE_API_MODE=live`, no mock fallback), not a crash.
- Console: **zero errors** (`read_console_messages` onlyErrors → "No console logs."). Only vite HMR/dev noise on the unfiltered log.
- Kavya's a11y additions (role/tabIndex/onKeyDown/aria-label on Card/tr/div across Board/List/Timeline views) did not affect build or mount — no new TS errors, no console warnings tied to those elements.

**VETO: not exercised — full PASS.** Brand pipeline page cleared to score aligned.

**VETO: not exercised — full PASS.** Frontend and backend both compile and test green under the bundled offline Maven; migrations are structurally sound. Cleared to proceed to next pipeline gate.

---

## TASK: Realtime messaging for brand-chat — backend SSE stream (Priya direct assignment)

**Owner:** Priya (architecture: SSE, consistent with the existing Meera `/chat` stream — NOT WebSocket)

```
FROM Vikram → Kavya | Backend SSE stream for deal messages | influora-api/src/main/java/com/influora/service/DealMessageStreamRegistry.java (new), influora-api/src/main/java/com/influora/service/DealService.java, influora-api/src/main/java/com/influora/web/DealController.java, + 3 test files below | READY for QA | see notes + frontend contract below
```

**What shipped:**
- `DealMessageStreamRegistry` (new `@Component`, `com.influora.service`): in-memory `ConcurrentHashMap<String dealId, CopyOnWriteArrayList<SseEmitter>>`. `register(dealId, emitter)` wires `onCompletion`/`onTimeout`/`onError` to deregister; `publish(dealId, DealMessageResponse)` sends a named `deal-message` SSE event to every live emitter for that deal, dropping any that throw. **Single-instance design, documented at the top of the file as a deliberate MVP call, not an oversight** — if the API ever runs >1 replica, brand/creator can land on different instances and miss each other's events; documented upgrade path is Redis pub/sub (or DB LISTEN/NOTIFY) keyed by dealId.
- `DealController`: new `GET /deals/{dealId}/messages/stream` → returns `SseEmitter`. Calls `dealService.authorizeMessageStream(principal, dealId)` FIRST — this reuses `DealService`'s existing `requireOwnedCollaboration` ownership check (the exact same one `GET /messages`/`POST /messages` already use, nothing new invented) — so an unauthorized caller gets the normal error response and no emitter is ever created/registered. Then constructs `new SseEmitter(DealMessageStreamRegistry.EMITTER_TIMEOUT_MS)` (30 min), registers it, sends a comment-only heartbeat (`:connected`, doesn't fire client `onmessage`).
- `DealService`: added `authorizeMessageStream(principal, dealId)` (thin wrapper, throws `ApiException` same as today). `sendMessage` now calls `messageStreamRegistry.publish(collaboration.getId(), response)` right after persisting, wrapped in a best-effort try/catch (publish failure never fails the already-succeeded send) — publishes the exact same `DealMessageResponse` DTO returned to the sender, so both parties' streams render identically.
- **Endpoint:** `GET /deals/{dealId}/messages/stream` (base path `/deals`, same controller as existing messaging endpoints). **Event shape:** `event: deal-message`, `data:` = JSON-serialized `DealDtos.DealMessageResponse` (id, dealId, kind, senderId, senderType, content, metadata, createdAt, readBy) — identical to what `GET /{dealId}/messages` already returns, per the "same DTO" requirement.
- **Frontend auth contract (flagging for Ananya, not implemented here):** browser must consume this via fetch-based SSE with a standard `Authorization: Bearer <token>` header (like `useMeeraStream`), NOT raw `EventSource` — do not add a token query param, standard header auth on the GET is correct and is what the backend expects.
- **One spec/reality note:** the task brief said "party-mismatch caller is rejected 403." The actual reused `requireOwnedCollaboration` check returns `DEAL_NOT_FOUND` / HTTP 404 for a caller who isn't the deal's owner (matches every other messaging endpoint's existing behavior — see `DealServiceTest`'s pre-existing `testSendMessageRejectsForeignWorkspace` etc.). Kept it exactly as-is per "reuse the exact authorization, don't invent a new one" — did not fabricate a 403 path. `WRONG_USER_TYPE` (403) still applies separately if the caller's account type isn't brand/creator at all.

**Tests added (all green):**
- `DealMessageStreamRegistryTest` (new, 6 tests): register+publish delivers to a live emitter; publish on an unregistered dealId is a no-op; a throwing emitter is dropped while others still receive; onCompletion/onTimeout/onError callbacks each deregister (no leak).
- `DealServiceTest` (+3 tests): `sendMessage` persists THEN publishes (`inOrder` verified) with the same DTO; `authorizeMessageStream` happy path for the owning creator; `authorizeMessageStream` rejects a brand on a foreign-workspace deal (404, `verifyNoInteractions(messageStreamRegistry)`).
- `DealControllerTest` (+2 tests): `streamMessages` authorizes THEN registers the emitter (`inOrder` verified); an unauthorized caller's thrown `ApiException` propagates with zero registry interaction (no emitter ever created).

**Build:** `mvn -o -q compile` → clean. `mvn -o test -Dtest=DealServiceTest,DealControllerTest,DealMessageStreamRegistryTest` → **36/36 passed, 0 failures/errors**. Full-module `mvn -o test` run in progress at handoff time — will post the result once it completes; nothing in this change touches any other controller/service so no cross-module risk expected.

**Next:** Kavya QA review, then hand to Meera for local verification (SSE smoke test — open stream, send a message from the other role, confirm event arrives) and Kabir if this counts as auth-touching (it reads the existing auth check, adds no new auth logic).

```
FROM Ananya → Kavya | Frontend half of realtime brand-chat messaging (fetch-SSE consumer) | src/lib/api.ts (messages.stream), src/pages/brand-chat.tsx | READY for QA | tsc clean, see notes below
```

**What shipped (frontend):**
- `messages.stream(role, dealId, handlers): DealMessageStreamHandle` in `src/lib/api.ts` — new method on the existing `messages` export. `handlers: { onMessage: (msg: DealMessage) => void; onOpen?: () => void; onError?: (err: Error) => void }`. Plain `fetch()` GET to `${API_BASE_URL}/deals/{dealId}/messages/stream` with `Authorization: Bearer <token>` (token read via the file's existing `TOKEN_KEYS[role]` → `localStorage`, same `brand_token`/`creator_token` every other brand/creator request uses — no Meera on-behalf token, no query-param token). Parses the `deal-message` SSE event via a local `parseDealMessageSseFrame` that mirrors `src/hooks/useMeeraStream.ts`'s frame parser (blank-line-delimited frames, `:`-comment/heartbeat skip, multi-line `data:` join). `JSON.parse`s the event payload straight into the existing `DealMessage` type (same shape `messages.list` returns) — no parallel type introduced. Returns `{ close: () => void }` backed by an `AbortController`; connection failures, non-OK responses, and stream read errors all route through the optional `onError` instead of throwing/rejecting.
- `src/pages/brand-chat.tsx` — new `useEffect` (after the existing `loadMessages`/`loadDeliverables` effect) that opens the stream only when `isApiLive() && selectedDeal`, keyed on `selectedDeal?.id` (not the object) so an unrelated list-refresh reference change doesn't tear down/reopen the connection. `onMessage` appends to `liveMessages` with `prev.some(m => m.id === incoming.id)` dedupe (mandatory — publish-on-send fires to the sender's own open emitter too, and `handleSendMessage` already appends the `messagesApi.send` response) and calls the same `scrollToBottom` pattern used elsewhere. `onError` is a `console.debug` only — never blocks send/render; the existing `messagesApi.list` fetch-on-load path is untouched. Effect cleanup (`handle.close()`) fires on both deal-id change and unmount, so no leaked connections and no stale-deal stream writing into the newly selected one.
- **Verify:** `npx tsc --noEmit -p tsconfig.json` → exit 0, zero errors. No `any` introduced (checked via grep on the diff). Could not exercise the live SSE round-trip from here (no backend running in this pass) — wiring/types and the non-live + stream-error degrade paths are what's actually verified; real browser realtime needs Meera's local run against a live backend.

```
FROM Meera → Arjun | Dual-gate local verification, realtime brand-chat messaging | full FE build + full BE `mvn -o test` suite (main tree only, feat/portfolio-view-tracking branch) | ✅ BOTH PASS | cleared to score, live SSE round-trip still unexercised
```

**MEERA VERIFICATION — realtime brand-chat SSE (dual gate) — 2026-07-18**

| Gate | Command | Result |
|---|---|---|
| Frontend build | `npm run build` (main tree) | ✅ PASS, exit 0. 4739 modules transformed, built in 1m11s; `postbuild` prerender 16/16 marketing routes snapshotted OK. No TS/bundler errors — only pre-existing `tsconfig.json` duplicate-`baseUrl` warning and >500kB chunk-size warnings (unrelated, pre-existing). |
| Backend full suite | `influora-api/.tools/apache-maven-3.9.10/bin/mvn.cmd -o test` (bundled offline Maven, full module, not targeted) | ✅ PASS, exit 0. **1329 tests run, 0 failures, 0 errors, 3 skipped** (pre-existing `DatabaseConstraintIntegrationTest`, Docker/Testcontainers unavailable — unrelated to this change). This supersedes Vikram's targeted 36/36 run as the authoritative full-suite check. |
| New SSE tests (subset of above) | — | `DealMessageStreamRegistryTest` 6/6, `DealServiceTest` 22/22 (incl. the 3 new stream-related cases), `DealControllerTest` 8/8 — all green, no regression anywhere else in the module. |
| Frontend mount spot-check | `npm run dev` + browser nav to `/brand/chat` | Best-effort only, honestly caveated: route is auth-gated (`BrandLayoutWrapper`), so with no backend up it correctly redirected to the brand sign-in screen rather than mounting `BrandChatPage` itself. Confirms the module graph (including the new `messages.stream` import and the new `useEffect`) loads without throwing — no console errors, no white screen. Could **not** exercise the actual live SSE round-trip (both services need to be up simultaneously for that); flagging this the same way Ananya did, not claiming more than was observed. |

**VETO: not exercised — full PASS on both gates.** Realtime brand-chat messaging (backend SSE stream + frontend fetch-SSE consumer) is cleared to score. Live end-to-end SSE round-trip (brand + creator both connected, message sent by one arrives at the other) remains unverified in any pass so far and should be called out explicitly if this ships without a manual/staging check.

---

## MEERA VERIFICATION — security-remediation pass (ErrorLogRedactor + AdminBrandService/MeeraController/GlobalExceptionHandler edits) — 2026-07-18

```
FROM Arjun → Meera | Re-verify build after security-remediation pass | influora-api/src/main/java/com/influora/security/ErrorLogRedactor.java (new), .../service/admin/AdminBrandService.java, .../web/MeeraController.java, .../common/GlobalExceptionHandler.java, .../service/ErrorLogService.java, 5 frontend TS files | ✅ ALL PASS | cleared for next gate
```

| Check | Command | Result |
|---|---|---|
| Frontend tsc | `npx tsc --noEmit -p tsconfig.json` | ✅ PASS, exit 0, 0 errors |
| Frontend build | `npm run build` | ✅ PASS, exit 0, built in 1m1s + postbuild prerender 16/16 routes. Only pre-existing >500kB chunk-size warnings, no errors |
| Backend compile | `.tools/apache-maven-3.9.10/bin/mvn.cmd -o -q compile` (bundled offline Maven) | ✅ PASS, exit 0, no errors — new `ErrorLogRedactor` + edits to `AdminBrandService`/`MeeraController`/`GlobalExceptionHandler`/`ErrorLogService` compile cleanly |
| Backend tests | `mvn -o test -Dtest=<41 classes matching Admin/Brand/Meera/Error/Campaign/Support/Creator/PlatformFee/AuditLog>` | ✅ PASS, **305 tests run across 41 classes, 0 failures, 0 errors, 0 skipped**. Verified the 4 classes whose files contain the string `@SpringBootTest` (`AdminDashboardServiceTest`, `AdminDashboardStatsCacheTest`, `AdminModerationServiceTest`, `AdminSupportServiceTest`) only reference it in doc-comments explaining why they're plain-Mockito instead — none are actually annotated `@SpringBootTest`. Nothing BLOCKED-no-docker this pass. |

**Note:** no dedicated test class exists yet for `ErrorLogRedactor`/`ErrorLogService`/`AdminErrorLogService` (new/untracked files, no `*Error*` match in `src/test/java`) — compile-clean only, not unit-tested. Flagging as a coverage gap, not a blocker for this pass.

**VETO: not exercised — full PASS.** Frontend and backend both compile and test green under the bundled offline Maven. Cleared to proceed to next pipeline gate.

---

## Meera → Arjun | AdminBrandService budget-override floor verify | 2026-07-18

FROM Vikram → Meera | Verify `AdminBrandService.java` budget-override committed-spend floor rewrite (two-pass algo, `nz`/`isCountedHold` helpers, `INVALID_BUDGET_SCALE` reject, new `HashMap` import) | `influora-api/src/main/java/com/influora/service/admin/AdminBrandService.java` | ✅ PASS | routing per pipeline

- Backend compile (`mvn -o -q compile`): ✅ PASS, exit 0, no errors.
- Backend tests (`mvn -o test -Dtest=<19 classes matching AdminBrand/Brand/Budget/Escrow/Campaign>`): ✅ PASS, **151 tests run across 19 classes, 0 failures, 0 errors, 0 skipped**. All plain-Mockito (`@ExtendWith(MockitoExtension.class)`) — nothing BLOCKED-no-docker.
- **Coverage gap:** no test file anywhere in `src/test/java` references `overrideCampaignBudget`, `committedSpend`, `isCountedHold`, or `INVALID_BUDGET_SCALE` (grepped repo-wide). The committed-spend floor rewrite is compile-verified only — **not unit-tested**. `ApprovalWorkflowServiceTest` mocks `AdminBrandService` as a collaborator but doesn't exercise this method.

**VETO: not exercised — compile/tests PASS, but flagging the floor logic as untested before this ships as a money-path change.**

---

## Vikram → Kavya | I7 backend: workspace settings GET/PATCH | 2026-07-18

FROM Priya (direct) → Vikram | I7 backend half — brand Settings > General > Workspace Information had no persistence endpoint (`src/pages/brand-settings.tsx:38-46`) | `influora-api/src/main/java/com/influora/web/WorkspaceController.java`, `service/WorkspaceService.java`, `domain/entity/Workspace.java`, `web/dto/workspace/WorkspaceMemberDtos.java` + 3 test files | READY for QA | frontend wiring is Ananya's follow-up, not touched here

**Note on sequencing:** the Wave 3 plan above (`FROM Arjun → Ananya+Vikram | Wave 3: I6, I7`) marks I7 BLOCKED on Wave 2 Kabir PASS. This backend half was assigned directly by Priya and built now, ahead of that gate. It touches no auth/security/money-path code (plain CRUD-with-role-check on a non-financial entity), but flagging the out-of-sequence start for Arjun/Kabir's awareness — still routing to Kavya first per standard gate, not skipping QA.

**Investigation finding:** the read/update service methods and DTOs already existed from an earlier pass (L-9, `INFLUORA-PRODUCTION-READINESS-AUDIT-2026-07-14.md`) — `WorkspaceService.getMyWorkspace`/`updateMyWorkspace` and `WorkspaceMemberDtos.WorkspaceReadResponse`/`WorkspaceUpdateRequest` were fully built and unit-tested, but `WorkspaceController` never mounted them (only `/workspaces/slug-check` existed). The actual gap was the missing controller wiring + the `email` field.

**Endpoints:**
| Method | Path | Auth | Response |
|---|---|---|---|
| GET | `/workspaces/me` | Brand, any active member | `ApiResponse<WorkspaceReadResponse>` |
| PATCH | `/workspaces/me` | Brand, OWNER/ADMIN only | `ApiResponse<WorkspaceReadResponse>` |

`WorkspaceReadResponse`: `{id, name, slug, email, industry, companySize, websiteUrl, logoUrl, verificationStatus}`.
`WorkspaceUpdateRequest`: `{name*, email?, industry?, companySize?, websiteUrl?, description?, logoUrl?}` (`*`=required, full-replace semantics).

**Field persistence status (the 4 fields Ananya needs for the Settings page):**
- `workspaceName` → `workspaces.name` — ✅ persists.
- `website` → `workspaces.website_url` — ✅ persists.
- `email` → `workspaces.billing_email` — ✅ persists, **reused column, not new**. Same mapping precedent already established by `AdminBrandDtos.UpdateBrandRequest.email` (admin panel). This is the workspace's billing/contact email, not a personal user email — flagging the semantic reuse explicitly since it's a judgment call, not a fabricated contract.
- `phone` → **no column anywhere** (`workspaces` or `users`). NOT persisted. Needs a migration decision from Priya before this can wire live — do not build a fake success path for it.

**Auth/role gate:** `BrandContextService.requireBrandWorkspace` (resolve caller's own workspace — never a client-supplied id) → `requireMember` (confirms active membership) → `requireRole(OWNER, ADMIN)` for the PATCH only; GET allows any active member. Same pattern as every other brand mutation in this codebase.

**Validation:** `name` non-blank, `email` format-checked (`@Email` DTO annotation + a service-level regex check — the duplication is deliberate: this codebase has no MockMvc/`@WebMvcTest` harness — see `AuthControllerTest`'s note — so the service-level check is what makes "bad email/blank name rejected" actually unit-testable), `websiteUrl` loose sanity `@Pattern` (optional `http(s)://`, requires a `domain.tld` shape, empty string allowed to clear).

**Tests:** `WorkspaceControllerTest.java` (new, 2 tests — GET/PATCH delegation + response mapping incl. `email`), `WorkspaceServiceTest.java` (+5: happy-path w/ email persists, blank-name rejected, malformed-email rejected, non-OWNER/ADMIN role rejected, caller-not-a-member rejected — all assert `workspaceRepository.save` never called on rejection), `WorkspaceServiceAnalyzeSiteTest.java` (5 pre-existing tests updated for the new method signature only, no behavior change, still green).

**Test run:** `mvn -o test` (full suite, bundled `.tools/apache-maven-3.9.9`) → **1343 run, 0 failures, 0 errors, 3 skipped** (pre-existing/unrelated). `mvn -o compile` / `mvn -o test-compile` both clean.

**Docs written:** `wiki/processes/api-docs.md` (new), `docs/api.md`, `docs/docs/api.md`, `docs/features/workspaces-members.md`, `docs/docs/features/workspaces-members.md`.

**For Ananya (once Kavya clears this):** wire `src/lib/api.ts`'s `workspaces` export with `getMe`/`updateMe` calls to `GET`/`PATCH /workspaces/me`, then replace the hardcoded `useState` seed + disabled Save button in `src/pages/brand-settings.tsx` (lines 20-48, 141-188) for `workspaceName`/`email`/`website`. Leave `phone` disabled/local-only with an honest caption — same discipline as the other UI-only toggles already on that page.

---

## Vikram → Kavya | I7 follow-up: phone now persists (Swapnil-approved) | 2026-07-18

FROM Priya (direct) → Vikram | Add real `phone` column — closes the gap flagged in the I7 handoff above (was UI-only, no column) | migration `V20260718180000__workspace_phone.sql`; `domain/entity/Workspace.java`; `web/dto/workspace/WorkspaceMemberDtos.java`; `web/WorkspaceController.java`; `service/WorkspaceService.java`; `test/.../WorkspaceControllerTest.java`, `WorkspaceServiceTest.java`, `WorkspaceServiceAnalyzeSiteTest.java` | READY for QA | backend/Java only — did not touch `src/lib/api.ts` or any `.tsx` (Ananya's territory, she's wiring the frontend concurrently)

**Migration:** `V20260718180000__workspace_phone.sql` — `ALTER TABLE workspaces ADD COLUMN phone VARCHAR(30) NULL AFTER billing_email;`. Additive + nullable, no default, no backfill. Logged in `wiki/processes/schema-changes.md`.

**Updated GET/PATCH shape (for Ananya):**
- `WorkspaceReadResponse`: `{id, name, slug, email, phone, industry, companySize, websiteUrl, logoUrl, verificationStatus}` — `phone` inserted right after `email`.
- `WorkspaceUpdateRequest`: `{name*, email?, phone?, industry?, companySize?, websiteUrl?, description?, logoUrl?}` — `phone` inserted right after `email`, full-replace (blank/null clears it, same as `email`).

**Persistence:** `workspace.getPhone()` on read; `WorkspaceService.updateMyWorkspace` calls the new `Workspace#updatePhone` mutator (same blank-clears-it semantics as `updateContactEmail`). Removed the now-stale "no phone column" javadocs on `Workspace#updateContactEmail`, `WorkspaceReadResponse`, `WorkspaceUpdateRequest`, and the controller.

---

## Meera Verification — Meera chat R2/R3b/R6 + Sarvam voice R5 + R1 secret alignment — 2026-07-20

```
FROM Kavya → Meera | Local verification (FE+AI svc+config) | src/components/feature/meera/MeeraChatPanel.tsx, src/lib/meera-api.ts, src/components/feature/meera/Composer.tsx, src/data/meera-copy.ts, influora-ai/app/providers/sarvam.py, influora-ai/app/prompt/persona.py, influora-ai/app/config.py, influora-ai/tests/providers/test_sarvam_tts.py, influora-api/.env (lines 34-35) | ✅ ALL PASS | restarts required before activation (see below)
```

| Check | Command | Result |
|---|---|---|
| Frontend typecheck | `npx tsc --noEmit -p tsconfig.json` | ✅ exit 0, 0 errors, empty output |
| Frontend build | `npm run build` (vite build + postbuild prerender) | ✅ exit 0 — 4745 modules transformed, built in 28.0s, 16/16 marketing routes prerendered. Only pre-existing >500kB chunk-size warning, no new errors. |
| AI svc — sarvam tests | `python -m pytest tests/providers/test_sarvam_tts.py -q` (influora-ai) | ✅ 31 passed in 1.38s (10 new regression tests included) |
| AI svc — money-path route | `python -m pytest tests/routes/test_chat_money_path.py -q` | ✅ included in combined run below |
| AI svc — tool-result-data route | `python -m pytest tests/routes/test_chat_tool_result_data.py -q` | ✅ combined: 12 passed, 1 warning (pre-existing pydantic `SkipValidation` UserWarning in a third-party dep, unrelated to this diff) in 3.40s |
| Python compile check | `python -m py_compile app/providers/sarvam.py app/prompt/persona.py app/config.py` | ✅ exit 0, no syntax errors |
| Python lint | `python -m ruff check ...` | ⚠️ SKIPPED — `ruff` not installed in this environment (`No module named ruff`), not part of `requirements.txt`/`requirements-dev.txt`. Not a regression; not blocking. |

**R1 secret alignment — CONFIRMED MATCH.** Real key/value lines (not the comment block the task pointed at):
- `influora-api/.env:34` `INTERNAL_SERVICE_TOKEN_SECRET=dev-internal-service-token-secret-change-in-production-min-32-chars` ↔ `influora-ai/.env:57` `SERVICE_TOKEN_SIGNING_KEY=dev-internal-service-token-secret-change-in-production-min-32-chars` — **byte-for-byte identical**
- `influora-api/.env:35` `INTERNAL_REQUEST_HMAC_SECRET=dev-internal-request-hmac-secret-change-in-production-min-32-chars` ↔ `influora-ai/.env:46` `INTERNAL_HMAC_KEY=dev-internal-request-hmac-secret-change-in-production-min-32-chars` — **byte-for-byte identical**
- Note: the task's cited `influora-api/.env` lines 29-30 are the explanatory comment block, not the values — actual values sit at lines 34-35 in the current file. `influora-ai/.env` line numbers (46, 57) were accurate.

**Required restarts (NOT performed — another chat's dev server is live in this folder):**
- `influora-api` Docker backend needs a restart to pick up the (already-matching) env-file secrets at container start — only matters if this pair was just edited; values already agree so this is a no-op restart unless the container is running stale values.
- `influora-ai` Python service needs a restart (module re-import) to activate R5 (`app/prompt/persona.py` + `PROMPT_VERSION` bump in `app/config.py`) and the Sarvam voice tuning/chunking changes in `app/providers/sarvam.py`.

**VERDICT: ✅ GREEN — all build/typecheck/test gates pass.** VETO not exercised. Cleared pending the two restarts above to actually activate R1/R5/voice changes in the running services.

**Validation:** lenient/nullable — blank or null clears it. If non-blank: DTO `@Pattern` restricts to allowed characters (`+ ( ) - space`, digits) as a first-pass filter; `WorkspaceService` does the real check (`isValidPhone`) — strips to digits-only and requires 7-15 digits, same "DTO annotation + service-level belt-and-suspenders" precedent as the existing `email` field. No over-restriction on international formats.

**Tests added:** `WorkspaceServiceTest` — happy path now asserts `phone` persists on save (`"+1 (415) 555-0100"`), new `updateMyWorkspace_blankPhone_clearsPhone` (blank string clears a previously-set phone), new `updateMyWorkspace_badPhone_rejected` (`"123"` → `VALIDATION_ERROR`, never saved). `WorkspaceControllerTest` — both existing tests extended to assert `phone` round-trips through GET and PATCH. `WorkspaceServiceAnalyzeSiteTest` — 5 pre-existing tests updated for the new parameter position only (no behavior change).

**Test run:** `mvn -o test` (full suite, bundled `.tools/apache-maven-3.9.10`) → **1345 run, 0 failures, 0 errors, 3 skipped** (pre-existing/unrelated, same 3 as before). Targeted Workspace* run also green (18/18) before the full run.

**Schema log:** `wiki/processes/schema-changes.md` updated with this migration's row + a notes entry.

2026-07-18 16:43 | Meera -> Arjun | AdminBrandServiceBudgetOverrideTest verified | influora-api/src/test/java/com/influora/service/admin/AdminBrandServiceBudgetOverrideTest.java | PASS (8/8 tests, BUILD SUCCESS, ~35.7s) | partial-escrow scenario (testPartialEscrowFloorsAtAgreedRate: agreedRate 100k + FUNDED 30k -> floors at 100k) confirmed passing; no compile errors; ready for Swapnil review

---

Ananya → Kavya | Fix mock-only Approve/Request Revision on brand timeline deliverable panel (Priya direct task) | src/components/brand/timeline/panels/deliverable-review-panel.tsx | READY for QA | replaced fake `setTimeout` in handleApprove/handleRequestRevision with real `deliverablesApi.approve`/`deliverablesApi.requestRevision` calls (src/lib/api.ts:1434-1446), same proven pattern as brand-chat.tsx handleApproveLive/handleReviseLive. Uses `event.metadata.deliverableId` (not `event.id`) as the backend id — was already present on TimelineEventMetadata, no new prop threading needed on deliverable-card.tsx. Added inline `submitError` state + banner on failure, guards on missing deliverableId. tsc --noEmit clean. Verified via temporary dev-only smoke route (created + fully removed after test): Approve/Request Revision now fire real POST /deliverables/:id/approve and /revise, confirmed ERR_CONNECTION_REFUSED in network log against live-mode backend (none running) — proves it's a real network attempt, not a fake resolve. Deal-Room surface (brand-chat.tsx) untouched.

---

## Meera Verification — Contracts/Disputes/Analytics batch (3 FE-only partial-fixes, single build gate) — 2026-07-18

```
FROM Kavya → Meera | Local build verification (FE-only, backends pre-existing) | src/lib/contract-generator.ts, deal-contract-tab.tsx, contract-panel.tsx, creator-contract-panel.tsx, creator-deal-contract-tab.tsx, contracts-and-deliverables.tsx, src/lib/api.ts (brandDisputes.list), src/pages/brand-disputes.tsx, src/pages/brand-analytics.tsx | ✅ ALL PASS | cleared to score
```

| Check | Command | Result |
|---|---|---|
| Typecheck | `npx tsc --noEmit` | ✅ exit 0, 0 errors, empty output |
| Build (authoritative gate) | `npm run build` (vite build + postbuild prerender) | ✅ exit 0 — 4739 modules transformed, built in 1m35s, 16/16 marketing routes prerendered. Only pre-existing >500kB chunk-size warning, no new errors. `package.json`/`package-lock.json` changes from the other in-flight session did **not** break this build — no missing-dep failure to flag. |
| Contracts wiring | grep `api.contracts.sign` | ✅ live in `contract-generator.ts:225` and `contracts-and-deliverables.tsx:579`; 4 panel files present in module graph (see mount check below) |
| Brand disputes wiring | grep `brand/disputes/list` | ✅ `src/lib/api.ts:3115` — `http.request<BrandDisputeRow[]>('GET', '/brand/disputes/list', ...)` in live mode; `brand-disputes.tsx` doc comment updated to match |
| Brand analytics wiring | grep `deals.list`/`demoCreators` in `brand-analytics.tsx` | ✅ live roster derives from `api.deals.list('brand', 'all')` (line 63); `demoCreators` correctly retained only for the `!live` branch (mock mode) |
| Mount spot-check | `npm run dev` + browser → `/brand/disputes`, `/brand/analytics` | ⚠️ PARTIAL — both routes redirect to the brand sign-in gate (no test credentials in this environment, expected auth-gated behavior, not a bug). Could not directly observe the live `GET /brand/disputes/list` call or the analytics roster fetch. No console errors, no failed/500 network requests, no crash — Vite dev server served every requested module cleanly. |
| Bundle-inclusion proxy | network log during mount attempt | ✅ `contract-panel.tsx` and `deliverable-review-panel.tsx` both loaded as 200 OK modules mid-render, confirming the contract panels compile and are reachable in the live module graph even though the authenticated view itself couldn't be reached |

**CANNOT-VERIFY:** authenticated live round-trip of `/brand/disputes` and `/brand/analytics` against a running backend — no test credentials/session available in this environment; only the pre-auth redirect and clean module load were exercised.

**VERDICT: ✅ BUILD PASS (authoritative gate) — all three fixes cleared to score.** tsc clean, build clean, all three changes confirmed present and wired via grep. Mount check partial due to auth gate (environment limitation, not a defect). VETO not exercised.

---

Ananya → Kavya | Wire Hype campaign config into POST/PATCH /campaigns (Priya/coordinator direct task, depends on Vikram's HypeConfigDto backend) | src/lib/api.ts (campaignToPayload + mapCampaignFromApi) | READY for QA (needs Vikram's backend running for live verification) | campaignToPayload now forwards `campaignType` + full `hype` block (was dropping both). `liveUntil` converted Date→ISO string on write (fmtIso), ISO string→Date on read (mapCampaignFromApi), matching HypeConfigDto's raw-string contract (CampaignDtos.java:43-47). FE/BE enum mismatch handled: FE CampaignType has 'OPEN' which backend's CampaignIntentType (HYPE/DIRECT/REVIEW/STANDARD) rejects — only forward campaignType when it isn't 'OPEN'; omitting it for the generic (non-Hype) create/edit forms is unchanged behavior (backend defaults absent campaignType to STANDARD). Did NOT reconcile the full OPEN/STANDARD mismatch — flagging as a separate pre-existing item, not in scope. Read path: campaigns-list.tsx / HypeCampaignCard already consumed campaign.campaignType/campaign.hype correctly, no changes needed there. brand-edit-campaign.tsx has no Hype UI — confirmed backend silently ignores PATCH with hype:undefined for an existing HYPE campaign (CampaignService.java:201-210), so no regression from the generic edit form. tsc --noEmit clean. Verified via temporary dev-only smoke route (created + fully removed after test, git status confirms clean): captured actual POST /campaigns bodies — Hype create sent `campaignType:"HYPE"` + full `hype` block with `liveUntil` as ISO string (e.g. "2026-07-21T13:11:44.230Z"); standard create sent neither `campaignType` nor `hype` keys at all (no "OPEN" ever sent).

---

## Meera Verification — Sarvam TTS ReadTimeout follow-up fix (24kHz + 15s read) — 2026-07-20

```
FROM (direct request) → Meera | Re-verify commit 5350af2 (python-only, no FE files) | influora-ai/app/providers/sarvam.py, influora-ai/app/config.py, influora-ai/tests/providers/test_sarvam_tts.py | ✅ ALL PASS (1 pre-existing unrelated FAIL flagged) | needs influora-ai service restart to take effect
```

| Check | Command | Result |
|---|---|---|
| Compile | `python -m py_compile influora-ai/app/providers/sarvam.py influora-ai/app/config.py` | ✅ exit 0 |
| Config sanity-grep | `grep sarvam_tts_read app/config.py` / `grep speech_sample_rate app/providers/sarvam.py` | ✅ `sarvam_tts_read: float = 15.0` (config.py:122); `"speech_sample_rate": 24000` (sarvam.py:300, not 44100) |
| Sarvam TTS regression suite | `python -m pytest tests/providers/test_sarvam_tts.py -q` | ✅ **31 passed** in 0.87s (matches expected count exactly) |
| Cost/voice-adjacent suite | `python -m pytest tests/costs/test_pricing.py tests/routes/test_voice_spend_gate.py -q` | ⚠️ **25 passed, 1 failed** — `test_gemini_cost_matches_point10_and_point40_per_mtok` asserts `Decimal('0.50')` but got `Decimal('2.8000000')`. **Pre-existing, unrelated to this commit** — confirmed via `git show 5350af2 -- influora-ai/app/config.py`: diff only touches `sarvam_tts_read`, zero touches to any Gemini pricing constant. Gemini pricing-table bug, not a Sarvam-fix regression. Flagging for Arjun to route separately. |
| Lint | `ruff check ...` | ⏭️ SKIPPED — `ruff` not installed in this environment (`command not found`) |
| FE regression (cheap check, full build not required — commit is Python-only) | `npx tsc --noEmit -p tsconfig.json` (repo root) | ✅ exit 0, 0 errors (node v22.15.0, tsc 5.7.3) |

**VERDICT: 🟢 GREEN — cleared.** Both landed values confirmed exactly as specified (15.0s read timeout, 24000Hz sample rate), compile clean, the targeted Sarvam regression test (31/31) passes, and the frontend is unaffected (tsc clean, no FE files in this diff). The one test failure found is a pre-existing, unrelated Gemini cost-pricing bug — not a blocker for this fix, flagging separately.

**ACTION REQUIRED (not yet done — do NOT restart, another dev server is live in this folder):** the `influora-ai` Python service must be restarted to pick up the module re-import of `sarvam.py`/`config.py`. After restart, confirm via `ai_dev.log` that a `voice_speak_started` entry is no longer followed by `sarvam speak failed: ReadTimeout`.

---

## Meera Verification — commits 07f67c6 + e20dd98 (pricing fix + Meera chat batch) — 2026-07-20

```
FROM (direct request) → Meera | Verify 07f67c6 (pricing test fix) + e20dd98 (short replies/options/voice-sync/templates) | src/hooks/useVoiceOutput.ts, MeeraChatPanel.tsx, ToolResultRenderer.tsx, src/lib/meera-api.ts, influora-ai/app/config.py, app/tools/loop.py, app/tools/schemas.py, app/routes/chat.py, app/prompt/persona.py, tests/costs/test_pricing.py | ✅ ALL PASS | needs influora-ai restart + FE rebuild to activate
```

| # | Command | Result |
|---|---|---|
| 1 | `npx tsc --noEmit -p tsconfig.json` (repo root) | ✅ exit 0, 0 errors |
| 2 | `npm run build` (repo root) | ✅ exit 0 — vite build 32.46s, 4745 modules; postbuild prerender 16/16 routes captured clean (no flake this run) |
| 3 | `python -m pytest tests/costs/test_pricing.py tests/tools/ tests/routes/test_chat_money_path.py tests/routes/test_chat_tool_result_data.py tests/eval/test_prompt_injection.py tests/providers/test_sarvam_tts.py -q` (influora-ai/) | ✅ **106 passed**, 1 unrelated pydantic deprecation warning, 0 failures — this run also confirms the previously-flagged Gemini pricing failure (`test_gemini_cost_matches_point10_and_point40_per_mtok`) is now gone, i.e. 07f67c6 actually fixed it |
| 4 | `python -m py_compile app/config.py app/tools/loop.py app/tools/schemas.py app/routes/chat.py app/prompt/persona.py` (influora-ai/) | ✅ exit 0 |
| 5 | Config confirmations (grep) | ✅ `PROMPT_VERSION = "meera-2026.07.21.3"` (config.py:69); ✅ `meera_chat_max_tokens` default_factory `_get_int("MEERA_CHAT_MAX_TOKENS", 384)` (config.py:221-223); ✅ `PRESENT_OPTIONS = "present_options"` and `LOCAL_TOOL_NAMES = (ANALYZE_SITE, PRESENT_OPTIONS)` (schemas.py:58-59) |

**VERDICT: 🟢 GREEN — cleared.** Both commits build and test clean. tsc clean, vite build + prerender clean (16/16, no flake), 106/106 targeted Python tests pass, py_compile clean on all 5 touched modules, all 3 landed config values confirmed exactly as claimed in the commit message.

**RESTARTS REQUIRED TO ACTIVATE (none performed — live dev server in this folder, per instruction):**
- `influora-ai` Python service — must restart to pick up persona.py/schemas.py/config.py/loop.py re-imports (PROMPT_VERSION bump, 384-token cap, `present_options` tool registration all inert until reload).
- Frontend rebuild/reload — needed to activate voice Option A (speakSequence), the options-cards UI (ToolResultRenderer), and the templates-until-first-message gate in MeeraChatPanel.tsx.

**Not verified here (per commit's own caveat):** runtime behavior — whether the model actually calls `present_options`, voice playback sequencing, template-gate UX — needs the live stack after both restarts. Static/build/test verification only.

VETO not exercised — code passes local verification.
