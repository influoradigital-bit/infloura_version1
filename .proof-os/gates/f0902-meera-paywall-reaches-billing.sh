#!/usr/bin/env bash
# gates/f0902-meera-paywall-reaches-billing.sh — closes F-0902 (H5 paywall-dead-end).
#
# On CREDITS_EXHAUSTED the paywall CTA called onFunctionCall('request_payment') with no payload,
# which parked StageFunding on "Securing your funds…" forever. It now links to
# /brand/settings/billing (SubscriptionService applies the new plan allotment on upgrade).
#
# Runs the test rather than grepping it. Falsified 2026-09-18: restoring CreditPaywall.tsx,
# MeeraChatPanel.tsx and meera-copy.ts from HEAD (the old CTA) -> both tests fail.
#
# Correction recorded 2026-09-18: the done_when said applyEscrowFundedReset "has no caller". It has
# one, ConfirmLaunchExecutor (Meera's confirm_launch, never offered to the model); the normal
# publish path does not call it. The fix is unaffected; the claim was wrong.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

TEST=src/components/feature/meera/__tests__/MeeraChatPanel.credit-paywall.test.tsx
PANEL=src/components/feature/meera/MeeraChatPanel.tsx
for f in "$TEST" "$PANEL" src/components/feature/meera/CreditPaywall.tsx src/data/meera-copy.ts; do
  [ -f "$f" ] || { echo "VERDICT: broken — $f is missing"; exit 1; }
done
# The exact dead-end call site; code, not prose.
if grep -q "onFund={() => onFunctionCall('request_payment')}" "$PANEL"; then
  echo "VERDICT: broken — the paywall CTA calls request_payment again (F-0902 dead end)"; exit 1
fi

command -v npx >/dev/null 2>&1 || { echo "· npx not on PATH — unavailable"; exit 2; }
LOG=$(mktemp 2>/dev/null || echo "${TMPDIR:-/tmp}/f0902-gate.log")
npx vitest run "$TEST" --exclude "**/.lanedv/**" > "$LOG" 2>&1
RC=$?
if [ $RC -ne 0 ]; then
  if grep -qE "Tests +.*[0-9]+ failed" "$LOG"; then
    grep -E "FAIL|Tests " "$LOG" | tail -6
    echo "VERDICT: broken — the F-0902 paywall tests fail"; exit 1
  fi
  tail -5 "$LOG"; echo "· vitest did not reach the tests — unavailable"; exit 2
fi
grep -qE "Tests +.*2 passed" "$LOG" || { tail -5 "$LOG"; echo "· expected 2 passing tests — unavailable, never green"; exit 2; }
echo "VERDICT: proved — F-0902 paywall CTA reaches billing and never opens the funding stage"
exit 0
