# Trend-Spark AI — API Keys Required

**Rule (Priya):** Every key lives in the backend `.env` ONLY. Never in frontend / `NEXT_PUBLIC_*`.
**Billing owner:** Rohan — confirm free-tier before build, set spend cap, alert Swapnil before any limit.
**Who requests:** Dev (workflow keys) + Vikram (backend keys). Swapnil approves any paid one.

---

## v1 keys (all free tier — build with these)

| # | Service | What it gives us | From whom / where | Cost (v1) | Who sets it up | Env var |
|---|---------|------------------|-------------------|-----------|----------------|---------|
| 1 | **Google Trends** | What India searches now | No official key. Use `pytrends` (unofficial) OR SerpAPI Google Trends (has key) | Free (pytrends) / SerpAPI free tier 100/mo | Dev | `GOOGLE_TRENDS_*` |
| 2 | **NewsAPI** (or GNews) | Headlines by category | newsapi.org → sign up → free key | Free 100 req/day | Dev | `NEWSAPI_KEY` |
| 3 | **TMDb** | Movie/OTT release dates | themoviedb.org → account → API → free key | Free | Dev | `TMDB_API_KEY` |
| 4 | **YouTube Data API** | Trending videos India | Google Cloud Console → enable YouTube Data API v3 → key | Free 10k units/day | Dev | `YOUTUBE_API_KEY` |
| 5 | **Festival calendar** | Diwali/Eid/cricket dates | No API — static JSON we maintain | Free | Nisha | (file, no key) |
| 6 | **Snapsby catalog** | Our own 500+ videos | Internal DB — our own credentials | Free (ours) | Vikram | `DB_URL` (existing) |
| 7 | **AI model (nudge phrasing)** | Writes Meera's message | Anthropic (Haiku-class, cheap) — existing Claude account | Pay-per-token (small) | Ash | `ANTHROPIC_API_KEY` |

---

## How to get each (step by step)

**Google Trends** — Two paths. (a) `pytrends` Python lib, no key, but unofficial and can rate-limit → Dev wraps with retry/backoff. (b) SerpAPI's Google Trends endpoint — needs an account key, cleaner, free 100 searches/mo. **Rec: start pytrends, upgrade to SerpAPI if it breaks.**

**NewsAPI** — newsapi.org, sign up with company email, copy key. Free "Developer" plan = 100 requests/day, enough for one 6 AM pull. (GNews is the backup if NewsAPI limits hurt.)

**TMDb** — themoviedb.org account → Settings → API → request key (instant for personal use). Gives release dates + poster art we can reuse.

**YouTube Data API** — console.cloud.google.com → new project → enable "YouTube Data API v3" → Credentials → API key. Restrict it to that API. 10,000 units/day free (a trending pull costs ~1–5 units).

**Anthropic (nudge AI)** — we already have a Claude account for the agents. Ash uses the existing `ANTHROPIC_API_KEY` with a cheap model. No new vendor.

---

## Phase 2 / later keys (paid — need Swapnil approval)

| Service | Why | Cost | Trigger to add |
|---------|-----|------|----------------|
| **X (Twitter) API** | Real-time buzz | ~₹8,000+/mo (Basic) | Only if revenue proves it |
| **SerpAPI paid** | Reliable Google Trends at scale | ~$50/mo | If pytrends breaks often |
| **Meta official API** | Legit Instagram trends | Partner approval | Phase 3 only |

**Do NOT** scrape Instagram/TikTok — against terms, legal + reliability risk. Official API only.

---

## Rohan's billing checklist (before build)

1. ✅ Confirm every v1 key is genuinely free tier → yes (table above)
2. ✅ Set a monthly cap on the Anthropic nudge spend (only paid item in v1)
3. ✅ Add alert: if any free source nears its daily limit → notify Swapnil
4. ✅ After week 1 live: read `nudge_log`, compute real ₹/nudge, report to Swapnil
5. ✅ No paid source added without Swapnil's written approval in `budget-approvals.md`

**v1 net new cost:** ~₹0 fixed + small per-nudge Anthropic token cost (capped).
