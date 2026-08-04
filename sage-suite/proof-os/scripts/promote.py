#!/usr/bin/env python3
"""promote.py — no failure closes without an executable check.
Usage: promote.py F-0009 gates/newcheck.py     (close via gate — must exist)
       promote.py F-0009 --unautomatable "who accepted + why"
       promote.py --recurrence                  (the honesty metric)"""
import json, sys, os, tempfile
if len(sys.argv) < 2:
    print(__doc__ or "usage: promote.py <F-id> <gate> | --unautomatable <who+why> | --recurrence"); sys.exit(64)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import data_dir
LED = os.path.join(data_dir(), "ledger", "failures.jsonl")
recs = [json.loads(l) for l in open(LED) if l.strip()]
if sys.argv[1] == "--recurrence":
    classes = {}
    for r in recs: classes.setdefault(r["class"], []).append(r)
    rep = sum(len(v) - 1 for v in classes.values() if len(v) > 1)
    print(f"records {len(recs)} · classes {len(classes)} · recurrences {rep} · recurrence_rate {rep/max(len(recs),1)*100:.0f}%")
    for c, v in classes.items():
        if len(v) > 1: print(f"  REPEAT ×{len(v)}: {c} → this class needs a stronger gate")
    sys.exit(0)
fid, arg = sys.argv[1], sys.argv[2]
for r in recs:
    if r["id"] == fid:
        if arg == "--unautomatable":
            r["promoted_to"] = f"UNAUTOMATABLE: {sys.argv[3]}"
        else:
            cand = [os.path.join(data_dir(), arg),
                    os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), arg), arg]
            hit = next((c for c in cand if os.path.isfile(c)), None)
            if not hit:
                print(f"REFUSED: {arg} not found in .proof-os/ or plugin. Write the gate first (new gates go in .proof-os/gates/), then close."); sys.exit(1)
            r["promoted_to"] = arg
        r["status"] = "closed"
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(LED)); os.close(fd)
        open(tmp, "w").write("\n".join(json.dumps(x) for x in recs) + "\n")
        os.replace(tmp, LED)  # atomic: crash mid-close cannot truncate history
        print(f"{fid} closed → {r['promoted_to']}"); sys.exit(0)
print(f"{fid} not found"); sys.exit(1)
