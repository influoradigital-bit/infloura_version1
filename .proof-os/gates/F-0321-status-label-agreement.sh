#!/usr/bin/env bash
# F-0321-status-label-agreement.sh — gate for F-0321 (cross-surface-label-contradiction).
#
# F-0252 mapped the backend's real terminal ContractStatus, CANCELLED, to 'expired' ->
# "Expired" in contracts-and-deliverables.tsx's statusConfig, and to "Contract cancelled" in
# brand-campaign-detail.tsx's contractStatusLabel — for the SAME backend value. A contract
# cancelled by a party is not one whose expiration date passed. Side effect: statusConfig.disputed
# became unreachable, since nothing can produce the 'disputed' UI status any more.
#
# A gate that greps for one literal ("Cancelled") in one file is a snapshot of today's strings,
# not an agreement check — it would pass just as happily if only ONE of the two surfaces were
# fixed. This gate instead derives BOTH surfaces' real label for the SAME backend status through
# their own production code paths and compares them.
#
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

CONTRACTS=src/components/brand/contracts/contracts-and-deliverables.tsx
CAMPAIGN_DETAIL=src/pages/brand-campaign-detail.tsx
for f in "$CONTRACTS" "$CAMPAIGN_DETAIL"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

echo "· sanity: contracts-and-deliverables.tsx exports a badge-label helper the agreement test can call"
CONTRACTS_CODE=$(code_view "$CONTRACTS") || { echo "$(code_why) - unavailable"; exit 2; }
if ! grep -qE "export function contractStatusBadgeLabel" "$CONTRACTS_CODE"; then
  echo "VERDICT: broken — contractStatusBadgeLabel is not exported from contracts-and-deliverables.tsx;"
  echo "         the cross-surface agreement test has no real production code path to call (F-0321)"
  exit 1
fi
echo "  clean — contractStatusBadgeLabel is exported"

echo "· sanity: brand-campaign-detail.tsx still exports contractStatusLabel"
CAMPAIGN_CODE=$(code_view "$CAMPAIGN_DETAIL") || { echo "$(code_why) - unavailable"; exit 2; }
if ! grep -qE "export const contractStatusLabel" "$CAMPAIGN_CODE"; then
  echo "VERDICT: broken — contractStatusLabel is no longer exported from brand-campaign-detail.tsx"
  echo "         (F-0321 regressed the other surface's own control-label lookup)"
  exit 1
fi
echo "  clean — contractStatusLabel is exported"

echo "· side effect: statusConfig.disputed is not left behind as unreachable dead config"
if grep -qE "^\s*disputed\s*:\s*\{" "$CONTRACTS_CODE"; then
  echo "VERDICT: broken — statusConfig still has a 'disputed' entry, but nothing in"
  echo "         mapApiContractStatus can ever produce the 'disputed' UI status any more (F-0321"
  echo "         side effect not resolved)"
  exit 1
fi
echo "  clean — no dead 'disputed' entry in statusConfig"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· node_modules/.bin/vitest not installed — unavailable"; exit 2; }

SUITE=src/pages/__tests__/contract-status-label-agreement.test.ts
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

BUDGET="${PROOF_F0321_VITEST_TIMEOUT:-90}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

echo "· vitest run $SUITE (budget ${BUDGET}s)"
out=$($TO node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?

if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: everything below this line — the suite did not finish, so no test result"
  echo "             was observed"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "✓|×|FAIL|Tests |Test Files" | tail -40
  echo "VERDICT: broken — contracts-and-deliverables.tsx and brand-campaign-detail.tsx do not"
  echo "         agree on what CANCELLED means for a brand reading either surface (F-0321)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files" | tail -2
echo "  suite green — contract-status-label-agreement.test.ts"

echo "VERDICT: aligned (proved) — contracts-and-deliverables.tsx's status badge and"
echo "         brand-campaign-detail.tsx's contractStatusLabel both describe a backend CANCELLED"
echo "         contract as cancelled, never as expired; statusConfig no longer carries a"
echo "         'disputed' entry that mapApiContractStatus can never produce"
echo "NOT CHECKED: whether any OTHER page in the brand surface has its own, third, contract-status"
echo "             vocabulary (only these two files are in this record's scope); the exact wording"
echo "             each surface uses beyond 'contains the word cancel, not expire' — the two"
echo "             functions intentionally keep different styles (short badge word vs. full"
echo "             action sentence), so this does not assert byte-identical strings."
exit 0
