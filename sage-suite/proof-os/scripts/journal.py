#!/usr/bin/env python3
"""journal.py — append-only change journal. Every change, by whom.
add:   journal.py add --who vikram --what edit --file route.ts --task T-1 --stage produce [--gate aligned]
query: journal.py query [--who X] [--task T] [--today]"""
import json, sys, os, datetime
from _common import data_dir
J = os.path.join(data_dir(), "journal.jsonl")
a = sys.argv
if len(a) < 2:
    print("usage: journal.py add --who W --what X [...] | query [--who] [--task] [--today]"); sys.exit(64)
def arg(k, d=None): return a[a.index(k)+1] if k in a else d
if a[1] == "add":
    if not arg("--who"): print("REFUSED: no anonymous writes (--who required)"); sys.exit(1)
    rec = {"ts": datetime.datetime.now().isoformat(timespec="seconds"),
           "who": arg("--who"), "what": arg("--what","?"), "file": arg("--file",""),
           "task": arg("--task",""), "stage": arg("--stage",""), "gate_result": arg("--gate","")}
    open(J,"a").write(json.dumps(rec)+"\n"); print("journaled:", rec["who"], rec["what"], rec["file"])
elif a[1] == "query":
    rows = [json.loads(l) for l in open(J)] if os.path.exists(J) else []
    if arg("--who"): rows = [r for r in rows if r["who"]==arg("--who")]
    if arg("--task"): rows = [r for r in rows if r["task"]==arg("--task")]
    if "--today" in a:
        t = datetime.date.today().isoformat(); rows = [r for r in rows if r["ts"].startswith(t)]
    for r in rows: print(f"{r['ts']}  {r['who']:<10} {r['what']:<8} {r['file']:<28} {r['task']} {r['stage']} {r['gate_result']}")
    print(f"({len(rows)} events)")
