#!/usr/bin/env bash
# build-plugin.sh — package proof-os and PROVE the archive is what it says it is.
# origin: F-0018 (a sed version-bump chained after a failing compile silently never ran;
#          three releases shipped a stale manifest, caught only because someone hashed files).
#
# Order matters and is deliberate:
#   1. compile everything FIRST — a failure here stops the build, it does not chain past it
#   2. stamp MANIFEST.sha256 from the real bytes
#   3. zip
#   4. EXTRACT THE ZIP AGAIN and assert version + hashes from inside it
# Step 4 is the whole point. Everything before it is a claim; step 4 is the evidence.
#
# Usage: build-plugin.sh <plugin_dir> <expected_version> [out.zip]
set -euo pipefail
SRC="${1:?usage: build-plugin.sh <plugin_dir> <expected_version> [out.zip]}"
WANT="${2:?expected version required — the build asserts against it, never reads it back as truth}"
OUT="${3:-proof-os-$WANT.plugin}"
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT";; esac   # absolute: step 3 cd's into $SRC

command -v python3 >/dev/null || { echo "FAIL: python3 required"; exit 2; }
command -v zip     >/dev/null || { echo "FAIL: zip required";     exit 2; }

echo "· 1/4 compile"
# NB: run the loop in python, not a shell pipeline. A `while read` pipeline runs in a
# subshell, so its `exit 1` cannot fail the build — that is the F-0018 shape exactly.
python3 "$SRC/../_compile_check.py" "$SRC" || { echo "  build stopped: compile failed"; exit 1; }
for f in "$SRC"/gates/*.sh; do [ -e "$f" ] || continue; bash -n "$f" || { echo "  FAIL: $f"; exit 1; }; done
echo "  ok"

echo "· 2/4 stamp version + hashes"
python3 - "$SRC" "$WANT" <<'PY'
import hashlib, json, os, sys
src, want = sys.argv[1], sys.argv[2]
m = os.path.join(src, ".claude-plugin", "plugin.json")
d = json.load(open(m, encoding="utf-8"))
d["version"] = want                      # set it here, once, before anything is packed
json.dump(d, open(m, "w", encoding="utf-8"), indent=2)
lines = []
for root, dirs, files in os.walk(src):
    dirs[:] = [x for x in dirs if x not in ("__pycache__", ".git")]
    for f in sorted(files):
        if f == "MANIFEST.sha256":
            continue
        p = os.path.join(root, f)
        rel = os.path.relpath(p, src).replace(os.sep, "/")
        lines.append(f"{hashlib.sha256(open(p,'rb').read()).hexdigest()}  {rel}")
open(os.path.join(src, "MANIFEST.sha256"), "w", encoding="utf-8").write(
    "# proof-os " + want + " — recomputed and verified by gates/version_assert.py\n"
    + "\n".join(sorted(lines)) + "\n")
print(f"  stamped {want} · {len(lines)} files hashed")
PY

echo "· 3/4 zip"
rm -f "$OUT"
( cd "$SRC" && zip -q -r -X "$OUT" . -x '*__pycache__*' '*.pyc' )
echo "  wrote $OUT ($(wc -c < "$OUT") bytes)"

echo "· 4/4 verify FROM INSIDE the archive (this is the only step that proves anything)"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
unzip -q "$OUT" -d "$TMP"
GOT=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['version'])" "$TMP/.claude-plugin/plugin.json")
if [ "$GOT" != "$WANT" ]; then
  echo "  FAIL: archive declares $GOT, expected $WANT — this is F-0018 recurring"; exit 1
fi
# Verify hashes from inside the archive. Done inline, because not every plugin ships
# gates/version_assert.py — assuming it did was itself a build-script bug (a verifier
# that silently cannot run is the silent-oracle trap, F-0023).
python3 "$SRC/../_verify_archive.py" "$TMP" || { echo "  FAIL: archive contents drifted from MANIFEST"; exit 1; }
if [ -f "$TMP/gates/version_assert.py" ]; then
  python3 "$TMP/gates/version_assert.py" "$TMP" || { echo "  FAIL: version_assert rejected the archive"; exit 1; }
else
  echo "  (no gates/version_assert.py in this plugin — inline hash check only)"
fi
echo "  PROVED: $OUT is proof-os $GOT and every file matches its recorded hash"
