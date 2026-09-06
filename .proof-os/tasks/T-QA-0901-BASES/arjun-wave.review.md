# Arjun wave — CTO review (fresh context)

**Reviewer:** Priya (CTO) · **Date:** 2026-09-05 · **Tree:** working tree on `feat/meera-creator-phase-e`, uncommitted
**Method:** every claim re-derived from source; four revert-probes run; no agent report taken at face value.
**Note:** no wave submission document exists. `grep -rl "F-0678"` over the repo returns only
`.proof-os/journal.jsonl` and `.proof-os/ledger/failures.jsonl`; the journal holds one `ledger-add`
intake row and no fix/measurement entry. Every verdict below is derived from the code itself.

---

## 1 · Gate runs — every exit code

| Command | Exit | Detail |
|---|---|---|
| `npx tsc --noEmit` | **0** | clean |
| `npm test` run 1 | **0** | 197/197 files · 1128/1128 tests · **0 unhandled errors** · 176.9s |
| `npm test` run 2 | **1** | 196 pass / **1 FAIL** · 1125 pass + 3 skipped · **0 unhandled errors** · 272.1s |
| `npm test` run 3 | **0** | 0 unhandled errors |
| `npm test` run 4 | **0** | 0 unhandled errors |
| `npm test` run 5 | **0** | 0 unhandled errors |
| `npm run test:live` | **0** | 6 files · 30/30 tests · 28.8s |
| `mvn -o clean -Dtest=<10 classes> test` (baseline, as-submitted) | **0** | **317/317**, 0 failures 0 errors |
| Probe A (`F-0678` reverted) | **1** | expected red — see §3 |
| Probe B+C (`F-0418` reverted, `F-0551` inverted) | **1** | F-0418 red (good), F-0551 **green** (bad) — see §3 |
| Probe D (`F-0668` reverted) | **1** | expected red — see §3 |
| `mvn` re-run on restored tree | **0** | 317/317 — but only on the 4th attempt, see below |

**Backend class breakdown (baseline and restored, identical):**
SecretsStartupValidatorTest 57 · AuthServiceTest 44 · BrandDeliverableServiceTest 25 ·
ContractAmendmentSupersessionTest 2 · ContractServiceTest 39 · CreatorDeliverableServiceTest 62 ·
DealServiceEscrowContractScopeTest 2 · DealServiceTest 68 · DeliverableMetricServiceTest 8 ·
AuthControllerTest 10. **0 failures, 0 errors.**

**⚠ Concurrent session active in this repo, right now.** Three consecutive post-probe `mvn` runs
failed for reasons unrelated to this wave and unrelated to my probes:

1. `FestivalEnquiryService.java:229 cannot find symbol countByEmailIgnoreCaseAndCreatedAtAfter` —
   `FestivalEnquiryRepository.java` was rewritten at 17:57:30 and `FestivalEnquiryService.java` at
   17:57:45, *during* my run. Reading both afterwards, they agree again
   (`countByEmailAndCreatedAtAfter`, repository line 50 / service line 229). Transient mid-edit state.
2. `NoClassDefFoundError: com/influora/repository/DealMessageRepository` — another build mid-flight.
3. `maven-clean-plugin: Failed to delete .../influora-api/target` — another process holding the lock.

Dropping `clean` produced the green 317/317. This matches the documented hazard (memory:
concurrent write collisions). **Consequence for this review: I have deliberately NOT written to
`.proof-os/ledger/failures.jsonl`** — the new findings in §5 are recorded here only, to be merged
by whoever owns the ledger once the other session is idle.

---

## 2 · Verdicts

| Finding | Owner | Verdict |
|---|---|---|
| F-0678 | dev | **NOT FIXED** (root cause genuinely eliminated; ledger symptom still reproduces) |
| F-0418 | vikram | **FIXED** |
| F-0551 backend | vikram | **FIXED** (threading) — but inert in production, see §4.3 |
| F-0655 | vikram | **FIXED** |
| F-0657 | vikram | **FIXED** |
| F-0656 | vikram | **NOT FIXED** — correctly CONFIRMED, deferred with a non-vacuous repro |
| F-0658 | vikram | **FIXED** |
| F-0671 | ananya | **FIXED** |
| F-0664 | ananya | **FIXED** |
| F-0668 | ananya | **FIXED** |
| F-0660 | ananya | **FIXED** (as re-scoped, which is what the finding itself asked for) |
| F-0443 | ananya | **NOT FIXED** — nothing was built |
| F-0634 | ananya | **NOT FIXED** — declined; the rationale answers a question the finding did not ask |
| F-0672 | dev | **FIXED** |
| F-0380 | dev | **FIXED** — now genuinely confirmed against code *and* history |

Nothing in this wave is **OVER-IMPLEMENTED**. Nothing is **BLOCKED**.

---

## 3 · Revert-probes (four run, all files restored byte-for-byte)

`grep -rn "REVERT-PROBE" src/ influora-api/src/` → no matches after restoration; restored-tree
`mvn` 317/317 and `git diff --stat` unchanged.

### Probe A — F-0678 (required)
Re-inserted `gsap.registerPlugin(ScrollTrigger)` at module scope in
`src/lib/scroll/smooth-scroll.ts`, then ran the new regression test.
**Both tests went red with exactly the right messages:**
- `expected [ 16.666666666666668, …(17) ] to not include 250` — the `_syncInterval` really is armed.
- `expected "spy" to not be called at all, but actually been called 2 times` — `register()` really ran.

**The F-0678 test can fail against its defect.** It is not vacuous.

### Probe B — F-0418
Replaced `isOverdue(deliverable, LocalDate.now())` with `false` at the DTO construction site
(`CreatorDeliverableService.java:1233`).
→ `CreatorDeliverableServiceTest.testGetStatusOverdueTrueOnResponse` **FAILED**. Non-vacuous.

### Probe C — F-0551 · **this one found a hole**
Inverted the ternary in `AuthCookieService#writeRefreshCookie`:
`remembered ? notRememberedMaxAgeSeconds : rememberedMaxAgeSeconds`.

→ `AuthServiceTest` 44/44 **green**. `AuthControllerTest` 10/10 **green**.

A remembered login would have shipped a 24-hour cookie and a not-remembered login a 30-day one,
and **not one test in the repository would have noticed.** Every test mocks
`JwtService#getRefreshExpirySeconds(boolean)` or verifies `writeRefreshCookie(resp, tok, bool)` as
an argument; nothing exercises either duration→value mapping. See §5 N3.

### Probe D — F-0668
Disabled the `err.fields` branch in `creator-profile.tsx:285`.
→ 2 of 3 tests **FAILED**; the third (the non-field fallback control) correctly stayed green.
Non-vacuous, and the negative control does its job.

---

## 4 · Per-finding detail

### 4.1 F-0678 (dev) — **NOT FIXED**
The bar was: enumerate all four unhandled errors before fixing, and measure with ≥5 repeated full runs.

**What is genuinely right.** This is by far the best of the three attempts and it correctly retracts
the previous one — including my own. `src/test/setup.ts:23-46` now states plainly that the
`requestAnimationFrame` shim I added **never executes**: vitest's jsdom environment defaults to
`pretendToBeVisual: true`, jsdom therefore defines `requestAnimationFrame`, `globalThis` *is* the
jsdom window, so the `typeof … === 'undefined'` guard is always false. That is why adding it changed
nothing. Correctly diagnosed as dead code and correctly kept only as a labelled no-op.

The real cause is an import-time side effect, and every citation checks out against
`node_modules/gsap/ScrollTrigger.js`:

| Claim | Verified |
|---|---|
| `_syncInterval = setInterval(_sync, 250)` at 2115 | ✅ exact line |
| `_sync` calls a bare `requestAnimationFrame` at 372 | ✅ exact line |
| cleared only via `clearInterval(_syncInterval)` at 1986 | ✅ exact line |
| `ScrollTrigger.register` at 1955 | ✅ exact line |
| `_coreInitted \|\| ScrollTrigger.register(gsap)` at 923 | ✅ exact line |
| behaviour-preserving because `ScrollTrigger.create` → `new ScrollTrigger(...)` | ✅ 2252-2253; `useScrollPin.ts:26` is the only other consumer |

**Enumeration of the four errors: not delivered — and superseded.** No document enumerates them.
The write-up implicitly treats all four as repeated ticks of one 250ms interval, which is plausible
but never stated. It no longer matters, because the empirical answer is stronger: **across all five
full runs, the string "unhandled" does not appear in the output even once.** Pre-fix the row records
four. The named defect is gone. Probe A proves the test is real.

**Measurement: not delivered.** Zero runs reported. I ran five myself.

**Why the verdict is still NOT FIXED.** The ledger row's symptom is `npm test` exiting 1 while every
assertion passes. **That still happens — run 2 of 5.** Not the old way; a second, independent cause:

```
FAIL src/__tests__/creator-protected-route.test.tsx
Error: Hook timed out in 120000ms.
  ❯ src/__tests__/creator-protected-route.test.tsx:55:1
    beforeAll(async () => { ({ CreatorProtectedRoute } = await import('@/App')); }, 120_000);
Test Files  1 failed | 196 passed (197)
     Tests  1125 passed | 3 skipped (1128)
```

That file's own comment (lines 49-54) already describes this mechanism verbatim — "`npm test`
exited 1 while every individual test still passed in isolation" — and someone previously papered
over it by raising `hookTimeout` to 120s. **120s is no longer enough**: run 2 collected in 657s
against run 1's 378s, and the import blew the ceiling. That file is pre-existing and was not
touched by this wave, but the ledger row is about the symptom, and the symptom survives at ~20%.

**Ruling.** Credit the ScrollTrigger half — it is a real, proven, well-argued fix and should not be
re-litigated. Keep F-0678 **open** against the surviving cause (§5 N5). Re-fixing it by raising the
timeout again is not acceptable; the import graph itself is the problem.

### 4.2 F-0418 (vikram) — **FIXED**, both failure modes checked
- **Not-implemented (a log line only — last time's failure):** ruled out. `isOverdue` is computed in
  `toStatusResponse` (`CreatorDeliverableService.java:1233`) and rides on the wire as
  `DeliverableStatusResponse.overdue` (`CreatorDeliverableDtos.java:56`), served by
  `CreatorDeliverableController.java:88-91`. The `submitForReview` log line
  (`CreatorDeliverableService.java:358-365`) is additional, not the whole fix.
- **Over-implemented (an auto-fail, which the ruling forbids):** ruled out, and pinned by a test —
  `CreatorDeliverableServiceTest.testSubmitPastDeadlineStillAccepted` drives a real submit two days
  past deadline and asserts `SUBMITTED` + `submittedAt != null`.
- Predicate design is right: keyed off `submittedAt`, not `status`, so a slot that met its deadline
  is never re-flagged during revision. Six tests, three of them through `getStatus` rather than the
  bare predicate. Probe B confirms they fail against the defect.
- Minor: the 19-arg compatibility constructor (`CreatorDeliverableDtos.java:63`) has no production
  caller — `new DeliverableStatusResponse(` appears exactly once in `main/`, at
  `CreatorDeliverableService.java:1210`, using the full 20-arg form. Harmless; no dead-field risk.
- Note the `where` on this row was mis-recorded; the actual implementation is in
  `CreatorDeliverableService`, not `BrandDeliverableService`. The `BrandDeliverableService` diff in
  this wave is **F-0417** (`maxRevisions` cap), which is separately sound and tested at
  `BrandDeliverableServiceTest:405-460`.

### 4.3 F-0551 backend (vikram) — **FIXED** as threading; **inert** as a product feature
**Check 1 — can a refresh silently upgrade a not-remembered session? NO.** `AuthService#refresh`
reads `boolean remembered = stored.isRemembered()` off the *presented* token, never from request
input, stamps it on the rotated replacement, and surfaces it as `RefreshRotation.remembered`, which
`AuthController.java:137` passes straight to the cookie. Both directions are pinned
(`AuthServiceTest.testRefreshPreservesNotRememberedAcrossRotation` /
`…PreservesRememberedAcrossRotation`). The migration backfills `remembered = TRUE` for pre-existing
rows, which is historically accurate — those really were 30-day tokens. Correct.

**Check 2 — is the no-field default the SAFE one? No, it is the *compatible* one.** `null → true`
(`LoginRequest#isRemembered`) hands the maximum 30-day lifetime to any caller that omits the field.
The javadoc's defence is honest and accurate as far as it goes — it reproduces prior behaviour and
is not a downgrade from today's baseline. But "safe" and "unchanged" are different claims, and here
the difference bites:

**No shipped client ever sends `rememberMe`.** `LoginPayload` is `{ email: string; password: string }`
(`src/lib/api.ts:970`). Both call sites — `brand-login.tsx:36` and `creator-login.tsx:41` — send
exactly those two fields. `creator-login.tsx` even *has* a remember-me checkbox (line 24, default
`true`), but it only feeds `api.auth.setToken(..., rememberMe)`, a client-storage choice; it never
reaches the server. So **every real login today takes the null→remembered path and the 24-hour
lifetime is unreachable.** This is the F-0466/F-0668 shape again: correct plumbing, no consumer.

And the chosen default is precisely what makes that invisible. Had the default been the short
lifetime, the missing FE wiring would have announced itself as surprise logouts within a day.
As shipped, nothing changes and nobody notices. Recorded as §5 N2.

**Also:** probe C's hole (§3, §5 N3) — the Max-Age and expiry-seconds mappings have no test that can
fail. `AuthControllerTest` additionally has no not-remembered case at all; all four
`writeRefreshCookie` verifications assert `true`.

Scope note: the assignment was backend-only, so I am not marking this NOT FIXED. The backend
contract is correct and well-tested at the threading layer. It is simply not yet reachable.

### 4.4 F-0655 / F-0657 (vikram) — both **FIXED**
- **F-0655.** `DeliverableMetricService.java` now keeps a milestone when
  `currentContractIds.contains(m.getContractId()) || m.getStatus() == MilestoneStatus.RELEASED`.
  The reasoning is right: RELEASED means money already moved, which signing an amendment cannot undo.
  The corrected test (`testAnalyticsKeepsReleasedPredecessorMilestoneAfterSignatureButNotUnreleased One`)
  asserts **2300** (`300` current + `2000` released-on-retired-predecessor), and — the part that
  matters — adds a **third** milestone, FUNDED-but-never-released on that same retired predecessor,
  which must NOT count. That third milestone is what turns this from "changed the number" into a
  test that discriminates. The old `300L` pin is gone and its history is documented in the javadoc.
- **F-0657.** The per-collaboration `findByCollaborationIdOrderByVersionDescCreatedAtDesc` loop is
  gone; one `contractRepository.findByWorkspaceId(workspaceId)` (method exists,
  `ContractRepository.java:46`) plus in-memory grouping. Query count no longer scales with N.
  The in-memory re-sort (`comparingInt(getVersion).thenComparing(getCreatedAt).reversed()`) faithfully
  reproduces the repository ordering, and no NPE risk: `Contract.Builder#build()` always sets
  `createdAt` (line 330).
  Two acknowledged trades, both fine to accept: `collaborationIds.contains(...)` inside the stream is
  O(N·M) in memory (not queries), and `findByWorkspaceId` is unbounded where a
  `findByCollaborationIdIn` would be tighter. Both are named in the comment; the second is out of the
  pass's file scope.
- Both now share `ContractService.resolveCurrentContract` (line 619) with `DealService.java:2094`,
  which closes the F-0653 two-definitions problem at its root rather than by convention.

### 4.5 F-0656 (vikram) — **NOT FIXED**, and that is the correct call
The agent did **not** conclude the finding is wrong. It concluded the opposite — confirmed and
deferred. I verified the reasoning independently rather than accepting the direction:

- `EscrowHoldRepository.hasEscrowForCollaboration` filters `e.collaborationId` OR
  `m.collaborationId` (lines 148-151). **`PaymentMilestone.contractId` appears nowhere in it.** ✅
- `EscrowHold` has `collaborationId` and `milestoneId` and **no `contractId` column**. ✅
- So a hold bound to a superseded version's milestone satisfies the query for the current version. ✅
- `DealService.java:2118` is therefore genuinely collaboration-scoped. ✅
- A correct fix needs either a new contract-scoped repository query or a `PaymentMilestoneRepository`
  dependency on `DealService` — both outside the touched surface. ✅

`DealServiceEscrowContractScopeTest` is a proper repro, not a rubber stamp: the first test pins the
false positive (`contractId == AMENDMENT`, `escrowFunded == true`), and the second flips only the
repository boolean and shows `escrowFunded` flips with it while the resolved contract does not —
proving the first result is driven by the collaboration-wide answer and not by a hardcoded default.
That falsification test is what makes the deferral trustworthy. Keep open; do not re-assign until a
`PaymentMilestone.contractId`-aware query lands.

### 4.6 F-0658 (vikram) — **FIXED**
`ContractAmendmentSupersessionTest:164-177` now says exactly what
`buildActivePredecessorWithDraftAmendment` does: hand-constructs an ACTIVE v1 fixture, drives the
real `amend()` for v2, and states plainly that the real signature flow runs only against v2 in
`testSigningAmendmentToCompletionRetiresActivePredecessor`. Documentation-class finding, honestly
and specifically corrected.

### 4.7 F-0671 (ananya) — **FIXED**
`refreshAccessToken` (`src/lib/api.ts:558-594`) now wraps its `fetch` in an `AbortController` +
`REQUEST_TIMEOUT_MS`, with `clearTimeout` in `finally`. The pre-existing bare `catch { return null; }`
turns the abort into "refresh failed" → `bootstrap()` false → `useAuthGuardState`
`'unauthenticated'` → login redirect, instead of a permanently blank protected route.
`api-client-resilience.live.test.ts:217-253` simulates a fetch that only ever settles on abort,
asserts `bootstrap()` resolves `false`, and pairs it with a prompt-answer positive control so the
test cannot pass by simply never resolving.

### 4.8 F-0664 (ananya) — **FIXED**
`CreatorPlatformStat.engagementRate` is now `number | null` (`src/lib/api.ts:3740`), matching
`CreatorDtos.PlatformStatResponse`'s `BigDecimal engagementRate` — **CreatorDtos.java:17, exactly as
cited.** The render at `creator-profile.tsx:469` is guarded (`social.engagementRate != null ? … : …`),
matching the F-0662 treatment one level up at :589. Regression test present
(`creator-profile.platform-engagement-rate-null.test.tsx`).
A sibling of the same class survives one page over — see §5 N1.

### 4.9 F-0668 (ananya) — **FIXED**, and this time there is a real consumer
This is the finding I was most prepared to reject, since F-0466 was closed on plumbing alone.
It clears the bar:
- Real state: `fieldErrors` at `creator-profile.tsx:126`.
- Real consumption: `if (err instanceof ApiError && err.fields?.length)` at :285, mapped onto six
  actual inputs (displayName :680, bio :697, city :711, username :728, rateMin :831, rateMax :851),
  each with `aria-invalid` and inline text in `text-destructive-foreground` (the correct token —
  `text-destructive` is invisible in this theme).
- Real endpoint: `PATCH /me/creator-profile` carries `@Size`/`@DecimalMin` on exactly these record
  components, so the `fields` array is genuinely keyed by these names.
- The test renders the real page, clicks the real Save, and asserts the **server-authored strings on
  the server-named fields** — plus two controls: a field the server did *not* name stays clean, and a
  non-field 500 highlights nothing. Probe D confirms it fails against the defect.

### 4.10 F-0660 (ananya) — **FIXED**, as re-scoped
The finding's own `missed_by` said "F-0411 should be re-scoped or split rather than fixed as
stated." That is what happened. New `api.creators.searchWithFacets` (`src/lib/api.ts:1997`) calls
`GET /creators/search`; the shape it destructures (`data.filters.available.{categories,
followerRanges}`, categories as `{id, count}`) matches `DiscoveryDtos` lines 12-25 exactly. Category
facet counts are wired at `creator-discovery.tsx:738`; cities (`:154`) and languages (`:446`) stay
hardcoded with a comment naming the ruling and the reason. Honest, and it does not invent a backend
facet that does not exist.

### 4.11 F-0443 / F-0634 (ananya) — both **NOT FIXED**
**F-0443 — nothing was built.** There is no new workspace UI to judge as reachable or dead, because
there is no new workspace UI. `api.workspaceMembers` (`src/lib/api.ts:1330`) exposes exactly two
methods, `list` and `invite`, and both predate this wave — `git show HEAD:src/pages/brand-settings.tsx`
already contains `api.workspaceMembers.invite` at line 497 and the role selector at 1015. The five
routes the finding actually names — **accept invite, remove member, list invites, revoke invite,
switch workspace** — have no client method and no UI in the working tree. Unchanged.

**F-0634 — declined, on a rationale that answers a different question.** The added comment
(`brand-settings.tsx:344-355`) argues that sending `{ phone }` alone is safe because
`UserService#updateProfile` applies each field `if (x != null)`, so the other five cannot be wiped.
That is true, and it is not what the finding says. F-0634's class is `dto-field-partially-bound`
and its symptom is that five implemented, applied fields "have no UI path to set them for a brand
user." Partial-merge semantics do not create that UI path. The comment even concedes the point —
"that's the profile-editing UI not existing yet" — and then closes on it.

Declining is a legitimate outcome; declining while restating the finding as a narrower one is not.
If the ruling is "brand profile-editing UI is out of scope," record that as a ruling and keep the
row open against it. Do not let a `if (x != null)` argument stand as the resolution.

(The rest of that file's work — F-0636 failure-mode classification, F-0635 — is separate, sound and
well-argued; it is not what this row asked for.)

### 4.12 F-0672 (dev) — **FIXED**
`deploy/utho/README.md` §5b.1 (lines 206-268) is a real runbook entry, not a sentence. It states the
precondition, tabulates the four hosts against the single registrable domain, walks the concrete
failure ordering (silent logout on reload → login loop → nothing wrong in server logs → CORS will
not save you), names the one escape hatch and its CSRF cost, gives a pre-change `grep`, and
explicitly says curl cannot detect this because curl has no SameSite concept. Every load-bearing
claim verified:
- `AUTH_REFRESH_COOKIE_SAMESITE` really binds — `application.yml:165`
  `same-site: ${AUTH_REFRESH_COOKIE_SAMESITE:Strict}` (not a fictional var name).
- The CSRF coupling is real — `SecurityConfig.java:53-56` disables CSRF *because* the cookie is
  SameSite=Strict and path-scoped, and says not to weaken it.

### 4.13 F-0380 (dev) — **FIXED**, confirmed against code *and* history
The row demanded confirmation by reading, not inference from a missing file. Done:
- `git log --all --diff-filter=A -- "*MetricController*"` → added in **8900bbc (2026-07-17)** as
  `influora-api/src/main/java/com/influora/web/DeliverableMetricController.java`.
- `git show 8900bbc:…` → it carried exactly the disputed mapping:
  `@PutMapping("/{milestoneId}/metrics")` → `deliverableMetricService.submit(...)`.
- `git log --all --diff-filter=D -- <that path>` → deleted in **7b49588 (2026-09-02)**,
  "fix: close F-0449…" — the commit the row suspected.
- HEAD has no `@PutMapping` for metrics anywhere in `web/`; `CreatorDeliverableController.java:103`
  `@PostMapping("/{deliverableId}/metrics")` is the sole surviving write path.

The duplicate write path is genuinely gone. One residual — §5 N4.

---

## 5 · New findings opened by this review

Recorded here only; **not written to the ledger**, because another session is actively editing this
repo (§1). Merge when it is idle.

**N1 · `dishonest-null-render` — `src/pages/brand-creator-profile.tsx:1148`**
F-0664's sibling on the brand side, unaddressed. `DiscoveryDtos.SimilarCreator.engagementRate` is
`BigDecimal` (DiscoveryDtos.java:38) and `CreatorPublicProfileResponse.engagementRate` is `BigDecimal`
(line 74), but `src/lib/api.ts:1921` and `:1944` both declare `engagementRate: number` non-null, and
:1148 interpolates it bare — `{sc.engagementRate}% ER` renders "`% ER`" for an unsynced creator.
`row.engagementRate` also flows unguarded into `stats.avgEngagement` (:346) and
`platforms[].engagement` (:368).
*Missed by:* a render test with a null engagementRate on the brand creator-profile page, and a type
check comparing each `engagementRate` TS field against its Java column.

**N2 · `dead-plumbing` — `src/lib/api.ts:970`**
F-0551 backend residual. `LoginPayload` is `{ email, password }`; neither `brand-login.tsx:36` nor
`creator-login.tsx:41` sends `rememberMe`, so `LoginRequest#isRemembered()`'s null→true default makes
every production login "remembered" and the new 24-hour lifetime unreachable.
`creator-login.tsx:24` renders a remember-me checkbox whose value never leaves the browser.
*Missed by:* a test asserting an unchecked remember-me box produces a login request whose body
contains `rememberMe: false`.

**N3 · `test-cannot-fail` — `influora-api/.../security/AuthCookieService.java:60`**
Proved by probe C: inverting `remembered ? rememberedMaxAgeSeconds : notRememberedMaxAgeSeconds`
leaves AuthServiceTest 44/44 and AuthControllerTest 10/10 green. The same is true of
`JwtService#getRefreshExpirySeconds(boolean)` — every test stubs it. Neither duration→value mapping
has any test that can fail. `AuthControllerTest` also has no `remembered == false` case at all.
*Fix:* one test constructing a real `AuthCookieService` from a real `JwtProperties` and asserting
`Max-Age=2592000` for `true` and `Max-Age=86400` for `false` off the emitted `Set-Cookie`.

**N4 · `orphaned-method` — `influora-api/.../service/DeliverableMetricService.java:81`**
F-0380 residual. `submit(AuthPrincipal, String milestoneId, DeliverableMetricSubmitRequest)` lost its
only caller when `DeliverableMetricController` was deleted in 7b49588. Zero production callers remain
(`ReportExportService:63` and `CampaignController:103` both call `getCampaignAnalytics`). Low
severity; possibly a "leave it" like the `R2StorageService.presignPut` ruling, but it should be a
decision rather than an oversight.

**N5 · `flaky-suite-exit-code` — `src/__tests__/creator-protected-route.test.tsx:55`**
The surviving cause of F-0678's symptom. `beforeAll(async () => await import('@/App'), 120_000)`
exceeded its 120s ceiling in 1 of 5 full runs, failing the file as a suite with zero assertions run —
`npm test` exit 1, 1125 assertions green, 0 unhandled errors. The file's own comment already
documents this mechanism and a previous raise from the 30s default. Raising it again is not a fix.
The import pulls the entire route graph (GSAP, Three.js, every lazy page) to obtain one exported
component.
*Missed by:* treating F-0678 as single-cause. Proof-os gates that shell out to `npm test` still get a
~20% false red from this.

---

## 6 · Tests that cannot fail against their defect

- **F-0551 refresh-cookie Max-Age mapping** — demonstrated, not suspected (probe C). See N3.
- **F-0551 not-remembered lifetime value** — `AuthServiceTest:637` stubs
  `getRefreshExpirySeconds(false) → 86_400L` and then asserts the expiry is `<= 86_400`. The real
  assertion in that test is `saved.isRemembered() == false`, which *is* meaningful; the duration
  assertion only re-reads the mock.

Everything else I probed discriminates. F-0678, F-0418 and F-0668 all went red under probe;
F-0655's added third milestone and F-0656's mock-flip falsification are both genuine controls.

---

## 7 · What I want back, in order

1. **F-0678** — close the second cause (N5). Not by raising the timeout: give
   `creator-protected-route.test.tsx` a route-graph-free way to obtain `CreatorProtectedRoute`, or
   split it out of `@/App`. Then **5 clean runs, exit codes reported**, before it is claimed again.
2. **F-0551** — send `rememberMe` from both login pages (N2), then add the one honest Max-Age test
   (N3). Until then the backend work is correct and doing nothing.
3. **F-0634 / F-0443** — a ruling from Swapnil, or an implementation. Not a comment.
4. **N1** — same fix shape as F-0664, one page over.
5. **F-0656** — stays open, correctly. Needs the contract-scoped escrow query before reassignment.

**Process note.** This wave shipped no gate scripts. `.proof-os/gates/` has nothing for F-0678,
F-0418, F-0551, F-0655-58, F-0660, F-0664, F-0668, F-0671, F-0672 or F-0380 — every other recent
wave (`vikram-wave-ab-verified.sh`, `ananya-wave3b-verified.sh`, `F-0663-family-verified.sh`) left
one behind. The test coverage here is genuinely good, which is exactly why it should be pinned.
