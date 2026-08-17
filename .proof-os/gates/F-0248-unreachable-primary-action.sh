#!/usr/bin/env bash
# F-0248-unreachable-primary-action.sh — gate for F-0248 (unreachable-primary-action).
#
# The Contracts page carries a UI-only status vocabulary
# (draft/pending_review/pending_signature/signed/expired/disputed) that a real record only
# ever enters through mapApiContractStatus(). That mapper emits draft, pending_signature,
# signed — it CANNOT emit 'pending_review'. The Sign button gated on
# `status === 'pending_review'`, so in live mode it never rendered: the button, the sign
# dialog, the signature canvas and the legal notice under it were all unreachable. There
# was no way to sign a contract from the Contracts page at all, and nothing in the type
# system objected, because both sides of the comparison are legal members of the union.
#
# The fix gates on `pending_signature && !brandSigned`, where `brandSigned` is adapted
# straight from `rec.brandSignedAt` rather than inferred from the status string — so it
# stays correct no matter which party signed first, which the coarse status cannot express
# (see F-0250). The sibling "Awaiting Creator Signature" card is gated the mirror way.
#
# The load-bearing leg is the third one. Asserting that the Sign button renders is worth
# little on its own; asserting that every status literal the component GATES ON is a value
# the mapper can actually emit is what closes the whole class, permanently, for controls
# nobody has written yet.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

FILE=src/components/brand/contracts/contracts-and-deliverables.tsx
[ -f "$FILE" ] || { echo "· $FILE missing — unavailable"; exit 2; }

echo "· the Sign control is gated on a status the mapper can emit"
if grep -qE "selectedContract\.status === 'pending_review'" "$FILE"; then
  grep -nE "selectedContract\.status === 'pending_review'" "$FILE"
  echo "VERDICT: broken — a control is gated on 'pending_review' again (F-0248), a literal"
  echo "         mapApiContractStatus can never emit; the control is dead in live mode"
  exit 1
fi
if ! grep -qE "selectedContract\.status === 'pending_signature' && !selectedContract\.brandSigned" "$FILE"; then
  echo "VERDICT: broken — the Sign button is no longer gated on pending_signature with an"
  echo "         unsigned brand (F-0248); it is either unreachable or shown after signing"
  exit 1
fi
echo "  clean — Sign gated on pending_signature && !brandSigned"

echo "· mapApiContractStatus still cannot emit 'pending_review'"
if sed -n '/export function mapApiContractStatus/,/^}/p' "$FILE" | grep -q "pending_review"; then
  echo "VERDICT: broken — the mapper now emits 'pending_review' (F-0248); the reachability"
  echo "         invariant this gate rests on has moved and the spec must be re-derived"
  exit 1
fi
echo "  clean — mapper output set excludes pending_review"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/components/brand/contracts/__tests__/contracts-sign-reachability.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -25
  echo "VERDICT: broken — a primary action is gated on an unreachable status (F-0248)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — the Sign control is reachable and its gate is a live status"
echo "NOT CHECKED: that signing actually persists — the spec proves the button RENDERS and"
echo "             opens the dialog, not that the submit reaches the server or that the"
echo "             contract advances; the signature canvas remains decorative (strokes are"
echo "             drawn but never read; only the typed name is submitted, under an IT Act"
echo "             2000 notice) — a known MEDIUM this gate does not address; and three"
echo "             'pending_review' references survive OUTSIDE the gating expressions —"
echo "             the mock seed, the status-config label map, and a filter dropdown option"
echo "             that can therefore never match a live record"
