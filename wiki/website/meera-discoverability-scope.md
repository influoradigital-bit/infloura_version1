# Meera discoverability — scope (Aditya, SEO)

Scoping only. I write no source code. Every claim below cites `file:line` from a file I opened this session. Anything I could not verify from code is marked **UNVERIFIED**.

Origin: Swapnil's question-bank example — *"Can a creator get any AI help?"* The product's answer is yes. Three surfaces answer it wrong.

---

## Summary of routing

| # | Layer | Change | Owner | Blocked on |
|---|---|---|---|---|
| 0 | Pricing poll persists nothing | Wire, caption, or remove | **Vikram** (endpoint) → **Ananya** (component) | — |
| 1 | `/meera-for-creators` orphaned | Add inbound links | **Ananya** | Layer 0 + tense ruling |
| 2 | Creator nav says "Co-pilot" | Rename to "Meera" | **Ananya** | Nothing |
| 3 | `llms.txt:30` says brand-only | Reword | **Tejas** approves claim, then **Vikram**/**Ananya** applies | Phase A deploy |

**Do Layer 2 first.** It is the only one with no blocker, and it is the one that actually answers Swapnil's question for a logged-in creator.

---

## Layer 0 — the pricing poll (BLOCKER on Layer 1)

### Verified state

`src/components/site/MeeraPricingPoll.tsx`:
- `:36` `const STORAGE_KEY = 'meera-pricing-vote';`
- `:38-45` `readVote()` — reads `window.localStorage` only.
- `:47-53` `writeVote()` — writes `window.localStorage` only.
- `:62-65` `choose()` calls `writeVote(id)` then `setChosen(id)`. No `fetch`, no `api.*` call anywhere in the file (233-line page, 136-line component; both read in full).
- `:11-15` the component's own doc comment concedes it: *"There is no backend for anonymous votes yet … so the choice is kept in the browser."*
- `:93-111` the control is a `<Button>` labelled **"Choose this"** which flips to **"Your pick"** with a check icon and `aria-pressed={active}`.

### One correction to the brief

Priya's framing — *"nobody ever receives it"* — is right about the vote itself but there **is** a partial escape hatch that should be named so nobody says this scope over-claimed:

- `:68-72` after a pick, `mailHref` is built as a `mailto:` to `COMPANY.email` with the chosen price in the subject.
- `:121-132` a "Thanks" panel appears with a **"Send a one-line note"** link.

So the path is: vote (goes nowhere) → optional second click → optional third action (the visitor actually sends the mail from their own client). The vote is never transmitted. The mailto is a separate, opt-in, three-step manual action that most visitors will not take, and it carries the *price* but no structured record. **It does not make the poll wired.** It does mean the honest description is "the vote is not collected," not "there is no way for us to hear anything at all."

### Against the rule

`TECH-STACK.md:65` — *UI Honesty (LOCKED — Priya, 2026-07-30)*, `:67`: **"a control may not represent state it does not persist."** `:69` — three states, no fourth:
- `:71` **Wired** — "calls a real endpoint, persists, **reflects server truth on reload**." The poll reflects `localStorage` on reload (`:58-60`), not server truth. Not this.
- `:72` **Absent** — it is rendered. Not this.
- `:73` **Disabled + captioned** — it is enabled and uncaptioned. Not this.

`:76` bans "a `Switch` or input that holds local state and persists nothing." The poll is a button group, not a `Switch`, but it is the same defect the clause names: local state, nothing persisted, and `aria-pressed` (`:101`) announces the recorded state to assistive tech as well as sighted users. **It is in the banned fourth state.**

### Ruling: linking waits on this

`/meera-for-creators` currently gets essentially no traffic — that is the whole complaint. The moment we link it from the header, the footer, and `/how-it-works/creators`, every creator who lands there is invited to cast a vote nobody counts, under the heading `src/pages/meera-for-creators.tsx:184` *"Which plan would you pick?"* and the line at `:187-188` *"we will build around what creators choose."*

That is a promise. Driving traffic to an uncounted promise is worse than the orphan page. **Do not link until Layer 0 closes.**

### Three ways to close it — pick one, Priya/Swapnil's call, not mine

1. **Wire it (best).** A small public `POST /public/meera-pricing-vote` accepting `{ option }` with rate limiting and no PII. **Vikram** builds; **Ananya** swaps `writeVote` (`:47-53`) for the call and keeps `localStorage` only as a "you already voted" convenience. This is the only option that keeps the data. Note it needs an unauthenticated POST route, which is a new class for this API (`:12-13` — "every POST route is authenticated") and therefore needs a Kabir look before it ships.
2. **Caption it.** Keep the buttons, but reword the section so the control's honest job is *"pick one, then tell us by email"* and make the mailto the primary action, not a follow-on. Cheapest. Still slightly awkward because the button persists a "Your pick" state we do nothing with.
3. **Remove the poll**, keep the three price cards as read-only information with a single "Tell us which you'd pay for" mailto CTA. Zero honesty risk, loses the interaction.

My preference is **1**, falling back to **3**. Option 2 leaves a control that still looks like a ballot.

### Also on this page — the tense

`src/pages/meera-for-creators.tsx` is written in future tense throughout while sitting at sitemap priority `0.8` (`scripts/marketing-routes.mjs:27`):
- `:16` the file's own comment calls it a *"'coming soon' page."*
- `:103` badge: `In the works · coming soon`
- `:144` `<h2>` **"What Meera will do for you"**
- `:128` *"The real Meera will work on your deals"*
- `:209` *"Brands will know."*
- `:77` meta description ends *"Coming soon on Influora."*

Meanwhile the product is on in code: `influora-api/src/main/resources/application.yml:202` `creator-enabled: ${MEERA_CREATOR_ENABLED:true}`, and both deploy files pin it on — `deploy/hostinger/docker-compose.hostinger.yml:158` and `deploy/utho/docker-compose.utho.yml:179` both set `MEERA_CREATOR_ENABLED: "true"`.

So the page's tense is not merely cautious, it is drifting out of date, and the drift resolves the moment Phase A deploys. **The tense needs a ruling before we link.** Two coherent end-states:

- **Keep it a waitlist page.** Then it should probably drop to sitemap priority `0.6` and lose the pricing poll's ballot framing, and the header link is not justified — a footer link is.
- **Convert it to a live feature page** once Phase A deploys, matching `/features/*` in tense and structure. Then it earns the header slot and `0.8`.

This is a claims decision, so it is **Tejas's** with **Swapnil** on the pricing question. I recommend the second, sequenced after the Phase A live smoke, because a `/features/`-shaped page is what actually competes for *"AI assistant for influencers India"*-class queries; a "coming soon" page competes for nothing.

**Secondary, UNVERIFIED:** `src/components/motion/FadeUp.tsx:40-42` sets `initial={{ opacity: 0, y }}` and only animates to `opacity: 1` on `whileInView`. Every content block on `/meera-for-creators` is wrapped in `FadeUp` (`:102`, `:143`, `:182`, `:190`, `:201`). `scripts/prerender.mjs:58,485` uses real headless Chrome via `puppeteer-core`, so above-fold blocks should intersect and resolve to `opacity: 1` — but below-fold blocks (the poll section at `:182`, the promises at `:201`) may be captured at `opacity: 0` in the prerendered HTML that non-JS crawlers and AI fetchers read. **I have not opened a prerendered artifact and I was told not to run a build, so this is unverified.** The check is one command on an existing `dist/meera-for-creators/index.html` — grep it for `opacity:0` around the `h2` at `:184`. Worth doing before we drive traffic; route the check to **Meera** (build verification), not to me.

---

## Layer 1 — where `/meera-for-creators` should be linked from

### Verified state: zero inbound links

Repo-wide grep for `meera-for-creators` (excluding `node_modules`, `dist`) returns exactly seven hits, and **not one is a link**:

| File:line | What it is |
|---|---|
| `scripts/marketing-routes.mjs:27` | sitemap entry, `priority: '0.8'` |
| `src/App.tsx:52` | import |
| `src/App.tsx:714` | route definition |
| `src/components/site/MeeraDemoPlayer.tsx:15` | a code comment naming the page |
| `src/pages/meera-for-creators.tsx:78` | its own `canonical` |
| `src/pages/meera-for-creators.tsx:85` | its own schema `url` |
| `src/pages/meera-for-creators.tsx:91` | its own breadcrumb self-reference |

Confirmed absent from the two nav sources:
- `src/components/site/SiteHeader.tsx:31-46` — three arrays, `HOW_IT_WORKS_LINKS`, `FEATURES_LINKS`, `TOP_LINKS`. Nine links. None is `/meera-for-creators`.
- `src/components/site/SiteFooter.tsx:16-55` — `FOOTER_NAV`, four groups (Product / Resources / Legal / Company), sixteen links. None is `/meera-for-creators`.
- `src/pages/how-it-works-creators.tsx:117-155` — the cross-link section holds exactly two cards, `/features/hype` (`:132`) and `/tds` (`:148`). No third.

An indexable page at priority 0.8 with zero internal inbound links gets crawled (it is in the sitemap) but receives no internal link equity and is invisible to any human navigating the site. It is also invisible to an AI crawler doing link-following discovery rather than sitemap ingestion.

### Ruling: yes, link it — but not yet, and not from the header first

**Not at all in its current state.** Sequence: close Layer 0, get the tense ruling, *then* link. Linking a live "coming soon" page with an uncounted ballot on it multiplies the exposure of both defects.

**Once unblocked, three placements in priority order:**

**1. `/how-it-works/creators` cross-link card — do this one first.** This is the highest-intent placement on the site: the reader is already a creator evaluating Influora, and *"can I get AI help"* is a question they are actively holding. It is also the exact page an answer engine reads when asked about the creator side.

- File: `src/pages/how-it-works-creators.tsx`, the section at `:117-155`.
- Change: the grid at `:119` is `sm:grid-cols-2` with two `FadeUp` cards. Add a third card and change to `sm:grid-cols-3`, or keep two columns and let it wrap — **Ananya's** layout call, not mine.
- Copy the exact pattern of the TDS card at `:138-153`: `<Badge variant="outline">`, `<h3 className="mt-3 font-semibold">`, a `<p>`, then a `<Link to="/meera-for-creators">` styled as at `:145-151`.
- Anchor text matters for SEO. Use **"Meera for Creators"** in the link, not "Learn more" — the link text is the strongest on-page signal we control for that URL, and it is currently zero.
- Suggested heading: *"Want help pricing a brief?"* — matches the search intent, not the feature name.

**2. Footer — `FOOTER_NAV` Product group.** Site-wide, cheap, no design debate, and it gives the URL a link from every prerendered page at once, which is the fastest way to fix the equity problem.

- File: `src/components/site/SiteFooter.tsx`, the `Product` group array at `:19-27`.
- Add `{ label: 'Meera for Creators', href: '/meera-for-creators' },` — place it after the `How It Works — Creators` entry at `:21`, so the two creator-side entries sit together.

**3. Header — `FEATURES_LINKS`. Only after the page converts to a live feature page.**

- File: `src/components/site/SiteHeader.tsx`, `FEATURES_LINKS` at `:36-40`.
- Add `{ label: 'Meera', href: '/meera-for-creators' },`.
- **Argument for waiting:** the "Features" dropdown currently holds three shipped, present-tense pages (`/features/secure-payments`, `/features/deal-room`, `/features/hype`). Dropping a "coming soon" page in beside them makes the whole menu less trustworthy — a visitor who clicks one of four and gets a waitlist starts discounting the other three. The header is our highest-authority internal link surface and it should list shipped things.
- **Argument against waiting:** Meera is arguably the most differentiated thing Influora has, and it is the only major surface with no header presence at all. That is a real cost.
- I come down on waiting, because the cost is temporary and the trust damage to a four-item menu is not. The footer link (2) closes the equity gap in the meantime.

**Note on the URL itself:** if the page becomes a live feature page, the natural URL is `/features/meera`. **Do not rename it.** `/meera-for-creators` is already in `sitemap.xml` via `marketing-routes.mjs:27` and has been crawlable; a rename costs a 301 and a re-crawl for no ranking gain, and the existing precedent in this repo is exactly that lesson — `src/App.tsx:716-724` documents the `/features/escrow` → `/features/secure-payments` rename and the redirect machinery it forced. One inconsistent URL is cheaper than a second migration. If someone insists, it needs a `public/_redirects` entry plus the in-app `<Navigate>` twin, same as `App.tsx:725`.

**Owner: Ananya** for all three. No backend involved. Route through **Arjun** for pipeline order.

---

## Layer 2 — should the creator nav label become "Meera"?

### Verified state

- `src/components/brand/brand-layout.tsx:122` — `{ label: 'Meera', href: '/brand/meera', icon: Sparkles },`
- `src/components/creator/creator-layout.tsx:134` — `{ label: 'Co-pilot', href: '/creator/copilot', icon: Sparkles },`

Same `Sparkles` icon, same `Main` nav group, same product. Swapnil confirmed directly that the backend, persona, consent gate and language settings are Meera's, and that "Co-pilot" is only the tab label and page heading.

The page underneath already disagrees with its own tab. `src/pages/creator-copilot.tsx`:
- `:121` `<h1 className="text-2xl font-bold">Co-pilot</h1>`
- `:134` `<CardTitle className="text-base">Meera</CardTitle>`
- `:139` *"Meera for creators isn't available on your account yet."*
- `:148` `<CardTitle className="text-base">Talk to Meera</CardTitle>`
- `:172` button label `'Open Meera'`
- `:104` error copy: *"Couldn't reach Meera — try again."*

So a creator clicks **Co-pilot**, lands on a page headed **Co-pilot**, and every functional control beneath it says **Meera**. That is one product with two names inside a single viewport.

### The argument for keeping "Co-pilot" — taken seriously

It is not obviously wrong. A brand and a creator arrive with different knowledge:

- A brand meets Meera during onboarding and in marketing — `src/pages/landing.tsx:137` carries a `MEERA` block on the homepage, and `public/llms.txt:30` describes her as a brand-workspace feature. By the time a brand sees the nav item, "Meera" is a known name.
- A creator meets nothing. `/meera-for-creators` is orphaned (Layer 1), so the creator-side introduction to the name does not exist in the navigable site. A creator's first encounter with "Meera" is a bare proper noun in a sidebar — a name with no referent. "Co-pilot" at least describes a function.

That is a genuine recognition argument, and it is why I would not have called this a defect if the label stood alone.

### Ruling: rename to "Meera" — but the argument is about consistency, not clarity

Three reasons the recognition argument loses:

1. **The label is already contradicted one click later.** The recognition benefit of "Co-pilot" lasts exactly until the page renders, at which point `:134`, `:148` and `:172` all say Meera and the creator has to reconcile two names anyway. We are not sparing them the unfamiliar name; we are making them learn it in a worse place — after a click, from a card title, instead of from the nav item they chose. A descriptive label that stops describing the thing on arrival is not clarity, it is a handoff cost.

2. **The brand side already made the opposite call and it is the one that scales.** `brand-layout.tsx:122` chose the proper noun. Two names for one product means two support vocabularies, two sets of help docs, and — the part I care about — a diluted entity. Search engines and answer engines build an entity for "Meera" from every mention we make. Splitting half of them into "Co-pilot," a generic industry term we cannot own and that a dozen competitors use, throws away the half of the signal that comes from the creator side. "Meera" is brandable and unambiguous; "Co-pilot" is neither.

3. **Swapnil has already ruled on the substance.** He stated Co-pilot *is* Meera. A label that contradicts a stated product fact is the same class of problem as `llms.txt:30` in Layer 3 — an internal surface asserting something the product does not do.

**Mitigate the recognition cost rather than paying it forever.** Two cheap fixes, both **Ananya**:
- Keep a functional descriptor next to the name where there is room. `creator-copilot.tsx:121` `<h1>Co-pilot</h1>` → `<h1>Meera</h1>` with a subtitle line underneath along the lines of *"Your AI co-pilot — reads briefs, suggests rates, drafts replies."* This keeps the descriptive word doing its job in the one place a first-time creator will actually read a sentence, and it gives the page an `<h1>` that matches the entity we want to rank. (Copy is **Nisha/Ishaan's** to finalise — I am specifying the structure, not the wording.)
- Fixing Layer 1 fixes most of this on its own: once `/how-it-works/creators` links to `/meera-for-creators`, a creator can meet the name before they ever see the sidebar.

### Exact changes

| File:line | From | To | Owner |
|---|---|---|---|
| `src/components/creator/creator-layout.tsx:134` | `label: 'Co-pilot'` | `label: 'Meera'` | Ananya |
| `src/pages/creator-copilot.tsx:121` | `<h1 …>Co-pilot</h1>` | `<h1 …>Meera</h1>` + descriptor subtitle | Ananya, copy from Nisha |

**Leave the route `/creator/copilot` alone.** It is an authenticated app route, not indexable, so there is no SEO value in renaming it and a rename means a redirect plus every bookmark breaking. Same reasoning as the URL note in Layer 1. `creator-layout.tsx:134`'s `href` and `creator-copilot.tsx:22`'s route comment stay as they are.

**Also check before shipping:** `src/components/creator/copilot/CopilotPreviewCard.tsx` and `src/components/creator/copilot/DailySuggestionSection.tsx` (imported at `creator-copilot.tsx:5-6`) — **UNVERIFIED**, I did not open either. If they carry user-visible "Co-pilot" strings they should change with the rest. **Ananya** to check as part of the same ticket. Directory and filenames stay — renaming those is churn with no user-visible benefit.

---

## Layer 3 — `llms.txt` rewording (draft only, NOT applied)

### Verified state

`public/llms.txt`, the "Who is Meera?" answer:

> **Who is Meera?**
> Meera is Influora's built-in AI campaign co-pilot, included in every brand workspace. She suggests matching creators, works out a budget and per-reel rate from a stated goal, and drafts the campaign. She proposes; a human confirms every step that moves money. (https://influora.in/)

The whole answer is brand-only, and `llms.txt:7-9` sets the contract for this file: *"Written to be quoted standing alone. Each one matches the copy rendered at the URL given."* An answer engine asked Swapnil's question — *"can a creator get AI help on Influora?"* — reads that block, finds the creator case excluded by the phrase "every brand workspace," and answers **no**. It will do this even though `application.yml:202` defaults `MEERA_CREATOR_ENABLED` to `true` and both deploy compose files pin it on (`docker-compose.hostinger.yml:158`, `docker-compose.utho.yml:179`).

### Constraints I am writing to

- **Phase A is committed but unpushed** and gated on a live smoke test. So creator Meera is on in code and **not yet deployed**. `llms.txt` describes the live site; it must not claim a deployed capability before the smoke passes.
- **`META_CREATOR_MARKETPLACE_ENABLED` defaults `false`** — `application.yml:425` `enabled: ${META_CREATOR_MARKETPLACE_ENABLED:false}`, and both compose files default it false (`docker-compose.hostinger.yml:219`, `docker-compose.utho.yml:248`). **Creator Connect / the creator marketplace cannot be mentioned at all.**
- The line's own `llms.txt:7-9` rule: the answer must match copy actually rendered at the cited URL. That constrains which URL we can cite.

### Draft — do not apply

Two variants, because the right one depends on deploy state. Both keep the existing structure, keep the "she proposes, a human confirms" safety line, and add nothing about the marketplace.

**Variant A — for now, before Phase A deploys.** Honest about the split without claiming a live creator feature:

> **Who is Meera?**
> Meera is Influora's built-in AI co-pilot. On the brand side she is included in every brand workspace: she suggests matching creators, works out a budget and per-reel rate from a stated goal, and drafts the campaign. A creator-side Meera — reading brand briefs, suggesting what to charge, and drafting replies — is in development and not yet available to creators. In both cases Meera proposes; a human confirms every step that moves money. (https://influora.in/)

**Variant B — after Phase A deploys and the live smoke passes.** Answers Swapnil's question with a clean yes:

> **Who is Meera?**
> Meera is Influora's built-in AI co-pilot, and both sides of the platform get her. A brand gets her in every workspace: she suggests matching creators, works out a budget and per-reel rate from a stated goal, and drafts the campaign. A creator gets her to read an incoming brand brief, suggest what to charge for it, and draft the reply — the creator approves every message before it sends. Meera proposes; a human confirms every step that moves money. (https://influora.in/)

**Notes on the drafts:**
- Both drop *"included in every brand workspace"* as the sentence's main clause — that phrase is the whole problem, because it reads as a scope limit rather than a brand-side fact. Variant A keeps the words but scopes them explicitly to the brand side.
- Variant A's *"in development and not yet available"* is the phrasing that matches `/meera-for-creators`'s own copy (`src/pages/meera-for-creators.tsx:103` badge *"In the works · coming soon"*), which satisfies the `llms.txt:7-9` "matches the copy rendered at the URL" rule.
- Neither draft mentions credits, pricing, or the ₹899/₹999/₹1,499 options from `MeeraPricingPoll.tsx:30-34`. Those are an unresolved poll, not a published price. Keeping them out of `llms.txt` is deliberate.
- Variant B's *"the creator approves every message before it sends"* is verifiable in the shipped product — `creator-copilot.tsx:47-53` gates on a DPDP consent screen, and `meera-for-creators.tsx:113` states *"The send button is always yours."* It is a claim we can stand behind.
- **When Variant B lands**, `/meera-for-creators` should also be added to the `## Key pages` list in `llms.txt` (the block currently running `Homepage` through `Contact`), described as the creator-side Meera page. Not before — pointing an AI crawler at a "coming soon" page is the same mistake as linking it in the header.

### Owner

**Tejas owns the claim.** He decides which variant, and when A flips to B. Nothing in `public/llms.txt` changes until he signs off — this is a public statement about what the product does, which is his call, not mine and not engineering's.

Once approved, the edit itself is one paragraph in `public/llms.txt` and can go to **Vikram** or **Ananya** with the approved text pasted in. **I have not touched the file.**

---

## What I could not verify

- Prerendered `opacity: 0` on below-fold `FadeUp` blocks on `/meera-for-creators` — mechanism cited (`FadeUp.tsx:40-42`, `prerender.mjs:58,485`), outcome **UNVERIFIED**, needs a look at a built artifact. Route to **Meera**.
- Whether `CopilotPreviewCard.tsx` / `DailySuggestionSection.tsx` carry user-visible "Co-pilot" strings — **UNVERIFIED**, not opened. Route to **Ananya** with the Layer 2 ticket.
- Whether a live `sitemap.xml` currently served in production already lists `/meera-for-creators` — I read the generator source (`marketing-routes.mjs:27`), not a deployed artifact. **UNVERIFIED** as to production.
