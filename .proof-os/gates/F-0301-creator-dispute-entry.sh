#!/usr/bin/env bash
# F-0301-creator-dispute-entry.sh — gate for F-0301 (missing-creator-dispute-entry).
#
# F-0242 gave the BRAND a real dispute-open control in the deal room (brand-chat.tsx): a
# DropdownMenuItem gated on selectedDeal.escrowFunded, wired to api.brandDisputes.open. The
# creator side's equivalent overflow trigger (creator-chat.tsx, F-0289) was left permanently
# `disabled` with a tooltip claiming "There is no creator-side deal-room action behind it
# today" — but the backend allows either party to open a dispute (DealController.java:130,
# role: 'creator'), and api.ts already exports creatorDisputes.open for exactly this. F-0301 is
# the mirror image of F-0242: the creator, not the brand, is the one locked out.
#
# This gate asserts:
#   1. api.ts genuinely exports a client call the creator side can use (creatorDisputes.open,
#      POST /deals/:dealId/disputes, role: 'creator') — so "no client exists" is not silently
#      assumed on the strength of one api layer (this repo has TWO: api.ts and meera-api.ts).
#   2. creator-chat.tsx no longer hard-disables the "Deal options" trigger.
#   3. a real, reachable handler function calls creatorDisputes.open in its body — not just a
#      menu item that renders with an empty or missing onClick.
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
MEERA_API=src/lib/meera-api.ts
MEERA_API_CODE=$(code_view "$MEERA_API") || { echo "$(code_why) - unavailable"; exit 2; }
PAGE=src/pages/creator-chat.tsx
PAGE_CODE=$(code_view "$PAGE") || { echo "$(code_why) - unavailable"; exit 2; }
TESTFILE=src/pages/__tests__/creator-chat.dispute-entry.test.tsx

[ -f "$API" ] || { echo "· $API missing — unavailable"; exit 2; }
[ -f "$PAGE" ] || { echo "· $PAGE missing — unavailable"; exit 2; }

echo "· api layer: a creator-side dispute-open client call actually exists"
# Check BOTH layers — a "no client exists" conclusion drawn from only one has been wrong here
# before. Report which layer it was found in.
found_in=""
if awk '/^export const creatorDisputes = \{/,/^\};$/' "$API_CODE" | grep -qE "open:\s*\(dealId" \
   && awk '/^export const creatorDisputes = \{/,/^\};$/' "$API_CODE" | grep -qE "role:\s*'creator'"; then
  found_in="src/lib/api.ts"
elif [ -f "$MEERA_API_CODE" ] && grep -qE "creatorDisputes" "$MEERA_API_CODE" \
   && grep -qE "role:\s*'creator'" "$MEERA_API_CODE"; then
  found_in="src/lib/meera-api.ts"
fi
if [ -z "$found_in" ]; then
  echo "VERDICT: broken — no creatorDisputes.open(dealId, ...) client call with role: 'creator'"
  echo "         found in either api.ts or meera-api.ts. If this is genuinely true, the honest"
  echo "         end state is the disabled-with-reason control this gate replaces, and F-0301"
  echo "         belongs under NOT FIXED, not broken — but as of this writing it exists in"
  echo "         src/lib/api.ts (creatorDisputes.open, POST /deals/:dealId/disputes)."
  exit 1
fi
echo "  clean — creatorDisputes.open(dealId, reason) found in $found_in"

echo "· page: the 'Deal options' trigger is no longer hard-disabled"
# The pre-fix control was a permanently-disabled button with aria-disabled="true" right next to
# a "Deal options" aria-label. A fix must remove the unconditional disabled state from that
# specific control (unrelated disabled buttons elsewhere in the file, e.g. shipment/deliverable
# gating, are not this control and must not trip this leg).
deal_options_block=$(awk '/aria-label="Deal options"/{print; f=5} f{print; f--}' "$PAGE_CODE")
trigger_context=$(grep -n 'aria-label="Deal options"' "$PAGE_CODE" | head -1 | cut -d: -f1)
if [ -z "$trigger_context" ]; then
  echo "VERDICT: broken — no 'Deal options' control found at all in $PAGE (F-0301)"
  exit 1
fi
# Look at the 12 lines around the trigger for an unconditional disabled/aria-disabled pair.
start=$((trigger_context > 10 ? trigger_context - 10 : 1))
end=$((trigger_context + 4))
window=$(sed -n "${start},${end}p" "$PAGE_CODE")
if printf '%s\n' "$window" | grep -qE '^\s*disabled\s*$' \
   && printf '%s\n' "$window" | grep -qE 'aria-disabled="true"'; then
  echo "  $window" | head -5
  echo "VERDICT: broken — the 'Deal options' trigger around $PAGE:$trigger_context is still"
  echo "         unconditionally disabled (bare 'disabled' + aria-disabled=\"true\", no gating"
  echo "         condition) — the F-0289 disabled-with-reason state was never replaced (F-0301)"
  exit 1
fi
echo "  clean — no unconditional disabled/aria-disabled pair around the Deal options trigger"

echo "· page: a real handler function calls creatorDisputes.open in its body"
handler_body=$(awk '/const handleOpenDispute = async \(\) => \{/,/^  \};$/' "$PAGE_CODE")
if [ -z "$handler_body" ]; then
  echo "VERDICT: broken — no 'const handleOpenDispute = async () => { ... }' handler found in"
  echo "         $PAGE; a dispute action needs a real function body, not an inline no-op (F-0301)"
  exit 1
fi
if ! printf '%s\n' "$handler_body" | grep -qE "creatorDisputes\.open\("; then
  echo "$handler_body"
  echo "VERDICT: broken — handleOpenDispute() exists but never calls creatorDisputes.open(); a"
  echo "         handler that runs and does nothing is the same dead end as no handler (F-0301)"
  exit 1
fi
if ! printf '%s\n' "$handler_body" | grep -qE "role:\s*'creator'|creatorDisputes\.open\(selectedDeal"; then
  echo "$handler_body"
  echo "VERDICT: broken — handleOpenDispute() does not appear to pass the selected deal's id"
  echo "         into creatorDisputes.open (F-0301)"
  exit 1
fi
echo "  clean — handleOpenDispute() calls creatorDisputes.open(...)"

echo "· page: handleOpenDispute is actually wired to a clickable control, not dead code itself"
if ! grep -qE 'onClick=\{\(\)\s*=>\s*void handleOpenDispute\(\)\}|onClick=\{handleOpenDispute\}' "$PAGE_CODE"; then
  echo "VERDICT: broken — handleOpenDispute is defined but no onClick in $PAGE calls it; a menu"
  echo "         item that renders with an empty/missing onClick is exactly the wrong fix this"
  echo "         gate rejects (F-0301)"
  exit 1
fi
echo "  clean — handleOpenDispute is bound to a real onClick"

echo "· page: the dispute trigger is gated on escrowFunded, mirroring the brand-side pattern"
if ! grep -qE 'selectedDeal\.escrowFunded' "$PAGE_CODE"; then
  echo "VERDICT: broken — no escrowFunded gating found near the dispute control; F-0242's brand"
  echo "         fix gates on selectedDeal.escrowFunded (NO_FUNDED_ESCROW is a real 409 the"
  echo "         server can return) and this control should mirror that, not invent a second"
  echo "         pattern (F-0301)"
  exit 1
fi
echo "  clean — selectedDeal.escrowFunded gates the control"

command -v npx >/dev/null 2>&1 || { echo "· npx not on PATH — unavailable"; exit 2; }
[ -f package.json ] || { echo "· package.json missing — unavailable"; exit 2; }
[ -f "$TESTFILE" ] || { echo "· $TESTFILE missing — unavailable"; exit 2; }

BUDGET="${PROOF_F0301_VITEST_TIMEOUT:-180}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 15 $BUDGET"; else TO=""; fi

echo "· vitest run $TESTFILE (budget ${BUDGET}s)"
out=$(cd "$ROOT" && $TO npx vitest run "$TESTFILE" 2>&1); rc=$?

if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: everything below this line — the suite did not finish"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: broken — $TESTFILE does not pass; the wiring this gate asserts statically is"
  echo "         not actually exercised end to end (F-0301)"
  exit 1
fi
echo "  suite green — $TESTFILE"

echo "VERDICT: aligned (proved) — creatorDisputes.open exists in $found_in, the 'Deal options'"
echo "         trigger is no longer hard-disabled, handleOpenDispute() calls"
echo "         creatorDisputes.open(selectedDeal.id, reason), is wired to a real onClick, gated"
echo "         on selectedDeal.escrowFunded exactly like the brand side, and $TESTFILE passes"
echo "NOT CHECKED: a live 409 (NO_FUNDED_ESCROW / DISPUTE_ALREADY_OPEN) round-trip against a"
echo "             real backend; whether the admin console actually surfaces creator-opened"
echo "             disputes distinctly from brand-opened ones (DisputeOpenerType is a backend"
echo "             concern, not this gate's surface)."
exit 0
