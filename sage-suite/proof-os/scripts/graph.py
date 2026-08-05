#!/usr/bin/env python3
"""graph.py — ask questions of graph.json. Read-only; never writes.

  graph.py imports    <file>        what this file pulls in
  graph.py importers  <file>        who pulls THIS in (one hop)
  graph.py blast      <file>        everything reachable if you break it (transitive)
  graph.py path       <a> <b>       shortest chain from a to b, if any
  graph.py find       <substring>   nodes whose id contains this
  graph.py service    <name>        what a service declares it touches
  graph.py bad        [status]      non-green edges with evidence (broken|missing|partial)
  graph.py orphans                  files nothing imports
  graph.py hubs       [n]           the n files with the largest blast radius — gate these first
  graph.py stats                    counts

Paths are matched by suffix, so `graph.py blast layout.tsx` works without the full path.
"""
import collections, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    from _common import data_dir
    GP = os.path.join(data_dir(), "graph.json")
except Exception:
    GP = os.path.join(os.environ.get("PROOF_OS_DIR", ".proof-os"), "graph.json")

if not os.path.exists(GP):
    print("NOT READY: no graph.json -> run scripts/scan.py --project . first")
    sys.exit(3)

G = json.load(open(GP, encoding="utf-8"))
EDGES = G["edges"]
NODES = {n["id"]: n for n in G["nodes"]}
SYM = {"aligned": "●", "partial": "◐", "broken": "✕", "missing": "○"}

out_e = collections.defaultdict(list)
in_e = collections.defaultdict(list)
for e in EDGES:
    out_e[e["src"]].append(e)
    in_e[e["dst"]].append(e)


def resolve(q):
    """Exact id, else unique suffix match, else report the candidates."""
    if q in NODES:
        return q
    hits = [n for n in NODES if n == q or n.endswith("/" + q) or n.endswith(q)]
    if len(hits) == 1:
        return hits[0]
    if not hits:
        print(f"no node matches {q!r} — try: graph.py find {q}")
        sys.exit(1)
    exact = [h for h in hits if os.path.basename(h) == q]
    if len(exact) == 1:
        return exact[0]
    print(f"{len(hits)} nodes match {q!r} — be more specific:")
    for h in hits[:20]:
        print("  ", h)
    sys.exit(1)


def show(e, arrow="->"):
    ev = e["evidence"]
    note = ev.get("note", "")
    return (f"  {SYM[e['status']]} {e['src']} {arrow} {e['dst']}  [{e['type']}]"
            f"  {ev['where']}{('  ' + note) if note else ''}")


if len(sys.argv) < 2:
    print(__doc__); sys.exit(64)
cmd = sys.argv[1]
arg = sys.argv[2] if len(sys.argv) > 2 else None

if cmd == "imports":
    n = resolve(arg)
    es = [e for e in out_e[n] if e["type"] in ("imports", "uses", "touches", "reads/writes", "calls")]
    print(f"{n} depends on {len(es)}:")
    for e in sorted(es, key=lambda e: e["dst"]):
        print(show(e))

elif cmd == "importers":
    n = resolve(arg)
    es = in_e[n]
    print(f"{len(es)} depend on {n}:")
    for e in sorted(es, key=lambda e: e["src"]):
        print(show(e, "<-").replace(f"{e['src']} <- {e['dst']}", f"{e['dst']} <- {e['src']}"))

elif cmd == "blast":
    n = resolve(arg)
    seen, frontier, depth = {n}, [n], {n: 0}
    while frontier:
        nxt = []
        for cur in frontier:
            for e in in_e[cur]:
                if e["src"] not in seen:
                    seen.add(e["src"]); depth[e["src"]] = depth[cur] + 1; nxt.append(e["src"])
        frontier = nxt
    seen.discard(n)
    print(f"blast radius of {n}: {len(seen)} files break or change if this does")
    for f in sorted(seen, key=lambda x: (depth[x], x)):
        print(f"   {depth[f]} hop{'s' if depth[f] > 1 else ' '}  {f}")

elif cmd == "path":
    a, b = resolve(arg), resolve(sys.argv[3])
    prev, frontier, seen = {}, [a], {a}
    while frontier and b not in seen:
        nxt = []
        for cur in frontier:
            for e in out_e[cur]:
                if e["dst"] not in seen:
                    seen.add(e["dst"]); prev[e["dst"]] = cur; nxt.append(e["dst"])
        frontier = nxt
    if b not in seen:
        print(f"no path from {a} to {b}"); sys.exit(1)
    chain, cur = [b], b
    while cur != a:
        cur = prev[cur]; chain.append(cur)
    print(" -> ".join(reversed(chain)))

elif cmd == "find":
    hits = [n for n in NODES if arg.lower() in n.lower()]
    print(f"{len(hits)} nodes match {arg!r}:")
    for h in sorted(hits)[:60]:
        print(f"   [{NODES[h]['kind']}] {h}{'' if NODES[h].get('exists', True) else '   (NOT ON DISK)'}")

elif cmd == "service":
    n = resolve(arg)
    es = out_e[n]
    by = collections.defaultdict(list)
    for e in es:
        by[e["status"]].append(e)
    print(f"{n} — {len(es)} declared edges")
    for st in ("broken", "missing", "partial", "aligned"):
        for e in by[st]:
            print(show(e))

elif cmd == "bad":
    want = {arg} if arg else {"broken", "missing", "partial"}
    es = [e for e in EDGES if e["status"] in want]
    print(f"{len(es)} non-green edges:")
    for e in es:
        print(show(e))

elif cmd == "orphans":
    files = [n for n, m in NODES.items() if m["kind"] == "file" and m.get("exists", True)]
    orph = [f for f in files if not in_e[f]]
    print(f"{len(orph)} of {len(files)} files are imported by nothing:")
    for f in sorted(orph)[:80]:
        print("  ", f)

elif cmd == "hubs":
    k = int(arg) if arg else 15
    files = [n for n, m in NODES.items() if m["kind"] == "file"]
    rad = {}
    for f in files:
        seen, frontier = {f}, [f]
        while frontier:
            nxt = []
            for cur in frontier:
                for e in in_e[cur]:
                    if e["src"] not in seen:
                        seen.add(e["src"]); nxt.append(e["src"])
            frontier = nxt
        rad[f] = len(seen) - 1
    print(f"largest blast radius — gate these first:")
    for f, r in sorted(rad.items(), key=lambda kv: -kv[1])[:k]:
        print(f"  {r:5}  {f}")

elif cmd == "stats":
    print(f"generated {G['generated']} · project {G.get('project')}")
    print(f"alignment {G['alignment_pct']}% · nodes {len(NODES)} · edges {len(EDGES)}")
    print(" by kind: ", dict(collections.Counter(n["kind"] for n in G["nodes"])))
    print(" by type: ", dict(collections.Counter(e["type"] for e in EDGES)))
    print(" by status:", G["counts"])

else:
    print(__doc__); sys.exit(64)
