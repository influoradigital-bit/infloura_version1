#!/usr/bin/env python3
"""gates/adapter_roundtrip.py — origin: F-0037 (adapter-schema-mismatch-false-green).

adapt_graphify.py read G["edges"]. Graphify writes NetworkX node-link format, where
edges live under "links". So on real output it produced 59 nodes, 0 edges, printed
"alignment 0.0%", and exited 0. That reads as "your codebase is completely unaligned"
when the truth is "I could not see anything." A false GREEN — the class F-0029 is open
for, committed again one layer down.

This gate feeds the adapter a fixture in EACH schema it claims to accept and asserts:
  1. every schema yields edges > 0            (it can actually read the format)
  2. a nodes-only file exits 2, never 0       (blindness is unavailable, not 0%)
  3. a model-derived edge never renders green (the trust law survives the adapter)

LAW (false-red): adapter absent / python broken => exit 2 (unavailable). exit 1 = the
adapter is blind to a schema it advertises.
Usage: gates/adapter_roundtrip.py [path/to/adapt_graphify.py]
"""
import json, os, subprocess, sys, tempfile

here = os.path.dirname(os.path.abspath(__file__))
ADAPTER = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "scripts", "adapt_graphify.py")
ADAPTER = os.path.abspath(ADAPTER)

if not os.path.isfile(ADAPTER):
    print(f"· {ADAPTER} not found — nothing to attest (unavailable)")
    sys.exit(2)

NODES = [
    {"id": "a_py", "label": "a.py", "source_file": "a.py", "_origin": "ast"},
    {"id": "b_py", "label": "b.py", "source_file": "b.py", "_origin": "ast"},
]
EDGE_AST = {"source": "a_py", "target": "b_py", "relation": "imports_from",
            "_origin": "ast", "confidence": "EXTRACTED",
            "source_file": "a.py", "source_location": "L1", "context": "import"}
EDGE_MODEL = dict(EDGE_AST, relation="calls", _origin="llm", confidence="INFERRED")

CASES = [
    # name                  payload                                     expect_rc  min_edges
    ("node-link ('links')", {"nodes": NODES, "links": [EDGE_AST]},           0, 1),
    ("plain ('edges')",     {"nodes": NODES, "edges": [EDGE_AST]},           0, 1),
    ("nodes only",          {"nodes": NODES},                               2, 0),
]

fails = []
tmp = tempfile.mkdtemp(prefix="proofos-adapter-")

for name, payload, want_rc, min_edges in CASES:
    src = os.path.join(tmp, "in.json")
    out = os.path.join(tmp, "out.json")
    if os.path.exists(out):
        os.remove(out)
    json.dump(payload, open(src, "w", encoding="utf-8"))
    try:
        p = subprocess.run([sys.executable, ADAPTER, src, "--out", out],
                           capture_output=True, text=True, timeout=60)
    except Exception as e:
        print(f"· adapter could not be executed: {e} — unavailable")
        sys.exit(2)

    if p.returncode != want_rc:
        fails.append(f"{name}: exit {p.returncode}, expected {want_rc} "
                     f"({'blindness must report unavailable, never 0%' if want_rc == 2 else 'adapter cannot read a schema it accepts'})"
                     f" :: {(p.stdout + p.stderr).strip().splitlines()[-1] if (p.stdout + p.stderr).strip() else 'no output'}")
        continue

    if want_rc != 0:
        continue
    if not os.path.exists(out):
        fails.append(f"{name}: exit 0 but wrote no output file")
        continue
    got = json.load(open(out, encoding="utf-8"))
    n = len(got.get("edges") or [])
    if n < min_edges:
        fails.append(f"{name}: adapted {n} edges from {min_edges}+ in source — "
                     f"schema mismatch rendered as an aligned-0% graph (F-0037)")

# 3 · the trust law must survive the adapter
src = os.path.join(tmp, "m.json")
out = os.path.join(tmp, "m.out.json")
json.dump({"nodes": NODES, "links": [EDGE_MODEL]}, open(src, "w", encoding="utf-8"))
p = subprocess.run([sys.executable, ADAPTER, src, "--out", out], capture_output=True, text=True, timeout=60)
if p.returncode == 0 and os.path.exists(out):
    green = [e for e in json.load(open(out, encoding="utf-8"))["edges"]
             if e["status"] == "aligned" and e["evidence"]["oracle"] != "ast"]
    if green:
        fails.append(f"{len(green)} model-derived edge(s) rendered aligned — "
                     f"the cap did not hold through the adapter")
else:
    fails.append(f"model-edge case: exit {p.returncode} on a well-formed graph")

print(f"adapter roundtrip: {len(CASES) + 1} cases against {os.path.relpath(ADAPTER)}")
for x in fails:
    print("  ", x)
if not fails:
    print("   all schemas read · blindness reports unavailable · model edges stay capped")
sys.exit(1 if fails else 0)
