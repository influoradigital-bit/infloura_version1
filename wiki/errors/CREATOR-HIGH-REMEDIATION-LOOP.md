# Creator-Surface High-Severity Remediation Loop — 2026-08-10

> **Mechanism:** `/proof-os:work` — verb resolved to `FIX`, loop modifier `UNBOUNDED` ("until complete work all
> high issue solve done") converted to a queue per FLOW law 1 (`queue.py` never accepts an unbounded loop as
> typed). The 12 High-severity findings newly logged to `wiki/errors/CREATOR-BUG-TRACKER.md` as CR-51–CR-124
> (creatorF.md intake pass) were filed as ledger records **F-0105–F-0116**, then ordered by `queue.py`'s own
> blast-radius/money-path/auth-path ranking — not invented by hand. Each item runs: **arjun** routes/confirms
> the owner → the owner fixes it for real (code + tests) → a real build/test oracle verifies it → **priya**
> reviews it independently in a **fresh context** (artifact + done_when only, per proof-os §6 — she does not see
> this conversation) → the ledger record is promoted to closed against a real gate → next item.
>
> **Status legend matches `CREATOR-BUG-TRACKER.md` §2** — `ASSIGNED` → `IN PROGRESS` → `IN QA` (code done, Priya
> reviewed) → `IN VERIFY` (needs Neha's live re-test) → `DONE`. Nothing in this loop reaches `DONE` — that
> requires a live re-test against the deployed app, out of scope for this loop.

## Queue order (as computed by `queue.py`, filtered to this batch)

| # | Ledger ID | CR | Class | Rank reason | Status |
|---|---|---|---|---|---|
| 1 | F-0113 | CR-100 (+ CR-99 root cause) | dead-pipeline-blocks-money-gate | money path | ✅ **IN QA** — see below |
| 2 | F-0109 | CR-90 | logout-no-server-invalidation | auth path, blast 31 | ✅ **IN QA** — see below |
| 3 | F-0108 | CR-87 | dead-form-no-handler | auth path | ✅ **IN QA** — see below (2 review passes, caught a real security bug) |
| 4 | F-0110 | CR-93 | no-fallback-on-dead-realtime | blast 4 | ✅ **IN QA** — see below (finding was partly stale, scope narrowed correctly) |
| 5 | F-0107 | CR-64 | unhandled-empty-state | blast 4 | ✅ **IN QA** — see below (2 review rounds, coverage gap fixed) |
| 6 | F-0112 | CR-98 | actor-side-stale-view | blast 4 | ✅ **IN QA** — see below (also fixed the identical bug in the counter handler) |
| 7 | F-0105 | CR-55 | status-label-drift | blast 3 | ✅ **IN QA** — see below |
| 8 | F-0106 | CR-63 | dropped-oauth-field | blast 3 | ✅ **IN QA** — already fixed, verified not reimplemented |
| 9 | F-0116 | CR-103 | false-success-state | blast 3 | ✅ **IN QA** — see below (pre-existing test asserted the bug) |
| 10 | F-0111 | CR-94 | single-instance-inmemory-registry | infra (see note) | 🚧 **BLOCKED** — architecture decision, not a code fix (see below) |
| 11 | F-0114 | CR-101 | built-component-never-mounted | — | ✅ **IN QA** — see below |
| 12 | F-0115 | CR-102 | missing-controller-route | — | ✅ **IN QA** — see below (3 review rounds + a major incidental discovery, F-0166) |

**Note on #10 (CR-94):** the finding is "the SSE registry is an in-memory `ConcurrentHashMap` scoped to one JVM,
so it cannot fan out across replicas." A real fix is a shared pub/sub backend (Redis, or the existing DB), not a
code patch inside the class — this will need a scoping decision before it can be worked as a same-shaped ticket
to the rest, and will very likely be routed as `BLOCKED` pending that decision rather than fixed inline.

---

## #1 — CR-99 (🔴 Critical, root cause) + CR-100 (🟠 High) — DONE THIS PASS

**What:** Meta's Graph API requires the numeric IG Business Account ID in the request path. Three production
call sites (`MetricsPollingJob`, `AudienceDemographicsJob`, `DeliverableVerificationService`) were passing the
internal ULID `creatorProfileId` instead, so every call 400'd and was silently caught — Instagram analytics,
audience demographics, and deliverable verification were all dead in production, and CR-100's consequence was
that escrow could never auto-release a milestone gated on `ON_VERIFIED_METRICS`.

**Fix (Vikram):**
- `MetricsPollingJob.java`, `AudienceDemographicsJob.java`, `DeliverableVerificationService.java` — all three
  now resolve `igBusinessAccountId` off the token row and pass it as the Graph API path argument. A token row
  with none on file is skipped (jobs) / falls back to `FALLBACK_DATA_INTEGRITY` (verification service), never
  called with a null or wrong ID.
- Test files for all three gained pinning regression tests that fail red if the old ULID-passing behavior is
  reintroduced (verified by literally reverting the fix and re-running — see Priya's review below).
- **A concurrent proof-os session independently found and fixed the same root cause in a fourth call site**,
  `BrandOwnContentService.java` (TrendSpark's brand-own-content check) — confirmed still present and correct on
  disk; no collision between the two sessions' edits.

**Verified (real oracle, not self-scored):** `mvn -o test -Dtest=MetricsPollingJobTest,AudienceDemographicsJobTest,DeliverableVerificationServiceTest`
→ **38/38 passing, 0 failures, 0 errors.**

**Independent review — Priya, fresh context (artifact + done_when only, no visibility into this conversation):**
**APPROVE.** She re-derived the evidence herself rather than trusting the diff: reverted all three call sites
back to the ULID and re-ran the suites, got 10 failures + 5 errors including exactly the three new pinning
tests, then restored and re-confirmed 38/38 green. Found one test-rigor gap (two of the three
"missing-igBusinessAccountId" tests were accidentally passing for the wrong reason) — **fixed in this pass**,
re-verified green. Two legitimate but out-of-scope follow-ups logged to the ledger rather than silently
expanding this ticket:
- **F-0126** — the job-level rate-limit pre-flight check is still keyed on the ULID while the Graph client
  itself now keys on the real IG ID, so the pre-flight guard is decorative (not unsafe — the client's own
  check still throws — just no longer load-bearing).
- **F-0127** — `InstagramMetricsFetcher.java` has a dormant, misleadingly-named parameter that would
  reintroduce this exact bug class if it's ever wired up (currently has zero production callers).

**Gate:** [`​.proof-os/gates/F-0113-meta-igid-fix.sh`](../../.proof-os/gates/F-0113-meta-igid-fix.sh) — re-runs
the three pinning suites live; F-0113 promoted/closed against it.

**What this does NOT close:** Neha's live re-test against a real, deployed Meta connection — the honest ceiling
here is `IN QA`, not `DONE`, exactly per this repo's own `CREATOR-BUG-TRACKER.md` §2 rule.

---

## #2 — CR-90 (🟠 High) — DONE THIS PASS

**What:** the sidebar/mobile-dropdown logout — the primary, easiest-to-reach logout path — only cleared
client-side state; the server never saw it, so the refresh token/cookie stayed live and a new access token
could be silently re-issued via `/auth/refresh`.

**Fix (Ananya, routed from Kabir):** `handleLogout` in `creator-layout.tsx` now calls `api.auth.logout('creator')`
before the local clear, mirroring the already-reviewed Settings-page pattern (CR-32/CR-91) — a failed server call
still always clears local state and navigates away.

**Verified:** new `creator-layout-logout.test.tsx` (2 tests), confirmed to fail red when the fix is reverted and
pass green when restored. Full touched-area suite 9/9 green; `creator-chat-refresh.test.tsx` re-checked for
collateral breakage, 3/3 green.

**Independent review — Priya, fresh context: APPROVE.** She independently reverted the fix herself (git-hash
verified restore), confirmed red→green, and separately checked for duplicate calls, mock-mode correctness, and
XSS/CSRF exposure — none found. One non-blocking cosmetic note (no in-flight loading state during the request)
left unlogged — correctness is unaffected.

**Gate:** [`.proof-os/gates/F-0109-sidebar-logout-fix.sh`](../../.proof-os/gates/F-0109-sidebar-logout-fix.sh).

---

## #3 — CR-87 (🟠 High) — DONE THIS PASS (took 2 review rounds — a real bug was caught)

**What:** the Change Password dialog's inputs were uncontrolled and "Update Password" had no handler at all,
despite a real, working, rate-limited backend endpoint already existing.

**Fix (Ananya) — pass 1:** controlled inputs, client-side validation, wired the API call, success/failure
handling, pinning tests.

**Priya's review — REJECT.** She found `api.auth.changePassword` had no `role` parameter, and the shared HTTP
layer silently defaults to the **brand** token when none is given. A creator submitting this form would either
401, or — worse — silently authenticate as and change the password of whatever **brand** session shared the
same browser. She also flagged an untested validation branch.

**Fix — pass 2:** `changePassword` now takes a required `role` (no default, so a missing role is a compile
error), both call sites (creator + brand settings) pass the correct one, and the missing test was added.

**Re-review — Priya APPROVE.** She traced the token-selection chain end-to-end this time (not just the call
site) and mutation-tested the new guard test.

**This is the loop's independent-review step doing exactly what it exists to do** — a same-context self-check
would very plausibly have missed a bug in a shared file the original ticket never mentioned.

**Gate:** [`.proof-os/gates/F-0108-changepassword-fix.sh`](../../.proof-os/gates/F-0108-changepassword-fix.sh).

---

## #4 — CR-93 (🟠 High) — DONE THIS PASS (finding was stale — CR-31 already fixed most of it)

**What creatorF.md said:** SSE dead → event silently discarded, no fallback, ever.
**What was actually true on re-verification:** CR-31 (already `IN VERIFY`) had already added exponential-backoff
reconnect + an `onReconnect` refetch hook to both chat pages. The real, narrower gap: a **backgrounded tab**,
where a browser can suspend/throttle an in-flight fetch read with no error/close event ever firing, so
`onReconnect` never triggers even though the stream is effectively dead.

**Fix (Vikram):** a `document.visibilitychange` listener in both `creator-chat.tsx` and `brand-chat.tsx`,
scoped to the selected deal, that unconditionally refetches messages + deal state on foreground — additive to
CR-31, doesn't touch the reconnect path.

**Verified:** two new test files, each with a mutation-tested pair of assertions (hiding triggers nothing;
foregrounding triggers both refetches). Confirmed red on two independent mutations, green restored. 19/19 on
the full related-suite regression.

**Independent review — Priya APPROVE.** Re-ran both mutations herself, confirmed additive-not-replacing
relationship to CR-31. Two non-blocking brand-side follow-ups logged (F-0151 loading-flash, F-0152 missing
request-token guard) rather than scope-creeping this ticket.

**Gate:** [`.proof-os/gates/F-0110-chat-visibility-resync.sh`](../../.proof-os/gates/F-0110-chat-visibility-resync.sh).

---

## #5 — CR-64 (🟠 High) — DONE THIS PASS (2 review rounds)

**What:** `no_suggestion_today` collapses into the same `dismissed` UI status as a creator-dismissed
suggestion, but with `suggestion: null` — and `DailySuggestionCard` returns `null` whenever there's no
suggestion object, so a normal "nothing today" server response rendered a completely blank Co-pilot page. A
purpose-built `SuggestionEmptyState` component already had the right copy; nothing called it.

**Fix (Ananya):** special-case `dismissed + no suggestion` in the section's single switch(status) site, routed
to the empty state.

**Priya's review — REJECT (round 1):** the fix itself was correct, but the test file only covered 3 of 7
statuses. She proved the gap was real by hoisting the guard above the idle/error checks (a realistic
"simplification") — that mutant passed all 4 existing tests while silently killing the Instagram-connect CTA
and the error-retry path.

**Round 2:** added the 3 missing tests. **Re-review — Priya APPROVE**, re-ran the same mutation, now fails
loudly across all 3.

**Gate:** [`.proof-os/gates/F-0107-copilot-empty-state-fix.sh`](../../.proof-os/gates/F-0107-copilot-empty-state-fix.sh).

---

## #6 — CR-98 (🟠 High) — DONE THIS PASS

**What:** the brand's own accept/reject on the Deals dashboard called only `loadDeals()`, never
`loadMessages()` — the message timeline went stale even for the actor. Fixed the identical bug in the
counter-offer handler too (same file, same pattern, not in the original citation).

**Fix (Vikram):** all three handlers now `Promise.all([loadDeals(), loadMessages(id)])` on success.

**Verified:** 3 new tests (accept/reject/counter), confirmed red on two independent mutations. **Priya
APPROVE** — additionally confirmed no SSE race and no failure mode that strands the dialog open.

**Incidental finding (not this ticket, not independently verified):** this file now has full SSE wiring —
CR-97 (🔴 Critical, "zero SSE handling") looks like it's already been fixed by a concurrent session. Flagged
in the tracker for whoever owns it to confirm.

**Gate:** [`.proof-os/gates/F-0112-actor-refresh-fix.sh`](../../.proof-os/gates/F-0112-actor-refresh-fix.sh).

---

## #7 — CR-55 (🟠 High) — DONE THIS PASS

**What:** the campaign detail page kept its own drifted status-label map (7 of 13 statuses) with a
raw-enum-string fallback — a cancelled application literally rendered "CANCELLED".

**Fix (Ananya):** deleted the local map outright, switched to the canonical `getApplicationStatusLabel`
(all 13 `CollaborationStatus` values covered; `CANCELLED` → "Closed").

**Verified:** 3 tests, confirmed red on revert (raw enum literally in the DOM), green restored.

**Independent review — Priya APPROVE.** She additionally swept every sibling status-label site in the
codebase to confirm none of them have the same drift, and verified two brand-side maps that looked similar
are genuinely different vocabularies, not missed duplicates.

**Gate:** [`.proof-os/gates/F-0105-status-label-drift-fix.sh`](../../.proof-os/gates/F-0105-status-label-drift-fix.sh).

---

## #8 — CR-63 (🟠 High) — ALREADY FIXED, verified not reimplemented

Re-reading the source first (law 3) showed this was already fixed alongside CR-105 (creatorF.md's
audit produced two duplicate tickets for the same bug). Traced the full chain end-to-end
(`setLocalConnectionState` → localStorage → `useDailySuggestion` → `DailySuggestionSection`) and
confirmed `BusinessAccountRequired` is genuinely reachable now. Rewrote the one existing test that
covered only the plumbing half to also cover the live success case. **Priya APPROVE.**

## #9 — CR-103 (🟠 High) — DONE THIS PASS

**What:** the callback page moved to "success" unconditionally on any 200 response, so a personal
account (server correctly returns `connected: false`) was falsely told "Brands can now see your
verified Instagram and Facebook metrics." **The pre-existing test for this exact code path actively
asserted the bug as correct behavior** — the same shape as the CR-99 ULID bug's test suite.

**Fix (Ananya):** heading/description now branch on `connected` + `accountType`; the true-success
case is byte-identical to before.

**Verified:** 2 new tests, confirmed red on revert (rendered the false claim verbatim in the DOM),
green restored. **Priya APPROVE** — confirmed the false claim is structurally unreachable across the
full state matrix and there's no flash-of-false-success race.

**Follow-up logged, not folded in:** F-0164 — the onboarding-resume redirect can skip a
personal-account creator past this new explanation on one entry path.

**Gate:** [`.proof-os/gates/F-0106-F-0116-meta-callback-fix.sh`](../../.proof-os/gates/F-0106-F-0116-meta-callback-fix.sh) (shared).

---

## #10 — CR-94 (🟠 High) — BLOCKED, correctly not "fixed"

Re-reading the source first: this is not an unaddressed oversight. The class javadoc already documents the
in-memory single-instance SSE registry as *"a deliberate MVP scope decision by Priya, not an oversight"*,
names the current single-replica deployment as why it's acceptable today, and spells out the real upgrade
path (Redis Pub/Sub or DB LISTEN/NOTIFY). This needs an infrastructure decision, not a same-shaped code
patch — promoted `--unautomatable`, matching this tracker's own CR-15 precedent.

## #11 — CR-101 (🟠 High) — DONE THIS PASS

`ConnectedAccounts` (fully built, zero imports anywhere) is now mounted on the Settings page. Mounting it
broke both pre-existing Settings test suites (missing `metaOAuth` mock member) — fixed. New mount-pinning
test added, confirmed red on removal. **Priya APPROVE** — additionally confirmed no new toasts/rejections
interfere with the logout/password flows already on that page. Follow-up logged: `meta_connection`
localStorage survives creator logout (F-0165).

## #12 — CR-102 (🟠 High) — DONE THIS PASS, 3 review rounds, plus a major incidental discovery

**What was actually missing:** just the frontend. The backend route and correct creator-scoped revoke
already existed. Added `api.metaOAuth.disconnect()` and a Disconnect button gated by a confirmation dialog.

**Priya rejected twice** — both times because the confirmation dialog's copy claimed a consequence the code
doesn't produce ("deliverable verification stops"; then "brands stop seeing your metrics" — disconnect only
revokes the token, it never touches the persisted rows brands actually read). **Round 3 approved** once the
copy claimed only what's true, with Priya independently tracing every writer to confirm it — including one
(`MetricsPollingJob`) I hadn't even cited.

**The incidental discovery, found while she was checking that copy:** `DeliverableVerificationService` (from
item #1, CR-99/CR-100) resolves the creator's token via the **workspace-scoped** getter, and a creator row's
`workspaceId` is always null — which the repository's query (hardened in a prior pass) can *never* match.
**CR-99/CR-100's fix earlier in this loop, while itself correct, could not have made the pipeline work
end-to-end.** Confirmed by reading the JPQL directly and an empirical Hibernate+H2 test, not by trusting a
comment. Found the identical bug in `MetricsPollingJob` and `AudienceDemographicsJob` too — **fixed all
three in this same pass** (F-0166), 38/38 backend tests green. A **fourth, more severe** instance —
`MetaTokenRefreshService`, the mechanism that keeps creator tokens alive at all — was found but **left
unfixed**, logged as **F-0171**, flagged higher priority than this entire batch: if creator tokens are never
refreshed, everything above eventually goes dark regardless of any other fix here.

**Gates:** [`F-0115-disconnect-capability-fix.sh`](../../.proof-os/gates/F-0115-disconnect-capability-fix.sh), [`F-0166-token-scope-fix.sh`](../../.proof-os/gates/F-0166-token-scope-fix.sh).

---

## Loop complete — 11 of 12 fixed, 1 correctly blocked, 1 new critical follow-up surfaced

All 12 queued items have been processed. Summary: **10 code fixes landed** (CR-55, 63, 64, 87, 90, 93, 98,
99/100, 101, 102, 103 — CR-99/103/105/106 folded together as shared root causes), **1 correctly left
`BLOCKED`** on an infrastructure decision (CR-94), and **1 major additional defect found and fixed** as a
byproduct of the review process (F-0166 — the same token-scope bug in 3 files), with its most severe sibling
(F-0171, `MetaTokenRefreshService`) surfaced and flagged rather than silently absorbed into this loop's scope.

**Independent review caught 3 real, shipped-would-be-wrong defects** across this loop: CR-87's
cross-account security bug, CR-64's silent-refactor test gap, and CR-102's two rounds of false consequence
claims in a destructive-action dialog. None of these would have been caught by a same-context self-check.

**Nothing here reaches `DONE`** — every fix is capped at `IN QA`/`IN VERIFY` per this tracker's own §2 rule;
that requires Neha's live re-test against the deployed build, out of scope for this loop.

---

## Post-loop — F-0171 (MetaTokenRefreshService) fixed

The most severe follow-up from the loop above — creator Meta tokens were never refreshed at all — is now
fixed. `refreshOne()` branches on `workspaceId == null`: creator-owned rows now resolve via
`getValidCreatorToken`/persist via `storeCreatorToken` (the race-safe, revoke-before-insert writer); brand
rows are completely unchanged. `MetaTokenStorage.storeToken`'s stale javadoc (which incorrectly claimed a
null workspaceId still matched a row) is corrected.

**Verified:** 11/11 tests, two independent mutation experiments (revert both halves; revert only the write
half) each catching a distinct regression the other couldn't. Full Meta-integration family (7 test classes,
83 tests) re-run green. **Priya APPROVE** — she additionally checked `storeCreatorToken`'s revoke-before-insert
step is safe to run on every refresh cycle (it is — no unbounded row growth, no window with zero/two live
rows) and found one real, non-blocking side effect: a creator's "connected since" date will now silently
advance ~every 55 days instead of staying fixed at first connect, since the insert-based writer always
stamps a fresh `createdAt`. Logged as **F-0173**, not silently fixed — deciding whether `connectedAt` should
mean "first connected" or "current token issued at" is a product call, not an engineering one.

**Gate:** [`.proof-os/gates/F-0171-token-refresh-fix.sh`](../../.proof-os/gates/F-0171-token-refresh-fix.sh).

This closes out the entire workspace/creator token-scope bug family found during this loop (F-0113 → F-0166 →
F-0171): all four call sites that could reach a creator-owned Meta token now do so through the correct,
creator-scoped storage methods.

---

## Post-loop — F-0166 test-fixture gap closed (external bug report)

An external bug report re-described the exact F-0166 defect in `DeliverableVerificationService.verifyInstagram`
(workspace-scoped `getValidToken(tokenRow.getWorkspaceId(), creatorProfileId)` against an always-null
creator-row workspaceId → permanent `FALLBACK_NO_TOKEN`). Re-checked the live file: the production fix
described above was already in place and unmodified, at the same line the report cited.

What the report actually surfaced as new: `DeliverableVerificationServiceTest`'s `activeTokenRow()` fixture
was still building a **brand-shaped** row (`.workspaceId(WORKSPACE_ID)`, non-null) even though every test in
the class exercises the creator-verification path — the exact fixture-honesty gap Priya had flagged as a
non-blocking residual during the original F-0166 review, and it had been left open. None of the existing 14
tests could have caught a regression back to the workspace-scoped call, because none of them modeled a real
creator-owned (`workspace_id IS NULL`) row.

Fixed: removed the dead `WORKSPACE_ID` constant, `activeTokenRow()` now leaves `workspaceId` null by
construction, and added `creatorOwnedTokenRowResolvesRatherThanFallingBackToNoToken()` — asserts
`Outcome.VERIFIED` (not `FALLBACK_NO_TOKEN`) for a null-workspace token row, plus
`verify(metaTokenStorage, never()).getValidToken(any(), any())` to pin the workspace-scoped path as
unreachable for creator rows.

**Verified:** `mvn -o test -Dtest=DeliverableVerificationServiceTest` → **15/15, 0 failures, 0 errors** (14
original + 1 new).

---

## Post-loop — remaining follow-up batch (F-0126, F-0127, F-0151, F-0152, F-0164, F-0165, F-0173)

All 7 items from the pending-work table are now fixed, each independently mutation-proofed (revert
the fix, confirm the new test fails for the exact stated reason, restore, confirm `git hash-object`
matches the pre-mutation hash exactly, confirm green again) before being sent for review:

- **F-0126** (rate-limit-key-drift): `MetricsPollingJob`, `AudienceDemographicsJob`, and
  `DeliverableVerificationService` all keyed `MetaRateLimitTracker.getCurrentUsage`/`markLimited` on
  the internal ULID `creatorProfileId` instead of `igBusinessAccountId` — the namespace
  `MetaGraphApiClient` itself actually uses — so every job-level pre-flight rate-limit guard silently
  read usage=0 and was decorative. `DeliverableVerificationService` additionally had the same wrong
  ULID passed as the `businessAccountId` arg into `getMediaInsights`, feeding Meta's real usage-header
  data into the wrong key. Fixed all call sites; 3 new pinning tests (one per file) stub ONLY the
  correct key at a tripping threshold and assert the ULID key is never even read.
  Verified: `MetricsPollingJobTest` 13/13, `AudienceDemographicsJobTest` 13/13,
  `DeliverableVerificationServiceTest` 16/16.
- **F-0127** (misleading-parameter-name): already fixed by an earlier, unrelated commit (366d031) —
  confirmed live against `InstagramMetricsFetcher.java`: the parameter is genuinely named
  `igBusinessAccountId` throughout, javadoc accurate. No code change needed this pass.
- **F-0151/F-0152** (brand-chat.tsx loading flash + unguarded concurrent fetch): the message thread
  render was gated on `!messagesLoading` (and, briefly mid-fix, `!messagesError` too), so every
  background resync (deal-switch, SSE onReconnect, foreground visibility resync) replaced an
  already-correct thread with a full spinner or blanked it on a transient error. `loadMessages` also
  had no request-token/mount guard, unlike `creator-chat.tsx`'s equivalent. Fixed to match
  creator-chat's convention exactly: thread renders unconditionally, `messagesRequestRef` +
  `isMountedRef` guard `loadMessages`, and a failed background resync no longer wipes the thread.
  3 new tests in `brand-chat-visibility-resync.test.tsx`. Verified: 5/5 (that file) + 12/12
  (`brand-chat-proposal.test.tsx`, collateral-breakage check).
- **F-0164** (explanatory-screen-bypassed-by-redirect): the onboarding-resume auto-redirect in
  `creator-meta-callback.tsx` fired regardless of `result.connected`, so a personal-account creator
  starting the connect from onboarding was redirected straight back into the wizard before ever
  seeing the F-0116 "Business account needed" explanation. Fixed: only auto-advances on an actual
  connection; the manual "Back to onboarding" button still covers the `connected:false` case. 2 new
  tests (one per direction). Verified: 17/17.
- **F-0165** (stale-session-mirror-survives-logout): `clearCreatorSession()` removed the five
  `creator_*` keys but not the `meta_connection` mirror, so a second creator on a shared browser could
  be seeded with the first creator's Meta connection state if the live re-verification call failed.
  Fixed: `clearCreatorSession()` now also removes `meta_connection`. 1 new test in
  `creator-session.test.ts`, the same home as the sibling CR-32 test. Verified: 4/4.
- **F-0173** (connected-since-date-silently-advances): `storeCreatorToken`'s revoke-then-insert
  rotation (which F-0171 routes every creator token refresh through) always stamped a fresh
  `createdAt`, so the "connected since" date `MetaConnectionService.getStatus` shows a creator
  silently advanced forward every ~55-day auto-refresh. Fixed: added an explicit, opt-in
  `createdAt` override on `MetaOAuthToken.Builder` (every other caller is unaffected and keeps
  stamping "now"); `storeCreatorToken` now captures the original row's `createdAt` before revoking it
  and carries it forward onto the new row. First-time connects (no prior row) are unaffected. This
  resolves the product-judgment call the original F-0173 note flagged as undecided — "connected
  since" now means first-connected, not last-token-issued. 2 new tests (refresh preserves; first
  connect still stamps fresh). Verified: 21/21.

**Broader regression check:** full backend suite (`mvn -o test`) — 1734 tests, 0 failures, 1
pre-existing error unrelated to any file touched this pass (`BrandDeliverableServiceTest
.testRejectSubmitted`, reproduces in isolation on this branch since before this session — flagged
separately, not fixed here as out of scope). Full frontend suite (`npx vitest run`) — 63 files, 446
tests, all green.

**Gates:** [`F-0126`](../../.proof-os/gates/F-0126-rate-limit-key-drift-fix.sh) ·
[`F-0151/F-0152`](../../.proof-os/gates/F-0151-F-0152-brand-chat-thread-resync-fix.sh) ·
[`F-0164`](../../.proof-os/gates/F-0164-onboarding-resume-explanation-fix.sh) ·
[`F-0165`](../../.proof-os/gates/F-0165-meta-connection-logout-clear-fix.sh) ·
[`F-0173`](../../.proof-os/gates/F-0173-connected-since-date-stable-fix.sh).

**Status:** all 6 code changes + F-0127's verification dispatched to Priya for independent
fresh-context review (isolation: artifact paths + done_when only, no reasoning/summary passed
through). Ledger status set to `in_review`, pending her verdict.

### Priya's verdict — **APPROVE on all 7, zero rejects**

She ran every gate herself (not just read the code) plus 3 collateral suites the gates don't cover
(`creator-layout-logout`, `creator-settings-logout`, `creator-chat-visibility-resync` — 8 tests,
green). Per-ticket:

- **F-0126 APPROVE.** Grepped the whole `src/main` tree independently rather than trusting the 3
  named files — confirmed all 10 non-comment tracker call sites key on `igBusinessAccountId`,
  matching `MetaGraphApiClient.java`'s own namespace. Traced the mutation-catches-it claim herself.
- **F-0127 APPROVE.** Re-verified against the live file rather than the ticket text; confirmed
  provenance via `git log` (commit 366d031, predates this session).
- **F-0151/F-0152 APPROVE.** Confirmed brand-chat's fix is structurally identical to creator-chat's
  (not just superficially similar) by diffing both files' render-gate and guard-hook shape line for
  line.
- **F-0164 APPROVE.** Confirmed both redirect directions are tested and the manual escape hatch is
  still wired end-to-end.
- **F-0165 APPROVE.** Confirmed the literal key matches `api.ts`'s `META_CONNECTION_KEY` and the
  test is non-vacuous.
- **F-0173 APPROVE**, including the product-judgment call: *"preserving the first-connected date is
  the right default... nothing is lost — lastRefreshedAt already carries token-issuance recency for
  ops. I'd have made the same call."* She specifically hunted for a JPA lifecycle callback
  (`@PrePersist`/`@CreationTimestamp`) that could silently override the builder at flush time and
  make the fix green-but-broken against a mocked-repository test — confirmed none exists.

**3 new, real findings surfaced during the review** (none blocking the approvals above — logged as
new open tickets, not fixed in this pass):

- **[F-0192](../../.proof-os/ledger/failures.jsonl) — cross-deal message bleed on deal switch**, in
  *both* `brand-chat.tsx` and `creator-chat.tsx`. Removing brand-chat's `!messagesLoading` gate
  (needed for F-0151) also removed an *incidental* side effect it had — hiding the previous deal's
  thread during a switch. Neither file clears its message list on `selectedDeal.id` change, so
  switching deals can now show deal A's messages under deal B's header for one round-trip.
  Pre-existing in creator-chat, newly surfaced in brand-chat. The one user-visible finding of the
  three — worth prioritizing.
- **F-0193 — F-0173's `createdAt` preservation isn't scoped to the same `igBusinessAccountId`.** A
  creator switching to a *different* Instagram account without disconnecting first inherits the old
  account's "connected since" date. Disconnect-then-reconnect is unaffected. Display-only, low
  severity; her suggested fix is a one-line scoping check in `storeCreatorToken`.
- **F-0194 — a misleading `@DisplayName` + one untested branch**, no code defect: the F-0126
  `DeliverableVerificationServiceTest` pinning test's display name over-claims what its body
  actually exercises (short-circuits at the pre-flight check), and the insights-fetch 429 path's
  `markLimited` call (`DeliverableVerificationService.java:237`) has no dedicated test.

Ledger: F-0126/F-0127/F-0151/F-0152/F-0164/F-0165/F-0173 all `closed`, `closed_by: priya`,
`closed: 2026-08-12`. F-0192/F-0193/F-0194 logged `open`.

---

## Post-loop — pre-existing, unrelated backend test fixed (`BrandDeliverableServiceTest`)

Separately flagged as out-of-scope during this loop's broader regression check: `testRejectSubmitted`
failed with `ApiException: Collaboration not found` in complete isolation, on a file untouched by any
of this loop's changes (last touched in commit e3e59d0, well before this session).

**Root cause:** a prior fix (D-9/CR-22a) added a `requireNotCancelled(collaborationId)` guard to
`reject()` — the same guard `approve()` already had — but `testRejectSubmitted` was never updated to
call the `stubActiveCollaboration()` helper every other approve()-path test calls. Without it,
`collaborationRepository.findById(COLLAB_ID)` fell through Mockito's default `Optional.empty()`, and
the test tripped the guard's own not-found exception instead of exercising the reject path it's
actually about — a stale test fixture, not a production defect.

**Fix:** added the `stubActiveCollaboration()` call to `testRejectSubmitted` (mirroring every sibling
test that exercises the shared guard).

**Verified:** mutation-proofed (removed the stub, confirmed the exact original failure reproduces,
restored, confirmed `git hash-object` matches exactly, confirmed green again). Full backend suite:
**1734/1734, 0 failures, 0 errors** — up from 1734/1735 with 1 error before this fix (same total
test count; the fixture change didn't add or remove a test, just made an existing one pass for the
right reason).

---

## Post-loop — F-0192 fixed (cross-deal message bleed on switch)

The one user-visible finding from Priya's F-0151/F-0152 review: neither `brand-chat.tsx` nor
`creator-chat.tsx` cleared its message list when `selectedDeal.id` changed to a different deal, so
switching deals could render the previous deal's messages under the new deal's header (with a
spinner on top) until the new deal's fetch resolved. In brand-chat this was newly exposed by F-0151
removing the `!messagesLoading` render gate, which had incidentally hidden the previous deal's
thread during the round-trip; creator-chat had the same gap already, unrelated to F-0151.

**Fix:** both files now call `setLiveMessages([])` synchronously inside the effect that fires on a
genuine `selectedDeal?.id` change — deliberately placed there and not inside `loadMessages` itself,
which every same-deal resync caller (SSE `onReconnect`, foreground visibility resync, manual Retry)
also shares and must NOT have cleared, or this reopens F-0151/F-0152 from earlier today.

**New tests:** one per file (`brand-chat-visibility-resync.test.tsx`,
`creator-chat-visibility-resync.test.tsx`) — switch to a second deal whose fetch is held pending via
an unresolved Promise, assert the first deal's message content is absent before the second deal's
fetch resolves, then resolve it and assert the second deal's content appears.

**Verified:** target suite 6/6 + 3/3 (28/28 across the full 5-file collateral set, including
`brand-chat-proposal.test.tsx`, `creator-chat-refresh.test.tsx`, `creator-chat-verified-badge.test.tsx`).
Mutation-proofed in both files independently (removed the `setLiveMessages([])` call, confirmed both
new tests fail for the exact stated reason, restored, confirmed `git hash-object` matches exactly for
both files, confirmed green again).

**Gate:** [`F-0192`](../../.proof-os/gates/F-0192-cross-deal-message-bleed-fix.sh).

**Status:** dispatched to Priya for independent fresh-context review (isolation: artifact paths +
done_when only). Ledger status `in_review`, pending her verdict.

### Priya's verdict — **APPROVE**

She ran the gate herself (28/28, 45.6s), then went further than the gate: she personally re-ran the
mutation test (removed `setLiveMessages([])` from both effects, confirmed exactly the 2 new F-0192
tests fail while the 3 F-0151/F-0152 tests stay green, restored, confirmed file hashes match
byte-for-byte) rather than trusting the mutation-proof claim.

Confirmed independently:
- The clear sits in the deal-switch effect in both files, not inside `loadMessages` — traced every
  shared caller (visibility listener, SSE `onReconnect`, manual Retry) and confirmed none of them
  route through the effect, so F-0151/F-0152 are not reopened.
- Both effects' dependency arrays are provably stable (`useCallback(..., [])` / `[liveApi]`) and
  `selectedDeal` can't cycle through null-then-same-id, so the clear can never wrongly fire on a
  same-deal re-render.
- Traced the F-0152 request-token race explicitly: a stale deal-A response arriving after the switch
  fails `isCurrent()` and is discarded, so it cannot re-populate the just-cleared list; confirmed the
  SSE stream for the old deal is closed (cleanup effect) *before* the clear runs, since React always
  runs cleanups before new effect bodies.

**2 new findings surfaced, out of F-0192's scope, logged as new open tickets:**
- **F-0195 — as filed at the time, believed to be the identical bleed on the deliverables panel.**
  [Correction below: this turned out to be false — see "Post-loop — F-0195" for what was actually
  fixed (an unguarded-concurrent-fetch race), and the ledger entry has been reclassified.]
- **F-0196 — the optimistic-send append has no dealId guard** in either file: a send POST resolving
  after the user has already switched deals appends the sent message into the new (wrong) deal's
  thread. Narrow window, pre-existing, unrelated to F-0192.

Ledger: F-0192 `closed`, `closed_by: priya`, `closed: 2026-08-12`. F-0195/F-0196 logged `open`.

---

## Post-loop — F-0195 fixed (unguarded-concurrent-fetch in loadDeliverables; NOT a render-level bleed — see below)

Same class as F-0192: `brand-chat.tsx`'s deal-switch effect called `loadDeliverables(dealId)` with
no matching clear, and `loadDeliverables` itself had no request-token guard (unlike `loadMessages`,
which F-0152 already fixed).

**Investigation finding, logged honestly rather than silently accepted:** the ticket's literal
symptom description — "renders liveDeliverables completely unconditionally with no loading gate" —
does not hold up against the live file. The deliverables panel is a modal `Dialog` gated by a real
`deliverablesLoading ? spinner : error ? ... : <DealDeliverablesTab .../>` ternary, and `selectDeal()`
already force-closes the panel (`setOpenPanel(null)`) on every switch. Both independently prevent
stale rows from ever painting today. Confirmed by mutation-testing my own first draft test: with the
`setLiveDeliverables([])` clear removed, a render-based "switch, reopen, assert old rows absent" test
still passed — it couldn't distinguish the fix from the pre-existing protections it's redundant with.
That test was removed rather than kept as false coverage.

**What's actually real and fixed:** `loadDeliverables` had no request-token/mount guard at all, so a
slow, stale response for a *previously*-selected deal — if it resolved after a newer deal's fetch had
already completed and rendered correctly — could silently overwrite the newer deal's rows with the
old ones. This is a genuine, currently-reachable race, the same class F-0152 closed for
`loadMessages`.

**Fix:** `setLiveDeliverables([])` added to the deal-switch effect (defense-in-depth, matching
F-0192's pattern even though not independently observable today — protects the data layer if either
existing guard is ever weakened, the exact way F-0151 quietly removed messages' equivalent gate).
`deliverablesRequestRef` + the existing `isMountedRef` added to `loadDeliverables`, mirroring
`messagesRequestRef`'s pattern exactly; the catch block no longer unconditionally clears on failure,
matching F-0152's fix to `loadMessages`.

**Verified:** 1 new test (`brand-chat-visibility-resync.test.tsx`) — a slow stale deal-1 response
cannot overwrite deal 2's freshly-loaded rows. Target suite 7/7 + 12/12 collateral
(`brand-chat-proposal.test.tsx`). Mutation-proofed: removing the token guard fails the new test for
the exact right reason; removing the clear does *not* fail any test (confirming the "defense in
depth, not independently testable" finding above rather than hiding it). Both mutations restored,
`git hash-object` confirmed exact match, green again.

**Gate:** [`F-0195`](../../.proof-os/gates/F-0195-deliverables-panel-bleed-fix.sh).

**Status:** dispatched to Priya for independent fresh-context review, explicitly asked to challenge
the ticket's own symptom description and to flag any non-discriminating test rather than credit it.
Ledger status `in_review`.

### Priya's verdict — **REJECT**, on narrow (documentation) grounds

She confirmed the code logic is safe and mutation-proven — she personally removed the
`deliverablesRequestRef`/`isCurrent()` guard and re-ran the gate, confirming the exact right test
failure, then restored the file byte-identical. **She would have approved the code as written.**

The reject is because the ticket's own symptom, and a code comment restating it as observed fact,
were false — and she caught it more precisely than my own investigation had:
- She removed `setLiveDeliverables([])` herself and re-ran both gate suites: **19/19 still passed**
  — confirming zero discriminating coverage for that line, same finding I'd already logged.
- She then went further and disproved the underlying claim directly: read the render JSX
  (`deliverablesLoading ? spinner : deliverablesError ? error : <DealDeliverablesTab .../>`) and
  `selectDeal()`'s `setOpenPanel(null)`, and traced every `setSelectedDeal` call site to confirm no
  path exists where a deal switch could leave stale rows visible — not just "unproven by tests" but
  actually structurally unreachable.
- Flagged that while the test file's own comment disclosed this honestly, the **code comment in
  `brand-chat.tsx` did not** — it asserted the bleed as something that "rendered," which is false and
  would mislead a future reader who trusts inline comments over test-file caveats.

**Corrections applied** (documentation only, zero logic changes — re-ran the full target+collateral
suite after, still 19/19 green):
1. Rewrote the `brand-chat.tsx` comment to state the true situation: the panel was always protected
   by `deliverablesLoading` and `selectDeal()`'s force-close; the clear is redundant data-layer
   hygiene, not a fix for an observed render bug.
2. Reclassified the F-0195 ledger entry from `cross-deal-deliverables-bleed-on-switch` to
   `unguarded-concurrent-fetch` (correctly, same class as F-0152), symptom text rewritten to match
   what was actually fixed.
3. Fixed "modal Dialog" → the panel's actual component (Radix `Sheet`) in the test file's
   explanatory comment.

Re-dispatched to Priya for a confirmation pass on the corrections (documentation-accuracy only, not
re-litigating the already-approved code logic).

### Priya's confirmation pass — **APPROVE**

Independently re-verified all three corrections against the live files (not by trusting her own
prior review):
- Re-traced every `setSelectedDeal` call site herself again to re-confirm "structurally unreachable"
  holds, and confirmed the corrected `brand-chat.tsx` comment now states that accurately rather than
  claiming an observed bleed.
- Confirmed the ledger reclassification is accurate, exactly one F-0195 entry exists, and the file
  still parses as valid JSONL.
- Confirmed "Sheet" (not "Dialog") against the actual `<Sheet><SheetContent side="right">` JSX.
- Gate re-run: 19/19 green.
- Confirmed the request-token guard itself is byte-for-byte unchanged from her already-approved prior
  pass — this pass was comment/ledger-only, no logic touched.

**3 non-blocking nits she flagged in this very tracker doc** (now fixed above): the "F-0195 fixed
(deliverables panel bleed on switch)" heading and the "identical bleed" line in the F-0192 section
both stated the disproven bleed as fact to a heading-skimmer even though the body corrected it. Left
as a low-priority mention, not fixed: the gate filename
(`F-0195-deliverables-panel-bleed-fix.sh`) still encodes "bleed" — renaming it would require updating
`promoted_to` in the ledger in the same commit; deferred.

**Final ledger state:** F-0195 `closed`, `closed_by: priya`, `closed: 2026-08-12`, `class:
unguarded-concurrent-fetch`.
