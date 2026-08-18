#!/usr/bin/env bash
# F-0227-no-pool-fund-over-signed-deal.sh — gate for F-0227 (escrow-hold-unbound-repair).
#
# Campaign-level ("pool") escrow funding binds no collaboration: EscrowService#initiateFund sets
# collaborationId ONLY inside the `milestoneId != null` branch. Before any contract exists that is
# harmless and deliberate. After one, it is destructive — the brand is debited, no milestone is
# marked funded, onEscrowFunded never fires, and a signed deal sits at CONTRACTED with the creator
# unable to submit anything.
#
# That is F-0222's exact failure chain, reached from /brand/wallet instead of the deal room.
# F-0222 was closed by ADDING a deal-room control; it did not close this entry point, which is
# why F-0227 exists as its repair record.
#
# Two legs:
#   1. The guard is present on the money path. A grep, because the refusal must sit in
#      initiateFund itself — the wallet page is not the only caller (Meera funds through the same
#      service) and a frontend-only filter protects nobody.
#   2. Both behaviours are tested: refused when a PENDING milestone exists, and STILL ALLOWED when
#      none does. The second matters as much as the first — a guard that blocked all pool funding
#      would "pass" leg 1 while breaking the legitimate pre-contract flow.
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
[ -f "$ESCROW" ] || { echo "· $ESCROW missing — unavailable"; exit 2; }
ESCROW_CODE=$(code_view "$ESCROW") || { echo "$(code_why) - unavailable"; exit 2; }

echo "· guard present on the money path (initiateFund, not the UI)"
if ! grep -q "CAMPAIGN_HAS_UNFUNDED_MILESTONES" "$ESCROW_CODE"; then
  echo "VERDICT: broken — initiateFund no longer refuses a pool fund over a campaign with pending"
  echo "         milestones; the wallet can strand money on a signed deal again (F-0227)"
  exit 1
fi
echo "  clean — CAMPAIGN_HAS_UNFUNDED_MILESTONES is raised in EscrowService"

command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — unavailable"; exit 2; }

echo "· mvn -o test EscrowServiceTest (refusal AND the still-allowed case)"
out=$(cd influora-api && mvn -q -o test -Dtest='EscrowServiceTest' -DfailIfNoSpecifiedTests=false 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "ERROR|Tests run" | tail -15
  echo "VERDICT: broken — the F-0227 guard or the pre-contract pool path regressed"
  exit 1
fi
echo "  suite green"

echo "VERDICT: aligned (proved) — a pool fund cannot strand money on a campaign with signed deals"
echo "NOT CHECKED: the wallet page still LISTS such a campaign in its picker — the brand learns"
echo "             only on click, from the 409's message. Filtering it out needs a new field on the"
echo "             campaign list DTO and was left out of scope. Also unchecked: whether PENDING is"
echo "             the complete set of statuses a pool fund can strand (FUNDED/RELEASED milestones"
echo "             deliberately do not block, so a campaign mid-payout stays fundable), and every"
echo "             claim here is a Mockito test — no pool fund has been attempted against a real"
echo "             database."
exit 0
