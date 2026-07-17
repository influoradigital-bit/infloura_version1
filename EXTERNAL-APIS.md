# Influora — External APIs & Keys (Complete List)

> Generated 2026-07-16. This is the **full** map of every outside service Influora calls, in one place.
> It has two groups: **(A) core app secrets** (the app won't run without them — full detail in `BLUEPRINT/06-DEPLOYMENT-AND-API-KEYS.md`) and **(B) trend data-source APIs** (the "v1, free-tier" set that feeds the TrendSpark pipeline).
>
> **The most important thing to understand:** the Group B trend APIs are **not** read by the Java or Python services. They are called by the **n8n workflow** at `trendspark/n8n/trend-pull-workflow.json`, so their keys live in **n8n's credential store**, not in any app `.env` file.

---

## A. Core application secrets

These are consumed directly by the Java (`influora-api`) and Python (`influora-ai`) services. Full setup, generation commands, and gotchas are in `BLUEPRINT/06`. Summarised here so the whole surface is visible at once.

| # | Service | What it gives us | Used by | Env var(s) | Where to get it |
|---|---|---|---|---|---|
| A1 | **Anthropic (Claude)** | Meera's main reasoning model | Python AI | `ANTHROPIC_API_KEY`, `CLAUDE_MODEL` | console.anthropic.com/settings/keys |
| A2 | **Google Gemini** | Secondary LLM | Python AI | `GEMINI_API_KEY` | aistudio.google.com/app/apikey |
| A3 | **Sarvam** | Speech-to-text / text-to-speech (India) | Python AI | `SARVAM_API_KEY` | dashboard.sarvam.ai |
| A4 | **Razorpay / RazorpayX** | Payments + creator payouts | Java API | `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `RAZORPAYX_ACCOUNT_NUMBER` | Razorpay Dashboard → API Keys / Webhooks |
| A5 | **MSG91** | OTP SMS + transactional email | Java API | `MSG91_AUTH_KEY`, `MSG91_TOKEN_AUTH`, `MSG91_WIDGET_ID`, `MSG91_OTP_TEMPLATE_ID`, `MSG91_WELCOME_TEMPLATE_ID`, `MSG91_EMAIL_TRANSACTIONAL_TEMPLATE_ID` | MSG91 → Settings / Templates |
| A6 | **Cloudflare R2** | Object storage (S3-compatible) for uploads/media | Java API | `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET_NAME`, `R2_ENDPOINT`, `R2_PUBLIC_URL` | Cloudflare → R2 → API Tokens |

Plus internal infrastructure (no third-party key): **MySQL 8** (`SPRING_DATASOURCE_*`), **Redis** (`REDIS_URL`), **ClamAV** (`CLAMAV_HOST`). And the internal signing secrets you generate yourself (JWT, HMAC, JWKS, PII encryption) — see `BLUEPRINT/06 §3`.

> Optional, off by default (leave unset unless used): `influora.meta.*` (Meta/Instagram), `influora.shopify.*`, `influora.woocommerce.*`.

---

## B. Trend data-source APIs (v1 — all free tier)

These feed the **TrendSpark** feature ("what's trending in India right now"). They are pulled by the **n8n workflow** `trendspark/n8n/trend-pull-workflow.json`, which normalises them and hands the result to the app. **Keys go into n8n credentials, not the app `.env`.**

| # | Service | What it gives us | From / where | Cost (v1) | Owner | Env var / key | Endpoint used |
|---|---|---|---|---|---|---|---|
| B1 | **Google Trends** | What India searches now | No official key. Use **pytrends** (unofficial) **OR** SerpAPI Google Trends (has a key) | Free (pytrends) / SerpAPI free 100/mo | Dev | `GOOGLE_TRENDS_*` (or `SERPAPI_KEY`) | `https://serpapi.com/search` |
| B2 | **NewsAPI** (or GNews) | Headlines by category | newsapi.org → sign up → free key | Free 100 req/day | Dev | `NEWSAPI_KEY` | `https://newsapi.org/v2/top-headlines` |
| B3 | **TMDb** | Movie / OTT release dates | themoviedb.org → account → API → free key | Free | Dev | `TMDB_API_KEY` | `https://api.themoviedb.org/3/movie/upcoming` |
| B4 | **YouTube Data API v3** | Trending videos (India) | Google Cloud Console → enable "YouTube Data API v3" → key | Free 10k units/day | Dev | `YOUTUBE_API_KEY` | `https://www.googleapis.com/youtube/v3/videos` |
| B5 | **Festival calendar** | Diwali / Eid / cricket dates | **No API** — a static JSON file the team maintains | Free | Nisha | *(file, no key)* | — |
| B6 | **Snapsby catalog** | Our own 500+ videos (SNAPSBY nudge mode) | **Internal DB** — our own credentials | Free (ours) | Vikram | `DB_URL` (existing) | internal |

### How these connect to the app

- The n8n workflow calls B1–B4, plus reads B5 (static JSON) and B6 (internal DB), then produces a normalised "trend" payload.
- The app's TrendSpark logic (already built in code) turns a trend into a creator nudge in one of two modes:
  - **`OWN_CONTENT`** — tell the creator to post their own content for this trend.
  - **`SNAPSBY`** — the brand's shelf is empty for this trend, so surface up to 3 ready catalog videos (from B6) with a "Preview" handoff.
- Code references: prompt logic in `influora-ai/app/prompt/trendspark.py`, route in `influora-ai/app/routes/trendspark.py`, UI in `src/components/trendspark/TrendSparkNudgeCard.tsx`, types in `src/lib/api.ts`.

### Where to put the keys

Because n8n is the caller, add B1–B4 as **credentials in your n8n instance** (or as environment variables on the n8n host that the workflow references), not in `influora-ai/.env` or `influora-api`'s env. B6 reuses the **existing** database connection. B5 is a file in the repo the team edits directly — no key.

### Status note (verified against code, 2026-07-16)

- The external trend keys (`NEWSAPI_KEY`, `TMDB_API_KEY`, `YOUTUBE_API_KEY`, `GOOGLE_TRENDS_*`/`SERPAPI`) appear **only** in `trendspark/n8n/trend-pull-workflow.json` — there are **no** references to them in the FastAPI, Spring, or frontend source. So they are wired at the **n8n layer**, and the app consumes the workflow's output rather than calling these providers itself.
- The **SNAPSBY catalog** and **OWN_CONTENT** logic **are** implemented in the app code (paths above).

---

## C. Quick "who owns what" summary

| Owner | Responsible for |
|---|---|
| **Dev** | B1–B4 trend API keys in n8n; the trend-pull workflow itself |
| **Vikram** | B6 Snapsby catalog / DB; the Java API integrations (A4–A6) |
| **Nisha** | B5 festival calendar JSON |
| **Whoever deploys** | A1–A6 core secrets per `BLUEPRINT/06` |

---

**Related docs:** `BLUEPRINT/06-DEPLOYMENT-AND-API-KEYS.md` (core secrets, in depth) · `DEPENDENCIES.md` (libraries) · `RUN-LOCAL.md` (running it).
