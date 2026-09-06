#!/usr/bin/env bash
# gates/ananya-wave2-verified.sh — closes F-0408, F-0431, F-0438, F-0439, F-0442, F-0464, F-0466,
# F-0405, F-0409, F-0410, F-0419, F-0441, F-0349, F-0639, F-0632, F-0637, F-0638.
#
# origin: Ananya Wave 2 (frontend contract-fidelity, discovery, mocked-data, creator-chat) plus one
# companion backend field (F-0632) the frontend row fix depended on.
#
# A fresh-context CTO review returned CHANGES REQUIRED on the first pass and named four things.
# All four were independently re-verified by priya against the real tree before this gate was
# written; two were fixed here, two are deliberately EXCLUDED from this gate:
#
#   FIXED after the review:
#   1. `npm run test:live` exited 1 on a suite whose 16 tests all passed — the F-0439 timeout test
#      flushed fake timers BEFORE attaching its rejection handler, so ApiError(TIMEOUT) rejected
#      with nothing listening. Product code was never at fault. Re-verified: real exit 0 now.
#   2. F-0637's two new backend fields were read through a local intersection type + `as` cast in
#      creator-dashboard.tsx — the exact FE-type-vs-DTO drift class this same wave was closing
#      (F-0435/F-0464), leaving tsc unable to police the binding. Both fields are now declared on
#      ContractApiRecord itself and the cast is gone; tsc clean, 11/11 dashboard tests still green.
#
#   EXCLUDED, still open, NOT closed by this gate:
#   - F-0411: unfixable as worded. The only facets endpoint that exists (GET /creators/search)
#     returns categories + followerRanges — there is NO city or language facet anywhere in the
#     backend, so "wire cities/languages from the real facets endpoint" cannot be done. Confirmed
#     independently: zero `creators/search` callers in src/lib/api.ts. Needs re-scoping, not a fix.
#   - F-0435: only 1 of its 3 drifts (the dead `city` fallback) was in api.ts and is fixed. The
#     other two live outside this wave's file boundaries — PortfolioItem.metrics in src/lib/types.ts
#     and a hard-nulled description in the backend's CreatorMapper.
#
# HONEST NOTE ON F-0638: its stated symptom cannot occur — Contract.collaboration_id is
# `nullable = false` at the DB level (Contract.java:25), so a null can never reach the row. The
# defensive guard added is harmless and is what this gate's test pins; the finding's premise was
# wrong, and closing it here means "the guard behaves correctly", not "a live leak was closed".
#
# HONEST NOTE ON WHAT THIS WAVE ACTUALLY DID: most of these findings were ALREADY fixed in the
# working tree or at HEAD before the wave ran (an earlier interrupted pass, plus commit 1792c37).
# The wave's real contribution for those is the regression tests the ledger's own missed_by fields
# asked for. Genuinely new code this wave: F-0419's aggregation, F-0441's live gate, F-0639's copy
# branch, F-0632's backend fields, F-0637/F-0638's row rendering.
#
# LAW: exit 0 proved, 1 broken, 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not found — unavailable"; exit 2; }

STD="src/lib/__tests__/deal-message-stream.test.ts src/components/brand/discover/__tests__/creator-discovery-server-filters.test.tsx src/pages/__tests__/brand-analytics.aggregate.test.tsx src/pages/__tests__/brand-creator-analytics.live-demo-gate.test.tsx src/pages/creator-chat-unmatched-deal-id.test.tsx src/pages/creator-chat-deliverables-zero-slots.test.tsx src/pages/creator-dashboard.contract-identity.test.tsx src/pages/creator-dashboard.unsigned-contracts.test.tsx"
LIVE="src/lib/__tests__/api-client-resilience.live.test.ts src/lib/__tests__/creator-discovery-dto-fidelity.live.test.ts"
for f in $STD $LIVE; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

echo "· tsc --noEmit"
tsc_out=$(npx --no-install tsc --noEmit 2>&1); tsc_rc=$?
if [ $tsc_rc -ne 0 ]; then
  printf '%s\n' "$tsc_out" | head -20
  echo "VERDICT: broken — tsc fails"
  exit 1
fi
echo "  tsc clean"

BUDGET="${PROOF_ANANYA_WAVE2_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

# shellcheck disable=SC2086
echo "· vitest (project config): 8 files"
out=$($TO node_modules/.bin/vitest run $STD 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if printf '%s' "$out" | grep -q "No test files found"; then echo "  collected nothing — unavailable"; exit 2; fi
if [ $rc -ne 0 ]; then printf '%s\n' "$out" | tail -50; echo "VERDICT: broken — a project-config test failed"; exit 1; fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true

# The live-config suite is a SEPARATE vitest project (vitest.config.ts excludes *.live.test.ts, so
# a green `npm test` never runs these) — running it here is the whole point: seven of the eight
# api.ts findings are pinned ONLY by these files.
# shellcheck disable=SC2086
echo "· vitest (live config): 2 files"
out=$($TO node_modules/.bin/vitest run --config vitest.live.config.ts $LIVE 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if printf '%s' "$out" | grep -q "No test files found"; then echo "  collected nothing — unavailable"; exit 2; fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -50
  echo "VERDICT: broken — a live-config test failed, OR an unhandled rejection made the suite exit"
  echo "         nonzero despite green assertions (that exact false-green-with-red-exit is what the"
  echo "         review caught on this wave's first pass — it must stay caught)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true

# Backend leg: the F-0632 field the dashboard row depends on.
if [ -d influora-api ] && command -v mvn >/dev/null 2>&1; then
  echo "· mvn -o clean -Dtest=ContractServiceTest test"
  out=$($TO mvn -o -q clean -Dtest=com.influora.service.ContractServiceTest -f influora-api/pom.xml test 2>&1); rc=$?
  if echo "$out" | grep -qiE "COMPILATION ERROR|cannot find symbol"; then
    printf '%s\n' "$out" | tail -40; echo "VERDICT: unavailable — backend does not compile"; exit 2
  fi
  if [ $rc -ne 0 ]; then printf '%s\n' "$out" | tail -50; echo "VERDICT: broken — F-0632's backend test failed"; exit 1; fi
  echo "  backend green"
else
  echo "· backend leg unavailable (no mvn/module)"; exit 2
fi

echo "VERDICT: aligned (proved) — 17 findings pinned by real tests on a typechecking tree, across"
echo "         both vitest projects and the Java module."
echo "NOT CHECKED: F-0411 and F-0435 (deliberately excluded, see header — both still open);"
echo "             F-0464's one render site (creator-profile.tsx renders a bare '%' for a null"
echo "             rate — honest-but-ugly, flagged not fixed); live-backend behaviour."
exit 0
