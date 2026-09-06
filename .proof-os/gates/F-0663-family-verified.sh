#!/usr/bin/env bash
# gates/F-0663-family-verified.sh — closes F-0663, F-0675, F-0676, F-0677.
#
# This family is not four bugs. It is one failure MODE that produced at least five dead fixes on
# this codebase in a week: a regression test that passes while its finding stays broken, because
# the test pins something other than the defect.
#
#   F-0663 — the gate itself (.proof-os/gates/F-0663-test-pins-subject.py). Checks that every
#            actionable ledger row's `where` resolves to a real file, that no claimed subject is a
#            bare directory once a test is filed under it, and that a test filed under an F-id
#            actually REACHES that row's file by import (or, for Java, by class name in code).
#            Its own falsification is documented in its header: run against an older ledger it
#            catches F-0640 (via CHECK A) and F-0440 (via CHECK C), the two real cases that shipped.
#
#   F-0675 — F-0280's test imported NOTHING and re-declared the Publish button's disabled rule as
#            local constants, asserting on its own copy. A tautology dressed as a regression test.
#            Fixed by EXTRACTING the rule to `isPublishDisabled` in campaign-form.tsx so component
#            and test evaluate the same function — the remedy already used here for
#            buildCounterOfferBody (F-0432). The suite also now asserts the BUTTON still calls it,
#            because otherwise a future inline would leave the other assertions passing against an
#            orphaned helper. Falsified: inlining the condition back turns exactly that test red.
#
#   F-0676 — NOT a broken test, and worth recording as such. F-0275's fix spanned three files; its
#            `where` recorded two. creator-layout-logo-home.test.tsx legitimately cites F-0275 and
#            covers the third facet (the logo routed to /creator/deals while the sidebar's own
#            "Home" item beside it pointed at /creator/dashboard). Both recorded files DO have real
#            tests asserting the landing route. The ledger was incomplete, not the test — `where`
#            corrected rather than a test rewritten to satisfy a gate.
#
#   F-0677 — three honest-terms specs asserted only on the captured PDF blob and never the DOM, so
#            they were green while the very same components rendered fabricated contract terms on
#            screen. Satisfied by round 3 of F-0669: five DOM-asserting specs now exist, all
#            asserting via `screen.`, three using no capturedHtml at all.
#
# WHAT THE GATE FOUND ON ITS FIRST CLEAN RUN, all real: SIX ledger rows whose `where` named a file
# that does not exist — including F-0418 (pointed at a nonexistent DeliverableService.java, which
# had already misdirected a fix agent into correctly reporting the file was missing) and F-0473
# (same shape as F-0402). Wrong `where` paths are the documented root cause of agents testing an
# already-correct file. All six corrected.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v python >/dev/null 2>&1 || { echo "· python not on PATH — unavailable"; exit 2; }
[ -f .proof-os/gates/F-0663-test-pins-subject.py ] || { echo "· the F-0663 gate is missing — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not found — unavailable"; exit 2; }

echo "· F-0663 gate over the live ledger"
if ! python .proof-os/gates/F-0663-test-pins-subject.py > /tmp/f0663.out 2>&1; then
  tail -25 /tmp/f0663.out
  echo "VERDICT: broken — a ledger subject does not resolve, or a test filed under an F-id does not"
  echo "         reach it. Each is a fix or a test aimed at something other than the defect."
  exit 1
fi
grep -E "VERDICT" /tmp/f0663.out | sed 's/^/  /'

echo "· vitest: the rewritten F-0280 suite (F-0675)"
out=$(node_modules/.bin/vitest run src/components/brand/campaigns/__tests__/campaign-form-f0280-simple.test.tsx 2>&1); rc=$?
if printf '%s' "$out" | grep -q "No test files found"; then echo "  collected nothing — unavailable"; exit 2; fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -25
  echo "VERDICT: broken — the Publish-disabled rule regressed, or the button stopped calling the"
  echo "         extracted predicate (which would make the rest of that suite vacuous again)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true

# F-0677: the DOM specs that replaced the PDF-only ones must still assert on the DOM. A spec that
# quietly loses its `screen.` assertions would return this family to the exact state it started in.
echo "· F-0677: DOM-asserting honest-terms specs"
DOM_SPECS=$(find src -name "*dom-honest-terms*.test.tsx" 2>/dev/null | sort)
[ -n "$DOM_SPECS" ] || { echo "  none found — unavailable"; exit 2; }
for f in $DOM_SPECS; do
  n=$(grep -c "screen\." "$f")
  if [ "$n" -lt 1 ]; then
    echo "  $f asserts on no DOM query at all"
    echo "VERDICT: broken — an honest-terms spec stopped reading the screen (F-0677)"
    exit 1
  fi
  printf "  %-72s %s screen assertions\n" "$(basename "$f")" "$n"
done

echo "VERDICT: aligned (proved) — every actionable ledger row's subject resolves to a real file and"
echo "         every test filed under an F-id reaches it; the F-0280 rule is exercised through the"
echo "         production function rather than a copy of it; and the honest-terms specs read the"
echo "         rendered screen, not only the generated document."
echo "NOT CHECKED: whether a test that DOES reach its subject actually exercises the defect — only"
echo "             running it against the old code proves that, and that is falsification, not a"
echo "             static check. The gate's own header lists its further blind spots (the"
echo "             DTO-drop shape, assertion-target analysis, cross-language pairs). Advisory"
echo "             findings on CLOSED rows are reported by the gate and deliberately not failed."
exit 0
