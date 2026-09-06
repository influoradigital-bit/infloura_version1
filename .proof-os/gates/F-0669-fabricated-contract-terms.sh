#!/usr/bin/env bash
# gates/F-0669-fabricated-contract-terms.sh — closes F-0669.
#
# origin: F-0669 (fabricated-legal-terms). Invented contract terms — a usage-rights period, an
# exclusivity clause, a revision cap, a delivery deadline, a brand name, and in one place a PRICE —
# were shown to brands and creators as the terms of their real agreement.
#
# THIS FINDING TOOK THREE ROUNDS, and the reason is the lesson worth keeping:
#   round 1 fixed the creator deal-room PDF.  A review found the brand deal-room PDF unfixed.
#   round 2 fixed that.                        A review found the terms were never PDF-only — the
#                                              same invented strings were RENDERED ON SCREEN, and
#                                              all three shipped specs asserted on the captured PDF
#                                              blob and never the DOM, so they were green while the
#                                              screen lied (logged separately as F-0677).
#   round 3 fixed five DOM surfaces.           A final check found ONE more 'Influora Brand'
#                                              fallback in a sibling file, fixed by the CTO.
# Each round fixed exactly the surfaces someone had named. That is `partial-fix-narrows-defect`,
# a blocked recurring class on this ledger. Hence the repo-wide check below rather than a file list.
#
# WHAT WAS FIXED: the PDF payload at all five downloadContractPDF call sites, and the on-screen
# rendering in contract-panel.tsx, creator-contract-panel.tsx, creator-contract-card.tsx,
# deal-room-dashboard.tsx and contracts-and-deliverables.tsx. Unknown terms now read "Not specified"
# — the same wording contract-generator.ts already used for the PDF, so a reader sees identical
# honest language on screen and in the download. The invented "INR 2,500 per round" charge is gone;
# a revision cap that MATCHES its fixture's own maxRevisions field was deliberately kept, because
# self-consistent demo content is not a fabrication.
#
# DELIBERATELY NOT TOUCHED: demo fixture data that happens to contain the same literals
# (campaigns-list.tsx's createdAt, brand-messages.tsx's mock metadata, the mock proposals in
# brand-chat.tsx / creator-chat.tsx, collaboration-timeline.tsx's fixtures). Mock fixtures are not
# the defect; an invented value presented to a user AS A TERM OF THEIR AGREEMENT is. Removing them
# would break mock mode for no honesty gain.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not found — unavailable"; exit 2; }

# REPO-WIDE, not a file list. Three rounds of this finding were "fixed at the named surface" and a
# fourth surface kept turning up. Comments are excluded (a line explaining why a literal was removed
# must not re-trip the gate); tests are excluded (they assert these strings are ABSENT).
echo "· repo-wide scan for invented contract terms in live component code"
if ! python "$SELF/_f0669_scan.py"; then
  echo "VERDICT: broken — an invented contract term is back in live component code (F-0669)"
  exit 1
fi
echo "  none in live code"

BUDGET="${PROOF_F0669_TIMEOUT:-400}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

# Both halves: the DOM specs (round 3) AND the PDF specs (rounds 1-2). The PDF ones alone were
# green while the screen showed fabrications — that is precisely why both run here.
FILES=$(ls src/components/brand/timeline/panels/__tests__/contract-panel-dom-honest-terms.test.tsx \
           src/components/creator/deal-room/__tests__/creator-contract-panel-dom-honest-terms.test.tsx \
           src/components/creator/deal-room/__tests__/creator-contract-card-dom-honest-terms.test.tsx \
           src/components/brand/deals/deal-room-dashboard-dom-honest-terms.test.tsx \
           src/components/brand/contracts/__tests__/contracts-and-deliverables-dom-honest-terms.test.tsx \
           src/components/brand/deal-room/__tests__/deal-contract-tab-honest-terms.test.tsx \
           src/components/creator/deal-room/__tests__/creator-deal-contract-tab-honest-terms.test.tsx \
           2>/dev/null)
[ -n "$FILES" ] || { echo "· no honest-terms specs found — unavailable"; exit 2; }

for f in $FILES; do
  git ls-files --error-unmatch "$f" >/dev/null 2>&1 || {
    echo "· $f is not git-tracked — this gate would be absent on a fresh clone"
    echo "VERDICT: broken — F-0324 pattern; git add it"; exit 1; }
done
echo "· all honest-terms specs are git-tracked"

# shellcheck disable=SC2086
echo "· vitest: DOM + PDF honest-terms specs"
out=$($TO node_modules/.bin/vitest run $FILES 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if printf '%s' "$out" | grep -q "No test files found"; then echo "  collected nothing — unavailable"; exit 2; fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken — a fabricated term reached a document or a screen again (F-0669)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true

echo "VERDICT: aligned (proved) — no invented usage-rights period, exclusivity clause, revision cap,"
echo "         deadline, brand name or price reaches either a generated contract document or the"
echo "         on-screen contract panels; unknown terms read 'Not specified' in both places."
echo "NOT CHECKED: whether 'Not specified' is the right product wording (a copy decision, not a"
echo "             correctness one); demo fixture data deliberately left intact; and whether some"
echo "             OTHER surface renders contract terms under wording this scan does not match —"
echo "             the repo-wide grep covers the six known literals, not the concept."
exit 0
