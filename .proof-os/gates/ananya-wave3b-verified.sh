#!/usr/bin/env bash
# gates/ananya-wave3b-verified.sh — closes F-0440, F-0432, F-0640.
#
# This is the CORRECTION round for four findings whose first pass produced green tests and no
# fixes. The fresh-context review of that first pass put it plainly:
#
#     "All six per-finding test files pass. That is the problem — four unfixed findings have green
#      tests because each pins something other than the defect."
#
# ROOT CAUSE, found by the review and now fixed in the ledger itself: TWO OF THE LEDGER ROWS HAD
# WRONG PATHS. F-0440's `where` named src/components/brand/deal-room — a directory containing no
# accept handler at all. F-0640's named src/components/creator/CreatorDealContractTab.tsx, a file
# that does not exist. Agents dutifully went where the ledger pointed, found already-correct code,
# and wrote tests that could never fail. Both `where` fields have been corrected in
# .proof-os/ledger/failures.jsonl with a note recording what they used to say and why it mattered.
#
# WHAT EACH FINDING ACTUALLY NEEDED:
#   F-0440 — real site deal-room-dashboard.tsx (NOT brand-chat.tsx, which was already correct).
#            setShowProposalDialog(false) fired synchronously before the await, so a failed accept
#            discarded the dialog AND the actionError rendered inside it, leaving no retry path.
#            The close now happens inside the try, after a successful await, matching the same
#            file's handleSendCounter/handleRejectProposal.
#   F-0432 — FALSE FINDING at 2 of its 3 sites, and the pushback was accepted on the evidence:
#            neither brand-campaign-detail.tsx nor creator-chat.tsx has any usage-rights input to
#            map, and creator-chat's free-text "changes to terms" box is general-purpose prose —
#            mapping it onto usageRights would silently overwrite the deal's real rights with
#            whatever the creator typed. The review's own words: "Implementing my ticket as written
#            would have shipped a real data-corruption bug." Both call sites now carry a documented
#            note, and the test pins that the fabrication is NOT reintroduced.
#   F-0640 — the render block added in the first pass was DEAD: creator-chat.tsx, the tab's only
#            caller, passed no milestones prop, so every real creator saw "No payment milestones
#            are on file" on the tab they countersign from. Wired at the call site here.
#
# THE FALSIFICATION THAT MATTERS (F-0640, run by priya, not reported by a producer): the review's
# inverse probe had ADDED the missing wiring and the component suite returned identical 4/4 passes
# — proof it could not detect the defect in either state. So a CALL-SITE test was written
# (creator-chat-contract-milestones-wired.test.tsx). Reverting the one-line prop turns that test
# RED with the exact production symptom ("Unable to find /Kickoff reel delivery/") while the
# component suite stays fully GREEN. That asymmetry is the whole point of this round.
#
# The component-level tests were NOT deleted — an unpassed prop rendering an honest empty state is
# a legitimate component contract. They were simply never a gate for the wiring.
#
# STILL OPEN, deliberately not closed here:
#   F-0434 — frontend is honest now (the control is disabled and the key withheld rather than
#            showing a false success), but persistence is BLOCKED ON BACKEND: PortfolioPatchRequest
#            (PortfolioDtos.java:119-131) has no collabs field, so Jackson drops it. The finding's
#            stated symptom — those fields cannot be edited — is still true.
#   F-0432's third site, deal-room-dashboard.tsx, was owned by the F-0440 agent this round and is
#            still bare; it is covered by F-0432's own note, not by a fix.
#
# LAW: exit 0 proved, 1 broken, 2 unavailable. Runs the FULL suite plus tsc plus the live project —
# this wave's failures were repeatedly cross-file, so a per-file green proves nothing here.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not found — unavailable"; exit 2; }

for f in src/components/brand/deals/deal-room-dashboard-accept-rollback.test.tsx \
         src/pages/f0432-counter-usage-rights.test.ts \
         src/pages/creator-chat-contract-milestones-wired.test.tsx; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

# The one-line wiring this whole round turned on. Grepped explicitly because its absence is
# invisible to the component-level suite — exactly how it shipped dead the first time.
if ! grep -q "milestones={liveContract?.milestones}" src/pages/creator-chat.tsx; then
  echo "· creator-chat.tsx no longer passes milestones to CreatorDealContractTab"
  echo "VERDICT: broken — F-0640's render block is dead again; the creator countersigns a schedule"
  echo "         they cannot see (the component tests will NOT catch this — see header)"
  exit 1
fi
echo "· call-site wiring present (creator-chat.tsx passes milestones)"

echo "· tsc --noEmit"
out=$(npx --no-install tsc --noEmit 2>&1); rc=$?
if [ $rc -ne 0 ]; then printf '%s\n' "$out" | head -20; echo "VERDICT: broken — tsc fails"; exit 1; fi
echo "  tsc clean"

BUDGET="${PROOF_ANANYA_WAVE3B_TIMEOUT:-900}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 15 $BUDGET"; else TO=""; fi

echo "· npm test (full suite)"
out=$($TO npm test 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "FAIL|Test Files|Tests |AssertionError" | tail -30
  echo "VERDICT: broken — the full suite fails"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Test Files|Tests " | tail -2 | sed 's/^/  /'

echo "· npm run test:live"
out=$($TO npm run test:live 2>&1); rc=$?
if [ $rc -ne 0 ]; then printf '%s\n' "$out" | tail -25; echo "VERDICT: broken — live project fails"; exit 1; fi
printf '%s\n' "$out" | grep -E "Test Files|Tests " | tail -2 | sed 's/^/  /'

echo "VERDICT: aligned (proved) — F-0440 (a failed accept keeps the dialog and its error, with a"
echo "         retry path), F-0432 (no fabricated usage-rights value is sent from either counter"
echo "         screen, and the wrong fix cannot be reintroduced silently) and F-0640 (the creator"
echo "         deal room passes the contract's real milestones, pinned at the CALL SITE where the"
echo "         defect actually lived) hold on a typechecking tree with both vitest projects green."
echo "NOT CHECKED: F-0434 (frontend honest, persistence blocked on a backend DTO field);"
echo "             F-0432's third call site in deal-room-dashboard.tsx, still bare by scope."
exit 0
