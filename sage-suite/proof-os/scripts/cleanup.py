#!/usr/bin/env python3
"""cleanup.py — RETENTION law. Working .md dies after task close (archived 30 days).
close: cleanup.py close T-0031 --who arjun     purge: cleanup.py --purge"""
import sys, os, json, shutil, datetime
from _common import data_dir
D = data_dir(); ARC = os.path.join(D, "_archive")
a = sys.argv
if len(a) < 2:
    print("usage: cleanup.py close <task> --who W | --purge"); sys.exit(64)
if a[1] == "--purge":
    n = 0
    if os.path.isdir(ARC):
        for t in os.listdir(ARC):
            meta = os.path.join(ARC, t, ".purge_after")
            if os.path.exists(meta) and open(meta).read().strip() < datetime.date.today().isoformat():
                shutil.rmtree(os.path.join(ARC, t)); n += 1
    print(f"purged {n} expired archives"); sys.exit(0)
task = a[1] if a[1] != "close" else a[2]
who = a[a.index("--who")+1] if "--who" in a else None
if not who: print("REFUSED: --who required (retention law: no anonymous deletes)"); sys.exit(1)
src = os.path.join(D, "tasks", task)
if not os.path.isdir(src): print(f"no working dir for {task} — nothing to clean"); sys.exit(0)
dst = os.path.join(ARC, task); os.makedirs(ARC, exist_ok=True)
shutil.move(src, dst)
purge = (datetime.date.today() + datetime.timedelta(days=30)).isoformat()
open(os.path.join(dst, ".purge_after"), "w").write(purge)
open(os.path.join(D, "journal.jsonl"), "a").write(json.dumps({
    "ts": datetime.datetime.now().isoformat(timespec="seconds"), "who": who,
    "what": "cleanup", "file": f"tasks/{task}/*", "task": task, "stage": "cleanup",
    "gate_result": f"archived, purge {purge}"})+"\n")
print(f"{task}: working files archived → _archive/{task} (auto-purge {purge}). Journal keeps the facts.")
