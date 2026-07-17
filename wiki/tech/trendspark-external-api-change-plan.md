# Architecture Decision & Change Plan — TrendSpark External APIs + "Make It Smart"

> Owner: **Priya (CTO)** with **Ash (AI/ML)** · 2026-07-16 · Verified against code.
> Answers: *what code changes, how much editing, how we make it smart, how we do it in n8n.*
> Companion: `wiki/ai-review/trendspark-external-api-ai-review.md` (the AI reasoning), `EXTERNAL-APIS.md`.

---

## CTO ruling in one paragraph

Going **live properly costs almost no code** — it is one n8n workflow file plus one new festival JSON, **zero application code, zero schema change**. Making it genuinely **smart** is a small, well-bounded second step (~120 lines, no new dependencies, one auth decision I rule on below). Going **deeper** (heat-ranking, dedup, semantic match, personalization) is a moderate third step with exactly **one schema migration** I approve conditionally. We do it in three tiers, in order, and we do **not** touch the safety guardrails — the intelligence goes *behind* them.

---

## The stack reality (governance flag)

`TECH-STACK.md` still says Next.js / Prisma / Vercel+Railway. **The real stack is Vite+React SPA / Spring Boot (Java 21) / FastAPI (Python 3.13) / MySQL via JPA+Flyway / n8n.** TrendSpark spans all four. I'm flagging that `TECH-STACK.md` is stale and must be reconciled — but it does not block this work. (Separate task.)

---

## How TrendSpark works today (so the edits are legible)

```
n8n 06:00 IST ─┬─ TMDb ✅  ┬─ Merge → Normalize → Theme-Tagger(keyword) → MySQL trends
               ├─ NewsAPI ✅│
               ├─ YouTube ✅│
               └─ GTrends ❌ STUB (returns [])
Java read: findActive → ThemeMatchService.score(set-overlap) → argmax → ContentGap → Catalog
           → influora-ai /internal/trendspark/nudge (Haiku, phrasing only) → NudgeLog
```
Intelligence today = keyword substring match + set-overlap. LLM = phrasing only.

---

## TIER 1 — Make it actually work (n8n only)

| # | Change | File | Edit size | App code? | Schema? | Owner |
|---|---|---|---|---|---|---|
| 1 | Google Trends: replace stub with SerpAPI `google_trends_trending_now` HTTP node + extend Normalize to map `trending_searches[]` | `trendspark/n8n/trend-pull-workflow.json` | ~40 lines | none | none | Dev |
| 2 | Festival producer node (look-ahead + lead time) + bump Merge `numberInputs` 4→5 + 1 connection | same workflow file | ~20 lines | none | none | Dev |
| 3 | Festival data file | **new** `trendspark/n8n/festivals-IN-2026.json` | ~1 file | none | none | Nisha |
| 4 | Activate + fill the 5 `REPLACE_WITH_CRED_ID`, set `"active": true` | same workflow file | config only | none | none | Dev/Meera |

**Tier 1 total: 2 files (1 edited, 1 new), ~60 lines, NO application code, NO migration.** This alone takes us from *inactive / 3-of-4 sources* to *live / all sources*. **Risk: LOW. Approved.**

---

## TIER 2 — Make it smart: the LLM recovery tagger (highest ROI)

**Problem (Ash):** the keyword tagger drops any trend it doesn't literally know — "Pushpa 3 first look", "Deepavali", new slang → `themes=[]` → row discarded, silently. Fail-closed is correct, but we lose real trends.

**Fix:** keep the free keyword tagger as the fast path. **Only on `themes=[]`**, call a cheap Haiku structured-output classifier constrained to the **locked vocab**, then re-apply the closed-vocab filter (nothing unsafe can enter).

| # | Change | File | Edit size | New dep? |
|---|---|---|---|---|
| 5 | `POST /internal/trendspark/tag` — LLM→closed-vocab tagger endpoint | **new** `influora-ai/app/routes/trend_tag.py` | ~80 lines | none (reuses `ClaudeProvider`, `TRENDSPARK_MODEL`) |
| 6 | Tagger prompt (closed-vocab, invent-nothing, subset-validated) | edit `influora-ai/app/prompt/trendspark.py` | ~30 lines | none |
| 7 | Mount router | edit `influora-ai/app/main.py` | +3 lines | none |
| 8 | n8n: IF `themes==[]` → HTTP call tagger → merge back before insert | `trend-pull-workflow.json` | ~1 node + wiring | — |
| 9 | Config: ingest auth secret | edit `influora-ai/app/config.py` + `.env.example` | ~4 lines | none |

**Tier 2 total: ~3 code files (1 new) + workflow, ~120 lines, NO new dependency, same cheap model.**

### 🔒 CTO auth ruling (this is the one real decision)
The existing `/internal/*` endpoints authenticate with **Spring-issued service tokens** — n8n cannot mint those. I rule: **v1 uses a dedicated static shared-secret header** (`TREND_TAG_INGEST_SECRET`) stored in the n8n credential store, exactly like the TMDb/NewsAPI keys, with the endpoint additionally bound to the internal network (not internet-exposed). This is a **new auth path → tag Kabir for review**. It is a deliberate, documented v1 shortcut. The clean long-term fix (Tier 4) is to fold trend-pull orchestration into Java so it uses the real service-token path — not now.

**Risk: MEDIUM (new auth surface). Approved with Kabir sign-off.**

---

## TIER 3 — Deeper intelligence (do after 1 & 2 prove out)

| # | Change | File(s) | Edit size | Schema? |
|---|---|---|---|---|
| 10 | **Heat ranking:** persist Google Trends `search_volume` + `increase_percentage`; blend into score | **new migration** `V20260716xxxxxx__trends_heat.sql` (+`Trend.java` +2 fields, +n8n insert cols, `ThemeMatchService.java` ~20 lines) | ~50 lines across 4 files | **YES — 1 migration** |
| 11 | **Dedup across sources:** cluster same-event rows, merge `source[]` | n8n tagger JS (recommended, no app change) ~20 lines | ~20 lines | none |
| 12 | **Pass recent-content themes to phrasing** (gap-check already computes them, comment says "not consumed yet") | `TrendSparkNudgeService.java` ~10 + `TrendSparkAiDtos.java` + Python parse/prompt ~15 | ~25 lines | none |
| 13 | **Semantic match (embeddings)** — DEFER | **new** `influora-ai/app/providers/embeddings.py` (Gemini embeddings via existing `google-genai` dep) + cache + blend | ~80 lines | none |
| 14 | **Flywheel/eval:** few-shot from top-CTR nudges + golden eval set + drop/FALLBACK-rate metrics | `prompt/trendspark.py` + new `tests/eval/…` | ~40 lines | none |

**Tier 3 total: ~6–8 files, ~200 lines, exactly ONE migration.**

### 🔒 CTO schema ruling
Migration #10 is the only schema change. **Approved conditionally:** columns are `NULL`-able and additive (no backfill, no lock risk on `trends`), must follow the **timestamp naming convention** (`V20260716HHMMSS__…`, not the legacy `V51` style), and the n8n insert mapping + `Trend.java` entity must land in the **same PR** — because n8n writes `trends` directly, an entity/column drift breaks the pull silently. #13 (embeddings) uses the already-approved `google-genai` lib, so **no new dependency approval needed**; still, DEFER it until Tier 1/2 show real usage.

---

## "How much are we editing?" — the number you asked for

| Goal | Files | ~Lines | New deps | Schema | App code | Effort |
|---|---|---|---|---|---|---|
| **Live & correct** (T1) | 2 | ~60 | 0 | no | none | ~½ day |
| **+ Smart recovery tagger** (T2) | ~3 | ~120 | 0 | no | Python+n8n | ~1 day |
| **+ Deeper intelligence** (T3) | ~6–8 | ~200 | 0 | 1 migration | Java+Python | ~2–3 days |

**Bottom line:** to *properly work*, we edit **one workflow file and add one JSON** — no application code. Everything smarter is incremental on top, no rewrites, no new libraries.

---

## How we implement it in n8n (target topology)

```
Schedule 06:00 IST
  ├─ TMDb (IN)            ┐
  ├─ NewsAPI (IN)         │
  ├─ YouTube (IN)         ├─► Merge (numberInputs: 5) ─► Normalize ─► Keyword Tagger
  ├─ Google Trends  ◄── NEW: SerpAPI google_trends_trending_now (geo=IN, hours=24, only_active)
  └─ Festival Producer ◄── NEW: reads festivals-IN-2026.json, emits look-ahead + lead time
                                        │
                         ┌──────────────┴───────────────┐
                    themes != []                    themes == []
                         │                               │
                         │                     NEW: HTTP → influora-ai
                         │                     /internal/trendspark/tag (LLM→closed vocab)
                         │                               │
                         └──────────► Dedup (merge same-event) ─► MySQL INSERT trends
Schedule 06:30 IST ─► DELETE expired            (unchanged)
```

Rules that stay non-negotiable in n8n: keys **only** in the n8n credential store (never in the workflow JSON — already correct); every HTTP node keeps `retryOnFail + onError:continueRegularOutput` so one dead source never sinks the run; the tagger call is **best-effort** (on failure the row simply stays `themes=[]` and is dropped — never a hard error).

---

## Handoff (SHARED_CONTEXT.md)

```
FROM Priya → Dev     | T1 GoogleTrends+Festival nodes + activate | trendspark/n8n/trend-pull-workflow.json, trendspark/n8n/festivals-IN-2026.json | READY | needs SerpAPI + n8n creds
FROM Priya → Ash     | T2 LLM recovery tagger endpoint + prompt  | influora-ai/app/routes/trend_tag.py, app/prompt/trendspark.py, app/main.py | READY | reuse Haiku; static-secret auth
FROM Priya → Kabir   | Review new n8n→influora-ai static-secret auth path | trend_tag.py | PENDING | v1 shortcut, documented
FROM Priya → Vikram  | T3 heat cols + entity + ThemeMatch blend + pass recent_themes | V20260716xxxxxx__trends_heat.sql (+entity/service) | AFTER T1/T2 | migration additive/nullable
FROM Priya → Nisha   | Maintain festival calendar JSON | trendspark/n8n/festivals-IN-2026.json | READY | date,name,lead_days
```

**Verdict: APPROVED — execute T1 now, T2 next sprint (Kabir sign-off on auth), T3 after usage data. No rewrites, no new dependencies, one additive migration.**
