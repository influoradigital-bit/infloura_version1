# Influora — SEO / AEO / GEO Rank-Readiness Audit

**Site:** https://influora.in · **Date:** 2026-07-17 · **Scope:** local codebase (Vite + React 19 SPA, React Router 7)
**Prepared by:** geo-technical agent (code audit) + Aditya, SEO Lead (cross-model readiness)

---

## Executive summary

**Technical GEO Score: 47/100 (Poor).** The paradox: the SEO application layer is genuinely well built — per-page `Seo.tsx` meta, five JSON-LD schema types, a robots.txt that welcomes every AI crawler, and an exemplary `llms.txt`. But it is all delivered via JavaScript that the AI crawlers it targets **do not execute**. GPTBot, ClaudeBot, PerplexityBot, CCBot and Bingbot all receive an empty `<div id="root">` titled "Creator OS - Brand Dashboard" on every one of the 18 sitemap URLs.

**One fix gates four of seven surfaces:** shipping prerender/SSR unblocks Bing, ChatGPT search (which depends on the Bing index), Google AI Overviews, and speeds up Google organic. Everything else is incremental until that lands.

---

## 1. Rank-readiness matrix (all models)

| Surface | JS rendered? | Readiness | Biggest unlock |
|---|---|---|---|
| **Google organic** | Yes (48h render-queue delay) | 🟡 Partial (40%) | Prerender — same-day indexing, better LCP |
| **Google AI Overviews** | Inherited from Google | 🔴 Blocked (10%) | Prerender + question-H2 rewrites on 5 pages |
| **Bing + Copilot** | **No — sees empty shell** | 🔴 Blocked (0%) | Prerender; Bing indexes zero pages today |
| **ChatGPT search** | Via Bing index + OAI-SearchBot | 🔴 Blocked (5%) | Bing index first, then Quora/Reddit citations |
| **Perplexity** | Yes (PerplexityBot renders JS) | 🟡 Partial (30%) | Prerender + YourStory article + comparison page |
| **Claude** | Limited | 🟡 Partial (35%) | Seed off-site citations (llms.txt already strong) |
| **Gemini** | Yes (Googlebot infra) | 🟡 Partial (25%) | Wikidata + Knowledge Graph entity + YouTube |

*Volume figures and platform-behavior notes below are estimates/heuristics, not measured data.*

---

## 2. Critical findings (technical)

### C1. 100% client-side rendered — most AI crawlers see nothing
- `vite.config.ts` plugins: `[react()]` only. No prerender/SSR/SSG.
- Non-JS crawlers (GPTBot, OAI-SearchBot, ClaudeBot, PerplexityBot's index feed, CCBot, Bytespider, Google-Extended, **Bingbot**) see: wrong-brand title (`index.html:8`), no description, no body text, a 404ing favicon (`/vite.svg` doesn't exist). All 18 URLs are identical blank duplicates.

**Fixes ranked by effort:**
- **(c) Ship today (~1h):** replace `index.html` head (correct brand title, meta description, canonical, OG, real favicon links, static Organization JSON-LD) + static fallback content inside `#root` (H1, escrow pitch paragraph, nav links) that React replaces on render. Full drop-in block is in the technical agent's report — see "index.html replacement" appendix note below.
- **(a) This sprint (1–2 days), recommended:** React Router 7 framework-mode `prerender` — repo is already on RR7, no migration needed. `react-router.config.ts` with `ssr: false` + `prerender: [16 marketing routes + 3 blog posts]`. Alternative at similar effort: post-build Puppeteer snapshot of `dist/` routes.
- **(b) Only if content velocity grows (1–2 wks):** split marketing site to Astro/Next at the apex, keep app SPA at `/brand/*`, `/creator/*`, `/admin/*`.

### C2. 6 of 18 sitemap URLs are 404s or soft-404s
| URL | Reality |
|---|---|
| `/features/contracts` | No route → NotFoundPage. **Also promoted in llms.txt** — AI systems pointed at a 404 |
| `/kyc`, `/tds`, `/refund-policy` | Swallowed by `/:handle` catch-all (`App.tsx:540`) → broken portfolio lookup, HTTP 200 soft-404 |
| `/guidelines/creators`, `/guidelines/brands` | No route → NotFoundPage |

**Fix now:** remove the 6 URLs from `sitemap.xml` and `/features/contracts` from `llms.txt` until the pages exist.

### C3. Real pages missing from sitemap
`/contact` (has Seo + Organization JSON-LD) and all **3 blog posts** — the site's most citable GEO assets — are not in the sitemap. Add them.

---

## 3. High findings (technical)

- **H1. Bare, off-brand `index.html`** — wrong title, no meta/OG/JSON-LD, dead favicon ref. (Fix = C1c block.)
- **H2. `og-image.png` doesn't exist** — `schema.ts:18` defaults every page's `og:image`/`twitter:image`/Article image to `https://influora.in/og-image.png`; there is no such file. Ship a 1200×630 PNG.
- **H3. `/terms`, `/privacy`, `/support` are indexable placeholder shells** with no `Seo` component at all, while a proper `LegalPage.tsx` sits **orphaned (imported nowhere)**. Trust pages are an E-E-A-T signal for a payments platform. Wire LegalPage or `noindex` the placeholders.
- **H4. No SPA fallback file** — no `public/_redirects`, no `404.html`. On Netlify-style hosts every deep link hard-404s. Add `/*  /index.html  200`.
- **H5. No route-level code splitting** — `App.tsx` eagerly imports ~60 pages (admin, dashboards, charts) into one bundle served to landing visitors. `React.lazy` the `/brand/*`, `/creator/*`, `/admin/*` zones (~0.5 day).

**Medium/low:** add HSTS + Permissions-Policy to `_headers`; blanket-identical `lastmod` dates read as auto-generated; `/:handle` catch-all needs a reserved-slug/404 guard (`noindex` on failed lookups); no `speakable` schema on blog Articles; `AuroraBackground` appears dead (verify CSS coupling before deleting); add `manualChunks` vendor splitting after H5. Three.js discipline is already good (lazy, not on critical path).

---

## 4. AEO answer-block readiness (per page)

| Page | Question H2s | FAQ schema | AEO-ready? | Fix effort |
|---|---|---|---|---|
| `/` landing | 0 | ❌ | ❌ Blocked | 1h — add 5-question FAQ accordion + `getFaqPageSchema` |
| `/pricing` | 11 | ✅ | ✅ **Ready** (post-prerender) | 0h — best AEO page on the site |
| `/features/escrow` | 1 of 3 | ✅ | ⚠️ Partial | 15min — reframe "Why escrow matters" → "Why does escrow matter for influencer deals?", add "Is escrow safe for large payments?" |
| `/how-it-works/brands` | 0 | ❌ | ❌ Blocked | 30min — wrap step groups in question H2s ("How do I create a campaign on Influora?", "How does payment work through escrow?") |
| `/how-it-works/creators` | 0 | ❌ | ❌ Blocked | 30min — same treatment |

**Total content effort: ~2h 15min → 5 of the live marketing pages become AI-Overviews-quotable.**

---

## 5. Keyword / query coverage

Note: `Seo.tsx` references a `keywords.md` that **does not exist** — coverage below is reverse-engineered from page content.

**Well-targeted:** influencer marketing platform india (landing), escrow influencer payments (escrow), influencer marketing cost india (pricing), how to run influencer campaign india (how-it-works/brands), deal room / hype (feature pages).

**Orphan queries — no page targets them:**
1. **"best influencer marketing platforms india"** (~1,100/mo, highest-value orphan) → create comparison listicle post
2. **"influencer payment terms india"** (~320/mo) → payment guide post linking escrow/tds/contracts
3. **"instagram verified creators india"** (~260/mo) → public `/creators` landing or explainer post
4. **"influora vs X"** → defer until brand search volume exists

**Partial:** "how to pay influencers safely india" (content matches escrow page, title doesn't); "ugc campaign platform india" (Hype = UGC but never says so in title/H1).

---

## 6. Off-site brand authority — 90-day plan

Brand mentions are the strongest GEO signal (≈3× backlinks). Influora currently has **zero** presence on Reddit, Quora, YouTube, Wikidata, Crunchbase, G2/Capterra, or Indian startup press.

| Tactic | Effort | Impact | When |
|---|---|---|---|
| Reddit — 3 answers/posts (r/InstagramMarketing, r/IndianStartups, r/SocialMediaMarketing) | 6h | High — Perplexity/ChatGPT recrawl Reddit monthly | Wk 1–2 |
| Quora — 5 answers on India influencer-payment questions (~65K combined views) | 10h | **Highest** — heavily cited by ChatGPT | Wk 2–4 |
| YourStory explainer: "How escrow is solving India's influencer payment problem" | 20h | **Highest authority** — DA~72, Google News, cited by all engines; backlink triggers Bing crawl | Wk 4–8 |
| Wikidata entry (org, India, SaaS, CIN) | 2h | Knowledge Graph seed → Gemini entity | Wk 6 |
| Crunchbase free profile | 1h | "What is Influora" queries | Wk 6 |
| G2/Capterra/SaaSworthy listing | 3h | Category-index presence for "best platform" queries | Wk 8 |
| YouTube escrow explainer (5–7 min screencast) | 8h | Gemini video grounding | Wk 10 |
| Ongoing: 1 Reddit comment + 1 Quora answer weekly | 2h/wk | Sustained citation growth | Wk 5–12 |

**Total: ~66h over 90 days.** Expected: 15–25 seeded citations, Bing index entry, entity presence, recognition across all six AI engines within ~120 days.

---

## 7. Ranked action plan

| # | Action | Owner | Effort | Impact |
|---|---|---|---|---|
| 1 | Replace `index.html` head + static `#root` fallback | Vikram/Ananya | 1h | Stops the bleeding for all non-JS crawlers **today** |
| 2 | Sitemap cleanup: remove 6 dead URLs, drop `/features/contracts` from llms.txt, add `/contact` + 3 blog posts | Aditya | 30min | Sitemap integrity; citable content discovered |
| 3 | `public/_redirects` SPA fallback + HSTS in `_headers` | Vikram | 15min | Deep-link availability |
| 4 | Ship `public/og-image.png` (1200×630) | Zara | 1h | Un-404s every social/Article image sitewide |
| 5 | **RR7 prerender of marketing routes** (`react-router.config.ts`, `ssr:false` + `prerender` list) | Vikram | 1–2d | **THE fix** — real HTML+JSON-LD for every AI crawler; unblocks Bing → ChatGPT → Copilot |
| 6 | Question-H2 + FAQ rewrites on 5 pages (§4) | Aditya→Ishaan | 2h15 | Unblocks AI Overviews |
| 7 | Route-split brand/creator/admin zones with `React.lazy` | Ananya | 0.5d | Core Web Vitals on marketing pages |
| 8 | Off-site seed: Reddit + Quora + Wikidata + Crunchbase (§6) | Tejas/Nisha + Aditya | 19h | Unblocks GEO citations (ChatGPT/Perplexity/Claude) |
| 9 | Wire orphaned `LegalPage.tsx` for terms/privacy (or `noindex` placeholders) | Vikram | 0.5–2d | E-E-A-T for a payments platform |
| 10 | YourStory article + "best platforms India" comparison post | Nisha→Ishaan | 32h | Highest-authority citation + highest-volume orphan query |
| 11 | Build `/kyc`, `/tds`, `/guidelines/*`, `/refund-policy`, `/features/contracts` with FAQPage schema; restore to sitemap | Ishaan/Vikram | 1–2wk | New GEO landing surface for question-shaped queries |

**Critical path:** #1–4 today → #5 prerender this sprint (blocks everything downstream) → #6 + #8 in parallel → #10 → #11.
**Timeline to "ready" across all surfaces: ~120 days.**

---

*Key files: `index.html`, `vite.config.ts`, `public/sitemap.xml`, `public/llms.txt`, `public/_headers`, `src/App.tsx`, `src/lib/seo/Seo.tsx`, `src/lib/seo/schema.ts`, `src/pages/static-page.tsx`, `src/pages/legal/LegalPage.tsx` (orphaned), `src/lib/blog/posts.ts`, `src/content/blog/`.*
