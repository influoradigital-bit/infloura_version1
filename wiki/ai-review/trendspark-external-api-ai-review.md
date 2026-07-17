# AI Review: TrendSpark — External APIs (Google Trends + Festival Calendar) & "how AI gets smart"

> Reviewer: **Ash** (AI/ML) · Date: 2026-07-16 · Verified against code, not memory.
> Scope: the two unfinished data sources, and where real intelligence should live.
> Companion facts: `EXTERNAL-APIS.md`, `trendspark/n8n/trend-pull-workflow.json`, `theme-tagger.js`.

---

## How It Works (traced flow)

```
n8n daily 06:00 IST
  ├─ TMDb upcoming (IN)          ── HTTP, real, keyed ✅
  ├─ NewsAPI top-headlines (IN)  ── HTTP, real, keyed ✅
  ├─ YouTube mostPopular (IN)    ── HTTP, real, keyed ✅
  └─ Google Trends               ── Code node returns []  ❌ STUB
        │
   Merge → Normalize → Theme Tagger (keyword/substring match → themes+campaign_type)
        │
   MySQL INSERT into `trends`  (Flyway V51 table; columns match Trend.java exactly)
        │
   06:30 IST: DELETE FROM trends WHERE expires_at < NOW()   (auto-expiry)

App read path (per brand):
  TrendSparkNudgeService.recommend(workspace)
    → trendRepository.findActive(now)
    → themeMatchService.score(trend, brandProfile.themeTags)   ← DETERMINISTIC set-overlap
    → pick bestTrend (max score, >= scoreThreshold)
    → contentGapService.decide() → mode (OWN_CONTENT | SNAPSBY)
    → catalogMatchService.topMatches() → Snapsby videos
    → POST influora-ai /internal/trendspark/nudge   ← the ONLY LLM call (Haiku-class)
         builds system+user prompt → Claude → defensive JSON parse + 6 validators
         → on any miss: deterministic templated fallback (still 200)
    → NudgeLog saved (trendId, matchScore, mode, message, messageSource=AI|FALLBACK)
```

**Files:** `trendspark/n8n/trend-pull-workflow.json`, `trendspark/n8n/theme-tagger.js`, `influora-ai/app/routes/trendspark.py`, `influora-ai/app/prompt/trendspark.py`, `influora-api/.../trendspark/TrendSparkNudgeService.java`, `ThemeMatchService`, `ContentGapService`, `CatalogMatchService`.

### The one sentence that matters
**Today there is no ML in TrendSpark except phrasing.** Tagging is substring keyword matching (JS), trend→brand matching is theme-set overlap (Java), selection is `argmax(score)`. The LLM only turns an already-made decision into a 2-sentence nudge. That's a *safe* design — but it caps how "smart" the feature can be, and it silently drops real trends the keyword list doesn't know.

---

## Integration Map

- **Call sites (LLM):** one — `influora-ai/app/routes/trendspark.py` → `ClaudeProvider.complete_text(model=TRENDSPARK_MODEL)`.
- **Dependencies & failure modes:** provider error / malformed JSON / failed validator / spend gate → all degrade to a templated fallback (never a 500). Good. Google Trends stub → that source silently contributes nothing. n8n direct-to-MySQL → couples n8n to the `trends` schema (a future migration breaks it silently).
- **Est. cost:** phrasing call ≈ Haiku, ~300–600 tokens in / ~60 out ⇒ well under ₹0.05/nudge. One trend-pull/day. Adding the two ML upgrades below stays in the "few paise per call, only on the miss path" range. No runaway risk.

---

## Findings

### P1 — Google Trends is a stub; wire the real source **and use its features**
**Where:** `trend-pull-workflow.json` → node `Google Trends (pytrends/SerpAPI — STUB)` (returns `[]`).

**Issue:** "What India searches now" — the single most valuable real-time signal — contributes zero rows. Worse, even the 3 live sources feed the tagger only a **title string**; all momentum/volume signal is discarded.

**Fix (do this — SerpAPI `google_trends_trending_now`, keyed, works today):**
Replace the stub with an HTTP Request node:

```
url:    https://serpapi.com/search
method: GET
query:
  engine:      google_trends_trending_now
  geo:         IN
  hours:       24
  only_active: true
  api_key:     (n8n credential httpQueryAuth, param name "api_key")  ← GOOGLE_TRENDS_SERPAPI_KEY
retryOnFail: true, maxTries: 3, onError: continueRegularOutput   (match the other 3 nodes)
```

Then extend the Normalize node to map the response's **feature-rich** fields, not just the title:

```js
// SerpAPI trending_now → normalized raw trend (uses the FEATURES)
if (Array.isArray(j.trending_searches)) {
  for (const t of j.trending_searches) {
    if (!t || !t.query) continue;
    const cat = (t.categories && t.categories[0] && t.categories[0].name || '').toLowerCase();
    out.push({ json: {
      text: String(t.query),
      source: 'google_trends',
      category: cat,                                 // → niche themes
      // FEATURE SIGNAL the pipeline currently throws away:
      search_volume: t.search_volume || null,        // rank / heat
      increase_percentage: t.increase_percentage || null, // momentum
      related: (t.trend_breakdown || []).slice(0, 5),// richer text for tagging
      // momentum → dynamic peak window: fast spikes are HYPE (short), slow burns last longer
      peakWindowDays: (t.increase_percentage && t.increase_percentage > 500) ? 3 : undefined,
    }});
  }
  continue;
}
```

**Why SerpAPI, not the others:** the **official Google Trends API is still alpha** (application-gated, and it's an *interest-over-time* API, not a "trending now" feed) — don't block v1 on it. **pytrends** can't run in an n8n JS Code node; it needs a separate Python microservice (you already run FastAPI in `influora-ai`, so a tiny `/trending?geo=IN` endpoint using `pytrends.trending_searches(pn='india')` is the zero-cost follow-up when SerpAPI's 100/mo free cap gets tight — one pull/day = ~30/mo, safe for now).

**Gain:** the highest-signal source goes from 0 → live; `search_volume`/`increase_percentage`/`trend_breakdown` become inputs for heat-ranking and data-driven expiry (see P2 items).

---

### P1 — Festival calendar has a consumer but no producer
**Where:** tagger handles `source='festival_calendar'` → SEASONAL (`theme-tagger.js:150`) and `campaign-rulebook.json` references it — but **no workflow node emits it.**

**Issue:** Diwali/Eid/cricket dates never enter `trends`. Festivals are the most *predictable, highest-commercial-intent* trend class, and they're the one you can nudge **ahead** of time — currently missed entirely.

**Fix:** Nisha maintains `festivals-IN-2026.json` (date, name, optional lead_days). Add an n8n Code node on the 06:00 branch that emits any festival inside a look-ahead window, with **lead time** so creators are nudged *before* the day:

```js
// Festival calendar producer — reads the maintained JSON (host it at a raw URL
// or read via n8n's HTTP node), emits upcoming festivals with lead time.
const LOOKAHEAD = 30;                    // start nudging up to 30 days out
const today = new Date();
const out = [];
for (const f of $json.festivals) {       // {name, date:'2026-11-08', lead_days?:21}
  const d = new Date(f.date + 'T00:00:00+05:30');
  const daysUntil = Math.ceil((d - today) / 86400000);
  const lead = f.lead_days ?? 21;
  if (daysUntil < 0 || daysUntil > Math.max(LOOKAHEAD, lead)) continue;
  out.push({ json: {
    text: `${f.name} season begins across India`,
    source: 'festival_calendar',
    // expire AFTER the festival day, not a fixed 21 — data-driven window:
    peakWindowDays: Math.max(1, daysUntil + 2),
  }});
}
return out;
```

Wire it as a 5th input into `Merge all sources` (bump `numberInputs` 4→5). The tagger already does the rest.

**Gain:** an entire high-intent trend class turns on, with correct pre-festival timing and expiry — no AI needed, pure wiring.

---

### P1 — Tagging is brittle substring matching → real trends get silently dropped
**Where:** `theme-tagger.js` `tagTrend()` — `lowered.includes(keyword)`; rows with **no theme match are dropped** (`route`/tagger fail-closed).

**Issue:** A real trend like *"Pushpa 3 first look breaks records"* or a transliterated festival (*"Deepavali"*) matches **none** of the fixed keywords → 0 themes → dropped. The fail-closed rule is correct (never write garbage) but it also silently discards good trends. This is the biggest *quality* gap and it's invisible (no metric on drop rate).

**Fix (keep the guardrail, add an LLM *recovery* path):** keep the free keyword tagger as the fast path. **Only when it returns `themes=[]`**, call a cheap Haiku structured-output classifier that maps the trend text to the **locked vocab** — then re-apply the same closed-vocab filter so nothing outside Nisha's list can enter:

```
system: "Map this India trend to Influora's CLOSED theme vocabulary. Return ONLY
         JSON {themes:[...], campaign_type:'HYPE|SEASONAL|PRIDE|EDUCATIONAL'}.
         themes MUST be a subset of: [strength, action, ... resilience].
         If nothing fits, return {themes:[]}. Invent nothing."
user:   trend_text (+ related queries from Google Trends trend_breakdown)
post:   themes = themes ∩ THEMES   // same closed-vocab guard as today; empty → still drop
```

Runs in n8n via an HTTP node to a small `influora-ai` endpoint, or inline. Cost: only on the miss path, ~1 Haiku call per unmatched trend/day (single-digit calls). **This is the single highest-ROI "make it smart" move** — it lifts recall without weakening any safety rule.

**Gain:** recovers real trends the keyword list can't know (new film names, slang, transliteration) while preserving fail-closed + closed-vocab.

---

### P2 — Selection is theme-overlap only; add semantic match + heat ranking
**Where:** `TrendSparkNudgeService` → `themeMatchService.score()` = set overlap; `argmax`.

**Issue:** Two limits. (1) Overlap is lexical on a 40-word vocab — a fitness brand and a "discipline/strength" cricket trend match, but subtler fits are missed. (2) Selection ignores **trend heat** — a barely-trending item and a nation-stopping one score equally if themes tie.

**Fix:** blend three cheap signals into the score:
`final = w1·themeOverlap + w2·cosine(embed(trend_text), embed(brand_niche_profile)) + w3·normalized_heat`
where `heat` = the Google Trends `search_volume`/`increase_percentage` you now capture (P1), and embeddings use the cheapest model, cached per trend (≤ a few hundred/day). Keep `scoreThreshold` as the floor.

**Gain:** more relevant + more timely trend picked per brand → higher nudge click/purchase.

---

### P2 — Cross-source duplicates spam the same event
**Where:** Merge node — TMDb + News + YouTube + Trends routinely surface the **same** event as 3–4 rows ("Pushpa trailer", "Allu Arjun film", "Pushpa release").

**Issue:** A brand can get several nudges for one event; and a multi-source event (stronger signal) isn't recognized as such.

**Fix:** before insert, cluster near-duplicate `trend_text` (embedding cosine > 0.85, or simple title n-gram overlap) into one row with **merged `source` array**. A trend seen in 3 sources is a stronger, higher-heat signal — feed that count into the P2 ranking.

**Gain:** no repeat-nudge spam; multi-source corroboration becomes a ranking feature.

---

### P2 — The AI never uses the brand's own recent-content signal (already computed!)
**Where:** `TrendSparkNudgeService` — `contentGapService.decide()` returns a gap-check that "carries the brand's recent own-content theme signal (Meta-backed when available)" with a code comment: *"not consumed here yet, available for the AI phrasing step to reference later."*

**Issue:** You already compute what the brand has been posting, then don't hand it to the phrasing model — so nudges can't say "this trend fits the *festive* angle you've been leaning into."

**Fix:** pass a compact `recent_themes: [...]` into the nudge request body and reference it in the system prompt ("if the brand's recent themes overlap the trend, acknowledge it briefly"). Keep it wrapped as untrusted data.

**Gain:** noticeably more personal, "this assistant knows my brand" nudges — the cheapest personalization win, data already in hand.

---

## Data & Training Roadmap

You are **already capturing the flywheel** — use it:
- **Now:** `NudgeLog` logs `trendId, matchScore, mode, message, messageSource(AI|FALLBACK)`; `TrendSparkController` has `POST /nudge/{id}/click` and `/purchase`. Join these into a funnel table: *(trend, brand, mode, message) → shown → clicked → purchased*. Also log/alert on **two hidden quality metrics**: tagger **drop rate** (themes=[]) and nudge **FALLBACK rate** (AI validation misses). You can't improve what you don't measure.
- **Next:** take the top-CTR real nudges and add 2–3 as **few-shot examples** in the currently **zero-shot** phrasing prompt (`build_system_prompt`) — lifts tone consistency and cuts FALLBACK rate. Build a **golden eval set** (~20 trend+brand → expected-mode + acceptable-message) and run it on every prompt change.
- **Later (only at volume):** once you have ~10k funnel rows, a small learned ranker for trend→brand selection (replacing hand-set `w1/w2/w3`) and/or fine-tuning the phrasing model. Not before — the eval loop + few-shot will carry you a long way.

---

## Verdict: **SHIP WITH P1 FIXES**

The safety architecture is genuinely good — fail-closed, prompt-injection-wrapped, hallucination kill-switches, cheap-model discipline, spend-gated. Don't touch that. But TrendSpark is **not "properly implemented" until the three P1s land**:

1. **Wire Google Trends** (SerpAPI `google_trends_trending_now`, geo=IN) and capture its feature signal.
2. **Add the festival-calendar producer node** (consumer already exists).
3. **Add the LLM tagger recovery path** so real trends stop getting silently dropped — the closed vocab keeps it safe.

Then activate the workflow (`"active": true`) and fill the `REPLACE_WITH_CRED_ID` credentials. The P2s (semantic+heat ranking, dedup, recent-theme personalization) are how it gets genuinely *smart* after that — all cheap, all behind the existing guardrails.

---

**Handoffs:** n8n nodes → **Dev**. Java ranking/dedup + pass recent_themes → **Vikram**. LLM tagger endpoint + few-shot prompt + eval set → **influora-ai / me (Ash)**. Credentials + activation → **Dev/Meera**. Route blockers to Priya if scope > 1 sprint.

Sources: [SerpAPI Google Trends Trending Now](https://serpapi.com/google-trends-trending-now) · [SerpAPI Google Trends API](https://serpapi.com/google-trends-api) · [Google Trends API (alpha)](https://developers.google.com/search/apis/trends)
