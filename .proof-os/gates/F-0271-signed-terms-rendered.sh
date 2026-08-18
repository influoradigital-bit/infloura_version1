#!/usr/bin/env bash
# F-0271-signed-terms-rendered.sh — gate for F-0271 (signed-terms-not-contract-repair).
#
# F-0237 deleted an invented 5-item clause list from the brand contract "Terms (read-only)"
# panel, leaving nothing where terms should be — because ContractApiRecord (api.ts) carried no
# terms field at all, only milestones/amounts/signatures. F-0271 only closes once F-0283 gives
# the client something real to read (a `terms` field on ContractApiRecord, sourced from the
# server's now-persisted ContractResponse.terms). This gate:
#   1. proves the client type can carry real terms text,
#   2. proves the panel renders that real text when present,
#   3. proves the panel says PLAINLY that no terms are on file when absent — never invents
#      filler (the exact defect class F-0237 was),
#   4. guards against the specific fabricated clause strings F-0237 shipped ever reappearing
#      outside a clearly demo-gated path.
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

API=src/lib/api.ts
API_CODE=$(code_view "$API") || { echo "$(code_why) - unavailable"; exit 2; }
PANEL=src/components/brand/deal-room/deal-contract-tab.tsx
PANEL_CODE=$(code_view "$PANEL") || { echo "$(code_why) - unavailable"; exit 2; }

for f in "$API" "$PANEL"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

echo "· client type: ContractApiRecord can carry real terms text"
record_block=$(awk '/export interface ContractApiRecord/,/^}/' "$API_CODE")
if [ -z "$record_block" ]; then
  echo "VERDICT: broken — ContractApiRecord interface not found (F-0271)"
  exit 1
fi
if ! printf '%s\n' "$record_block" | grep -qE '^\s*terms\??:\s*string'; then
  echo "$record_block"
  echo "VERDICT: broken — ContractApiRecord has no terms field; the client has nowhere to receive"
  echo "         what F-0283 now persists and returns server-side (F-0271)"
  exit 1
fi
echo "  clean — ContractApiRecord.terms exists"

echo "· panel: renders a genuine Terms section, not just the payment-schedule/milestone list"
if ! grep -qE "contractRecord\.terms\b" "$PANEL_CODE"; then
  echo "VERDICT: broken — deal-contract-tab.tsx never reads contractRecord.terms; nothing in the"
  echo "         panel actually surfaces the real terms text (F-0271)"
  exit 1
fi
echo "  clean — panel reads contractRecord.terms"

echo "· panel: absent terms are stated plainly, never papered over with invented content"
# Must have SOME honest "no terms on file" branch keyed off the real record actually being
# empty/absent — not merely a loading/error string (those already existed pre-fix and are not
# what this record is about).
if ! grep -qiE "no terms (are )?on file|terms are not (yet )?(available|on file)|no terms (have been )?(captured|recorded)" "$PANEL_CODE"; then
  echo "VERDICT: broken — no honest 'no terms on file' message found; an absent terms value must"
  echo "         say so plainly, not render nothing and not fabricate content (F-0271)"
  exit 1
fi
echo "  clean — an honest absent-terms message is present"

echo "· regression guard: the F-0237 fabricated clause list has not reappeared outside a demo path"
# The exact fabricated values F-0237 shipped (wiki/errors/BRAND-FRONTEND-UX-AUDIT-0817.md:102):
# "6-month usage rights, 2 revision rounds" framed as if read from the real contract.
if grep -qE "usage rights for 6 months|2 revision rounds|6-month usage rights" "$PANEL_CODE"; then
  # Allowed ONLY inside the pre-existing, clearly-demo-only PDF fallback (handleDownloadPDF's
  # demoContractData), which is gated behind !liveApi and never presented as the signed terms.
  if ! grep -qE "demoContractData|isApiLive|liveApi" "$PANEL_CODE"; then
    echo "VERDICT: broken — F-0237's fabricated clause text is present with no demo guard —"
    echo "         invented terms are being shown as if they were the real signed terms (F-0271"
    echo "         regression)"
    exit 1
  fi
fi
echo "  clean — no un-gated fabricated clause text"

echo "· regression guard: no new hardcoded default clause list has been invented in its place"
# F-0283's scope boundary is explicit: capturing terms is engineering, authoring default terms
# text is a product/legal decision this task must NOT make. A hardcoded array of clause strings
# (e.g. a literal exclusivity/usage-rights/arbitration list) rendered unconditionally would be
# F-0237 again under a different name.
if grep -qE "(Exclusivity|Arbitration|Usage [Rr]ights)['\"]?\s*,\s*['\"]?(Revision|Deliverable)" "$PANEL_CODE"; then
  echo "VERDICT: broken — a hardcoded multi-clause list literal was found in the panel; deciding"
  echo "         what contract terms should say is a product/legal decision this task is not"
  echo "         authorized to make (F-0271/F-0283 scope boundary)"
  exit 1
fi
echo "  clean — no invented default clause list"

command -v npx >/dev/null 2>&1 || { echo "· npx not on PATH — unavailable"; exit 2; }
[ -f package.json ] || { echo "· package.json missing — unavailable"; exit 2; }

BUDGET="${PROOF_F0271_TSC_TIMEOUT:-180}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 15 $BUDGET"; else TO=""; fi

echo "· npx tsc --noEmit (budget ${BUDGET}s) — the new terms field must not break the build"
out=$($TO npx tsc --noEmit 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  tsc exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: typecheck result — did not finish in budget"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "error TS" | head -30
  echo "VERDICT: broken — tsc --noEmit fails; the client-side terms plumbing does not typecheck"
  echo "         (F-0271)"
  exit 1
fi
echo "  clean — tsc --noEmit: 0 errors"

# A static grep alone cannot tell "reads contractRecord.terms and renders it" from "reads
# contractRecord.terms into a variable nobody displays" — the panel used to render the SAME
# unavailable-state block twice (once per section) with byte-identical copy, which every grep
# leg above happily proved "clean" while the actual DOM broke every singular
# findByText/getByText query. A behavioral leg is required, not optional (this batch's house
# rule: a static-only gate greens a "control renders but does nothing" fix).
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/components/brand/deal-room/__tests__/signed-terms-rendered.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

BUDGET="${PROOF_F0271_VITEST_TIMEOUT:-180}"
if command -v timeout >/dev/null 2>&1; then TO2="timeout -k 15 $BUDGET"; else TO2=""; fi

echo "· node_modules/.bin/vitest run $SUITE (budget ${BUDGET}s) — proves the terms text is"
echo "  ACTUALLY rendered when present, and the honest 'no terms on file' copy is ACTUALLY"
echo "  rendered when absent, not merely referenced somewhere in the file"
out=$($TO2 node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: everything below this line — the suite did not finish, so no test result was observed"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: broken — signed-terms-rendered.test.tsx does not pass; the panel's grep-visible"
  echo "         wiring does not actually render real terms / the honest absent-terms message"
  echo "         (F-0271)"
  exit 1
fi
printf '%s\n' "$out" | grep -aE '(Test Files|Tests)[[:space:]]+[0-9]' | tail -2
echo "  suite green — signed-terms-rendered.test.tsx"

echo "VERDICT: aligned (proved) — ContractApiRecord carries real terms text sourced from the"
echo "         server, the panel reads AND RENDERS it when present, states plainly when absent"
echo "         rather than inventing filler (proved behaviorally, not just by static grep), the"
echo "         F-0237 fabricated clause text is not reachable un-gated, no new hardcoded clause"
echo "         list was introduced, tsc --noEmit is clean, and signed-terms-rendered.test.tsx"
echo "         passes"
echo "NOT CHECKED: live rendering in a real browser against a real signed contract; whether any"
echo "             FE surface lets a brand TYPE terms before generating a contract (none exists —"
echo "             out of scope, see PRODUCT DECISION NEEDED); visual/CSS correctness of the new"
echo "             section; whether creator-side surfaces (out of this record's file scope) have"
echo "             an equivalent gap."
exit 0
