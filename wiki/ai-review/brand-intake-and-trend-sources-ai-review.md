# AI Review: Brand intake (analyze_site) + Trend/marketing data sources (TrendSpark)

Reviewer: Ash · 2026-07-21 · Question: does the AI actually understand a brand's business from its website, and are the trend/marketing signals (news API, Google Trends, TMDb) working? Traced from real code.

## How It Works (traced flow)

**Brand intake:** brand URL → `guarded_fetch` (httpx, SSRF-guarded) → strip tags → truncate 20k chars → `gemini-2.5-flash` classify (temp 0.2, JSON schema) → `{niche_tags, tone_dial, brand_color, product_catalog[name,price,currency]}` → written to `BrandProfile` (status PENDING→ANALYZING→READY/FAILED), async.
Files: `influora-ai/app/routes/analyze_site.py`, `app/security/ssrf_guard.py`, `app/providers/gemini.py:34-42` (classify prompt), `influora-api/.../service/brand/AnalyzeSiteTriggerService.java`, `domain/entity/BrandProfile.java`.

**Trend intake:** n8n cron (06:00 IST) → 4 source nodes (TMDb, NewsAPI, YouTube, Google-Trends-stub) → Normalize → Theme-Tagger (closed 40-term vocab) → AI-recovery fallback (`/internal/trendspark/tag`, Haiku) → INSERT `trends`. Java reads `trends` read-only via `TrendSparkNudgeService`.
Files: `trendspark/n8n/trend-pull-workflow.json`, `trendspark/n8n/theme-tagger.js`, `influora-ai/app/routes/trend_tag.py`, `influora-api/.../service/trendspark/TrendSparkNudgeService.java`.

## Direct answers

**Does the AI understand the brand's business properly?** — **Shallowly, and often not at all.** It captures ~4 surface fields (niche tags, tone sliders, one hex color, a *guessed* product list). It does NOT capture business model, target audience, USP, or competitors (`competitor_urls` is always written empty). And it likely can't even read most target-market sites (see P1 below).

**Does it get their data from the website?** — Yes, but: **one static-HTML page, tag-stripped, truncated to 20k chars, with prices/names GUESSED by the model, not scraped.**

**Are news API / Google Trends / TMDb working?** — **No live data flows today.** Google Trends is a no-op stub. TMDb/NewsAPI/YouTube are wired but have no credentials and have never run. TrendSpark currently produces nothing.

## Findings

### [P1] "Playwright JS-rendering" is claimed in comments but does NOT exist — analyze_site can't read SPA/Shopify sites
**Where:** `influora-ai/app/routes/analyze_site.py:38-39` (+ `gemini.py:8`) vs the actual `guarded_fetch` (httpx) path.
**Issue:** The code comments claim *"Playwright's rendered DOM text extraction is the primary control,"* and `playwright==1.49.1` is in `requirements.txt` — but **no Playwright import or call exists**. The real fetch is a single static `httpx` GET (no JS execution). The target market (Indian D2C on Shopify/Wix/React storefronts) renders products client-side → the fetched HTML is near-empty → `empty_page` error or a hollow classification. So for a large fraction of real brands, intake silently produces garbage or nothing. This is the single biggest reason brand understanding fails.
**Fix:** Either actually use the already-installed Playwright (render then extract text) for the fetch, or drop the false comments and be explicit that only static HTML is supported. Recommend: wire Playwright behind the same SSRF guard (validate resolved IP, then render), with the httpx path as fallback. Tag Kabir on the render sandbox.
**Gain:** intake works on the actual customer base instead of failing on modern storefronts.

### [P1] Product names & prices are model GUESSES, not scraped facts
**Where:** `gemini.py:40` ("best-effort, empty list if unclear"), schema `gemini.py:55-87`; no DOM/schema.org/price-selector parsing anywhere.
**Issue:** `product_catalog` is whatever Gemini infers from flattened text; `price` is a bare NUMBER so currency/formatting is lost. Downstream this matters twice: (a) `calculate_budget` and Meera's plan lean on product price, and (b) the persona *requires* Meera to quote real prices — but the "real" price it's handed is a hallucination risk. A confidently-wrong price in a money conversation is worse than no price.
**Fix:** Parse structured signals first — JSON-LD `Product`/`Offer` schema.org, OpenGraph `product:price:amount`, common price DOM patterns — and pass those as *facts* to the model; let the LLM fill only gaps, and mark each product `price_source: scraped|inferred`. Never let an inferred price drive a budget number without flagging it.
**Gain:** trustworthy catalog + prices; removes a live hallucination surface from the money path.

### [P1] Google Trends is a no-op stub — a claimed marketing signal that produces nothing
**Where:** `trendspark/n8n/trend-pull-workflow.json:181-194` (`code-google-trends-stub` → `return [];`).
**Issue:** Google Trends is wired into the graph but returns an empty array — pytrends needs an external Python service, SerpAPI needs a key; neither exists. It's listed as a live source in docs (`EXTERNAL-APIS.md:35`) but contributes zero trends. Anyone believing "we use Google Trends" is mistaken.
**Fix:** Either stand up a small pytrends microservice (or SerpAPI with a key) and wire it, or remove it from the source list + docs so the capability isn't overstated. Decide based on whether real-time search-trend data is worth the ops.
**Gain:** honest signal set; if built, a genuinely useful India search-trend feed.

### [P2] TrendSpark has never run on real data — all keys unprovisioned
**Where:** `trend-pull-workflow.json:449` (`"active": false`), all cred IDs `REPLACE_WITH_CRED_ID`; `SHARED_CONTEXT.md:1682` (live run "can't be exercised… once env vars are set").
**Issue:** TMDb/NewsAPI/YouTube keys live in the n8n credential store, never created. With no data in `trends`, `TrendSparkNudgeService` returns `Optional.empty()` → controller 204 → the nudge silently never shows. So TrendSpark is invisible, not broken-looking — indistinguishable from "nothing relevant today." Same keys-gated pattern as the rest of the stack.
**Fix:** Provision the three free-tier keys (NewsAPI/TMDb/YouTube), set `TREND_TAG_INGEST_SECRET` + `INFLUORA_AI_INTERNAL_URL`, flip the workflow active, run one staging pull. Ops, not code.
**Gain:** turns a dark feature on; also validates the (well-built) tagger against real headlines.

### [P2] Brand understanding is shallow by schema — no business model / audience / USP
**Where:** classify schema `gemini.py:55-87`; `competitor_urls` always `List.of()` (`AnalyzeSiteTriggerService.java:166`).
**Issue:** Even when intake works, the AI gets tags + tone + color + products. It does not model who the brand sells to, their positioning, or competitors — the things a strategist would use to plan a campaign. This caps how "smart" Meera can be regardless of the model.
**Fix:** Extend the classify schema with `target_audience`, `positioning_usp`, `business_model` (and actually populate `competitor_urls`), each with a confidence + source. Feed into Block B. Small prompt/schema change; pairs with the Phase-2 outcome grounding.
**Gain:** Meera reasons about the brand, not just its tags.

### [P2] analyze_site eval gives false confidence — it never tests fetch, JS, or price accuracy
**Where:** `influora-ai/evals/datasets/analyze_site_classify.jsonl` + `scorers.py:23-81`.
**Issue:** The 10 golden cases feed the model **pre-cleaned `page_text`**, and scoring checks only `niche_tags` F1 (≥0.60) and tone bucket (≥0.70). It never exercises the fetch/render layer (the P1 failure), the 20k truncation, or price/product-name correctness. So the eval can be green while real intake is broken.
**Fix:** Add real-URL fixtures (incl. a Shopify SPA and a static site) that exercise fetch→classify end-to-end, and a price/name-accuracy scorer. Gate on those before claiming intake quality.
**Gain:** the eval measures the thing that actually fails.

### [P2] Stale model-id + tone-dial-shape comments
`gemini.py:3,133` say `gemini-2.5-flash-lite` but `config.py:67` pins `gemini-2.5-flash` (config wins); `BrandProfile` javadoc documents a `tone_dial` shape that doesn't match what's stored (`AnalyzeSiteTriggerService.java:50-56`). Doc-only; fix to avoid future confusion.

## What's genuinely solid (credit)
SSRF hardening (DNS-rebind pinning, per-hop redirect re-validation, private-range blocks) is strong and well-tested. Async intake never blocks the Meera turn. Defensive JSON parsing degrades to empty, never crashes. The theme-tagger is a clean closed-vocab design with CI-enforced byte-equivalence between the n8n mirror and canonical JS, and the AI-recovery tagger is fail-closed on a closed vocabulary. The plumbing is good; the *data-capture reality* is the problem.

## Data & Training Roadmap
- **Now:** provision TrendSpark keys + run one staging pull (P2); fix/clarify Playwright (P1); log analyze_site outputs + which fields the brand later edits (free eval signal + quality metric).
- **Next:** structured price/schema.org scraping (P1); extend classify schema with audience/USP (P2); real-URL end-to-end eval fixtures.
- **Later:** decide Google Trends (build pytrends service vs drop); once ~1k real analyze runs are logged, mine edit patterns for prompt/schema improvements.

## Verdict: BLOCK the claim "our AI understands the brand", SHIP-WITH-P1-FIXES the pipeline
The plumbing is sound but the *intake reality* is weak: it can't read modern (JS) storefronts, it guesses prices, it captures only surface tags, and the trend feed has no live data (Google Trends is fake; the rest is unprovisioned). Do NOT represent brand-understanding as working until the Playwright/render P1 and price-scraping P1 land and TrendSpark is provisioned and run once for real.
