#!/usr/bin/env bash
# F-0887-onboarding-brand-only.sh — gate for T-UPGRADEGATE-0918 (Lane D2).
# Closes F-0887: onboarding offered an Agency workspace that is silently degraded (no
# subscriptions row, invisible to admin billing, no credit reset). Swapnil ruled it removed on
# 2026-09-18 (wiki/decisions/2026-09-18-agency-chooser-removed.md).
#
# PROOF: src/pages/brand-onboarding.f0887-brand-only.test.tsx asserts no workspace-type chooser
# renders and the signup payload sends workspaceType 'BRAND'. Falsified in 18f2ce2's review:
# restoring the chooser turns the no-chooser assertion red.
#
# exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
command -v npx >/dev/null 2>&1 || { echo "· npx not on PATH — unavailable"; exit 2; }
[ -d node_modules ] || { echo "· node_modules missing — unavailable"; exit 2; }

TEST=src/pages/brand-onboarding.f0887-brand-only.test.tsx
[ -f "$TEST" ] || { echo "VERDICT: broken — $TEST is gone"; exit 1; }

# No colour: ANSI codes in the summary line defeat the "Tests" match below.
out=$(NO_COLOR=1 FORCE_COLOR=0 npx vitest run "$TEST" 2>&1)
rc=$?
files=$(printf '%s\n' "$out" | grep -E "Test Files" | tail -1)
tests=$(printf '%s\n' "$out" | grep -E "^\s*Tests " | tail -1)
echo "$files"; echo "$tests"

if [ -z "$tests" ]; then
  printf '%s\n' "$out" | tail -30
  echo "VERDICT: unavailable — no Tests summary from vitest"
  exit 2
fi
if [ "$rc" -ne 0 ] || printf '%s\n' "$tests" | grep -qE "failed|skipped"; then
  printf '%s\n' "$out" | grep -E "FAIL|×" | head -10
  echo "VERDICT: broken — onboarding offers a workspace-type chooser again, or the payload is not BRAND"
  exit 1
fi

echo "VERDICT: proved — onboarding renders no Brand/Agency chooser and signup sends BRAND"
echo
echo "NOT CHECKED:"
echo "  - The 4 existing AGENCY workspaces: converting them is C5, a separate SQL task."
echo "  - The backend still accepts workspaceType=AGENCY from any other client; only this UI stops sending it."
exit 0
