#!/usr/bin/env python3
"""ledger.py — the sanctioned way IN to the failure ledger.
add:  ledger.py add --who W --class C --where PATH --symptom S --missed-by M [--fix F]
list: ledger.py list [--open]
Records enter open. They leave ONLY via promote.py. RETENTION rule 3 applies:
no anonymous writes, append-only, hand-editing failures.jsonl is a violation."""
import json, sys, os, datetime
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import data_dir
if len(sys.argv) < 2:
    print(__doc__); sys.exit(64)
LED = os.path.join(data_dir(), "ledger", "failures.jsonl")
recs = [json.loads(l) for l in open(LED) if l.strip()] if os.path.exists(LED) else []
a = sys.argv
def arg(k, d=None): return a[a.index(k)+1] if k in a else d
if a[1] == "list":
    show = [r for r in recs if r["status"] == "open"] if "--open" in a else recs
    for r in show: print(f"{r['id']}  {r['status']:<7} {r['class']:<32} {r['where'][:50]}")
    print(f"({len(show)} shown / {len(recs)} total)"); sys.exit(0)
if a[1] != "add": print(__doc__); sys.exit(64)
who = arg("--who")
if not who: print("REFUSED: --who required (no anonymous failures)"); sys.exit(1)
for req in ("--class", "--where", "--symptom", "--missed-by"):
    if not arg(req): print(f"REFUSED: {req} required — a failure without it can't be learned from"); sys.exit(1)
nxt = max([int(r["id"].split("-")[1]) for r in recs], default=0) + 1
rec = {"id": f"F-{nxt:04d}", "class": arg("--class"), "where": arg("--where"),
       "symptom": arg("--symptom"), "missed_by": arg("--missed-by"),
       "promoted_to": "", "status": "open", "fix": arg("--fix", ""),
       "opened_by": who, "opened": datetime.date.today().isoformat()}
open(LED, "a").write(json.dumps(rec) + "\n")
jp = os.path.join(data_dir(), "journal.jsonl")
open(jp, "a").write(json.dumps({"ts": datetime.datetime.now().isoformat(timespec="seconds"),
    "who": who, "what": "ledger-add", "file": rec["where"], "task": "", "stage": "intake",
    "gate_result": rec["id"]}) + "\n")
print(f"{rec['id']} opened · {rec['class']} · closes only via promote.py")
