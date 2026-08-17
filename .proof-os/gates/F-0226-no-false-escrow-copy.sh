#!/usr/bin/env bash
# F-0226-no-false-escrow-copy.sh — gate for F-0226 (false-success-copy).
# Signing a contract reaches CONTRACTED and nothing more: IN_PROGRESS comes only from
# CollaborationLifecycleService.onEscrowFunded, driven by the brand funding a milestone,
# and the creator's Submit Deliverable control is gated on in_progress. So no creator
# contract surface may tell the creator — in a toast, a status label, a badge or a
# static line — that escrow is funded or that deliverables/work can start at signature.
#
# Two legs, because one alone is not enough:
#   1. CONTENT SCAN. False copy is a string, and it can reappear in any string on any of
#      these surfaces. A component test only covers the components it renders.
#   2. THE CARD SUITE, which additionally asserts the removed "Escrow Funded" block stays
#      removed and the badge no longer reads "Active & Funded".
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

DIR=src/components/creator/deal-room
[ -d "$DIR" ] || { echo "· $DIR missing — unavailable"; exit 2; }

echo "· content scan: $DIR for escrow-funded claims"
# Deliberately narrow so it fails on the DEFECT, not on any mention of escrow: each pattern
# is an assertion that money has moved or that work may begin. "Waiting for the brand to fund
# escrow" and "Awaiting Escrow" must NOT match — those are the honest replacements.
#
# Two exclusions, both load-bearing, and both discovered by this gate failing on its own tree:
#   - *.test.tsx, because F-0226's regression tests assert the ABSENCE of these very strings
#     (`queryByText(/escrow funded/i)`), so scanning them makes the gate fail when it passes.
#   - comment lines, because the fix documents itself in prose that quotes the old copy.
# Both are scanned for meaning elsewhere: the test file by the vitest leg below, and comments
# are not user-visible at all, which is the only thing this leg is about.
hits=$(grep -rniE \
  "escrow is funded|escrow funded|secured in escrow|active & funded|active &amp; funded|sign to start deliverables|you can start (on )?deliverables" \
  --include='*.tsx' --include='*.ts' --exclude='*.test.tsx' --exclude='*.test.ts' \
  "$DIR" 2>/dev/null | grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*|\{/\*)')

if [ -n "$hits" ]; then
  echo "$hits"
  echo "VERDICT: broken — a creator contract surface claims escrow is funded or work may start at signature (F-0226 regressed)"
  exit 1
fi
echo "  no escrow-funded claim found"

SUITE=$DIR/creator-contract-card.test.tsx
if [ ! -f "$SUITE" ]; then
  echo "· $SUITE missing — cannot run the component leg; content scan alone is not the whole gate"
  exit 2
fi
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed (--no-install) — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  echo "$out" | tail -30
  echo "VERDICT: broken — the card re-asserts an escrow state it cannot know (F-0226 regressed)"
  exit 1
fi
echo "$out" | tail -5

echo "VERDICT: aligned (proved) — no creator contract surface claims escrow is funded at signature"
echo "NOT CHECKED: whether the REPLACEMENT copy is accurate — this gate proves the false claim is"
echo "             absent, not that what replaced it is true (the 'you'll be notified' promise rests"
echo "             on NotificationListener.on(EscrowFundedEvent), which nothing here exercises);"
echo "             brand-side surfaces; any escrow claim rendered from a string built at runtime or"
echo "             served by the API rather than written in these files; runtime behaviour."
exit 0
