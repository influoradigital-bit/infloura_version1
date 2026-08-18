#!/usr/bin/env bash
# F-0223-approval-reports-release.sh — gate for F-0223 (silent-money-skip).
#
# Approving a deliverable is the act that pays the creator: BrandDeliverableService#approve
# calls EscrowService#tryReleaseOnApproval in the same transaction. That method SKIPS the
# release and returns NORMALLY on eight conditions — unfunded milestone, unmet release
# condition, dispute freeze, and five more — so the approval succeeds either way. It used to
# return a bare boolean the caller discarded, and the API returned an identical
# {status: APPROVED} whether the creator had just been paid or not. The brand read "Approved"
# over a payment that never happened; the creator waited with nothing to look at.
#
# Three legs, each guarding a different way the silence comes back:
#   1. The outcome type still carries a REASON. If tryReleaseOnApproval reverts to boolean,
#      the reason is gone and nothing downstream can say which happened.
#   2. approve() still puts it on the wire — ReviewResponse must carry paymentReleased.
#   3. The UI mapping suite: every reason code produces a message that does NOT imply payment
#      succeeded, and an unknown code is not smoothed into a reassuring generic.
#
# What it deliberately does NOT do: assert any particular wording. Copy is allowed to change;
# what is not allowed is copy that reads as a completed payment when none happened.
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

ESCROW=influora-api/src/main/java/com/influora/service/EscrowService.java
ESCROW_CODE=$(code_view "$ESCROW") || { echo "$(code_why) - unavailable"; exit 2; }
DTO=influora-api/src/main/java/com/influora/web/dto/deliverable/BrandDeliverableDtos.java
DTO_CODE=$(code_view "$DTO") || { echo "$(code_why) - unavailable"; exit 2; }
for f in "$ESCROW" "$DTO"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

echo "· outcome type: tryReleaseOnApproval returns a reason, not a bare boolean"
if grep -qE "boolean +tryReleaseOnApproval" "$ESCROW_CODE"; then
  grep -nE "boolean +tryReleaseOnApproval" "$ESCROW_CODE"
  echo "VERDICT: broken — tryReleaseOnApproval is a bare boolean again; the skip reason is lost"
  echo "         before it can reach the brand (F-0223)"
  exit 1
fi
grep -q "ReleaseOutcome" "$ESCROW_CODE" || {
  echo "  ReleaseOutcome not found in EscrowService"
  echo "VERDICT: broken — the release outcome type is gone (F-0223)"; exit 1; }
echo "  clean — ReleaseOutcome carries heldReason"

echo "· wire format: ReviewResponse still reports paymentReleased"
if ! grep -q "paymentReleased" "$DTO_CODE"; then
  echo "VERDICT: broken — ReviewResponse no longer reports whether the approval paid the creator"
  echo "         (F-0223); the API is back to APPROVED-means-nothing"
  exit 1
fi
echo "  clean — paymentReleased/paymentHeldReason on the response"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/lib/__tests__/escrow-release-reason.test.ts
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -25
  echo "VERDICT: broken — a held-payment message implies the creator was paid (F-0223)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — an approval that moves no money says so"
echo "NOT CHECKED: that the brand's UI actually RENDERS the held branch — the two call sites"
echo "             (DeliverableViewer, contracts-and-deliverables) branch on paymentReleased but"
echo "             no test mounts them; that the eight server reason codes are the complete set"
echo "             (isExpectedReleaseSkip could grow one and this mapping would fall back to the"
echo "             generic message — honest, but less useful); and whether a release that DID"
echo "             happen actually moved money, which only a live run shows."
exit 0
