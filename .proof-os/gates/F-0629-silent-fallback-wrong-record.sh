#!/usr/bin/env bash
# gates/F-0629-silent-fallback-wrong-record.sh
# origin failure: F-0629 (silent-fallback-to-wrong-record), opened by priya 2026-09-04, found by
# a zero-context QA pass reading the F-0447 routing code (not by exercising the app).
#
#   "selectedDeal resolved as dealRooms.find(d => d.id === selectedDealId) ?? dealRooms[0] ??
#    null - an explicitly-requested id that matched nothing silently fell back to the creator's
#    FIRST deal, showing its messages/contract/deliverables with no error."
#
# missed_by: "the new regression test, which failed against the original code and passes against
# the fix". That sentence is this gate's whole specification.
#
# WHY THIS IS DANGEROUS, CONCRETELY. src/pages/creator-dashboard.tsx links each unsigned contract
# to /creator/chat?deal=<collaborationId>&tab=contract. If that id is ever stale (a deal that
# aged out of the dealRooms fetch window, one the API stopped returning, a copy-pasted link) the
# OLD code did not fail — it substituted dealRooms[0], a DIFFERENT deal belonging to the SAME
# creator, and rendered its messages/contract/deliverables as if it were the one requested. A
# multi-deal creator following a stale link would see a real, plausible-looking, WRONG contract
# with no error of any kind.
#
# THE FIX, src/pages/creator-chat.tsx (see the F-0447 round 2 comment at the selectedDeal memo):
# dealRooms[0] is now the fallback ONLY when no id was requested at all (selectedDealId == null);
# an explicit id that matches nothing falls through to null and the existing "No deals yet" guard.
#
# WHY THIS IS AN EXECUTION GATE, NOT A GREP. Grepping for "selectedDealId == null" proves the
# token is present, not that an unmatched id genuinely reaches the empty-state guard instead of a
# substituted deal, and a grep cannot tell "no id at all" apart from "an id that matched nothing"
# — which is the entire distinction the bug is about. The page is rendered both ways and the
# gate looks at which deal (if any) it actually shows.
#
# THE TEST. src/pages/creator-chat-unmatched-deal-id.test.tsx, describe "CreatorChatPage — an
# unmatched ?deal= id must not substitute a different deal": (1) an unmatched ?deal= id shows "No
# deals yet", never a substituted deal; (2) no ?deal= param at all still defaults to the first
# deal (the legacy behaviour this fix must not break). A REAL product test file living in
# src/pages/, not a throwaway .proof-os/gates fixture.
#
# FALSIFICATION, MEASURED (not assumed), 2026-09-04, on this tree:
#   · unmodified tree                                   -> 2/2 pass
#   · `if (selectedDealId == null)` changed to `if (true)` (the pre-fix shape: dealRooms[0] wins
#     unconditionally)                                   -> exactly the unmatched-id case fails
#                                                            (the no-param case still passes,
#                                                            because dealRooms[0] happens to be
#                                                            right there too — the failure is
#                                                            specific, not a blanket crash)
#   · reverted back to the guarded form                 -> 2/2 pass again
#
# LAW: exit 0 = proved · exit 1 = broken (real finding) · exit 2 = cannot run.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

F=src/pages/creator-chat.tsx
T=src/pages/creator-chat-unmatched-deal-id.test.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
[ -f "$T" ] || { echo "· $T missing — the regression test is gone — unavailable"; exit 2; }

if ! grep -q "selectedDealId == null" "$F"; then
  echo "· $F no longer guards the dealRooms[0] fallback on selectedDealId == null — the exact"
  echo "  condition F-0629's fix introduced looks to have changed. Source alone cannot say"
  echo "  whether a rewrite is still safe (that is what the test below is for), but the specific"
  echo "  guard this gate was written to pin is gone."
fi

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
if [ ! -f node_modules/.bin/vitest ]; then
  echo "· node_modules/.bin/vitest not found — unavailable"
  echo "NOT CHECKED: everything this gate asserts. There is deliberately no grep-only fallback:"
  echo "             a token check cannot tell 'no id' apart from 'an id that matched nothing',"
  echo "             which is the whole defect."
  exit 2
fi
[ -f vitest.config.ts ] || { echo "· vitest.config.ts missing — unavailable"; exit 2; }

BUDGET="${PROOF_F0629_VITEST_TIMEOUT:-180}"
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
  echo "VERDICT: broken — an unmatched ?deal= id shows a substituted deal instead of the empty"
  echo "         state, or the no-param legacy default regressed (F-0629)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true
echo "  suite green"

echo "VERDICT: aligned (proved) — an explicitly-requested, unmatched deal id was navigated to and"
echo "         the page showed 'No deals yet', never a different creator deal's messages/contract."
echo "         A request with no id at all still defaulted to the first deal, unchanged. Falsified:"
echo "         forcing the dealRooms[0] fallback unconditionally turns exactly the unmatched-id"
echo "         case red, leaving the no-param case green — the test is sensitive to the specific"
echo "         distinction the fix makes, not to an unrelated crash."
echo "NOT CHECKED: whether a REAL stale/expired deal id from a live backend behaves the same as"
echo "             this test's synthetic unmatched id (this is a unit-level render test, not an"
echo "             end-to-end one against the API). Deal rooms fetched after initial load"
echo "             (dealsLoading race conditions beyond the existing full-page guard). Any deal"
echo "             the fix newly makes UNREACHABLE that a real id should have matched."
exit 0
