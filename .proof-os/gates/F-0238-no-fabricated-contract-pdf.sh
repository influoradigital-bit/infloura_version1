#!/usr/bin/env bash
# gates/F-0238-no-fabricated-contract-pdf.sh
# origin failure: F-0238 (fabricated-legal-document) — on a failed PDF fetch the client generated
# a contract from invented data and toasted only "Opened a local copy".
set -u
# SELF resolved BEFORE the cd below (F-0025/F-0026 convention, see build.node.sh) — once
# we cd into the target project, a relative $0 can no longer locate our own directory.
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
F=src/components/brand/deal-room/deal-contract-tab.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
fail=0
# downloadContractPDF must never be reachable from the live branch. Extract the live branch
# (from the liveApi/isApiLive guard to its early return) and assert the call is absent there.
# Look at CODE, not file bytes (F-0266) — the fix's own explanatory comment, or a
# trailing/JSX comment anywhere in the file, may legitimately quote the old string, and
# a gate that cannot tell code from a comment reports its own documentation as a defect.
# A naive line-start-only filter (`^\s*(//|\*|/\*)`) used to live here; it missed
# trailing same-line comments and JSX `{/* ... */}` comments, both reproduced as false
# negatives — see .proof-os/tasks/T-BRANDOPEN-0817/F-0266.prefix.log. The interpreter
# probing and the tokenizer now live in gates/_code.sh, which every affected gate
# sources, so there is ONE implementation to be wrong or right rather than 48.
. "$SELF/_code.sh" 2>/dev/null || { echo "· gates/_code.sh unreadable — unavailable"; exit 2; }
code_ready || { echo "· $(code_why) — unavailable"; exit 2; }
F_CODE=$(code_view "$F") || { echo "· $(code_why) — unavailable"; exit 2; }
if grep -q "Opened a local copy" "$F_CODE"; then
  echo "· the silent local-copy fallback message is still live in code"; fail=1
fi
if ! grep -q "demoContractData" "$F_CODE"; then
  echo "· invented contract data is not scoped to a demo-only binding"; fail=1
fi
[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0238 regressed)"; \
  echo "NOT CHECKED: whether the live failure message is understandable, or runtime behaviour"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: whether the live failure message is understandable, or runtime behaviour"
exit 0
