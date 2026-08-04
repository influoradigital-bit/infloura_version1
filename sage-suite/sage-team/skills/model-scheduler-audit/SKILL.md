---
name: model-scheduler-audit
description: Scan a codebase, verify whether each model/feature is actually wired up and working, and produce a single self-contained HTML dashboard that lists every feature, marks each one aligned or broken, and shows a completion percentage computed from the real code. Use whenever the user wants a project audit, a feature/model checklist, a "what's working" report, an alignment check, a health dashboard, a progress dashboard, or an HTML report of feature status — even if they don't use the word "audit". Trigger on phrases like "check the models", "is it aligned", "give me a percentage", "feature dashboard", "what's built vs broken", or "audit the codebase".
---

# Model Scheduler Audit

Produce a **single HTML file** that reports, per feature/model, whether the implementation is present and correctly wired, marks it aligned or broken, and shows an overall completion percentage derived from the actual source code — never from docs.

## Core principle

Code is the only source of truth. Ignore README, comments, `.md` files, and TODOs when deciding if something works. A feature counts as **aligned** only if you can trace it end-to-end in the code (definition → usage → wiring). If you can't, it's **broken** or **missing**, and you must say why with a file+line reference.

## Workflow

### 1. Locate the codebase
Look in `/mnt/user-data/uploads` and the working directory. If no code is present, stop and ask the user to upload the project — do not invent findings.

### 2. Discover models and features
Build the feature list from the code itself, not from any spec:
- Model/config registries, enums, or dicts (e.g. a `MODELS = {...}` map, a schema, a routing table).
- Route handlers / API endpoints, exported components, background jobs, CLI commands.
- Each entry the code claims to support is one row in the report.

### 3. Verify each feature end-to-end
For every feature, trace the chain that must exist for it to work. A generic chain:

```
declaration → referenced/imported → invoked → result consumed → surfaced (API/UI/output)
```

Record for each link whether it's present (with `file:line` evidence). Assign a status:
- **aligned** — full chain present and consistent.
- **partial** — declared and partly wired, but a link is missing (e.g. defined but never invoked, or invoked but result discarded).
- **broken** — a link is wrong (bad import, wrong name, dead reference, contradictory wiring).
- **missing** — referenced somewhere but never implemented.

### 4. Compute the percentage from code
Score each feature: aligned = 1.0, partial = 0.5, broken/missing = 0.0.
Overall completion = `sum(scores) / feature_count * 100`, rounded to one decimal.
Show both the overall number and the per-feature contribution. The number must be reproducible from the evidence table — never estimated.

### 5. Emit the HTML dashboard
Generate ONE self-contained `.html` file (inline CSS, no external assets, no network calls) written to `/mnt/user-data/outputs/`. Then call `present_files` on it. Use the helper script to guarantee a consistent, valid file:

```bash
python /path/to/model-scheduler-audit/scripts/build_dashboard.py audit.json /mnt/user-data/outputs/audit-dashboard.html
```

Where `audit.json` is data you assemble (see schema below). You may also hand-write the HTML if the script doesn't fit, but keep it single-file and self-contained.

## audit.json schema

```json
{
  "project": "Sage Digital",
  "generated": "2026-07-15",
  "overall_pct": 72.5,
  "features": [
    {
      "name": "Claude routing",
      "status": "aligned",
      "score": 1.0,
      "chain": [
        {"step": "MODELS map", "ok": true,  "evidence": "sage.py:14"},
        {"step": "route picks model", "ok": true,  "evidence": "sage.py:88"},
        {"step": "response parsed", "ok": false, "evidence": "sage.py:130 — result never read"}
      ],
      "note": "Defined and called, but the parsed response is discarded."
    }
  ]
}
```

`status` ∈ `aligned | partial | broken | missing`. Every feature needs at least one `chain` entry with real `evidence`.

## Report rules
- Sort features worst-first (missing/broken at top) so the user sees problems immediately.
- Color: aligned = green, partial = amber, broken/missing = red.
- Header shows project name, date, overall percentage, and a progress bar.
- Every "broken"/"missing"/"partial" row must carry a one-line reason with a file reference. No bare verdicts.
- Never inflate the score to look complete. A blunt low number is the point.

## Output format
End your chat reply with a 3–5 line summary: overall %, count aligned/partial/broken/missing, and the single most important fix. Then present the HTML file. Keep prose minimal — the dashboard is the deliverable.
