#!/usr/bin/env bash
# F-0256-no-mock-campaign-seed.sh — gate for F-0256 (mock-seed-in-live-selector).
#
# The invite dialog's campaign `<Select>` seeded `inviteCampaigns` with three fabricated
# campaigns (`{ id: 'c1', name: 'Diwali Collection Launch 2024', status: 'ACTIVE' }`, …) as
# INITIAL React `useState`, ungated by `isApiLive()`. In live mode, before the real
# `GET /campaigns` effect resolved, those fake rows sat in the dropdown fully selectable — a
# brand could submit an invite/offer against a `campaignId` that does not exist on the server.
#
# Three legs:
#   1. Structural — the mock seed must be reachable ONLY when `!isApiLive()`. A bare
#      `useState(mockCampaigns)` with no live gate is exactly the F-0256 shape.
#   2. Structural — a loading flag must exist and gate the selector so the brand cannot pick
#      anything before the real list has resolved.
#   3. vitest — the behavioral suite: while `campaigns.list` is pending the selector is
#      disabled and no fabricated name is in the DOM; once it resolves only real campaigns show.
#
# What it deliberately does NOT do: assert the exact wording of the loading state, or that
# `campaigns.list` is called with any particular params — only that the selector cannot hand out
# a fake campaign id before the real one exists.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

SRC=src/components/brand/discover/creator-discovery.tsx
[ -f "$SRC" ] || { echo "· $SRC missing — unavailable"; exit 2; }

echo "· mock campaign seed is gated behind isApiLive() (not used as an unconditional initial state)"
# Bad shape: `React.useState(mockCampaigns)` with nothing conditioning it on live/mock mode.
if grep -qE "useState\(mockCampaigns\)" "$SRC"; then
  grep -nE "useState\(mockCampaigns\)" "$SRC"
  echo "VERDICT: broken — inviteCampaigns still seeds from the fabricated mock list unconditionally;"
  echo "         a brand in live mode can select a campaign that does not exist (F-0256)"
  exit 1
fi
# Good shape must exist somewhere: mockCampaigns only reachable through a liveApi/isApiLive check.
if ! grep -qE "liveApi\s*\?\s*\[\]\s*:\s*mockCampaigns|!liveApi.*mockCampaigns|isApiLive\(\).*mockCampaigns" "$SRC"; then
  echo "  could not find an isApiLive()-gated initial state for inviteCampaigns"
  echo "VERDICT: broken — no evidence the mock campaign seed is gated behind live/mock mode (F-0256)"
  exit 1
fi
echo "  clean — mock seed only reachable in mock mode"

echo "· a loading flag exists and can gate the selector before the real list resolves"
if ! grep -qE "campaignsLoading" "$SRC"; then
  echo "VERDICT: broken — no loading state guards the campaign selector; nothing stops a brand"
  echo "         from picking a campaign before GET /campaigns has responded (F-0256)"
  exit 1
fi
echo "  clean — campaignsLoading present"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/components/brand/discover/__tests__/creator-discovery-invite-campaigns.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken — the invite selector can still hand out a fabricated campaign id before"
  echo "         the live list resolves (F-0256)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — the invite selector never offers a campaign the server doesn't have"
echo "NOT CHECKED: server-side validation of campaignId on submit (defense in depth if this ever"
echo "             regresses); whether GET /campaigns itself can silently omit a real campaign"
echo "             (a backend concern, not this component's)."
exit 0
