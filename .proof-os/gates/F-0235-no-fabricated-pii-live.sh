#!/usr/bin/env bash
# gates/F-0235-no-fabricated-pii-live.sh
# origin failure: F-0235 (fabricated-pii-live) — a hardcoded person's name, phone and street
# address sat in useState with no isApiLive() guard and was POSTed to the live shipment endpoint.
# LAW: exit 1 = real finding. exit 2 = cannot run. exit 0 = proved.
set -u
cd "${1:-.}" 2>/dev/null || { echo "· not a directory: ${1:-.} — unavailable"; exit 2; }
F=src/pages/brand-chat.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
fail=0

# 1. The literal must not sit in live-reachable state. It may exist ONLY as a module-level
#    constant whose name marks it mock-only.
if grep -nE "useState.*(Sea View|Carter Road|9876543210)" "$F" >/dev/null 2>&1; then
  echo "· fabricated address is back in component state (live-reachable):"
  grep -nE "useState.*(Sea View|Carter Road|9876543210)" "$F" | head -5
  fail=1
fi
# 2. If the literal exists at all, it must be under a MOCK_-prefixed binding.
if grep -nE "(Sea View|Carter Road)" "$F" >/dev/null 2>&1; then
  if ! grep -nE "MOCK_SHIPPING_ADDRESS" "$F" >/dev/null 2>&1; then
    echo "· address literal present but no MOCK_-prefixed binding guards it"
    fail=1
  fi
fi
# 3. A live shipment fetch must exist — the real source the address now comes from.
if ! grep -nE "api\.shipments\.get|fetchLiveShipment" "$F" >/dev/null 2>&1; then
  echo "· no live shipment fetch found — the address has no real data source"
  fail=1
fi

[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0235 regressed)"; \
  echo "NOT CHECKED: whether the fetched address is the RIGHT one, runtime behaviour, or PII in files other than $F"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: whether the fetched address is the RIGHT one, runtime behaviour, or PII in files other than $F"
exit 0
