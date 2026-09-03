#!/usr/bin/env python3
"""gates/graph_source.py — origin: F-0024 (stale-runtime-copy) extended to the graph itself.

A graph is a set of claims about your codebase. Two things must be true of it before
any number derived from it can be quoted:

  1. It says which engine produced it. "2,406 edges" means nothing without knowing
     whether a parser or a regex found them. F-0024 was exactly this mistake, made
     about the plugin version; this is the same mistake made about the data.
  2. No model-derived edge renders green. An edge a language model inferred is an
     opinion about the code, and opinions are capped at believed — the same law
     validate.py applies to reports, applied to topology.

LAW (false-red): no graph.json => exit 2 (unavailable). exit 1 = the graph lies about itself.
Usage: gates/graph_source.py [graph.json]
"""
import json, os, sys, collections

path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.environ.get("PROOF_OS_DIR", ".proof-os"), "graph.json")

if not os.path.isfile(path):
    print(f"· {path} not found — run scan.py or adapt_graphify.py first (unavailable)")
    sys.exit(2)
try:
    G = json.load(open(path, encoding="utf-8"))
except Exception as e:
    print(f"· graph unparseable: {e} — unavailable"); sys.exit(2)

edges = G.get("edges") or []
if not edges:
    print("· graph has no edges — nothing to attest (unavailable)"); sys.exit(2)

fails = []

# 1 · the graph must name its engine
engine = G.get("engine")
if not engine:
    fails.append("graph declares no 'engine' — a number with no provenance is not evidence "
                 "(scan.py and graphify produce very different graphs from the same repo)")

# 2 · every edge must carry locatable evidence
no_ev = [e for e in edges if not (e.get("evidence") or {}).get("where")]
if no_ev:
    fails.append(f"{len(no_ev)} edge(s) carry no evidence.where — unverifiable claims")

# 3 · the trust law, applied to topology
by_oracle = collections.Counter((e.get("evidence") or {}).get("oracle", "unstated") for e in edges)
# regex is a weak oracle, not a model: deterministic and reproducible, so it may render
# green — but it is counted separately, because oracle_strength is the metric that says
# how much of your "proved" rests on pattern-matching rather than parsing.
DETERMINISTIC = {"ast", "regex", "parser", "compiler", "unstated", None, ""}
green_model = [e for e in edges
               if (e.get("evidence") or {}).get("oracle") not in DETERMINISTIC
               and e.get("status") == "aligned"]
if green_model:
    fails.append(f"{len(green_model)} model-derived edge(s) render 'aligned' — "
                 f"first: {green_model[0]['src']} -> {green_model[0]['dst']}. "
                 f"A guess about the code cannot be green.")

# 4 · a graph whose edges never state an oracle cannot be audited at all
if by_oracle.get("unstated", 0) == len(edges) and engine == "graphify":
    fails.append("graphify-sourced graph but no edge states an oracle — adapter did not run")

ast_n = by_oracle.get("ast", 0)
strength = round(ast_n / len(edges) * 100, 1)
print(f"graph engine: {engine or 'UNSTATED'} · {len(edges)} edges · "
      f"oracles {dict(by_oracle)} · oracle_strength {strength}% (parser-derived {ast_n}/{len(edges)})")
for x in fails:
    print("  ", x)
sys.exit(1 if fails else 0)
