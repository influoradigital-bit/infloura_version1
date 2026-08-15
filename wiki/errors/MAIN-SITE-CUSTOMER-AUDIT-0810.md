# Main marketing website — customer-behavior audit (2026-08-10)

**Owner:** neha (live E2E) · **Isolation:** shared-context (echo — this walkthrough ran in-conversation, not a separate fresh-context dispatch) for the narrative judgment; the four evidence rows below are `believed` (named oracle not on the deterministic allowlist, but command/result/where are real and reproducible).
**Scope:** the public marketing site (`src/pages/*`) served by `npm run dev` (Vite) — home, pricing, how-it-works (brands/creators), features (escrow/deal-room/hype), blog, about, contact.
**Method:** walked each route like a first-time visitor would — read rendered copy, checked console + network per page, cross-checked a few copy/trust claims against source.

## Findings

### 1. Homepage title/OG-title mismatch (cosmetic, real)
- `index.html:6` and `index.html:18` (static `<title>` + `og:title`, shown to non-JS crawlers and to anyone sharing the link before React hydrates): **"Influora — Escrow-Protected Influencer Marketing for India"**
- `src/pages/landing.tsx:165` (`<Seo title=...>`, what a JS-executing visitor/crawler sees after hydration): **"Influora — Escrow-protected influencer deals for India"**
- Two different taglines for the same page. Low severity, but it's the literal first thing a customer or search engine sees.

### 2. `og:image` points at a file that doesn't exist (real, already tracked)
- `index.html:21` references `https://influora.in/og-image.png`.
- `public/` has no `og-image.png` — only `public/og-image-placeholder.txt`, a TODO already assigned to Zara via Aditya's brief in SHARED_CONTEXT.md.
- Effect: sharing the homepage link on WhatsApp/Twitter/LinkedIn/Facebook — a primary discovery channel for Indian D2C brands — shows a broken preview image. Not new information, but worth surfacing since it's still open and directly hits "how the site looks" the moment a customer shares it.

### 3. Hero/About stat counters read "0+ / ₹0.0Cr+ / 0h" — NOT a live-site bug
- Checked and ruled out as a false positive: this automation tab reports `document.hidden = true`, which the browser uses to pause `requestAnimationFrame`. `CountUp` (`src/components/motion/CountUp.tsx:41`) only advances inside an rAF loop, so it never leaves 0 in this specific harness.
- The real values are hardcoded, correct constants (`STATS` in `landing.tsx:48-50`: 8,915 creators / ₹4.26Cr paid out / 24h avg payout) and the once-in-viewport trigger (`useInViewOnce`) is wired correctly.
- Recorded here specifically so nobody re-files this as a bug from a similar automated check.

### 4. Everything else checked came back clean
- 10 route loads (home + 9 subpages), 0 console errors, 0 non-200 network responses.
- Unmatched paths get a 200 (SPA fallback) and the client router renders a real 404 page (`src/App.tsx:676`), not a blank screen.
- Copy quality across every page is consistent, concrete, and honest — e.g. "Illustrative numbers. Yours come from your own store's webhook." on the sales-tracking mock, real CIN/GSTIN on the Contact page, an explicit "licensed payment partner" line on the Escrow page. No filler, no unverifiable superlatives.
- "Trusted by 500+ Indian brands" / no client logos is a deliberate choice, not a gap — `about.tsx:14-15` cites `CEO-DECISIONS.md #4`: no logos until written permission exists, anonymized stats only.

## NOT CHECKED
- **Mobile viewport (375px) rendering** — the browser pane hung mid-emulation twice in this session; recovered only after switching back to desktop. Could not get a reliable mobile read this run. Given India is majority mobile traffic, this is the highest-value follow-up.
- Pixel-level visual QA (spacing, contrast, animation feel) — no screenshot capability was available in this session's browser pane (compositor never displayed).
- Accessibility/WCAG contrast audit, Lighthouse/performance numbers.
- Individual blog post pages and blog category filters (only the /blog index was checked).
- The actual production deployment — no public domain is configured yet (Hostinger deploy is IP-only test, per `deploy/hostinger/docker-compose.test.yml:3`); this audit checked the local Vite dev server, which is the real source of truth for what ships.

## Skipped
- No code changes made — this was scoped as a check-only pass (user confirmed "Live UX + content audit report" over "audit + fix" or "rewrite copy").
