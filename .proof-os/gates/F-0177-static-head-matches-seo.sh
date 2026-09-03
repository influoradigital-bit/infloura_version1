#!/usr/bin/env bash
# F-0177-static-head-matches-seo.sh — gate for F-0177 (content-consistency).
#
# THE RECORD. index.html:6,18 vs src/pages/landing.tsx:165 — "homepage <title>/og:title says
# 'Escrow-Protected Influencer Marketing' pre-hydration; client <Seo> title says
# 'Escrow-protected influencer deals' post-hydration — two taglines for one page."
#
# WHAT WAS MISSING. "no automated check compares the static index.html head to each page's <Seo>
# title output; would need a small script that renders each route and diffs title strings."
#
# WHAT THIS IS. index.html is the one static shell the app is served from. For "/" it is also
# the page's own head: every non-JS crawler, and every real browser until the bundle hydrates,
# reads it. src/pages/landing.tsx's <Seo> then overwrites the same head. This gate mounts the
# REAL Seo component with the REAL props landing.tsx passes it, reads the post-hydration
# document.head back, parses index.html's <head> through DOMParser, and diffs every singleton
# tag both sides emit. It is not a substring grep and it is not a re-implementation of Seo's
# title-suffix / canonical-resolution rules — those run for real.
#
# The static side is read as a parsed DOM, so index.html's long HTML comments and its non-JS
# <body> fallback (which quotes retired taglines verbatim) are Comment and <body> nodes that
# querySelector cannot reach. A fix whose comment mentions an old string cannot fail this gate.
#
# SCOPE. Only the "/" route is compared. Every other marketing route gets its own static file
# from scripts/prerender.mjs at build time, so the raw shell is not their published head; "/" is
# the one route whose shipped pre-hydration head lives in this repo as source.
#
#   exit 0 = proved (defect absent) · 1 = broken (defect present) · 2 = unavailable
set -u

ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
SELF=$(cd "$(dirname "$0")" 2>/dev/null && pwd) || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

SPEC=".proof-os/gates/F-0177.static-head-matches-seo.spec.tsx"
GATES_CFG="$SELF/vitest.gates.config.ts"
MARKER="$SELF/.F-0177.unavailable"

for f in index.html src/pages/landing.tsx src/lib/seo/Seo.tsx "$SPEC" vitest.config.ts "$GATES_CFG"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -x node_modules/.bin/vitest ] || [ -f node_modules/.bin/vitest ] || {
  echo "· vitest not installed — unavailable"; exit 2; }

rm -f "$MARKER" 2>/dev/null || true

BUDGET="${PROOF_F0177_VITEST_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

echo "· rendering src/pages/landing.tsx's <Seo> and diffing it against index.html's <head>"
out=$($TO node_modules/.bin/vitest run --config "$GATES_CFG" --root . "$SPEC" --reporter=basic 2>&1); rc=$?

if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  spec exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "VERDICT: unavailable — the head comparison did not finish"
  echo "NOT CHECKED: everything below; nothing was compared"
  exit 2
fi

if [ -f "$MARKER" ]; then
  echo "  $(cat "$MARKER" 2>/dev/null)"
  echo "VERDICT: unavailable — the gate could not read one of the two heads it compares"
  echo "NOT CHECKED: the static-vs-hydrated head diff; no drift claim is made either way"
  exit 2
fi

if printf '%s' "$out" | grep -q "No test files found"; then
  echo "  vitest collected no test file for $SPEC — unavailable"
  echo "VERDICT: unavailable — the fixture was not collected"
  echo "NOT CHECKED: the static-vs-hydrated head diff"
  exit 2
fi

if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | sed -n '1,80p'
  echo "VERDICT: broken — index.html's <head> and the head src/pages/landing.tsx renders describe"
  echo "         the same URL differently, so \"/\" ships one set of tags to a non-JS crawler and a"
  echo "         different set to a hydrated browser (F-0177)"
  echo "NOT CHECKED: routes other than \"/\" (scripts/prerender.mjs writes their static heads at"
  echo "             build time, not in source); the prerendered dist/ output; whether either"
  echo "             wording is the RIGHT wording — only that the two agree; JSON-LD payloads;"
  echo "             og:locale / og:image:alt / robots crawler directives, which index.html emits"
  echo "             and <Seo> does not, so there is no pair to compare."
  exit 1
fi

printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true

echo "VERDICT: aligned (proved) — the real <Seo> was mounted with the real props landing.tsx passes"
echo "         it, and every singleton head tag that index.html and the hydrated head both emit"
echo "         (title, description, canonical, the og:* set and the twitter:* set) carries an"
echo "         identical value, with both sides agreeing on indexability. \"/\" describes itself"
echo "         the same way before and after hydration."
echo "NOT CHECKED: routes other than \"/\" (scripts/prerender.mjs writes their static heads at"
echo "             build time, not in source); the prerendered dist/ output; whether either"
echo "             wording is the RIGHT wording — only that the two agree; JSON-LD payloads;"
echo "             og:locale / og:image:alt / robots crawler directives, which index.html emits"
echo "             and <Seo> does not, so there is no pair to compare; the <body> non-JS fallback"
echo "             copy, which F-SEO-marketing-surface.sh owns."
exit 0
