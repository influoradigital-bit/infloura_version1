# 🧪 Test Report: Influora Creator Deal Room Negotiation Room and Public Page E2E

- **Date:** 2026-07-27
- **Target:** http://200.141.1.6 (creator: tejas.chache5@gmail.com) - LIVE logged-in walkthrough
- **Stages run:** E2E
- **Health:** 0%
- **Verdict:** FAIL ❌

## Summary

| Severity | Count |
|----------|-------|
| Critical | 3 |
| High | 7 |
| Medium | 5 |
| Low | 2 |

## Findings by tester

### Neha — E2E

#### [Critical] CONFIRMED LIVE - 'Share page' button does nothing at all, so the creator can never share their public page
- **Where:** src/pages/creator-portfolio-public.tsx:88-106 (sharePage)
- **Issue:** Measured on the live site: window.isSecureContext === false, navigator.share === undefined, navigator.clipboard === undefined, because the app is served over plain http:// on a bare IP and both APIs require a secure context. sharePage() therefore skips the `if (navigator.share)` branch and calls `navigator.clipboard.writeText(url)` on an undefined object, which throws TypeError - swallowed by the empty catch at :105 whose own comment says 'clipboard blocked - no-op, button stays idle'. Clicked it live: label stays 'Share page', nothing is copied, no toast, no error, no feedback whatsoever. THIS IS THE REPORTED BUG - the creator presses Share and has nothing to paste anywhere. (The page itself is fine: GET /api/v1/portfolio/tejas_creater returns 200 with no Authorization header, and /@tejas_creater, /tejas_creater and /@tejas_creater/ all serve the SPA shell 200. The data is public - only the sharing is broken.)
- **Fix:** Serve the app over HTTPS (this single change restores navigator.share and navigator.clipboard). Until then add a document.execCommand('copy') / hidden-textarea fallback, and ALWAYS show the URL in a selectable input plus a visible success/failure toast so the button can never fail silently.

#### [Critical] CONFIRMED LIVE - accepted/contracted deal still shows Accept, and pressing it returns 409
- **Where:** influora-api/.../service/DealService.java:504 + :713; src/pages/creator-chat.tsx:1651
- **Issue:** Reproduced end to end in the browser. Deal 01KY73H2HCEY0PY942G87W39JW ('QA E2E - Diwali Skincare Reels') renders 'Contracted' in the deal list and sits on step 2 Contract, yet the proposal card still reads 'Pending' and offers Accept / Counter / Decline. Clicking Accept produced POST /api/v1/deals/01KY73H2HCEY0PY942G87W39JW/accept -> 409 Conflict. Root cause: persistProposalMessage() hardcodes metadata.status='pending' (DealService.java:713) and nothing ever rewrites it - doAccept() only appends a system message (:504). The UI gates the action row on event.metadata?.status === 'pending' (creator-chat.tsx:1651), so the buttons survive forever, including across a hard reload. Collaboration.canAccept() (Collaboration.java:185-190) correctly refuses anything past IN_NEGOTIATION - the backend is right, the UI is lying about what is possible.
- **Fix:** In doAccept()/doReject(), rewrite the originating proposal message's metadata.status to 'accepted'/'rejected' and persist it. Also gate the frontend action row on the deal's own state, so a CONTRACTED deal can never render an Accept button.

#### [Critical] CONFIRMED LIVE - the 409 failure is completely silent, no toast ever renders
- **Where:** src/pages/creator-chat.tsx:1004-1011; src/hooks/use-toast.ts:8-9
- **Issue:** After the 409 above, queried the live DOM: zero toast nodes ([role=status], [data-radix-toast-root], li[data-state] all empty) and document.body.innerText does not contain the server message. The ONLY trace is a console line: '[error] Failed to accept proposal ApiError: This deal cannot be accepted in its current state'. So the creator presses Accept, the page does not change in any way, and they are given no indication that anything failed. Combined with the finding above they are invited to press a button that can only ever fail, silently. (Note TOAST_LIMIT=1 and TOAST_REMOVE_DELAY=1000000ms in use-toast.ts:8-9 - a single earlier toast can occupy the only slot indefinitely.)
- **Fix:** Verify the Toaster portal actually mounts on this route, drop TOAST_REMOVE_DELAY to a few seconds, and render a persistent inline state on the proposal card itself ('This deal has already moved to Contracted') rather than depending on a transient toast.

#### [High] CONFIRMED LIVE - top 106px of the deal room is clipped and unreachable, creator cannot scroll to it
- **Where:** src/pages/creator-chat.tsx:1520-1521 and :1256-1258
- **Issue:** Measured the live container chain. The wrapper div at creator-chat.tsx:1520 ('flex-1 flex flex-col min-h-0 overflow-hidden') reports scrollHeight 616 vs clientHeight 510 - 106px of overflow - AND its computed overflow-y is 'hidden'. It was sitting at scrollTop 104.8. Mechanism: the auto-scroll effect at :1256 calls messagesEndRef.scrollIntoView(), and scrollIntoView scrolls EVERY scrollable ancestor including overflow:hidden ones. Because that wrapper has no scrollbar and does not respond to the wheel, the 106px it scrolled away is permanently unreachable by the user - only script can reset it (verified: setting scrollTop=0 from JS restores the hidden content). That is exactly the clipped proposal-card header in the reported screenshot. Contributing: the ScrollArea root carries p-4 (:1521) which makes it 539px tall inside a 510px parent, guaranteeing the overflow, and baseEventsForDeal (:1211) is unmemoized so the events useMemo and therefore this scroll effect re-fire on every single render, including every keystroke in the message box.
- **Fix:** Three parts: (1) move p-4 off the ScrollArea root onto the inner content div and add min-h-0 so the ScrollArea fits its parent; (2) replace scrollIntoView with a direct scrollTop assignment on the Radix viewport so no ancestor is ever scrolled; (3) wrap baseEventsForDeal in useMemo keyed on selectedDeal?.id so the effect only fires when the timeline genuinely changes.

#### [High] CONFIRMED LIVE - the same deal shows two different statuses on two pages
- **Where:** src/lib/creator-deal-mappers.ts:30-56 vs :58+ (mapCollaborationStatusToDealsPage vs mapCollaborationStatusToChatPage)
- **Issue:** Deal 'QA E2E - Diwali Skincare Reels' renders as 'Negotiating' in the /creator/deals list but as 'Contracted' in the deal room list for the very same deal, in the same session, seconds apart. The two pages run different status mappers over the same backend CollaborationStatus and disagree - mapCollaborationStatusToDealsPage folds TERMS_AGREED into 'negotiating' while the chat mapper treats it as contracted. The creator cannot tell what state their deal is actually in.
- **Fix:** Collapse to one shared mapper (or one shared display-status helper) used by both pages so a given CollaborationStatus can only ever render one label.

#### [High] CONFIRMED LIVE - creator identity is wrong everywhere in the shell: 'Creator Account' / 'IN' / '@priya_sharma'
- **Where:** src/components/creator/creator-layout.tsx:229, :234, :242, :325; root cause src/pages/creator-login.tsx:40-43
- **Issue:** Read live from the logged-in sidebar: the account button renders initials 'IN' and the name 'Creator Account' while logged in as Tejas, and the dropdown shows the hardcoded demo handle '@priya_sharma' (creator-layout.tsx:242/:325). Root cause confirmed in source: creator-login.tsx only calls login() when NOT in live mode (:40-43), so on this live build the auth store is never populated and user stays null after every real login - every user?.* read falls through to its demo default. The Profile page shows the correct 'Tejas Creater' because it fetches independently, which is what makes the mismatch so obvious. The shipped bundle index-NdzlUg4U.js still contains the '@priya_sharma' string.
- **Fix:** Populate the auth store from the live login response (call login() with the real user in both modes, or hydrate from GET /me after setToken). Delete the '@priya_sharma' and 'Creator Account' fallbacks so a missing user renders a neutral skeleton instead of someone else's identity.

#### [High] Brand negotiation room: Accept and Counter are dead buttons (no onClick)
- **Where:** src/pages/brand-chat.tsx:1488-1497
- **Issue:** The brand-side proposal card renders <Button>Accept</Button> and <Button>Counter</Button> with NO onClick handler at all - clicking either does nothing, issues no request and changes no state. brand-chat.tsx contains zero calls to api.deals.accept / api.deals.reject; the only working brand accept lives on a different page entirely (brand-campaign-detail.tsx:651/674). A brand therefore cannot close a negotiation from the room where the negotiation happens, so any creator counter-offer is a dead end. (Source-confirmed; not driven live because that needs a brand login.)
- **Fix:** Wire both buttons to api.deals.accept(dealId,'brand') and the existing counter form, mirroring creator-chat.tsx:991-1085, then reload the message timeline (brand-chat.tsx already has loadMessages at :771) and show a toast.

#### [High] Accept / decline / counter never reach the other party's open deal room (no SSE publish)
- **Where:** influora-api/.../service/DealService.java:736 and :740 (vs :395)
- **Issue:** messageStreamRegistry.publish(...) is called in exactly ONE place, the send-message path (:395). Both persistProposalMessage() (:736) and appendSystemMessage() (:740) save the row and stop. So 'Creator accepted the proposal', 'Brand rejected: ...' and EVERY counter-offer are invisible to the counterparty's open /messages/stream connection - during a live negotiation the other side sees a frozen room until a full reload. Confirmed the stream itself is healthy: GET /deals/01KY73H2HCEY0PY942G87W39JW/messages/stream returned 200 in the live session.
- **Fix:** Publish to messageStreamRegistry from persistProposalMessage() and appendSystemMessage() using the same best-effort try/catch already at :395, so a publish failure never fails the underlying accept/counter.

#### [High] Creator deal room: Accept / Decline never refresh the message timeline
- **Where:** src/pages/creator-chat.tsx:991-1037 (vs :621-640)
- **Issue:** handleAcceptProposal and handleDeclineProposal call loadDeals(), which refreshes only the left-hand deal LIST, and never reload the messages. The proposal card derives from liveMessages, fetched only when selectedDeal.id changes (:621-640). There is no success toast either. Even on the success path the room looks completely unchanged. brand-chat.tsx does this correctly for counters (loadMessages at :1001), so the creator side is the outlier.
- **Fix:** Extract the message fetch into a loadMessages(dealId) callback mirroring brand-chat.tsx:771 and await it after accept/decline/counter succeeds; add a success toast.

#### [High] One render error permanently whites out the ENTIRE app - ErrorBoundary wraps the router and never resets
- **Where:** src/App.tsx:129-130; src/components/ErrorBoundary.tsx:20-59
- **Issue:** <ErrorBoundary> is mounted OUTSIDE <BrowserRouter> (App.tsx:129-130), so it wraps the whole router. getDerivedStateFromError sets hasError=true (ErrorBoundary.tsx:26-28) and NOTHING resets it - no resetKeys, no route-change reset, no componentDidUpdate. One transient render throw on one page therefore tears down the entire Router, and every subsequent tab click renders the same dead fallback because the routing tree no longer exists. This is the mechanism behind the reported 'after that, every other tab goes white' - once tripped the SPA is bricked until a manual reload.
- **Fix:** Move <ErrorBoundary> INSIDE <BrowserRouter> so navigation still works when it trips, and reset it on route change (key it on useLocation().pathname or add resetKeys).

#### [Medium] NOT REPRODUCED - white screen on the All -> Negotiating -> other tab sequence
- **Where:** /creator/deals filter chips and /creator/chat panels - attempted on account tejas.chache5@gmail.com
- **Issue:** Drove the reported sequence live and could NOT reproduce it on this account. Clicked All, Negotiating, Active, Completed, New and back to All - all five requests returned 200 (GET /api/v1/deals?status=all|negotiating|in_progress|completed|new), zero console errors, no blank render. Then walked all 11 sidebar nav items (Home, Deals, Campaigns, Applications, Co-pilot, Analytics, Wallet, Reviews, Disputes, Coupons, Affiliate) - none crashed or blanked. Then opened the deal room and clicked every phase step and tool panel (Negotiate, Deliver, Pay, Deliverables, Payments) - no crash. The latch behaviour once it DOES trip is fully explained by the ErrorBoundary finding above, but the originating throw is still unidentified and likely depends on specific deal data this account does not have.
- **Fix:** Need the console output at the moment of blanking - componentDidCatch logs '[ErrorBoundary] Uncaught render error:' plus the component stack (ErrorBoundary.tsx:32), which names the exact throw site. Alternatively identify which creator account / deal reproduces it and retest against that data.

#### [Medium] CONFIRMED LIVE - every filter chip count collapses to 0 as soon as a filter is selected
- **Where:** src/pages/creator-deals.tsx:253-259 and :217-251
- **Issue:** Observed live: on the All tab the chips read All 2 / Negotiating 2; after clicking Active every single chip reads 0, including All. Cause: the effect refetches deals scoped to activeFilter (:222) and replaces the whole deals array, while counts (:253-259) is computed from that same filtered array. So the badges describe the current filter's result set rather than the totals, and every other chip is reported as empty. The creator is told they have no deals at all.
- **Fix:** Fetch the unfiltered set once for the counts (or have the API return per-status totals) and keep the badge numbers independent of the active filter.

#### [Medium] CONFIRMED LIVE - 'Active' tab hides contracted and in-review deals
- **Where:** src/pages/creator-deals.tsx:85 vs influora-api/.../DealService.java:863-890
- **Issue:** The Active chip's local match() accepts contracted || in_progress || review, but the chip id sent to the API is 'in_progress', and the backend's statusesForFilter maps that to ONLY CollaborationStatus.IN_PROGRESS. Verified live: with a contracted deal present, the Active tab rendered 'Nothing active - Deals you're working on will show here.' A signed, contracted deal is invisible on the tab the creator would look at for it.
- **Fix:** Either send a filter the backend understands as the same union (add a 'contracted,in_progress,review' multi-status filter), or align statusesForFilter's 'in_progress' case with the chip's intent.

#### [Medium] CONFIRMED LIVE - public page renders 'Synced NaNd ago'
- **Where:** src/pages/creator-portfolio-public.tsx (platform stats block)
- **Issue:** Read straight off the live public page at /@tejas_creater: the Platform Stats card renders the literal string 'Synced NaNd ago'. A missing or unparseable last-synced timestamp is being fed through a day-difference calculation without a guard, and the NaN is rendered to the public. This is on the page creators send to brands.
- **Fix:** Guard the timestamp before formatting and fall back to hiding the line (or 'Not synced yet') when it is absent or invalid.

#### [Medium] Public page URL is a bare IP over plain HTTP - unusable as a shareable link
- **Where:** src/pages/creator-portfolio-public.tsx:89; surfaced to creators at src/pages/creator-profile.tsx:197
- **Issue:** The share URL is built as `${window.location.origin}/@${username}`, which on this deployment yields http://200.141.1.6/@tejas_creater. The profile page tells the creator to 'share it in your Instagram bio' (creator-profile.tsx:197) - Instagram and most messaging apps will not linkify or will actively warn on a bare-IP http:// URL, and any recipient outside this network cannot resolve it at all. It is also the direct cause of the secure-context failure that kills the Share button (see the Critical finding).
- **Fix:** Put the app behind a real domain with TLS and drive the share URL from a configured public base URL rather than window.location.origin, so staging IPs can never leak into a link a creator hands to a brand.

#### [Low] CONFIRMED LIVE - sidebar Deals badge is hardcoded to 3
- **Where:** src/components/creator/creator-layout.tsx:129, :203-207
- **Issue:** unreadCount is React.useState(3) with no setter and no data source, so the sidebar always shows 'Deals 3' and a red '3' on the bell. Observed live as 'Deals|3' while the account actually had 2 deals and 0 unread. A permanently wrong notification count trains creators to ignore the badge.
- **Fix:** Drive the badge from the real unread total (the deals API already returns unreadCount per deal) or remove it until it is wired.

#### [Low] Deal room height overflows the creator layout by 8px
- **Where:** src/pages/creator-chat.tsx:1335 vs src/components/creator/creator-layout.tsx:274
- **Issue:** The deal room root is h-[calc(100vh-4rem)] but the CreatorLayout header above it is h-14 (3.5rem). Measured live: main is 664px inside a 720px viewport with the deal room at 656px, leaving a permanent mismatch. Cosmetic alone, but it reduces the message viewport and compounds the 106px clipping above.
- **Fix:** Use h-[calc(100vh-3.5rem)] or share a single header-height token between the layout and the pages that subtract it.

## Next steps
- Route Critical/High blockers to Ananya (frontend) / Vikram (backend) via Arjun.
- Escalate Critical to Swapnil via Kavya/Priya.
- Re-run the same stage after fixes before final PASS.

_Generated by the `tester` skill._