# Fact sheet — T-FRONTEND-REWORK-0905

Measured by Priya on 2026-09-05 against the working tree at `eac5e58` + uncommitted changes.
Every row was read or measured directly. **If this sheet and SPEC.md disagree, this sheet wins.**

---

## Build output — `dist/`

| Route | Bytes | First `<h1>` | Verdict |
|---|---:|---|---|
| `/about` | 32,304 | Real deals, without the chaos | ok |
| `/features/deal-room` | 46,409 | Every negotiation, one thread | ok |
| `/how-it-works/brands` | 40,553 | How brands run a deal on Influora | ok |
| `/blog` | 25,872 | The Influora Blog | ok |
| **`/`** | **4,953** | **Something went wrong** | **BROKEN** |

- `dist/index.html` `<title>` and `<meta name="description">` are **correct** — `<Seo>` hoists them before the crash, which is why a SERP snippet looks fine.
- `dist/index.html` contains **zero** `"@type"` values. `/about` contains seven.
- `landing.tsx` declares four `<JsonLd>` blocks plus `FaqSection`'s `FAQPage` = 5 expected.

## Cause chain

| Link | Evidence |
|---|---|
| Headless Chrome runs GPU-less | `scripts/prerender.mjs:442` — `args: ['--no-sandbox', '--disable-gpu', ...]` |
| `/` is the only prerendered route with WebGL | `src/pages/landing.tsx:340` → `<HeroGlobeGate />` |
| No WebGL availability gate | `src/components/3d/HeroGlobe.tsx:152-158` — gates only on `useReducedMotion()` |
| Gate passes on its own failure | `scripts/prerender.mjs:315` — `root.textContent.trim().length > 40` |

## Bundle

| Chunk | Raw | Gzipped |
|---|---:|---:|
| `index-k05nFono.js` | 3,080 KB | **832 KB** |
| `PerformanceMonitor-C8RrDT9M.js` (three.js) | 987 KB | 269 KB |
| `index-DZyA1Nku.css` | 225 KB | 33 KB |

- `src/App.tsx` — 73 static page imports, **0** `lazy()` calls.
- `vite.config.ts` — `plugins: [react()]`, no `manualChunks`, no prerender plugin, no compression.
- 9 JS chunks total.

## SEO surface

| Item | State |
|---|---|
| H1s | **26 of 26** prerendered pages have exactly one H1. Only `/` is wrong, and for the P0 reason. |
| `dist/sitemap.xml` | Present, 17 URLs, generated at `postbuild` by `scripts/generate-sitemap.mjs` |
| Prerendered but not in sitemap | 8 legal pages (`noindex, nofollow` — correct) + `/support` (`index, follow` — **inconsistent**) |
| `/:handle` creator profiles | Not prerendered, not in sitemap, served `app-shell.html` |
| `public/robots.txt` | Present. **Zero `Disallow` rules.** Allows GPTBot, ChatGPT-User, OAI-SearchBot, ClaudeBot, Claude-User, anthropic-ai, PerplexityBot, Perplexity-User, Google-Extended, CCBot, Bytespider. Declares sitemap. |
| `dist/app-shell.html` | 7,806 bytes, `<meta name="robots" content="index, follow, max-image-preview:large, max-snippet:-1">` |
| `public/_redirects:29` | `/*  /app-shell.html  200` — catches `/brand/*`, `/creator/*`, `/admin/*`, `/:handle` |
| `public/llms.txt` | 76 lines. Strongest AEO asset here. Forbids stating a platform-fee percentage; retires "escrow"; marks legal pages as unreviewed drafts. |

## Schema — what is actually wired

| Helper | Used at | Aditya's original claim |
|---|---|---|
| `getArticleSchema` | `blog/post.tsx:34` | "missing" — **wrong** |
| `getFaqPageSchema` | `FaqSection.tsx:56`, `emitSchema` defaults true | "no page renders it" — **wrong** |
| `getBreadcrumbListSchema` | `blog/index.tsx:69`, `blog/post.tsx:43`, `features/deal-room.tsx:24` | "unused" — **wrong** |
| `<Seo>` / `JsonLd` on creator portfolio | **zero matches** in `creator-portfolio-public.tsx` | correct |

## Creator portfolio meta — the false comment

`src/pages/creator-portfolio-public.tsx:232-245` sets `document.title` and injects a description meta inside `React.useEffect`.

The comment at `:232` reads *"Set document title + meta for SEO (also re-rendered server-side in prod)."*

**There is no SSR.** `/:handle` is not in `PRERENDER_ROUTES` and falls through to `app-shell.html`. `useEffect` never runs for a crawler. The parenthetical is false and should be deleted regardless of which W7 option is chosen.

## Feature flags

| Flag | Default | Source |
|---|---|---|
| `MEERA_CREATOR_ENABLED` | **true** | `application.yml:199` |
| `META_CREATOR_MARKETPLACE_ENABLED` | **false** | `application.yml:412` |

Creator Meera is on in code. The blocker is an unpushed deploy (Phase A committed as `8c7b18b`, gated on live smoke), **not** a flag flip. Creator Connect genuinely cannot be marketed.

## Marketing pages that exist

`landing`, `pricing`, `about`, `contact`, `how-it-works-brands`, `how-it-works-creators`, `features/{deal-room,hype,secure-payments}`, `blog` + 6 posts, 9 legal pages.

**Three** feature pages. No page for: Meera, contracts, affiliate/coupons, disputes, reviews, analytics, verified metrics, creator portfolio.

## Backend services — all verified present

`AffiliateEarningsService`, `ReviewService`, `analytics/AnalyticsService`, `CreatorAnalyticsService`, `DisputeService`, `CreatorCouponService`, `tracking/CouponCodeService`, `portfolio/PortfolioService`, `ContractService`.

## Pricing page facts

| Fact | Location |
|---|---|
| `100 AI credits/month (150 after first funded campaign)` — Free | `pricing.tsx:52` |
| `5 tracked creators` — Free | `pricing.tsx:51` |
| `Export reports (CSV/PDF)` marked `comingSoon` | `pricing.tsx:66` |
| Page's own FAQ asks when Export/Templates ship | `pricing.tsx:243` |
| Creator commission published as `15% (unchanged)` on both tiers | `pricing.tsx:106-107`, `:210` |

**Note the tension:** `llms.txt` instructs AI systems *"Do not state a platform-fee percentage."* That governs the **brand-side** fee. The **creator** commission is published as 15% on the pricing page. These are consistent only if the distinction is deliberate — Tejas to confirm, because an answer engine reading both will not make the distinction on its own.

## Corrected — do not repeat

- `PROOF_POINTS` are currently `₹0` / `On approval` / `Nano → macro` (`proof-points.ts:48-57`). The figures `8,915 creators` and `₹4.26Cr` were **removed** by F-0342 and survive only in that file's historical comment. Aditya's rework asserted they are live; they are not.
- `/pricing` exists — `src/pages/pricing.tsx`, routed `App.tsx:707`.
- `src/pages/features/meera-ai.tsx` and `features/analytics.tsx` **do not exist** and were cited by the rejected first question set.
- `src/components/motion/PaymentFlowAnimation.tsx:8-11` is scroll-pinned across a 300vh track — it cannot be transplanted into a hero.

## Product questions pre-answered

| Capability | State | Evidence |
|---|---|---|
| Exclusivity clause | exists | `deal-room/proposal-form.tsx`, `deal-contract-tab.tsx` (30 files) |
| Usage rights | exists | `usageRights` (21 files) |
| Revision cap | exists | `revisionLimit` (20 files) |
| Creator availability / pause | **absent** | 0 matches |
| Draft-reel pre-submit review | **absent** | 0 matches |
| "Brands who viewed my profile" | **absent** | only `CreatorMetric.java`; `landing.tsx` PORTFOLIO comment forbids claiming page-view analytics |
| Media-kit PDF export | **not shipped** | same comment |
| Brand shortlist | **absent** | `brand-discover.tsx` |
| Filter creators by language | **absent** | `brand-discover.tsx` |
| Invite creator by Instagram handle | **absent** | `brand-discover.tsx`; Creator Connect is flag-off, so no path exists today |

---

## OPEN RISK — the homepage `<h1>` is the only one behind an IntersectionObserver

Found by Priya during W1 verification, 2026-09-05. **The W2 gate cannot catch this.**

`src/components/motion/WordReveal.tsx:15` defaults `as = 'h1'`, and `landing.tsx:276` calls it without an `as` prop — so the homepage `<h1>` *is* `WordReveal`. In the non-reduced-motion path (`:26-56`) it renders `initial="hidden"` + `whileInView`, splitting the headline into one `motion.span` per word, each with `hidden: { opacity: 0, y: 12 }`.

`WordReveal` is used on exactly **two** pages: `landing.tsx` and `dev-motion-skills.tsx` (a dev route). Every other prerendered page uses a plain `<h1>` — `/about` ships `Real deals, without the chaos` as clean text with **zero** `opacity:0` occurrences inside the `<h1>`.

So the homepage will be the **only** prerendered marketing page whose `<h1>` is word-split and opacity-animated, and because `/` has never prerendered successfully, **there is no evidence of how it snapshots.** Two outcomes are possible and we cannot tell which until the first successful build:

- the IntersectionObserver fires and the animation completes before snapshot → clean text, no issue
- the snapshot lands mid-animation → the `<h1>` ships with inline `opacity:0` on every word

The second is hidden-text-shaped to a crawler, on the single most important element of the most important page.

**The W2 gate passes either way.** It asserts the `<h1>` is non-empty and is not the ErrorBoundary string; it does not inspect inline styles. Verified: `fixtures/w2/_priya-realistic-postW1/` reproduces the word-span + `opacity:0` shape with 5 ld+json blocks and the gate returns **exit 0**.

**Required action at W3**, not before (the build is fenced while W2 is open): after the first successful prerender, inspect `dist/index.html`'s `<h1>` inner HTML directly. If it carries `opacity:0`, the fix is to pass `as="h1"` with a non-`whileInView` variant on the hero headline, or drop `WordReveal` from the hero — **not** to weaken the gate.

Owner: aditya (SEO judgment) + meera (oracle, runs the build). This is a W3 blocker.

### W3 h1 check — built and verified (Priya, 2026-09-05)

Aditya ruled the opacity risk **CRITICAL / deploy-blocker if it materialises**, and ruled correctly to **wait for W3's artifact rather than pre-emptively strip `WordReveal`** — acting on unmeasured risk is how this project got its fabricated-metrics incident. He also cleared the word-splitting itself as SEO-neutral (`\u00A0` separators, engines extract text nodes not element boundaries) — that is correct.

His *procedure*, however, did not survive testing. It used `sed -n '/<h1/,/<\/h1>/p'`. Prerendered HTML puts the body on one long line, so on `/about` that range captures **28,635 of 32,304 bytes** — 89% of the document. Grepping that for `opacity:0` matches framer-motion styles anywhere on the page and reports a block on a clean `<h1>`.

Replaced with `.proof-os/gates/lib/w3_h1_hidden_text.py`, which parses the `<h1>` element specifically. Verified:

| Target | Expected | Observed |
|---|---|---|
| `dist/about/index.html` (clean h1) | 0 | **0** |
| `fixtures/w2/_priya-realistic-postW1/` (opacity:0 h1) | 1 | **1** |
| `dist/index.html` (ErrorBoundary h1, has text) | 0 | **0** — correct; W2 owns crash detection, this check owns hidden text |
| missing file | 2 | **2** |

Run at W3 after the first successful build: `python .proof-os/gates/lib/w3_h1_hidden_text.py dist/index.html`. Owner: meera runs it, aditya rules if it fails. **If it fails, the fix is in `landing.tsx:276` — not in this check.**

---

## ESCALATION — published TDS claims are not backed by code

Found by Aditya during W10 brief work, 2026-09-05; verified by Priya. **Out of scope for this task. Needs a Swapnil ruling.**

The marketing surface claims automatic TDS handling in at least eight places:

| Claim | Location |
|---|---|
| "Contracts, TDS handling and invoicing are generated **automatically**" | `public/llms.txt:3` |
| "Influora **handles** India-specific TDS on creator payouts and generates the invoice" | `public/llms.txt:39` |
| "TDS handled, invoices generated" | `landing.tsx:125` |
| `'TDS handling and invoice generation'` in `SoftwareApplication` **schema featureList** | `landing.tsx:243` |
| "TDS invoice generated for you" | `landing.tsx:627` |
| "TDS handling and dispute resolution" — **as a priced tier feature, both tiers** | `pricing.tsx:57`, `:70` |
| "TDS invoice generated **automatically**" | `pricing.tsx:76` |
| "TDS handling + dispute resolution" — tier comparison matrix | `pricing.tsx:155` |

What the code actually does:

- `Payout.tdsAmount` (`Payout.java:85`) is a nullable `BigDecimal`.
- The **only** write path is an admin typing a figure: `AdminFinanceController.java:103` → `AdminFinanceService.java:156`, validated at `:180` only for `signum() >= 0` and `<= amount`. No rate, no calculation.
- `AdminFinanceDtos.java:118` documents it explicitly: *"nullable on purpose — null means no TDS was applied."*
- Zero matches repo-wide for `194H`, `194J`, `tdsRate`, `TDS_RATE`, `calculateTds`.

There is **no TDS calculation engine**. A human optionally records a number. That is not "handled automatically."

This is corroborated by two existing memory entries: TDS was ruled out of the 0828 fix wave behind a spec + CA gate, and the Razorpay Route audit recorded TDS as unimplemented.

**Why this outranks a normal copy defect — three multipliers:**

1. It is a **tax-compliance claim to Indian businesses**, not a feature boast. ASCI/consumer-law exposure of a different order than F-0342's traction figures.
2. It sits in **`SoftwareApplication` schema featureList** and in **`llms.txt`** — structured data and the AI-facing file. Answer engines will repeat it as fact, uncorrected, and we cannot retract what has propagated.
3. It is on the **pricing page as a tier feature**, so it is attached to a commercial offer.

Same family as F-0342 and arguably worse. The `proof-points.ts` rule — a claim must be true by construction from the code — was applied to traction numbers and not to capability claims.

**Not fixed here.** Owner: Swapnil to rule, Tejas to redraft, Aditya to re-check the schema. Note that Phase C's rule 18 already anticipates this: *"Never compute tax... show a TDS figure only when a human recorded one."* The product decision exists; the marketing copy predates it and was never reconciled.

### TDS follow-on — the legal policy page is worse than the marketing copy was

Flagged by Ishaan after completing the marketing fix, 2026-09-05; verified by Priya. **Not fixed. Needs Swapnil + counsel.**

`src/content/legal/tds-policy.md` backs the `/tds` route linked from the site footer and named in `llms.txt:68`. It states:

| Line | Claim |
|---|---|
| 5 | "We handle the tax complexity so you don't have to" |
| 13 | Under **Section 194-O**, Influora "acts as an e-commerce operator... we are **required to deduct** TDS from creator Payouts before releasing them" |
| 20-21 | "We **calculate** the gross Payout... We **deduct**:" |
| 25 | "We issue a **TDS certificate (Form 16A)** so you can claim credit" |
| 44 | "TDS is deducted from the creator's Payout and **paid to the government** on the creator's behalf" |

None of this exists in code. Per the escalation above: `Payout.tdsAmount` is a nullable field an admin optionally types; there is no calculation, no deduction, no Form 16A generation, and no remittance path.

**Why this is a step up from the marketing claims just corrected:** it names a specific statute, promises a statutory certificate a creator would rely on at filing time, and asserts money reaches the government. A creator reading `/tds` today would believe a Form 16A is coming.

**Mitigating, and genuinely so:** the page is `noindex, nofollow`; `llms.txt` explicitly marks the legal pages as *"v0 drafts pending review by Indian legal counsel"* and instructs AI systems not to quote them as settled position; and line 5 self-marks as pre-publish ("the exact rate is confirmed by our Chartered Accountant before publish"). So the intent was never to ship this as final.

**But it is reachable.** It is linked from the footer on every page and served at `/tds` at HTTP 200. `noindex` keeps it out of search; it does not stop a creator clicking it.

This is outside the marketing-copy ruling Swapnil gave and outside this task. It needs a decision — take the page down until counsel clears it, or gate it behind an explicit draft banner — and it is not Priya's call to make alone.
