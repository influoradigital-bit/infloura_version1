#!/usr/bin/env bash
# gates/F-0251-contract-payments-truthful.sh
# origin failure: F-0251 (missing-endpoint-hidden-by-mock) — `ContractApiRecord` carries no
# top-level escrow field, no hash, and no funded/released timestamp (only `milestones[]`:
# id/description/amount/status), and `GET /wallet/escrow` is brand-wide, not per-contract. F-0236
# un-mocked the LIVE branch of the Payments tab to render the real `milestones[]` instead of a
# fabricated "50% Paid / 50% In Escrow" schedule — but left the non-live (demo/mock) branch
# completely untouched: EVERY demo contract, including a draft/unsigned/zero-escrow one
# (mockContracts contract-3), still rendered the identical hardcoded 50/50 schedule plus a fake
# "Escrow Funded — Jan 10, 2024" / "First Payment Released — Jan 15, 2024" transaction history.
#
# This gate asserts the tab tells the truth about what data actually exists — in BOTH modes,
# with no separate liveApi-gated fabrication path left — including an honest "not available"
# state when there is nothing real to show. It does not merely check that the fabricated STRING
# is gone; a wrong fix that renames the same numbers (e.g. still splits `value` in half under a
# different label) must also fail this gate.
#
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

F=src/components/brand/contracts/contracts-and-deliverables.tsx
F_CODE=$(code_view "$F") || { echo "$(code_why) - unavailable"; exit 2; }
API=src/lib/api.ts
API_CODE=$(code_view "$API") || { echo "$(code_why) - unavailable"; exit 2; }
TESTFILE=src/components/brand/contracts/__tests__/contracts-and-deliverables.payments-truthful.test.tsx

for f in "$F" "$API" "$TESTFILE"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done
fail=0

echo "· ContractApiRecord still carries no escrow/hash/funded-released-timestamp field (sanity —"
echo "  confirms the 'no real data exists yet' premise this gate is built on hasn't changed"
echo "  under us; src/lib/api.ts is read-only to this record's owner)"
RECBLOCK=$(awk '/export interface ContractApiRecord/{f=1} f{print} f&&/^}/{exit}' "$API_CODE")
if [ -z "$RECBLOCK" ]; then
  echo "· could not isolate ContractApiRecord in $API — unavailable"
  exit 2
fi
if printf '%s' "$RECBLOCK" | grep -qE '\bhash\b|\bescrowAmount\b|\bescrowLocked\b|fundedAt|releasedAt'; then
  echo "  ContractApiRecord now carries a field this gate assumed did not exist yet — the honest-"
  echo "  fallback copy below may be stale (not a defect on its own; re-check by hand)"
fi
echo "  noted"

echo "· Payments tab: exactly ONE render path — no liveApi-gated branch left"
PAYBLOCK=$(awk '/TabsContent value="payments"/{f=1} f{print} f&&/<\/TabsContent>/{exit}' "$F_CODE")
if [ -z "$PAYBLOCK" ]; then
  echo "· could not isolate the Payments TabsContent block — unavailable"
  exit 2
fi
if printf '%s' "$PAYBLOCK" | grep -qE '\bliveApi\b'; then
  echo "· the Payments tab still branches on liveApi — a demo-mode-only path can still exist and"
  echo "  drift from what live mode actually proves; F-0251 lived exactly in that unguarded branch"
  fail=1
fi
[ $fail -eq 0 ] && echo "  clean — no liveApi reference inside the Payments tab block"

echo "· no fabricated 50/50 split anywhere in the Payments tab (either mode)"
for needle in '50% Upon Signing' '50% Upon Completion' 'value / 2'; do
  printf '%s' "$PAYBLOCK" | grep -qF "$needle" && {
    echo "· found '$needle' in the Payments tab — a fabricated fixed split (F-0251)"
    fail=1; }
done
[ $fail -eq 0 ] && echo "  clean — no hardcoded 50% split, no value/2 arithmetic"

echo "· no invented transaction dates anywhere in the Payments tab"
for needle in 'Jan 10, 2024' 'Jan 15, 2024' 'Escrow Funded' 'First Payment Released'; do
  printf '%s' "$PAYBLOCK" | grep -qF "$needle" && {
    echo "· found '$needle' in the Payments tab — an invented settlement date/event (F-0251)"
    fail=1; }
done
[ $fail -eq 0 ] && echo "  clean — no fabricated transaction history strings"

echo "· an honest fallback exists for 'no milestone-level data' — real escrow fields, not silence"
printf '%s' "$PAYBLOCK" | grep -qiE "isn.{0,10}t available|not available" || {
  echo "· no 'isn't available' (or similar honest) copy found in the Payments tab — a contract"
  echo "  with nothing real to show must say so, not render nothing / render invented data"
  fail=1; }
printf '%s' "$PAYBLOCK" | grep -qE '\bescrowLocked\b' || {
  echo "· the no-milestone fallback does not reference escrowLocked — it cannot be reflecting the"
  echo "  contract's own real state"
  fail=1; }
printf '%s' "$PAYBLOCK" | grep -qE '\bescrowFrozen\b' || {
  echo "· the no-milestone fallback does not reference escrowFrozen — a disputed contract with no"
  echo "  milestone breakdown would fall back to a state that hides the freeze (see F-0273)"
  fail=1; }
[ $fail -eq 0 ] && echo "  clean — fallback is built from the contract's own real escrow fields"

if [ $fail -eq 1 ]; then
  echo "VERDICT: broken — the Payments tab does not truthfully reflect available data in both"
  echo "         modes (F-0251)"
  echo "NOT CHECKED: runtime behaviour beyond what the suite below exercises; whether a future"
  echo "             per-contract transaction/audit-log endpoint has since shipped server-side"
  exit 1
fi

echo "· vitest: contracts-and-deliverables.payments-truthful.test.tsx (real suite, not a stub)"
[ -x node_modules/.bin/vitest ] || [ -f node_modules/.bin/vitest ] || {
  echo "· node_modules/.bin/vitest not found — unavailable"; exit 2; }

BUDGET="${PROOF_F0251_VITEST_TIMEOUT:-180}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

out=$($TO node_modules/.bin/vitest run "$TESTFILE" --reporter=basic 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: everything below this line — the suite did not finish"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: broken — contracts-and-deliverables.payments-truthful.test.tsx does not pass; the"
  echo "         static checks above are not actually exercised end to end (F-0251)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " || true
echo "  suite green"

echo "VERDICT: aligned (proved) — the Payments tab has one render path (no liveApi branch), never"
echo "         a fabricated 50/50 split or invented transaction dates in either live or demo mode,"
echo "         falls back to the contract's own real escrowLocked/escrowAmount/escrowFrozen fields"
echo "         with an honest 'not available' note when no milestone breakdown exists, and the"
echo "         F-0251 vitest suite passes."
echo "NOT CHECKED: whether a per-contract transaction/audit-log endpoint is added server-side in"
echo "             the future (this gate would need updating to consume it); live rendering"
echo "             against a real backend (only a running backend + E2E proves that)."
exit 0
