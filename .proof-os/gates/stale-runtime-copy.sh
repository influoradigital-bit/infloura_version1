#!/usr/bin/env bash
# gates/stale-runtime-copy.sh — CLASS gate for stale-runtime-copy.
# Closes F-0053, F-0426, F-0428, F-0469.
#
# The class: this project keeps its own copies of the plugin's gates, and the copies drift. Every
# row is the same shape and the same consequence — a gate that has drifted BEHIND runs weaker
# checks than the canonical one and still reports green, so the trust layer says "proved" on
# evidence it never gathered:
#
#   F-0053 registry_render.py was proof-os 0.3.x in-project while registry.json declared 0.4.1;
#          the 0.4.2 plugin copy exited 0 on the same file the project copy rejected.
#   F-0426 citations.py was a superseded stub that exited 0 on a document with ZERO citations.
#   F-0428 nine gates were all smaller in-project than canonical, not just citations.py.
#   F-0469 five gates had run records proving this project executed them while the files were gone.
#
# THE RULE: a gate present in both trees must be byte-identical, unless the project copy declares
# `PROJECT-LOCAL FORK:` with a reason. Identity is not the goal — REVIEWABILITY is. A declared fork
# is a decision someone can audit; silent drift is the defect. And any gate the registry names must
# resolve to a file, or a past verdict from it can never be reproduced.
#
# MEASURED WHEN WRITTEN: 16 shared gates, 13 already identical, 3 diverged. Two of those three were
# genuinely stale — eslint.sage.json was a strict SUBSET of canonical (no `root`, no parserOptions,
# so no JSX parsing at all) and eslint.sage.mjs lacked BOTH the F-0208 underscore convention and
# Swapnil's F-0209 motion.* ruling. The project was running a lint gate that predated two rulings it
# was supposed to enforce. Both were synced from canonical. The third, build.sh, is a real fork and
# now says so.
#
# WHAT SIZE COMPARISON MISSES, and why this gate hashes instead: the project build.sh is LARGER than
# canonical, which reads as "ahead" — while simultaneously LACKING the plugin's law-5 exit-trap
# content. Drift runs both ways at once. That gap is F-0687, recorded rather than blessed.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v python >/dev/null 2>&1 || { echo "· python not on PATH — unavailable"; exit 2; }
SCAN="$SELF/_stale_copy_scan.py"
[ -f "$SCAN" ] || { echo "· $SCAN missing — unavailable"; exit 2; }

python "$SCAN"
rc=$?
[ "$rc" -eq 2 ] && exit 2
[ "$rc" -ne 0 ] && exit 1

echo "VERDICT: aligned (proved) — no gate this project shares with the plugin has drifted without"
echo "         saying so, and every gate the registry names resolves to a file that can be re-run."
echo "NOT CHECKED: whether a DECLARED fork is a good idea — the gate reads the marker, it cannot"
echo "             judge the reason, so a fork declared for a bad reason passes. Gates that exist"
echo "             ONLY in the project (the F-#### instance gates) have no canonical counterpart"
echo "             and are not compared at all. Nothing here verifies the plugin copy is itself"
echo "             current, only that the two agree — if both are stale, this gate is green and"
echo "             wrong. And byte-identity says nothing about whether a gate WORKS: a shared gate"
echo "             that is broken in both trees passes this check untouched."
exit 0
