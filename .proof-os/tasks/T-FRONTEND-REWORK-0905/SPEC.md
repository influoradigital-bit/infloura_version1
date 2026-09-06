# T-FRONTEND-REWORK-0905 — Brand & Creator Frontend Rework

**Status:** assigned, not started. **Scoped by Swapnil 2026-09-05: content fixes only, roadmap deferred** (§6). **Opened by:** Arjun (scheduler) on Priya's ruling.
**Baseline commit:** `eac5e58` (HEAD on `feat/meera-creator-phase-e`) **plus the uncommitted working tree**.
**Ruling:** Priya, 2026-09-05 — homepage prerender P0, hero replacement, bundle split, in-app IA.
**Fact sheet:** `facts/verified-state.md`. If the fact sheet and this spec disagree, the fact sheet wins and this spec has a bug to report.

Origin: Swapnil asked for a brand + creator frontend rework, removal of the rotating hero globe, and a content/layout pass with SEO involved. The audit that followed found a production defect that outranks the request.

---

## 0. Rules that apply to every step

1. **Check the build output, not the source.** Three findings in the originating audits were wrong because the auditor read code that is *supposed to* produce an outcome and reported on its intent. Any claim about what ships is made against `dist/`, never against `src/`.
2. **A producer may not write the gate that proves their own work.** Producers (`ananya`, `vikram`, `dev`) claim `believed`. Only an oracle (`meera`, `neha`) can move a claim to `proved`. This is registry law, not a preference — see `.proof-os/registry.json`.
3. **Every gate is falsified before it is trusted.** Run the gate against the current broken tree first. A gate that does not exit non-zero on today's `dist/index.html` is not a gate.
4. **F-0342 is binding.** No numeric literal in a stat-shaped constant on a marketing page. `.proof-os/gates/F-SEO-marketing-surface.sh` enforces it. No traction number without an endpoint behind it.
5. **"escrow" stays banned in user-facing copy.** Vocabulary is Secure Payments / secured funds. Identifiers, paths and admin surfaces are untouched.
6. **No claim about existing code without having opened the file.** Cite `path:line`. "Add if missing" is an unfinished sentence, not an instruction.
7. **UI Honesty (LOCKED, TECH-STACK.md).** A control is wired, absent, or disabled-with-a-reason. There is no fourth state.

---

## 1. Scope in one table

| Id | Job | Producer | Oracle / verifier | Judgment gate | Ceiling on completion |
|----|-----|----------|-------------------|---------------|----------------------|
| W1 | Delete hero globe; ship Deal Room hero | ananya | meera | kavya | proved |
| W2 | Harden the prerender gate | vikram | neha | priya | proved |
| W3 | Rebuild, verify artifact, resubmit sitemap | — | meera | aditya | proved |
| W4 | Route-level code splitting + manualChunks | ananya | meera | kavya | proved |
| W5 | Collapse brand deal surfaces; promote creator Portfolio | ananya | neha | kavya | proved |
| W6 | robots.txt Disallow + app-shell noindex | vikram | neha | aditya | proved |
| W7 | Creator portfolio — **reduced to option 2** (Disallow + delete false comment) | vikram | neha | aditya | proved |
| W8 | Homepage section reorder + copy pass | ishaan | meera | nisha | believed |
| W9 | Answer the 200-question bank — **content-answerable subset only** | priya + tejas | — | swapnil | believed |
| W10 | Missing feature pages (Meera, contracts, affiliate) | ishaan | meera | aditya | believed |
| W11 | Security pass on anything touching routing/headers | — | — | kabir | echo |
| W12 | Cost + time log | rohan | — | — | believed |

**Deferred by Swapnil, 2026-09-05 — do not open W-items for these:**

| Deferred | Surface | Why it was raised |
|---|---|---|
| Brand shortlist | `brand-discover.tsx` | Browse-then-decide flow absent |
| Filter creators by language | `brand-discover.tsx` | Regional/vernacular targeting absent |
| Invite creator by Instagram handle | `brand-discover.tsx` | Brands arrive with an offline wishlist; no path today |
| Creator availability / pause | `creator-settings.tsx` | Burnout + double-booking |
| Draft-reel pre-submit review | `creator-chat.tsx` | Revision insurance |
| "Brands who viewed my profile" | `creator-analytics.tsx` | Motivation signal |
| Media-kit PDF export | `creator-portfolio-editor.tsx` | Off-platform pitching |
| Prerendered public creator profiles | new backend endpoint | W7 option 1 — see §3 |

These stay recorded here so the next planning pass does not rediscover them. They are **not** cancelled; they are unpriced.

---

## 2. Who does which part, and why that person

| Agent | Registry kind | May claim | Assigned because |
|-------|--------------|-----------|------------------|
| **ananya** | producer | believed | Owns all React/Vite frontend. W1, W4, W5 are component and routing work in `src/`. |
| **vikram** | producer | believed | Owns backend, build scripts and technical-SEO implementation. W2 (`scripts/prerender.mjs`), W6 (`public/`), W7 (needs a backend enumeration endpoint). |
| **meera** | **oracle** | **proved** | The only agent who can run the build and assert what `dist/` actually contains. Every W that ships an artifact routes through her. This is the whole point of W2. |
| **neha** | **oracle** | **proved** | Live browser E2E. W5 needs a real click test — a dead nav control passes tsc, eslint and screenshot review (F-0341). W6/W7 need a real crawler-view check. |
| **kavya** | judgment | echo | QA gate. No frontend diff reaches an oracle without her review. |
| **kabir** | judgment | echo | W11. W6 and W7 change robots, headers and indexability. Adversarial pass before sign-off. |
| **aditya** | judgment | echo | SEO/AEO/GEO owner. Judges W3, W6, W10. **Not** trusted to self-verify build output — see §5. |
| **tejas** | judgment | echo | Owns positioning and claims. Co-owns W9; approves every claim in W8 and W10 against F-0342. |
| **nisha** | judgment | echo | Content lead. Approves W8 copy before it ships. |
| **ishaan** | producer | believed | Writes the copy for W8 and the pages in W10, to Nisha's brief. |
| **priya** | judgment | believed | Architecture rulings only. W7 needs a ruling before anyone builds. Signs off the task. |
| **rohan** | governor | believed | W12. Flags if W4 or W7 exceed budget. |
| **arjun** | scheduler | believed | This document. Pipeline status in `SHARED_CONTEXT.md`. |
| **swapnil** | root | — | Final call on W9 scope (see §6) and on W7's ruling if it costs real money. |

---

## 3. Work items

### W1 — Delete the hero globe, ship the Deal Room hero
**Producer:** ananya · **Blocks:** W3 · **Priority:** P0

Remove `<HeroGlobeGate />` from `src/pages/landing.tsx:340` and the `lazy()` import at `:39-41`. Replace with a DOM-rendered Deal Room thread: proposal → counter → contract signed → deliverable approved → payment released, cycling with Framer Motion.

Hard constraints:
- **No WebGL in the hero.** Not a smaller canvas, not a guarded one.
- Must render meaningful DOM in the static snapshot — that is the artifact crawlers read.
- Must not become the LCP element. The `<h1>` and the `data-speakable` sub-paragraph stay the primary textual signal.
- Explicit `aspect-ratio` so nothing reflows on load.
- Resting first frame must be readable with JS disabled and with `prefers-reduced-motion`.

Build it from the same components as `brand-chat.tsx` / `creator-chat.tsx` rather than a screenshot, so it cannot drift from the product it depicts.

**Rejected alternative:** moving `PaymentFlowAnimation` into the hero. It is scroll-pinned across a 300vh track (`src/components/motion/PaymentFlowAnimation.tsx:8-11`) and would freeze at its first keyframe.

**Done when:** meera confirms `dist/index.html` contains the real `<h1>` and no `three` chunk is fetched by `/`.

---

### W2 — Harden the prerender gate
**Producer:** vikram · **Blocks:** W3 · **Priority:** P0

`scripts/prerender.mjs:315` currently checks only `root.textContent.trim().length > 40`. The ErrorBoundary fallback is longer than 40 characters, so the guard passes on the exact failure it exists to catch.

Add, per route:
- assert the snapshot does **not** contain `Something went wrong`
- assert the expected `application/ld+json` block count (homepage: 5 — four `<JsonLd>` in `landing.tsx` plus `FaqSection`'s `FAQPage`)
- assert a non-empty `<h1>` whose text is not the error string
- fail the build, do not warn

**Falsification requirement (rule 3):** run this gate against the current tree *before* W1 lands. If it does not exit non-zero on today's `dist/index.html`, it is not a gate and neha rejects it.

---

### W3 — Rebuild, verify, resubmit
**Oracle:** meera · **Judge:** aditya · **Depends on:** W1, W2

Confirm `dist/index.html` carries the real `<h1>` and all five schema blocks. Then resubmit `sitemap.xml` and request reindexing of `/`. Aditya confirms the `SoftwareApplication` + `AggregateOffer` and `FAQPage` blocks are present — neither has ever reached production.

---

### W4 — Split the bundle
**Producer:** ananya · **Priority:** P1

`src/App.tsx` statically imports 73 page modules with zero `lazy()` calls. `vite.config.ts` sets no `manualChunks`. Current: `index-*.js` at 3,080 KB raw / **832 KB gzipped**, containing the admin console, shipped to every marketing visitor.

Route-level `lazy()` plus `manualChunks` separating marketing from the brand, creator and admin apps.

**Target:** marketing entry chunk under 200 KB gzipped. **Done when:** meera reports measured before/after gzip sizes.

---

### W5 — In-app IA
**Producer:** ananya · **Oracle:** neha · **Priority:** P1

Brand (`src/components/brand/brand-layout.tsx:103-124`, 13 items):
- Collapse "Deals" (`/brand/chat`) and "Messages" (`/brand/messages`) into one Deals surface with a messages filter. Retire or fold `/brand/deals` (`DealRoomDashboard`), currently orphaned from nav entirely.
- Give campaign sales tracking (`/brand/campaigns/:id/tracking`) a nav home.

Creator (`src/components/creator/creator-layout.tsx:93-112`, 12 items):
- Promote Portfolio and Profile out of the avatar dropdown (`:329-337`, `:466-472`) into the sidebar.
- Consider merging Coupons + Affiliate into one earnings surface.

**neha must click every changed control in a live browser.** A div styled as a card passes tsc, eslint, screenshot review and code review; only a click test finds it (F-0341).

**Constraint:** Phases C and D add a money timeline, delivery-proof cards, WhatsApp preferences, dispute evidence and account-health flags to the creator shell (`.proof-os/tasks/T-MEERA-CREATOR-PHASE-C/SPEC.md`, `-PHASE-D/SPEC.md`). Leave room rather than rebuild in six weeks.

---

### W6 — robots.txt and app-shell indexability
**Producer:** vikram · **Judge:** aditya + kabir · **Priority:** P1

`public/robots.txt` has **zero `Disallow` rules**. `public/_redirects:29` sends everything without a physical file to `/app-shell.html`, which carries `<meta name="robots" content="index, follow">`. So `/brand/*`, `/creator/*`, `/admin/*` and every `/:handle` are invited to be crawled and indexed, all serving the same contentless 7.8 KB shell.

The `_redirects` comment shows the team already knew about the blanket `Allow: /` and fixed the *mislabeling* half. The thin-content half is open.

Also: `/support` is prerendered and `index, follow` but excluded from the sitemap. Pick one.

Do **not** weaken the AI-crawler allowlist. `public/llms.txt` is the strongest AEO asset on this project and the permissive posture is deliberate.

---

### W7 — Creator portfolio: ruling required before build
**Ruling owner:** priya · **Then:** vikram + ananya · **Priority:** P1

`src/pages/creator-portfolio-public.tsx:232-245` sets `document.title` and injects a description meta inside a `React.useEffect`. The comment at `:232` says *"also re-rendered server-side in prod."* **There is no SSR.** `/:handle` is not in `PRERENDER_ROUTES`; it falls through to `app-shell.html`. `useEffect` never runs for a crawler. The comment is false and should be deleted whatever else is decided.

There is also no `<Seo>` component and no `Person` / `ProfilePage` schema, and no `/:handle` URL appears in `dist/sitemap.xml` (17 URLs).

Three options were put to Swapnil:
1. Prerender public profiles at build — needs a backend enumeration endpoint and a sitemap fetch. Largest upside, longest lead.
2. `Disallow` `/:handle` until option 1 ships. Cheap, honest, forfeits the surface.
3. Status quo — indexable empty shells. **Not acceptable**; this is worse than noindex.

**RULED 2026-09-05 (Swapnil): option 2.** Option 1 requires a new backend endpoint, which falls outside the content-only scope. So W7 reduces to two cheap changes:

- add `Disallow: /brand/`, `Disallow: /creator/`, `Disallow: /admin/` and a `/:handle` rule to `public/robots.txt` (folds into W6), **or** flip `dist/app-shell.html` to `noindex` — vikram picks one and says which; do not do both without checking they don't conflict
- **delete the false comment** at `creator-portfolio-public.tsx:232`. The parenthetical *"also re-rendered server-side in prod"* is untrue and would mislead the next person to open the file. This is a content fix and is in scope regardless.

**Cost of this ruling, recorded so it is not forgotten:** the public creator directory is the largest untapped organic surface this product has, and option 2 forfeits it for now rather than capturing it. It stops Google indexing empty shells, which is the urgent half. It does not build the asset. Revisit when roadmap work is priced.

---

### W8 — Homepage reorder and copy pass
**Producer:** ishaan · **Judge:** nisha, then tejas on claims

Current order: hero → TrustBar → Features → Meera → PaymentFlowAnimation → Hype → Sales tracking → Portfolios → How creators earn → Pricing → FAQ → close.

Move payment protection up behind TrustBar; move sales tracking adjacent to Hype; merge the two creator sections. Copy only — no new claims without a code-backed source.

**Read `src/components/site/proof-points.ts` before writing anything.** It records which claims are forbidden and why.

---

### W9 — The question bank
**Owners:** priya (product truth) + tejas (positioning) · **Scope decision:** swapnil

200 questions: Tejas's 100 on product/UI behaviour, Aditya's 100 on SEO/AEO/GEO. Merge into one bank — a substantial number are the same question arriving from the product side and the search side, and each answer only needs writing once.

Pre-answered by Priya on 2026-09-05, verified in source:

| Question | Answer |
|---|---|
| Exclusivity clause | **Exists** — `deal-room/proposal-form.tsx`, `deal-contract-tab.tsx` |
| Usage rights in contract | **Exists** — `usageRights`, 21 files |
| Revision cap | **Exists** — `revisionLimit`, 20 files |
| Creator availability/pause | **Absent** — roadmap |
| Draft-reel pre-submit review | **Absent** — roadmap |
| "Brands who viewed my profile" | **Absent** — and `landing.tsx` forbids claiming page-view analytics |
| Media-kit PDF export | **Not shipped** — same comment |
| Brand shortlist / language filter / invite-by-handle | **All absent** — `brand-discover.tsx` |

See §6 for the scope question Swapnil must settle.

---

### W10 — Missing feature pages
**Producer:** ishaan · **Judge:** aditya + tejas

Only three feature pages exist: `deal-room`, `secure-payments`, `hype`. Order: **Meera first**, then contracts, then affiliate/sales tracking. Each page prerenders, carries `FAQPage` + `BreadcrumbList` schema, and enters the sitemap.

`llms.txt` already points AI systems at the homepage for *"Who is Meera?"* — until W1 lands, our best AEO asset cites our most broken URL.

**Flag constraint:** `MEERA_CREATOR_ENABLED` defaults **true** (`application.yml:199`) — creator Meera is on in code; the blocker is an unpushed deploy, not a flag. `META_CREATOR_MARKETPLACE_ENABLED` defaults **false** (`:412`) — Creator Connect cannot be marketed at all.

---

## 4. Sequence

| Order | Items | Gate to proceed |
|-------|-------|-----------------|
| 1 | W2 (falsified first), then W1 | neha confirms the gate fails on the broken tree |
| 2 | W3 | meera confirms artifact; aditya confirms schema |
| 3 | W4, W6 in parallel | meera on sizes; kabir on headers |
| 4 | W7 (folds into W6 — same file, one change) | aditya confirms crawler view |
| 5 | W5 | neha live click test |
| 6 | W9 → W8 → W10 | scope ruled; triage bank before writing copy |
| — | W12 throughout | rohan |

Nothing below order 2 starts while the homepage ships an error page.

**Rohan's W7 estimate is cancelled** — option 1 was the only item needing one.

---

## 5. Standing note on audit trust

Both originating audits produced findings that did not survive verification.

- **aditya** declared the prerender infrastructure "solid" having read `scripts/prerender.mjs` and never opened `dist/`. Three further findings were wrong: Article schema *is* called (`blog/post.tsx:34`), FAQPage *is* emitted (`FaqSection.tsx:56`), BreadcrumbList *is* used in three places. A first attempt at the question set was produced with **zero tool calls** and was rejected. The rework was materially better (15 tool calls, real citations) but still asserted that `8,915 creators` and `₹4.26Cr` are live on the homepage — they were removed by F-0342 and `proof-points.ts` now reads `₹0 / On approval / Nano → macro`. He read the historical comment as current state.
- **tejas** reported `/pricing` as unbuilt (it exists, `App.tsx:707`) and reported creator Meera as flag-off (it defaults on).

Consequence for this task: `aditya` and `tejas` hold `echo` ceiling in the registry and that is correct. Neither may close a work item. Every artifact claim routes through `meera` or `neha`.

Credit where due: aditya's rework surfaced `creator-portfolio-public.tsx:232-245`, which this audit had missed by grepping only for `Seo|JsonLd`. That is W7 and it is the highest-upside item in the task.

---

## 6. Scope — RULED

**Swapnil, 2026-09-05: content fixes only. Roadmap deferred.**

Both open items are closed by that one call:

1. **Question bank (W9)** — answer only the questions whose fix is copy, layout, or surfacing something the product already does. Questions whose honest answer is "the product does not do this" get recorded in the deferred table in §1 and stop there. No W-item, no estimate, no design.
2. **W7** — option 1 needed a new backend endpoint, so it is out. Option 2 stands. See §3.

**What this ruling does not touch.** W1–W6 are not roadmap; they are repairs to things that are built and broken. The homepage P0, the prerender gate, the bundle, the nav collapse and the robots/indexability work all proceed at full scope. "Content fixes only" narrows what we *build new*, not what we *fix*.

**Standing instruction for W9.** When Priya and Tejas work the bank, every question gets one of three dispositions, and the third is a full stop:

| Disposition | Meaning | Action |
|---|---|---|
| `COPY` | Product does it; nothing says so | In scope. Route to ishaan via nisha. |
| `SURFACE` | Product does it; it is buried in the UI | In scope. Route to ananya via kavya. |
| `DEFER` | Product does not do it | Record in §1 deferred table. Stop. |

A `DEFER` is not a failure and is not an invitation to build a small version of it. The cheapest way to blow this scope is a `DEFER` that someone decides is "only a day's work."
