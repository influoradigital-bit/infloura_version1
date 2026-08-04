#!/usr/bin/env python3
"""adapt_graphify.py — fold a Graphify knowledge graph into proof-os, under the trust law.

Graphify (MIT, safishamsi/graphify) derives topology far better than scan.py does:
Tree-sitter ASTs across 25 languages, symbol-level granularity, alias resolution.
proof-os should stop pretending to be a scanner and consume it.

THE REASON THIS IS A CLEAN FIT, not a bolt-on:
Graphify already tags every edge with `_origin` — "ast" when a parser produced it,
otherwise a model did. That is exactly the proved/believed axis, at the level of the
graph itself. So the trust law applies unchanged:

    _origin: ast   + confidence EXTRACTED   -> oracle "ast"   -> may render aligned (proved)
    anything else                           -> oracle "model" -> CAPPED at partial, never green

An edge a language model guessed can never be a fact about your codebase, for the same
reason a model's "looks good to me" is never a passing test.

Usage:
  adapt_graphify.py <graphify.json> [--out graph.json] [--project DIR]
  adapt_graphify.py graphify-out/graph.json --out .proof-os/graph.json --project .
"""
import collections, json, os, re, sys

if len(sys.argv) < 2:
    print(__doc__); sys.exit(64)

SRC = sys.argv[1]


def arg(flag, default=None):
    return sys.argv[sys.argv.index(flag) + 1] if flag in sys.argv else default


OUT = arg("--out")
PROJECT = arg("--project")

if not os.path.isfile(SRC):
    print(f"· {SRC} not found — run `/graphify .` first, or point at graphify-out/graph.json")
    sys.exit(2)
try:
    G = json.load(open(SRC, encoding="utf-8"))
except Exception as e:
    print(f"· {SRC} unparseable: {e}"); sys.exit(2)

raw_nodes = G.get("nodes") or []
# F-0037: graphify writes NetworkX node-link format — edges live under "links".
# Reading only "edges" returned 0 and printed "alignment 0.0%", which reads as
# "your codebase is unaligned" rather than "I could not see anything." False green.
raw_edges = G.get("edges") or G.get("links") or []
if not raw_nodes and not raw_edges:
    print("· graphify output is empty — nothing to adapt (unavailable)"); sys.exit(2)
# F-0037: nodes but no edges is not a 0% codebase, it is a schema the adapter cannot read.
# Silence here is the false green; say unavailable and name the keys actually present.
if raw_nodes and not raw_edges:
    print(f"· {SRC} has {len(raw_nodes)} nodes and 0 edges under 'edges'/'links' — "
          f"top-level keys: {sorted(G.keys())}. This is a schema mismatch, not an "
          f"unaligned codebase (unavailable, never 0%%)."); sys.exit(2)

# ---- nodes ------------------------------------------------------------------
# A node whose id is the slug of its own source_file is the FILE; anything deeper
# is a symbol inside it. Keeping both is the upgrade over scan.py, which only ever
# saw files and therefore could not answer "who calls this function".
def slug(path):
    return re.sub(r"[^a-z0-9]+", "_", os.path.splitext(path or "")[0].lower()).strip("_")


nodes, file_of = {}, {}
for n in raw_nodes:
    nid = n.get("id")
    if not nid:
        continue
    sf = n.get("source_file") or ""
    kind = "file" if nid == slug(sf) else "symbol"
    exists = True
    if PROJECT and sf:
        exists = os.path.exists(os.path.join(PROJECT, sf))
    nodes[nid] = {"id": nid, "kind": kind, "exists": exists,
                  "label": n.get("label") or nid, "file": sf,
                  "engine": n.get("_origin", "unknown")}
    file_of[nid] = sf

# ---- edges, under the trust law ---------------------------------------------
TYPE = {"imports_from": "imports", "imports": "imports", "calls": "calls",
        "contains": "contains", "references": "references", "extends": "extends",
        "implements": "implements"}

edges, seen, capped = [], set(), 0
for e in raw_edges:
    s, t = e.get("source"), e.get("target")
    if not s or not t:
        continue
    origin = (e.get("_origin") or "unknown").lower()
    oracle = "ast" if origin == "ast" else "model"

    if t not in nodes:
        status = "broken"          # a real finding: the edge points nowhere
    elif not nodes[t]["exists"]:
        status = "missing"
    else:
        status = "aligned"

    # THE CAP. A model-derived edge may never render green, however confident it says it is.
    if oracle == "model" and status == "aligned":
        status = "partial"
        capped += 1

    key = (s, t, e.get("relation"))
    if key in seen:
        continue
    seen.add(key)

    where = e.get("source_file") or ""
    loc = str(e.get("source_location") or "").lstrip("L")
    edges.append({
        "src": s, "dst": t,
        "type": TYPE.get(e.get("relation"), e.get("relation") or "links"),
        "status": status,
        "evidence": {
            "where": f"{where}:{loc}" if where and loc else (where or "graphify"),
            "note": e.get("context") or "",
            "oracle": oracle,
            "confidence": e.get("confidence", ""),
        },
    })

order = {"missing": 0, "broken": 1, "partial": 2, "aligned": 3}
edges.sort(key=lambda e: (order[e["status"]], e["src"]))
score = {"aligned": 1.0, "partial": 0.5, "broken": 0.0, "missing": 0.0}
pct = round(sum(score[e["status"]] for e in edges) / max(len(edges), 1) * 100, 1)
proved = [e for e in edges if e["evidence"]["oracle"] == "ast"]
ppct = round(sum(score[e["status"]] for e in proved) / max(len(edges), 1) * 100, 1)
counts = {k: sum(1 for e in edges if e["status"] == k) for k in order}

out = {
    "generated": None,           # stamped by the caller; this script never invents a clock
    "project": PROJECT,
    "engine": "graphify",        # F-0024's law, applied to the graph: say who made it
    "engine_detail": {"origins": dict(collections.Counter(
        e["evidence"]["oracle"] for e in edges))},
    "alignment_pct": pct,
    "proved_pct": ppct,
    "counts": counts,
    "nodes": list(nodes.values()),
    "edges": edges,
}

dest = OUT or "graphify-adapted.json"
json.dump(out, open(dest, "w", encoding="utf-8"), indent=1)

print(f"{dest}: {len(nodes)} nodes ({sum(1 for n in nodes.values() if n['kind']=='file')} files, "
      f"{sum(1 for n in nodes.values() if n['kind']=='symbol')} symbols) · {len(edges)} edges")
print(f"  alignment {pct}% · proved {ppct}% (ast) · {counts}")
if capped:
    print(f"  NOTE: {capped} model-derived edge(s) claimed aligned — rendered partial. "
          f"Green requires a parser, not a guess.")
