# Trend-Spark AI — Task Assignments

**Project:** Snapsby Trend-Spark AI (persona name: **Meera** — placeholder, change later)
**Source spec:** `Snapsby-TrendSpark-AI-Spec.md`
**Orchestrator:** Arjun · **Tech owner:** Priya · **AI owner:** Ash · **Billing:** Rohan
**Final go/no-go:** Ash + Swapnil (Arjun may sign off on non-strategic stages)
**Date:** 2026-07-12

---

## Build order (each stage feeds the next — see `03-PIPELINE-CHAIN.md`)

| # | Owner | Task | Input (from) | Output (to) |
|---|-------|------|--------------|-------------|
| 1 | **Priya** | Approve architecture + schema, set security rules (keys in `.env` only) | Spec | Locked `TECH-STACK` note → Arjun |
| 2 | **Rohan** | Confirm all v1 sources are free-tier; set spend cap + alert | API sheet | Budget OK → Arjun |
| 3 | **Dev** | Build n8n daily trend-pull workflow (6 AM) + theme auto-tagging | Priya schema, API keys | `trends` rows → Vikram |
| 4 | **Vikram** | Backend: `trends` table, brand-profile table, content-gap check, catalog-match query, AI-call route, `nudge_log` table | Dev feed, Priya schema | API routes → Ash + Kavya |
| 5 | **Nisha/Tejas** | Theme taxonomy + campaign-type rulebook + Meera tone rules (warm, NO "dear/darling") | Spec | Rulebook files → Vikram + Ash |
| 6 | **Ananya** | Frontend: soft nudge card (on-open), preview handoff to Snapsby, "use your own content" mode | Vikram routes | UI → Kavya |
| 7 | **Ash** | Prompt design, cheap-model choice, output validation, fallback message, eval set + flywheel logging | Vikram route, rulebook | AI layer → Kavya |
| 8 | **Kavya** | QA all code vs standards + spec | 4,6,7 | Pass → Meera(verify) |
| 9 | **Meera (verifier)** | Local build/test/curl, confirm trend feed + nudge end-to-end | Kavya pass | Report → Kabir |
| 10 | **Kabir** | Security audit (keys, prompt-injection, data leaks) | Meera pass | Pass → Ash/Swapnil |
| 11 | **Ash + Swapnil** | Final AI review + business sign-off | Kabir pass | SHIP / HOLD |
| 12 | **Rohan** | Log real token cost after first live week | nudge_log | Cost report → Swapnil |

---

## What each employee actually does

**Priya (CTO)** — Locks the data model: `trends`, `brand_profile`, `nudge_log`, catalog theme tags. Sets the rule: every API key lives in `.env`, never in frontend code. Approves the whole shape before anyone codes.

**Dev (Automation)** — Owns the n8n workflow. Every morning it calls the trend sources, merges results, tags each trend with themes + peak window + expiry, writes to the `trends` table. Also builds the auto-delete of expired trends.

**Vikram (Backend)** — The engine room. Builds:
- `brand_profile` (themes, last-posted date, own-content library link)
- **content-gap check** (the anti-spam gate — is brand's shelf empty?)
- **catalog-match query** (find Snapsby videos matching niche + theme)
- the route that calls the AI to phrase the nudge
- `nudge_log` (every nudge + click + purchase — the flywheel)

**Nisha + Tejas (Content/Marketing)** — Write the two rulebooks the engine reads: the **theme taxonomy** (which words tag to which themes) and the **campaign-type rulebook** (movie→hype, festival→seasonal…). Also write Meera's **tone guide**: warm, Indian-casual, uses the brand's name and energy words, **no romantic pet-names** (Tejas ruling).

**Ananya (Frontend)** — Builds the soft nudge card (on-open, dismissible — not a popup), the "use your own content" default view, and the click-through that hands the brand into Snapsby's preview page.

**Ash (AI/ML)** — Owns the one AI call. Picks a cheap model (phrasing, not reasoning), writes the structured prompt, validates JSON output defensively, sets the fallback templated message if the AI fails, and stands up the eval set (10+ golden nudges) + flywheel logging from day one.

**Kavya (QA)** → **Meera (local verify)** → **Kabir (security)** — the quality chain. No code ships until all three pass.

**Rohan (CFO)** — Confirms free-tier before build, sets the spend cap + alert, and after week 1 logs real per-nudge token cost so we know the true unit economics.

---

## Key rules carried from Swapnil's decisions
- **v1 trigger:** on-open soft card (idle-timer = Phase 2)
- **Target:** brands first (creators = Phase 3)
- **Snapsby only on content-gap** (anti-spam gate — section 5b of spec)
- **Meera tone:** warm, personalized, NO "dear/darling"
- **Persona name "Meera" is a placeholder** — rename later, keep it a single config value so the change is one line
