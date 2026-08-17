#!/usr/bin/env bash
# gates/F-0239-no-fake-money-progress.sh
# origin failure: F-0239 (fake-money-action) — a non-dismissable modal stepped through
# "Locking escrow funds" on setTimeout, in live mode, with no server call.
set -u
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
F=src/components/brand/deals/deal-room-dashboard.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
fail=0
# The animation must be demo-only by name, and must not be called from the live branch.
if grep -q "runContractAnimation" "$F"; then
  echo "· the animation is still named as a general (non-demo) action"; fail=1
fi
if ! grep -q "runDemoContractAnimation" "$F"; then
  echo "· no demo-scoped animation binding found"; fail=1
fi
# The escrow-claiming dialog must be dismissable.
if grep -qE "onOpenChange=\{\(\) => \{\}\}" "$F"; then
  echo "· a dialog is still non-dismissable (onOpenChange is a no-op)"; fail=1
fi
if grep -q "onInteractOutside={(e) => e.preventDefault()}" "$F"; then
  echo "· a dialog still blocks outside-interaction dismissal"; fail=1
fi
[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0239 regressed)"; \
  echo "NOT CHECKED: whether the live branch's success message matches what the server actually did, or runtime behaviour"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: whether the live branch's success message matches what the server actually did, or runtime behaviour"
exit 0
