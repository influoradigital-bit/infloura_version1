#!/usr/bin/env bash
# F-0259-discover-loading-empty.sh — gate for F-0259 (no-loading-no-empty-state).
#
# Discover had no loading state and no empty state: while GET /creators was in flight the grid
# was simply blank — identical to a genuine zero-result search — so the brand could not tell
# "still loading" from "nothing matches" from "it broke". On top of that, filtering/sorting all
# ran client-side over one 20-row page, and the price-range filter's UNTOUCHED default
# ([5000, 200000]) silently dropped every creator with no `averageRate` set, because the filter
# read `(c.averageRate ?? 0)` and 0 sits below the 5000 floor — even though the brand never
# touched that slider.
#
# Three legs:
#   1. Structural — distinct loading and empty markers exist in the source (not the same branch
#      doing double duty).
#   2. Structural — the price filter is conditioned on the brand having actually moved it, not
#      applied unconditionally at its untouched default.
#   3. vitest — the behavioral suite: in-flight renders loading (and not empty), a genuine
#      zero-result renders empty (and not loading), and a no-rate creator survives default filters.
#
# What it deliberately does NOT do: assert any particular skeleton visual, exact copy, or that
# every one of the four filters/sort is server-side (a real pagination overhaul, out of scope for
# a MEDIUM ticket) — only that loading/empty are distinguishable and the default rate filter
# doesn't act as an invisible filter nobody asked for.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

SRC=src/components/brand/discover/creator-discovery.tsx
[ -f "$SRC" ] || { echo "· $SRC missing — unavailable"; exit 2; }

echo "· distinct loading and empty markers exist in the source"
if ! grep -qE "discover-loading" "$SRC"; then
  echo "VERDICT: broken — no loading-state marker found; an in-flight search renders nothing"
  echo "         distinguishable from a zero-result search (F-0259)"
  exit 1
fi
if ! grep -qE "discover-empty" "$SRC"; then
  echo "VERDICT: broken — no empty-state marker found; a zero-result search renders nothing"
  echo "         distinguishable from a stalled/loading page (F-0259)"
  exit 1
fi
echo "  clean — discover-loading and discover-empty both present"

echo "· the price-range filter does not fire at its untouched default"
# Bad shape: an unconditional filter over averageRate ?? 0 with no touched-guard anywhere nearby.
if grep -qE "priceFilterTouched|priceRange\[0\] > 5000 \|\| priceRange\[1\] < 200000" "$SRC"; then
  echo "  clean — a touched-guard exists for the price filter"
else
  echo "VERDICT: broken — no guard found; the price-range filter appears to run unconditionally,"
  echo "         which drops every creator with no rate set even at untouched defaults (F-0259)"
  exit 1
fi

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/components/brand/discover/__tests__/creator-discovery-loading-empty.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken — Discover still fails to distinguish loading/empty, or still drops"
  echo "         no-rate creators at default filters (F-0259)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — Discover has a real loading state, a real empty state, and the"
echo "         default rate filter no longer silently excludes unpriced creators"
echo "NOT CHECKED: server-side pagination/filtering (still client-side over one page — a bigger"
echo "             rework than this MEDIUM ticket); an error state distinct from empty (the"
echo "             existing toast-on-failure path is unchanged and untested here); whether the"
echo "             follower/engagement range sliders have the same untouched-default bug class"
echo "             (checked by hand during LOOKUP — their floors are both 0, so ?? 0 never drops"
echo "             below them; only price's non-zero floor did)."
exit 0
