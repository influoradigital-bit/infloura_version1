#!/usr/bin/env bash
# gates/F-0236-no-mock-money-in-live.sh
# origin failure: F-0236 (mock-in-live-money) — the Contracts Payments tab rendered
# "50% Upon Signing / Paid" and a Jan-2024 transaction history with no isApiLive() guard.
set -u
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
F=src/components/brand/contracts/contracts-and-deliverables.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
fail=0
# The fixture literals may exist, but ONLY inside a live/mock branch. Require the payments
# TabsContent to open with a liveApi ternary.
if ! awk '/TabsContent value="payments"/{f=1} f&&/liveApi \?/{print;exit}' "$F" | grep -q "liveApi"; then
  echo "· payments tab is not gated by a liveApi branch"
  fail=1
fi
# The escrow badge must be derived, not a constant false.
if ! grep -q "deriveEscrowFromMilestones" "$F"; then
  echo "· escrowLocked is not derived from real milestones"
  fail=1
fi
[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0236 regressed)"; \
  echo "NOT CHECKED: whether the live branch shows the RIGHT amounts, or mock leakage in other files"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: whether the live branch shows the RIGHT amounts, or mock leakage in other files"
exit 0
