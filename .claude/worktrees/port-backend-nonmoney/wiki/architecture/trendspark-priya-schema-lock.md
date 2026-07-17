# Trend-Spark AI — Architecture + Schema + Security LOCK (Priya)

> **Owner:** Priya (CTO) — **LOCKED.** No agent changes the schema or the placement rules
> below without Priya sign-off. This is INDEX.md Task 1 (unblocks Tasks 2, 3, 5).
> **Date:** 2026-07-13 · **Spec:** `Snapsby-TrendSpark-AI-Spec.md` · **Stack law:** `TECH-STACK.md`

## 0. Stack reconciliation (READ FIRST — the spec is wrong about the stack)

`Snapsby-TrendSpark-AI-Spec.md` and the trendspark/ docs were written assuming
Next.js/Prisma/Postgres. **This repo is not that.** Per `TECH-STACK.md` (which wins):

| Concern | Where it actually lives | Owner |
|---|---|---|
| `trends`, `brand_profiles` ext, `nudge_log`, catalog tables | **MySQL via Flyway** in `influora-api` | Vikram |
| content-gap check, catalog-match, nudge orchestration route | **Spring Boot** `com.influora.service` + `com.influora.web` | Vikram |
| the ONE AI phrasing call | **FastAPI** `influora-ai/app/routes/` (already holds `ANTHROPIC_API_KEY`) | Ash |
| soft nudge card, own-content mode, preview handoff | **Vite + React 19** in `src/` (NOT `src/app/`) | Ananya |
| daily trend pull + theme tagging | **n8n** workflow + a tagging step | Dev |

**Data flow (authoritative):**
```
n8n (6AM) ──writes──▶ MySQL trends  ──read──▶ Spring nudge-orchestrator
                                                   │ (rules: gap-check, catalog-match, score)
                                                   ├─▶ POST influora-ai /trendspark/nudge (phrasing only)
                                                   └─▶ writes nudge_log ──▶ React card renders
```
The Java side owns ALL logic and data. influora-ai is a stateless phrasing sidecar — it
holds no trend/nudge data and invents nothing factual (§4). Frontend calls Spring only.

## 1. Schema — Flyway `V51__trendspark.sql` (LOCKED shape)

Latest existing migration is **V50**. Trend-Spark is **V51** (never renumber). IDs are
ULID `VARCHAR(26)`, timestamps `DATETIME(6)`, JSON columns are MySQL `json`. All new
tables InnoDB, `utf8mb4`.

### 1a. `trends` (n8n writes, Spring reads)
```
id                VARCHAR(26)  PK            -- ULID
trend_text        VARCHAR(500) NOT NULL
source            JSON         NOT NULL      -- ["tmdb","google_trends"]
region            VARCHAR(8)   NOT NULL DEFAULT 'IN'
detected_date     DATE         NOT NULL
peak_window_days  INT          NOT NULL
expires_at        DATETIME(6)  NOT NULL      -- n8n auto-deletes rows past this
themes            JSON         NOT NULL      -- ["strength","action","energy"]
campaign_type     VARCHAR(16)  NOT NULL      -- HYPE|SEASONAL|PRIDE|EDUCATIONAL
created_at        DATETIME(6)  NOT NULL
updated_at        DATETIME(6)  NOT NULL
INDEX idx_trends_expires (expires_at)
INDEX idx_trends_campaign (campaign_type)
```
`campaign_type` is a controlled vocabulary (validate on write). Never store live-source
payloads here — only the merged, tagged, expiring record.

### 1b. `brand_profiles` — ALTER (extend the EXISTING table, do not recreate)
`brand_profiles` already exists (`workspace_id`, `niche_tags` JSON, `product_catalog`
JSON, `tone_profile` JSON). Add exactly:
```
ALTER TABLE brand_profiles
  ADD COLUMN theme_tags   JSON        NULL,   -- brand's Trend-Spark themes (controlled vocab)
  ADD COLUMN last_posted_at DATETIME(6) NULL; -- drives content-gap check (§3)
```
`theme_tags` is distinct from `niche_tags` (niche = category label; theme = Trend-Spark
matching vocab from Nisha's taxonomy). Do not overload `niche_tags`.

### 1c. `snapsby_catalog_video` (catalog-match target; Vikram seeds a few rows for MVP)
No catalog table exists yet. Minimal shape so catalog-match has something real to query:
```
id            VARCHAR(26)  PK
title         VARCHAR(300) NOT NULL
niche         VARCHAR(64)  NOT NULL       -- fitness|apparel|skincare|...
themes        JSON         NOT NULL       -- overlaps trends.themes
language      VARCHAR(16)  NOT NULL
price_inr     INT          NOT NULL       -- server-derived, never client-trusted
preview_url   VARCHAR(500) NULL
active        TINYINT(1)   NOT NULL DEFAULT 1
created_at    DATETIME(6)  NOT NULL
INDEX idx_catalog_niche (niche)
```

### 1d. `nudge_log` (Vikram writes — THE FLYWHEEL)
```
id                 VARCHAR(26)  PK
workspace_id       VARCHAR(26)  NOT NULL       -- brand workspace (isolation key)
trend_id           VARCHAR(26)  NOT NULL
campaign_type      VARCHAR(16)  NOT NULL
match_score        INT          NOT NULL
mode               VARCHAR(16)  NOT NULL        -- SNAPSBY | OWN_CONTENT
video_ids          JSON         NULL            -- suggested catalog ids (SNAPSBY mode)
message            TEXT         NOT NULL        -- copy actually shown
message_source     VARCHAR(16)  NOT NULL        -- AI | FALLBACK
shown_at           DATETIME(6)  NOT NULL
clicked_at         DATETIME(6)  NULL
purchased_at       DATETIME(6)  NULL
purchased_video_id VARCHAR(26)  NULL
created_at         DATETIME(6)  NOT NULL
INDEX idx_nudge_workspace (workspace_id)
INDEX idx_nudge_trend (trend_id)
```
No PII beyond `workspace_id`. `message` is brand-facing marketing copy — allowed. Never
log emails, tokens, or raw model prompts here.

## 2. Campaign-type + score rules (Java, ₹0 — the "logic brain")
- `campaign_type` derived from `peak_window_days`: ≤3 & celeb/movie→HYPE; festival→SEASONAL;
  sports→PRIDE; evergreen→EDUCATIONAL. Final mapping table = Nisha/Tejas (Task 5); Java reads it.
- **Score** = count(overlap(trend.themes, brand.theme_tags)). Trigger only if `score ≥ THRESHOLD`
  (default **2**, config value). Below threshold → **stay silent** (correct, not an error).

## 3. Anti-spam gate (LOCKED — §5b of spec) — content-gap check, Java
Order is mandatory: **match → gap-check → mode**.
```
gap = (last_posted_at is null OR older than GAP_DAYS[default 4])
   OR (no own-content video matching trend theme)
   OR (peak_window_days <= 3 and brand has nothing ready)
   OR (brand new / empty catalog)
mode = gap ? SNAPSBY : OWN_CONTENT
```
`OWN_CONTENT` mode NEVER mentions the marketplace. Fail-closed: if brand profile can't be
read, default to `OWN_CONTENT` (never push Snapsby on missing data).

## 4. AI placement + guardrails (LOCKED — Ash owns the impl)
- Exactly **one** AI call, in influora-ai, AFTER rules decided everything. Phrasing only.
- **Cheap model**: Haiku-class (`claude-haiku-4-5-20251001`). Not Opus/Sonnet. Capped (Rohan).
- **Structured JSON out**, parsed defensively: strip code fences, try/catch, length-check.
- **AI invents no facts**: trend text, prices, video_ids all come from the Java request. The
  Java caller MUST re-validate returned `video_ids` ⊆ the ids it sent, and reject/scrub any
  price the model echoes. This is the hallucination kill-switch AND the injection defense.
- **Fallback**: on any parse/validation failure → deterministic templated message. User never
  sees an error. `message_source=FALLBACK` logged.
- **Flywheel**: every nudge logged with shown/clicked/purchased from day one.

## 5. Security rules (LOCKED — Priya mandate, Kabir enforces at Task 11)
1. **Keys in backend `.env` only.** `NEWSAPI_KEY`, `TMDB_API_KEY`, `YOUTUBE_API_KEY`,
   `GOOGLE_TRENDS_*`/SerpAPI in the n8n credential store or `.env`. `ANTHROPIC_API_KEY`
   stays in influora-ai `.env` (already there). **Never** `VITE_*`/client-exposed. The React
   card calls Spring; Spring calls influora-ai server-to-server (JWKS, per TECH-STACK).
2. **Workspace isolation:** every `nudge_log`/`brand_profiles` read resolves the row then
   checks `workspace_id` ownership via the `BrandContextService`/`requireBrandWorkspace`
   pattern. Never trust a path-param id. (Cross-cutting rule 2.)
3. **Prompt-injection:** brand-supplied strings (brand name, niche) are UNTRUSTED. Pass them
   as delimited data in the prompt, and enforce §4's "video_ids ⊆ sent ids / no echoed price"
   output validation. A malicious brand name cannot make the model surface facts it wasn't given.
4. **No PII in logs** (§1d). No model prompts/keys in `nudge_log` or app logs.
5. **Fail-closed** at every stage (matches `03-PIPELINE-CHAIN.md`): unsure → say nothing.
6. **New deps** (`pytrends`/SerpAPI client, any n8n node lib, Java HTTP client if new) → log
   in `wiki/tech/approved-deps.md` before install. n8n itself is infra, not a repo dep.

## 6. Persona-name rule
"Meera" nudge persona is a **placeholder** — single config constant (one line to rename).
Do not hardcode the name across templates; reference the config value.

## 7. What Priya approves / rejects
- ✅ APPROVED: rule-based logic in Java, one cheap AI phrasing call in influora-ai, MySQL/Flyway
  schema above, on-open trigger for v1 (idle-timer = Phase 2), fail-closed everywhere.
- ❌ REJECTED for v1: any AI call from Java or frontend; any secret in `VITE_*`; scraping
  IG/TikTok; new paid source without Swapnil approval; storing live-source payloads in `trends`.

---
**Priya sign-off:** Architecture + schema + security LOCKED. Tasks 2, 3, 5 unblocked. — Priya · 2026-07-13
