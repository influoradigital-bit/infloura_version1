# Influora.in Website Rebuild — Master Index

> **CEO Directive:** 2026-07-13
> **Domain:** influora.in
> **Goal:** Premium, conversion-focused, GEO-optimized website

---

## Phase Status

| Phase | Status | Owner | Deliverables |
|-------|--------|-------|--------------|
| **Phase 1: Strategy** | ✅ DONE + CEO APPROVED | Tejas/Nisha/Aditya/Ishaan | personas, keywords, content-map, copy |
| **Phase 2: Design** | ⏩ FOLDED into build | — | Design direction locked in CEO-DECISIONS.md; reuse existing design system |
| **Phase 3: Build** | 🟡 IN PROGRESS | Ananya/Vikram/Ishaan | homepage, blog infra, SEO/GEO tech, blog posts |
| **Phase 4: QA/Launch** | ⬜ WAITING | Kavya/Meera | testing, deploy |

---

## Phase 1 Deliverables — ✅ ALL APPROVED (see CEO-DECISIONS.md)

| Document | Owner | Status |
|----------|-------|--------|
| [personas.md](personas.md) | Tejas | ✅ 6 personas, India-specific |
| [keywords.md](keywords.md) | Aditya | ✅ 9 GEO clusters + schema plan |
| [content-map.md](content-map.md) | Nisha | ✅ 35 pages mapped |
| [homepage-copy.md](homepage-copy.md) | Ishaan | ✅ full homepage copy |
| [CEO-DECISIONS.md](CEO-DECISIONS.md) | Swapnil | ✅ blockers resolved, scope locked |

---

## Phase 3 Build — TIER 1 ✅ DONE + LIVE-VERIFIED

| Task | Owner | Status |
|------|-------|--------|
| Homepage enhancement (3D/motion/copy/CTAs/testimonials/footer) | Ananya | ✅ verified live |
| Blog infrastructure (markdown loader, index, post, category, TOC) | Ananya | ✅ verified live |
| SEO/GEO tech foundation (index.html, robots, llms.txt, sitemap, schema, `<Seo/>`) | Vikram | ✅ verified live |
| 3 launch blog posts (markdown) | Ishaan | ✅ rendering live |

**Live verification (Swapnil, in-app browser on localhost:3000):**
- Homepage: hero word-reveal, new 3-step "How a deal happens", sharpened GEO copy, anonymized testimonials, expanded footer, Organization/WebSite JSON-LD ✅
- Blog: `/blog` index (3 posts, category filters), `/blog/:slug` (breadcrumb, auto-TOC, Quick Answer box, markdown tables/lists, Article JSON-LD) ✅
- SEO: real `<title>` per page, single correct canonical per page, robots/llms.txt/sitemap serving ✅
- `vite build` green (3997 modules); source `tsc --noEmit` clean (0 errors in our code)

**2 defects found in live verify → FIXED by Swapnil + re-verified:**
1. Hero `CountUp` stats stuck at `0`/`₹0.0Cr+` (in-view observer never fired in embedded browser; pre-existing component behavior) → added on-screen safety-net fallback in `src/components/motion/CountUp.tsx`; now shows `8,915+` / `₹4.3Cr+`.
2. Double `<link rel=canonical>` (static `index.html` root canonical bled onto every sub-page) → removed static canonical; each page now sets its own via `<Seo/>`. Verified single correct canonical on `/` and `/blog/:slug`.

---

## Phase 3 Build — TIER 2 ✅ DONE + LIVE-VERIFIED (Ananya, 2026-07-13)

7 marketing pages built to `/impeccable` standard, all registered before `/:handle`:
`/features/escrow` (FAQPage schema) · `/features/hype` · `/features/deal-room` · `/how-it-works/brands` · `/how-it-works/creators` · `/pricing` (FAQPage schema, "0% to start", no hard %) · `/about` (no fake logos).

- Extracted shared `SiteHeader`/`SiteFooter` (DRY nav/footer), swapped homepage to real `<Seo/>`.
- **Bug fixed:** leftover static `description`/`og:*`/`twitter:*` in `index.html` produced duplicate/wrong meta on every `<Seo/>` sub-page (React 19 appends managed meta after static). Removed page-varying static tags; kept only page-invariant fallbacks.
- **Live-verified (Swapnil):** `/features/escrow` → single canonical + single description + FAQPage/BreadcrumbList JSON-LD ✅. `/pricing` → correct copy, 5-Q FAQ accordion, working nav dropdowns ✅.
- `vite build` green (4008 modules); source `tsc` clean.

---

## POLICY TRACK — Influora Digital Private Limited (2026-07-13)

- `wiki/website/policy-list.md` (Swapnil) — full policy inventory: 7 mandatory public + 9 platform-specific + 6 recommended + 5 internal, each with India legal basis (DPDP 2023, IT Rules 2021, E-Commerce Rules 2020, Sec 194-O TDS, ASCI, RBI PA/PG).
- `wiki/website/policy-content-strategy.md` (Tejas) — voice standard, cross-page glossary, trust-framing; recommends pulling **ASCI Disclosure into Wave 1**.
- **3 CEO blockers before P0 legal pages publish:** (1) appoint named Grievance Officer, (2) engage Indian counsel + CA, (3) confirm CIN + registered address.
- **Status:** ✅ list APPROVED (ASCI → Wave 1). CEO policy decisions P-1…P-11 logged in CEO-DECISIONS.md. Rohan CFO advisory done (`cfo-payment-advisory.md`).
- **P0 v0 drafts ✅ DONE** (Ishaan) → `wiki/website/policy-drafts/` (8 files: terms, privacy, escrow-and-refund, dispute-resolution, grievance, kyc, tds, advertising-disclosure). All `noindex`/pending-review, no hard fee/tax numbers, correct no-refund reframe (verified by Swapnil on escrow-and-refund draft). NOT shipped to `src/`.
- **Blocked on human:** (1) Grievance Officer name/email/address, (2) Indian counsel + CA review, (3) CIN + registered address + support email. Then: counsel review → Ananya builds noindex pages → flip indexable after validation.
- **Also pending CEO greenlight to publish numbers:** brand fee % on `/pricing` (Rohan's 10%→5% tiers approved internally), TDS rate (needs CA).

---

## KNOWN FOLLOW-UPS (next waves)

| Item | Owner | Notes |
|------|-------|-------|
| **TIER 2 pages** | Ananya | `/features/escrow`, `/features/hype`, `/how-it-works/brands`, `/how-it-works/creators`, `/pricing`, `/about` — copy from content-map, use `<Seo/>` + FAQPage schema |
| **TIER 3 trust/legal v0** | Ananya + Ishaan | `/support`, `/kyc`, `/tds`, guidelines, refund; `/terms`+`/privacy` upgrade from StaticPage to v0 templates (noindex) |
| **og-image.png (1200×630)** | Zara | `og:image` points to `/og-image.png` which doesn't exist yet → social previews blank until created |
| **`marked` dep approval** | Priya | Ananya hand-rolled a minimal markdown renderer (covers current posts). Approve `marked` if richer syntax needed later |
| **Build gate: undeclared `vitest`** | Priya/Arjun | 11 untracked `.test.tsx` files from other concurrent work reference `vitest`/`@testing-library` (not in package.json) → `tsc --noEmit` (and thus `npm run build`) fails on them. NOT caused by website work; `vite build` alone is green. Fix: add vitest as devDep OR exclude test globs from build tsconfig |
| **Static OG tags on sub-pages** | (architectural) | No-JS social scrapers see homepage OG defaults on sub-pages (SPA-without-SSR limitation). Canonical is correct; only affects OG previews for non-JS scrapers. Fix needs prerender/SSR — defer |
| **Blog stat `[needs real data]`** | Nisha/Rohan | One pricing figure in a blog post flagged as placeholder |

---

## Tech Stack (locked — see /TECH-STACK.md)

- **Frontend:** Vite 6 + React 19, Tailwind v4
- **Animation:** Framer Motion 12, GSAP 3, Lenis
- **3D:** React Three Fiber 9 + drei
- **Existing motion components:** `src/components/motion/*`
- **Existing 3D components:** `src/components/3d/*`

---

## CEO Constraints

1. NO code before Phase 1 complete
2. Use existing motion system — don't reinvent
3. Enhance existing landing page — don't rewrite from scratch
4. Blog must have structured data for GEO
5. CTAs must be WCAG AA contrast (not pale pastel)

---

## Next Actions

- [ ] Tejas: Define target personas → `personas.md`
- [ ] Aditya: GEO-first keyword research → `keywords.md`
- [ ] Nisha: Full site content map → `content-map.md`
- [ ] Ishaan + Nisha: Homepage copy draft → `homepage-copy.md`
- [ ] Aditya + Nisha: 10 blog topics → `blog-topics.md`

**Gate:** Swapnil reviews Phase 1 before Phase 2 starts.
