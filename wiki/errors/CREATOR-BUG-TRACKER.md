# 🐞 CREATOR SURFACE — MASTER BUG TRACKER

> **Owner (document):** Priya Sharma — CTO
> **Routing authority:** Arjun (Eng Lead / COO)
> **Maintainer (status updates):** **Tara** — see [§6 Tara's Update Protocol](#6-taras-update-protocol)
> **Source of findings:** Neha (E2E), live logged-in walkthrough of `http://200.141.1.6` as `tejas.chache5@gmail.com`
> **Opened:** 2026-07-27
> **Last updated:** 2026-07-28 — **Wave 3/4 pass.** Eight `OPEN` Ananya tickets are code-complete → `IN QA` (CR-04, CR-06, CR-10, CR-12, CR-14, CR-16, CR-17, CR-20; commit `5b86a49`, pushed). **Four stale facts corrected:** (1) §9's deploy blocker is **RESOLVED** — `publish-images.yml` already carries `feat/creator-my-applications`, added in `04b7a53`; (2) the live box serves **Wave 2**, not the pre-Wave-1 bundle — Waves 1–2 ARE deployed and their 11 `IN VERIFY` tickets are testable now; (3) Wave 2 + CR-23 are committed and pushed, not uncommitted working-tree changes; (4) "0 DONE because nothing is deployed" no longer holds — the gap is QA/test time, not infrastructure. Every 🔴 Critical and 🟠 High ticket now has code. Totals unchanged at 29 logged, **0 DONE**.
>
> ⚠️ **Protocol exception, recorded:** this pass was written at the repo owner's explicit direction, **not by Tara**. §6 reserves §3 and the §5 `Status:` lines for Tara; that rule was knowingly overridden for this entry. Tara should re-review rather than assume this followed the normal route.

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
| CR-04 | 🟠 High | Top 106px of deal room clipped and unreachable — can't scroll | Ananya | IN QA |
| CR-05 | 🟠 High | Same deal shows two different statuses on two pages | Ananya | IN VERIFY |
| CR-06 | 🟠 High | Wrong identity in shell — "Creator Account" / "IN" / "@priya_sharma" | Ananya | IN QA |
| CR-07 | 🟠 High | Brand negotiation room: Accept + Counter are dead buttons | Ananya | IN VERIFY |
| CR-08 | 🟠 High | Accept/decline/counter never reach the other party (no SSE publish) | Vikram | IN VERIFY |
| CR-09 | 🟠 High | Creator accept/decline never refresh the message timeline | Ananya | IN VERIFY |
| CR-10 | 🟠 High | One render error whites out the ENTIRE app permanently | Ananya | IN QA |
| CR-11 | 🟡 Medium | White screen on tab sequence — **NOT REPRODUCED**, needs data | Neha | BLOCKED |
| CR-12 | 🟡 Medium | All filter chip counts collapse to 0 when a filter is active | Ananya | IN QA |
| CR-13 | 🟡 Medium | "Active" tab hides contracted + in-review deals | Vikram + Ananya | OPEN |
| CR-14 | 🟡 Medium | Public page renders "Synced NaNd ago" | Ananya | IN QA |
| CR-15 | 🟡 Medium | Public URL is a bare IP over HTTP — unusable as a shared link | Meera | BLOCKED |
| CR-16 | 🟢 Low | Sidebar "Deals 3" badge is hardcoded | Ananya | IN QA |
| CR-17 | 🟢 Low | Deal room height overflows layout by 8px | Ananya | IN QA |
| CR-18 | 🟡 Medium | `usageRights` missing from proposal metadata — always "Not specified" | Priya (implemented) | IN VERIFY |
| CR-19 | 🟡 Medium | N1: `settleStatus` BigDecimal→Double round-trip; two bare `ObjectMapper`s | Vikram | IN VERIFY |
| CR-20 | 🟢 Low | N2: `loadMessages` lost unmount cancellation (no leak today, React 18+) | Ananya | IN QA |
| CR-21 | 🟢 Low | N3: "Refresh deal" flashes the whole page (full-page spinner) | Ananya | IN VERIFY |
| CR-22 | 🟡 Medium | Brand-side `canReject` withdrawal flow needs its own UI (not the proposal card) | Unassigned | OPEN |
| CR-23 | 🟢 Low | Brand `refreshDeal` catch block missing the staleness guard (cf. creator-side W2-L1) | Priya | IN VERIFY |
| CR-24 | 🟡 Medium | Brand-side status mapper unification — three surfaces diverge on `CollaborationStatus` | Unassigned | OPEN |
| CR-25 | 🟡 Medium | SSE publishes fire inside the caller's `@Transactional` — pre-rollback frames observable | Vikram | OPEN |
| CR-26 | 🟡 Medium | `DISPUTED`/`CANCELLED` render as "Done"/"Completed" — no display bucket exists | Unassigned | OPEN |
| CR-27 | 🟢 Low | `creator-deals.tsx` under-offers actions vs the server (`canAccept()` allows more) | Unassigned | OPEN |
| CR-28 | 🟢 Low | Backend test helper hides the settle path (`proposalMessage` carries null metadata) | Vikram | OPEN |
| CR-29 | 🟢 Low | CR-23's fix has no test coverage (superseded-failed-refresh scenario untested) | Unassigned | OPEN |

**Totals:** 3 Critical · 7 High · 11 Medium · 8 Low = **29 logged**, **0 DONE**

**By status:** 8 `IN QA` · 11 `IN VERIFY` · 8 `OPEN` · 2 `BLOCKED`

**Progress against the 29:**

| Severity | Logged | Code written | Not started | Blocked |
|---|---|---|---|---|
| 🔴 Critical | 3 | **3** | 0 | 0 |
| 🟠 High | 7 | **7** | 0 | 0 |
| 🟡 Medium | 11 | 4 | 5 | 2 |
| 🟢 Low | 8 | 5 | 3 | 0 |
| **Total** | **29** | **19 (66%)** | **8** | **2** |

**Every 🔴 Critical and 🟠 High ticket now has code.** Everything still unwritten is Medium or Low.

> **0 DONE is still correct, and the reason has changed.** Per §2 only Neha's live re-test closes a ticket, and none has happened. But this is no longer an infrastructure problem — Waves 1–2 **are** deployed (see §9) and their 11 `IN VERIFY` tickets are testable today. What now stands between 0 DONE and ~10 DONE is Neha's time, not a blocked pipeline.

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

### 🌊 Wave 3 — Trust & stability · *code-complete 2026-07-28*
| ID | Owner | State |
|---|---|---|
| CR-04 | Ananya | ✅ `IN QA` |
| CR-06 | Ananya | ✅ `IN QA` |
| CR-10 | Ananya | ✅ `IN QA` |
| CR-11 | Neha (investigation) | 🚧 still `BLOCKED` — needs the console line or a reproducing account. CR-10's fix stops one throw being *permanent*; it does **not** identify the throw site. |

### 🌊 Wave 4 — Correctness & polish · *Ananya's share code-complete 2026-07-28*
| ID | Owner | State |
|---|---|---|
| CR-12, CR-14, CR-16, CR-17 | Ananya | ✅ `IN QA` |
| CR-13 | Vikram → Ananya | ⬜ still `OPEN` — **blocked on Vikram**; Priya ruled the backend filter path must move, so it was deliberately not worked around client-side in CR-12 |
| CR-20 | Ananya | ✅ `IN QA` (logged after the original wave plan) |

### 🌊 Wave 5 — Unstarted remainder *(added 2026-07-28)*
Everything still `OPEN`. **No 🔴 Critical or 🟠 High tickets remain unwritten** — this is all Medium and Low.

| ID | Owner | Blocker |
|---|---|---|
| CR-13 | Vikram + Ananya | Needs Vikram's `statusesForFilter` fix first |
| CR-25, CR-28 | Vikram | None — ready to start |
| CR-22, CR-24, CR-26, CR-29 | **Unassigned** | **Need an owner before they can move to `ASSIGNED`** |
| CR-27 | **Unassigned** | Needs a **product call** on whether the list should expose negotiation-stage actions — not an automatic fix |

**Pipeline for every ticket:** `Owner → Kavya (QA) → Meera (build/run) → Neha (live re-test) → Tara (mark DONE here)`

> **Where the 8 `IN QA` tickets actually sit in that pipeline:** code done, and `typecheck`/`test`/`lint`/`build` all run and green (Meera's checks, self-run). **Kavya has not reviewed them**, and the images are published but **not pulled onto the box**, so Neha cannot re-test them yet. Next step is Kavya, then a VPS restart, then Neha.

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
**Owner:** Ananya · **Status:** IN QA

**Wave 3 update (2026-07-28):** Fixed — **all three** contributing causes, not just the scroll call:
> 1. `p-4` moved off the `ScrollArea` **root** onto the inner content div, plus `min-h-0`. Padding on the Radix Root inflates the scroll container itself (539px inside a 510px parent); padding belongs to the scrolled *content*, not the viewport.
> 2. `scrollIntoView` replaced with a `scrollTo({top: scrollHeight})` on the Radix **viewport**, reached via a new optional `viewportRef` prop on `components/ui/scroll-area.tsx`. `scrollIntoView` scrolls every scrollable ancestor including `overflow:hidden` ones — that is what put 106px permanently beyond the user's reach. Assigning scroll position on the viewport cannot move an ancestor. The old `messagesEndRef` anchor div is deleted.
> 3. `baseEventsForDeal` wrapped in `useMemo` keyed on the **resolved** deal id (`selectedDeal?.id`, not the `selectedDealId` state — that one holds whatever the URL asked for, which may not have loaded).
>
> `viewportRef` is additive and optional, so existing `ScrollArea` call sites (including brand pages) are untouched.
> ⚠️ **Not verified in a browser** — this is a layout fix and nobody has seen it render. Kavya/Neha must confirm the top of the room is reachable and the thread still pins to the bottom on new messages.

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
**Owner:** Ananya · **Status:** IN VERIFY

**Wave 2 update (Tara, 2026-07-27):** Fixed by Ananya — root cause was a private `mapDealStatusToRoomStatus` in `creator-chat.tsx` disagreeing with the shared mapper on 4 statuses. `TERMS_AGREED` is the reported one: list said "Negotiating", room said "Contracted" — the exact state `doAccept` produces, so the pages diverged the instant a creator accepted. Fixed at source: one `mapCollaborationStatusToDealStage` in `src/lib/creator-deal-mappers.ts`, both pages import it, private mappers deleted. Kavya QA: **PASS**. Not yet re-tested live.

**Where:** `src/lib/creator-deal-mappers.ts:30-56` vs `:58+`

**Evidence:** "QA E2E — Diwali Skincare Reels" renders **Negotiating** in `/creator/deals` but **Contracted** in the deal room — same session, seconds apart.

**Why:** Two different mappers over the same backend `CollaborationStatus`. `mapCollaborationStatusToDealsPage` folds `TERMS_AGREED` into `negotiating`; the chat mapper treats it as contracted.

**Fix:** Collapse to one shared display-status helper used by both pages.

---

### CR-06 · 🟠 High · Wrong creator identity across the shell
**Owner:** Ananya · **Status:** IN QA

**Wave 3 update (2026-07-28):** Fixed at the root cause and along the whole chain. The CTO note was followed literally — **the literals are deleted, not repointed**:
> - `lib/auth-session.ts` gains `persistCreatorSession` / `getCreatorSession` / `clearCreatorSession`. The creator flow had **no equivalent of `persistBrandSession`** — it kept the token and discarded the rest of the `TokenPair`, which is why there was no identity to show.
> - `api.auth.creatorLogin` / `creatorRegister` now persist the session and return `email` + `displayName`; **both pages call `login()` in both modes**, closing the `if (!isApiLive())` gap at `creator-login.tsx:40-43`.
> - New `hooks/use-creator-identity.ts` hydrates from the session, then from `GET /me/creator-profile`. Needed because the auth store is `partialize: () => ({})` (`lib/store.ts`) and therefore empties on every hard reload — fixing login alone would have left the bug on refresh.
> - `'@priya_sharma'` and `'Creator Account'` are **gone**; `getInitials` lost its `'IN'` fallback and returns `null`. Unknown identity renders a neutral skeleton.
> - `handleLogout` now clears the new keys, so the next person to open the browser cannot see the previous creator's name.
>
> **Verified:** neither `priya_sharma` nor `Creator Account` appears anywhere in the production bundle (`grep -c` → 0 for both). This directly answers the note below that the shipped bundle still contained the string.
> ⚠️ **Not verified in a browser** — the skeleton states and the real-name render are unobserved.

**Where:** `src/components/creator/creator-layout.tsx:229, :234, :242, :325`; **root cause** `src/pages/creator-login.tsx:40-43`

**Evidence (read live from the logged-in sidebar):** initials `IN`, name **"Creator Account"**, dropdown handle **"@priya_sharma"** — while logged in as Tejas.

**Why:** `creator-login.tsx` only calls `login()` when **not** in live mode (`:40-43`). On this live build the auth store is never populated, so `user` stays `null` after every real login and every `user?.*` read falls through to its demo default. The Profile page shows the correct "Tejas Creater" because it fetches independently — hence the visible mismatch. The shipped bundle `index-NdzlUg4U.js` still contains the `@priya_sharma` string.

**Fix:** Populate the auth store from the live login response (call `login()` with the real user in both modes, or hydrate from `GET /me` after `setToken`). **Delete the `@priya_sharma` and `Creator Account` fallbacks** — a missing user must render a neutral skeleton, never someone else's identity.

> ⚠️ **CTO note:** shipping one user's handle as another user's fallback is an identity-leak pattern. Remove the literal, don't just fix the store.

---

### CR-07 · 🟠 High · Brand negotiation room: Accept + Counter are dead buttons
**Owner:** Ananya · **Status:** IN VERIFY

**Wave 2 update (Tara, 2026-07-27):** Fixed by Ananya — root cause: **no `onClick` on either button at all**. Compounding: live mode flattened `kind: 'proposal'` messages into anonymous text bubbles, so the controls were unreachable in the only mode that moves money. Both fixed; one `renderProposalCard` now serves demo and live. Gate mirrors `doAccept`'s `CANNOT_ACCEPT_OWN_OFFER` exactly. Kavya QA: **PASS**. Not yet re-tested live — **Neha still needs brand test credentials to re-test this; that blocker is unchanged.**

**Where:** `src/pages/brand-chat.tsx:1488-1497`

**Why:** Both buttons render with **no `onClick` at all** — no request, no state change. `brand-chat.tsx` contains zero calls to `api.deals.accept` / `api.deals.reject`; the only working brand accept lives on a different page (`brand-campaign-detail.tsx:651/674`). A brand cannot close a negotiation from the room where the negotiation happens, so every creator counter-offer is a dead end.

**Fix:** Wire both to `api.deals.accept(dealId,'brand')` and the existing counter form, mirroring `creator-chat.tsx:991-1085`; reload the timeline via the existing `loadMessages` (`brand-chat.tsx:771`) and toast the result.

*(Source-confirmed. Not driven live — needs a brand login. **Neha requires brand test credentials to close this ticket.**)*

---

### CR-08 · 🟠 High · Accept/decline/counter never reach the other party
**Owner:** Vikram · **Status:** IN VERIFY

**Wave 2 update (Tara, 2026-07-27):** Fixed by Vikram — `DealService` published to SSE in exactly one place (`sendMessage`). Now accept/reject/counter each publish two frames: the settled/superseded card first (original ULID, post-settle metadata), then the system message or new card. Kavya QA: **PASS**. Not yet re-tested live.

**Where:** `DealService.java:736` and `:740` (vs `:395`)

**Why:** `messageStreamRegistry.publish(...)` is called in exactly **one** place — the send-message path (`:395`). Both `persistProposalMessage()` (`:736`) and `appendSystemMessage()` (`:740`) save the row and stop. So "Creator accepted the proposal", "Brand rejected: …" and **every counter-offer** are invisible to the counterparty's open stream. During a live negotiation the other side sees a frozen room until a full reload.

The stream itself is healthy — `GET /deals/.../messages/stream` returned 200 live.

**Fix:** Publish from both methods using the same best-effort try/catch already at `:395`, so a publish failure never fails the underlying accept/counter.

---

### CR-09 · 🟠 High · Accept/decline never refresh the timeline
**Owner:** Ananya · **Status:** IN VERIFY

**Wave 2 update (Tara, 2026-07-27):** Now **COMPLETE** — no longer partial. `afterDealMutation` = `Promise.all([refreshDeal, loadMessages])` runs on accept, decline, **and** counter. This closes out the Wave 1 partial-fix carryover noted below. Kavya QA: **PASS**. Not yet re-tested live.

**Wave 1 update (Tara, 2026-07-27):** `loadMessages(dealId)` has been extracted as its own callback, now carrying a monotonic request token that replaces the old `cancelled` closure flag — this closes a real race where a stale response could overwrite a newer one. It is wired to the new Refresh button and confirmed working there. **However, the accept/decline handlers themselves still call only `loadDeals()`** — they do not yet call the new `loadMessages(dealId)`. The timeline still will not refresh automatically on accept/decline. Wiring the handlers to `loadMessages` is carried to Wave 2. Do not advance this ticket past `IN PROGRESS` until that wiring lands and passes Kavya.

**Where:** `src/pages/creator-chat.tsx:991-1037` (vs `:621-640`)

**Why:** Both handlers call `loadDeals()` — which refreshes only the left-hand deal **list** — and never reload messages. The proposal card derives from `liveMessages`, fetched only when `selectedDeal.id` changes. No success toast either. Even on the success path the room looks unchanged. `brand-chat.tsx` does this correctly for counters (`:1001`), so the creator side is the outlier.

**Fix:** Extract a `loadMessages(dealId)` callback mirroring `brand-chat.tsx:771`; await it after accept/decline/counter; add a success toast.

---

### CR-10 · 🟠 High · One render error whites out the ENTIRE app
**Owner:** Ananya · **Status:** IN QA

**Wave 3 update (2026-07-28):** Fixed — `<ErrorBoundary>` moved **inside** `<BrowserRouter>` via a `RoutedErrorBoundary` wrapper that reads `useLocation().pathname` and passes it as a new `resetKey` prop; `componentDidUpdate` clears `hasError` when that key changes. `<Toaster>` and `<DemoModeBanner>` moved inside the Router with it so they keep rendering alongside the routes.
> **`resetKey` rather than React's `key`, deliberately:** keying the boundary would remount the entire subtree on *every* navigation, discarding all component state on healthy routes to fix a case that almost never fires. The reset is also loop-safe — the guard requires `hasError`, which the `setState` immediately falsifies.
> The fallback now offers **Try again** (local reset, re-renders the same route) alongside **Reload page**, and its copy no longer implies the whole app is dead. A deterministic throw simply returns the fallback, which is honest — every *other* route stays reachable, which is the actual fix.
> ⚠️ **Not verified in a browser**, and note this does not identify CR-11's throw site — it only stops one throw being permanent.

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
**Owner:** Ananya · **Status:** IN QA

**Wave 3 update (2026-07-28):** Fixed — the chip badges **and** the header summary (`newCount` / `activeCount` / `pendingPayout`, which had the identical defect) now read a separate unfiltered `api.deals.list('creator','all')`. Accept/decline update both arrays, so badges move with the row instead of going stale until remount. The counts fetch swallows its own errors on purpose: the list fetch already toasts, and a second toast for the badges would be noise on one outage — the badges hold their last value rather than lying with 0.
> **Scope decision worth flagging to Kavya:** the list's own fetch stays **server-filtered**. Fetching once and filtering entirely client-side would have been simpler and would also have hidden CR-13's symptom on this page — but Priya ruled the filter path (`DealService.statusesForFilter`) is the side that must move, so masking it client-side was deliberately avoided. **Expect a visible consequence:** the Active chip may now read a non-zero count while the Active tab still renders "Nothing active." That *is* CR-13, now more visible rather than newly broken.

**Where:** `src/pages/creator-deals.tsx:253-259` and `:217-251`

**Evidence:** On **All** the chips read `All 2 / Negotiating 2`; after clicking **Active**, every chip reads `0` — including All.

**Why:** The effect refetches deals scoped to `activeFilter` (`:222`) and replaces the whole `deals` array, while `counts` (`:253-259`) is computed from that same filtered array. The badges describe the current filter's result set, so every other chip reports empty. The creator is told they have no deals at all.

**Fix:** Fetch the unfiltered set once for counts (or have the API return per-status totals); keep badge numbers independent of the active filter.

---

### CR-13 · 🟡 Medium · "Active" tab hides contracted + in-review deals
**Owner:** Vikram (API) + Ananya (client) · **Status:** OPEN

**Where:** `src/pages/creator-deals.tsx:85` vs `DealService.java:863-890`

**Why:** The Active chip's local `match()` accepts `contracted || in_progress || review`, but the id sent to the API is `in_progress`, and `statusesForFilter` maps that to **only** `IN_PROGRESS`. Verified live: with a contracted deal present, the Active tab rendered *"Nothing active."* A signed, contracted deal is invisible on the tab a creator would look at for it.

**Wave 2 note (Tara, 2026-07-27):** `DealService.statusesForFilter:1030-1058` puts `TERMS_AGREED` in `"contracted"` while three display mappers put it pre-contract, and `AdminBrandService:94-108`'s javadoc ends the pre-agreement set at `IN_NEGOTIATION`. User-visible consequence, verified: a `TERMS_AGREED` deal is badged "Negotiating", is NOT returned by the "Negotiating" chip, and — since `creator-deals.tsx` has **no Contracted chip at all** — is reachable only under "All". Priya ruled the filter path is the side that must move.

**Fix:** Either support a multi-status filter (`contracted,in_progress,review`) or align `statusesForFilter`'s `in_progress` case with the chip's intent.

---

### CR-14 · 🟡 Medium · Public page renders "Synced NaNd ago"
**Owner:** Ananya · **Status:** IN QA

**Wave 3 update (2026-07-28):** Fixed — `relativeTime` returns `null` for a missing or unparseable timestamp (`Number.isFinite` guard) and the caller drops the whole "Synced …" line rather than printing arithmetic wreckage. A *future* timestamp (clock skew between the sync job and the viewer) is clamped to 'just now' instead of rendering "-1h ago".
> **Also corrected the lying type:** `PortfolioPlatformStats.lastSyncedAt` was declared `string` (non-nullable) while the live `GET /portfolio/:username` omits it for a platform that never completed a sync — which is precisely how the `NaN` arrived. Widened to `?: string | null`, so the guard is real code rather than something TypeScript considers unreachable.

**Where:** `src/pages/creator-portfolio-public.tsx` (Platform Stats block)

**Evidence:** Read straight off the live public page — the literal string **"Synced NaNd ago"**. A missing/unparseable last-synced timestamp flows through a day-difference calculation with no guard.

**Impact:** This is the page creators send to brands.

**Fix:** Guard the timestamp before formatting; hide the line or show "Not synced yet" when absent/invalid.

---

### CR-15 · 🟡 Medium · Public URL is a bare IP over HTTP
**Owner:** Meera · **Status:** BLOCKED · *(bundle with CR-01)*

**Wave 1 update (Tara, 2026-07-27):** Blocked — awaiting a domain + TLS purchase decision from Swapnil (CEO); see the escalation already logged in §8. Note the interaction with CR-01: CR-01's `execCommand('copy')` fallback means the Share button itself now works over plain HTTP, but the link it copies is still `http://200.141.1.6/@handle` — still unusable in an Instagram bio and still unreachable from outside the local network. CR-01 being in `IN VERIFY` does not reduce the urgency of this blocker.

> **Distinct from the §9 Deploy Blocker — read both, don't conflate them.** This ticket is blocked on a **domain + TLS decision** (there is no HTTPS to serve on, regardless of what's deployed). §9 was blocked on a **CI/CD workflow-branch decision**. Fixing one does **not** fix the other.
>
> ⚠️ **Update 2026-07-28 — this prediction was borne out. §9 is now RESOLVED; CR-15 is NOT.** Images publish and Waves 1–2 are deployed, and the share URL is still `http://200.141.1.6/@tejas_creater`: still unlinkable in an Instagram bio, still unresolvable outside this network. **CR-15 is now the only remaining Swapnil-gated infrastructure blocker in this file**, and it is the one holding the organic acquisition loop shut (see §8).

**Where:** `src/pages/creator-portfolio-public.tsx:89`; surfaced at `src/pages/creator-profile.tsx:197`

**Why:** Share URL is built from `window.location.origin`, yielding `http://200.141.1.6/@tejas_creater`. The profile page tells creators to *"share it in your Instagram bio"* — Instagram and most messaging apps will not linkify (or will warn on) a bare-IP `http://` URL, and recipients outside this network cannot resolve it at all.

**Fix:** Real domain + TLS; drive the share URL from a configured public base URL, never `window.location.origin`, so a staging IP can never leak into a link a creator hands to a brand.

---

### CR-16 · 🟢 Low · Sidebar "Deals 3" badge is hardcoded
**Owner:** Ananya · **Status:** IN QA

**Wave 3 update (2026-07-28):** Fixed — new `hooks/use-creator-unread-count.ts` sums the `unreadCount` the deals API already returns per deal, so no new backend was needed. Keyed on `location.pathname` so the badge settles after a deal room marks its messages read. Drives **both** the sidebar "Deals" badge and the header bell, which shared the same literal. Fails silently by design: this is chrome, and the pages themselves already surface deal-loading failures.

**Where:** `src/components/creator/creator-layout.tsx:129, :203-207`

**Why:** `unreadCount` is `React.useState(3)` with no setter and no data source. Observed live as `Deals|3` while the account had 2 deals and 0 unread.

**Fix:** Drive from the real unread total (the deals API already returns `unreadCount` per deal) or remove until wired.

---

### CR-17 · 🟢 Low · Deal room height overflows layout by 8px
**Owner:** Ananya · **Status:** IN QA

**Wave 3 update (2026-07-28):** Fixed with the shared token, not the local number — a new `--app-header-h: 3.5rem` in `src/app/globals.css` is consumed by **both** the layout header (`h-[var(--app-header-h)]`, visually identical to the old `h-14`) and the four deal-room `h-[calc(100vh-var(--app-header-h))]` roots. The two can no longer drift. Verified present in the built CSS.
> **Deliberately left alone:** the fifth `h-[calc(100vh-4rem)]` in `creator-chat.tsx` (the ToolsSheet body) measures against the **sheet's own** header, not the app header, so it is a different context and out of this ticket's scope. It is under-sized rather than over-sized, so it cannot overflow — but it is unexamined and someone should confirm that deliberately rather than discover it.

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
**Owner:** Vikram · **Status:** IN VERIFY

**Wave 2 correction (Tara, 2026-07-27):** Stale row fixed — this is complete; Vikram finished it after last pass. He enabled `USE_BIG_DECIMAL_FOR_FLOATS` on `DealMessage.MAPPER` only, and deliberately did **not** touch `DealService.MAPPER` because its read path feeds the response DTO and the flag would change API response bytes. He reproduced the defect empirically (`25000.00` → `25000.0`) and verified the fix against the real compiled entity. Kavya QA: **PASS**. Not yet re-tested live.

**Where:** `DealService.java:71`; `DealMessage.java:29`

**Why:** `DealMessage`'s bare `ObjectMapper` does not have `USE_BIG_DECIMAL_FOR_FLOATS` enabled, so any settle operation round-trips a value like `25000.00` down to `25000.0`. Not reachable via the SPA today — JS numbers arrive at scale-0 — but this metadata is the payment evidence trail, and the method's own javadoc claims only `status` is rewritten by `settleStatus`, which is no longer strictly true once a settle touches the whole JSON blob.

**Also flagged:** `DealService.java:71` and `DealMessage.java:29` each hold their own independent bare `ObjectMapper` instance. Two independent instances of the same serialization concern can drift in configuration over time.

**Fix (in progress):** Configure `USE_BIG_DECIMAL_FOR_FLOATS` on the mapper used by `settleStatus`, and consolidate to a single shared, correctly-configured `ObjectMapper` rather than two independent instances.

---

### CR-20 · 🟢 Low · N2: `loadMessages` lost unmount cancellation
**Owner:** Ananya · **Status:** IN QA

**Wave 3 update (2026-07-28):** Fixed — an `isMountedRef` now sits alongside the existing monotonic request token, combined into one `isCurrent()` predicate applied to all three branches (success, error, finally). A response is applied only if it is both the newest request **and** still wanted by a mounted component. `console.error` stays unconditional, matching the W2-L1 `refreshDeal` convention: a failed request is worth diagnosing whether or not its result is still wanted.
> Still correctly characterised as **restoring a capability, not fixing a live defect** — no leak is observable under React 18+.

**Where:** `src/pages/creator-chat.tsx` (`loadMessages` extraction, see CR-09)

**Why:** The previous inline fetch used a `cancelled` closure flag set on unmount. The extracted `loadMessages(dealId)` replaces it with a monotonic request token that correctly ignores stale *responses*, but does not currently abort or ignore work still in flight after the component unmounts. Not a bug under React 18+ (no state-update-after-unmount warning/leak observed), but it is a capability the replaced code had and this one doesn't.

**Fix:** Add an unmount guard (abort controller or an `isMounted` ref) alongside the existing request token.

---

### CR-21 · 🟢 Low · N3: "Refresh deal" flashes the whole page
**Owner:** Ananya · **Status:** IN VERIFY

**Wave 2 update (Tara, 2026-07-27):** **Closed incidentally** by CR-09's work, not worked directly. `refreshDeal` is a single `GET /deals/:id` that never touches `dealsLoading`, so accept/decline no longer blank the room. Not yet re-tested live.

**Where:** `src/pages/creator-chat.tsx` (`loadDeals()`, `dealsLoading`)

**Why:** `loadDeals()` sets `dealsLoading`, which early-returns a full-page spinner. This is pre-existing behavior, but it is newly reachable now that the CR-09 Refresh button calls it more often as a minor affordance — clicking Refresh currently blanks the whole page rather than updating just the deal list/timeline in place.

**Fix:** Scope the loading indicator to the refreshed region instead of gating the entire page render on `dealsLoading`.

---

### CR-22 · 🟡 Medium · Brand-side `canReject` withdrawal flow needs its own UI
**Owner:** Unassigned · **Status:** OPEN

**Why:** Per CTO ruling, deal-level withdrawal deliberately does **not** belong on the proposal card — it needs its own, separate flow. Cross-reference the ruling documented at `creator-chat.tsx:1856-1871` (the H2 decision).

**Fix:** Design and implement a dedicated withdrawal affordance for the brand side, distinct from the proposal-card accept/reject/counter actions. Needs an owner assigned before it can move to `ASSIGNED`.

---

### CR-23 · 🟢 Low · Brand `refreshDeal` catch block missing the staleness guard
**Owner:** Priya · **Status:** IN VERIFY

**Wave 2 update (Tara, 2026-07-27):** Fixed by Priya in `src/pages/brand-chat.tsx`'s `refreshDeal` useCallback — the creator-side pattern was ported verbatim: `isSupersededRefresh(dealId)` is now defined immediately after the token is claimed and applied to **both** the success and failure paths, matching `creator-chat.tsx:721-753`. `console.error` stays **unconditional** (a failed request is worth diagnosing regardless of whether its result is still wanted); only the `toast(...)` is suppressed when superseded, since that's the part that would contradict what's already on screen. One change beyond the literal port: the brand copy previously checked `if (!fresh) return;` *before* the staleness check, while the creator copy checks staleness first — matched to the creator ordering so both files test in the same order (no behavioral difference, a stale response returns either way). Comments cite **W2-L1b** (the brand-side finding ID), not W2-L1, so the same defect found on two surfaces stays traceable as two distinct citations of one root cause. Verification: `npm run typecheck` clean · `npm test` **252/252, 27 files** (unchanged — this is a guard on an error path, and no existing test exercises a superseded *failed* refresh) · `npm run lint` **403, exactly baseline**.

> **Caveats, recorded honestly:** (1) **Not yet re-reviewed by Kavya** — she raised W2-L1b as LOW/non-blocking so it didn't warrant its own QA round, but this change landed *after* her Wave 2 PASS; fold into the next QA pass rather than let it ride to deploy unexamined. (2) **No new test coverage** — the existing 252 tests still pass, but none exercises the specific superseded-failed-refresh scenario; the fix is reasoned and typechecked, not test-pinned. (3) **Still nothing deployed** — `http://200.141.1.6` continues to serve the pre-Wave-1 bundle, so this cannot advance past `IN VERIFY` any more than the other tickets can.

**Why:** The success path checks the per-deal token before applying a refresh, but the `catch` block toasts unconditionally — so a slow, *failing* refresh can pop "Could not refresh this deal" after a newer refresh has already succeeded. This is the same defect Kavya raised as **W2-L1** on the creator side, reproduced independently in the new brand code while the creator copy was being fixed this wave.

**Fix:** Port the pattern already used on the creator side — `isSupersededRefresh` at `creator-chat.tsx:721-722` — into the brand `refreshDeal` catch block. Note: `console.error` is deliberately left unconditional there; only the user-facing toast should be gated.

---

### CR-24 · 🟡 Medium · Brand-side status mapper unification
**Owner:** Unassigned · **Status:** OPEN

**Where:** `brand-chat.tsx:164-186`, `brand-pipeline.tsx:83-86`, `deal-room-dashboard.tsx:81`

**Why:** Three brand surfaces still switch over `CollaborationStatus` independently instead of sharing one mapper. `brand-chat.tsx:164` maps `TERMS_AGREED → 'contracted'` — **character-for-character the mapping deleted from `creator-chat.tsx` this wave as the CR-05 defect** — so the two sides of one negotiation can disagree about its stage, the brand mirror of CR-05.

**Fix:** Same shape as CR-05's fix, applied to the brand vocabulary.

> **Scope note:** Priya ruled this **OUT of Wave 2** — the brand vocabulary feeds that page's chips/filters/empty-states and needs its own QA pass. Needs an owner and its own wave before work starts.

---

### CR-25 · 🟡 Medium · SSE publishes fire inside the caller's `@Transactional`
**Owner:** Vikram · **Status:** OPEN

**Why:** A subscriber can observe a publish frame that a later rollback then erases, because the publish happens inside the same transaction as the write it describes. Pre-existing — it affects the original `sendMessage` publish too — and is **not** introduced by CR-08's new publishes; CR-08 just doubled the surface area where it can happen.

**Fix:** Move the publish to an `afterCommit` transaction synchronization so a subscriber only ever sees committed state. This alters the shipped send path, hence its own ticket rather than folding it into CR-08.

---

### CR-26 · 🟡 Medium · `DISPUTED`/`CANCELLED` render as "Done"/"Completed"
**Owner:** Unassigned · **Status:** OPEN

**Why:** No server-side display bucket exists for these two statuses — `bucketFor` returns `null` for them and no status filter chip selects them, so they fall through to whatever bucket happens to render as a default. Telling a creator a **disputed** deal is "Done" is a real, user-facing misstatement, not cosmetic. A `stage-disputed` design token already exists in the system, unused.

**Fix:** Add a 7th bucket for disputed/cancelled, plus the corresponding chip, filter, and empty-state work on both the creator and brand pages.

---

### CR-27 · 🟢 Low · `creator-deals.tsx` under-offers actions vs the server
**Owner:** Unassigned · **Status:** OPEN

**Why:** The page only offers Accept/Counter/Decline when status is `'new'` (`INVITED`), but `Collaboration.canAccept()` also permits `APPLIED`, `SHORTLISTED`, and `IN_NEGOTIATION` — states where the server would allow the action but the list view never surfaces it.

**Fix:** Logged as a decision point, not an automatic fix — may be intentional (negotiation-stage actions belong in the room, not the list). Needs a product call on whether the list should also expose these actions before any code changes.

---

### CR-28 · 🟢 Low · Backend test helper hides the settle path
**Owner:** Vikram · **Status:** OPEN

**Why:** The pre-existing `proposalMessage` helper in `DealServiceTest` carries **null metadata**, so `settleStatus` no-ops in every older accept/counter test built on it — those tests pass without ever exercising the settle path. Only the new `pendingProposalMessage` helper (added alongside the CR-02/CR-19 work) actually covers it.

**Fix:** Migrate the older accept/counter tests in `DealServiceTest` onto `pendingProposalMessage`, or otherwise add explicit coverage of the settle path for each. Risk today is **missing** assertions, not wrong ones — no known false-positive has been traced to this yet.

---

### CR-29 · 🟢 Low · CR-23's fix has no test coverage
**Owner:** Unassigned · **Status:** OPEN

**Why:** All 252 tests pass, but none exercises the specific scenario CR-23's guard protects: a refresh that **fails** after a newer refresh of the same deal has already **succeeded**. Contrast with Wave 2's three remediation guards, each of which fails if you revert the fix it protects — the CR-23 fix has no equivalent tripwire. It is reasoned and typechecked, not test-pinned. The same gap applies to the creator-side **W2-L1** fix that CR-23 was ported from — neither copy is test-pinned.

**Fix:** Add a test that forces a refresh to fail after a newer refresh of the same deal has already resolved, and assert the failure toast is suppressed while `console.error` still fires. Cover both the brand (`brand-chat.tsx`, W2-L1b/CR-23) and creator (`creator-chat.tsx`, W2-L1) copies.

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
| 2026-07-27 | Tara | **Stale-row correction:** CR-19 was left `IN PROGRESS` after last pass; Vikram finished it since then. `IN PROGRESS` → `IN VERIFY`. He enabled `USE_BIG_DECIMAL_FOR_FLOATS` on `DealMessage.MAPPER` only, deliberately leaving `DealService.MAPPER` untouched (its read path feeds the response DTO and the flag would change API response bytes); defect reproduced empirically (`25000.00` → `25000.0`), fix verified against the real compiled entity. **Wave 2 results:** CR-05 `OPEN` → `IN VERIFY` (one shared `mapCollaborationStatusToDealStage` in `creator-deal-mappers.ts`, private per-page mappers deleted). CR-07 `OPEN` → `IN VERIFY` (both buttons wired, `renderProposalCard` now unified for demo+live, gate mirrors `doAccept`'s `CANNOT_ACCEPT_OWN_OFFER`; Neha still needs brand test credentials — blocker unchanged). CR-08 `OPEN` → `IN VERIFY` (`DealService` now publishes two SSE frames per accept/reject/counter — settled/superseded card first, then system message or new card). CR-09 `IN PROGRESS (partial)` → `IN VERIFY`, now **COMPLETE** (`afterDealMutation` = `Promise.all([refreshDeal, loadMessages])` on accept, decline, and counter). CR-21 `OPEN` → `IN VERIFY`, **closed incidentally** by CR-09's work (not worked directly) — `refreshDeal` never touches `dealsLoading`. Added a note to CR-13 (no status change): `DealService.statusesForFilter` and `AdminBrandService`'s javadoc disagree with three display mappers on where `TERMS_AGREED` sits; Priya ruled the filter path must move. Added six new tickets: CR-23 (brand `refreshDeal` catch block missing staleness guard, mirrors creator-side W2-L1, OPEN, owner Ananya, Low), CR-24 (brand-side status mapper unification across three surfaces, OPEN, unassigned, Medium, ruled OUT of Wave 2 by Priya), CR-25 (SSE publishes fire inside caller's `@Transactional`, pre-existing, OPEN, owner Vikram, Medium), CR-26 (`DISPUTED`/`CANCELLED` render as Done/Completed, no display bucket, OPEN, unassigned, Medium), CR-27 (`creator-deals.tsx` under-offers actions vs `Collaboration.canAccept()`, OPEN, unassigned, Low, possibly intentional), CR-28 (backend test helper `proposalMessage` carries null metadata and hides the settle path, OPEN, owner Vikram, Low). Totals recalculated: 3 Critical · 7 High · 11 Medium · 7 Low = **28 logged**, **0 DONE**. Verification evidence recorded against Wave 2: `npm run typecheck` clean; `npm test` **252/252, 27 files** (227 baseline + 5 CR-07 + 17 CR-05 + 3 remediation guards); `npm run lint` **403, exactly baseline**; `mvn -o test` **1486 tests, 0 failures, 0 errors, 3 skipped, BUILD SUCCESS**. Kavya QA: **FAIL** on first pass (Critical **W2-C1** — the brand room was a third SSE consumer still running ignore-if-present dedupe, which would have reopened CR-02 on the brand side the moment CR-08 shipped), then **PASS** after remediation. **Correction on the record:** Wave 1 was committed with a red backend test (`DealServiceTest.testBrandAcceptHappyPath` asserted one `save()` while CR-02 made `doAccept` save twice) — went unnoticed because Priya instructed `-DskipTests` for Wave 1's build check; fixed in Wave 2, the suite now runs on every wave. **Meera's Wave 2 build verification landed — ALL PASS:** `npm run build` PASS (Vite 4765 modules in 24.33s), then `postbuild` (`node scripts/prerender.mjs`) **16/16 routes snapshotted** — the genuine risk this wave, since three route-level page components plus a shared lib changed and a prerender can fail on code that typechecks cleanly; only warnings were the pre-existing duplicate-key `tsconfig.json` esbuild notice and Vite's standard >500 kB chunk advisory, neither a failure. `mvn -o package` run **WITHOUT `-DskipTests`** — BUILD SUCCESS in 26.8s, **1486 tests, 0 failures, 0 errors, 3 skipped**, same signature as the standalone `mvn -o test`, no regression; jar packaged and Spring Boot repackaged (`influora-api-0.1.0-SNAPSHOT.jar`). **This is the first wave where `mvn -o package` ran with tests**, closing the gap that let Wave 1's red test through undetected. Bundle: `index-8fhUJ8_B.js` at 2,697.80 kB (gzip 725.37 kB); CSS `index-CImlwGd-.css` 222.93 kB. Deltas: **+6.47 kB (+0.24%)** vs the Wave 1 build (`index-Bu4yUEbB.js`, 2,691.33 kB); **≈ +17.8 kB (≈ +0.66%)** vs the deployed `index-NdzlUg4U.js` — recorded as **approximate**, since the deployed reference is only known as "~2.68 MB", not an exact byte count. **NOT verified: anything in a browser.** **Nothing has been deployed** — `http://200.141.1.6` still serves the pre-Wave-1 bundle. No ticket is marked `DONE`; nothing has been verified in a live browser by Neha. **Record note:** `wiki/errors/SHARED_CONTEXT.md` now exists (created this pass, outside the original brief but consistent with company protocol) — it is left in place, but this tracker, not `SHARED_CONTEXT.md`, remains the single source of truth for creator-defect status; the two files must not be allowed to drift apart on ticket state. |
| 2026-07-27 | Tara | **CR-23** `OPEN` → `IN VERIFY`. Fixed by Priya in `brand-chat.tsx`'s `refreshDeal` useCallback — creator-side `isSupersededRefresh` pattern ported verbatim (matches `creator-chat.tsx:721-753`): `console.error` stays unconditional, only the failure `toast(...)` is suppressed when superseded; staleness-check-before-early-return ordering matched to the creator copy (no behavioral change); comments cite **W2-L1b** so the twice-found defect stays traceable to two surfaces. Verification: `npm run typecheck` clean · `npm test` **252/252, 27 files** (unchanged — no test exercises a superseded *failed* refresh) · `npm run lint` **403, exactly baseline**. Caveats recorded on the ticket: not yet re-reviewed by Kavya (landed after her Wave 2 PASS; W2-L1b was raised as LOW/non-blocking so didn't get its own QA round — fold into next pass), no new test coverage for the specific scenario, and still nothing deployed. Totals unchanged: 3 Critical · 7 High · 11 Medium · 7 Low = **28 logged**, **0 DONE** — CR-01/02/03/05/07/08/09/18/19/21/23 all remain `IN VERIFY`; `http://200.141.1.6` still serves the pre-Wave-1 bundle. |
| 2026-07-28 | Claude (at repo owner's direction — **not Tara**, see header) | **Wave 3/4 pass + four factual corrections.** **Status changes:** CR-04, CR-06, CR-10, CR-12, CR-14, CR-16, CR-17, CR-20 all `OPEN` → `IN QA` — code-complete in commit `5b86a49`, pushed to `origin/feat/creator-my-applications` (fast-forward `ad8d503..5b86a49`, 15 files, +679/−77). **CR-06** fixed at root cause: new `persistCreatorSession`/`getCreatorSession`/`clearCreatorSession` (the creator flow had no `persistBrandSession` equivalent), `login()` now called in both modes, new `useCreatorIdentity()` hydrating from session + `GET /me/creator-profile` (required because the auth store is `partialize: () => ({})` and empties on reload), and the `@priya_sharma` / `Creator Account` / `IN` literals **deleted** per the CTO note — verified absent from the production bundle. **CR-10** boundary moved inside `<BrowserRouter>` with a `resetKey` on pathname (prop not `key`, to avoid remounting healthy routes). **CR-04** all three causes: `p-4` off the ScrollArea root, `scrollIntoView` → viewport `scrollTo` via a new optional `viewportRef`, `baseEventsForDeal` memoized. **CR-12** badges + header summary read a separate unfiltered fetch; list stays server-filtered so CR-13 is not masked client-side against Priya's ruling — expect the Active chip to show a count while the Active tab is empty, which *is* CR-13. **CR-17** new shared `--app-header-h` token; ToolsSheet occurrence deliberately untouched. **CR-14** `relativeTime` guard + `lastSyncedAt` widened to `?: string \| null` (the type was lying about what the server sends). **CR-16** real unread total from the deals API. **CR-20** `isMountedRef` alongside the request token. **Verification:** `npm run typecheck` clean · `npm test` **252/252, 27 files** (exactly baseline — **no new tests added**) · `npm run lint` **403 problems (336 errors, 67 warnings), exactly baseline**, measured by stashing rather than trusting the recorded figure; two findings did land above baseline (a `setState`-in-effect error and an `exhaustive-deps` warning) and both were fixed rather than suppressed · `npm run build` PASS, **16/16 routes prerendered**, 4768 modules · bundle `index-DkEVH8Vd.js` 2,702.25 kB, **+4.45 kB (+0.16%)** vs Wave 2. **NOT verified: anything in a browser** — the Browser pane would not composite this session, so CR-04/06/12/17, all visual, are unobserved. **Four corrections to the existing record:** (1) **§9's deploy blocker is RESOLVED** by `04b7a53`, which added the exact one-line fix §9 prescribed — §9 rewritten with a superseded banner, original text retained per the append-only rule; (2) **the live box serves Wave 2, not the pre-Wave-1 bundle** — `index-B_x5CUtn.js`, 2,697,823 bytes, matching the recorded 2,697.80 kB, so **Waves 1–2 ARE deployed** and their 11 `IN VERIFY` tickets are testable now; (3) Wave 2 + CR-23 are committed and pushed, not uncommitted; (4) the "0 DONE because nothing is deployed" rationale no longer holds — the gap is QA and Neha's time. **Wave 3 images published but NOT pulled:** run `30343078697` built and pushed all three images successfully, but the VPS was **deliberately not restarted** (`VPS_restartProjectV1` on `influora-test`) because these 8 tickets have had no Kavya pass and a restart would swap the build out from under Neha. Totals unchanged: 3 Critical · 7 High · 11 Medium · 8 Low = **29 logged**, **0 DONE**; by status **8 `IN QA` · 11 `IN VERIFY` · 8 `OPEN` · 2 `BLOCKED`**. Every 🔴 Critical and 🟠 High ticket now has code; everything unwritten is Medium or Low. |
| 2026-07-27 | Tara | Added **§9 Deploy Blocker** — the analysis of why nothing can go `DONE` had existed only in-session until now. Recorded: the live box is Hostinger VPS 1844961 running Docker Compose project `influora-test` (6 containers) pulling `ghcr.io/influoradigital-bit/influora-{api,ai,web}:latest`; `.github/workflows/publish-images.yml` doesn't trigger on `feat/creator-my-applications` (only `main` / `feat/creator-taxonomy-keyword-patch`); `workflow_dispatch` is unreachable because `origin/main` is a 1-file (`README.md`) branch with no workflow on it; merging to `main` is not a viable release valve (~108 unreviewed commits ahead); the one-line fix (add the branch to `push.branches`, precedented by `feat/creator-taxonomy-keyword-patch`'s existing entry) is safe on a push trigger (build-arg fallbacks match the live bundle's config) and rollback exists (images tagged both `:latest` and `:sha`); owner is Swapnil, this is a decision not an engineering task; editing the workflow file is blocked by this session's permission classifier. Recorded Wave 1's actual commit state (`21399b2`, `06c1bcb`, not pushed; Wave 2 + CR-23 uncommitted on top; branch also answers to `cr-08-deal-lifecycle-sse`, same commit, no work at risk). Added a note to CR-15 (no status change) distinguishing its domain+TLS blocker from §9's CI/CD blocker — fixing one does not fix the other. Added **CR-29** (🟢 Low, unassigned — CR-23's fix, and the creator-side W2-L1 fix it was ported from, have no test coverage for the superseded-failed-refresh scenario; contrast with Wave 2's three remediation guards, which do fail on revert). Totals recalculated: 3 Critical · 7 High · 11 Medium · 8 Low = **29 logged**, **0 DONE**. No ticket status changed in this pass; `http://200.141.1.6` still serves the pre-Wave-1 bundle. |

---

## 8. CTO Notes & Escalations

**Escalating to Swapnil (CEO) — one item:**
> **HTTPS migration (CR-01 + CR-15) is a business blocker, not a tech-debt item.** While the product runs on `http://` at a bare IP, no creator can share their public page and no shared link works in an Instagram bio. That removes the entire organic acquisition loop. This needs a domain + certificate decision from you before Wave 1 can complete.

> ⚠️ **Update 2026-07-28 — this is now the ONLY infrastructure item awaiting you.** The second escalation (the §9 CI/CD deploy blocker) is **resolved** — `04b7a53` added the workflow branch line, images publish, and Waves 1–2 are deployed. HTTPS is what remains. CR-01's `execCommand` fallback means the Share button now *works*, but the link it copies is still `http://200.141.1.6/@handle` — so the acquisition loop is still shut, exactly as described above.
>
> **A second, smaller decision now sits with you as a side effect:** `publish-images.yml` currently redeploys the box Neha tests on **on every push to `feat/creator-my-applications`**. The workflow's own comment says to remove that line once Waves 1–2 ship — they have shipped. Leave it (fast iteration, but unreviewed work can land under Neha mid-test) or remove it (back to manual releases). Either is defensible; it should be chosen, not drifted into.

**Architectural themes behind these 17 tickets** — worth fixing as patterns, not just instances:
1. **Server state is written but never broadcast** (CR-08) and **client state is fetched but never refetched** (CR-09, CR-02). The deal room has no single "reload this deal's world" path. One `refreshDeal(dealId)` used by every mutation would collapse three tickets into one.
2. **Duplicated mapping logic** (CR-05, CR-13) — the same backend enum is interpreted differently in three places. One shared mapper module, consumed everywhere.
3. **Demo fallbacks shipped to production** (CR-06, CR-16). Placeholder values must never be the `||` fallback of real data. Prefer an explicit loading/empty state.
4. **Silent catches** (CR-01, CR-03). Two separate user-facing failures were caused by an empty `catch`. New standard: **no empty catch blocks in `src/`** — log *and* surface, or don't catch.

**Wave 1 addendum (Tara, 2026-07-27):** As part of the CR-02 fix, `DealMessage` now imports Jackson and holds a static `ObjectMapper` so `settleStatus(String)` can narrow-write just the status field instead of exposing a raw `setMetadataJson` setter. This was a deliberate, flagged trade: the evidence-trail protection (no more free-form metadata writes reaching a payment-adjacent record) was judged to outweigh keeping serialization logic out of the domain layer. Note this as **a precedent to watch, not a general licence** — it should not be read as blanket permission for other entities to start carrying serialization logic. See also CR-19 (N1), which flags that this same class now has a bare `ObjectMapper` independent of `DealService.java:71`'s own instance, and the two can drift.

— *Priya Sharma, CTO*

---

## 9. Deploy Blocker — ✅ **RESOLVED 2026-07-28**

> # ⚠️ THIS SECTION IS SUPERSEDED — READ THIS BOX FIRST
>
> **The deploy blocker described below no longer exists.** It was resolved by commit **`04b7a53`** ("ci: publish images on pushes to feat/creator-my-applications"), which added the exact one-line fix this section prescribed. Verified 2026-07-28 by reading `.github/workflows/publish-images.yml` — `feat/creator-my-applications` **is** in `push.branches`, carrying the predicted comment, and the `paths:` filter includes `src/**` and `influora-api/**`.
>
> **Consequences, all verified:**
> 1. **Waves 1–2 ARE deployed.** `http://200.141.1.6` serves `index-B_x5CUtn.js` at **2,697,823 bytes**, which matches the recorded Wave 2 build (2,697.80 kB). The claim elsewhere in this file that the box still serves the pre-Wave-1 `index-NdzlUg4U.js` (~2.68 MB) is **wrong** — it has been superseded.
> 2. **The 11 `IN VERIFY` tickets are testable right now.** Nothing infrastructural is stopping Neha. CR-07 remains blocked on **brand test credentials**, which is a separate and still-unresolved ask.
> 3. **Wave 2 + the CR-23 fix are committed and pushed**, not uncommitted working-tree changes as recorded below.
> 4. **The §9 → CR-15 distinction still holds, in the other direction.** CR-15 (domain + TLS) is **still blocked** and still needs Swapnil. Resolving §9 did not resolve it, exactly as this section predicted.
>
> **The exposure this section warned about is now live, not hypothetical.** *"Every future push to this branch redeploys the box Neha tests on, including half-finished work."* — that is now the operating reality. The Wave 3 push (`5b86a49`) triggered run `30343078697`, all three images built and pushed successfully. **The VPS has not pulled them**: publishing to GHCR and deploying are two separate steps, and nothing restarted the stack. The box therefore still serves Wave 2 while Wave 3 images sit in the registry. **A `VPS_restartProjectV1` on Docker Compose project `influora-test` is what would make Wave 3 live — deliberately NOT done**, because the eight Wave 3 tickets have had no Kavya pass and restarting would swap the build out from under Neha mid-session.
>
> **Still Swapnil's decision:** the workflow comment says to remove the branch line once Waves 1–2 ship. They have shipped. Leaving it in means every push auto-redeploys Neha's test box.

**Historical record — the original analysis follows, retained per §6's append-only rule. It described the situation accurately as of 2026-07-27 and is preserved for that reason, but do not act on it.**

**~~Read this before asking why any `IN VERIFY` ticket hasn't gone `DONE`.~~** ~~Every single one of them — CR-01, CR-02, CR-03, CR-05, CR-07, CR-08, CR-09, CR-18, CR-19, CR-21, CR-23 — is stuck behind the same wall, described once here rather than repeated eleven times.~~

### What is actually running
`http://200.141.1.6` is Hostinger VPS id **1844961** (`srv1844961.hstgr.cloud`, Ubuntu 24.04). It runs a Docker Compose project named **`influora-test`** — 6 containers: `caddy`, `frontend`, `influora-api`, `influora-ai`, `mysql`, `redis`. A separate `n8n` project runs on the same box, unrelated to this app. The three app containers pull **`ghcr.io/influoradigital-bit/influora-{api,ai,web}:latest`**. Deploying is **not** copying files to a server — it means publishing new images to that registry and restarting the stack. Nobody can do that by hand without going through the pipeline below.

### Why it is blocked
Images are published by `.github/workflows/publish-images.yml`, which triggers on `workflow_dispatch` or on push to `main` / `feat/creator-taxonomy-keyword-patch`. All of Waves 1–2 (and the CR-23 fix) live on `feat/creator-my-applications`, which is **not** in that trigger list. Nothing on this branch can reach the registry today.

Both apparent escape routes are closed:
- **`workflow_dispatch` is unreachable.** `origin/main` contains **exactly one file, `README.md`** (verified with `git ls-tree -r origin/main --name-only`). The workflow doesn't exist on the default branch, and GitHub only renders the "Run workflow" button for workflows present there. There is no button to click.
- **Merging to `main` is not a release.** `feat/creator-my-applications` is **~108 commits ahead** of `main`. Merging would take `main` from 1 file to the entire repository and ship all 108 commits at once, of which only Waves 1–2 have been reviewed.

### The fix — one line
Add the active branch to the workflow's `push.branches` list:
```yaml
      - feat/creator-my-applications
```
**Precedented, not a workaround:** `feat/creator-taxonomy-keyword-patch` is already in that list, carrying the comment *"so it runs without needing main / the dispatch button"* — someone hit this exact wall before and solved it the same way.

**Why this is safe on a push event** (the thing someone will worry about): the `web` job's condition is `if: github.event_name == 'push' || inputs.publish_web`, and its build-args fall back to `VITE_API_BASE_URL=http://200.141.1.6/api/v1` and `VITE_MEERA_STREAM_URL=http://200.141.1.6/meera` when dispatch inputs are absent. Those are exactly what the currently-running bundle targets, so a push-triggered build produces a correctly-configured frontend. The `paths:` filter includes `influora-api/**` and `src/**`, so both waves qualify and would trigger a build.

**Rollback exists.** Images are tagged **both** `:latest` and `:${{ github.sha }}`, so a bad deploy can be pinned back to a previous SHA. *(This corrects an earlier claim in this session that there was no rollback path — there is.)*

### Two caveats
- Editing `.github/workflows/**` is blocked by the permission classifier in this session. Swapnil must either add the line himself or grant the permission for someone else to.
- Adding that line means **every future push to this branch redeploys the box Neha tests on**, including half-finished work. Remove the line once the waves ship, or accept the exposure consciously while the box is a test box — but it should be a conscious choice, not a surprise.

**Owner: Swapnil (CEO).** This is a decision, not an engineering task — no ticket in §5 should be opened against it.

### Not the same blocker as CR-15
CR-15 (bare-IP-over-HTTP) is a **separate** blocker from the one above, both needing a Swapnil decision but neither substituting for the other: CR-15 needs a **domain + TLS** decision (there is nowhere to serve HTTPS regardless of what's deployed); this section needs a **CI/CD workflow-branch** decision (the current build isn't reaching the box at all, even over plain HTTP). Fixing one does not fix the other.

### Current commit / branch state — **UPDATED 2026-07-28**

**Everything is committed and pushed to `origin/feat/creator-my-applications`.** Current tip:

| Commit | What |
|---|---|
| `5b86a49` | **Wave 3/4** — the 8 Ananya tickets (CR-04/06/10/12/14/16/17/20), 15 files, +679/−77 |
| `ad8d503` | docs: Wave 2 tracker update + deploy blocker record |
| `04b7a53` | **ci: publish images on pushes to `feat/creator-my-applications`** ← the §9 fix |
| `28603a6` | Wave 2: deal lifecycle SSE fan-out + status consistency |
| `06c1bcb` | docs: creator bug tracker + Wave 1 E2E evidence |
| `21399b2` | Wave 1: creator deal room + public page critical fixes |

> **Superseded:** the previous text here said Wave 1 was "committed locally and **not pushed**" with Wave 2 + CR-23 as "**uncommitted working-tree changes**". Both statements are now false — all of it is on the remote.
>
> **The branch-name wrinkle is real and unresolved.** Local work sat on `cr-08-deal-lifecycle-sse` (the subagent-created name noted previously) and Wave 3 was pushed from it via `git push origin HEAD:feat/creator-my-applications` — a clean fast-forward. The two names still point at the same commit and no work is at risk, but **anyone checking out `cr-08-deal-lifecycle-sse` is on a branch that does not exist on the remote and is not in the CI trigger list.** Work on `feat/creator-my-applications`.
