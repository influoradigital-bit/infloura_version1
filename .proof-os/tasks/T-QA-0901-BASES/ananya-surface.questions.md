# QA Questions — Frontend Surface (Ananya batch)

Reviewer: Kavya (QA Lead) — zero-context pass
Date: 2026-09-05
Scope: creator/brand auth + route guards + logout, onboarding file upload, brand settings, admin
websocket, `src/lib/api.ts` (request timeouts, 401 session recovery, server validation errors, SSE
message stream, creator search params), brand discovery filters and counts, brand analytics and brand
creator-analytics, brand registration, creator dashboard "contracts awaiting your signature", creator
deal room (chat / contract tab / deliverables), brand deal-room proposal dialog, brand contract status.

These are questions only. Nothing below asserts that anything works or is broken. An answer must be
backed by the real screen a user loads against a real backend — not by a component rendered in
isolation.

---

1. **Mid-session 401 → hard redirect.** `src/lib/api.ts` now calls `redirectToLogin(role)`
   (`window.location.href`) once a refresh-and-retry still fails. Most `api.*` methods default to
   `role: Role = 'brand'`. On a **creator** screen, does every authenticated call actually pass
   `'creator'` — or can a signed-in creator whose session expires be thrown to `/brand/login`, a page
   they cannot sign in on? What happens to in-progress work when this fires: a half-typed counter
   offer, a partially filled registration step, an in-flight deliverable upload — is any of it
   preserved, or even warned about, before the location changes? When a page fans out several
   authenticated requests at once (brand analytics issues one `/analytics/creators/{id}/metrics` per
   roster creator), do simultaneous 401s produce one clean redirect, and can a redirect land mid-write
   so the user leaves believing a change was saved that never was?

2. **Can a user get locked out, or stay logged in when they should not be?** `ProtectedRoute` /
   `CreatorProtectedRoute` (`src/App.tsx`) test only for the *presence* of a token via `readAuthToken`,
   which reads both `localStorage` and `sessionStorage`. With remember-me OFF (token in
   `sessionStorage`), does closing and reopening the tab genuinely end the session on both the brand
   and creator sides, and does a tab opened from the first inherit a session it should not? After
   logout — including a logout whose network call fails, and a mock/demo-mode logout where the
   `isApiLive()` gate skips the API call — is *every* key cleared from *both* stores for *both* roles
   (creator token, brand token, `admin_token`, plus the derived `creator_user_id` / `creator_email` /
   `creator_display_name`), or can Back or a reload put the user back inside the app? Conversely, with
   an expired-but-present token the guard admits the user: does a fully-rendered shell appear and only
   collapse when some request 401s — and on a screen that makes no authenticated request at all, does
   the user sit indefinitely in a logged-out session that still looks logged in? Does a valid token for
   one role open the other role's routes?

3. **Do the analytics screens show numbers and names that are actually real?**
   `aggregateMetricsAcrossRoster` (`src/pages/brand-analytics.tsx`) sums per-creator metrics
   client-side and counts failures in `creatorsFailed`. On the loaded `/brand/analytics` page, is
   "N included / Y could not be loaded" **rendered next to the numbers it qualifies**, or computed and
   never shown — so that six of eight creators timing out silently drops "Total Reach" by three
   quarters while it still presents as the account total, indistinguishable from a real decline? Is
   `engagementRate` an unweighted mean across creators of vastly different follower counts, and is it
   labelled as such? Does a `trendData` date present for only some creators render as a cliff in the
   chart? When the selector switches between aggregate and a single creator, do all tiles, charts and
   captions change together, or can one panel keep the previous selection's numbers under the new
   heading? And on `src/pages/brand-creator-analytics.tsx`, `creator` now resolves only when
   `!isApiLive()` — so in live mode, what does the header actually render: the creator's real name from
   a real endpoint, an empty string, a permanent skeleton, or real metrics with no identity attached at
   all? When `creatorId` changes, can the header, score panel and content panel name different creators
   at the same moment?

4. **Discovery: do the counts and the filters describe the same thing?** The results caption reads
   `Showing ${filteredCreators.length} of ${apiTotal} creators`, with `apiTotal` from
   `result.meta.total`. Is `filteredCreators` a client-side subset of one page of server results while
   `apiTotal` counts a different (wider or narrower) filter set — can the two halves of that sentence
   disagree, and does "Showing 12 of 340" mean the brand will never reach the other 328? Does every
   filter chip the UI shows as active actually appear in the request that `creatorSearchQuery` builds,
   or does some control claim to filter while the server ignores it? Does the price-range filter's
   "creator has no rate set" behavior on the live backend match what the mock fixture (`id: '9'`,
   `averageRate: undefined`) demonstrates in demo mode? When a filter changes, is the count taken from
   the new response, or can a stale `apiTotal` sit under a fresh result list?

5. **Contract status: "Superseded".** `mapApiContractStatus` now maps backend `COMPLETED` to a
   `completed` UI status labelled **"Superseded"**. Is `COMPLETED` reachable *only* through the
   amendment / retire-predecessor path — or does a contract that simply ran to completion also land
   there and get described to the brand as "Superseded", which would be a false statement about a
   legitimately fulfilled agreement? Does the creator's view of that same contract show the same word,
   or do the two parties read different statuses for one agreement? With a superseded contract and its
   live replacement side by side in the list, is it unambiguous on screen which one currently binds,
   and does the retained "Download PDF" / signature-record block make clear the document is historical?

6. **"Contracts awaiting your signature" rows (creator dashboard).** `contractIdentityLabel` returns
   `null` when both `campaignTitle` and `brandWorkspaceName` are null, and `linkedDealId` returns
   `null` when `collaborationId` is missing. On the real dashboard, what does an unlabelled row look
   like — can two contracts for the same amount be visually identical, leaving the creator to guess
   which brand they are about to sign with? What does a row with no linked deal do when clicked: is it
   visibly non-interactive, or does it still look like a link and do nothing? Is the "brand signed,
   your turn" line derived from each record's own signature fields for every row — and after the
   creator signs one, does this list refetch, or can the dashboard keep telling them a contract they
   already signed is still awaiting their signature?

7. **Is the payment schedule fully visible before the creator signs?** `CreatorDealContractTab` now
   renders the `milestones` its call site passes. Before Sign becomes available, is the contract fetch
   guaranteed to have resolved — or can a creator see "No payment milestones are on file for this
   contract yet" only because the request is still in flight or failed, and sign anyway? Does the sum
   of displayed milestone amounts reconcile with the `contractAmount` / `totalAmount` above them, and
   if they disagree, which figure is the creator actually agreeing to? Are all milestones reachable on
   a phone-sized screen, or can the scroll area hide the last one? Is the contract's own `terms` text —
   not just amounts and dates — readable before signing? If a contract that genuinely has a schedule
   returns an empty `milestones` array, is that distinguishable on screen from "no milestones exist"?

8. **The counter-proposal round trip — does each party see what they are agreeing to?**
   `buildCounterOfferBody` (`src/pages/creator-chat.tsx`) folds the form's "Any Changes to Terms?" free
   text into `message` and sends no `usageRights`. After submitting, does the creator see the exact
   text that was transmitted (including the `Terms:` block) rendered back in their own thread, or does
   their terms text vanish from their view? Does the brand's View Proposal dialog display that
   `message` in full — or can a brand press **Accept** while the creator's terms changes sit somewhere
   they never scrolled to? If usage rights appear anywhere in that dialog, are they the original,
   unchanged rights, and would either party reading the screen conclude otherwise? On the creator's
   side, when a stale `?deal=<id>` link resolves to the new "Deal not found" state, can the creator
   still reach the deal they meant, or is the only route back a manual re-navigation?

9. **Proposal accept / counter / reject — did anything actually happen?** `handleAcceptProposal`
   (`src/components/brand/deals/deal-room-dashboard.tsx`) sets `actionMessage('Proposal accepted.')`
   and now closes the dialog only after the request settles. On the real screen, after that message
   appears, does the deal's own state visibly change (status badge, stage, contract availability), and
   does the acceptance survive a reload — or is the message the only evidence anything occurred? On
   failure, is `actionError` visible now that the dialog stays open, and is there a retry that cannot
   double-submit? Do the same "did it stick" and "is the error visible" questions hold for counter and
   reject? Separately, `ApiError` now carries `field` / `fields` from the envelope — does any form on
   these screens actually consume them to mark the offending control (brand registration's new
   first/last name inputs, brand settings mobile number, the counter form), or does that plumbing land
   nowhere so a server-named field error still surfaces as one generic page-level message?

10. **Hangs, timeouts, and stuck submits.** `REQUEST_TIMEOUT_MS` (30s) covers the JSON request paths
    but is deliberately not applied to `upload` / `uploadForm`. During brand onboarding, if the
    connection dies mid-upload, does the promise ever settle — does the button ever re-enable, is there
    progress or a cancel, and can the UI show the file as attached (from a local preview) while nothing
    ever reached `/uploads`? On the 30s path, does the `TIMEOUT` `ApiError` reach each caller's `catch`
    with a message the user actually sees, or do callers that branch on `err.status` (undefined here)
    fall through to a blank or generic state? After a timeout, is the write known *not* to have
    happened — or can the server have processed it, so the user's retry creates a duplicate (the
    counter-offer idempotency key is `${dealId}-counter-${Date.now()}`, fresh per submit)? In brand
    settings, does the phone **save** have failure handling as careful as
    `classifyAccountPhoneLoadFailure` gives the load, does the non-retryable auth branch's "Sign In"
    discard what the user was doing, and could a 403 that is not an expired session send a still-valid
    user to the login page?

11. **Live channels that can look connected while delivering nothing.** The deal-message SSE stream now
    sends `Last-Event-ID` on reconnect against a time-bounded server buffer, with `onReconnect` as the
    refetch cue. In the creator and brand deal rooms, is `onReconnect` wired to an actual refetch on the
    real screen, and after a gap longer than the server's retention does the thread end up complete — or
    silently missing messages with no marker at all? Is the connection status from `onStatusChange`
    surfaced anywhere the user can see, so "nobody has replied" is distinguishable from "you are
    disconnected"? Can replayed frames appear as duplicates or out of order in the rendered thread, and
    what happens to the stream when the token refreshes mid-connection? For the admin websocket: on an
    auth-failure close the client deliberately does not reconnect, and a heartbeat timeout forces one —
    in both cases, does the admin UI visibly become disconnected/stale, or does it keep showing the
    last-received numbers as though they were live?

12. **Which of these claims rests only on an isolated component render?** For each item above, what is
    the strongest evidence that exists — a real route loaded in a browser against a real backend, or a
    component mounted with hand-fed props and a mocked `api`? The specific failure pattern to rule out:
    a contract tab rendered a milestone block whose only call site never passed the prop, so the
    component test passed while every real creator saw the empty state. Applied here: was the brand
    analytics aggregate exercised through `/brand/analytics` with a real roster rather than by calling
    `aggregateMetricsAcrossRoster` directly; was the 401 redirect observed as an actual navigation
    rather than an assertion on `window.location.href`; were the guards exercised by loading the route
    rather than mounting `CreatorProtectedRoute` with a hand-set token; were the discovery filters
    verified against the request the browser actually sent rather than `creatorSearchQuery`'s return
    value; was the admin websocket run against a real server rather than a stub; and was the proposal
    dialog driven through the real deal room? For anything whose only proof is an isolated render,
    which screen must still be loaded by hand before it can be called working?
