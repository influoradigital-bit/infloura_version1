#!/usr/bin/env bash
# gates/F-0625-unguarded-property-access.sh
# origin failure: F-0625 (unguarded-property-access), opened by priya 2026-09-04, found during
# the zero-context QA pass on F-0447 (creator pending-signature contracts list).
#
#   "contract.milestones.length is accessed with no null guard on the new unsigned-contracts row,
#    unlike totalAmount on the same row which tolerates null via formatINR. If the server ever
#    sends a contract with no milestones array the whole dashboard render throws."
#
# missed_by: "a test seeding a contract with milestones absent and asserting the section still
# renders". That sentence is this gate's whole specification.
#
# THE FIX, in src/pages/creator-dashboard.tsx (the "Contracts awaiting your signature" row):
#   {contract.milestones?.length ?? 0} milestone
#   {(contract.milestones?.length ?? 0) === 1 ? '' : 's'} ...
# was, before the fix: {contract.milestones.length} milestone{contract.milestones.length === 1 ...}
#
# WHY THIS IS AN EXECUTION GATE, NOT A GREP. Grepping for "?." on this line proves the token is
# present, not that a contract missing its milestones array actually renders instead of throwing —
# the guard could be on the wrong property, or the JSX could crash earlier in the same block for
# an unrelated reason. The subject is rendered with milestones absent, undefined and null, and the
# gate looks for a real uncaught exception, the same way React would surface it to a real user.
#
# THE TEST. src/pages/creator-dashboard.unsigned-contracts.test.tsx, describe block "creator
# dashboard — unsigned contract missing milestones (F-0625 regression)": three it.each cases
# (milestones absent / undefined / null) asserting the section renders, shows the "0 milestones"
# fallback, and the rest of the dashboard (section heading, layout) survives. This is a REAL
# product test file living in src/pages/ (not a throwaway .proof-os/gates fixture) — the same
# test also covers F-0447's original four cases (API call, row rendering, empty state, error
# state), so this gate is deliberately narrow: it runs the whole file, not one it() in isolation,
# because a regression anywhere in that describe block is this gate's business too.
#
# FALSIFICATION, MEASURED (not assumed), 2026-09-04, on this tree:
#   · unmodified tree                                        -> 9/9 pass (this file's 7 + the
#                                                                sibling unmatched-deal-id file's 2,
#                                                                run together as they were written)
#   · contract.milestones?.length ?? 0 reverted, both spots, to contract.milestones.length
#                                                              -> exactly 3 of 7 in this file fail,
#                                                                 each a real uncaught TypeError at
#                                                                 creator-dashboard.tsx:573, not a
#                                                                 soft assertion miss; the 4
#                                                                 pre-existing F-0447 cases (fixture
#                                                                 always has milestones) still pass
#   · reverted back to the guarded form                       -> 9/9 pass again
#
# LAW: exit 0 = proved · exit 1 = broken (real finding) · exit 2 = cannot run.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

F=src/pages/creator-dashboard.tsx
T=src/pages/creator-dashboard.unsigned-contracts.test.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
[ -f "$T" ] || { echo "· $T missing — the regression test is gone — unavailable"; exit 2; }

if ! grep -q "milestones?.length" "$F"; then
  echo "· $F no longer guards contract.milestones?.length — the property access F-0625 is about"
  echo "  looks to have been rewritten. This gate cannot tell whether that rewrite is still safe"
  echo "  from source alone (that is exactly the point of running the test below), but the"
  echo "  specific guard this gate was written to pin is gone."
fi

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
if [ ! -f node_modules/.bin/vitest ]; then
  echo "· node_modules/.bin/vitest not found — unavailable"
  echo "NOT CHECKED: everything this gate asserts. There is deliberately no grep-only fallback:"
  echo "             a token check proves nothing about whether the render actually throws."
  exit 2
fi
[ -f vitest.config.ts ] || { echo "· vitest.config.ts missing — unavailable"; exit 2; }

BUDGET="${PROOF_F0625_VITEST_TIMEOUT:-180}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
echo "· vitest: $T (budget ${BUDGET}s)"
out=$($TO node_modules/.bin/vitest run "$T" 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  exit 2
fi
if printf '%s' "$out" | grep -q "No test files found"; then
  echo "  vitest collected no test file for $T — unavailable"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -50
  echo "VERDICT: broken — a contract missing its milestones array crashes the \"Contracts awaiting"
  echo "         your signature\" render, or a sibling F-0447 case regressed (F-0625)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true
echo "  suite green"

echo "VERDICT: aligned (proved) — a contract row with milestones absent, undefined and null was"
echo "         RENDERED inside the dashboard's unsigned-contracts section and none of the three"
echo "         threw; each showed the '0 milestones' fallback and the rest of the dashboard"
echo "         survived. Falsified: reverting the ?? 0 guard turns exactly the 3 milestone cases"
echo "         red with a real uncaught TypeError at creator-dashboard.tsx:573, nothing else."
echo "NOT CHECKED: any other optional/nullable field on this same row (totalAmount, brandSignedAt,"
echo "             collaborationId) — this gate is scoped to milestones, the field F-0625 named."
echo "             A null collaborationId still interpolates unguarded into the row's href (a"
echo "             separate, still-open finding). Runtime/live-backend behaviour."
exit 0
