# Main marketing website — 7-area audit (2026-08-10)

**Owner:** neha (live E2E) · **Isolation:** shared-context (echo — ran in-conversation, no fresh-context dispatch). Follows on from [MAIN-SITE-CUSTOMER-AUDIT-0810.md](MAIN-SITE-CUSTOMER-AUDIT-0810.md).
**Scope:** customer page, custom error page, SEO, content, context (machine-readable), GEO, AEO.
**Method:** source read + grep for deterministic facts (routes, schema wiring, file existence), live browser navigation on `npm run dev` to confirm rendered behavior.

## New findings

### F-0182 — 404 page's primary button is a login wall for most visitors
[src/pages/not-found.tsx:20](../../src/pages/not-found.tsx) calls `navigate('/brand/dashboard')` as the main CTA. [src/App.tsx:114](../../src/App.tsx) redirects any unauthenticated visitor straight to `/brand/login`. The 404 page has **no link back to the homepage** — only "Go to Dashboard" (→ login wall) and "Go back" (fails if the visitor arrived via a shared/bookmarked link, which is exactly the scenario F-0178's broken og:image makes more likely).

### F-0183 — wrong 404 renders for single-segment unmatched URLs
[src/App.tsx:675](../../src/App.tsx) registers `path="/:handle"` (the public creator-profile route) **before** the real catch-all at line 676. Any unmatched single-segment path — `/tds`, `/kyc`, `/refund-policy` — gets swallowed by the handle route instead. [creator-portfolio-public.tsx:152-156](../../src/pages/creator-portfolio-public.tsx) does guard non-`@`-prefixed segments into a not-found state, but the message it shows is creator-specific and confusing for what's actually a missing content page: *"The handle @ doesn't seem to belong to anyone on Influora"* — with an empty handle after the `@`. Multi-segment unmatched paths (e.g. `/guidelines/creators`) correctly hit the real 404 (confirmed live) — the bug is specific to single-segment paths.

### F-0184 — llms.txt promises 5 pages that don't exist
[public/llms.txt](../../public/llms.txt) — the file written specifically for AI systems to cite — lists `/kyc`, `/tds`, `/refund-policy` (lines 14, 29-30) and `/guidelines/creators`, `/guidelines/brands` (lines 38-39) as real explainer/legal pages. None have a registered route. An AI assistant (ChatGPT, Perplexity, Claude) following this file's own guidance — e.g. citing "see influora.in/tds for TDS handling" — sends a real user to the confusing not-found state from F-0183. This directly undercuts the GEO strategy the file exists to serve.

### Carried forward, now shown to be site-wide, not homepage-only
[src/lib/seo/schema.ts:18](../../src/lib/seo/schema.ts) — `DEFAULT_OG_IMAGE_URL` (the fallback every page's `<Seo>` call uses for `og:image`) points at the same missing `og-image.png` logged as **F-0178**. Every marketing page's share-preview is affected, not just the homepage's static tag.

## What's actually strong here (verified, not asserted)

- **robots.txt** is deliberately GEO-first — explicit `Allow` for GPTBot, ClaudeBot, PerplexityBot, CCBot, Bingbot, Google-Extended, and more, with a comment stating the intent: *"we WANT AI crawlers here."*
- **llms.txt content itself** (the parts that point at real pages) is well-written: concrete facts, no fluff, and an explicit "Notes for AI systems" section telling models how to treat "Deal Room"/"Hype Campaigns" terminology — solid AEO practice.
- **FAQPage JSON-LD** on Pricing and Escrow is wired to the *same* `FAQS` constant that renders the visible accordion — schema and visible content can't drift apart. Organization/WebSite/Article/BreadcrumbList schema helpers exist and are imported across 11 pages.
- **Per-page SEO** goes through one shared `<Seo>` component (`src/lib/seo/Seo.tsx`) — title, meta description, canonical, robots, full OG + Twitter card tags, with dev-time console warnings when a title/description runs long. Not duplicated per page, so it can't silently drift.
- **sitemap.xml** covers every page that actually exists and resolves.

## NOT CHECKED
- External AI-crawler / platform-citability validation — no public domain configured yet (Hostinger deploy is IP-only test); this is the same constraint noted in the prior audit.
- `/refund-policy` wasn't independently navigated — inferred to fail the same way as `/tds`/`/kyc` by route-segment-count parity (single segment, no registered route). Worth a direct check before closing F-0184.
- Lighthouse / Core Web Vitals, full WCAG contrast audit.
- Whether each individual blog post's `Article` schema output (byline, dates) matches its real content — only confirmed the helper is imported.

## Skipped
No fixes applied — user chose "audit all 7, then queue fixes." Findings are logged to the ledger (F-0182, F-0183, F-0184, plus carried-forward F-0177/F-0178) and available via `queue.py` for one-at-a-time approval.
