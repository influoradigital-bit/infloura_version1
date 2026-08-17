#!/usr/bin/env bash
# gates/F-0242-brand-can-open-dispute.sh
# origin failure: F-0242 (capability-parity-gap) — brand-disputes.tsx told the brand to open a
# dispute "in the relevant deal room" while no such control existed and no brand-role client
# method for POST /deals/:dealId/disputes had ever been written.
set -u
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
A=src/lib/api.ts
D=src/pages/brand-chat.tsx
[ -f "$A" ] && [ -f "$D" ] || { echo "· source files missing — unavailable"; exit 2; }
fail=0
# 1. A brand-role open method must exist on brandDisputes.
if ! awk '/export const brandDisputes/{f=1} f&&/^};/{exit} f' "$A" | grep -q "open:"; then
  echo "· brandDisputes has no open() method"; fail=1
fi
if ! awk '/export const brandDisputes/{f=1} f&&/^};/{exit} f' "$A" | grep -q "role: 'brand'"; then
  echo "· brandDisputes.open does not send the brand role"; fail=1
fi
# 2. The deal room must call it — and must NOT call the creator variant.
if ! grep -q "brandDisputes.open" "$D"; then
  echo "· the deal room never calls brandDisputes.open"; fail=1
fi
# Strip comment lines: this file's own doc-comment names creatorDisputes.open to explain
# why it is NOT used, and a gate that cannot tell code from a comment fails on its own docs.
DCODE=$(grep -vE '^\s*(//|\*|/\*)' "$D")
if printf '%s' "$DCODE" | grep -q "creatorDisputes.open"; then
  echo "· the deal room calls the CREATOR dispute method — wrong JWT slot"; fail=1
fi
# 3. The stale 'intentionally NOT wired' comment must be gone.
if grep -q "Opening a dispute is intentionally NOT wired here" "$A"; then
  echo "· the stale 'not wired' comment still contradicts the shipped method"; fail=1
fi
[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0242 regressed)"; \
  echo "NOT CHECKED: whether the control is reachable at runtime, whether both 409s render understandably, or backend authorization"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: whether the control is reachable at runtime, whether both 409s render understandably, or backend authorization"
exit 0
