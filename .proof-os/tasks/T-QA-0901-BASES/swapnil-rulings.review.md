# Priya — CTO review of four CEO-approved rulings

**Reviewer:** Priya (CTO), fresh context
**Date:** 2026-09-05
**Branch:** `feat/meera-creator-phase-e` (working tree, uncommitted)
**Scope:** verify implementation fidelity and safety of F-0631, F-0643, F-0418, F-0551. Not a
relitigation of the decisions themselves.

---

## Verdict summary

| Ruling | Verdict | One-line reason |
|---|---|---|
| F-0631 | **IMPLEMENTED** | Tile and list now share one source of truth; backend query matches the claim exactly. |
| F-0643 | **IMPLEMENTED** (backend faithful; frontend half not shipped) | Gate is correct and load-bearing, but every Accept button on an invite/application is now a guaranteed 409 with no rate-entry affordance. |
| F-0418 | **NOT IMPLEMENTED** (the constraint is honoured, the deliverable is absent) | "No auto-fail" is correctly respected — not over-implemented — but "notify + visible overdue state" reaches no client: `isOverdue`'s only consumer is a log line. |
| F-0551 | **IMPLEMENTED, SHIP-BLOCKING DEFECT** | Memory-only store is correct and proven in the named files, but the second API layer (`meera-api.ts`) still reads the storage key and now sends `Authorization: Bearer session-active`. |

**Not blocked.** No ruling was blocked on a missing HttpOnly refresh cookie. See F-0551 §"BLOCKED
claim".

---

## Commands run — all exit codes

| Command | Exit | Result |
|---|---|---|
| `npx tsc --noEmit` | **0** | clean |
| `npm test` | **0** | 183 files / 1068 tests passed |
| `npm run test:live` | **0** | 5 files / 25 tests passed |
| `mvn -o clean -Dtest='DealServiceTest,DealServiceBudgetTest,CreatorDeliverableServiceTest,BrandDeliverableServiceTest,ContractServiceTest' test` | **0** | 194 tests, 0 failures (DealServiceTest 68, CreatorDeliverableServiceTest 59, ContractServiceTest 39, BrandDeliverableServiceTest 25, DealServiceBudgetTest 3) |

Every gate is green. **Green does not mean correct here** — the F-0551 defect below is invisible to
all four, because no test in either suite exercises `src/lib/meera-api.ts` under live mode.

---

## Revert probes (3 run; F-0551 was one)

I falsified the implementations myself rather than trusting the authors' falsification notes.

### Probe 1 — F-0551 (required)

Reverted `HttpClient.getToken`/`setToken` (`src/lib/api.ts`) and `persistBrandSession`
(`src/lib/auth-session.ts`) to their pre-ruling, storage-backed form.

`npx vitest run --config vitest.live.config.ts src/lib/__tests__/f0551-access-token-memory-only.live.test.ts src/__tests__/f0551-silent-refresh-on-load.live.test.tsx`
→ **exit 1, 4 of 9 red**, with the real credential literally present in storage:

```
AssertionError: expected [ 'true', …(1) ] to not include 'super-secret-real-bearer-credential-v…'
```

The store change is genuinely load-bearing. **Caveat:**
`src/__tests__/f0551-silent-refresh-on-load.live.test.tsx` (2 tests) stayed **green** against the
reverted store — it exercises `App.tsx`'s guard, which I did not revert, so it is orthogonal to the
memory-only rule and must not be counted as a gate on it. Files restored; suite re-run green
(exit 0).

### Probe 2 — F-0643

Commented out `requireAgreedRateForCommitment(collaboration);`
(`influora-api/src/main/java/com/influora/service/DealService.java:1131`).

`mvn -o -Dtest=DealServiceBudgetTest test` → **exit 1**:

```
DealServiceBudgetTest.testAcceptRejectsNullRateApplication:260
  expected: <AGREED_RATE_REQUIRED> but was: <CAMPAIGN_NOT_FOUND>
```

The gate is load-bearing. **Caveat:** with the gate removed the test fails on a *different* error
(`CAMPAIGN_NOT_FOUND`, an unstubbed repository in that fixture) rather than on "the accept
succeeded". That is a weaker falsification than the ideal — it proves the gate produces the code, not
that the accept would otherwise have completed. The behaviour change is nonetheless established
independently: eight pre-existing `DealServiceTest` cases had to be edited to set a rate before
accept (`DealServiceTest.java:432, 476, 537, 829, 1410, 1549, 2102, …`). File restored; re-run green.

### Probe 3 — F-0418

Stubbed `CreatorDeliverableService.isOverdue` to `return false`.

`mvn -o -Dtest=CreatorDeliverableServiceTest test` → **exit 1, 1 of 59 red**
(`testIsOverduePastDeadlineNotSubmittedIsTrue`). Only the predicate's own unit test moved. **Nothing
else in the system depends on the predicate** — no DTO test, no notification test, no controller test
went red. This is the mechanical confirmation of the F-0418 finding below. File restored; re-run
green (exit 0).

---

## F-0631 — creator dashboard pending-actions tile — **IMPLEMENTED**

**Citation:** `src/pages/creator-dashboard.tsx:433`

```ts
const awaitingSignatureCount = unsignedContracts.length;
```

The second, looser filter over `GET /deals` by `contractStatus === 'PENDING_SIGNATURES'` is gone;
`PendingBreakdown` (line 70) no longer carries a signature count at all, so there is structurally
only one number in the component. The tile and the list beneath it read the same state.

**I verified the backend actually means what the frontend comment claims.**
`influora-api/src/main/java/com/influora/repository/ContractRepository.java:87-93`:

```java
"SELECT c FROM Contract c WHERE c.status = …PENDING_SIGNATURES "
  + "AND c.creatorSignedAt IS NULL AND c.collaborationId IN "
  + "(SELECT co.id FROM Collaboration co WHERE co.creatorId = :creatorUserId "
  + "AND co.status <> …CollaborationStatus.CANCELLED) "
```

That is exactly "awaiting the viewer" — status pending, this creator has not signed, collaboration
not cancelled. The ruling is satisfied at both ends.

`pendingLoading` correctly folds in `unsignedLoading` (line 434) so the total cannot flash a low
number while the second fetch is in flight. Covered by
`src/pages/creator-dashboard.pending-signature-tile.test.tsx` (green in the `npm test` run).

**No defects found. No over-implementation.**

---

## F-0643 — agreedRate required before TERMS_AGREED — **IMPLEMENTED**

**Citation:** `influora-api/src/main/java/com/influora/service/DealService.java:1131` (gate call,
placed before both budget and collaborator-cap gates) and `:1822-1830`:

```java
private void requireAgreedRateForCommitment(Collaboration collaboration) {
    if (collaboration.getAgreedRate() == null) {
        throw new ApiException("AGREED_RATE_REQUIRED", …, HttpStatus.CONFLICT);
```

`committedValue` (`:1850`) now returns `ZERO` for a rate-less row instead of F-0476's `budgetMax`,
with the residual case (a pre-ruling committed row) documented rather than hidden. Ordering is
correct: the gate runs first, so the two gates below it can legitimately assume every committed row
is priced.

### Scrutiny asked for: does any legitimate flow still need TERMS_AGREED without a rate?

I traced every construction site. **Both** entry points into a collaboration are rate-less by
construction:

- **Brand invite** — `CreatorDiscoveryService.java:499-504` builds `Collaboration.invite(id,
  campaignId, creatorUserId, message, currency)`. There is no amount parameter on the factory
  (`Collaboration.java:110`) and none on the invite request.
- **Creator apply** — `CreatorCampaignService.java:256-261` builds `Collaboration.apply(…)`, same
  signature, same absence.
- **Meera** — `service/meera/tool/ConfirmLaunchExecutor.java:472` writes `Collaboration.invite(…)`
  rows at campaign launch ("Invited by Meera at campaign launch."), then
  `bindFundedHoldsToCollaborations` (`:501`) binds a **FUNDED escrow hold** to each one.

`agreedRate` is written in exactly two places (`DealService.java:279`, `:1305`), both reached only
through propose/counter. So after this ruling the **only** route to TERMS_AGREED is
propose-or-counter → accept.

**That is what the ruling ordered, and it does not dead-end any flow** — `canCounter() ==
canAccept()` (`Collaboration.java:358`), so an INVITED/APPLIED party can always counter with a rate
first. I found no legitimate path that is now unreachable.

### Consequence the ruling implies but nobody shipped — flag for follow-up

The frontend was not touched. `grep -rn "AGREED_RATE_REQUIRED" src/` → **no hits.**

- `src/pages/creator-deals.tsx:536` renders `onAccept` for every `isNew` deal unconditionally
  (`DealRow`, line 563: `const isNew = deal.status === 'new'`). For an invite-sourced deal that
  button is now a **guaranteed 409**. The user gets a destructive toast
  ("Negotiate a rate before accepting…") and no affordance to enter a rate next to it — Counter is a
  separate control that navigates elsewhere.
- The brand side is symmetric: accepting a creator's rate-less application 409s the same way.

This is the dead-control class (F-0341 family): a control that renders, is enabled, and cannot
succeed. `tsc`, `eslint`, screenshots and review all pass it. **Recommended follow-up ticket:** hide
or disable Accept when `agreedRate` is absent and route the user to Counter, on both the creator
deals list and the brand applications surface.

Second-order note, not a defect in this ruling: a Meera `confirm_launch` leaves FUNDED escrow holds
bound to rate-less INVITED rows that can no longer be accepted directly. The money is not lost, but
those holds now sit against collaborations that require a negotiation round first. Worth a product
decision on whether `confirm_launch` should seed an `agreedRate` from the campaign budget.

**Verdict: IMPLEMENTED.** Backend is faithful, ordered correctly and load-bearing (Probe 2).

---

## F-0418 — visible overdue state, no auto-fail — **NOT IMPLEMENTED**

### The "no auto-fail" half is correct — explicitly NOT over-implemented

I checked this first, since over-implementation was the named risk here.

`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:361`:

```java
if (isOverdue(deliverable, LocalDate.now())) {
    log.info("Deliverable {} submitted after its deadline of {} — accepted per the F-0418 ruling …");
}
```

It logs and falls through. No status change, no penalty, no block, no scheduled job. `submitForReview`
is not gated on it anywhere. Late submission is proven to still work by
`testSubmitPastDeadlineStillAccepted` (asserts `SUBMITTED` + `submittedAt` set on a deadline two days
past), and `testIsOverdueFalseOnceSubmittedEvenLate` proves a late submission clears the predicate
rather than staying flagged. I ran these: green.

**No auto-fail exists anywhere in the tree.** That half of the ruling is honoured exactly.

### The deliverable half is missing

The ruling was "notify + a visible overdue state". Neither shipped.

- **Not visible.** `isOverdue` is `static`, package-private, and has exactly **one** call site in
  the entire backend — the `log.info` above (verified: `grep -rn "isOverdue" influora-api/src/main`
  returns the definition at `:921`, the log at `:361`, and javadoc). It reaches no DTO.
  `CreatorDeliverableDtos.DeliverableStatusResponse` (`CreatorDeliverableDtos.java:29-49`) is
  **unchanged** — it carries `deadline` and `submittedAt` but no `overdue` field. The brand-side DTO
  has no `deadline` at all (the code's own javadoc concedes this, calling the DTO change "tracked
  separately"). No client can render an overdue state from anything this ruling added.
- **Not notified.** No event, no `ApplicationEventPublisher` call, no notification service touch.
- The javadoc at `:899` claims the predicate is "computed on every read". **That is not true** — it is
  computed once, on submit, only to write a log line. The comment overstates what the code does; it
  should be corrected either way.

The two frontend "Overdue" strings that do exist (`src/components/brand/dashboard/dashboard-page.tsx:320`,
`src/components/brand/deals/deal-room-dashboard.tsx:120`) are **pre-existing client-side date
arithmetic**, unrelated to this ruling, and both are brand-facing. The creator — the party who has to
act on a missed deadline — still sees nothing.

Probe 3 is the mechanical proof: stubbing the predicate to `false` moves exactly one test, its own.
Nothing depends on it.

**Verdict: NOT IMPLEMENTED.** The constraint (no auto-fail) is respected; the behaviour the ruling
actually bought (notify + visible state) does not exist. What shipped is an untested-in-anger
predicate and a log line. This needs an `overdue` boolean on both deliverable DTOs and a notification
on deadline pass before it can be called done.

---

## F-0551 — access token memory-only — **IMPLEMENTED, with a ship-blocking regression**

### The named scope is correctly done, and proven

- `src/lib/api.ts:468-473` — live mode reads from `getMemoryAccessToken(role)` only, **no storage
  fallback**.
- `src/lib/api.ts:497-503` — live mode writes the real token to memory and puts
  `LIVE_SESSION_TOKEN_HINT` (`'session-active'`) in `Storage` under the same key, clearing the other
  store so no stale hint survives a remember-me change.
- `src/lib/auth-session.ts:134-139` — `persistBrandSession`, the only place a brand's real token ever
  reaches the client, does the same.
- `src/lib/api.ts:509-514` — `clearToken` clears memory + both stores + the remember flag.
  `api.auth.logout` (`:1208-1216`) calls it in `.finally()`, so a failed logout request still clears
  the credential. `clearCreatorSession` (`auth-session.ts:277`) clears memory independently, which
  matters because both real logout call sites gate the API call behind `isApiLive()`.
- `src/App.tsx:119-139` — `useAuthGuardState`: memory first, then exactly one silent
  `POST /auth/refresh` before concluding "logged out"; renders `null` while checking so a valid
  session is never bounced to the login form.

**No code path writes a real access token to `localStorage` or `sessionStorage` in live mode.** I
verified exhaustively, not by reading the diff:
`grep -rn "setItem(.*[Tt]oken\|[Tt]oken.*setItem" src/ --include=*.ts --include=*.tsx` returns four
non-test hits — `api.ts:505` and `auth-session.ts:138` (both the **mock-mode** branches),
`demo-access-panel.tsx:48` (`'mock_creator_token'`, a literal), and `admin-login.tsx:60` (out of
scope, see below). Probe 1 confirms the assertions are real.

**Cold load genuinely recovers.** Proven at the client by
`f0551-access-token-memory-only.live.test.ts` ("a cold load … recovers a session via bootstrap()")
and at the server by inspection of the real backend, below.

### BLOCKED claim — independently verified as FALSE

There is no "blocked" record for F-0551 in `.proof-os/ledger/failures.jsonl` or `journal.jsonl`
(both still show `status: open`, no fix entry). Had one been made, it would have been wrong:

`influora-api/src/main/java/com/influora/web/AuthController.java` calls
`authCookieService.writeRefreshCookie(response, pair.refreshToken())` on **all four** entry points —
brand register (`:68`), brand login (`:77`), creator register (`:98`), creator login (`:107`) — and
`/auth/refresh` (`:117-131`) reads it back via `readRefreshToken(request)` and rotates it.

`security/AuthCookieService.java` builds it `HttpOnly` + `Secure` + `SameSite` +
`Path=/api/v1/auth`. Both roles are covered. Supporting facts I checked rather than assumed:

- `CorsConfig.java:27` — `config.setAllowCredentials(true)`, and `CORS_ALLOWED_ORIGINS` is an
  explicit list (never `*`), so `credentials: 'include'` works.
- Cookie `Path` (`/api/v1/auth`, `application.yml:166`) matches the client's refresh URL
  (`API_BASE_URL` + `/auth/refresh`, where the shipped base is `https://api.influora.in/api/v1`,
  `.github/workflows/publish-images.yml:225`).
- `SameSite=Strict` is safe for the shipped topology only because app and API share the registrable
  domain `influora.in` (same-site). **If the API is ever moved to a different registrable domain,
  the cookie stops being sent and every cold load logs every user out.** That constraint is now
  load-bearing in a way it was not before this ruling and should be written into the deploy runbook.

**A "safe" verdict would also have been wrong**, for the reason below.

### DEFECT (ship-blocking) — the second API layer still reads the storage key

`src/lib/meera-api.ts:401-403`:

```ts
function getToken(role: MeeraRole = 'brand'): string | null {
  return localStorage.getItem(role === 'creator' ? 'creator_token' : 'brand_token');
}
```

This module does **not** go through `HttpClient`. After F-0551 that key holds
`LIVE_SESSION_TOKEN_HINT`, so every Meera request in live mode sends
`Authorization: Bearer session-active`. Three call sites are affected:

- `meera-api.ts:440-441` — the shared `request()` helper: **every** Meera REST call, both roles
  (`/meera/*` and `/creator/meera/*`).
- `meera-api.ts:712-713` — `voice/speak`.
- `meera-api.ts:760-761` — `voice/transcribe`.

`JwtAuthenticationFilter.java:30-44` fails open on an unparseable bearer (clears the security
context and continues), so these requests arrive **anonymous** and are rejected by the endpoint's own
auth — the entire Meera surface goes dark in live mode for brands and creators alike. This lands
directly on Meera Creator Phase A, which was signed off 10/10 and is queued to deploy.

**Proven, not inferred.** I wrote a probe test under the live config and ran it:

```
FAIL … PROBE: meera-api Authorization header under F-0551 live mode > sends the real token
AssertionError: expected 'Bearer session-active' to be 'Bearer real-bearer-credential'
FAIL … > creator role: sends the real token
AssertionError: expected 'Bearer session-active' to be 'Bearer real-bearer-credential'
Test Files 1 failed (1) / Tests 2 failed (2)     exit 1
```

(Probe file removed after the run; the tree is as I found it.)

This is the exact failure mode my own standing note warns about — this repo has **two** API layers,
`api.ts` and `meera-api.ts`, and checking only `api.ts` yields false clean results. The F-0551 test
file's own falsification note lists `api.ts` and `auth-session.ts` and never mentions `meera-api.ts`,
which is why 1068 + 25 green tests hide this.

**Required fix (must land before this ruling ships):** delete `meera-api.ts`'s local `getToken` and
read through the one shared accessor, exactly as `api.ts:2578` already does for the SSE stream after
F-0667 —

```ts
import { api } from './api';
// …
const token = api.auth.getToken(role);   // or export http.getToken and use it directly
```

— then add a live-config regression test asserting the header, so the next change to the token store
cannot silently re-break the second layer.

### Secondary findings on F-0551 (not blocking, but log them)

1. **`clientErrors.report` loses attribution.** `src/lib/api.ts:6584` reads
   `localStorage.getItem(TOKEN_KEYS.brand) ?? localStorage.getItem(TOKEN_KEYS.creator)` directly and
   will now attach `Bearer session-active`. Auth is optional on that endpoint and the JWT filter
   fails open, so crash reports still arrive — but **anonymously**. Given that the live
   `[CLIENT_ERROR_REPORT]` stream is our only production crash telemetry, losing the user on every
   report is a real diagnostic regression. Same one-line fix as above.
2. **No timeout on the cold-load refresh.** `HttpClient.refreshAccessToken` (`api.ts:550`) uses a
   plain `fetch` with no `AbortController` — unlike `fetchWithTimeout`, which exists in the same
   class. `useAuthGuardState` renders `null` while `state === 'checking'`, so a hung network on a
   cold load leaves a **permanently blank protected page**: no login redirect, no error, no spinner.
   Before this ruling a stored token answered synchronously and this failure mode did not exist. Add
   a timeout and treat expiry as `unauthenticated`.
3. **Extra unauthenticated request per logged-out visit.** Every cold hit on a protected route now
   spends one `POST /auth/refresh` before redirecting to login. `AUTH_RATE_LIMIT_REFRESH` defaults to
   30/window (`application.yml:174`); a logged-out user clicking around can reach it. Low severity,
   worth watching after deploy.
4. **Admin console is out of scope but is the same bug class, on the highest-value target.**
   `src/pages/admin-login.tsx:60` writes a real admin access token to `localStorage`, and
   `src/admin/services/api-contracts.ts:89,526,916`, `src/admin/services/websocket.ts:233` and
   `src/admin/utils/auditLogger.ts:184` read it back. `AdminProtectedRoute` (`App.tsx:216-219`) still
   gates on `localStorage.getItem('admin_token')`. The admin console handles money, KYC and
   moderation, and `security/AdminAuthCookieService.java` already exists — so the same treatment is
   available. The ruling named `api.ts`/`auth-session.ts`/`App.tsx`, so this is **correctly** outside
   it, but it should be the next ticket.

**Verdict: IMPLEMENTED** within its named file scope, and correctly so. **Do not ship** until the
`meera-api.ts` token read is fixed.

---

## What I am asking for

1. **Blocker — F-0551:** fix `src/lib/meera-api.ts:401-403` (and `api.ts:6584`) to read the token
   through the shared accessor, add a live-config header regression test, re-run `npm run test:live`.
   Nothing about F-0551 deploys before this.
2. **F-0418:** the ruling is not delivered. Add `overdue` to `CreatorDeliverableDtos.DeliverableStatusResponse`
   and the brand detail DTO, render it on both sides, and fire the notification. Correct the
   "computed on every read" javadoc at `CreatorDeliverableService.java:899` regardless.
3. **F-0643:** open a follow-up for the dead Accept control on `creator-deals.tsx` and the brand
   applications surface, plus a product decision on whether Meera's `confirm_launch` should seed an
   `agreedRate`.
4. **F-0631:** nothing. Close it.
5. **Ops:** record in the deploy runbook that the SPA and API must stay on the same registrable
   domain, or `SameSite=Strict` on the refresh cookie will log every user out on cold load.
