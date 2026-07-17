# 🧭 Trend-Spark AI — MASTER INDEX

**Project:** Snapsby Trend-Spark AI · **Persona:** Meera (placeholder — rename later)
**Owners:** Priya (arch) · Ash (AI) · Arjun (pipeline) · Rohan (billing) · Swapnil (final)
**Updated:** 2026-07-12

> This is the control file. Every agent reads THIS first, does their task, then marks it
> DONE with their name + date. The loop repeats until all rows are ✅.

---

## 📁 Connected files (click to open — full paths for agents)

Every agent MUST open and read its file before starting. Click the link, read the full .md.

| # | File (clickable) | What's inside | Owner |
|---|------------------|---------------|-------|
| A | [Snapsby-TrendSpark-AI-Spec.md](<./../Snapsby-TrendSpark-AI-Spec.md>) | Full architecture + anti-spam gate + roadmap | Priya |
| B | [01-TASK-ASSIGNMENTS.md](<./01-TASK-ASSIGNMENTS.md>) | Who does what, in order | Arjun |
| C | [02-API-KEYS-REQUIRED.md](<./02-API-KEYS-REQUIRED.md>) | Keys, from whom, billing | Dev/Vikram/Rohan |
| D | [03-PIPELINE-CHAIN.md](<./03-PIPELINE-CHAIN.md>) | Chain + error gates + approvals | Priya/Ash |

**Absolute paths (agents open these directly):**

```
A · C:\Users\Sage world\Downloads\New Influora Ai\New Influora\Snapsby-TrendSpark-AI-Spec.md
B · C:\Users\Sage world\Downloads\New Influora Ai\New Influora\trendspark\01-TASK-ASSIGNMENTS.md
C · C:\Users\Sage world\Downloads\New Influora Ai\New Influora\trendspark\02-API-KEYS-REQUIRED.md
D · C:\Users\Sage world\Downloads\New Influora Ai\New Influora\trendspark\03-PIPELINE-CHAIN.md
```

> Each work-tracker task below names the file (A/B/C/D) the agent must read first.

---

## 🔁 THE MULTI-AGENT LOOP (the formula)

```
Arjun reads INDEX → picks next PENDING task whose blockers are ✅
        ↓
Assigns to the owner agent
        ↓
Agent does the work in its own context
        ↓
Agent writes result to SHARED_CONTEXT.md  (FROM→TO | TASK | FILES | STATUS | NEXT)
        ↓
Agent marks the row here:  ✅ DONE by <agent> · <date> · <output file>
        ↓
Next agent's blocker is now clear
        ↓
   ── LOOP repeats until every row = ✅ ──
        ↓
All ✅ → Ash (AI review) + Swapnil (business) → SHIP
```

**Rules of the loop:**
1. No agent starts a task until its **Blocked by** rows are ✅.
2. Every finished task MUST be signed: `✅ DONE by <agent> · <date>`.
3. If a task FAILS a check → set to 🔴 BLOCKED, write reason, route back via Arjun. Loop continues on other unblocked tasks.
4. Loop ends only when all rows ✅ AND both final sign-offs are ✅.

---

## ✅ WORK TRACKER (agents update this)

Status key: ⬜ Pending · 🟨 In progress · ✅ Done · 🔴 Blocked

| # | Task | Owner | Blocked by | Status | Signed off (agent · date · file) |
|---|------|-------|-----------|--------|----------------------------------|
| 1 | Approve architecture + schema + security rules | Priya | — | ✅ | Priya · 2026-07-13 · wiki/architecture/trendspark-priya-schema-lock.md |
| 2 | Confirm free-tier + set spend cap/alert | Rohan | 1 | ✅ | Rohan · 2026-07-13 · wiki/tech/budget-approvals-trendspark.md |
| 3 | n8n daily trend-pull + theme tagging | Dev | 1,2 | ✅ | Dev · 2026-07-13 · trendspark/n8n/* (tagger self-test 5/5) |
| 4 | Backend: trends, brand_profile, gap-check, catalog-match, AI route, nudge_log | Vikram | 1,3 | ✅ | Vikram · 2026-07-13 · V51__trendspark.sql + service/trendspark/* + web/TrendSparkController (mvn -o compile SUCCESS) |
| 5 | Theme taxonomy + campaign rulebook + Meera tone | Nisha/Tejas | 1 | ✅ | Nisha/Tejas · 2026-07-13 · influora-api/src/main/resources/trendspark/*.json,meera-tone-guide.md |
| 6 | Meta API wiring: read brand's own IG for gap-check + personalization | Vikram | 4 | ✅ | Vikram · 2026-07-13 · service/trendspark/BrandOwnContentService.java (+ ContentGap/ThemeMatch fixes, mvn compile SUCCESS) |
| 7 | Frontend: soft nudge card + "use own content" mode + preview handoff | Ananya | 4,5 | ✅ | Ananya · 2026-07-13 · src/components/trendspark/TrendSparkNudgeCard.tsx + hook + api.ts + App.tsx QueryClientProvider (tsc exit 0) |
| 8 | AI layer: prompt, cheap model, output validation, fallback, eval + logging | Ash | 4,5 | ✅ | Ash · 2026-07-13 · influora-ai/app/routes/trendspark.py + prompt/trendspark.py + tests/eval (25/25, 49/49 no regress) |
| 9 | QA: standards + spec compliance | Kavya | 6,7,8 | ✅ | Kavya · 2026-07-13 · 🟡 CONDITIONAL PASS (no blockers) · wiki/errors/trendspark-t9-kavya-qa.md |
| 10 | Local verify: build/test/curl + end-to-end nudge | Meera | 9 | ✅ | Meera · 2026-07-13 · mvn 897/11F/8E (0 new TS), py 25/25+78/78, tsc/vite 0 · curl→PP-1 · wiki/reports/2026-07-13/trendspark-t10-meera-verify.md |
| 11 | Security audit: keys, injection, PII in logs | Kabir | 10 | ✅ | Kabir · 2026-07-13 · CLEARED (no Crit/High; 4 M/L follow-ups) · wiki/security/trendspark-t11-kabir-audit.md |
| 12 | AI review: logic + safety + eval pass | Ash | 11 | ✅ | Ash · 2026-07-13 · PASS (no P0, eval 25/25 re-run; 1 P1: request.json 500) · wiki/ai-review/trendspark-t12-ash-review.md |
| 13 | Business sign-off: feel, brand, non-spammy | Swapnil | 12 | ✅ | Swapnil · 2026-07-13 · GO-WITH-CONDITIONS (PP-1 + live cap = blockers) · wiki/decisions/2026-07-13-trendspark-swapnil-signoff.md |
| 14 | Post-launch: real ₹/nudge cost report | Rohan | 13 | ✅ | Rohan · 2026-07-13 · framework+SQL ready; real ₹ PENDING 1st live week · wiki/reports/2026-07-13/trendspark-t14-cost-framework.md |

---

## 🚦 FINAL GATES (loop cannot close until all ✅)

| Gate | Decider | Status |
|------|---------|--------|
| Logic + AI correct & safe | **Ash** | ✅ (T12 — no P0, eval 25/25) |
| Secure | **Kabir** | ✅ (T11 — no Crit/High) |
| Cost sane | **Rohan** | ✅ (T2 ₹0+₹1,500 cap; T14 model) |
| Business go | **Swapnil** | ✅ GO-WITH-CONDITIONS (T13) |

All four gates ✅ — build loop COMPLETE (14/14 rows ✅ · 2026-07-13).

> **⚠️ Production release is NOT yet authorized.** Per Swapnil's GO-WITH-CONDITIONS, Arjun releases to prod via n8n ONLY after the mandatory **PP-1 live gate** (real-host boot + `curl GET /api/v1/brand/trendspark/nudge` end-to-end + Flyway V51 run against real MySQL — none runnable in this sandbox) AND the LIVE Anthropic ₹1,500/mo cap is configured AND fast-follows land: **Ash P1** (`request.json` 500 guard, before PP-1) and **Kabir M1** (self-spend GET throttle, by 2026-07-20). Everything up to merge is done and verified; nothing has shipped to a real brand.

---

## 📝 How an agent marks done (example)

```
Row 3 → ✅ DONE by Dev · 2026-07-14 · trends table live, 42 trends tagged
        SHARED_CONTEXT: Dev→Vikram | trend feed ready | trends table | DONE | build gap-check
```

Then Arjun sees row 4's blocker (3) is now ✅ and assigns Vikram. Loop continues.
