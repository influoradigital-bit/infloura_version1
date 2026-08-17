#!/usr/bin/env bash
# F-0290-application-has-a-timeline.sh — gate for F-0290 (opening-event-never-recorded).
#
# Applying to a campaign created a Collaboration and NOTHING else — zero DealMessage rows. Both
# parties then opened a deal room with an empty thread: the creator had no record of what they
# asked for or when, and the brand saw a deal appear with no request behind it. When the brand
# later accepted, DealService#doAccept appended "Brand accepted the proposal" — a line naming a
# proposal card that had never existed.
#
# This is the user-reported bug "the initial request and its complete history (including when the
# brand accepts it) must be clearly visible".
#
# Two legs:
#   1. apply() still writes to the timeline at all. A grep, because the write is best-effort by
#      design (a timeline failure must not roll back a successful application) — so it cannot be
#      caught by an exception test, and deleting the call would break nothing loudly.
#   2. The behaviour suite: the event row is written, the creator's note is attributed to the
#      CREATOR rather than folded into the system line, no note means no fabricated message, and
#      a timeline outage still lets the application succeed.
#
# SCOPE. This covers the APPLICATION direction only. Brand invites (CreatorDiscoveryService#invite)
# have the same empty-room defect and are deliberately NOT fixed here: creator-chat.tsx gates its
# bare-invite Accept card on `events.length === 0`, so writing an opening message to an INVITED
# room would REMOVE the creator's only way to accept. That pair has to land together, and the
# frontend was mid-edit by another session. Tracked separately.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

SVC=influora-api/src/main/java/com/influora/service/CreatorCampaignService.java
[ -f "$SVC" ] || { echo "· $SVC missing — unavailable"; exit 2; }

echo "· apply() still records the application on the timeline"
if ! grep -q "recordApplicationOnTimeline" "$SVC"; then
  echo "VERDICT: broken — apply() no longer writes a deal-room timeline, so an application is"
  echo "         invisible to both parties again (F-0290)"
  exit 1
fi
# Both paths: a fresh insert AND a revived (F-0225) row. A re-application is still an application.
calls=$(grep -c "recordApplicationOnTimeline(" "$SVC")
if [ "$calls" -lt 3 ]; then
  echo "  found $calls reference(s) — expected the definition plus BOTH call sites"
  echo "VERDICT: broken — one of apply()'s two paths (fresh insert / revived row) no longer"
  echo "         records its timeline (F-0290)"
  exit 1
fi
echo "  clean — definition plus both call sites"

command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — unavailable"; exit 2; }

echo "· mvn -o test CreatorCampaignServiceTest"
out=$(cd influora-api && mvn -q -o test -Dtest='CreatorCampaignServiceTest' -DfailIfNoSpecifiedTests=false 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "ERROR|Tests run" | tail -15
  echo "VERDICT: broken — the application timeline regressed (F-0290)"
  exit 1
fi
echo "  suite green"

echo "VERDICT: aligned (proved) — an application leaves a visible record for both parties"
echo "NOT CHECKED: that either UI RENDERS these rows — the creator and brand deal rooms already"
echo "             render system and text messages, but no test mounts them against these; the"
echo "             INVITE direction, which still opens an empty room (see SCOPE above); and"
echo "             whether doAccept's 'accepted the proposal' wording now reads correctly against"
echo "             an application that never had a proposal card — it is still that literal string."
exit 0
