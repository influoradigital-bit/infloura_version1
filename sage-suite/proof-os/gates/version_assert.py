#!/usr/bin/env python3
"""gates/version_assert.py — origin: F-0018 (packaging lied) + F-0024 (stale runtime copy).

F-0018: a sed version-bump chained after a failing compile silently never ran, and three
        releases shipped a stale manifest. Labels are not evidence.
F-0024: a session measured a project with a pre-0.1.5 copy of these scripts, reported the
        numbers as fact, and was wrong by 1,892 edges. Nothing had asserted the version.

This gate refuses to trust the label. It recomputes every shipped file's hash and compares
against MANIFEST.sha256, which is written at package time and read back out of the archive.

LAW (false-red): missing manifest / unreadable plugin => exit 2 (unavailable).
                 exit 1 = the build does not match what it claims to be.
Usage: gates/version_assert.py [plugin_root]
"""
import hashlib, json, os, py_compile, sys, tempfile

root = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
manifest_path = os.path.join(root, ".claude-plugin", "plugin.json")
hashes_path = os.path.join(root, "MANIFEST.sha256")

if not os.path.isfile(manifest_path):
    print(f"· no .claude-plugin/plugin.json under {root} — unavailable"); sys.exit(2)
try:
    declared = json.load(open(manifest_path, encoding="utf-8")).get("version")
except Exception as e:
    print(f"· manifest unparseable: {e} — unavailable"); sys.exit(2)
if not declared:
    print("· manifest declares no version — unavailable"); sys.exit(2)

fails = []

# 1. every shipped script must compile. F-0018 shipped because a compile failure
#    was swallowed and the chained step never ran.
compiled = 0
for sub in ("scripts", "gates"):
    d = os.path.join(root, sub)
    if not os.path.isdir(d):
        continue
    for f in sorted(os.listdir(d)):
        if f.endswith(".py"):
            try:
                py_compile.compile(os.path.join(d, f), cfile=tempfile.mktemp(), doraise=True)
                compiled += 1
            except py_compile.PyCompileError as e:
                fails.append(f"{sub}/{f} does not compile: {str(e).splitlines()[0]}")

# 2. the contents must match the hashes recorded when the version was stamped
if not os.path.isfile(hashes_path):
    print(f"· proof-os {declared} · {compiled} scripts compile")
    print("· no MANIFEST.sha256 — running from a working tree, not a packaged build (unavailable)")
    sys.exit(1 if fails else 2)

recorded, drifted, missing = {}, [], []
for line in open(hashes_path, encoding="utf-8"):
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    h, _, rel = line.partition("  ")
    recorded[rel] = h

for rel, want in recorded.items():
    p = os.path.join(root, rel)
    if not os.path.isfile(p):
        missing.append(rel); continue
    got = hashlib.sha256(open(p, "rb").read()).hexdigest()
    if got != want:
        drifted.append(rel)

# 3. F-0036: the checks above only ever look at files the manifest already knows about.
#    A file ADDED after the version was stamped is invisible to both of them — no drift,
#    no missing, exit 0. That is how a tree that had gained scripts/graph.py and
#    scripts/adapt_graphify.py still passed as "every file matches its recorded hash".
#    An audit that only audits what it was told about is not an audit.
untracked = []
for r, ds, fs in os.walk(root):
    ds[:] = [x for x in ds if x not in ("__pycache__", ".git", ".venv", "node_modules")]
    for f in fs:
        rel = os.path.relpath(os.path.join(r, f), root).replace(os.sep, "/")
        if rel == "MANIFEST.sha256" or rel.endswith(".pyc"):
            continue
        if rel not in recorded:
            untracked.append(rel)

fails += [f"{r} listed in MANIFEST.sha256 but absent from the build" for r in missing]
fails += [f"{r} content does not match its recorded hash — the label is stale" for r in drifted]
fails += [f"{r} present but never hashed — added after the version was stamped, so every "
          f"'all files verified' claim about this build excluded it (F-0036)" for r in untracked]

print(f"proof-os {declared} · {compiled} scripts compile · {len(recorded)} files hashed · "
      f"drifted {len(drifted)} · missing {len(missing)} · untracked {len(untracked)}")
for x in fails:
    print("  ", x)
sys.exit(1 if fails else 0)
