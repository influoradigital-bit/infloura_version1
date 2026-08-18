#!/usr/bin/env bash
# F-0257-invite-creator-handoff.sh — gate for F-0257 (dropped-handoff-context).
#
# The invite dialog's "Create New Campaign" option told the brand: "You will be redirected to
# create a new campaign with {creator} pre-selected." `handleInvite` navigates to
# `/brand/campaigns/new?creator=<id>`, but the destination
# (src/pages/brand-new-campaign.tsx, owned by a different producer, OUT OF SCOPE for this
# component) never reads any creator id off that URL. The promise is false; the creator is
# silently dropped.
#
# This gate encodes the actual scope boundary: either the promise is true (the destination
# consumes the creator id) or the promise must not be made. It does NOT require a specific fix —
# it fails if and only if the UI claims something the destination cannot deliver.
#
# Two legs:
#   1. Structural cross-check — if creator-discovery.tsx still promises "pre-select(ed)", the
#      destination file MUST read a creator id from the URL (useSearchParams / searchParams.get
#      ('creator')). If neither is true, that is exactly F-0257.
#   2. vitest — the behavioral suite: the explainer copy makes no pre-selection claim, and the
#      "Create Campaign" confirm still navigates (the flow itself isn't broken, only the promise).
#
# What it deliberately does NOT do: touch or require changes to brand-new-campaign.tsx — a
# producer editing that file concurrently can land a real pre-select and this gate keeps passing
# either way, as long as the promise and the delivery agree.
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

SRC=src/components/brand/discover/creator-discovery.tsx
DEST=src/pages/brand-new-campaign.tsx
[ -f "$SRC" ] || { echo "· $SRC missing — unavailable"; exit 2; }
SRC_CODE=$(code_view "$SRC") || { echo "$(code_why) - unavailable"; exit 2; }
[ -f "$DEST" ] || { echo "· $DEST missing — unavailable"; exit 2; }
DEST_CODE=$(code_view "$DEST") || { echo "$(code_why) - unavailable"; exit 2; }

echo "· invite→create-campaign promise matches what the destination actually consumes"
if grep -qiE "pre-select" "$SRC_CODE"; then
  if ! grep -qE "searchParams\.get\(['\"]creator['\"]\)|useSearchParams" "$DEST_CODE"; then
    echo "  $SRC still promises pre-selection; $DEST reads no creator id from the URL"
    echo "VERDICT: broken — the invite dialog promises the creator will be pre-selected on"
    echo "         /brand/campaigns/new, but the destination never reads a creator id off the"
    echo "         navigation, so the creator is silently dropped (F-0257)"
    exit 1
  fi
  echo "  clean — promise matches: destination reads a creator id from the URL"
else
  echo "  clean — no pre-selection promise is made; nothing to drop"
fi

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/components/brand/discover/__tests__/creator-discovery-invite-handoff.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken — the invite dialog still makes a promise about the destination it cannot"
  echo "         keep (F-0257)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — the invite dialog no longer promises a handoff the destination doesn't consume"
echo "NOT CHECKED: whether brand-new-campaign.tsx SHOULD read the creator id and pre-select it —"
echo "             that is a real gap, tracked as NOT FIXED, and belongs to whoever owns that file."
exit 0
