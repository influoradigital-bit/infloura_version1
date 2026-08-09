# Influora — Live Broken & Confusing Features Audit
**Host:** http://200.141.1.6 · **Date:** 2026-08-08 · **Auditor:** neha (live E2E)
**Accounts walked:** brand `inglepriya7715@gmail.com` (workspace "Priya (Software Developer)") ·
creator `tejas.chache5@gmail.com` (@tejas_creater)
**Coverage:** 19/19 brand routes · 15/15 creator routes — both surfaces complete

---

## 0. How to read this document

Every row is labelled with how strongly it is established. This matters — half the value of an
audit is knowing which findings you can act on without re-checking.

| Label | Meaning |
|---|---|
| **PROVED-LIVE** | I reproduced it in the browser on 200.141.1.6 and captured the response/DOM |
| **PROVED-CODE** | Traced to a specific `file:line` that must produce this behaviour |
| **OBSERVED** | Seen live, root cause not traced |
| **NOT TESTED** | Explicitly out of reach this session — see §5 |

**Three findings in my first pass were wrong and are not in this report.** I list them in §6 so
the same mistakes aren't repeated: Radix tab controls and portal-rendered panels do not respond to
synthetic `.click()`, and a router navigation can land after a naive snapshot. Anything below
survived re-testing with real pointer events and full-document diffs.

---

## 1. The headline: "Timeline is not working"

You reported this specifically. It is real, and it is **three separate defects wearing the same
word**. That is itself the reason it feels broken everywhere.

### 1.1 Deal Room "Timeline" always says "No deadline set" — BLOCKER
**PROVED-LIVE + PROVED-CODE** · `influora-api/.../service/DealService.java:1115-1117`

`toDealResponse` does not compute three fields. It hardcodes them:

```java
unread,
0,        // deliverablesDone   <- literal
0,        // deliverablesTotal  <- literal
null,     // nextDeadline       <- literal
```

Consequence on the live deal `01KZ1TXGC97APN7YT0MKK0N0P1` (Tejas Creater, ₹7,000):

| Screen shows | Ground truth (same API, proposal metadata) |
|---|---|
| `Timeline — No deadline set` | `deadline: "2026-08-25"` |
| `Deliverables 0` | `deliverables: [{qty:1, type:"REEL"}], deliverableCount: 1` |
| `0 deliverables agreed · … itemized breakdown not available yet` | 1 REEL agreed, 1-year usage rights |

On `/brand/chat` the contradiction is visible **in a single screenshot**: the header reads
`0/0 done` while the proposal card directly beneath reads `Deliverables 1 items`.

This is not cosmetic. `nextDeadline` is the field every deadline/SLA/reminder surface reads, so
nothing downstream can ever fire. **It hits the creator side identically** — both roles are served
by this same method.

### 1.2 Pipeline → "Timeline" view has no time axis — HIGH (confusing)
**PROVED-LIVE** · `src/pages/brand-pipeline.tsx:563` (`timelineView`, commented "simplified Gantt-like")

Switching to the Timeline tab renders one progress bar per deal at `width: 33.3333%`. I scanned
the rendered view for any date, duration, or relative-time string: **zero matches**. There is no
axis, no dates, no schedule — it is a stage-completion bar (`stageIndex / stages.length`) labelled
"Timeline". A user clicking "Timeline" expecting a Gantt gets essentially the Board card again.

### 1.3 The word "Timeline" means four different things
**OBSERVED** — this is why it feels broken even where it works:

1. Pipeline view mode → a progress bar
2. Deal Room field label → a single deadline date
3. Campaign wizard step 3 → "Timeline & budget" (start/end dates)
4. `CollaborationTimeline` component → the message/activity feed

Four unrelated concepts, one word. Pick distinct names (Schedule / Deadline / Dates / Activity).

---

## 2. Broken features

### 2.1 Wallet permanently reads "Critical · 0d runway" — HIGH
**PROVED-LIVE + PROVED-CODE** · `src/components/brand/dashboard/dashboard-page.tsx:125,166`

The dashboard reads `walletResult.value.runwayDays ?? 0`. The live API returns:

```json
GET /api/v1/wallet → {"availableBalance":10000,"escrowLocked":0,"pendingPayouts":0}
```

**There is no `runwayDays` field.** `?? 0` therefore always wins, and the health rule
(`>30 healthy : >14 warning : critical`) always lands on `critical`.

Result: a funded brand with ₹10,000 and zero spend is permanently told
`Critical · 0d runway · Low · Recharge now`. Every brand sees this forever, regardless of balance.
It is a false alarm on the most trust-sensitive widget on the dashboard.

> Note: this class of bug is invisible to path-level contract checking — the endpoint exists and
> returns 200. It is a **response-shape** mismatch. See §4.

### 2.2 Brand analytics 403s on the brand's own creator — HIGH
**PROVED-LIVE + PROVED-CODE** · `influora-api/.../service/MetricsAuthorizationService.java:72`

`/brand/analytics` auto-selects Tejas Creater and immediately errors:

> Couldn't load metrics for this creator.
> **This workspace is not authorized to view metrics for that creator**

`GET /analytics/creators/01KY4YGN9MHZBEB7MY5ETVYWTK/metrics → 403`

The real cause is not authorization in any sense the user would recognise. The check requires an
active, non-revoked `meta_oauth_tokens` row pairing *this workspace* with *this creator* — i.e.
**the creator has not connected their Instagram/Meta account**. The brand has an active accepted
deal with them; nothing is misconfigured on the brand's side and nothing they can do in Settings
will fix it.

Compounding it: the page still renders the zeroed stat cards underneath the error, so it reads as
"analytics is broken" rather than "waiting on the creator".

**Fix is mostly copy + a CTA:** "Tejas hasn't connected their Instagram yet — send a request."

### 2.3 Business verification (KYC) is unreachable after campaign creation — MEDIUM
**PROVED-LIVE**

The KYC form (GSTIN, PAN, + document upload) exists and works — but *only* inside the
`/brand/campaigns/new` flow. Its own copy says:

> "You can do this anytime — it won't block your campaign."

That is false in the deployed build. `/brand/settings` exposes only **General · Notifications ·
Billing · Security**. There is no Verification tab, and `/brand/settings/verification` is not in the
shipped bundle at all. A brand that clicks "Skip for now" has **no way back** to verification
except by starting another campaign.

### 2.4 "View Contract" dead-ends on an empty global list — MEDIUM
**PROVED-LIVE**

On a deal with `contractId: null`, `/brand/deals/:id` shows a "View Contract" button. It navigates
to `/brand/contracts`, which is empty ("No contracts yet."). No explanation, no back link, no
"create the contract for this deal" action. The button promises a contract that does not exist.

### 2.5 `/brand/contracts` shows "No contracts yet." twice — LOW
**PROVED-LIVE + PROVED-CODE** · `contracts-and-deliverables.tsx:823` and `:1337`

The list pane and the detail pane both render the same string simultaneously. The detail pane
(`:1337`) should read "Select a contract" / "No deliverables yet." — not repeat the list's message.

### 2.6 `GET /brand/trendspark/nudge` aborts on every dashboard load — LOW
**PROVED-LIVE**

`204 No Content [FAILED: net::ERR_ABORTED]`, fired twice per load. Harmless to the user today but
it is permanent red noise in the console that will mask a real error later.

### 2.7 Every dashboard API call fires twice — LOW
**PROVED-LIVE**

`notifications`, `dashboard/actions`, `wallet`, `dashboard/pipeline` each issue two identical
requests per load. This is a production build, so it is not StrictMode double-invocation. Pure
waste today; a genuine hazard if this pattern reaches any non-idempotent endpoint.

---

## 3. Confusing features (works as coded, misleads the user)

| # | Where | What the user sees | Why it confuses | Evidence |
|---|---|---|---|---|
| 3.1 | Dashboard | **"Good evening, there"** | `user?.firstName \|\| 'there'` (`dashboard-page.tsx:177`). `firstName` is empty, though `brand_company` = "Priya (Software Developer)" is available. Reads as a broken template on the first screen after login. | PROVED-CODE |
| 3.2 | Deal Room | **"Usage Rights `1_YEAR`"** | Raw backend enum rendered verbatim. Should be "1 year". | PROVED-LIVE |
| 3.3 | Deal Room ×2 | Two different deal rooms | `/brand/deals/:id` and `/brand/chat` are both "the deal room" with **different capabilities**. Only `/brand/chat` has "Review & send contract"; `/brand/deals/:id` has the dead-end "View Contract". The nav sends you to the weaker one. | PROVED-LIVE |
| 3.4 | Deal Room | Stage stepper `1 Negotiate → 5 Pay` | Shows 5 stages but gives no indication of what unblocks the next one. With `deliverablesTotal` hardcoded to 0 (§1.1), progress indicators cannot move. | PROVED-LIVE |
| 3.5 | Contract panel | Milestone editor opens with no dialog semantics | Rendered into a portal with **no `role="dialog"`**, no accessible name. Screen readers get no modal announcement; my own first check missed it entirely for the same reason. | PROVED-LIVE |
| 3.6 | Global nav | **16 icon-only buttons with no accessible name** | The entire left nav and top bar expose as bare `button` in the a11y tree. Unusable by screen reader; ambiguous for new users. | PROVED-LIVE |
| 3.7 | Pipeline tabs | Board / List / Timeline tabs unnamed in a11y tree | Labels are `<span className="hidden sm:inline">`, so the name disappears at small widths with no `aria-label` fallback. | PROVED-LIVE |
| 3.8 | Campaign wizard | URL never changes across 5 steps | Stays `/brand/campaigns/new` for Basics → Review. Browser Back exits the whole wizard instead of stepping back; progress is not linkable or recoverable. | PROVED-LIVE |
| 3.9 | Wallet / Dashboard | "Critical" + "Low" + "₹10,000 Available" together | Three contradictory signals in one card (root cause §2.1). | PROVED-LIVE |
| 3.10 | Discover | "Showing 3 creators", one named **"Demo Creator"** with bio "Demo creator account for testing." | Seed/test data is visible in the production creator directory. Undermines trust immediately on the discovery surface. | PROVED-LIVE |

---

## 3B. Creator-side findings

The creator surface is in **better shape than the brand surface** in several places — the greeting
works ("Good evening, Tejas"), empty states are genuinely helpful, and Co-pilot renders a real
suggestion. But it carries the three worst money/trust defects in the whole audit.

### C1. Analytics tells creators to do something that is impossible — HIGH
**PROVED-LIVE + PROVED-CODE** · `src/components/creator/connected-accounts.tsx` (0 imports)

`/creator/analytics` says:

> "**Connect Instagram in Settings** and complete a few campaigns — your growth metrics will
> appear here after the first."

I checked every creator page for a connect control:

| Page | Mentions Instagram | Has a connect control |
|---|---|---|
| `/creator/settings` | **no — not at all** | **none** |
| `/creator/analytics` | yes (the instruction above) | none |
| `/creator/copilot` | yes | **"Connect Instagram" ✅** |
| `/creator/profile` | yes | "Connect More Accounts" |

The instruction points at the one page that has nothing. `ConnectedAccounts` — the component that
renders exactly the Settings card the copy describes — **is imported by nothing anywhere in
`src/`.** It is dead code. `creator-settings.tsx` imports `TaxIdentityForm` and `KycIdentityForm`
but never it.

**This is the missing link for the brand-side 403 (§2.2).** The Meta OAuth token that brand
analytics requires can only be created by a creator connecting Instagram — and the advertised
route to do that does not exist. Both analytics surfaces are dead for the same reason.
The backend is fine: `GET /meta/oauth/authorize` returns **200**.

### C2. Creator wallet shows disputed, frozen money as payable — HIGH
**PROVED-LIVE + PROVED-CODE** · `PaymentMilestoneRepository.java:63`

The wallet reads:

```
Available Balance ₹0     In Escrow ₹0     Pending Payouts ₹20,000
```

`GET /wallet/transactions` returns `[]` and the Payouts tab says "No payouts yet."

The ₹20,000 is exactly the deal **"QA E2E Flow Test — Contract and Escrow", status Disputed.**
`pendingPayouts` sums milestones with `status = FUNDED` across the creator's collaborations with
**no dispute filter**. So money frozen in an unresolved dispute is presented to the creator as
money on its way to them.

Worse, the two figures on that line come from different sources — `escrowLocked` is
`wallet.getEscrowBalance()` (0), `pendingPayouts` is the milestone sum (20,000) — so the card
contradicts itself: nothing is in escrow, yet ₹20,000 is pending release *from* escrow.

This is the highest-trust surface a creator has. It should never overstate.

### C3. `GET /creator/analytics/me/scores` → 404 on live — HIGH
**PROVED-LIVE** · mapping exists at `CreatorAnalyticsController.java:48`

| Endpoint | Live |
|---|---|
| `/creator/analytics/me/metrics` | 200 |
| `/creator/analytics/me/media` | 200 |
| `/creator/analytics/me/demographics` | 200 |
| `/creator/analytics/me/scores` | **404** |

The `@GetMapping("/scores")` is present in source, so **the deployed backend jar predates it**.
This fires on every `/creator/analytics` load. It is the only 404 either surface produced.

> This one slipped past my own gate by design: `fe_be_endpoints_app.py` compares frontend paths to
> backend **source** mappings, and explicitly declares "whether a mapping that exists is actually
> deployed" as its blind spot. It was right to declare it — that is precisely where the bug was.

### C4. One deal status, three different words — MEDIUM
**PROVED-LIVE**

Backend status `TERMS_AGREED` renders as:

| Surface | Label |
|---|---|
| Brand deal list | **Accepted** |
| Creator deal list | **Negotiating** |
| Creator proposal card | **Pending** |

The same deal, at the same moment, tells the brand it is agreed and the creator it is still being
negotiated. Combined with §1.1 (no deadline, no deliverable count), neither party can tell what
they have actually committed to.

Also: `Usage Rights` shows **"1_YEAR"** to the brand (§3.2) and **"Not specified"** to the creator
for the same agreed term.

### C5. QA/test fixtures are live in a real creator's deal room — MEDIUM
**PROVED-LIVE**

Tejas's deal room lists, as real deals:

- "Demo Brand Co" — *QA E2E — Diwali Skincare Reels* — ₹18,000
- "Influora Digital Private Limited" — *QA E2E Flow Test — Contract and Escrow* — ₹20,000 (the
  disputed one driving C2)
- "Influora Digital Private Limited" — *QA E2E Chain 2 — Deliverable to Release* — ₹15,000

Three of five deals are test fixtures. This is the same class as the brand-side "Demo Creator"
(§3.10) but far more damaging: it inflates the creator's apparent pipeline, and one fixture is
generating a false ₹20,000 payable.

### C6. Smaller creator items — LOW

| # | Finding | Evidence |
|---|---|---|
| C6.1 | §1.1 confirmed on the creator side — every deal shows `Deliverables 0/0`, `0 items`, `Usage Rights Not specified`, including an ₹18,000 `TERMS_AGREED` deal | PROVED-LIVE |
| C6.2 | "Summer Glow Serum Launch" renders as **"Negotiating ₹0"** — a live deal with zero value and no explanation | PROVED-LIVE |
| C6.3 | **"Withdraw" is enabled at ₹0 available balance.** I did not click it — initiating a withdrawal is a money movement I don't perform — so whether it errors gracefully is untested | OBSERVED |
| C6.4 | Creator greeting works ("Good evening, **Tejas**") while the brand's shows "Good evening, **there**" (§3.1) — the fix pattern already exists in `creator-dashboard.tsx:357`, which falls back through `displayName` before `firstName` | PROVED-CODE |
| C6.5 | Both `brand_token` and `creator_token` coexist in the same origin's localStorage, so one browser holds two active role sessions simultaneously. No misbehaviour observed, but role-switching bugs would be hard to reproduce | OBSERVED |

---

## 4. What is actually healthy (so effort goes to the right place)

Worth stating plainly, because "lots of broken pages" turned out **not** to be the shape of the
problem:

- **The backend is fully deployed.** I probed 64 endpoints unauthenticated: 62 returned `401`
  (route exists, auth-gated), 1 returned `200`, 1 returned `404` — and that `404` is
  `/portfolio/{handle}` correctly reporting an unknown handle. **No missing routes.**
- **FE↔BE path alignment is clean.** I wrote a gate for the brand/creator surface
  (`.proof-os/gates/fe_be_endpoints_app.py`, the sibling of the existing admin-only one) comparing
  every literal call-site in `src/lib/api.ts` against every `@*Mapping` in `influora-api`:
  **100/100 paths matched, 0 verb mismatches.** Exit 0.
- **34 of 34 routes rendered** (19 brand + 15 creator). Across both walks there were exactly
  **two** failed API calls — the §2.2 analytics 403 and the C3 scores 404 — and **zero** uncaught
  JS errors on either surface.
- **Creator empty states are genuinely good.** `/creator/analytics`, `/creator/coupons` and
  `/creator/affiliate` all explain what the user is waiting on. This is the standard the brand
  side should copy — §2.2 is the same situation handled badly.

The defects are concentrated in **projection logic** (§1.1), **response-shape drift** (§2.1),
**money aggregation** (C2), **unwired UI** (C1), **deploy drift** (C3), and
**labelling/navigation** (§3, C4) — not in missing pages or a dead backend.

> **The gap that let §1.1, §2.1 and C2 ship:** the endpoint gate proves paths and verbs, and
> explicitly declares response *body shape* as a blind spot. A hardcoded `0`, a field the server
> never sends, and a sum that forgets to exclude disputes all typecheck perfectly. Closing this
> needs a gate that compares DTO fields against the domain data they claim to project — and one
> that cross-checks money figures rendered together on a single screen.
>
> **And C3 shows the second gap:** the gate compares against backend *source*, not against what is
> *deployed*. A live smoke-probe of every endpoint would have caught it in seconds.

---

## 5. NOT TESTED

Both surfaces are now walked. What remains uncovered, and why:

**Money and outbound actions I deliberately did not trigger.** Each one moves real money or
reaches a real person:
- Wallet top-up (live Razorpay) and creator **Withdraw** (C6.3) — I don't initiate transfers
- "Send contract for signature" — notifies the real Tejas account. I opened the milestone editor
  and read it, then stopped
- KYC / Tax Identity submission — requires real GSTIN, PAN, and Aadhaar
- Opening a dispute — creates an admin-mediated case against a real counterparty

**Consequently untested:** everything downstream of escrow funding — deliverable upload, review,
revision limits, escrow release, payout, invoice and tax-document generation. These are the
stages §1.1's hardcoded `deliverablesTotal` would most affect, so that fix needs its own
verification pass once a deal is funded.

**Also not covered:** response-body shape for the ~95 endpoints other than `/wallet` and the deal
routes; visual layout and responsive behaviour (screenshots were unavailable in this environment,
so every finding here is DOM- and network-based — a purely visual break would not appear);
`/creator/onboarding` and `/creator/settings/meta/callback`, which need a fresh account and a
live Meta handshake respectively.

---

## 6. Corrections — three false positives I caught and discarded

Recording these because the same trap will catch the next audit:

1. **"Pipeline Board/List/Timeline tabs are all dead."** Wrong. Radix `TabsTrigger` does not
   respond to synthetic `.click()`; a real pointer event switches views correctly.
2. **"Review & send contract is a dead button."** Wrong. It opens a milestone editor rendered in a
   **portal outside `<main>`** with no `role="dialog"` — my snapshot only inspected `<main>` and
   `[role="dialog"]`, so it saw no change. (The missing dialog role is itself finding §3.5.)
3. **"View Contract is a dead button."** Wrong. It navigates to `/brand/contracts`; my first
   snapshot ran before the router settled. The real defect is the dead-end destination (§2.4).

Separately: `/brand/settings/verification` returns 404 live, but that route is **not in the
deployed bundle** — it exists only in uncommitted local source. That is deploy drift, not a
user-facing break, and it is not counted as a bug above. The *user-facing* consequence is §2.3.

---

## 7. Suggested order of work

| Priority | Item | Why first | Effort |
|---|---|---|---|
| **P0** | **C2** creator `pendingPayouts` excludes disputes | Overstates money owed to a creator by ₹20,000 on the highest-trust screen; two figures on one card contradict each other | Small — add the dispute filter |
| **P0** | §1.1 `DealService:1115-1117` | Blocks deadlines/SLA/reminders for **both** roles; visibly self-contradictory | Small — compute from the accepted proposal |
| **P0** | §2.1 wallet `runwayDays` | Every brand permanently sees a false "Critical" | Small — send the field or drop the widget |
| **P0** | **C5** purge QA/E2E fixtures from prod | 3 of 5 creator deals are fake, and one is generating the false ₹20,000 in C2 | Small — data |
| **P1** | **C1** mount `ConnectedAccounts` in Settings | Unblocks creator analytics **and** brand analytics (§2.2) in one change — the component already exists and the backend returns 200 | Small — one import |
| **P1** | **C3** redeploy the API | `/creator/analytics/me/scores` 404s; deployed jar is behind source | Small — deploy |
| **P1** | §2.2 analytics 403 copy + CTA | Reads as a platform bug; is a creator-connection prompt | Small — copy + button |
| **P1** | **C4** one status vocabulary | Same deal reads "Accepted" to the brand and "Negotiating" to the creator | Small — shared map |
| **P1** | §2.3 Verification in Settings | Current copy actively lies ("anytime") | Small — surface existing form |
| **P1** | §3.3 merge the two deal rooms | Users land on the one that cannot advance the deal | Medium |
| **P2** | §1.2 rename/build the Timeline view | Either give it a real time axis or call it "Progress" | Small→Medium |
| **P2** | §3.6/§3.7/§3.5 a11y names + dialog role | 16 unnamed controls; unusable by screen reader | Small |
| **P2** | §3.10 remove "Demo Creator" from prod | Trust damage on the discovery surface | Small |
| **P3** | §2.5 §2.6 §2.7 §3.1 §3.2 §3.8 C6.2 C6.3 | Polish; §3.1 is the first thing every brand user reads, and the fix already exists in `creator-dashboard.tsx:357` | Small each |

**Cheapest high-value change in the list:** C1. One import statement restores the Instagram
connect card, which is the single dependency behind both dead analytics surfaces.

---

## 8. Evidence trail

- proof-os task `live-ux-audit-0808`; ledger **F-0084** (hardcoded-projection), **F-0085**
  (fe-be-shape-mismatch), **F-0086** (confusing-authz-error), **F-0087** (deploy-drift-live-404),
  **F-0088** (unmounted-component), **F-0089** (disputed-funds-shown-as-payable)
- New oracle: `.proof-os/gates/fe_be_endpoints_app.py` — exit 0, 100/100 paths, 0 verb mismatches
- Deployed bundle: `/assets/index-DvQbcQp_.js`, `API_BASE=http://200.141.1.6/api/v1`, mode `live`
- Live objects used: brand deal `01KZ1TXGC97APN7YT0MKK0N0P1`, campaign
  `01KZ1TM6FW49H6ZC3QBXYKDP0D`, creator `01KY4YGN9MHZBEB7MY5ETVYWTK`, creator deals
  `01KY73H2HCEY0PY942G87W39JW` (₹18,000 TERMS_AGREED) and the disputed ₹20,000 QA fixture

**NOT CHECKED:** everything in §5 — money/outbound actions and the entire post-escrow half of the
lifecycle (deliverable upload → review → release → payout → invoicing); response-body shape for
the ~95 endpoints other than `/wallet` and the deal routes; visual layout and responsive behaviour
(screenshots were unavailable, so a purely visual break would not appear here); whether the fixes
for §1.1 and C2 actually render correctly once applied, since neither can be observed until a deal
is funded.
