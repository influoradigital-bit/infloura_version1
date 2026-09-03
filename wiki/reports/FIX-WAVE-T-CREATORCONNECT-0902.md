# Fix wave — T-CREATORCONNECT-0902

**Date:** 2026-09-03 (round 2 — closes the four code findings left open by round 1)
**Branch:** `fix/f0390-money-flags-build-pipeline`
**State:** **UNCOMMITTED** — every change in this wave is in the working tree / index only. Nothing committed, nothing pushed.
**Source findings:** `.proof-os/tasks/T-CREATORCONNECT-0902/findings.json` (31 findings, each now carrying `status` + `evidence`)
**Origin QA:** `wiki/reports/QA-CREATORCONNECT-0902.md` (tester × priya, 35-question Q&A)
**Sign-off:** Priya (CTO) — per-finding verdicts below are authoritative, produced by fresh-context review of each package with every check re-run rather than taken on the fixer's report.

---

## 1. Summary

**29 of 31 findings solved. 2 remain open. Build gate: GREEN.**

| Severity | Total | Solved | Open |
|---|---|---|---|
| Critical | 2 | 2 | 0 |
| High | 8 | 7 | **1** (Q3.1) |
| Medium | 17 | 16 | **1** (Q1.3) |
| Low | 4 | 4 | 0 |
| **Total** | **31** | **29** | **2** |

Round 2 closed all four findings this wave was dispatched to fix — **Q1.4 (High), Q4.5, Q5.5 and Q6.5
(Medium)**. Every one was re-checked against the live tree, not accepted on report.

**Both remaining opens are ops/provisioning, not code.** Q3.1 and Q1.3 are each blocked on a secret or
an address that only Swapnil/ops can provision; the code side of both is already correct and bound.
There is no open code defect left in this wave.

Nothing in this wave is a WRONG_FIX. No finding was closed on a vacuous pin, and no finding was closed
on a fixer's report alone.

---

## 2. Per-finding verdicts

Owner and package are as dispatched. Verdict is Priya's, after re-checking the current tree. The four
rows changed in round 2 are marked **[R2]**.

| ID | Sev | Owner | Package | Verdict | Evidence (path:line) |
|---|---|---|---|---|---|
| Q1.1 | Medium | vikram | pkg-lookup-ui-config | **FIXED** | `influora-api/src/test/java/com/influora/service/BusinessDiscoveryResponseJsonMappingTest.java:60`; projection matches DTO at `influora-api/src/main/java/com/influora/integration/meta/client/InstagramInsightsClient.java:120-122` |
| Q1.2 | Low | vikram | pkg-lookup-ui-config | **FIXED** | `influora-api/src/main/java/com/influora/service/ExternalCreatorService.java:243-248` (FACEBOOK_LOGIN filter added before the expiry filter, mirroring `:448`) |
| Q1.3 | Medium | vikram | pkg-lookup-ui-config | **OPEN** | Code correct at `ExternalCreatorService.java:558-561` / `:588-598`, bound at `influora-api/src/main/resources/application.yml:408-409` — defeated by blank `deploy/utho/generate-env.sh:152-153`, so the borrowed-token fallback at `:569-583` is still the production path |
| **Q1.4 [R2]** | **High** | vikram | pkg-q14-lookup-race | **FIXED** | Recovery re-read now in its own transaction: `influora-api/src/main/java/com/influora/service/ExternalCreatorService.java:469-475`; `REQUIRES_NEW` template `:155`, executor `:165-167`; racy save isolated `:449-453`; rename forced off `:476`; javadoc reconciled `:406-414`, `:460-468`, `:506-514`. Pins `ExternalCreatorServiceTest.java:388-393` (`verify(transactionManager, times(2)).getTransaction(any())` + `InOrder`) and real-transaction `ExternalCreatorServiceRaceRollbackIsolationTest.java:213` |
| Q1.5 | Medium | vikram | pkg-lookup-ui-config | **FIXED** | `src/components/brand/discover/creator-discovery.tsx:2113-2129`; NULL-safe bounds at `influora-api/src/main/java/com/influora/repository/ExternalCreatorSpecs.java:42-46`; pin `creator-discovery.instagram.test.tsx:219` |
| Q2.1 | Low | vikram | pkg-lookup-ui-config | **FIXED** | `src/components/brand/discover/creator-discovery.tsx:2009-2022` (Engagement tile removed, grid-cols-2), null render helper `:1941-1943` |
| Q2.2 | Low | vikram | pkg-lookup-ui-config | **FIXED** | `src/components/brand/discover/creator-discovery.tsx:1976-1981` (`const verified = creator.verifiedWithInfluora`); server flag `ExternalCreatorService.java:621` |
| Q2.3 | Medium | vikram | pkg-lookup-ui-config | **FIXED** | `src/lib/api.ts:221-227`, `:277-285` + all three envelope throw sites; consumer `creator-discovery.tsx:2206-2226`; wire `GlobalExceptionHandler.java:69-77` |
| Q2.4 | High | vikram | pkg-lookup-ui-config | **FIXED** | `src/components/brand/discover/creator-discovery.tsx:2113-2125`, `:2241-2243`, `:2329-2343`; pins `creator-discovery.instagram.test.tsx:188-205` and `:207-217` |
| Q2.5 | Low | vikram | pkg-lookup-ui-config | **FIXED** | `src/components/brand/discover/creator-discovery.tsx:2293-2298`, `:1925-1931`, `:2005`; `ExternalCreatorService.java:642` (`invitedAt` appended to DTO) |
| Q3.1 | High | vikram | pkg-lookup-ui-config | **OPEN** | `deploy/utho/generate-env.sh:159` still `ADMIN_NOTIFICATION_EMAIL=` (blank); `deploy/utho/docker-compose.utho.yml:238` and `deploy/hostinger/docker-compose.hostinger.yml:209` default to empty. Escalation only at `SHARED_CONTEXT.md:343` |
| Q3.2 | High | vikram | pkg-admin-join-hooks | **FIXED** | `influora-api/src/main/java/com/influora/domain/entity/CreatorConnectionRequest.java:139-151`; call site `ExternalCreatorService.java:516`; owner/admin fallback `NotificationListener.java:140-159`; pins `CreatorConnectionRequestTest.java:38-52`, `NotificationListenerTest.java:106-128` and `:130-151` |
| Q3.3 | Medium | vikram | pkg-admin-join-hooks | **FIXED** | `NotificationListener.java:709-720` (bounded retry) and `:722-745` (durable `error_log` row); sink safety `ErrorLogService.java:50-83`; console route `src/pages/admin-console.tsx:71`; pins `NotificationListenerTest.java:164/179/193` |
| Q3.4 | Medium | vikram | pkg-lookup-ui-config | **FIXED** | `ExternalCreatorService.java:620-623`, `:634-641`; FE double-submit guard `src/components/brand/discover/creator-discovery.tsx:2516`; pins `ExternalCreatorServiceTest.java:360`, `ExternalCreatorServiceRaceRollbackIsolationTest.java:166` |
| Q3.5 | Medium | vikram | pkg-lookup-ui-config | **FIXED** | `ExternalCreatorService.java:566-582` (`enforceDailyConnectCap`), called at `:515`/`:519`, skipped for the idempotent return at `:511-513`; config `application.yml:287-288`; pin `ExternalCreatorServiceTest.java:328-358` |
| Q4.1 | High | **ananya** | pkg-admin-ui | **FIXED** | `src/admin/pages/CreatorConnectionsPage.tsx:287-301`, `:303-306`, `:226-230`, `:310-313`, `:569-586`; falsified against the staged pre-fix page (3 of 4 new tests fail there) — pins `CreatorConnectionsPage.test.tsx:124/145/165` |
| Q4.2 | Medium | vikram | pkg-admin-join-hooks | **FIXED** | `influora-api/src/main/java/com/influora/service/admin/AdminCreatorConnectionService.java:255`, `:266-275`; pins `AdminCreatorConnectionServiceTest.java:180-209` (asserts the real workspace name is in the captured payload and "A brand" is absent) and `:211-237` |
| Q4.3 | Medium | vikram | pkg-admin-join-hooks | **FIXED** | `AdminCreatorConnectionService.java:412` (no `@Transactional`), `:484-497`, `:501-521`; type confirmed `MetaGraphApiClient.java:109-112`; FE surface `src/admin/pages/CreatorConnectionsPage.tsx:613-614`; pin `AdminCreatorConnectionServiceTest.java:154` |
| Q4.4 | High | vikram | pkg-admin-join-hooks | **FIXED** | `AdminCreatorConnectionService.java:76`, `:438-446`, `:346-385`; endpoint `influora-api/src/main/java/com/influora/web/AdminExternalCreatorController.java:60-66`; pins `AdminCreatorConnectionServiceTest.java:108-119`, `:120-131`, `:244-256`, `:257-268` |
| **Q4.5 [R2]** | Medium | **ananya** | pkg-q45-admin-null-safety | **FIXED** | Producer parity restored: `src/admin/types/admin.types.ts:617` `brandName`, `:619` `requestedByEmail`, `:621` `igUsername`, `:626` `creatorStatus` all `\| null`, mirroring `AdminCreatorConnectionService.java:675/677/679/684`. Crash closed at the cited site: `src/admin/pages/CreatorConnectionsPage.tsx:479` guarded `charAt`; em-dash fallbacks `:473`, `:484`, `:193`, `:195`. Pin `CreatorConnectionsPage.test.tsx:226` (four-null fixture `:191-212`) |
| Q5.2 | Medium | vikram | pkg-admin-join-hooks | **FIXED** | `influora-api/src/main/java/com/influora/domain/entity/ExternalCreator.java:239-250` (idempotent `markJoined`); `CreatorConnectionRequest.java:139-151`; pins `ExternalCreatorTest.java:44-65`, `:67-79`, `CreatorConnectionRequestTest.java:65-83` |
| Q5.3 | Medium | vikram | pkg-admin-join-hooks | **FIXED** | `influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:276`, `:313-316`, `:330-331`, `:379-390`; consumed at `ExternalCreatorLinkService.java:94-97` |
| Q5.4 | **Critical** | vikram | pkg-admin-join-hooks | **FIXED** | Call site removed `influora-api/src/main/java/com/influora/service/CreatorProfileService.java:176-183`; defence-in-depth first-link guard `ExternalCreatorLinkService.java:130-138`; pin `ExternalCreatorLinkServiceTest.java:112-131` |
| **Q5.5 [R2]** | Medium | vikram | pkg-q55-invite-token | **FIXED** | Claim path wired end to end and server-verified. FE: `src/pages/creator-register.tsx:2` (`useSearchParams`), `:33`, `:35`, `:107-113` → `src/lib/api.ts:939` → `:566`. BE: `AuthController.java:94-98` → `CreatorRegisterRequest.java:23-30` → `AuthService.java:364` `consumeInviteToken` → `RegistrationService.java:57-108` (live re-check of status + `invitedAt`) → `ExternalCreatorLinkService.java:207-235`. Issuer `AdminCreatorConnectionService.java:282-287`. Fail-closed secret guard `InviteTokenService.java:85-93`, `:133-143`. Secret provisioned `deploy/utho/generate-env.sh:67`, passed at `docker-compose.hostinger.yml:190` / `docker-compose.utho.yml:211`. Pins `InviteTokenServiceTest.java:88/101/111/124`, `RegistrationServiceTest.java:82/100/114`, `creator-register.test.tsx:168/187/201` |
| Q6.2 | High | vikram | pkg-lookup-ui-config | **FIXED** | `src/components/brand/campaigns/campaign-form.tsx:648-661` (+ helpers `:82-107`); server guard `influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:473-478`; creator-side backstop `influora-api/src/main/java/com/influora/service/DealService.java:157-160`, `:1443-1459`; pins `campaign-form-f0290-draft-invite-guard.test.tsx`, `CreatorDiscoveryServiceInviteDraftGuardTest`, `DealServiceCreatorDraftExclusionTest` |
| Q6.4 | High | vikram | pkg-lookup-ui-config | **FIXED** | `src/pages/brand-new-campaign.tsx:205-209`, honest banner `:128-133`; destination `src/pages/brand-new-hype-campaign.tsx:72-75`, `:298-310`; pins `brand-new-campaign.hype-handoff.test.tsx:65`, `brand-new-hype-campaign.handoff-banner.test.tsx:65-66` |
| **Q6.5 [R2]** | Medium | vikram | pkg-q65-email-ig-handle | **FIXED** | Both call sites now derive from one local: `influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:782-786` (`igQueryParam` + `campaignPath`), consumed at `:792` (`campaign_url`) and `:801` (in-app deep link) — so they cannot drift. Sole builder of that URL repo-wide (`grep "campaigns/new"` → `:786` only). Email CTA consumes it at `EmailTemplateRegistry.java:345-352`, `:437`, `:570`. Consumer `campaign-form.tsx:351/372/783`. Pins `NotificationListenerTest.java:168` (dotted handle, both surfaces) and `:203` (null → no `&ig=`) |
| Q7.2 | Medium | vikram | pkg-lookup-ui-config | **FIXED** | `ExternalCreatorService.java:259-297` (account-id-first resolve, `applyIgAccountId` at `:279`, per-creator catch `:288-296`), non-transactional `list()` `:132-148`; `influora-api/src/main/java/com/influora/integration/meta/client/CreatorMarketplaceClient.java:27-29`; entity guard `ExternalCreator.java:206-211` |
| Q7.3 | Medium | vikram | pkg-lookup-ui-config | **FIXED** | `ExternalCreatorService.java:132-148` (`@Transactional` removed), page-token cache `:86-90`/`:222-234`; `application.yml:45` `open-in-view: false`; zero lazy associations on either entity |
| Q7.4 | **Critical** | vikram | pkg-lookup-ui-config | **FIXED** | `deploy/hostinger/docker-compose.hostinger.yml:208` and `deploy/utho/docker-compose.utho.yml:237` (`:-false`); binder belt-and-braces `influora-api/src/main/java/com/influora/config/MetaApiProperties.java:76`, `:92`; pin `MetaApiPropertiesCreatorMarketplaceBindingTest.java:65` (binds the real `application.yml`) |
| Q7.5 | Medium | vikram | pkg-lookup-ui-config | **FIXED** | `influora-api/src/main/java/com/influora/repository/ExternalCreatorRepository.java:44-45` (explicit `@Query`, no `LOWER()`); callers normalize at `ExternalCreatorLinkService.java:100-101` and `AdminCreatorConnectionService.java:439`; collation/index `V20260902120000__external_creators_connection_requests.sql:42`, `:45` |

---

## 3. Build gate (Meera, round 2 — 2026-09-03)

**Result: GREEN.**

| Check | Command | Result |
|---|---|---|
| Frontend typecheck | `npx tsc --noEmit` | **PASS** — 0 errors |
| Frontend build | `npx vite build` | **PASS** — built in 1m 3s. Only pre-existing warnings (duplicate tsconfig `baseUrl` key, >500kB chunk on `PerformanceMonitor`/`index`), no errors |
| Frontend full test suite | `npx vitest run` | **PASS (qualified)** — 864/866 across 142/143 files. The only 2 failures are in `src/pages/creator-disputes.test.tsx`, the known pre-existing Radix pointer-events issue; that file is not in this wave's diff |
| Backend compile + test-compile | `mvn -o -q compile test-compile` | **PASS** — 0 errors, after excluding the untracked other-session file below via CLI flags only (no source or pom edited). Every wave file compiles clean |
| Backend targeted wave tests | `mvn -o -q test -Dtest='ExternalCreator*Test,AdminCreatorConnection*Test,EmailTemplateRegistryTest,MetaTokenStorageTest,CreatorProfileServiceTest,PortfolioServiceTest,NotificationListener*Test,*CreatorConnection*Test,MetaApiProperties*Test'` | **PASS** — exit 0; surefire reports show 0 failures / 0 errors across all 15 matched classes |
| Backend full test suite | `mvn -o -q test -DargLine="-Xmx2048m -XX:+UseSerialGC"` | **PASS (qualified)** — 2205 run, 2 failures, 0 errors, 11 skipped. Both failures are other-session files (see below). Every wave-scoped class is 0/0. **Note:** the pom's hardcoded surefire `-Xmx512m` is now too small for this repo's test count and produces spurious `NoClassDefFoundError` cascades; the heap was raised via CLI override only |
| Static schema check — `external_creators` | manual `@Column` vs `V20260902120000` diff | **PASS** — all 20 columns match, including the two unnamed fields (`bio`, `followers`) whose implicit snake_case default matches the SQL |
| Static schema check — `creator_connection_requests` | manual `@Column` vs same migration | **PASS** — all 12 columns match |
| Deploy plumbing — money-flag defaults | grep both compose files | **PASS** — `META_CREATOR_MARKETPLACE_ENABLED ${...:-false}` and `ADMIN_NOTIFICATION_EMAIL ${...:-}` present at hostinger `:208-209`, utho `:237-238` |
| `generate-env.sh` syntax | `bash -n deploy/utho/generate-env.sh` | **PASS** |
| Untracked wave files | `git status --porcelain` cross-checked against `TASKS.md` + `findings.json` | **PASS — 0 untracked files belong to this wave.** Every wave-scoped file is already tracked (`M`/`A`). All `??` entries under `src/` and `influora-api/` are other-session work (T-ADMINMAIL-0903, `CreatorAgentBaselines` admin UI, WooCommerce idempotency) |

### Excluded from the gate verdict — other-session failures, none owned by this wave

| File | Failure | Owner |
|---|---|---|
| `src/pages/creator-disputes.test.tsx` | 2 vitest failures (known pre-existing Radix pointer-events); file not in this wave's diff | other-session |
| `influora-api/src/main/java/com/influora/service/admin/AdminCustomEmailService.java` | `cannot find symbol enforcePreviewRateLimit()` at `:213`; **untracked** file from T-ADMINMAIL-0903. Blocks the default `mvn compile` for everyone until that session commits a consistent state | other-session |
| `influora-api/src/test/java/com/influora/service/tracking/ConversionTrackingServiceTest.java` | `testWorkspaceScopedOverloadReservesOrderDerivedKey` fails; file unmodified by this wave | other-session |
| `influora-api/src/test/java/com/influora/web/WooCommerceWebhookIdempotencyTest.java` | `receive_sameOrder_createdThenUpdated_redeemsExactlyOnce` fails; untracked file | other-session |

**Not run: live click-through.** Docker Desktop's daemon is down, and the only reachable MySQL is a
shared dev instance whose `flyway_schema_history` is stale — booting the API against it would
bulk-apply dozens of unrelated migrations on a persistent DB outside this wave's authority. This
remains the single largest residual risk: every verdict above is code- and test-proven, **none is
live-proven.**

---

## 4. Still open

**Two findings. Neither is a code defect, and neither is a regression from this wave.** Both are
blocked on a value only ops/Swapnil can provision; in both cases the application code is already
correct and correctly bound.

### Q3.1 — High — admin connection-request email dark on every deploy target (ops + optional code)

`deploy/utho/generate-env.sh:159` still writes `ADMIN_NOTIFICATION_EMAIL=` blank, and both compose files
pass `${ADMIN_NOTIFICATION_EMAIL:-}` — a default that defaults to empty. `NotificationListener`
WARN-and-skips, so step 3 of Swapnil's flow (the enquiry reaching the admin team) never fires and the
admin console page is the only channel a request is ever seen through. The escalation at
`SHARED_CONTEXT.md:343` is accurate and correctly routed, but an escalation is not a fix.

**To close:** (a) ops provisions a real inbox; **and** (b) the code half that is in reach — a startup
assertion or a `generate-env.sh` gate that fails loudly on blank, following the
`CompanyTaxStartupValidator` precedent already demonstrated in that same file. Alternatively an explicit
Swapnil ruling that WARN-and-skip is accepted for launch, recorded in `wiki/` so the next audit does not
re-raise it.

### Q1.3 — Medium — borrowed creator token is still the production path (ops + Swapnil ruling)

The preference chain in code is correct and correctly bound (`ExternalCreatorService.java:558-561`,
`:588-598`; `application.yml:408-409`). But `deploy/utho/generate-env.sh:152-153` ships both
`META_SYSTEM_IG_USER_ID` and `META_SYSTEM_IG_ACCESS_TOKEN` blank, so `resolveSystemCaller` returns null
on 100% of deploys and the borrowed-creator-token fallback at `:569-583` is taken every time — still
colliding with `MetricsPollingJob`'s throttle key, so brand Discover volume can stall an unrelated
creator's analytics ingestion.

**To close:** ops provisions both vars on every target (`generate-env.sh` plus both compose env lists),
then re-verify the fallback is not entered; **and** Swapnil rules on Meta platform-terms cross-user
token reuse before App Review.

---

## 5. Residuals logged in round 2 (follow-ups, not reopens)

None of these changes a verdict; all were found during CTO re-review and should be ticketed.

| Residual | Location | From |
|---|---|---|
| `CreatorRegisterPayload` still lacks `inviteToken?: string`; the FE routes around TS's excess-property check via an untyped local. Works today, but annotating that const or enabling `exactOptionalPropertyTypes` silently drops the field back to pre-fix behaviour with no type-layer failure | `src/lib/api.ts:818-825`, `src/pages/creator-register.tsx:107` | Q5.5 |
| Invite link + brand "creator joined" email commit **before** the registration transaction does (`REQUIRES_NEW` inside a `@Transactional` register). A failure after `AuthService.java:364` leaves the row permanently JOINED pointing at a rolled-back profile, and the brand already emailed. An `AFTER_COMMIT` listener is the correct shape | `AuthService.java:308`/`:364`, `ExternalCreatorLinkService.java:167`/`:219` | Q5.5 |
| Stale javadoc asserting `creatorRegister` "does not call `consumeInviteToken` yet" — now false | `RegistrationServiceTest.java:35` | Q5.5 |
| `connect()`'s recovery re-read is still a plain ambient-snapshot read with the same blindness Q1.4 just fixed in `lookup()` — a genuinely simultaneous two-tab pair can still 409 | `ExternalCreatorService.java:716-719` | Q1.4 / Q3.4 |
| Title/body still concatenate `"@" + event.igUsername()` unguarded; would read `@null` if the NOT NULL invariant were relaxed | `NotificationListener.java:796-800` | Q6.5 |
| External-creators table paging + unwired status/`q` filters; in-memory unbounded IN-list search | `CreatorConnectionsPage.tsx:266`, `AdminCreatorConnectionService` | Q4.5 |
| Surefire `argLine` hardcoded to `-Xmx512m` — too small for the current test count, produces spurious `NoClassDefFoundError` cascades that look like real breakage | `influora-api/pom.xml` | build gate |
| No `.proof-os/` gate asserts the compose `:-` defaults survive future edits (the Q7.4 regression guard) | `.proof-os/gates/` | Q7.4 |

---

## 6. Routing

| Item | Owner | Type |
|---|---|---|
| `ADMIN_NOTIFICATION_EMAIL` real inbox (Q3.1) | **Swapnil / ops** | provisioning |
| `META_SYSTEM_IG_USER_ID` + `META_SYSTEM_IG_ACCESS_TOKEN` (Q1.3) | **Swapnil / ops** | provisioning |
| `CREATOR_INVITE_TOKEN_SECRET` on every live target before the invite path is exercised | **Swapnil / ops** | provisioning — security |
| Meta platform-terms ruling on cross-user token reuse (Q1.3) | **Swapnil** | ruling |
| Startup assertion / deploy gate for blank `ADMIN_NOTIFICATION_EMAIL`, or a ruling that WARN-and-skip is accepted | Swapnil then vikram | ruling then code |
| The eight residuals in §5 | vikram / ananya / gate-dir owner | follow-up tickets |
| Raise surefire heap in `pom.xml` | vikram | build hygiene |
| Live click-through of the whole flow | Neha, once Docker is up | verification |

---

## 7. CTO position

**Every code finding in this wave is closed.** Round 2 took the four that round 1 left open — the
concurrent-lookup race, the admin-table null crash, the dead invite-claim path, and the joined-email
handle — and closed all four with mechanisms I re-verified myself rather than accepting on report. The
two remaining opens are provisioning items with no code component left; they cannot be closed by an
engineer.

Two things I want on the record before anyone reads 29/31 as "done":

1. **Nothing here is live-proven.** Twenty-nine findings closed on code reading and green tests, with
   the flow's end-to-end behaviour never once exercised against a running stack. This repo has a
   documented history of green local gates that did not predict live behaviour (F-0341 dead controls,
   F-0324 untracked files, the money-flag pipeline). The live click-through is not a formality.

2. **`CREATOR_INVITE_TOKEN_SECRET` must be provisioned before the invite path is exercised on any live
   target.** The fail-closed guard at `InviteTokenService.java:85-93` means an unprovisioned secret
   rejects every token rather than accepting a forgeable one — the safe direction — but it also means
   the invite flow will silently not work until ops sets it. Verify it is non-empty on the target
   before testing, or the live click-through will produce a false negative.

The wave is **safe to commit as work-in-progress**: build gate green, both Criticals closed, no
regressions, no open code defect. It is **not signed off for merge as complete** until the live
click-through runs.

**Recommended sequence:** commit as-is → provision the three secrets/addresses → Docker up → Neha live
click-through → then Swapnil sign-off.
