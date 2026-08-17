#!/usr/bin/env bash
# gates/F-0237-sign-over-real-terms.sh
# origin failure: F-0237 (signed-terms-not-contract) — a hardcoded clause list rendered under
# "Terms (read-only)" with the Sign button beneath it, while the real contract was never passed in.
set -u
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
F=src/components/brand/deal-room/deal-contract-tab.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
fail=0
if ! grep -q "contractRecord" "$F"; then
  echo "· panel has no fetched contract record — terms cannot be real"; fail=1
fi
if ! grep -q "contractRecord.milestones" "$F"; then
  echo "· terms are not rendered from the real contract's milestones"; fail=1
fi
# The Sign control must be gated on the real record being present.
if ! grep -qE "disabled=\{[^}]*!contractRecord" "$F"; then
  echo "· Sign control is not gated on the real contract record"; fail=1
fi
# The old invented clauses must not sit outside a demo binding.
if grep -qE "Brand retains usage rights for 6 months" "$F" && ! grep -q "demoContractData\|isApiLive\|liveApi" "$F"; then
  echo "· invented clause text present with no demo guard"; fail=1
fi
[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0237 regressed)"; \
  echo "NOT CHECKED: whether the rendered milestones match the signed PDF, or runtime behaviour"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: whether the rendered milestones match the signed PDF, or runtime behaviour"
exit 0
