#!/usr/bin/env bash
# gates/F-0239-no-fake-money-progress.sh
# origin failure: F-0239 (fake-money-action) — a non-dismissable modal stepped through
# "Locking escrow funds" on setTimeout, in live mode, with no server call.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }
F=src/components/brand/deals/deal-room-dashboard.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
F_CODE=$(code_view "$F") || { echo "$(code_why) - unavailable"; exit 2; }
fail=0
# The animation must be demo-only by name, and must not be called from the live branch.
if grep -q "runContractAnimation" "$F_CODE"; then
  echo "· the animation is still named as a general (non-demo) action"; fail=1
fi
if ! grep -q "runDemoContractAnimation" "$F_CODE"; then
  echo "· no demo-scoped animation binding found"; fail=1
fi
# The escrow-claiming dialog must be dismissable.
if grep -qE "onOpenChange=\{\(\) => \{\}\}" "$F_CODE"; then
  echo "· a dialog is still non-dismissable (onOpenChange is a no-op)"; fail=1
fi
if grep -q "onInteractOutside={(e) => e.preventDefault()}" "$F_CODE"; then
  echo "· a dialog still blocks outside-interaction dismissal"; fail=1
fi
[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0239 regressed)"; \
  echo "NOT CHECKED: whether the live branch's success message matches what the server actually did, or runtime behaviour"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: whether the live branch's success message matches what the server actually did, or runtime behaviour"
exit 0
