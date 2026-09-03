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

0.3.3 declared law 2 and then wrote its own allowlist containing `"unstated"`, `None`
and `""`. An edge that simply OMITTED its oracle key was therefore "deterministic": it
rendered aligned, exited 0, and the gate printed `oracle_strength 0.0%` beside the green.
Provenance by omission was the strongest provenance in the file. The allowlist now lives
in `_oracles.py` (frozen) and is asked, not re-implemented — unstated is capped, not green.

LAW (false-red): no graph.json / unparseable / wrong shape => exit 2 (unavailable).
                 exit 1 = the graph lies about itself.
Usage: gates/graph_source.py [graph.json]
"""
import os as _o, sys as _s
_s.path.insert(0, _o.path.dirname(_o.path.abspath(__file__)))
try:
    from _rc import rc_init; rc_init("graph_source")   # F-0026: liveness is read, not inferred
except Exception:
    pass

import json, os, sys, collections
from _oracles import is_green_oracle, GREEN_STATUSES, ALL_STATUSES

BLIND = [
    "whether the edges are TRUE — this gate audits provenance and status, never "
    "re-derives the topology from source",
    "whether evidence.where still points at the line it was captured from (see "
    "gates/citations.py for that check)",
    "nodes: only edges are audited",
]


_emitted = []


def emit(extra=()):
    if _emitted:
        return
    _emitted.append(1)
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


# law 5 ON THE SIGNAL PATH. Shell gates carry `trap _nc EXIT` and declare their blind
# spot even when killed; the python gates declared nothing, so `kill -TERM` gave rc=143
# and ZERO NOT CHECKED lines — a gate that died mid-run was indistinguishable from one
# that ran and found nothing to skip.
#
# LAW 5 MUST NOT BE BOUGHT WITH LAW 7. The first version restored SIG_DFL and re-killed,
# which OVERWROTE the recorder rc_init() installed above, so `_write(128+signo)` never
# ran and a killed gate recorded `running pid=… started=…` — F-0026's exact shape,
# reintroduced by the fix for law 5. FIX-CONTRACT §6: the recorder must record the code
# the process actually produced. This handler therefore CHAINS to the handler that was
# already installed (_rc.py's), which writes `exit=128+signo observed=signal` and exits.
# Both laws hold. The SIG_DFL re-kill is only the fallback for when no python-level
# handler was there to chain to.
_prev_handlers = {}


def _on_signal(signo, frame):
    import signal as _sig
    emit([f"everything not already printed above — the gate was killed by signal "
          f"{_sig.Signals(signo).name} before it could finish"])
    sys.stdout.flush()
    prev = _prev_handlers.get(signo)
    if callable(prev):
        prev(signo, frame)      # _rc.py's recorder: records 128+signo, then exits
        return                  # (unreachable in practice — prev exits)
    _sig.signal(signo, _sig.SIG_DFL)
    os.kill(os.getpid(), signo)


def _install_signal_handlers():
    import signal as _sig
    for _name in ("SIGTERM", "SIGINT", "SIGHUP"):
        signum = getattr(_sig, _name, None)
        if signum is None:
            continue
        try:
            _prev_handlers[signum] = _sig.getsignal(signum)
            _sig.signal(signum, _on_signal)
        except (ValueError, OSError, AttributeError):
            pass    # no such signal on this platform, or not the main thread


_install_signal_handlers()


if len(sys.argv) > 2:
    die(64, f"· unrecognised extra argument(s): {' '.join(sys.argv[2:])}\n"
            f"usage: gates/graph_source.py [graph.json]",
        ["every edge: the arguments were rejected before the graph was opened"])

path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.environ.get("PROOF_OS_DIR", ".proof-os"), "graph.json")

if os.path.isdir(path):
    die(2, f"· {path} is a directory, not a graph — unavailable",
        ["every edge: there was no graph to read"])
if not os.path.isfile(path):
    die(2, f"· {path} not found — run scan.py or adapt_graphify.py first (unavailable)",
        ["every edge: there was no graph to read"])
try:
    G = json.load(open(path, encoding="utf-8"))
except Exception as e:
    die(2, f"· graph unparseable: {e} — unavailable",
        ["every edge: the graph could not be parsed"])

if not isinstance(G, dict):
    die(2, f"· graph is a {type(G).__name__}, not an object — wrong shape, unavailable",
        ["every edge: the top level of the graph is not an object"])

edges = G.get("edges")
if edges is None:
    edges = []
if not isinstance(edges, list):
    die(2, f"· graph.edges is a {type(edges).__name__}, not a list — wrong shape, "
           f"unavailable", ["every edge: graph.edges is not a list"])
if not edges:
    die(2, "· graph has no edges — nothing to attest (unavailable)",
        ["every edge: the graph is empty"])

# An edge that is not an object cannot be audited. 0.3.3 crashed here with exit 1,
# reporting corrupt data as a defect in the codebase.
bad_shape = [i for i, e in enumerate(edges) if not isinstance(e, dict)]
if bad_shape:
    die(2, f"· {len(bad_shape)}/{len(edges)} edge(s) are not objects "
           f"(first: index {bad_shape[0]}, {type(edges[bad_shape[0]]).__name__}) — "
           f"corrupt graph, unavailable",
        ["every edge: the edge list does not hold edge objects"])

# LAW 2 WAS BYPASSED BY A CAPITAL LETTER. The green test was `e.get("status") not in
# ("aligned","proved")` against the RAW string, so `"Aligned"`, `"aligned "` and
# `"PROVED"` all failed the membership test, skipped the oracle check entirely, and
# rendered green at exit 0 beside a printed `oracle_strength 0.0%`. scripts/work.py
# refuses the same file at exit 2. Normalise before comparing — and a status that is not
# in the enum at all is CORRUPT DATA, which §1 makes exit 2, never a silent pass.
KNOWN_STATUSES = GREEN_STATUSES | ALL_STATUSES


def status_of(e):
    s = e.get("status")
    return s.strip().lower() if isinstance(s, str) else None


unknown_status = [(i, e.get("status")) for i, e in enumerate(edges)
                  if status_of(e) not in KNOWN_STATUSES]
if unknown_status:
    i0, s0 = unknown_status[0]
    die(2, f"· {len(unknown_status)}/{len(edges)} edge(s) carry a status outside "
           f"{sorted(KNOWN_STATUSES)} (first: index {i0}, {s0!r}) — an unclassifiable "
           f"edge is corrupt data, and neither green nor a finding can be read off it "
           f"(unavailable)",
        [f"every edge: {len(unknown_status)} of them state a status this gate cannot "
         f"classify, so no provenance verdict over this graph would be complete"])

fails, extra = [], []

# 1 · the graph must name its engine
engine = G.get("engine")
if not engine:
    fails.append("graph declares no 'engine' — a number with no provenance is not evidence "
                 "(scan.py and graphify produce very different graphs from the same repo)")

# 2 · every edge must carry locatable evidence
no_ev = [e for e in edges if not (e.get("evidence") or {}).get("where")]
if no_ev:
    fails.append(f"{len(no_ev)} edge(s) carry no evidence.where — unverifiable claims")


def oracle_of(e):
    ev = e.get("evidence")
    return ev.get("oracle") if isinstance(ev, dict) else None


# 3 · the trust law, applied to topology — decided by _oracles.py, not re-implemented.
by_oracle = collections.Counter(
    (oracle_of(e) if isinstance(oracle_of(e), str) and oracle_of(e).strip() else "unstated")
    for e in edges)

not_green = []
for e in edges:
    if status_of(e) not in GREEN_STATUSES:
        continue
    ok, why = is_green_oracle(oracle_of(e))
    if not ok:
        not_green.append((e, why))
if not_green:
    e0, why0 = not_green[0]
    fails.append(f"{len(not_green)} edge(s) render '{e0.get('status')}' on provenance that "
                 f"may never be green — first: {e0.get('src')} -> {e0.get('dst')}: {why0}")

# 4 · provenance coverage. In 0.3.3 this required engine == 'graphify' AND every single
#     edge unstated, so one honest edge switched it off for all the others.
unstated = by_oracle.get("unstated", 0)
if unstated == len(edges):
    fails.append(f"no edge in this graph states an oracle (engine "
                 f"{engine or 'UNSTATED'}) — the graph cannot be audited at all")
elif unstated and engine == "graphify":
    fails.append(f"{unstated}/{len(edges)} graphify edge(s) state no oracle — the adapter "
                 f"did not run over all of them, so their provenance is a blank, not a value")
elif unstated:
    extra.append(f"the provenance of {unstated}/{len(edges)} edge(s) that state no oracle "
                 f"(they are capped at believed, but nothing here can say what found them)")

# 5 · strength. 0.3.3 counted `ast` alone, so a graph of parser/compiler edges — which
#     the allowlist explicitly permits to be green — reported 0% beside a green exit.
green_n = sum(1 for e in edges if is_green_oracle(oracle_of(e))[0])
ast_n = sum(1 for e in edges
            if isinstance(oracle_of(e), str) and oracle_of(e).strip().lower() == "ast")
strength = round(green_n / len(edges) * 100, 1)

print(f"graph engine: {engine or 'UNSTATED'} · {len(edges)} edges · "
      f"oracles {dict(by_oracle)} · oracle_strength {strength}% "
      f"(deterministic {green_n}/{len(edges)}, of which parser-derived {ast_n})")
for x in fails:
    print("  ", x)
emit(extra)
sys.exit(1 if fails else 0)
