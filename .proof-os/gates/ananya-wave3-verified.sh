#!/usr/bin/env bash
# gates/ananya-wave3-verified.sh — closes F-0463, F-0659, F-0662, F-0278.
#
# THIS GATE COVERS ONLY 4 OF THE WAVE'S 8 FINDINGS. A fresh-context CTO review returned
# 3 fixed / 1 stale ticket / 4 NOT FIXED, and its central observation is the reason this gate is
# deliberately narrow:
#
#     "All six per-finding test files pass. That is the problem — four unfixed findings have green
#      tests because each pins something other than the defect."
#
# Every one of those four was independently re-verified by priya against the real tree before this
# gate was written, and each is EXCLUDED and still open:
#   F-0432 — zero code changed. brand-chat.tsx was already the one CORRECT call site; the three
#            still dropping usageRights (deal-room-dashboard.tsx, brand-campaign-detail.tsx,
#            creator-chat.tsx) were outside the assignment's file boundary.
#   F-0440 — the real defect is deal-room-dashboard.tsx:442 (setShowProposalDialog(false) before
#            the await, never reopened in the catch). The wave's test pins brand-chat.tsx, a file
#            with NO diff, so it passes against old code by construction.
#   F-0434 — the fix is INERT. creator-portfolio-editor.tsx now sends `collabs`, but
#            PortfolioPatchRequest (PortfolioDtos.java:119-131) has no such field — 12 fields, none
#            of them collabs — so Jackson silently drops it and nothing persists. Confirmed
#            independently. Its test asserts the outgoing BODY, never a round-trip, so it stays
#            green forever while the feature never works. This is the project's own documented
#            "FE type asserts a field the DTO never accepts" class.
#   F-0640 — the render block added to creator-deal-contract-tab.tsx is DEAD: its only caller,
#            creator-chat.tsx:2873-2883, passes contractId/brandName/campaignName/amount/
#            contractAmount/status/onStatusChange and NO milestones prop — verified by reading the
#            call site. Every real creator still sees "No payment milestones are on file". All four
#            of its tests render the component in isolation with the prop handed in.
#
# THREE REGRESSIONS this wave introduced or exposed, all fixed and re-verified before this gate:
#   1. `npm test` exited 1. contracts-sign-reachability.test.tsx pinned a 4-status closed set that
#      F-0659 legitimately grew to 5; brand-auth-identity.test.tsx did not fill the name fields
#      F-0463 legitimately made required. Both sibling invariants updated.
#   2. Also in that exit-1: creator-protected-route.test.tsx failed as a SUITE with zero assertions
#      run — importing all of @/App exceeded the 30s default hookTimeout under full-suite load
#      (passes alone in ~13s). Raised to 120s for that file only.
#   3. F-0659's own fix silently dropped the Download PDF button AND the "Contract Fully Executed"
#      block for COMPLETED contracts, because both gates still tested only the 'signed' bucket that
#      COMPLETED used to collapse into. A superseded contract WAS fully executed and its PDF is
#      exactly the historical record a brand wants. Both gates widened.
#
# F-0278 is closed here as a FALSE FINDING on verification: already fixed pre-wave in commit
# 535e024 (dashboard-page.tsx first-run vs genuinely-cleared branch). The ledger row was stale.
#
# LAW: exit 0 proved, 1 broken, 2 unavailable. Runs the FULL suite plus tsc plus the live project,
# because this wave's failures were all cross-file: a per-file green proved nothing here.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not found — unavailable"; exit 2; }

for f in src/pages/brand-register.no-name-fabrication.test.tsx \
         src/components/brand/contracts/__tests__/contracts-and-deliverables.status-label.test.tsx \
         src/pages/creator-profile.engagement-rate-null.test.tsx; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

echo "· tsc --noEmit"
out=$(npx --no-install tsc --noEmit 2>&1); rc=$?
if [ $rc -ne 0 ]; then printf '%s\n' "$out" | head -20; echo "VERDICT: broken — tsc fails"; exit 1; fi
echo "  tsc clean"

BUDGET="${PROOF_ANANYA_WAVE3_TIMEOUT:-900}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 15 $BUDGET"; else TO=""; fi

# The FULL suite, deliberately: two of this wave's three regressions were sibling files the wave
# never touched, invisible to any per-file run.
echo "· npm test (full suite)"
out=$($TO npm test 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "FAIL|Test Files|Tests |AssertionError" | tail -30
  echo "VERDICT: broken — the full suite fails (a per-file green does not clear this wave)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Test Files|Tests " | tail -2 | sed 's/^/  /'

echo "· npm run test:live"
out=$($TO npm run test:live 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -25
  echo "VERDICT: broken — the live project fails, or an unhandled rejection made it exit nonzero"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Test Files|Tests " | tail -2 | sed 's/^/  /'

echo "VERDICT: aligned (proved) — F-0463 (no fabricated human name is persisted or greeted),"
echo "         F-0659 (COMPLETED renders distinguishably from ACTIVE, and still keeps its PDF and"
echo "         execution record), F-0662 (a null engagement rate says so instead of rendering a"
echo "         bare unit) and F-0278 (first-run is distinguished from genuinely-cleared) hold on a"
echo "         typechecking tree with BOTH vitest projects fully green."
echo "NOT CHECKED: F-0432, F-0440, F-0434, F-0640 — all four deliberately excluded and still open;"
echo "             see header for why each one's currently-green test does not prove its finding."
echo "             Also not checked: F-0662's sibling at creator-profile.tsx:431, which still"
echo "             interpolates a per-platform engagementRate raw against a nullable backend column."
exit 0
