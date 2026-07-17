# SHARED_CONTEXT.md — ACTIVE TASK

> Pipeline bus (Arjun owns). Holds the ACTIVE task only. Terse handoffs:
> `FROM → TO | TASK | FILES | STATUS | NEXT`.

## TASK: TrendSpark "smart AI" — LLM Recovery Tagger

**Owner:** Arjun (Eng Lead) · **Feature:** recover trends the deterministic n8n
tagger drops, by mapping free text onto the closed theme/campaign vocabulary
with a cheap Haiku call. Spans Backend + Security + Frontend.

**Why:** `theme-tagger.js` returns `themes:[]` on any unseen text → n8n drops the
row → good trends silently lost. This pass rescues them onto the SAME closed
vocab; it can only add correctly-tagged trends, never write garbage.

### Handoffs

```
FROM Arjun → Vikram | Build recovery tagger (Python FastAPI) | app/routes/trend_tag.py, app/prompt/trend_tag.py, app/config.py, app/main.py | DONE | Haiku, static-secret auth, spend gate, closed-vocab validation, fail-closed drop
FROM Vikram → Kabir | Security audit of /internal/trendspark/tag | docs/security/trendspark-recovery-tagger-audit.md | PASS | static-secret = accepted v1 debt (T-DEBT-1); rotate quarterly + network-bind
FROM Arjun → Ananya | AI-recovered trend transparency chip | src/components/trendspark/ThemeProvenanceBadge.tsx, src/lib/api.ts (optional themeSource), TrendSparkNudgeCard.tsx | DONE | backward-compatible optional field; renders only for AI_RECOVERED
FROM Ananya → Meera | Verify | tests | PASS | influora-ai: 274 passed (21 new tagger tests); frontend badge Vitest: 3 passed; tsc clean
FROM Kabir → Arjun | Security gate | — | APPROVED | cleared for production; debt tracked
```

### Pipeline status

| Stage | Owner | Status |
|-------|-------|--------|
| Architecture fits closed-vocab schema-lock | Priya (via existing lock) | ✅ conforms |
| Backend — recovery tagger + prompt + config | Vikram | ✅ DONE |
| Frontend — provenance badge | Ananya | ✅ DONE |
| QA / build | Meera | ✅ 274 + 3 tests green, tsc clean |
| Security audit (OWASP + adversarial) | Kabir | ✅ PASS |
| Sign-off | Arjun | ✅ ready |

---

## TASK: TrendSpark n8n pipeline fixes (Dev, Priya-approved)

```
FROM Dev → Priya   | n8n review: 6 fixes need arch/schema sign-off | trendspark/n8n/trend-pull-workflow.json | APPROVED | see rulings below
FROM Priya → Dev   | Sign-off | V20260716120000__trends_theme_source.sql, Trend.java, TrendThemeSource.java | APPROVED | timestamp migration; theme_source additive+defaulted; DB unique key DEFERRED (needs legacy cleanup); UTC standard confirmed
FROM Dev → Kavya   | n8n pipeline fixes | trendspark/n8n/trend-pull-workflow.json (Normalize, Theme Tagger, INSERT, DELETE, 3x HTTP), + migration/entity/enum | READY | simulated green (5 cases); theme-tagger self-test PASS
```

**What Dev shipped:**
- **Recovery tagger WIRED:** Theme Tagger node now POSTs `themes:[]` trends to
  `/internal/trendspark/tag` (Bearer `$TREND_TAG_INGEST_SECRET`, base `$INFLUORA_AI_INTERNAL_URL`),
  fail-closed + capped at `$TREND_TAG_MAX_RECOVERY_PER_RUN` (40); on `recovered:true`
  writes `themes/campaign_type/peak_window_days` + `theme_source='AI_RECOVERED'`.
- **NewsAPI category pollution fixed** (`'entertainment'` → `''`).
- **Within-run dedup** by `region|detected_date|lower(trend_text)`.
- **UTC everywhere** (row stamps `getUTC*`; DELETE `UTC_TIMESTAMP(6)`).
- **HTTP source resilience** (`onError: continueRegularOutput` on TMDb/NewsAPI/YouTube).
- **Schema:** `trends.theme_source VARCHAR(16) NOT NULL DEFAULT 'KEYWORD'`; entity + `TrendThemeSource` enum.

### Deferred / ops (tracked)

- **DB uniqueness** on `trends` natural key (`region + detected_date + normalized trend_text`):
  deferred to a follow-up migration after a one-time legacy-dup cleanup (Priya).
- **Ops:** set `TREND_TAG_INGEST_SECRET` + `INFLUORA_AI_INTERNAL_URL` in the n8n env;
  network-bind the endpoint; add the secret to the quarterly rotation table.
- **Guide reconciliation:** the backend guide says "never use legacy V51 style" but the
  repo has both — recent migrations use timestamps (current convention). Update the guide.
- **Kavya/Meera:** run the workflow in n8n staging once the env vars are set (live n8n
  run can't be exercised from here).
