#!/usr/bin/env python3
"""gates/stale_runtime_copy.py - gate for ledger class `stale-runtime-copy`
(F-0053, F-0426, F-0428; the same shape version_assert.py was written for as F-0024).

THE CLASS. proof-os ships gates. A project keeps its own copy of them in
`.proof-os/gates/` and runs THAT copy. Nothing ever compared the two, so the
project copy quietly rotted and every downstream gate kept reporting green:

  * one copy of a gate was an 850-byte stub that verified nothing and exited 0
    on an empty document, while the canonical file was a 279-line checker;
  * three shared modules -- `_oracles.py`, `_rc.py`, `_rc.sh` -- were absent
    entirely, so every `from _rc import rc_init` fell into its `except: pass`
    and liveness recording was silently OFF for the whole tree. `.proof-os/rc/`
    kept showing old records, which read exactly like current ones.

Neither failure is visible to any gate that reads the PRODUCT. Both are visible
the moment you hash the gate files themselves, which is what this does.

WHAT IS ASSERTED
  1. a canonical proof-os tree exists and carries a parseable MANIFEST.sha256
     (else exit 2 -- there is no baseline, so nothing is proved)
  2. each canonical gate file is itself verified against that manifest BEFORE it
     is used as a baseline; a canonical file that fails its own manifest is
     excluded and named, never used to bless a project copy
  3. every project gate file that shares a name with a canonical one is
     byte-identical to it (content hash) -- any difference is DRIFT, exit 1
  4. the shared modules `_oracles.py`, `_rc.py`, `_rc.sh` are PRESENT in the
     project copy -- missing = exit 1, unconditionally
  5. a canonical gate this project demonstrably RUNS (it has an rc record under
     `.proof-os/rc/`) but no longer has on disk = exit 1. A canonical gate with
     no rc record was never adopted here; it is named on stdout as not-adopted
     and is NOT a finding, because failing a Java/TS repo for lacking
     `build.go.sh` is how a gate gets weakened until it guards nothing.

LAW (false-red): no canonical tree / no manifest / no project gates dir /
                 nothing left to compare => exit 2, never 0.
                 exit 1 = the copy this project runs is not the copy it claims.
Usage: gates/stale_runtime_copy.py [--canonical DIR] [--project DIR]
"""
import os as _o, sys as _s
_s.path.insert(0, _o.path.dirname(_o.path.abspath(__file__)))
try:
    from _rc import rc_init; rc_init("stale_runtime_copy")  # F-0026: liveness is read, not inferred
except Exception:
    pass

import hashlib
import os
import re
import sys

USAGE = "usage: gates/stale_runtime_copy.py [--canonical DIR] [--project DIR]"

# Modules every python/shell gate in this tree imports. Their absence does not
# raise -- it is swallowed by `except Exception: pass` at the top of each gate --
# so it can only ever be caught here.
SHARED = ("_oracles.py", "_rc.py", "_rc.sh")

# Not gate content: build residue and editor/OS droppings.
IGNORE_NAMES = {".DS_Store", "Thumbs.db"}
IGNORE_DIRS = {"__pycache__", "fixtures", "class", ".git"}

BLIND = [
    "whether the CANONICAL gate is itself correct -- this proves the project copy is "
    "identical to the shipped one, never that the shipped one works",
    "project-only gates (F-*.sh and friends) have no canonical counterpart, so their "
    "content is not judged here at all -- a project gate can still be a stub",
    "the canonical tree is trusted only as far as its own MANIFEST.sha256; nothing here "
    "verifies that manifest against anything above it (gates/version_assert.py does)",
    "whether a gate present and identical is actually WIRED INTO a run -- an identical "
    "file nobody executes proves nothing about the last verdict",
    "rc records are used only as evidence that a gate was adopted; a record's age, "
    "exit code and truthfulness are not examined",
]

_emitted = []


def emit(extra=()):
    """Law 5 - the NOT CHECKED line, on EVERY exit path, exactly once."""
    if _emitted:
        return
    _emitted.append(1)
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


# ------------------------------------------------------------------ arguments
canonical_arg = None
project_arg = None
argv = sys.argv[1:]
i = 0
while i < len(argv):
    a = argv[i]
    if a in ("--canonical", "--project"):
        if i + 1 >= len(argv) or argv[i + 1].startswith("--"):
            die(64, "* %s needs a directory\n%s" % (a, USAGE),
                ["everything: the gate never ran"])
        if a == "--canonical":
            canonical_arg = argv[i + 1]
        else:
            project_arg = argv[i + 1]
        i += 2
        continue
    die(64, "* unknown argument %r\n%s" % (a, USAGE), ["everything: the gate never ran"])


# ------------------------------------------------------------------ locating
def find_store(start=None):
    """An EXISTING .proof-os, walking upward. A read never creates state."""
    env = os.environ.get("PROOF_OS_DIR")
    if env:
        return os.path.abspath(env) if os.path.isdir(env) else None
    d = os.path.abspath(start or os.getcwd())
    while True:
        cand = os.path.join(d, ".proof-os")
        if os.path.isdir(cand):
            return cand
        parent = os.path.dirname(d)
        if parent == d:
            return None
        d = parent


# This file lives inside the project's own gates dir, so its location is a
# reliable second anchor when the gate is run from an unrelated cwd.
HERE = os.path.dirname(os.path.abspath(__file__))
store = find_store() or find_store(HERE)

if project_arg:
    project_gates = os.path.abspath(project_arg)
elif store:
    project_gates = os.path.join(store, "gates")
else:
    project_gates = HERE

if not os.path.isdir(project_gates):
    die(2, "* project gates dir %s not found - unavailable" % project_gates,
        ["every gate file: there was no project copy to read"])


def canonical_candidates():
    for key in ("PROOF_OS_HOME", "PROOF_OS_PLUGIN_ROOT", "PROOF_OS_CANONICAL"):
        v = os.environ.get(key)
        if v:
            yield v
    if store:
        yield os.path.join(store, ".runtime", "proof-os")
        yield os.path.join(os.path.dirname(store), "proof-os")


def looks_canonical(d):
    return (d and os.path.isdir(os.path.join(d, "gates"))
            and os.path.isfile(os.path.join(d, "MANIFEST.sha256")))


canonical = None
tried = []
for c in ([canonical_arg] if canonical_arg else list(canonical_candidates())):
    c = os.path.abspath(c)
    tried.append(c)
    if looks_canonical(c):
        canonical = c
        break

if canonical is None:
    die(2, "* no canonical proof-os tree with a MANIFEST.sha256 found - unavailable\n"
           "  looked at: %s\n"
           "  (set PROOF_OS_HOME, or pass --canonical DIR)"
           % (", ".join(tried) if tried else "nothing to look at"),
        ["every gate file: there was no canonical copy to compare against, so drift "
         "is undecidable - this is NOT a pass"])


# ------------------------------------------------------------------ manifest
MAN_LINE = re.compile(r"^([0-9a-fA-F]{64})\s+(\S.*)$")
man_path = os.path.join(canonical, "MANIFEST.sha256")
try:
    man_raw = open(man_path, encoding="utf-8", errors="replace").read().splitlines()
except OSError as e:
    die(2, "* MANIFEST.sha256 unreadable: %s - unavailable" % e,
        ["every gate file: the canonical manifest could not be read"])

manifest = {}
man_version = None
for ln in man_raw:
    s = ln.strip()
    if not s:
        continue
    if s.startswith("#"):
        m = re.search(r"proof-os\s+([0-9][0-9A-Za-z.\-]*)", s)
        if m and man_version is None:
            man_version = m.group(1)
        continue
    m = MAN_LINE.match(s)
    if m:
        manifest[m.group(2).strip().replace("\\", "/")] = m.group(1).lower()

man_gates = {k[len("gates/"):]: v for k, v in manifest.items() if k.startswith("gates/")}
if not man_gates:
    die(2, "* MANIFEST.sha256 at %s carries no gates/ record (%d record(s) total) - "
           "unavailable" % (man_path, len(manifest)),
        ["every gate file: the manifest named no canonical gate, so there is no "
         "baseline - an empty baseline is not a passing comparison"])


# ------------------------------------------------------------------ hashing
def sha256(p):
    h = hashlib.sha256()
    try:
        with open(p, "rb") as fh:
            for chunk in iter(lambda: fh.read(65536), b""):
                h.update(chunk)
    except OSError:
        return None
    return h.hexdigest()


def gate_files(d):
    out = {}
    for name in sorted(os.listdir(d)):
        p = os.path.join(d, name)
        if os.path.isdir(p):
            continue
        if name in IGNORE_NAMES or name.endswith(".pyc") or ".tmp." in name:
            continue
        out[name] = p
    return out


canon_dir = os.path.join(canonical, "gates")
canon_files = gate_files(canon_dir)
proj_files = gate_files(project_gates)

if not canon_files:
    die(2, "* canonical gates dir %s is empty - unavailable" % canon_dir,
        ["every gate file: the canonical tree shipped no gate to compare against"])

# rc records are the evidence that this project actually runs a given gate.
# `<label>.rc` and `<label>.<pid>.rc` both count.
adopted = set()
rc_dir = os.path.join(store, "rc") if store else None
rc_readable = bool(rc_dir and os.path.isdir(rc_dir))
if rc_readable:
    for name in os.listdir(rc_dir):
        if not name.endswith(".rc"):
            continue
        stem = name[:-3]
        stem = re.sub(r"\.\d+$", "", stem)
        adopted.add(stem)


def stem_of(name):
    base = name
    for ext in (".py", ".sh", ".mjs", ".json", ".js", ".ts"):
        if base.endswith(ext):
            return base[: -len(ext)]
    return base


# ------------------------------------------------------------------ compare
drift = []            # findings: content differs
missing_shared = []   # findings: a shared module is gone
missing_adopted = []  # findings: a gate this project runs is gone
not_adopted = []      # reported, not a finding
unverifiable = []     # canonical file we may not use as a baseline
compared = 0

for name in sorted(canon_files):
    cpath = canon_files[name]
    want = man_gates.get(name)
    got = sha256(cpath)
    if got is None:
        unverifiable.append("%s (canonical file unreadable)" % name)
        continue
    if want is None:
        unverifiable.append("%s (absent from MANIFEST.sha256)" % name)
        continue
    if got != want:
        unverifiable.append("%s (canonical copy FAILS its own manifest: %s != %s)"
                            % (name, got[:12], want[:12]))
        continue

    ppath = proj_files.get(name)
    if ppath is None:
        if name in SHARED:
            missing_shared.append(
                "%s ABSENT from %s - every `from _rc import rc_init` / oracle import in "
                "this tree fails into its `except: pass`, so the capability it provides "
                "is silently off and no gate reports it" % (name, project_gates))
        elif stem_of(name) in adopted:
            missing_adopted.append(
                "%s ABSENT from %s - but .proof-os/rc/ holds a record for %r, so this "
                "project has run this gate; the copy it ran is gone"
                % (name, project_gates, stem_of(name)))
        else:
            not_adopted.append(name)
        continue

    compared += 1
    phash = sha256(ppath)
    if phash is None:
        drift.append("%s UNREADABLE in the project copy (%s) - a gate that cannot be "
                     "read cannot be the gate that ran" % (name, ppath))
        continue
    if phash != got:
        drift.append(
            "%-24s DRIFT  project=%s (%d bytes)  canonical=%s (%d bytes)  [%s]"
            % (name, phash[:12], os.path.getsize(ppath), got[:12],
               os.path.getsize(cpath), cpath))

# Zero comparisons is the exact bug this class exists for: "I checked nothing,
# therefore it passed."
if compared == 0 and not (drift or missing_shared or missing_adopted):
    die(2, "* 0 canonical gate files could be compared against %s - unavailable, NOT "
           "green (canonical=%s, %d canonical file(s), %d verifiable)"
           % (project_gates, canonical, len(canon_files),
              len(canon_files) - len(unverifiable)),
        ["every gate file: nothing was actually compared, which is a broken assertion, "
         "not a passing one"])

# ------------------------------------------------------------------ verdict
print("* stale-runtime-copy: canonical=%s (proof-os %s), project=%s"
      % (canonical, man_version or "version unstated", project_gates))
print("  %d canonical gate file(s), %d manifest-verified, %d compared, "
      "%d project file(s) present" % (len(canon_files),
                                      len(canon_files) - len(unverifiable),
                                      compared, len(proj_files)))

for x in drift:
    print("  DRIFT           %s" % x)
for x in missing_shared:
    print("  MISSING SHARED  %s" % x)
for x in missing_adopted:
    print("  MISSING GATE    %s" % x)
for x in unverifiable:
    print("  UNVERIFIABLE    %s - excluded from the comparison, not blessed by it" % x)
if not_adopted:
    print("  not adopted     %s (shipped canonically, never run here - no rc record; "
          "reported, not a finding)" % ", ".join(not_adopted))

fails = len(drift) + len(missing_shared) + len(missing_adopted)
if fails:
    print("VERDICT: broken - %d finding(s): the gates this project runs are not the "
          "gates proof-os shipped" % fails)
else:
    print("VERDICT: aligned (proved) - every canonical gate present here is "
          "byte-identical, and all %d shared module(s) are present" % len(SHARED))

extra = []
if unverifiable:
    extra.append("%d canonical file(s) that could not be authenticated against "
                 "MANIFEST.sha256 and were therefore NOT used as a baseline: %s"
                 % (len(unverifiable), "; ".join(unverifiable)))
if not_adopted:
    extra.append("%d canonical gate(s) absent from the project copy with no rc record "
                 "to show this project ever ran them, so their absence is treated as "
                 "not-adopted rather than drift: %s"
                 % (len(not_adopted), ", ".join(not_adopted)))
if not rc_readable:
    extra.append("adoption evidence: .proof-os/rc/ was not readable, so NO canonical "
                 "gate could be shown to be adopted here - every absence fell through "
                 "to not-adopted and only drift and the shared modules were enforced")
extra.append("%d project file(s) with no canonical counterpart were not examined"
             % len([n for n in proj_files if n not in canon_files]))
emit(extra)
sys.exit(1 if fails else 0)
