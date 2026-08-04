"""_verify_archive.py — recompute every hash inside an extracted archive.

Used by build-plugin.sh step 4. Independent of the plugin's own gates, so it works
for any plugin, including ones that ship no gates/ directory at all.
Exit 0 = the archive is exactly what MANIFEST.sha256 says it is.
"""
import hashlib, os, sys

root = sys.argv[1]
man = os.path.join(root, "MANIFEST.sha256")
if not os.path.isfile(man):
    print("  FAIL: no MANIFEST.sha256 inside the archive"); sys.exit(1)

recorded = {}
for line in open(man, encoding="utf-8"):
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    h, _, rel = line.partition("  ")
    recorded[rel] = h

drifted, missing = [], []
for rel, want in recorded.items():
    p = os.path.join(root, rel)
    if not os.path.isfile(p):
        missing.append(rel); continue
    if hashlib.sha256(open(p, "rb").read()).hexdigest() != want:
        drifted.append(rel)

# also catch files present in the archive but absent from the manifest
on_disk = set()
for r, dirs, files in os.walk(root):
    dirs[:] = [d for d in dirs if d != "__pycache__"]
    for f in files:
        rel = os.path.relpath(os.path.join(r, f), root).replace(os.sep, "/")
        if rel != "MANIFEST.sha256":
            on_disk.add(rel)
extra = sorted(on_disk - set(recorded))

print(f"  {len(recorded)} files hashed · drifted {len(drifted)} · missing {len(missing)} · unlisted {len(extra)}")
for r in drifted: print(f"    DRIFT: {r}")
for r in missing: print(f"    MISSING: {r}")
for r in extra:   print(f"    UNLISTED: {r}")
sys.exit(1 if (drifted or missing or extra) else 0)
