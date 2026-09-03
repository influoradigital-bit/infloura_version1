#!/usr/bin/env python3
"""gates/one_gate_per_class.py — origin: F-0482 (duplicate-gate-drift).

Two sessions independently wrote gates for the same ledger classes on 2026-09-02. By the
time anyone noticed there were TWO gates each for unenforced-limit, empty-state-misleads,
missing-feature and unreachable-endpoint, and THREE for empty-state-misleads counting
empty_state_honesty.py.

That is not a tidiness problem. `promote.py <F-id> <gate-path>` takes exactly ONE gate
path, so a class with two gates has no answer to "which gate closes this finding?" — and
every finding in that class stays open regardless of how many gates exist. More gates made
the backlog LESS closable, which is the opposite of what writing a gate is for. It is the
same disease as F-0031 (three marketplace.json in one tree) moved down a level: from the
catalogue, to the gate.

This gate asserts one claimant per class:

  1. every gate naming a ledger class in its header names a class no other gate claims
  2. a gate file that claims no class at all is reported, not failed — an F-NNNN-named
     instance gate legitimately claims none
  3. archived gates under _archive/ are excluded: being superseded is the CORRECT end
     state for a loser, and flagging it would punish the reconciliation

LAW (false-red): unreadable gates dir / no gate files => exit 2, never 0. "I found no
gates, therefore they are all unique" is the vacuous pass this whole class exists for.
LAW (rule 5): declares its blind spot on EVERY exit path, including success.
LAW (no truncation): prints every collision, never a head -N slice.

Usage: gates/one_gate_per_class.py [gates_dir]
Exit:  0 one claimant per class · 1 a class has two or more · 2 nothing could be read · 64 usage
"""
import os
import re
import sys

BLIND = [
    "whether the ONE surviving gate for a class is the RIGHT one, or any good — only that "
    "exactly one file claims it",
    "a gate that claims its class in prose this parser does not match, which reads here as "
    "claiming nothing and is reported rather than failed",
    "whether two differently-NAMED classes are actually the same defect wearing two labels",
    "gate quality of any kind: exit-code discipline, blind-spot declaration and whether the "
    "gate can fail are all out of scope for this file",
]


def emit(extra=()):
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


argv = [a for a in sys.argv[1:] if not a.startswith("--")]
if len(sys.argv) > 1 and any(a.startswith("--") for a in sys.argv[1:]):
    die(64, "usage: gates/one_gate_per_class.py [gates_dir]",
        ["everything: the gate never ran"])

gates_dir = argv[0] if argv else os.path.join(".proof-os", "gates")
if not os.path.isdir(gates_dir):
    die(2, "· %s is not a directory — nothing to check" % gates_dir,
        ["everything: there was no gates directory to read"])

# A gate declares its class the way the existing ones do, e.g.
#   """gates/citations.py — origin: F-0482 (duplicate-gate-drift).
#   # gate | class: dead-control
#   class `empty-state-misleads` (F-0278, ...)
CLASS_PATTERNS = [
    re.compile(r"class[:\s]+[`'\"]?([a-z][a-z0-9]*(?:-[a-z0-9]+)+)[`'\"]?", re.I),
    re.compile(r"origin:\s*F-\d{4}\s*\(([a-z][a-z0-9]*(?:-[a-z0-9]+)+)\)", re.I),
]

claims = {}      # class -> [files]
unclaimed = []   # files naming no class
unreadable = []

# An INSTANCE gate (F-NNNN-slug.py) names its class to say which class it belongs to, not to
# claim ownership of it. F-0270-no-dead-controls.py and dead-control.py are a scoped gate and
# a class gate, which is the intended shape, not a collision.
INSTANCE = re.compile(r"^F-\d{4}-")
# A gate may also apply a known class to a DIFFERENT surface and say so — graph_source.py is
# "origin: F-0024 (stale-runtime-copy) extended to the graph itself". That is a second gate for
# the class on purpose. Only an unqualified claim is a claim to be THE gate for the class.
SCOPED = re.compile(r"extended to|applied to|for the .{0,30}surface|, scoped to", re.I)
scoped = []     # files whose class claim is explicitly a different surface

for name in sorted(os.listdir(gates_dir)):
    if not name.endswith(".py") or name.startswith("_"):
        continue
    path = os.path.join(gates_dir, name)
    if not os.path.isfile(path):
        continue
    if os.path.abspath(path) == os.path.abspath(__file__):
        continue                     # this file quotes example headers; it claims nothing
    try:
        head = open(path, encoding="utf-8", errors="replace").read(4000)
    except Exception as exc:
        unreadable.append("%s (%s)" % (name, exc.__class__.__name__))
        continue
    found, claim_line = None, ""
    for pat in CLASS_PATTERNS:
        m = pat.search(head)
        if m:
            found = m.group(1).lower()
            line_start = head.rfind("\n", 0, m.start()) + 1
            line_end = head.find("\n", m.end())
            claim_line = head[line_start:line_end if line_end != -1 else len(head)]
            break
    if not found:
        unclaimed.append(name)
    elif INSTANCE.match(name):
        scoped.append("%s (instance gate for %s)" % (name, found))
    elif SCOPED.search(claim_line):
        scoped.append("%s (%s, on another surface)" % (name, found))
    else:
        claims.setdefault(found, []).append(name)

if not claims and not unclaimed and not scoped:
    die(2, "· no gate files read under %s — nothing was checked, which is not a pass" % gates_dir,
        ["everything: the directory held no gate files"])

collisions = {c: fs for c, fs in claims.items() if len(fs) > 1}

print("* one-gate-per-class: %d class(es) claimed by %d gate file(s); %d file(s) claim no class"
      % (len(claims), sum(len(f) for f in claims.values()), len(unclaimed)))
if unreadable:
    print("  unreadable (reported, not failed): " + ", ".join(unreadable))
if scoped:
    print("  scoped or instance gates (expected, not collisions):")
    for n in scoped:
        print("    %s" % n)
if unclaimed:
    print("  no class claimed (helpers and one-off checks are expected here): %d file(s)" % len(unclaimed))

if collisions:
    for cls in sorted(collisions):
        print("  COLLISION  %-28s claimed by %d gates: %s"
              % (cls, len(collisions[cls]), ", ".join(sorted(collisions[cls]))))
    print("VERDICT: broken — promote.py names exactly one gate per finding, so each class "
          "above can close nothing until one claimant is chosen and the rest are archived")
    emit()
    sys.exit(1)

print("VERDICT: aligned (proved) — every claimed class has exactly one gate")
emit()
sys.exit(0)
