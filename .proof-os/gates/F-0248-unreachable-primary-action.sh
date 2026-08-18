#!/usr/bin/env bash
# F-0248-unreachable-primary-action.sh — gate for F-0248, F-0267 and F-0269.
#
# The Contracts page carries a UI-only status vocabulary that a real record only ever enters
# through mapApiContractStatus(), whose output set is exactly
# {draft, pending_signature, signed, expired, disputed, undefined}. The Sign button gated on
# 'pending_review' — a literal that mapper CANNOT emit — so in live mode the button, the sign
# dialog, the signature canvas and the legal notice under it were all unreachable. Nothing in
# the type system objected: both sides of the comparison were legal members of the union.
#
# The FIRST repair moved the gate to `pending_signature && !brandSigned` and was rejected on
# independent review. Contract.builder() defaults to DRAFT (Contract.java:232) and
# advanceIfFullySigned reaches PENDING_SIGNATURES only AFTER a first signature
# (Contract.java:148-154) — so that gate was reachable only when the CREATOR signed first. A
# freshly generated contract, which is precisely when the brand normally signs first, stayed
# unsignable. The originally recorded defect was still live on the common path (F-0267).
#
# The decisive fact, checked in the Java rather than assumed: DRAFT IS signable. No status
# guard exists in ContractService#recordSignature (:526-574), #doRecordSignature (:628-678,
# which checks only prior-signature idempotency and collaboration cancellation), or
# ContractController#sign (:78-113). The pending_signature restriction was frontend-invented
# with no backend basis. The gate now covers draft OR pending_signature for an unsigned brand.
#
# Why leg 4 is written the way it is. The first repair's spec passed while the hole was open,
# because it asserted that a gating literal is a MEMBER of the mapper's output set — and
# 'pending_signature' is a member. Set membership is not reachability. The replacement spec
# walks every status the mapper can emit and requires a reachable Sign control, or an explicit
# status badge saying why not. That is the property; the old test was checking a string.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

FILE=src/components/brand/contracts/contracts-and-deliverables.tsx
[ -f "$FILE" ] || { echo "· $FILE missing — unavailable"; exit 2; }
FILE_CODE=$(code_view "$FILE") || { echo "$(code_why) - unavailable"; exit 2; }

echo "· no live code references the unreachable 'pending_review' status"
# Strip line comments and block-comment bodies: the records deliberately NAME the dead literal
# in explanatory comments, and a comment is not a code path.
stripped=$(sed -e 's://.*::' -e 's:\*.*::' "$FILE_CODE")
if printf '%s' "$stripped" | grep -q "pending_review"; then
  printf '%s' "$stripped" | grep -n "pending_review"
  echo "VERDICT: broken — 'pending_review' is back on a live code path (F-0248); the mapper"
  echo "         cannot emit it, so whatever gates on it is dead"
  exit 1
fi
echo "  clean — the dead literal survives only in comments"

echo "· the Sign control covers a brand that has not signed, in draft OR pending_signature"
gate=$(tr '\n' ' ' < "$FILE_CODE" | grep -oE "\{\(selectedContract\.status === 'draft'[^}]{0,200}" | head -1)
case "$gate" in
  *"pending_signature"*) : ;;
  *) echo "  found: ${gate:-<no draft-inclusive gate at all>}"
     echo "VERDICT: broken — the Sign gate no longer covers both draft and pending_signature"
     echo "         (F-0267); one signing order is locked out"
     exit 1 ;;
esac
case "$gate" in
  *"!selectedContract.brandSigned"*) : ;;
  *) echo "  found: $gate"
     echo "VERDICT: broken — the Sign gate no longer excludes an already-signed brand (F-0248)"
     exit 1 ;;
esac
echo "  clean — gate is (draft || pending_signature) && !brandSigned"

echo "· mapApiContractStatus still cannot emit 'pending_review'"
if sed -n '/export function mapApiContractStatus/,/^}/p' "$FILE_CODE" | grep -q "pending_review"; then
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
  echo "VERDICT: broken — a brand cannot reach the signing action for some status the mapper"
  echo "         can actually emit (F-0248/F-0267)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — a brand can reach the Sign control on every signable status"
echo "NOT CHECKED: that signing PERSISTS — the spec proves the control renders and opens the"
echo "             dialog, not that the submit reaches the server or the contract advances;"
echo "             the signature canvas, whose strokes are drawn and never read while only the"
echo "             typed name is submitted under an IT Act 2000 notice — a separate MEDIUM, and"
echo "             a concurrent session was rewriting that region (F-0253) during this repair;"
echo "             the 'unresolved comments' warning, now regated onto clause comment data that"
echo "             the LIVE endpoints do not return — it is honestly hidden in live mode rather"
echo "             than dead, but it is unproven against real data and warns nobody today; and"
echo "             whether a DRAFT signature is SEMANTICALLY right — it is permitted by the"
echo "             server, which is a different claim from it being the intended lifecycle"
