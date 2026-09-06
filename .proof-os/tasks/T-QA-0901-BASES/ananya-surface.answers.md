# Answers — Frontend Surface (Ananya batch)

Answered by: priya (CTO), fresh pass over the working tree on 2026-09-05.
Method: read the real source and the real backend controllers/services, and ran the three commands
myself. The ananya-wave1/2/3/3b gate headers were read only to know what was CLAIMED; every claim
below was re-derived from source.

## Real test runs (this tree, right now)

    npx tsc --noEmit        -> exit 0   (no diagnostics printed at all)
    npm test                -> exit 0   Test Files 179 passed (179) / Tests 1057 passed (1057)
    npm run test:live       -> exit 0   Test Files   3 passed (3)   / Tests   16 passed (16)

One structural fact that colours every answer: the default vitest project pins mock mode in both
test.env and a transform-time define, so all 1057 tests run against the mock API; only the 16
live-config tests execute live-mode branches, and those stub fetch. Zero of the 1073 tests touch a
real backend or a real browser. Nothing below can be upgraded to "proved against a real backend".

Evidence: `vitest.config.ts:50` sets "VITE_API_MODE: 'mock',".
Evidence: `vitest.live.config.ts:39` pins "http://api.test.invalid/api/v1" so a leaked request fails.

---

## 1. Mid-session 401 -> hard redirect

**Role routing: I found no defect.** The redirect fires only after a refresh has also failed, at
which point the stale slot is cleared first. I enumerated every request helper call in api.ts that
omits a role (79 of them, so they take the brand default) and cross-checked which are reachable from
creator screens. The only hit is the public-config call on the unauthenticated creator register page,
where no auth header exists, so no retry and no redirect can fire. Creator wallet, deals, contracts
and analytics all pass or hardcode creator; the creator self-analytics path routes to a different
endpoint entirely. So a signed-in creator being thrown to /brand/login is not reachable through the
api layer today. It IS reachable through the SSE stream, which is a different bug — see Q11.

Evidence: `src/lib/api.ts:602` calls "redirectToLogin(role);" only after the refresh failed.
Evidence: `src/lib/api.ts:598` clears the slot first with "this.clearToken(role);".
Evidence: `src/hooks/analytics/useCreatorMetrics.ts:76` routes self-view to "api.creatorAnalytics.getMyMetrics(startIso, endIso)".

**In-progress work is neither preserved nor warned about.** The redirect is a raw location
assignment, and a repo-wide search for a beforeunload handler across the whole frontend returns zero
hits. A half-typed counter offer, a partly filled registration step and an in-flight upload are all
discarded silently. Uploads go through the same auth-retry wrapper, so an upload 401 redirects too.

Evidence: `src/lib/api.ts:236` is the bare assignment "window.location.href = target;".

**Concurrent 401s.** The refresh itself is deduped per role, but the clear-and-redirect runs once per
failing request. Brand analytics fans out one metrics call per roster creator through
Promise.allSettled, so N failures means N assignments to the same href; in a real browser the first
navigation wins, so the user sees one clean redirect. A redirect CAN land mid-write: the request that
redirected definitively did not persist, but a sibling write in flight elsewhere on the page is
aborted by the navigation with no marker, so "I clicked Save and then got logged out" stays genuinely
ambiguous. Nothing in the codebase addresses that.

---

## 2. Lock-out, or staying logged in when you should not be

**Both guards test presence only.** Neither decodes exp. So an expired-but-present token renders the
full shell and it only collapses when some request 401s; on a screen that issues no authenticated
request the user sits indefinitely in a logged-out session that still looks logged in. There is no
periodic session probe anywhere.

Evidence: `src/App.tsx:93` reads both stores via "localStorage.getItem(key) ?? sessionStorage.getItem(key)".
Evidence: `src/App.tsx:97` is the whole brand check, "readAuthToken('brand_token')".

**Cross-role is safe.** The two keys are distinct and every write to either slot goes through
setToken, which refuses a token whose userType claim belongs to the other role. A valid brand token
does not open creator routes.

**Logout clears everything for the creator and not for the brand.** The creator helper clears both
stores, which is the F-0459 residual and is genuinely fixed. The brand path is the mirror image and
is not fixed: it removes only the localStorage key, and the only code that would clear sessionStorage
sits behind an isApiLive gate ten lines above. This is latent rather than live today only because the
brand login has no remember-me control at all and always writes localStorage — the moment anyone adds
one, a mock/demo-mode brand logout leaks exactly the way the creator one used to.

Evidence: `src/lib/auth-session.ts:209` does clear "sessionStorage.removeItem('creator_token');".
Evidence: `src/components/brand/brand-layout.tsx:234` clears only "localStorage.removeItem('brand_token');".
Evidence: `src/components/brand/brand-layout.tsx:224` gates the server call on "if (isApiLive()) {".

**The admin token is cleared by neither logout.** A repo-wide search finds that key in no logout
handler on either side, so on a shared browser the admin console stays reachable after a brand or
creator signs out.

Evidence: `src/App.tsx:162` gates the console on "localStorage.getItem('admin_token');".

Back or reload after a successful logout does not get the user back in: both handlers navigate after
clearing, and the guards re-run on mount.

---

## 3. Do the analytics screens show real numbers and names?

**The partial-data disclosure IS rendered, not merely computed.** It sits under the scope subtitle,
above the tiles, so six of eight creators timing out is disclosed rather than silently shrinking the
totals. Two honest caveats: it is above the tiles rather than next to each figure, and it is gated on
the aggregate result but not on the loading flag, so during a date-range change the previous range's
failure count sits under the new heading.

Evidence: `src/pages/brand-analytics.tsx:356` renders "totals below are partial." beside the count.

**Engagement rate is an unweighted mean and is not labelled as one.** A 120k-follower creator and a
2k-follower creator weigh the same, and the tile is titled only Engagement Rate. Worse, the deliberate
null (nobody reported a rate) is coerced to a confident zero at the render site — the exact pattern
F-0662 established as wrong on the creator profile.

Evidence: `src/pages/brand-analytics.tsx:130` averages via "totals.engagementRateSum / totals.engagementRateCount".
Evidence: `src/pages/brand-analytics.tsx:409` renders the null as zero with "metrics?.engagementRate ?? 0".

**A date present for only some creators does render as a cliff.** The per-date bucket sums whatever
reported that date, with no normalisation for how many creators contributed.

Evidence: `src/pages/brand-analytics.tsx:107` sums unnormalised with "bucket.reach += point.reach;".

**Selector switching is mostly coherent.** Every tile and the chart read one derived metrics value,
and the metric card renders a skeleton while loading, so stale numbers are masked. The two panels that
are not loading-gated are the scope subtitle and the partial-data caption, both of which briefly
describe the previous selection.

**In live mode the per-creator analytics page renders the raw creator id as its headline.** The demo
fixture is correctly gated off in live mode, and nothing replaces it — no endpoint is called to
resolve the creator's real name. The brand reads a UUID as the page title, with real metrics beneath
it and no identity attached. Not an empty string, not a permanent skeleton. Still open.

Evidence: `src/pages/brand-creator-analytics.tsx:79` yields undefined live via "isApiLive() ? undefined : demoCreators.find".
Evidence: `src/pages/brand-creator-analytics.tsx:110` then prints the id via "{creator?.displayName ?? creatorId}".

---

## 4. Discovery: do the counts and the filters describe the same thing?

**The two halves of that sentence describe the same filter set — this one is genuinely correct.** In
live mode the client-side predicate block is bypassed entirely, so the first number is the accumulated
server rows and the second is that same query's server total. Showing 12 of 340 means 12 loaded of 340
matching, and Load more grows the first number; the other 328 are reachable.

Evidence: `src/components/brand/discover/creator-discovery.tsx:815` short-circuits with "if (liveApi) return apiCreators;".

**Every chip the UI shows as active does reach the server, and the server honours it.** I checked this
against the backend rather than the client's own query helper. The controller declares city, verticals,
categories, languages, min/max followers, min/max rate, min/max engagement rate, isVerified and sortBy.
The multi-select city chips are comma-joined client-side and the server splits them into an OR of LIKEs,
so multi-city is not silently broken. No control claims to filter while being ignored.

Evidence: `influora-api/src/main/java/com/influora/web/CreatorController.java:45` accepts "@RequestParam(required = false) String city,".
Evidence: `influora-api/src/main/java/com/influora/web/CreatorController.java:48` accepts "@RequestParam(required = false) String languages,".
Evidence: `src/components/brand/discover/creator-discovery.tsx:689` sends "selectedCities.join(',')" when any are picked.
Evidence: `influora-api/src/main/java/com/influora/service/CreatorProfileSpecifications.java:106` splits on comma into "cityIn(List.of(city.split(".
Evidence: `influora-api/src/main/java/com/influora/service/CreatorProfileSpecifications.java:94` ORs one predicate per city, "for (String city : cities) {".

**Unpriced creators now behave the same in both modes.** The mock predicate ORs a null check,
mirroring the server rateOverlap spec, so the id-9 fixture with no averageRate demonstrates the real
live behaviour rather than a divergent one.

Evidence: `src/components/brand/discover/creator-discovery.tsx:874` ORs with "(c) => c.averageRate == null ||".

**Stale count window: yes, briefly.** The total is only replaced on a successful response and the
fetch is debounced 300ms, so between a filter change and the new response the old total sits under
the old rows. The initial-load skeleton masks the first load but not subsequent filter changes.

**Still open, not this batch's fault:** the city and language option lists are hardcoded, not sourced
from a facets endpoint — no city or language facet exists in the backend at all. A brand can pick a
city no creator record uses and get an honest but confusing zero.

---

## 5. Contract status: Superseded

**The word is a true statement.** I grepped every write of the enum in the backend: there is exactly
one, inside retirePredecessorIfSuperseded. No other path sets COMPLETED. A contract that simply ran to
completion stays ACTIVE and keeps the Active badge, so it is never described as superseded. The badge
also uses paused-stage tokens rather than the green approved ones, so the two are not visually
confusable in the list.

Evidence: `influora-api/src/main/java/com/influora/service/ContractService.java:691` is the only write, "predecessor.setStatus(ContractStatus.COMPLETED);".
Evidence: `src/components/brand/contracts/contracts-and-deliverables.tsx:632` maps it to "label: 'Superseded'".

The separate consequence worth naming: a fulfilled contract has no terminal state at all and displays
Active forever. That is a different gap, not a false statement about a superseded one.

**The detail panel does not mark the document as historical.** The PDF button and the signature record
were both widened to include the completed status, so a superseded contract still shows a green
fully-executed block identical to the live contract's. The only differentiator anywhere on screen is
the badge label. A brand who opens the superseded row directly sees a green executed record with a
downloadable PDF and nothing saying it was replaced. Open.

Evidence: `src/components/brand/contracts/contracts-and-deliverables.tsx:1238` still shows "Contract Fully Executed".

**The creator never sees this word.** There is no creator-side mapping of COMPLETED anywhere; the
creator contract tab derives its state from the two signature timestamps only. So the two parties do
read different things about one agreement — the creator reads Fully signed.

---

## 6. Contracts awaiting your signature (creator dashboard)

**An unlabelled row shows an amount and a milestone count, and no brand.** The identifying line is
conditional and correctly omitted rather than fabricated when the backend resolved neither a campaign
title nor a workspace name. What remains is the rupee amount and the milestone count, so two contracts
for the same amount with the same milestone count are genuinely indistinguishable and the creator
cannot tell which brand they are about to sign with. Open, and rooted in the backend resolution rather
than a frontend bug.

Evidence: `src/pages/creator-dashboard.tsx:605` renders the label with "truncate text-sm font-medium text-foreground".
Evidence: `src/pages/creator-dashboard.tsx:372` sources the list from "api.contracts.listUnsigned('creator');".

**A row with no linked deal is visibly non-interactive — genuinely handled.** It renders as a plain
div, not a Link, with a dashed border, reduced opacity, a default cursor, and no trailing arrow.

Evidence: `src/pages/creator-dashboard.tsx:636` styles it "flex cursor-default items-center gap-3 rounded-lg border border-dashed border-border/60 p-3 opacity-80".

**The brand-signed line is derived per record**, from each row's own signature timestamp rather than
from an assumed backend invariant.

Evidence: `src/pages/creator-dashboard.tsx:621` branches on "{contract.brandSignedAt".

**Refetch happens on remount only.** The effect has an empty dependency array. Signing happens on
/creator/chat, so returning to the dashboard remounts and re-fetches; there is no in-page
invalidation, so if signing ever moved onto the dashboard the list would immediately go stale.

Evidence: `src/pages/creator-dashboard.tsx:388` closes the effect with "}, []);".

---

## 7. Is the payment schedule fully visible before the creator signs?

**No — the in-flight case is handled but the failed case is not, and the failed case is the dangerous
one.** While the contract fetch is pending, a spinner replaces the tab. When the fetch ERRORS, the
live contract stays null, loading goes false, and the contract status falls back to a coarse value
derived from the deal-list row, which is not undefined — so the tab renders with milestones undefined
and shows the honest-looking empty state while the Sign button stays enabled. The error text is
rendered only in the sibling no-contract-yet branch, so nothing on screen says the fetch failed. A
creator can therefore sign a contract whose schedule failed to load.

Evidence: `src/pages/creator-chat.tsx:2884` covers only in-flight, "liveApi && liveContractLoading && !liveContract ? (".
Evidence: `src/pages/creator-chat.tsx:1904` falls back to "mapDealApiContractStatus(selectedDeal.contractStatus, selectedDeal.escrowFunded)".
Evidence: `src/components/creator/deal-room/creator-deal-contract-tab.tsx:257` then shows "No payment milestones are on file for this contract yet.".
Evidence: `src/components/creator/deal-room/creator-deal-contract-tab.tsx:295` leaves Sign live, "disabled={isSigning || !signerName.trim()}".

**An empty array and a failed load are not distinguishable** — one render gate covers both.

Evidence: `src/components/creator/deal-room/creator-deal-contract-tab.tsx:235` is the single gate, "{milestones && milestones.length > 0 ? (".

**The call-site wiring is real**, and this is the component's only caller, so the F-0640 correction did
land where the defect actually lived.

Evidence: `src/pages/creator-chat.tsx:2902` passes "milestones={liveContract?.milestones}".

**No reconciliation between the milestone sum and the contract total.** The tab shows a net figure
derived from the contract total (or the deal value) and lists milestone amounts separately; nothing
sums them and nothing compares them. If they disagree, the screen does not say which one binds.

**The contract terms text is not readable before signing, and the fallback PDF fabricates terms.** The
component receives no terms prop, so the only route to terms is Download PDF — and the server refuses
that until after BOTH signatures, which means pre-signature the catch always fires and generates a
local document whose usage rights, exclusivity and revision cap are hardcoded constants, with an empty
deliverables list. The document a creator reviews before signing contains invented terms. This is the
most serious thing on this surface and it is open.

Evidence: `influora-api/src/main/java/com/influora/web/ContractController.java:155` 404s "until the contract is fully signed by both parties and PDF".
Evidence: `src/components/creator/deal-room/creator-deal-contract-tab.tsx:111` hardcodes "usageRights: '6 months',".

Phone reachability of the last milestone: the list sits inside a scroll area, so it should be
reachable, but nothing has been exercised at a phone viewport. Unverified.

---

## 8. The counter-proposal round trip

**The creator's terms text is transmitted, folded into the message, and comes back to them.** No
usageRights field is sent, which is the correct decision — mapping free prose onto the deal's real
usage rights would overwrite them with whatever the creator typed. After submit the handler refetches
both the deal and the thread, so the creator's own view re-renders from the server copy.

Evidence: `src/pages/creator-chat.tsx:686` folds terms into the message, "const message = [data.message,".

**The brand's View Proposal dialog never displays that message.** In live mode the dialog renders a
budget tile, a timeline tile and a deliverable count, and says outright that terms are not returned.
The creator's terms are not below a fold — they are absent from the dialog entirely, while Accept sits
in the same footer. A brand can accept without ever seeing them. The message does appear in the thread
behind the dialog, but the dialog is modal and nothing directs the brand there.

Evidence: `src/components/brand/deals/deal-room-dashboard.tsx:1007` says "Itemized breakdown and terms aren".

**In demo mode the dialog shows fabricated usage rights.** That branch is gated off in live mode so it
cannot reach a real brand, but a demo viewer would certainly conclude those are the deal's rights.

Evidence: `src/components/brand/deals/deal-room-dashboard.tsx:1033` invents "Usage Rights: 3 months from delivery".

**A separate live-visible mislabel on that same footer:** the button claims a contract is created, and
the handler's own comment states no contract request is made anywhere in this component.

Evidence: `src/components/brand/deals/deal-room-dashboard.tsx:1068` reads "Accept & Create Contract".

**A stale deal link leaves the creator with no route back but manual navigation.** The not-found state
is a full-page centred message inside the creator layout, with no deal list beside it and no button —
the copy tells the creator their other deals are still there and then offers no control to reach them.

Evidence: `src/pages/creator-chat.tsx:1874` renders "Deal not found" as the whole page body.

---

## 9. Accept / counter / reject — did anything actually happen?

**Acceptance is real and survives a reload.** The handler awaits a refetch of both the deal list and
the thread before the success message, so the status badge, stage and affordances re-render from the
server's own copy rather than from optimistic local state.

Evidence: `src/components/brand/deals/deal-room-dashboard.tsx:452` awaits "Promise.all([loadDeals(), loadMessages(selectedDeal.id)]);".

**F-0440 is genuinely fixed.** The dialog close now sits inside the try block after the await, so a
failed accept keeps the dialog open with its error visible and a live Accept button; that button is
disabled while the action is in flight, so a double-click cannot double-submit. Counter and reject
follow the same shape.

Evidence: `src/components/brand/deals/deal-room-dashboard.tsx:464` closes only after success, "setShowProposalDialog(false);".

**But none of these three actions carries an idempotency key.** Accept has no key parameter at all,
and the brand counter passes none either — api.ts documents that the server then derives one from
deal id plus amount, so two legitimate same-amount counters collide and the second silently no-ops.
Retry after a timeout is therefore unsafe for accept and lossy for counter.

Evidence: `src/lib/api.ts:2266` has no key in "accept: (id: string, role: Role = 'creator') =>".
Evidence: `src/components/brand/deals/deal-room-dashboard.tsx:487` sends only "amount: Number(counterAmount) || 0,".

**The field-level error plumbing lands nowhere.** The envelope fields are carried through all three
choke points in api.ts, and a repo-wide search for any consumer outside api.ts returns zero hits.
Brand registration's first and last name inputs, brand settings' mobile number and the counter form
all still surface a server-named field error as one generic page-level message or toast.

Evidence: `src/lib/api.ts:690` carries it through as "envelope.error?.field,".

---

## 10. Hangs, timeouts and stuck submits

**The upload path has no timeout, no cancel, no real progress, and a preview that lies.** The
multipart helper is deliberately not wrapped in the abort controller, so if the connection dies
mid-upload the promise never settles, the finally never runs, Continue stays blocked by the
still-uploading guard, and there is no cancel. Progress is only ever 0 or 100 because the client is a
single fetch rather than XHR, so the bar sits at 0 forever. And the local preview is committed to
state BEFORE the upload starts, so the UI shows the file attached while nothing reached /uploads.
Reload is the only recovery.

Evidence: `src/lib/api.ts:851` takes no signal, "async uploadForm<T>(path: string, formData: FormData, role: Role = 'brand'): Promise<T> {".
Evidence: `src/components/brand/onboarding/onboarding-steps.tsx:911` sets the preview first, "onUpdate({ logoFile: file, logoPreview: preview, logoUpload: null });".
Evidence: `src/components/brand/onboarding/onboarding-steps.tsx:926` is the finally that never runs, "setIsUploadingLogo(false);".

**Upload errors also lose their status**, so any caller branching on it gets undefined for uploads.

Evidence: `src/lib/api.ts:866` omits the status argument, "envelope.error?.message || 'Upload failed');".

**The 30s TIMEOUT does reach callers with a message a user sees** — it is a real ApiError with
human-readable text, so every instanceof-ApiError message path renders it. Callers branching on
err.status fall through to their generic branch, which in the sites I read still shows that message
rather than a blank state.

**After a timeout the write state is genuinely unknown, and the counter retry creates a duplicate.**
The key is regenerated per submit, which is right for a deliberate re-counter and wrong for a retry;
nothing distinguishes the two.

Evidence: `src/pages/creator-chat.tsx:1505` mints a fresh key ending "-counter-${Date.now()}".

**Phone save is less careful than phone load, and the load classifier makes a false claim.** The save
handler branches on three machine-readable codes and otherwise shows a generic toast; it never uses
the load classifier, so it has no offline / server / auth distinction. On the load side, 403 is folded
in with 401 and reported as an expired session — but 403 in this codebase explicitly means
authenticated-but-not-permitted, as api.ts's own retry comment states. So a still-valid user hitting a
role gate is told their session expired and is offered a Sign In button that navigates to the brand
login. It does not clear the token, and the phone dialog is not open in that state, so nothing typed
is lost — but the message is false and the destination is wrong.

Evidence: `src/pages/brand-settings.tsx:102` treats both alike, "if (err.status === 401 || err.status === 403) {".
Evidence: `src/pages/brand-settings.tsx:984` sends them away with "onClick={() => navigate('/brand/login')}".
Evidence: `src/pages/brand-settings.tsx:376` saves through "const updated = await api.users.updateMe({ phone: normalized });".

---

## 11. Live channels that can look connected while delivering nothing

**A remember-me-off creator's deal-message stream can never authenticate.** This is a real, live,
previously unreported defect on this exact surface. The stream reads the token from localStorage only;
a remember-me-off creator's token lives in sessionStorage. So the stream sends no auth header, gets a
401, refreshes once (writing back to sessionStorage), reconnects, re-reads localStorage, gets null
again, and this time falls into the terminal branch because 401 is a terminal status — no reconnect
ever again. The room is permanently deaf for that session. Every other consumer was taught to read
both stores; this one call site was not.

Evidence: `src/lib/api.ts:2511` reads one store, "const token = localStorage.getItem(TOKEN_KEYS[role]);".
Evidence: `src/lib/api.ts:2389` makes that fatal, "const TERMINAL_STREAM_STATUSES: readonly number[] = [401, 403, 404];".

**onReconnect is wired to a real refetch in all three rooms**, so after a gap longer than the server's
retention the refetch is what closes the hole, and it does run. Nothing marks the gap to the user, but
nothing is silently missing either. Note the brand deal room's comment is now stale — the client does
send the replay header.

Evidence: `src/components/brand/deals/deal-room-dashboard.tsx:363` still claims "This transport has no Last-Event-ID replay".

**Connection status is visible in the two chat pages and invisible in the brand deal room.** The chat
pages render a banner whenever the stream is not open; the deal room opens its stream with only
message and reconnect handlers and no status handler, so there "nobody has replied" and "you are
disconnected" look identical.

Evidence: `src/pages/creator-chat.tsx:2233` gates a banner on "{liveApi && streamStatus !== 'open' && (".
Evidence: `src/components/brand/deals/deal-room-dashboard.tsx:342` opens with "const handle = messagesApi.stream('brand', dealId, {" and no status handler.

Replayed frames cannot duplicate: the handler upserts by id rather than appending. Ordering is
whatever the server replays, unsorted client-side. A token refresh mid-connection is handled once per
connection generation via the 401 branch.

**The admin websocket question is moot: nothing consumes it and there is no server.** A repo-wide
search for the hook finds it only inside its own file; no admin page or component calls it. On the
backend, a search for any websocket handler, config or endpoint in influora-api returns nothing at
all. So the admin UI cannot show stale numbers as live, because no admin UI is bound to this client
and no connection can be established. The client, its heartbeat and its auth-close policy are
unreachable code today.

Evidence: `src/admin/hooks/useAdminSocket.ts:72` is the only status wiring, "const offStatus = socket.onStatusChange(setStatus);".

---

## 12. Which claims rest only on an isolated render?

- **Brand analytics aggregate — exercised through the real page, but with the tiles stubbed.** The
  page's own effect and roster derivation do run, which is better than calling the aggregate function
  directly, but the metric card and trend chart are replaced by stubs on top of a mocked api, so
  nothing proves the disclosure caption renders beside real numbers.

  Evidence: `src/pages/__tests__/brand-analytics.aggregate.test.tsx:48` stubs with "vi.mock('@/components/analytics/CreatorMetricsCard', () => ({".

- **The 401 redirect was never observed as a navigation.** jsdom cannot navigate; the only possible
  assertion is on the href value.

- **The guards were exercised by mounting the guard component with a hand-set token**, not by loading
  a route in a browser against a real session.

- **Discovery filters were verified against the argument handed to a mocked api function**, not
  against the URL the browser sent, so the serialisation step is untested end to end. I closed that
  gap by reading the Java controller and specification instead, which is stronger than the test but
  still not a live request.

  Evidence: `src/components/brand/discover/__tests__/creator-discovery-server-filters.test.tsx:42` mocks with "vi.mock('@/lib/api', async () => {".

- **The admin websocket was run against a stubbed global, not a real server.** Since no server
  endpoint exists, no other test was possible — and that is the finding, not a testing gap.

  Evidence: `src/admin/services/websocket.f0465.test.ts:16` imports "AdminSocketClient, AdminSocketStatus" directly.

- **The proposal dialog and the milestone wiring ARE driven through their real call sites**, which is
  exactly the asymmetry the F-0640 correction round was after. Both still mock the api.

  Evidence: `src/pages/creator-chat-contract-milestones-wired.test.tsx:29` renders the page via "import CreatorChatPage from './creator-chat';".

**New fixes versus pre-existing code plus a test.** By the waves' own admissions and my reading:
F-0459, F-0460, F-0461, F-0462 and most of wave 2 were already correct in the tree and gained only the
regression tests the ledger asked for; F-0465, F-0278, F-0634, F-0635 and F-0638 were false findings;
genuinely new behaviour in this batch is the roster aggregation, the live gate on the demo creator
fixture, the honest copy branch, the dashboard row rendering, the F-0440 dialog ordering and the
F-0640 call-site prop.

**Screens that must still be loaded by hand before anything here is called working:** /brand/analytics
with a real roster where some metric calls fail; the /creator/chat contract tab where the contract
fetch fails rather than merely being slow; any deal room opened by a remember-me-off creator; the
brand onboarding logo step with the connection killed mid-upload; /brand/discover against a populated
backend; and the brand View Proposal dialog after a real creator counter carrying a terms block.

---

## Still open on this surface

1. The creator's pre-signature PDF fabricates usage rights, exclusivity and revision cap (Q7).
2. A failed contract fetch lets a creator sign against a silently empty schedule (Q7).
3. The SSE stream reads localStorage only, so remember-me-off creators get a permanently dead room (Q11).
4. The brand View Proposal dialog omits the creator's counter message in live mode, and its button text
   claims a contract is created that no code creates (Q8).
5. Server-named field errors are plumbed into ApiError and consumed nowhere (Q9).
6. The aggregate engagement rate is unweighted, unlabelled, and renders null as zero percent (Q3).
7. The live-mode per-creator analytics headline is a raw UUID (Q3).
8. A superseded contract's detail panel is indistinguishable from the live one (Q5).
9. Brand logout does not clear sessionStorage, and neither logout clears the admin token (Q2).
10. A 403 is misreported as an expired session in brand settings (Q10).
11. The upload path has no timeout, cancel, real progress, or honest attach state (Q10).
12. The admin websocket client and hook are unreachable code with no server endpoint (Q11).
13. Accept, counter and reject carry no idempotency key, so a post-timeout retry can duplicate (Q9).
14. Discovery city and language options are hardcoded, with no facets endpoint behind them (Q4).
