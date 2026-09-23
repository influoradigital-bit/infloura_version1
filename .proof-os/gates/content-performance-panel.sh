#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# content-performance-panel.sh   (F-1783 / F-1784 / F-1785 / F-1786 / F-1788)
#
# Exit 0 = proved · 1 = broken · 2 = unavailable (never green)
#
# The per-post Content Performance panel (creator-analytics + brand-creator-analytics):
#   F-1783  375px layout overflowed its row by 90px and squeezed the title to 0px
#   F-1784  permalink never used, raw Meta enum as title, unconditional trend arrow
#   F-1785  "Eng. rate" named two different formulas
#   F-1786  sorted by poll time, not postedAt; US-format dates
#   F-1788  post thumbnails: VIDEO/REELS must use thumbnail_url never the mp4, Meta-CDN-only
#           host allow-list, link <= 2048 chars, creator route only, onError fallback.
#           Pinned by MediaMetricMapperTest + ContentPerformancePanel.thumbnail.test.tsx.
#
# This gate runs the tests that pin those behaviours. Each was falsified against a
# mutant before this gate existed (sort removed, caption leaked to brands, K-only
# formatter, javascript: link allowed) and went red.
#
# NOT covered, and cannot be by any test here: F-1783's layout. jsdom does no layout.
# It was measured in a real browser at 375px on commit 176e256 (row overflow 91px ->
# 0px, title 0px -> 198px). A future layout regression will NOT turn this gate red.
#
# Resource exhaustion is reported as 2 (unavailable), never 1: on a memory-starved
# host mvn/node die with "out of memory", and calling that a broken fix would be a
# false red.
# ---------------------------------------------------------------------------
set -uo pipefail

cd "$(dirname "$0")/../.." || { echo "GATE 2: cannot reach project root"; exit 2; }

PANEL="src/components/analytics/ContentPerformancePanel.tsx"
FE_TESTS="src/components/analytics/__tests__/ContentPerformancePanel"
for f in "$PANEL" "${FE_TESTS}.post-row.test.tsx" "${FE_TESTS}.post-counts.test.tsx" \
         influora-api/src/test/java/com/influora/service/analytics/AnalyticsServiceTest.java; do
  [ -f "$f" ] || { echo "GATE 2: missing $f -- cannot evaluate"; exit 2; }
done
command -v mvn >/dev/null 2>&1 || { echo "GATE 2: mvn not on PATH"; exit 2; }
command -v node >/dev/null 2>&1 || { echo "GATE 2: node not on PATH"; exit 2; }

LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT
fail=0
oom() { grep -qiE "out of memory|OutOfMemoryError|Cannot reserve|could not reserve|insufficient memory" "$LOG"; }

# --- backend: caption routing + postedAt sort + dedup ------------------------
# Redirect to a file and read $? directly: `mvn | grep` returns grep's exit code.
( cd influora-api && mvn -o -q -Dtest=AnalyticsServiceTest,AnalyticsControllerTest,MediaMetricMapperTest \
    -DfailIfNoTests=false test ) > "$LOG" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
  if oom; then echo "GATE 2: mvn ran out of memory -- backend half unevaluated"; exit 2; fi
  echo "  BROKEN: AnalyticsServiceTest/AnalyticsControllerTest/MediaMetricMapperTest failed (mvn exit $rc)"
  grep -E "^\[ERROR\]   " "$LOG" | head -8
  fail=1
else
  echo "  ok: backend -- sort by postedAt, no caption on any route (ADR 2026-07-06), thumbnail only on the creator route, thumbnail never the mp4, dedup intact"
fi

# --- frontend: link safety, title, dates, labels, number scale ---------------
NO_COLOR=1 FORCE_COLOR=0 node node_modules/vitest/vitest.mjs run "$FE_TESTS" > "$LOG" 2>&1
rc=$?
# Vitest still colours its summary line on some terminals, which made the pass-count
# check below miss a real "39 passed" and report GATE 2. Strip ANSI codes first.
sed -i 's/\x1b\[[0-9;]*m//g' "$LOG"
if [ "$rc" -ne 0 ]; then
  if oom; then echo "GATE 2: node ran out of memory -- frontend half unevaluated"; exit 2; fi
  if grep -q "No test files found" "$LOG"; then echo "GATE 2: vitest found no test files"; exit 2; fi
  echo "  BROKEN: ContentPerformancePanel tests failed (vitest exit $rc)"
  grep -E "×|FAIL" "$LOG" | head -8
  fail=1
else
  # A run that "passes" with zero tests is not a pass.
  if ! grep -qE "Tests +[0-9]+ passed" "$LOG"; then
    echo "GATE 2: vitest reported success but no passing tests were counted"; exit 2
  fi
  echo "  ok: frontend -- $(grep -oE "Tests +[0-9]+ passed" "$LOG" | tail -1)"
fi

if [ "$fail" -ne 0 ]; then
  echo "GATE 1: Content Performance panel regressed"
  exit 1
fi
echo "GATE 0: Content Performance panel behaviours pinned (layout: browser-measured only, see header)"
exit 0
