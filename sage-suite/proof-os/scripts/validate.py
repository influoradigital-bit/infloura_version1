#!/usr/bin/env python3
"""validate.py — the trust law, enforced. Computes scores; rejects self-scored
reports; model-attested rows are CAPPED at 0.5 and can never render green."""
import json, sys
if len(sys.argv) < 2:
    print("usage: validate.py <report.json>"); sys.exit(64)
SCORE = {"aligned": 1.0, "partial": 0.5, "broken": 0.0, "missing": 0.0}
data = json.load(open(sys.argv[1]))
if isinstance(data, dict):
    for k in ("alignment_pct", "overall_pct", "score"):
        if k in data:
            print(f"REJECTED: report supplies its own '{k}'. Scores are computed here."); sys.exit(1)
rows = data["rows"] if isinstance(data, dict) else data
if not rows:
    print("REJECTED: empty report — zero rows proves nothing."); sys.exit(1)
errs, capped = [], 0
for i, r in enumerate(rows):
    for f in ("subject", "owner", "status"):
        if f not in r: errs.append(f"row {i}: missing '{f}'")
    if r.get("status") not in SCORE: errs.append(f"row {i}: bad status '{r.get('status')}'")
    if "oracle" not in r.get("evidence", {}): errs.append(f"row {i} ({r.get('subject')}): evidence.oracle REQUIRED")
if errs:
    print("REJECTED:"); [print(" ", e) for e in errs]; sys.exit(1)
def eff(r):
    s = SCORE[r["status"]]
    if r["evidence"]["oracle"] == "model" and s > 0.5:
        return 0.5, True   # ceiling: a model's green is worth at most half
    return s, False
scores = []
for r in rows:
    s, c = eff(r)
    if c: capped += 1; r["_render"] = "believed"
    scores.append(s)
pct = round(sum(scores) / len(rows) * 100, 1)
prows = [r for r in rows if r["evidence"]["oracle"] != "model"]
ppct = round(sum(SCORE[r["status"]] for r in prows) / len(rows) * 100, 1)
print(f"VALID · {len(rows)} rows · alignment {pct}%{' (capped)' if capped else ''} · proved {ppct}% ({len(prows)}/{len(rows)} rows) · believed {len(rows)-len(prows)} · capped {capped}")
if capped:
    print(f"  NOTE: {capped} model-attested row(s) claimed 'aligned' — rendered believed, scored at the 0.5 ceiling. Green requires a real oracle.")
