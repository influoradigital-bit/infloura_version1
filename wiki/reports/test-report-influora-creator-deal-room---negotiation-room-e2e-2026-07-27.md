# 🧪 Test Report: Influora Creator Deal Room + Negotiation Room E2E

- **Date:** 2026-07-27
- **Target:** http://200.141.1.6 (creator: tejas.chache5@gmail.com)
- **Stages run:** E2E
- **Health:** 0%
- **Verdict:** FAIL ❌

## Summary

| Severity | Count |
|----------|-------|
| Critical | 4 |
| High | 4 |
| Medium | 1 |
| Low | 1 |

## Findings by tester

### Neha — E2E

#### [Critical] Brand negotiation room: Accept and Counter are dead buttons (no onClick)
- **Where:** src/pages/brand-chat.tsx:1488-1497
- **Issue:** The proposal card in the brand negotiation room renders <Button>Accept</Button> and <Button>Counter</Button> with NO onClick handler at all. Clicking either does literally nothing - no request, no state change, no error. brand-chat.tsx contains zero calls to api.deals.accept / api.deals.reject; the only working brand-side accept lives on a completely different page (brand-campaign-detail.tsx:651/674). Result: a brand can never close a negotiation from the room where the negotiation actually happens, so any creator counter-offer is a dead end.
- **Fix:** Wire both buttons to handlers that call api.deals.accept(dealId,'brand') and open the existing counter form, mirroring creator-chat.tsx:991-1085. On success reload the message timeline (brand-chat.tsx already has loadMessages at :771) and surface a toast.

#### [Critical] Accepted proposal still offers Accept/Counter/Decline -> repeat accept returns 409 DEAL_NOT_ACCEPTABLE
- **Where:** influora-api/.../service/DealService.java:504 + :713; src/pages/creator-chat.tsx:1651
- **Issue:** persistProposalMessage() hardcodes metadata.status = 'pending' at creation (DealService.java:713) and NOTHING ever writes it again - doAccept() only appends a system message (:504), it never flips the originating proposal message to 'accepted'. The creator UI gates the action row on event.metadata?.status === 'pending' (creator-chat.tsx:1651), so the buttons survive even a hard reload. Reproduced live: deal 01KY73H2HCEY0PY942G87W39JW is already CONTRACTED (stage 2, 'Contracted' badge) yet still shows Accept/Counter/Decline; clicking Accept returns POST /api/v1/deals/01KY73H2HCEY0PY942G87W39JW/accept 409 (Conflict) because Collaboration.canAccept() (Collaboration.java:185-190) only permits INVITED/APPLIED/SHORTLISTED/IN_NEGOTIATION. The backend guard is correct - the UI is lying about what actions are available.
- **Fix:** In doAccept()/doReject(), update the last proposal DealMessage's metadata.status to 'accepted'/'rejected' and persist it. Additionally gate the frontend action row on the deal's own state (canAccept-equivalent), not just message metadata, so a CONTRACTED deal never renders Accept.

#### [Critical] One render error permanently whites out the ENTIRE app - ErrorBoundary wraps the router and never resets
- **Where:** src/App.tsx:129-130; src/components/ErrorBoundary.tsx:20-59
- **Issue:** <ErrorBoundary> is mounted OUTSIDE <BrowserRouter> (App.tsx:129-130), so it wraps the whole router. getDerivedStateFromError sets hasError = true (ErrorBoundary.tsx:26-28) and NOTHING ever resets it - there is no resetKey, no route-change reset, no componentDidUpdate. Consequence: a single transient render throw on ONE page tears down the entire Router and every subsequent tab/nav click renders the same dead fallback, because the routing tree no longer exists. This is exactly the reported symptom - 'click All, then Negotiating, then any other tab and it goes white' - once it trips, the SPA is bricked until a manual full page reload. The fallback's own Reload button is the only exit.
- **Fix:** Move <ErrorBoundary> INSIDE <BrowserRouter> so navigation still works when it trips, and reset it on route change (key the boundary on useLocation().pathname, or add a componentDidUpdate/resetKeys prop). Nav must keep working after a crash on a single page.

#### [Critical] White screen triggered by the deal filter tab sequence (All -> Negotiating -> any other tab)
- **Where:** Reported on /creator/deals filter chips (src/pages/creator-deals.tsx:391-420) - exact throw site NOT yet isolated
- **Issue:** User-reproduced: clicking All, then Negotiating, then any other tab blanks the page and every tab stays blank afterwards. The persistence across tabs is fully explained by the ErrorBoundary latch above. The ORIGINATING throw is not yet isolated. Ruled out by source inspection: STATUS_CHIPS ids ('all','new','negotiating','in_progress','completed') are all accepted by the backend statusesForFilter (DealService.java:863-890) so no 400; StatusPill's config record (creator-deals.tsx:641-648) and EmptyState's copy record (:661-670) both cover every status/filter key; mapCollaborationStatusToDealsPage (creator-deal-mappers.ts:30-56) is exhaustive with a safe default; mapDealToDealsPageRow correctly converts lastMessageAt to a real Date so the .getTime() sort at :277-279 is safe. NOTE: the 'Active' chip sends filter 'in_progress' to the backend, which returns ONLY IN_PROGRESS, while the chip's local match() also expects contracted and review - a real filter mismatch worth fixing regardless.
- **Fix:** BLOCKED pending the console stack trace at the moment of blanking (or a test login so this can be driven in-browser). The [ErrorBoundary] Uncaught render error line that componentDidCatch logs (ErrorBoundary.tsx:32) names the exact component and throw - that single line pins it. Fix the ErrorBoundary latch in parallel; it is a genuine defect on its own.

#### [High] Creator deal room: Accept / Decline change nothing visible in the room
- **Where:** src/pages/creator-chat.tsx:991-1037 (vs :621-640)
- **Issue:** handleAcceptProposal and handleDeclineProposal call loadDeals() - which refreshes only the left-hand deal LIST - and never reload the message timeline. The proposal card is derived from liveMessages, which is fetched only when selectedDeal.id changes (:621-640). There is also no success toast. Net user experience: click Accept, spinner runs, card comes back looking identical, same buttons, same 'Pending' badge - the action appears to have done nothing even when it succeeded server-side. brand-chat.tsx does this correctly for counters (calls loadMessages at :1001), so the creator side is the outlier.
- **Fix:** Extract the message fetch into a loadMessages(dealId) callback (mirroring brand-chat.tsx:771) and await it after accept/decline/counter succeeds; add a success toast confirming the action.

#### [High] Creator cannot scroll the deal room - view is yanked back to the bottom every render
- **Where:** src/pages/creator-chat.tsx:1211-1215, :1217-1254, :1256-1258
- **Issue:** baseEventsForDeal (:1211) is a plain expression, not memoized, so it produces a NEW array reference on every render ([] or mockTimelineEvents.slice(0,3)). It is a dependency of the events useMemo (:1254), so events also returns a new reference every render. The auto-scroll effect depends on [events, openPanel] (:1256) and therefore fires scrollIntoView({behavior:'smooth'}) on EVERY render - including every keystroke in the message box, since setMessage re-renders the page. The creator gets dragged back to the newest message the moment they try to read scrollback.
- **Fix:** Wrap baseEventsForDeal in React.useMemo keyed on selectedDeal?.id (and drop it from the live-mode dependency path), so events is referentially stable and the scroll effect fires only when the timeline genuinely changes. Optionally only auto-scroll when the user is already near the bottom.

#### [High] Accept / decline / counter never reach the other party's open deal room (no SSE publish)
- **Where:** influora-api/.../service/DealService.java:736 and :740 (vs :395)
- **Issue:** messageStreamRegistry.publish(...) is called in exactly ONE place - the send-message path (:395). Both persistProposalMessage() (:736) and appendSystemMessage() (:740) save the row to the database and stop there. Consequence: 'Creator accepted the proposal', 'Brand rejected: ...' and EVERY counter-offer are invisible to the counterparty's open GET /deals/{id}/messages/stream connection. During a live negotiation the room looks frozen for the other side until they do a full page reload or switch deals and back.
- **Fix:** Publish to messageStreamRegistry from persistProposalMessage() and appendSystemMessage() using the same best-effort try/catch pattern already used at :395, so a publish failure never fails the underlying accept/counter.

#### [High] Wrong identity in creator sidebar: logged in as Tejas, UI shows @priya_sharma
- **Where:** src/components/creator/creator-layout.tsx:242 and :325; root cause src/pages/creator-login.tsx:40-43
- **Issue:** The avatar dropdown renders {user?.email || '@priya_sharma'} - a leftover demo handle - in both the desktop (:242) and mobile (:325) menus. The real cause is upstream: creator-login.tsx only calls login() when NOT in live mode (:40-43), so on the deployed live-mode build the auth store is never populated and user stays null after every real login. Every user?.* read in the layout therefore falls through to its demo default (the sidebar display name and avatar initials are hitting the same bug). The Profile page shows the correct 'Tejas Creater' because it fetches from the API independently, which is what makes the mismatch so visible. Verified the shipped bundle index-NdzlUg4U.js on 200.141.1.6 still contains the '@priya_sharma' string.
- **Fix:** Populate the auth store from the live login response (call login() with the real user in both modes, or hydrate from GET /me after setToken). Delete the '@priya_sharma' fallback entirely and render a neutral placeholder; also show the creator handle rather than the email if the UI implies an @handle.

#### [Medium] 409 conflict surfaces only in the console - creator gets no usable explanation
- **Where:** src/pages/creator-chat.tsx:1004-1011; src/hooks/use-toast.ts:8-9
- **Issue:** The 409 is caught and passed to toast() with the server message, and <Toaster /> IS mounted (App.tsx:588), but the failure is still effectively invisible to the user - the observed symptom was a raw 'POST .../accept 409 (Conflict)' in devtools with nothing actionable on screen. TOAST_LIMIT is 1 and TOAST_REMOVE_DELAY is 1000000ms, so a single stale toast can occupy the only slot and suppress later ones. Combined with finding #2 the creator is invited to press a button that can only ever fail.
- **Fix:** Give error toasts an explicit short duration, and render a persistent inline state on the proposal card itself (e.g. 'This deal has already moved to Contracted') rather than relying solely on a transient toast.

#### [Low] Deal room height overflows the creator layout by 8px
- **Where:** src/pages/creator-chat.tsx:1335 vs src/components/creator/creator-layout.tsx:274
- **Issue:** The deal room root is h-[calc(100vh-4rem)] (:1335) but the CreatorLayout header it sits under is h-14 (3.5rem), not 4rem. The two do not add up to the viewport, leaving a small permanent vertical offset. Cosmetic on its own, but it compounds the scroll problem in finding #4.
- **Fix:** Change the deal room to h-[calc(100vh-3.5rem)] or make the layout header height a shared token so the two cannot drift apart.

## Next steps
- Route Critical/High blockers to Ananya (frontend) / Vikram (backend) via Arjun.
- Escalate Critical to Swapnil via Kavya/Priya.
- Re-run the same stage after fixes before final PASS.

_Generated by the `tester` skill._