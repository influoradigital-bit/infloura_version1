#!/usr/bin/env bash
# F-0250-F-0268-pending-signature-deadlock.sh — gate for F-0250 and F-0268.
#
# The backend reaches Contract.status = PENDING_SIGNATURES after ONE signature by EITHER
# party, unordered (Contract.java:148-154 advanceIfFullySigned; the creator-authenticated
# path ContractService.java:576-594). mapDealApiContractStatus is handed only that bare
# enum plus escrowFunded — never brandSignedAt/creatorSignedAt — so it structurally CANNOT
# know which party signed. Every version of this bug is the same mistake: guessing anyway.
#
#   'brand_signed' (original)  → blocked the BRAND on the creator-first path.
#   'generated'    (F-0268)    → blocked the CREATOR on the brand-first path, the common
#                                one, and told them "brandName hasn't signed this contract
#                                yet" — a false statement, not merely a hidden control.
#
# Neither was a fix; each moved the deadlock to the other party. The resolution is a fifth
# union member, 'pending_signature', that asserts nothing about WHO signed, and which both
# parties' Sign gates opt into. The mapper stops guessing and the UI stops needing it to.
#
# Why the earlier spec did not catch F-0268: it asserted the mapper's RETURN VALUE. A
# return-value assertion passes happily while both parties are locked out, because the
# string is correct and the consequence is elsewhere. So the load-bearing leg here is the
# third one — the component specs that assert both Sign controls are actually REACHABLE.
# A mapper test is a pin on a string; only the component test is a gate on the deadlock.
#
# Three legs:
#   1. The mapper does not name a party for unfunded PENDING_SIGNATURES.
#   2. Both parties' Sign gates still accept the ambiguous member.
#   3. The specs: both controls render, and no copy names a signer, in that state.
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

MAP=src/lib/creator-contract-mappers.ts
MAP_CODE=$(code_view "$MAP") || { echo "$(code_why) - unavailable"; exit 2; }
BRAND=src/components/brand/deal-room/deal-contract-tab.tsx
BRAND_CODE=$(code_view "$BRAND") || { echo "$(code_why) - unavailable"; exit 2; }
CRTAB=src/components/creator/deal-room/creator-deal-contract-tab.tsx
CRTAB_CODE=$(code_view "$CRTAB") || { echo "$(code_why) - unavailable"; exit 2; }
CRPANEL=src/components/creator/deal-room/creator-contract-panel.tsx
CRPANEL_CODE=$(code_view "$CRPANEL") || { echo "$(code_why) - unavailable"; exit 2; }
for f in "$MAP" "$BRAND" "$CRTAB" "$CRPANEL"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

echo "· the coarse mapper does not name a signing party"
line=$(sed -n "/case 'PENDING_SIGNATURES':/,/case /p" "$MAP_CODE" | grep -E "return .*escrowFunded|return '" | head -1)
case "$line" in
  *brand_signed*|*creator_signed*)
    echo "  $line"
    echo "VERDICT: broken — the coarse mapper names a signing party for PENDING_SIGNATURES"
    echo "         (F-0250); it cannot know which party signed, so one of them is locked out"
    exit 1 ;;
esac
if ! printf '%s' "$line" | grep -q "pending_signature"; then
  echo "  $line"
  echo "VERDICT: broken — the ambiguous 'pending_signature' member is no longer returned"
  echo "         (F-0268); the deadlock has moved back to one party or the other"
  exit 1
fi
echo "  clean — returns the party-agnostic member"

echo "· both parties' Sign controls accept the ambiguous state"
fail=0
grep -qE "canBrandSign =.*pending_signature" "$BRAND_CODE"   || { echo "  brand canBrandSign does not accept it"; fail=1; }
grep -qE "canSign =.*pending_signature" "$CRTAB_CODE"        || { echo "  creator canSign does not accept it"; fail=1; }
grep -qE "shouldShowSignButton =.*pending_signature" "$CRPANEL_CODE" || { echo "  creator panel Sign does not accept it"; fail=1; }
if [ $fail -eq 1 ]; then
  echo "VERDICT: broken — a party's Sign control is hidden while signatures are pending"
  echo "         (F-0250/F-0268); that party cannot proceed"
  exit 1
fi
echo "  clean — brand and creator Sign controls both live"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
S1=src/lib/__tests__/creator-contract-mappers.test.ts
S2=src/components/creator/deal-room/__tests__/pending-signature-deadlock.test.tsx
S3=src/components/brand/deal-room/__tests__/pending-signature-deadlock.test.tsx
for s in "$S1" "$S2" "$S3"; do
  [ -f "$s" ] || { echo "· $s missing — unavailable"; exit 2; }
done

echo "· vitest run (mapper + both deadlock specs)"
out=$(node_modules/.bin/vitest run "$S1" "$S2" "$S3" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -25
  echo "VERDICT: broken — a party is locked out of signing while signatures are pending"
  echo "         (F-0250/F-0268)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — neither party is locked out while signatures are pending"
echo "NOT CHECKED: that the SERVER accepts a signature from whichever party clicks first —"
echo "             the client now offers both controls, but nothing here exercises the two"
echo "             endpoints or a real race between them; the optimistic post-sign status in"
echo "             DealContractTab.handleSign / CreatorDealContractTab.handleSign, which"
echo "             still sets the known-party value rather than re-deriving from the server"
echo "             and self-corrects only on the next fetch; and the two brand-timeline"
echo "             consumers (timeline/panels/contract-panel.tsx, event-cards/contract-card.tsx)"
echo "             which read contractStatus from a separate deal-message path and were"
echo "             verified not to import either mapper — that verification is a grep, not a test"
