# First-run dashboard guidance — why new users get lost, and the fix

**Date:** 2026-08-19 · **Raised by:** Swapnil (via Tejas)
**Status:** **ALL FOUR LAYERS BUILT** (see §6). One action remaining: supply the walkthrough video URL.
**Problem as stated:** "Tejas & Swapnil, first-time users — when they come to the dashboard they get confused. We made a video, we still have to guide them through the flow."

---

## 1 · What a first-time user actually sees today (read from source, not assumed)

### Brand — `/brand/dashboard` (`src/components/brand/dashboard/dashboard-page.tsx`)

| Region | What renders on a brand-new account |
|---|---|
| Header | "Good morning, {name}" + "Your brand workspace is ready — create your first campaign" + `New Campaign` button |
| Hero card | "Requires Your Action · 0 pending" → empty state "Ready to launch your first campaign?" |
| Pipeline card | "No deals in your pipeline yet." + an empty 0%-width segment bar |
| Wallet card | ₹0 available · ₹0 in escrow · runway `—` · badge **"Healthy"** |
| Trend-Spark | renders nothing |
| Left nav | **12 destinations** — Home, Meera, Campaigns, Creators, Deals, Messages, Wallet, Pipeline, Contracts, Analytics, Reviews, Disputes |

### Creator — `/creator/dashboard` (`src/pages/creator-dashboard.tsx`)

Empty state "No deals yet — that's normal" with two working CTAs (Explore campaigns / Complete profile), plus **11 nav destinations**.

### The last screen before the dashboard — brand onboarding step 3 (`src/pages/brand-onboarding.tsx`)

"You're in, {name} … **Pick where to go first:**" followed by two cards — *Create your first campaign* and *Discover creators*.

---

## 2 · Root causes (five, all verifiable in the code)

**RC-1 — the "pick where to go first" cards are dead.**
`NextActionCard` (brand-onboarding.tsx:214–247) is a plain `<div>`. No `onClick`, no `href`, no `navigate`. The screen literally instructs the user to pick a destination and then nothing is clickable except "Go to dashboard". The single strongest guidance moment in the product is inert. *This is a bug, not a design gap.*

**RC-2 — the dashboard answers "what is pending?", never "what do I do first?".**
Every region is a *state readout* of a lifecycle that has not started. On a brand-new account all four regions correctly report "nothing" — four separate ways of saying zero, which reads as a broken or empty product rather than a starting point.

**RC-3 — the canonical flow already exists but is only on the marketing site.**
`how-it-works-brands.tsx` and `how-it-works-creators.tsx` each hold a complete, well-written 6-step flow (`STEPS`). `/brand/help` holds 5 more sections. **None of it is reachable from inside the dashboard.** The explanation the user needs is written, approved, and shown only to logged-out visitors.

**RC-4 — 12 nav items with no sequence.**
Nothing in the shell signals that Campaigns → Creators → Deals → Contracts → Wallet is an *order*. A first-timer reads it as twelve equal doors.

**RC-5 — the video is not in the product.**
Nothing in the repo references a video, Loom, YouTube embed, or `<video>` for onboarding. Whatever was recorded lives outside the app, so it reaches nobody who is already logged in and confused.

> **The core insight:** the guidance content is not missing. It is written, approved and shipped — to the wrong audience, at the wrong moment. The fix is mostly *routing existing content to the logged-in first-run moment*, not authoring new material.

---

## 3 · The solution — four layers, in priority order

### Layer 0 — fix the dead cards (bug, ~15 min)

Make `NextActionCard` a real `<button>`/`<Link>`. "Create your first campaign" → completes onboarding then routes to `/brand/campaigns/new`; "Discover creators" → `/brand/discover`. Add the same terminal step to creator onboarding.
*Ship this regardless of everything below.*

### Layer 1 — a persistent, ordered First-Run Checklist on the dashboard (the main fix)

A dismissible card that **replaces** the four separate empty states while the account is genuinely new (`isGenuinelyEmpty` already computes exactly this condition — dashboard-page.tsx:120–128). Each row is derived from real state, never a local flag, so it self-completes as the user works.

**Brand — 5 steps:**

1. Create your first campaign → `/brand/campaigns/new` *(done when a campaign exists)*
2. Invite or discover creators → `/brand/discover` *(done when a proposal is sent)*
3. Agree terms in the Deal Room → `/brand/chat` *(done when a deal reaches Negotiating)*
4. Sign the contract & fund it → `/brand/contracts` *(done when a contract is signed)*
5. Approve the work — payment releases *(done on first release)*

**Creator — 4 steps:**

1. Complete your profile & rate card → `/creator/profile`
2. Connect Instagram → `/creator/settings`
3. Browse open campaigns / accept a Hype slot → `/creator/campaigns`
4. Accept a contract & submit your first deliverable → `/creator/deals`

Rules: progress bar "2 of 5"; only the **next** step carries the primary CTA (the rest stay quiet); dismissible, with a "bring it back" affordance in Help; auto-hides permanently once all steps complete.

### Layer 2 — the flow map, inside the app

Add `/brand/how-it-works` and `/creator/how-it-works` **inside the authenticated shell**, rendering the *same* `STEPS` arrays already exported by the marketing pages — single source of truth, no re-authoring, no drift. Link it from:

- the checklist card footer ("See the full flow"),
- the existing `/brand/help` page,
- a persistent `?` item in the nav's Manage group.

### Layer 3 — put the video where the confusion happens

Embed the existing video at the top of the in-app how-it-works page and as a "Watch the 2-min walkthrough" link in the checklist card. A video nobody can reach from a confused state is not a guidance surface.

### Also fix while in here

The wallet card shows **₹0 with a green "Healthy" badge** on a new account. Technically correct (`runwayDays === null` → healthy, per F-0099/F-0103) but it reads as "you're funded" to someone who has not paid anything. On `isGenuinelyEmpty`, show "Not funded yet — fund when your first deal is accepted" instead of a health badge.

---

## 4 · What NOT to do

- **No modal tour / spotlight overlay on first load.** Coach-marks fire once, get dismissed reflexively, and cannot be recovered — exactly the pattern that fails a user who returns confused on day 3. A persistent checklist is recoverable; a tour is not.
- **No new copy.** Everything needed is already in `STEPS` and `/brand/help`.
- **No third-party tour library** (driver.js / Joyride / Intro.js). None is installed today; the checklist is ~200 lines of the project's own components.

---

## 5 · Effort

| Layer | Scope | Est. |
|---|---|---|
| 0 — dead cards | 1 file (+1 creator equivalent) | 15 min |
| 1 — checklist | new component + wire into both dashboards | ~4 h |
| 2 — in-app flow map | 2 routes reusing exported `STEPS` | ~2 h |
| 3 — video embed | 1 component | ~30 min |
| wallet zero-state | 1 card branch | ~20 min |

---

## 6 · What shipped (L0 + L1, 2026-08-19)

**L0 — dead controls, fixed.** `NextActionCard` is a real `<button>` whose `onSelect` prop is **required**, so a future call site cannot ship inert. "Create your first campaign" → `/brand/campaigns/new`, "Discover creators" → `/brand/discover`; both complete onboarding first. `handleComplete` now takes the destination instead of hardcoding the dashboard. Same treatment for creator onboarding's "Check your Deals".

> A second defect surfaced while wiring this: `onClick={onComplete}` in creator onboarding would have passed the **MouseEvent** into `navigate()` once `onComplete` took an argument. Type-checks fine, breaks at runtime. Fixed and pinned by test.

**L1 — First-Run Checklist**, three new modules:

| File | Role |
|---|---|
| [FirstRunChecklist.tsx](src/components/shared/FirstRunChecklist.tsx) | Presentation + the honesty rules |
| [BrandFirstRunChecklist.tsx](src/components/brand/dashboard/BrandFirstRunChecklist.tsx) | 5 brand steps, derived from pipeline + wallet + campaign count |
| [CreatorFirstRunChecklist.tsx](src/components/creator/CreatorFirstRunChecklist.tsx) | 4 creator steps, derived from portfolio + Meta status + deals |
| [brand-pipeline-progress.ts](src/lib/brand-pipeline-progress.ts) | `countAtOrBeyond` — cumulative stage counting |

Three rules the code enforces, each with a test:

1. **`done` is derived from account state, never a stored flag.** Only the user's *dismissal* is persisted — that is their input, not a claim about their account.
2. **`done: null` means "could not be determined" and never renders as done.** It is also excluded from the "N of M" denominator and named explicitly ("2 couldn't be checked"). Defaulting to `false` reports a fraction the data does not support; defaulting to `true` congratulates the user for work they may not have done.
3. **Counting is cumulative.** A `Contracted` deal ticks "Agree the terms" too. Exact-bucket counting would un-tick a step at the moment the user succeeded at it — the ladder would appear to go backwards as they made progress.

**Wallet zero-state**, also fixed: badge reads "Not funded yet" (not green "Healthy"), subtitle "Fund this when your first deal is accepted", progress bar 0 (not full), CTA "Add funds". The `walletRunwayHealth` value itself is untouched — F-0099/F-0103 still govern everything downstream; only the label changed, and only on a confirmed real zero.

### Verification

- `npx tsc --noEmit` → 0 errors · `npx eslint` on new files → 0 errors, 0 warnings · `npm run build` → clean, 19/19 routes prerendered
- **23 new tests**, 155/155 passing across 37 affected files
- Gate `.proof-os/gates/F-0341-first-run-guidance.sh` → **exit 0**, and **falsified**: reverted to the `<div>`, gate exits 1; restored, exits 0
- **Live in the browser**, both dashboards: brand read "3 of 5" with steps 1–3 ticked from a 1-campaign / 2-Outreach / 1-Negotiating fixture and the CTA on step 4; creator read "2 of 4". With the backend down, every step reported "Status unknown · 5 couldn't be checked" and nothing was falsely ticked or crashed.

**One safety property added in the process:** the checklist is the only widget on the brand dashboard that makes its own network call. Its request is wrapped so a *synchronous* throw (missing/renamed client) degrades to "undeterminable" rather than white-screening the page. This was found for real — the widget crashed 6 existing dashboard tests whose mocks lacked `api.campaigns`.

### L2 — the flow, inside the app

The six steps now live in one place, [`src/content/how-it-works-steps.ts`](src/content/how-it-works-steps.ts), rendered by **four** surfaces: the two public marketing pages and two new in-app routes, `/brand/how-it-works` and `/creator/how-it-works`. Not a copy — the public pages' `HowTo` JSON-LD is still built from the same objects the page renders, so the in-app explanation and the machine-readable public one cannot drift apart.

Reachable three ways, so it survives the checklist being dismissed: a **persistent "How it works" item** in both nav shells (Manage group), a **"See the full flow"** card on `/brand/help`, and the checklist footer. The creator checklist previously pointed at `/how-it-works/creators` — a *logged-out* page that drops a signed-in creator out of the app shell mid-onboarding; it now stays in-app.

> **The extraction is proven content-neutral.** Prerendered `/how-it-works/brands` and `/how-it-works/creators` were captured before the refactor and diffed after: **0 differing lines**, JSON-LD included. The gate re-asserts that both pages still render from the shared module, so a future edit cannot silently rewrite the public schema.

### L3 — the video, where the confusion is

[`WalkthroughVideo`](src/components/shared/WalkthroughVideo.tsx) sits at the top of both in-app flow pages, and the checklist footer changes to *"Watch the walkthrough, or read the steps"* when one is configured.

**The URL is configuration, not code** — set per role in `.env.local` (documented in `.env.local.example`):

```
VITE_WALKTHROUGH_VIDEO_BRAND_URL=https://www.youtube.com/watch?v=...
VITE_WALKTHROUGH_VIDEO_CREATOR_URL=https://www.youtube.com/watch?v=...
```

With nothing set, every consumer renders nothing — no empty player, no "coming soon" box, no dead link promising a video that will not play.

**The URL goes through an allowlist** ([`walkthrough-video.ts`](src/lib/walkthrough-video.ts)): `https:` only, YouTube/Vimeo, or a media file on `*.influora.in`. This value is typed by hand into a `.env` per deploy — a typo or a stale paste piped straight into an `<iframe src>` turns a help page into a frame for content nobody here chose. A property test written as *"the returned src always points at an origin we chose"* caught a real hole in the first version of this allowlist: it gated the `<video>` branch on the file extension alone, so `https://anywhere.example.com/x.mp4` passed. Now gated on host **and** extension.

### Verification (L0–L3 combined)

- `tsc` 0 errors · `eslint` 0 errors, 0 warnings on all new files · `npm run build` clean, 19/19 routes prerendered
- **58 new tests**; **433/433 passing** across 74 affected files
- Gate [F-0341-first-run-guidance.sh](.proof-os/gates/F-0341-first-run-guidance.sh) → exit 0. **Falsified three ways**, each restored after: the inert `<div>` → exit 1; a re-introduced local `STEPS` copy → exit 1; the nav entry removed → exit 1
- Prerender diff vs pre-refactor baseline → **0 differing lines** on both marketing pages
- **Live in the browser:** `/brand/how-it-works` and `/creator/how-it-works` each render all six steps in the authenticated shell with "How it works" in the sidebar; the checklist footer navigates in-app. With a URL temporarily configured, the page embedded `https://www.youtube.com/embed/…` (watch URL correctly converted) with `loading="lazy"`, `referrerpolicy="strict-origin-when-cross-origin"` and fullscreen allowed, and the footer flipped to "Watch the walkthrough". `.env.local` was restored byte-identical afterwards.

---

## 7 · Not checked

- Whether Tejas and Swapnil's confusion is the *empty dashboard* specifically, or an earlier step (signup, KYC, wallet funding). No session recording or user interview backs this doc — the root causes are read from source, and they explain the reported symptom, but they are not proven to be the ones that actually confused those two people.
- Whether a checklist measurably raises first-campaign completion. That needs an activation funnel, which is not instrumented today.
- Where the recorded video lives (it is not in this repo).
