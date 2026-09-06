#!/usr/bin/env bash
# Gate for W2 of T-FRONTEND-REWORK-0905 — the prerender build's own success
# guard is satisfied by the exact failure it exists to catch.
#
# THE DEFECT THIS GATE EXISTS TO CATCH
# -------------------------------------
# scripts/prerender.mjs:315 accepts a route's snapshot on nothing more than
# `root.textContent.trim().length > 40`. Headless Chrome runs with
# `--disable-gpu` (scripts/prerender.mjs:442); `/` is the only prerendered
# route that mounts WebGL (<HeroGlobeGate /> in src/pages/landing.tsx); the
# globe throws, React's ErrorBoundary catches, and the fallback copy —
# "Something went wrong. An unexpected error occurred on this page." — is
# itself 80+ characters. The length check the build relies on to say "this
# route shipped real content" is satisfied by the crash screen. `dist/`
# reports success while `dist/index.html` ships 4,953 bytes of error copy
# and zero of the five JSON-LD blocks the homepage is supposed to carry.
# Every other prerendered route is fine; this is a single-route, single-cause
# defect that a byte-length guard structurally cannot see.
#
# WHAT THIS GATE ASSERTS, PER PRERENDERED ROUTE UNDER dist/
# ----------------------------------------------------------
#   1. TEXT       — the snapshot does NOT contain the ErrorBoundary's fallback
#                    heading text
#   2. H1         — a non-empty <h1> exists whose de-tagged text is not that
#                    fallback text
#   3. STRUCTURAL — the snapshot does NOT contain BOTH of the ErrorBoundary
#                    fallback's controls ("Try again" / "Reload page"),
#                    checked independently of 1/2 so a heading-only copy edit
#                    cannot blind the gate to a crashed route
#   4. LD+JSON    — the route's application/ld+json block count is >= this
#                    route's baseline — see "JSON-LD BASELINE" below
#
# Checks 1-3 are computed entirely inside lib/w2_check_route.py, which reads
# src/components/ErrorBoundary.tsx AT GATE-RUN-TIME to derive the heading
# text for checks 1/2 (never a hardcoded literal — see that file's module
# docstring for why: a prior version hardcoded the string and Kavya's review
# showed a copy-only rename of the heading silently defanged the gate). If
# ErrorBoundary.tsx is missing, unreadable, or its fallback heading (or the
# check-3 button labels) cannot be located in it, the helper exits 2 — this
# script propagates that as GATE UNAVAILABLE immediately (see the loop
# below), never as a pass and never as an ordinary per-route FAIL.
#
# JSON-LD BASELINE — PER-ROUTE FLOOR, DATA NOT CODE
# ----------------------------------------------------------
# lib/w2_ldjson_baseline.json holds one MINIMUM ld+json block count per
# route (26 routes — see that file's header comment for how each number was
# derived and cited). The helper looks the current route up in that table
# and asserts current_count >= baseline; a route NOT listed there is GATE
# UNAVAILABLE (exit 2), not a pass — there is no default/fallback floor.
# A guessed default (1, or any other number) is a no-op or a quieter version
# of the same defect for a route nobody has derived a real floor for yet, so
# adding an unlisted route must break the build until its floor is derived
# and added explicitly. A DECREASE from baseline is a regression (schema
# silently dropped) and FAILS the route; an INCREASE is fine and PASSES —
# adding schema later is not a defect, so this is a floor, not an
# exact-match table. This replaced an earlier `>= 1` floor for every
# non-homepage route (too weak to catch a route that regressed from, say, 3
# blocks to 1) and an exact `== 5` homepage-only special case (folded into
# the same per-route table instead of staying a one-off). See the JSON
# file's own header comment before editing any of its numbers — it explains
# how they were derived and cites the exact call sites, per the same
# "don't hardcode a table you cannot justify" rule this gate has always
# followed.
#
# DELIBERATELY OUT OF SCOPE
# --------------------------
# - Whether the JSON-LD PAYLOADS are schema.org-valid (types, required
#   properties). This gate counts <script type="application/ld+json"> BLOCKS
#   textually; it does not parse or validate their JSON contents.
# - Whether the <h1> COPY is good, only that it exists, is non-empty, and is
#   not the crash fallback.
# - dist/app-shell.html — it is the SPA catch-all shell, not a prerendered
#   route in PRERENDER_ROUTES, and is not matched by the dist/**/index.html
#   scan below.
# - Modifying scripts/prerender.mjs itself. That guard is a separate concern
#   (W2 is "harden the gate", not "fix the guard the build trusts") and
#   changing it here would let the producer mark their own homework.
# - Anything about /:handle creator-profile prerendering, robots.txt,
#   sitemap.xml membership, or bundle size — those are W6/W7/W4/W3.
#
# EXIT: 0 clean · 1 a route failed a check · 2 could not run
set -uo pipefail

# Capture this gate's own directory BEFORE cd'ing to the project root, so the
# python helper can still be found afterwards regardless of how this script
# was invoked (relative or absolute path).
GATE_DIR="$(cd "$(dirname "$0")" && pwd)" || { echo "GATE UNAVAILABLE: cannot resolve gate directory"; exit 2; }
HELPER="$GATE_DIR/lib/w2_check_route.py"

cd "$GATE_DIR/../.." || { echo "GATE UNAVAILABLE: cannot reach project root"; exit 2; }

command -v grep >/dev/null 2>&1 || { echo "GATE UNAVAILABLE: grep missing"; exit 2; }
command -v find >/dev/null 2>&1 || { echo "GATE UNAVAILABLE: find missing"; exit 2; }

[ -f "$HELPER" ] || { echo "GATE UNAVAILABLE: helper script missing: $HELPER"; exit 2; }

# Find a python interpreter that actually RUNS. On this Windows box `python3`
# exists on PATH only as a Microsoft Store app-execution-alias stub that
# resolves via `command -v` but exits non-zero (and prints an install nag)
# the moment it is invoked — `command -v python3` alone is not proof it
# works. Probe by actually running --version.
PY=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" --version >/dev/null 2>&1; then
    PY="$candidate"
    break
  fi
done
[ -n "$PY" ] || { echo "GATE UNAVAILABLE: no working python interpreter found (python3/python both failed --version)"; exit 2; }

# --- locate the dist directory and its prerendered routes -------------------
# Optional $1 overrides which directory plays the role of "dist" — used ONLY
# by the falsification harness (.proof-os/gates/fixtures/w2/) to point this
# same, unmodified gate at a small fixture tree that stands in for a full
# build. Real invocations pass no argument and get the real "dist".
DIST_DIR="${1:-dist}"

if [ ! -d "$DIST_DIR" ]; then
  echo "GATE UNAVAILABLE: $DIST_DIR/ does not exist — nothing was built, so there is nothing to check. This must never read as a pass."
  exit 2
fi

# Every prerendered route ships as $DIST_DIR/<route>/index.html, and the
# homepage as $DIST_DIR/index.html. dist/app-shell.html is deliberately NOT
# matched (see header). mapfile keeps this correct even if a future route
# path contains a space.
mapfile -d '' -t ROUTE_FILES < <(find "$DIST_DIR" -type f -name 'index.html' -print0 | sort -z)

if [ "${#ROUTE_FILES[@]}" -eq 0 ]; then
  echo "GATE UNAVAILABLE: found zero $DIST_DIR/**/index.html files — no route directories to check. This must never read as a pass."
  exit 2
fi

echo "== W2: prerender artifact integrity, ${#ROUTE_FILES[@]} route(s) under $DIST_DIR/ =="
echo

fail=0
checked=0

for file in "${ROUTE_FILES[@]}"; do
  # Derive the route label purely for readable output — not load-bearing for
  # any check. $DIST_DIR/index.html -> "/", $DIST_DIR/about/index.html ->
  # "/about", $DIST_DIR/blog/foo/index.html -> "/blog/foo".
  route="/${file#"$DIST_DIR"/}"
  route="${route%index.html}"
  case "$route" in
    "//") route="/" ;;
    */) route="${route%/}" ;;
  esac
  [ -n "$route" ] || route="/"

  # MSYS bash auto-converts a bare argument that LOOKS like an absolute POSIX
  # path (e.g. "/about") into a Windows path (e.g. "C:/Program Files/Git/about")
  # when it is handed to a native non-MSYS executable such as python.exe. That
  # would corrupt only the printed route label (the file-path argument is an
  # unrelated string and is unaffected), but a label should say what route it
  # means — so strip the leading "/" before it ever crosses the exec boundary;
  # the helper re-adds it for display and for its own baseline lookup.
  route_arg="${route#/}"

  checked=$((checked + 1))
  "$PY" "$HELPER" "$file" "$route_arg"
  rc=$?
  if [ "$rc" -eq 2 ]; then
    # The helper could not derive the ErrorBoundary signature, could not load
    # the baseline table, or could not read the target file at all. This is
    # never folded into an ordinary per-route FAIL (which would print as
    # "exit 1, see reasons above") — it means the gate itself does not know
    # what to check, and must say so loudly instead of guessing or skipping.
    echo
    echo "GATE UNAVAILABLE: helper could not evaluate $route (see its output above)"
    exit 2
  elif [ "$rc" -ne 0 ]; then
    fail=1
  fi
done

echo
if [ "$fail" -eq 0 ]; then
  echo "PASS — $checked route(s) carry real content, a real <h1>, no ErrorBoundary fallback (text or"
  echo "       structural), and meet their per-route JSON-LD baseline (lib/w2_ldjson_baseline.json)"
  echo "NOT CHECKED: JSON-LD payload validity (block presence only, not schema.org correctness);"
  echo "             <h1> copy quality (existence and non-emptiness only);"
  echo "             dist/app-shell.html, robots.txt, sitemap.xml, bundle size (out of scope — W6/W7/W4/W3)."
  exit 0
fi

echo "FAIL — see above"
exit 1
