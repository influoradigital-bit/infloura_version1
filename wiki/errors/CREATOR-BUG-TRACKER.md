# 🐞 CREATOR SURFACE — MASTER BUG TRACKER

> **Owner (document):** Priya Sharma — CTO
> **Routing authority:** Arjun (Eng Lead / COO)
> **Maintainer (status updates):** **Tara** — see [§6 Tara's Update Protocol](#6-taras-update-protocol)
> **Source of findings:** Neha (E2E), live logged-in walkthrough of `http://200.141.1.6` as `tejas.chache5@gmail.com`
> **Opened:** 2026-07-27
> **Last updated:** 2026-07-27 — by Tara (Wave 1 results: CR-01/CR-02/CR-03 → IN VERIFY, CR-09 → IN PROGRESS (partial), CR-15 → BLOCKED; added CR-18–CR-22; recorded Wave 1 verification evidence; §8 note on `DealMessage` ObjectMapper precedent)

**THIS IS THE SINGLE SOURCE OF TRUTH FOR ALL CREATOR-SIDE DEFECTS.**
No creator bug is worked, closed, or re-opened anywhere else. One file. One status column.

---

## 1. Actual Tech Stack (for anyone picking up a ticket)

Correcting the record before work starts — the generic company stack template does **not** describe this repo:

| Layer | Reality in this repo |
|---|---|
| Frontend | **Vite + React 19 + React Router 7** (`src/`) — *not* Next.js App Router |
| UI | Radix primitives + shadcn-style components + TailwindCSS |
| Backend | **Java / Spring Boot** (`influora-api/`) — *not* Next.js Route Handlers, *not* Prisma |
| Realtime | SSE via `messageStreamRegistry` → `GET /deals/{id}/messages/stream` |
| Deploy | Served over **plain HTTP on a bare IP** (`http://200.141.1.6`) — this is itself the root cause of CR-01 |

---

## 2. Status Legend

| Status | Meaning |
|---|---|
| `OPEN` | Not started |
| `ASSIGNED` | Owner accepted, not yet in progress |
| `IN PROGRESS` | Being worked |
| `IN QA` | Code done → with Kavya |
| `IN VERIFY` | Kavya passed → with Neha for live re-test |
| `DONE` | Neha re-tested on the live URL and confirmed fixed |
| `BLOCKED` | Cannot proceed — blocker named in the row |

**A ticket is only `DONE` when Neha has re-run the original repro steps against the deployed build.** Code merged ≠ done.

---

## 3. Summary Board

| ID | Severity | Title | Owner | Status |
|---|---|---|---|---|
| CR-01 | 🔴 Critical | "Share page" button does nothing — creator can never share their page | Meera → Ananya | IN VERIFY |
| CR-02 | 🔴 Critical | Contracted deal still offers Accept → 409 Conflict | Vikram + Ananya | IN VERIFY |
| CR-03 | 🔴 Critical | The 409 failure is completely silent — no toast ever renders | Ananya | IN VERIFY |
| CR-04 | 🟠 High | Top 106px of deal room clipped and unreachable — can't scroll | Ananya | OPEN |
| CR-05 | 🟠 High | Same deal shows two different statuses on two pages | Ananya | OPEN |
| CR-06 | 🟠 High | Wrong identity in shell — "Creator Account" / "IN" / "@priya_sharma" | Ananya | OPEN |
| CR-07 | 🟠 High | Brand negotiation room: Accept + Counter are dead buttons | Ananya | OPEN |
| CR-08 | 🟠 High | Accept/decline/counter never reach the other party (no SSE publish) | Vikram | OPEN |
| CR-09 | 🟠 High | Creator accept/decline never refresh the message timeline | Ananya | IN PROGRESS (partial) |
| CR-10 | 🟠 High | One render error whites out the ENTIRE app permanently | Ananya | OPEN |
| CR-11 | 🟡 Medium | White screen on tab sequence — **NOT REPRODUCED**, needs data | Neha | BLOCKED |
| CR-12 | 🟡 Medium | All filter chip counts collapse to 0 when a filter is active | Ananya | OPEN |
| CR-13 | 🟡 Medium | "Active" tab hides contracted + in-review deals | Vikram + Ananya | OPEN |
| CR-14 | 🟡 Medium | Public page renders "Synced NaNd ago" | Ananya | OPEN |
| CR-15 | 🟡 Medium | Public URL is a bare IP over HTTP — unusable as a shared link | Meera | BLOCKED |
| CR-16 | 🟢 Low | Sidebar "Deals 3" badge is hardcoded | Ananya | OPEN |
| CR-17 | 🟢 Low | Deal room height overflows layout by 8px | Ananya | OPEN |
| CR-18 | 🟡 Medium | `usageRights` missing from proposal metadata — always "Not specified" | Priya (implemented) | IN VERIFY |
| CR-19 | 🟡 Medium | N1: `settleStatus` BigDecimal→Double round-trip; two bare `ObjectMapper`s | Vikram | IN PROGRESS |
| CR-20 | 🟢 Low | N2: `loadMessages` lost unmount cancellation (no leak today, React 18+) | Ananya | OPEN |
| CR-21 | 🟢 Low | N3: "Refresh deal" flashes the whole page (full-page spinner) | Ananya | OPEN |
| CR-22 | 🟡 Medium | Brand-side `canReject` withdrawal flow needs its own UI (not the proposal card) | Unassigned | OPEN |

**Totals:** 3 Critical · 7 High · 8 Medium · 4 Low = **22 logged** *(0 DONE — Wave 1 fixes are code-complete and QA-passed, but nothing has been deployed; nothing is verified on the live URL)*

---

## 4. Execution Waves (Arjun — routing & sequencing)

Ordered by business damage, not by severity label. Ship wave by wave.

### 🌊 Wave 1 — Revenue-blocking. Start today.
> These three are why deals die and why creators can't grow their audience.

| ID | Owner | Why first |
|---|---|---|
| CR-01 + CR-15 | **Meera** (infra) → **Ananya** (client) | One HTTPS migration fixes both. Every day on HTTP is a day no creator can share their page. |
| CR-02 | **Vikram** (backend) → **Ananya** (gate) | Creators are being invited to press a button that can only 409. |
| CR-03 | **Ananya** | Even after CR-02, silent failure must never be possible again. |

### 🌊 Wave 2 — The negotiation flow can't complete
| ID | Owner |
|---|---|
| CR-07 | Ananya |
| CR-08 | Vikram |
| CR-09 | Ananya |
| CR-05 | Ananya |

### 🌊 Wave 3 — Trust & stability
| ID | Owner |
|---|---|
| CR-04 | Ananya |
| CR-06 | Ananya |
| CR-10 | Ananya |
| CR-11 | Neha (investigation) |

### 🌊 Wave 4 — Correctness & polish
| ID | Owner |
|---|---|
| CR-12, CR-13, CR-14, CR-16, CR-17 | Ananya (CR-13 needs Vikram first) |

**Pipeline for every ticket:** `Owner → Kavya (QA) → Meera (build/run) → Neha (live re-test) → Tara (mark DONE here)`

---

## 5. Ticket Detail

---

### CR-01 · 🔴 Critical · "Share page" button does nothing at all
**Owner:** Meera (HTTPS) → Ananya (client fallback) · **Status:** IN VERIFY

**Wave 1 update (Tara, 2026-07-27):** Fixed by Ananya — clipboard access is now feature-detected with a hidden-textarea `document.execCommand('copy')` fallback, so copying works on plain HTTP today without waiting on the CR-15 HTTPS migration. The share URL is now always rendered in a selectable readonly input regardless of which copy path succeeds. Success/failure toasts added. The empty catch at `:105` is deleted. Kavya QA: **PASS**. Not yet re-tested live (nothing deployed — see §3 note).

> **Known limitation (documented, not a silent gap):** iOS Safari's `readonly` + `.select()` behavior is inconsistent for manual copy-paste. This is NOT fixed by this ticket — it is resolved by CR-15 (HTTPS unlocks `navigator.clipboard` everywhere, including iOS Safari). Neha should not file a new ticket for this; it is tracked here.

**Where:** `src/pages/creator-portfolio-public.tsx:88-106` (`sharePage`)

**Evidence (measured live):**
```
isSecureContext: false
navigator.share:     undefined
navigator.clipboard: undefined
```
Clicked live — label stayed "Share page", nothing copied, no error, no feedback.

**Why:** The app is served over plain `http://` on a bare IP, so both Web Share and Clipboard APIs are unavailable (they require a secure context). `sharePage()` skips the `navigator.share` branch, then calls `navigator.clipboard.writeText(url)` on `undefined` → TypeError → swallowed by the empty catch at `:105`, whose own comment reads *"clipboard blocked — no-op, button stays idle"*.

**Impact:** The creator presses Share and has nothing to paste. This is the reported "public page is not visible if they share anyone". The page itself is fine — `GET /api/v1/portfolio/tejas_creater` returns 200 **with no auth header**, and `/@tejas_creater` serves the SPA 200. Only sharing is broken.

**Fix:**
1. **Meera:** serve over HTTPS on a real domain — this alone restores both APIs.
2. **Ananya:** add a `document.execCommand('copy')` hidden-textarea fallback, always render the URL in a selectable input, and show a success/failure toast. The button must never be able to fail silently.

**Re-test (Neha):** load the public page as an anonymous visitor, click Share, confirm the URL lands on the clipboard and a confirmation appears.

---

### CR-02 · 🔴 Critical · Contracted deal still offers Accept → 409
**Owner:** Vikram (backend) + Ananya (UI gate) · **Status:** IN VERIFY

**Wave 1 update (Tara, 2026-07-27):** Fixed by Vikram — added a new narrow domain method `DealMessage.settleStatus(String)`; the raw `setMetadataJson` setter is removed entirely (no more free-form metadata writes from `DealService`). Accept, reject, and counter all now call `settleStatus(...)` to settle the originating proposal card. Fixed by Ananya on the client — added `dealAllowsProposalResponse()`, which mirrors `Collaboration.canAccept()` exactly, so the action row can no longer render on a deal past `IN_NEGOTIATION` even if a stale `metadata.status` slips through. Kavya QA: **PASS**. `mvn -o compile` exit 0. Not yet re-tested live.

**Where:** `influora-api/src/main/java/com/influora/service/DealService.java:504` and `:713`; `src/pages/creator-chat.tsx:1651`

**Evidence (reproduced live):**
```
POST /api/v1/deals/01KY73H2HCEY0PY942G87W39JW/accept → 409 Conflict
```
Deal "QA E2E — Diwali Skincare Reels" renders **Contracted** and sits on step 2, yet the proposal card reads **Pending** with Accept / Counter / Decline.

**Why:** `persistProposalMessage()` hardcodes `metadata.status = "pending"` (`:713`) and **nothing ever rewrites it** — `doAccept()` only appends a system message (`:504`). The UI gates the action row on `event.metadata?.status === 'pending'` (`creator-chat.tsx:1651`), so the buttons survive forever, including across a hard reload. `Collaboration.canAccept()` (`Collaboration.java:185-190`) correctly refuses anything past `IN_NEGOTIATION`. **The backend is right; the UI is lying about what's possible.**

**Fix:**
1. **Vikram:** in `doAccept()` / `doReject()`, rewrite the originating proposal message's `metadata.status` to `accepted` / `rejected` and persist.
2. **Ananya:** additionally gate the action row on the deal's own state, so a CONTRACTED deal can never render Accept.

---

### CR-03 · 🔴 Critical · The 409 is completely silent
**Owner:** Ananya · **Status:** IN VERIFY

**Wave 1 update (Tara, 2026-07-27):** Fixed by Ananya — `use-toast.ts` rewritten to `useSyncExternalStore`; `TOAST_REMOVE_DELAY` cut from `1000000`ms to `4000`ms; `TOAST_LIMIT` raised from `1` to `2` so a stale toast can no longer occupy the only slot forever. A persistent inline banner was also added directly on the proposal card, with per-error-code copy, so the failure no longer depends on a transient toast surviving. Kavya QA: **PASS**. Not yet re-tested live — the mobile toast stack (structurally impossible before this diff) is unobserved and is Neha's to check after deploy.

**Where:** `src/pages/creator-chat.tsx:1004-1011`; `src/hooks/use-toast.ts:8-9`

**Evidence:** After the 409, queried the live DOM — **zero toast nodes** (`[role=status]`, `[data-radix-toast-root]`, `li[data-state]` all empty) and `document.body.innerText` does not contain the server message. Only trace:
```
[error] Failed to accept proposal ApiError: This deal cannot be accepted in its current state
```

**Impact:** The creator presses Accept, the page does not change in any way, and they are given no indication anything failed.

**Fix:** Verify the Toaster portal actually mounts on this route; drop `TOAST_REMOVE_DELAY` (currently `1000000`ms with `TOAST_LIMIT: 1` — one stale toast can occupy the only slot forever); render a persistent inline state on the proposal card rather than relying on a transient toast.

---

### CR-04 · 🟠 High · Top 106px of the deal room is clipped and unreachable
**Owner:** Ananya · **Status:** OPEN

**Where:** `src/pages/creator-chat.tsx:1520-1521` and `:1256-1258`

**Evidence (measured live):**
```
wrapper div (:1520)  scrollHeight 616  clientHeight 510  overflow-y: hidden  scrollTop: 104.8
```

**Why:** The auto-scroll effect at `:1256` calls `messagesEndRef.scrollIntoView()`. `scrollIntoView` scrolls **every** scrollable ancestor — including `overflow:hidden` ones. That wrapper has no scrollbar and ignores the wheel, so the 106px it scrolled away is **permanently unreachable by the user**. Verified: setting `scrollTop = 0` from script restores the hidden content.

**Contributing:** the ScrollArea root carries `p-4` (`:1521`), making it 539px inside a 510px parent — guaranteeing the overflow. And `baseEventsForDeal` (`:1211`) is unmemoized, so the `events` useMemo and this scroll effect re-fire on **every render**, including every keystroke in the message box.

**Fix (all three):**
1. Move `p-4` off the ScrollArea root onto the inner content div; add `min-h-0`.
2. Replace `scrollIntoView` with a direct `scrollTop` assignment on the Radix viewport so no ancestor is ever scrolled.
3. Wrap `baseEventsForDeal` in `useMemo` keyed on `selectedDeal?.id`.

---

### CR-05 · 🟠 High · Same deal, two different statuses
**Owner:** Ananya · **Status:** OPEN

**Where:** `src/lib/creator-deal-mappers.ts:30-56` vs `:58+`

**Evidence:** "QA E2E — Diwali Skincare Reels" renders **Negotiating** in `/creator/deals` but **Contracted** in the deal room — same session, seconds apart.

**Why:** Two different mappers over the same backend `CollaborationStatus`. `mapCollaborationStatusToDealsPage` folds `TERMS_AGREED` into `negotiating`; the chat mapper treats it as contracted.

**Fix:** Collapse to one shared display-status helper used by both pages.

---

### CR-06 · 🟠 High · Wrong creator identity across the shell
**Owner:** Ananya · **Status:** OPEN

**Where:** `src/components/creator/creator-layout.tsx:229, :234, :242, :325`; **root cause** `src/pages/creator-login.tsx:40-43`

**Evidence (read live from the logged-in sidebar):** initials `IN`, name **"Creator Account"**, dropdown handle **"@priya_sharma"** — while logged in as Tejas.

**Why:** `creator-login.tsx` only calls `login()` when **not** in live mode (`:40-43`). On this live build the auth store is never populated, so `user` stays `null` after every real login and every `user?.*` read falls through to its demo default. The Profile page shows the correct "Tejas Creater" because it fetches independently — hence the visible mismatch. The shipped bundle `index-NdzlUg4U.js` still contains the `@priya_sharma` string.

**Fix:** Populate the auth store from the live login response (call `login()` with the real user in both modes, or hydrate from `GET /me` after `setToken`). **Delete the `@priya_sharma` and `Creator Account` fallbacks** — a missing user must render a neutral skeleton, never someone else's identity.

> ⚠️ **CTO note:** shipping one user's handle as another user's fallback is an identity-leak pattern. Remove the literal, don't just fix the store.

---

### CR-07 · 🟠 High · Brand negotiation room: Accept + Counter are dead buttons
**Owner:** Ananya · **Status:** OPEN

**Where:** `src/pages/brand-chat.tsx:1488-1497`

**Why:** Both buttons render with **no `onClick` at all** — no request, no state change. `brand-chat.tsx` contains zero calls to `api.deals.accept` / `api.deals.reject`; the only working brand accept lives on a different page (`brand-campaign-detail.tsx:651/674`). A brand cannot close a negotiation from the room where the negotiation happens, so every creator counter-offer is a dead end.

**Fix:** Wire both to `api.deals.accept(dealId,'brand')` and the existing counter form, mirroring `creator-chat.tsx:991-1085`; reload the timeline via the existing `loadMessages` (`brand-chat.tsx:771`) and toast the result.

*(Source-confirmed. Not driven live — needs a brand login. **Neha requires brand test credentials to close this ticket.**)*

---

### CR-08 · 🟠 High · Accept/decline/counter never reach the other party
**Owner:** Vikram · **Status:** OPEN

**Where:** `DealService.java:736` and `:740` (vs `:395`)

**Why:** `messageStreamRegistry.publish(...)` is called in exactly **one** place — the send-message path (`:395`). Both `persistProposalMessage()` (`:736`) and `appendSystemMessage()` (`:740`) save the row and stop. So "Creator accepted the proposal", "Brand rejected: …" and **every counter-offer** are invisible to the counterparty's open stream. During a live negotiation the other side sees a frozen room until a full reload.

The stream itself is healthy — `GET /deals/.../messages/stream` returned 200 live.

**Fix:** Publish from both methods using the same best-effort try/catch already at `:395`, so a publish failure never fails the underlying accept/counter.

---

### CR-09 · 🟠 High · Accept/decline never refresh the timeline
**Owner:** Ananya · **Status:** IN PROGRESS — **PARTIAL**

**Wave 1 update (Tara, 2026-07-27):** `loadMessages(dealId)` has been extracted as its own callback, now carrying a monotonic request token that replaces the old `cancelled` closure flag — this closes a real race where a stale response could overwrite a newer one. It is wired to the new Refresh button and confirmed working there. **However, the accept/decline handlers themselves still call only `loadDeals()`** — they do not yet call the new `loadMessages(dealId)`. The timeline still will not refresh automatically on accept/decline. Wiring the handlers to `loadMessages` is carried to Wave 2. Do not advance this ticket past `IN PROGRESS` until that wiring lands and passes Kavya.

**Where:** `src/pages/creator-chat.tsx:991-1037` (vs `:621-640`)

**Why:** Both handlers call `loadDeals()` — which refreshes only the left-hand deal **list** — and never reload messages. The proposal card derives from `liveMessages`, fetched only when `selectedDeal.id` changes. No success toast either. Even on the success path the room looks unchanged. `brand-chat.tsx` does this correctly for counters (`:1001`), so the creator side is the outlier.

**Fix:** Extract a `loadMessages(dealId)` callback mirroring `brand-chat.tsx:771`; await it after accept/decline/counter; add a success toast.

---

### CR-10 · 🟠 High · One render error whites out the ENTIRE app
**Owner:** Ananya · **Status:** OPEN

**Where:** `src/App.tsx:129-130`; `src/components/ErrorBoundary.tsx:20-59`

**Why:** `<ErrorBoundary>` is mounted **outside** `<BrowserRouter>`, so it wraps the whole router. `getDerivedStateFromError` sets `hasError = true` and **nothing resets it** — no `resetKeys`, no route-change reset. One transient throw on one page tears down the entire Router, and every subsequent tab click renders the same dead fallback because the routing tree no longer exists. **This is the mechanism behind "after that, every other tab goes white."**

**Fix:** Move `<ErrorBoundary>` **inside** `<BrowserRouter>` so navigation survives a trip, and reset on route change (key it on `useLocation().pathname`, or add `resetKeys`).

---

### CR-11 · 🟡 Medium · White screen on tab sequence — NOT REPRODUCED
**Owner:** Neha · **Status:** 🚧 **BLOCKED — needs console output or a reproducing account**

**What was tried (all passed, no crash):**
- All 5 filter chips — `?status=all|negotiating|in_progress|completed|new` all **200 OK**, zero console errors
- All 11 sidebar nav items — Home, Deals, Campaigns, Applications, Co-pilot, Analytics, Wallet, Reviews, Disputes, Coupons, Affiliate
- Deal room phase steps + tool panels — Negotiate, Deliver, Pay, Deliverables, Payments

**Unblock condition:** capture the console line at the moment of blanking —
```
[ErrorBoundary] Uncaught render error: …
```
(logged by `ErrorBoundary.tsx:32`) plus the component stack beneath it. That single line names the exact throw site. Alternatively identify which creator account / deal reproduces it.

**Note:** CR-10 explains why it *stays* broken once tripped, and should be fixed regardless of this ticket.

---

### CR-12 · 🟡 Medium · Filter chip counts all collapse to 0
**Owner:** Ananya · **Status:** OPEN

**Where:** `src/pages/creator-deals.tsx:253-259` and `:217-251`

**Evidence:** On **All** the chips read `All 2 / Negotiating 2`; after clicking **Active**, every chip reads `0` — including All.

**Why:** The effect refetches deals scoped to `activeFilter` (`:222`) and replaces the whole `deals` array, while `counts` (`:253-259`) is computed from that same filtered array. The badges describe the current filter's result set, so every other chip reports empty. The creator is told they have no deals at all.

**Fix:** Fetch the unfiltered set once for counts (or have the API return per-status totals); keep badge numbers independent of the active filter.

---

### CR-13 · 🟡 Medium · "Active" tab hides contracted + in-review deals
**Owner:** Vikram (API) + Ananya (client) · **Status:** OPEN

**Where:** `src/pages/creator-deals.tsx:85` vs `DealService.java:863-890`

**Why:** The Active chip's local `match()` accepts `contracted || in_progress || review`, but the id sent to the API is `in_progress`, and `statusesForFilter` maps that to **only** `IN_PROGRESS`. Verified live: with a contracted deal present, the Active tab rendered *"Nothing active."* A signed, contracted deal is invisible on the tab a creator would look at for it.

**Fix:** Either support a multi-status filter (`contracted,in_progress,review`) or align `statusesForFilter`'s `in_progress` case with the chip's intent.

---

### CR-14 · 🟡 Medium · Public page renders "Synced NaNd ago"
**Owner:** Ananya · **Status:** OPEN

**Where:** `src/pages/creator-portfolio-public.tsx` (Platform Stats block)

**Evidence:** Read straight off the live public page — the literal string **"Synced NaNd ago"**. A missing/unparseable last-synced timestamp flows through a day-difference calculation with no guard.

**Impact:** This is the page creators send to brands.

**Fix:** Guard the timestamp before formatting; hide the line or show "Not synced yet" when absent/invalid.

---

### CR-15 · 🟡 Medium · Public URL is a bare IP over HTTP
**Owner:** Meera · **Status:** BLOCKED · *(bundle with CR-01)*

**Wave 1 update (Tara, 2026-07-27):** Blocked — awaiting a domain + TLS purchase decision from Swapnil (CEO); see the escalation already logged in §8. Note the interaction with CR-01: CR-01's `execCommand('copy')` fallback means the Share button itself now works over plain HTTP, but the link it copies is still `http://200.141.1.6/@handle` — still unusable in an Instagram bio and still unreachable from outside the local network. CR-01 being in `IN VERIFY` does not reduce the urgency of this blocker.

**Where:** `src/pages/creator-portfolio-public.tsx:89`; surfaced at `src/pages/creator-profile.tsx:197`

**Why:** Share URL is built from `window.location.origin`, yielding `http://200.141.1.6/@tejas_creater`. The profile page tells creators to *"share it in your Instagram bio"* — Instagram and most messaging apps will not linkify (or will warn on) a bare-IP `http://` URL, and recipients outside this network cannot resolve it at all.

**Fix:** Real domain + TLS; drive the share URL from a configured public base URL, never `window.location.origin`, so a staging IP can never leak into a link a creator hands to a brand.

---

### CR-16 · 🟢 Low · Sidebar "Deals 3" badge is hardcoded
**Owner:** Ananya · **Status:** OPEN

**Where:** `src/components/creator/creator-layout.tsx:129, :203-207`

**Why:** `unreadCount` is `React.useState(3)` with no setter and no data source. Observed live as `Deals|3` while the account had 2 deals and 0 unread.

**Fix:** Drive from the real unread total (the deals API already returns `unreadCount` per deal) or remove until wired.

---

### CR-17 · 🟢 Low · Deal room height overflows layout by 8px
**Owner:** Ananya · **Status:** OPEN

**Where:** `src/pages/creator-chat.tsx:1335` vs `src/components/creator/creator-layout.tsx:274`

**Why:** Deal room root is `h-[calc(100vh-4rem)]` but the layout header above it is `h-14` (3.5rem). Measured live: `main` 664px in a 720px viewport, deal room 656px.

**Fix:** Use `h-[calc(100vh-3.5rem)]`, or share one header-height token.

---

### CR-18 · 🟡 Medium · `usageRights` missing from proposal metadata
**Owner:** Priya (implemented) · **Status:** IN VERIFY

**Why:** The proposal card always rendered "Usage Rights: Not specified." `persistProposalMessage` never wrote a `usageRights` key into the message metadata; the value only ever lived on the `Collaboration` entity, never snapshotted onto the offer itself.

**Fix:** `persistProposalMessage` now snapshots `collaboration.getUsageRights()` into the metadata at the moment each offer is persisted.

> ⚠️ **Deliberately NOT backfilled.** Cards created before 2026-07-27 will read "Not specified" **permanently** — this is the documented trade, not a regression. **Neha must not file a new ticket against the deployed build for old cards still reading "Not specified."** Only newly-created proposal cards after this fix ships are in scope for re-test.

**Re-test (Neha):** create a **new** proposal after deploy and confirm the usage-rights value set on the collaboration appears on the card. Do not use a pre-existing deal for this re-test.

---

### CR-19 · 🟡 Medium · N1: `settleStatus` BigDecimal→Double round-trip
**Owner:** Vikram · **Status:** IN PROGRESS

**Where:** `DealService.java:71`; `DealMessage.java:29`

**Why:** `DealMessage`'s bare `ObjectMapper` does not have `USE_BIG_DECIMAL_FOR_FLOATS` enabled, so any settle operation round-trips a value like `25000.00` down to `25000.0`. Not reachable via the SPA today — JS numbers arrive at scale-0 — but this metadata is the payment evidence trail, and the method's own javadoc claims only `status` is rewritten by `settleStatus`, which is no longer strictly true once a settle touches the whole JSON blob.

**Also flagged:** `DealService.java:71` and `DealMessage.java:29` each hold their own independent bare `ObjectMapper` instance. Two independent instances of the same serialization concern can drift in configuration over time.

**Fix (in progress):** Configure `USE_BIG_DECIMAL_FOR_FLOATS` on the mapper used by `settleStatus`, and consolidate to a single shared, correctly-configured `ObjectMapper` rather than two independent instances.

---

### CR-20 · 🟢 Low · N2: `loadMessages` lost unmount cancellation
**Owner:** Ananya · **Status:** OPEN

**Where:** `src/pages/creator-chat.tsx` (`loadMessages` extraction, see CR-09)

**Why:** The previous inline fetch used a `cancelled` closure flag set on unmount. The extracted `loadMessages(dealId)` replaces it with a monotonic request token that correctly ignores stale *responses*, but does not currently abort or ignore work still in flight after the component unmounts. Not a bug under React 18+ (no state-update-after-unmount warning/leak observed), but it is a capability the replaced code had and this one doesn't.

**Fix:** Add an unmount guard (abort controller or an `isMounted` ref) alongside the existing request token.

---

### CR-21 · 🟢 Low · N3: "Refresh deal" flashes the whole page
**Owner:** Ananya · **Status:** OPEN

**Where:** `src/pages/creator-chat.tsx` (`loadDeals()`, `dealsLoading`)

**Why:** `loadDeals()` sets `dealsLoading`, which early-returns a full-page spinner. This is pre-existing behavior, but it is newly reachable now that the CR-09 Refresh button calls it more often as a minor affordance — clicking Refresh currently blanks the whole page rather than updating just the deal list/timeline in place.

**Fix:** Scope the loading indicator to the refreshed region instead of gating the entire page render on `dealsLoading`.

---

### CR-22 · 🟡 Medium · Brand-side `canReject` withdrawal flow needs its own UI
**Owner:** Unassigned · **Status:** OPEN

**Why:** Per CTO ruling, deal-level withdrawal deliberately does **not** belong on the proposal card — it needs its own, separate flow. Cross-reference the ruling documented at `creator-chat.tsx:1856-1871` (the H2 decision).

**Fix:** Design and implement a dedicated withdrawal affordance for the brand side, distinct from the proposal-card accept/reject/counter actions. Needs an owner assigned before it can move to `ASSIGNED`.

---

## 6. Tara's Update Protocol

**Tara owns every status change in this file. Nobody else edits §3 or the `Status:` lines in §5.**

### Trigger
Run whenever any of these happens:
- An owner accepts a ticket → `OPEN` → `ASSIGNED`
- Work starts → `IN PROGRESS`
- Code lands and goes to Kavya → `IN QA`
- Kavya passes → `IN VERIFY` (Neha)
- **Neha confirms fixed on the live URL** → `DONE`
- Anything stalls → `BLOCKED` + name the blocker inline

### Steps (every single time)
1. Update the `Status` cell in the **§3 Summary Board**.
2. Update the `**Status:**` line in that ticket's **§5 detail block**.
3. Recalculate the **Totals** line under §3.
4. Bump `**Last updated:**` in the header — date + your name + what changed.
5. Append one line to §7 Changelog.
6. If a ticket moved to `DONE`, append the verification evidence (what Neha checked, and the result) to that ticket.

### Hard rules
- ❌ Never mark `DONE` on a merge. **Only Neha's live re-test closes a ticket.**
- ❌ Never delete a ticket. Superseded/invalid → set `BLOCKED` and write why.
- ✅ If a fix creates a new defect, open a new `CR-xx` row rather than reusing the old one.
- ✅ Keep the changelog append-only.

---

## 7. Changelog

| Date | By | Change |
|---|---|---|
| 2026-07-27 | Priya (CTO) | File created. 17 creator defects logged from Neha's live E2E walkthrough. Wave routing set with Arjun's model. Tara assigned as maintainer. |
| 2026-07-27 | Tara | Wave 1 results recorded. CR-01, CR-02, CR-03: `OPEN` → `IN VERIFY` (Kavya QA PASS, code+build complete, per Kavya's routing). CR-09: `OPEN` → `IN PROGRESS` marked **PARTIAL** (timeline refresh wired to the new Refresh button only; accept/decline handlers still call only `loadDeals()`, remainder is Wave 2). CR-15: `OPEN` → `BLOCKED` (awaiting domain + TLS purchase decision from Swapnil). CR-11 unchanged, still `BLOCKED`. Added CR-18 (`usageRights` missing from proposal metadata, IN VERIFY, owner Priya), CR-19 (N1 — `settleStatus` BigDecimal→Double round-trip, IN PROGRESS, owner Vikram), CR-20 (N2 — `loadMessages` lost unmount cancellation, OPEN, owner Ananya), CR-21 (N3 — "Refresh deal" flashes whole page, OPEN, owner Ananya), CR-22 (brand-side `canReject` withdrawal flow, OPEN, unassigned). Totals recalculated: 3 Critical · 7 High · 8 Medium · 4 Low = 22 logged, **0 DONE**. Verification evidence recorded against Wave 1: `npm run typecheck` clean; `npm test` 227/227; `npm run lint` 403 problems (unchanged baseline, no new debt); `mvn -o compile` exit 0; Meera — `npm run build` PASS incl. `postbuild` prerender, 16/16 routes snapshotted, `mvn -o package -DskipTests` BUILD SUCCESS (`influora-api-0.1.0-SNAPSHOT.jar`, 83.8 MB); new bundle `index-Bu4yUEbB.js` at 2,691.33 kB vs deployed `index-NdzlUg4U.js` ~2.68 MB (~+10 KB, no material change); Kavya QA verdict PASS, cleared for Meera. **Nothing has been deployed** — `http://200.141.1.6` still serves the old bundle `index-NdzlUg4U.js`. No ticket is marked `DONE`; nothing has been verified in a live browser by Neha. |

---

## 8. CTO Notes & Escalations

**Escalating to Swapnil (CEO) — one item:**
> **HTTPS migration (CR-01 + CR-15) is a business blocker, not a tech-debt item.** While the product runs on `http://` at a bare IP, no creator can share their public page and no shared link works in an Instagram bio. That removes the entire organic acquisition loop. This needs a domain + certificate decision from you before Wave 1 can complete.

**Architectural themes behind these 17 tickets** — worth fixing as patterns, not just instances:
1. **Server state is written but never broadcast** (CR-08) and **client state is fetched but never refetched** (CR-09, CR-02). The deal room has no single "reload this deal's world" path. One `refreshDeal(dealId)` used by every mutation would collapse three tickets into one.
2. **Duplicated mapping logic** (CR-05, CR-13) — the same backend enum is interpreted differently in three places. One shared mapper module, consumed everywhere.
3. **Demo fallbacks shipped to production** (CR-06, CR-16). Placeholder values must never be the `||` fallback of real data. Prefer an explicit loading/empty state.
4. **Silent catches** (CR-01, CR-03). Two separate user-facing failures were caused by an empty `catch`. New standard: **no empty catch blocks in `src/`** — log *and* surface, or don't catch.

**Wave 1 addendum (Tara, 2026-07-27):** As part of the CR-02 fix, `DealMessage` now imports Jackson and holds a static `ObjectMapper` so `settleStatus(String)` can narrow-write just the status field instead of exposing a raw `setMetadataJson` setter. This was a deliberate, flagged trade: the evidence-trail protection (no more free-form metadata writes reaching a payment-adjacent record) was judged to outweigh keeping serialization logic out of the domain layer. Note this as **a precedent to watch, not a general licence** — it should not be read as blanket permission for other entities to start carrying serialization logic. See also CR-19 (N1), which flags that this same class now has a bare `ObjectMapper` independent of `DealService.java:71`'s own instance, and the two can drift.

— *Priya Sharma, CTO*
