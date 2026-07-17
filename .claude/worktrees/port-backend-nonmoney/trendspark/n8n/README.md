# Trend-Spark AI — n8n Daily Trend Pull (Task 3, Dev)

Produces the `trends` rows that Vikram's Spring nudge-orchestrator reads (Task 4).
Data flow (Priya schema-lock): **n8n (6 AM) → MySQL `trends` → Spring reads**.

## Files

| File | What it is |
|------|-----------|
| `trend-pull-workflow.json` | Importable n8n workflow export (11 nodes, 2 scheduled branches). |
| `theme-tagger.js` | Canonical pure tagger (themes + campaign_type + peak_window). Same logic is inlined in the workflow's "Theme Tagger" node. Run `node theme-tagger.js` for the self-test. |
| `README.md` | This file. |

## Import

1. n8n → **Workflows → Import from File** → pick `trend-pull-workflow.json`.
2. Create the 4 credentials below (Settings → Credentials). The JSON references them **by name**;
   after import, open each node once and re-select the credential so n8n binds the real IDs
   (the exported `"id": "REPLACE_WITH_CRED_ID"` placeholders are intentional — no secrets shipped).
3. Confirm the workflow timezone is **Asia/Kolkata** (already set in the export's `settings`), so the
   `0 0 6 * * *` cron fires at 06:00 IST, not UTC.
4. Activate. The pull runs 06:00 IST; the expiry sweep runs 06:30 IST.

## Credentials to create (names only — values live ONLY in the n8n cred store / backend `.env`)

| Credential name | n8n type | Where the secret goes | Env var (02-API-KEYS) |
|-----------------|----------|-----------------------|-----------------------|
| `TrendSpark TMDb API Key` | Query Auth (param `api_key`) | cred store | `TMDB_API_KEY` |
| `TrendSpark NewsAPI Key` | Header Auth (`X-Api-Key`) | cred store | `NEWSAPI_KEY` |
| `TrendSpark YouTube Data API Key` | Query Auth (param `key`) | cred store | `YOUTUBE_API_KEY` |
| `TrendSpark MySQL (influora)` | MySQL | cred store | `DB_*` (existing) |

**No API key appears in any committed file** (Priya §5, Kabir checks at Task 11). Only credential
references and `={{$env.X}}` placeholders. `ANTHROPIC_API_KEY` is NOT used here — the one AI call
lives in influora-ai (Ash, Task 8), not in the trend pull.

### Google Trends — stubbed, two documented paths (02-API-KEYS #1)

The "Google Trends (pytrends/SerpAPI — STUB)" Code node emits `[]` today and never fabricates
trends. n8n Code nodes run JavaScript, so `pytrends` (Python) cannot run inside the node. Activate
one path:

- **(a) pytrends** — run pytrends in a small external Python service; point `PYTRENDS_SERVICE_URL`
  at it and swap the stub for an HTTP Request node (snippet is in the node's comment). No key, but
  unofficial/rate-limits → keep the retry+backoff.
- **(b) SerpAPI Google Trends** — replace the stub with an HTTP Request node to
  `https://serpapi.com/search?engine=google_trends`, add credential `TrendSpark SerpAPI Key`
  (`GOOGLE_TRENDS_SERPAPI_KEY`), free 100 searches/mo.

Priya's rec: start pytrends, upgrade to SerpAPI if it breaks often. Until then the run proceeds on
TMDb + NewsAPI + YouTube — one missing source never sinks the pull.

## Column mapping — writes EXACTLY Priya schema-lock §1a `trends`

The "Theme Tagger + row builder" node emits one object per surviving trend; the MySQL INSERT maps
each key 1:1 to a column:

| `trends` column (§1a) | Produced by |
|-----------------------|-------------|
| `id` VARCHAR(26) | ULID generated in-node (Crockford base32, no dep) |
| `trend_text` VARCHAR(500) | source title, trimmed + capped at 500 |
| `source` JSON | `JSON.stringify(["tmdb", ...])` — merged sources, no live payloads |
| `region` VARCHAR(8) | `'IN'` |
| `detected_date` DATE | run date `YYYY-MM-DD` |
| `peak_window_days` INT | from campaign-rulebook typical (or explicit if provided) |
| `expires_at` DATETIME(6) | `detected + peak_window_days` (this is what the expiry sweep deletes on) |
| `themes` JSON | `JSON.stringify([...])` — **closed vocab only** (Nisha's taxonomy) |
| `campaign_type` VARCHAR(16) | one of `HYPE \| SEASONAL \| PRIDE \| EDUCATIONAL` |
| `created_at` / `updated_at` DATETIME(6) | run timestamp |

Only the merged, tagged, expiring record is stored — never raw source payloads (§1a rule).

### Theme tagging + campaign_type (Nisha's locked config)

`theme-tagger.js` embeds a verbatim copy of the closed vocab from
`influora-api/src/main/resources/trendspark/theme-taxonomy.json` and
`campaign-rulebook.json` (n8n nodes can't `require()` a repo file). If either JSON changes,
re-sync `theme-tagger.js` **and** the workflow node. `campaign_type` precedence
(source/keyword-disambiguated): **PRIDE** (patriotic/sports) → **EDUCATIONAL** (health/wellness) →
**HYPE** (TMDb source or movie/celeb keyword) → **SEASONAL** (festival calendar/keyword) →
peak-window fallback. Themes are filtered to the closed set; anything off-vocab is dropped.

## Fail / retry behavior (03-PIPELINE-CHAIN.md [A])

- **Retry with backoff** — every HTTP + MySQL node: `retryOnFail`, `maxTries: 3`, 5 s between tries.
- **Skip a dead source** — the 3 HTTP source nodes use `onError: continueRegularOutput`, so one
  source failing (rate-limit, outage) still lets the other sources flow to the tagger. The run is
  never sunk by a single bad source.
- **Don't write empty/garbage** — the tagger drops any item with empty `trend_text` **or** zero
  matched themes before the INSERT. No empty/untagged rows reach `trends`.
- **Fail closed** — the Google-Trends stub emits `[]` rather than inventing trends; nothing
  fabricated is ever written.
- **Auto-expire** — the 06:30 IST branch runs `DELETE FROM trends WHERE expires_at < NOW()`
  (indexed `idx_trends_expires`), so stale trends can never be suggested.

## Self-test

```
node theme-tagger.js
```

Expected: `ALL PASS (5 cases)` — Salman-Khan-film→HYPE, Diwali→SEASONAL, cricket-World-Cup→PRIDE,
Yoga-Day→EDUCATIONAL, cricket-vs-leather-bag still tags PRIDE (theme-overlap silence is decided
later, in Vikram's Java matcher, not here).
