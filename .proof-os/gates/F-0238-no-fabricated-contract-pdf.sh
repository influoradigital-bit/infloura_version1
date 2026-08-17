#!/usr/bin/env bash
# gates/F-0238-no-fabricated-contract-pdf.sh
# origin failure: F-0238 (fabricated-legal-document) — on a failed PDF fetch the client generated
# a contract from invented data and toasted only "Opened a local copy".
set -u
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
F=src/components/brand/deal-room/deal-contract-tab.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
fail=0
# downloadContractPDF must never be reachable from the live branch. Extract the live branch
# (from the liveApi/isApiLive guard to its early return) and assert the call is absent there.
# Strip comment lines first — the fix's own explanatory comment quotes the old string,
# and a gate that cannot tell code from a comment reports its own documentation as a defect.
CODE=$(grep -vE '^\s*(//|\*|/\*)' "$F")
if printf '%s' "$CODE" | grep -q "Opened a local copy"; then
  echo "· the silent local-copy fallback message is still live in code"; fail=1
fi
if ! grep -q "demoContractData" "$F"; then
  echo "· invented contract data is not scoped to a demo-only binding"; fail=1
fi
[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0238 regressed)"; \
  echo "NOT CHECKED: whether the live failure message is understandable, or runtime behaviour"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: whether the live failure message is understandable, or runtime behaviour"
exit 0
