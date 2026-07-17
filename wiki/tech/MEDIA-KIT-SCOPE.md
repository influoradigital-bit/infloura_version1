# Media Kit — CTO Scope

**Owner:** Priya (CTO) · **Requested by:** Swapnil · **Date:** 2026-07-18
**Status:** SCOPE — not yet approved for build. One open decision for Swapnil (§7).
**Depends on:** public-portfolio auth fix (shipped) + view-tracking (shipped 2026-07-18, see `PortfolioService#recordPublicView`).

---

## 1. The insight that shrinks this feature

The 2026 market has moved **away from static PDF media kits toward interactive web pages with live metrics** (Beacons auto-generates one; the industry guidance is "living pages with embedded proof, not PDFs"). **Influora already has that page** — the public portfolio at `influora.com/@handle` is, functionally, a modern media kit: bio, platform stats, trust bar, past collabs + testimonials, rate card, pinned content.

So this is **not** "build a media kit from scratch." It is three bounded gaps:

| Gap | What's missing | Effort |
|---|---|---|
| **A. Downloadable snapshot** | A brand marketer often must *forward* a kit into an internal deck/email. Today they can only share a URL. | Medium |
| **B. Case-study depth** | Past collabs show brand + rating + quote, but not the ROI metrics brands rank #1 (reach, engagement, conversions per campaign). | Medium |
| **C. Download tracking** | ~~`analytics.mediaKitDownloads` is a hardcoded `0`~~ **SHIPPED 2026-07-18** — now a real typed count (see §C-status). | Done |

## 2. Recommended approach

**Ship A + C first (the "downloadable, tracked kit"), defer B** to the demographics/metrics workstream that already needs Instagram/YouTube OAuth data. A+C is self-contained and reuses infra we already have.

### A. PDF generation — server-side, no headless browser
- **CTO ruling:** render the PDF **from an XHTML template with `openhtmltopdf`** (Flying Saucer lineage), **not** headless Chrome. Rationale: headless Chrome means another long-running service + memory footprint + a new attack surface, for a low-frequency, non-interactive artifact. `openhtmltopdf` is a library, deterministic, and runs inside the existing API pod. Add to `wiki/tech/approved-deps.md` before anyone `mvn`-adds it.
- Data source: the **existing** `PortfolioPageResponse` — zero new query work; the assembler already gathers everything.
- Output: stream the generated PDF to **R2** and return a **time-limited presigned GET** — identical pattern to `PortfolioService#uploadCover` / `resolveCoverUrl`. Do **not** serve the PDF bytes through the API pod on every hit.
- Endpoint: `GET /portfolio/{username}/media-kit.pdf` → 302 to the presigned R2 URL. **Must be added to the `SecurityConfig` public allowlist** alongside the `GET /portfolio/*` rule we just shipped (a brand downloading a kit is the same anonymous visitor). Narrow matcher: `GET /portfolio/*/media-kit.pdf`.
- Respect existing `PortfolioVisibility`: a `brands_only`/`hidden` rate card must be honored in the PDF exactly as on the web page — reuse the same visibility gates in the assembler, don't re-implement.

### C. Download tracking — generalize the events table  ✅ SHIPPED 2026-07-18 {#c-status}
Done as part of the view-tracking landing, before either was committed, so there was no throwaway
`CREATE`-then-`ALTER`:
- Table is `portfolio_events` (not `portfolio_view_events`) — one append-only, typed table.
- `event_type VARCHAR(24)` discriminator (`PortfolioEventType` enum: `VIEW` / `MEDIA_KIT_DOWNLOAD` /
  `LINK_CLICK`), stored `@Enumerated(STRING)`. VARCHAR not DB-ENUM so a new type is an app change,
  never a migration.
- `recordPublicView` writes `VIEW`; `analytics()` counts page views by `VIEW` and **`mediaKitDownloads`
  is now a real `countByCreatorProfileIdAndEventType(profileId, MEDIA_KIT_DOWNLOAD)`** — no longer a
  hardcoded `0`. It returns 0 today only because nothing writes `MEDIA_KIT_DOWNLOAD` yet; it goes live
  the instant the (deferred) PDF endpoint does. `LINK_CLICK` is wired in the enum for the same
  near-free pickup later.
- Files: `V20260718120000__portfolio_events.sql`, `PortfolioEvent`, `PortfolioEventType`,
  `PortfolioEventRepository`, `PortfolioService`. Covered by `PortfolioServiceTest` (7 tests, green).

## 3. Out of scope (explicitly)
- Custom PDF themes / brand accent colors (portfolio v2 backlog).
- Editable kit layout — the kit mirrors the public page 1:1; there is no second thing to maintain.
- Emailing the kit on the creator's behalf — a share/download link is enough for v1.

## 4. Rough sizing
- **A (PDF pipeline):** ~2–3 days — template, `openhtmltopdf` wiring, R2 stream+presign, endpoint, security allowlist, tests. Vikram.
- **C (event_type + counting):** ~0.5 day, mostly the migration + `analytics()` swap. Vikram.
- **FE:** ~0.5 day — re-add the "Download media kit (PDF)" button that was removed 2026-07-17 (`api.ts` note), pointing at the new endpoint. Ananya.
- QA + local verify: Kavya → Meera.

## 5. Team routing (on approval)
`FROM Priya → TO <agent> | TASK`
- **Priya** | approve `openhtmltopdf` in `approved-deps.md`; sign off `event_type` schema.
- **Meera (DB)** | migration: `ALTER TABLE portfolio_view_events ADD event_type …` (timestamped Vxxxx, after V20260718120000).
- **Vikram (BE)** | PDF service + `GET /portfolio/*/media-kit.pdf` + SecurityConfig allowlist + type-scoped counting in `analytics()`.
- **Ananya (FE)** | restore download button → new endpoint.
- **Kavya / Meera** | QA + build/verify (offline `mvn -o test` + curl the anonymous download path).

## 6. Risks
- **PDF fidelity:** `openhtmltopdf` is XHTML/CSS 2.1-era, not a full modern-CSS engine. The template is authored *for it*, not shared with the React page — don't expect pixel-parity with the live site. Acceptable: the PDF is a snapshot, not the product.
- **R2 object growth:** regenerate-on-request with a short cache, or cache the last render keyed by profile `updatedAt`. Decide at build time; lean toward regenerate-on-request for v1 simplicity.

## 7. OPEN DECISION for Swapnil
**Do brands actually need a downloadable PDF, or is a shareable link enough for our Indian-export-brand clients?**
- If **link is enough** → we ship **nothing new for A**; the public page already is the kit. We'd only do **C** (so "media kit downloads" isn't a dead stat — or we remove that stat). ~0.5 day total.
- If **PDF is required** (brand teams forward kits internally, work offline, attach to decks) → build **A + C** as scoped. ~3–4 days.

This is a business/market call, not a technical one — hence it's yours. My default recommendation if you don't have a strong signal: **ship C now** (cheap, kills a fake stat, unlocks link-click tracking) and **hold A** until a brand actually asks for a downloadable file.
