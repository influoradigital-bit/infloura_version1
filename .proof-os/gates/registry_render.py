#!/usr/bin/env python3
"""gates/registry_render.py — origin: F-0027 null-may-claim-crash.

work.py:49 threw TypeError on a service whose may_claim was null — i.e. `kind: root`,
exactly as PROOFOS.md §4 specifies. The --html path tolerated it, so the crash was
invisible to anyone who only opened the map.

This gate renders EVERY service in the registry through the same format contract the
terminal map uses, so the class can never ship again.

LAW (false-red): registry absent/unreadable => exit 2 (unavailable). exit 1 = real finding.
Usage: gates/registry_render.py [.proof-os/registry.json]
"""
import json, os, sys

path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.environ.get("PROOF_OS_DIR", ".proof-os"), "registry.json")

if not os.path.isfile(path):
    print(f"· {path} not found — cannot render (unavailable)"); sys.exit(2)
try:
    reg = json.load(open(path, encoding="utf-8"))
except Exception as e:
    print(f"· registry unparseable: {e} — unavailable"); sys.exit(2)

services = {k: v for k, v in reg.get("services", {}).items() if not k.startswith("_")}
if not services:
    print("· registry has no real services — nothing to render (unavailable)"); sys.exit(2)

VALID_KINDS = {"root", "scheduler", "oracle", "judgment", "producer",
               "governor", "syslog", "diagnostic", "dispatcher"}
VALID_CLAIMS = {"proved", "believed", "inherits", None}

fails, checked = [], 0
for name, meta in sorted(services.items()):
    checked += 1
    if not isinstance(meta, dict):
        fails.append(f"{name}: entry is {type(meta).__name__}, not an object"); continue

    kind = meta.get("kind")
    claim = meta.get("may_claim")

    # 1. the exact crash: every field the map formats must survive f-string width specs
    for field, val in (("kind", kind), ("may_claim", claim)):
        rendered = val if val is not None else "—"
        try:
            f"{rendered:<12}"
        except (TypeError, ValueError):
            fails.append(f"{name}: {field}={val!r} cannot be width-formatted (this is F-0027)")

    # 2. vocabulary — a typo here renders as a plausible-looking column.
    #    Guard on type first: an unhashable value (dict/list) would crash the `in` test
    #    and exit 1, i.e. a false red from the gate meant to prevent false reds.
    if not isinstance(kind, str) or kind not in VALID_KINDS:
        fails.append(f"{name}: kind {kind!r} not in {sorted(VALID_KINDS)}")
    if not (claim is None or isinstance(claim, str)):
        fails.append(f"{name}: may_claim {claim!r} is {type(claim).__name__}, must be a string or null")
    elif claim not in VALID_CLAIMS:
        fails.append(f"{name}: may_claim {claim!r} not in proved|believed|inherits|null")

    # 3. trust law: a proved ceiling with no gate is a silent oracle (F-0023)
    if claim == "proved" and not meta.get("gates"):
        fails.append(f"{name}: may_claim=proved but gates=[] — nothing can ever render it green")

    # 4. jurisdiction must exist, or dispatch has nothing to match on
    if kind != "root" and not meta.get("jurisdiction"):
        fails.append(f"{name}: no jurisdiction — cannot be dispatched to deterministically")

print(f"registry services rendered: {checked}, failed: {len(fails)}")
for x in fails:
    print("  ", x)
sys.exit(1 if fails else 0)
