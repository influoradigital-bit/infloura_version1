#!/usr/bin/env bash
# gates/F-0237-sign-over-real-terms.sh
# origin failure: F-0237 (signed-terms-not-contract) — a hardcoded clause list rendered under
# "Terms (read-only)" with the Sign button beneath it, while the real contract was never passed in.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }
F=src/components/brand/deal-room/deal-contract-tab.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
F_CODE=$(code_view "$F") || { echo "$(code_why) - unavailable"; exit 2; }
fail=0
if ! grep -q "contractRecord" "$F_CODE"; then
  echo "· panel has no fetched contract record — terms cannot be real"; fail=1
fi
if ! grep -q "contractRecord.milestones" "$F_CODE"; then
  echo "· terms are not rendered from the real contract's milestones"; fail=1
fi
# The Sign control must be gated on the real record being present.
if ! grep -qE "disabled=\{[^}]*!contractRecord" "$F_CODE"; then
  echo "· Sign control is not gated on the real contract record"; fail=1
fi
# The old invented clauses must not sit outside a demo binding.
if grep -qE "Brand retains usage rights for 6 months" "$F_CODE" && ! grep -q "demoContractData\|isApiLive\|liveApi" "$F_CODE"; then
  echo "· invented clause text present with no demo guard"; fail=1
fi
[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0237 regressed)"; \
  echo "NOT CHECKED: whether the rendered milestones match the signed PDF, or runtime behaviour"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: whether the rendered milestones match the signed PDF, or runtime behaviour"
exit 0
