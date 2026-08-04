#!/usr/bin/env python3
"""gates/graph_scope.py — guards the graphify graph against scope pollution.

origin: F-0044 (subagent-side-effect-outside-scope). A subagent ran
  `graphify update .`, replacing the human-approved graph (src + influora-api,
  ~14.3k nodes) with a whole-tree graph (~27.3k nodes) that included
  .claude, .cursor, .proof-os, claude-skills, wiki, docs, sage-suite,
  influora-ai — the exact dirs excluded at the confirm screen. Nothing detected
  it because git ignores graphify-out/ and no gate audited the graph's scope.

CHECK: every node's source_file must live under an APPROVED top-level root
  (src, influora-api) — or be a source-less external-symbol node (empty
  source_file, e.g. java.Pattern). Any node rooted in .claude / .proof-os /
  claude-skills / wiki / docs / influora-ai / etc. = pollution => exit 1.

LAW: exit 1 for real pollution, exit 0 when clean, exit 2 if graph absent.
usage: graph_scope.py [graph_json_path] [--allow root1 root2 ...]
"""
import json
import sys
from collections import Counter
from pathlib import Path

args = sys.argv[1:]
allow = ["src", "influora-api"]
if "--allow" in args:
    i = args.index("--allow")
    allow = args[i + 1:]
    args = args[:i]
graph_path = Path(args[0]) if args else Path("graphify-out/graph.json")

if not graph_path.exists():
    print(f"· {graph_path} not found — unavailable")
    sys.exit(2)
try:
    g = json.loads(graph_path.read_text(encoding="utf-8"))
except Exception as e:
    print(f"· could not parse {graph_path} ({e}) — unavailable")
    sys.exit(2)

nodes = g.get("nodes", [])
if not nodes:
    print("· graph has no nodes — unavailable")
    sys.exit(2)

allowset = set(allow)
offenders = Counter()
for n in nodes:
    if not isinstance(n, dict):
        continue
    sf = str(n.get("source_file", "")).replace("\\", "/")
    if not sf:  # source-less external-symbol node — always allowed
        continue
    root = sf.split("/")[0]
    if root not in allowset:
        offenders[root] += 1

if offenders:
    total = sum(offenders.values())
    print(f"VERDICT: broken — {total} node(s) from {len(offenders)} out-of-scope root(s) (approved: {', '.join(allow)})")
    for r, c in offenders.most_common(15):
        print(f"  {c:6} {r!r}")
    sys.exit(1)
print(f"VERDICT: aligned (proved) — all {len(nodes)} graph nodes are within approved scope ({', '.join(allow)}) or source-less")
sys.exit(0)
