#!/usr/bin/env bash
# F-0291-invite-has-a-timeline.sh — gate for F-0291 (invite-opens-empty-room).
#
# `CreatorDiscoveryService#invite` created a Collaboration and no DealMessage rows, so an invited
# creator opened a deal room with no terms, no note, and no record that a brand had reached out.
# The brand's message went to Collaboration.notes and rendered nowhere.
#
# THE WHOLE POINT OF THIS GATE IS THAT THE TWO HALVES CANNOT SEPARATE.
# creator-chat.tsx's bare-invite Accept card was gated on `events.length === 0`, which was correct
# only by accident — a bare invite produced zero messages because invite() persisted none. Landing
# the backend half ALONE removes the creator's only way to accept an invitation: strictly worse
# than the empty room it fixes. So this gate fails if either half regresses, in either direction.
#
# Three legs:
#   1. invite() still writes a timeline, on BOTH the fresh and revived-row paths.
#   2. creator-chat.tsx does NOT gate the invite card on an empty room. A literal grep for the old
#      condition, because reintroducing it is the specific regression that silently breaks Accept.
#   3. Both suites: the backend timeline rows, and the frontend pair (card SURVIVES ordinary
#      messages, card STANDS DOWN when a real proposal card exists).
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

SVC=influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java
ROOM=src/pages/creator-chat.tsx
for f in "$SVC" "$ROOM"; do [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }; done

echo "· backend: invite() records a timeline on both paths"
calls=$(grep -c "recordInviteOnTimeline(" "$SVC")
if [ "$calls" -lt 3 ]; then
  echo "  found $calls reference(s) — expected the definition plus BOTH call sites"
  echo "VERDICT: broken — an invitation no longer leaves a record, so the creator's room is empty again (F-0291)"
  exit 1
fi
echo "  clean — definition plus both call sites"

echo "· frontend: the invite card is NOT gated on an empty room"
# Scoped to showBareInviteResponse's own assignment. `events.length === 0` legitimately survives
# elsewhere in this file — the "No messages yet" empty state is exactly that question, and a
# whole-file grep flags it as a false positive (it did, on the first run of this gate).
card_gate=$(awk '/const showBareInviteResponse =/,/;/' "$ROOM")
if printf '%s' "$card_gate" | grep -q "events.length === 0"; then
  printf '%s
' "$card_gate"
  echo "VERDICT: broken — the bare-invite card is gated on an empty room again. Now that invite()"
  echo "         writes rows, that gate is never true, and an invited creator has NO way to accept."
  echo "         Key it off the absence of a proposal card instead (F-0291)."
  exit 1
fi
echo "  clean — keyed off the absence of a proposal card"

command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }

echo "· mvn -o test CreatorDiscoveryServiceTest"
out=$(cd influora-api && mvn -q -o test -Dtest='CreatorDiscoveryServiceTest' -DfailIfNoSpecifiedTests=false 2>&1) || {
  printf '%s\n' "$out" | grep -E "ERROR|Tests run" | tail -12
  echo "VERDICT: broken — the invite timeline regressed (F-0291)"; exit 1; }
echo "  backend green"

echo "· vitest run src/pages/creator-chat-bare-invite.test.tsx"
fout=$(node_modules/.bin/vitest run src/pages/creator-chat-bare-invite.test.tsx 2>&1) || {
  printf '%s\n' "$fout" | tail -20
  echo "VERDICT: broken — the creator can no longer accept an invitation (F-0291)"; exit 1; }
printf '%s\n' "$fout" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — an invitation is visible AND still acceptable"
echo "NOT CHECKED: that the deal room RENDERS these rows for the brand side; whether the invite's"
echo "             terms (there are none on a bare invite — no amount, no deliverables) should"
echo "             instead be a priced proposal card, which is a product question, not a bug; and"
echo "             the boundary against ApplicationHistoryService, which owns the structured AUDIT"
echo "             timeline while DealMessage owns the conversation — nothing mechanically stops a"
echo "             future writer duplicating one event into both."
exit 0
