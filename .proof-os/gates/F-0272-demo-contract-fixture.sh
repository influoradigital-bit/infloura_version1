#!/usr/bin/env bash
# gates/F-0272-demo-contract-fixture.sh
# origin failure: F-0272 (demo-sign-flow-dead) — the F-0237 fix gated DealContractTab's Sign
# control on a real fetched contract, but api.contracts.get resolved `null` in mock mode. So every
# demo/offline walkthrough rendered "Contract terms are not available yet" with Sign permanently
# disabled: a fix for a live-mode lie that silently broke the sales demo.
# Found by priya (fresh-context) reviewing the F-0237 fix.
# LAW: exit 1 = real finding · 2 = cannot run · 0 = proved.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory: ${1:-.} — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }
A=src/lib/api.ts
[ -f "$A" ] || { echo "· $A missing — unavailable"; exit 2; }
A_CODE=$(code_view "$A") || { echo "$(code_why) - unavailable"; exit 2; }
fail=0

# Isolate contracts.get and assert its NON-live branch resolves a record, not null.
GETBODY=$(awk '/^  get: \(role: Role, id: string\) =>/{f=1} f{print} f&&/^    : mockOr|^    \}\),/{c++; if(c>=1 && /\}\),/) exit}' "$A_CODE")
if [ -z "$GETBODY" ]; then
  # Fall back to a bounded window around the endpoint so a reformat does not read as a defect.
  GETBODY=$(grep -n "GET /contracts/:id" -A 22 "$A_CODE" | sed 's/^[0-9]*[-:]//')
fi
if [ -z "$GETBODY" ]; then
  echo "· could not locate contracts.get — unavailable"; exit 2
fi

if printf '%s' "$GETBODY" | grep -qE "mockOr<ContractApiRecord \| null>\(null\)"; then
  echo "· contracts.get still resolves null in mock mode — the demo Sign flow is dead again"
  fail=1
fi
if ! printf '%s' "$GETBODY" | grep -q "milestones"; then
  echo "· the mock branch returns no milestones — DealContractTab renders its unavailable state"
  fail=1
fi

# The live branch must be untouched: this fixture must never leak into live mode.
printf '%s' "$GETBODY" | grep -q "http.request<ContractApiRecord>('GET'" || {
  echo "· contracts.get no longer makes a real request in live mode"; fail=1; }

[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0272 regressed)"; \
  echo "NOT CHECKED: whether the fixture's values are plausible, whether Sign actually enables at runtime, or that isApiLive() reports what the deployed build sets"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: whether the fixture's values are plausible, whether Sign actually enables at runtime, or that isApiLive() reports what the deployed build sets"
exit 0
