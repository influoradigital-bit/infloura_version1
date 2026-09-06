#!/usr/bin/env bash
# gates/F-0489-escrow-hold-release-ui.sh
# origin: F-0489 (unreachable-endpoint) — "POST /wallet/escrow/release has one frontend caller and
# the api wrapper hardcodes body milestoneId, so the escrowHoldId branch has no caller at all. A
# Meera-funded campaign-level hold has no milestone row by construction and is therefore
# unreleasable from any brand screen — funds locked with no UI path out."
#
# THE FIX: src/lib/api.ts payments.releasePayout now accepts a milestoneId string OR an
# { escrowHoldId } object, sending exactly one key (never both, never neither — mirrors the
# backend's XOR contract). brand-wallet.tsx renders a real Release button for a FUNDED hold with no
# milestoneId, calling the new escrowHoldId form.
#
# FALSIFICATION, measured 2026-09-04: reverting both source files failed 5/8 assertions for the
# right reason (hardcoded milestoneId body, no Release button/handler existed); restored, 8/8 green.
# Independently re-verified by a fresh-context review: real <Button onClick> at
# brand-wallet.tsx:1605-1616, the both-fields-impossible ternary at api.ts:3634, tsc --noEmit clean
# (no existing caller broken by the widened signature).
#
# LAW: exit 0 proved, 1 broken, 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

T1=src/lib/__tests__/release-payout-xor.f0489.test.ts
T2=src/pages/__tests__/brand-wallet.release-hold.test.tsx
for f in "$T1" "$T2"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not found — unavailable"; exit 2; }

BUDGET="${PROOF_F0489_VITEST_TIMEOUT:-180}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
echo "· vitest: $T1 $T2 (budget ${BUDGET}s)"
out=$($TO node_modules/.bin/vitest run "$T1" "$T2" 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if printf '%s' "$out" | grep -q "No test files found"; then
  echo "  vitest collected neither file — unavailable"; exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -50
  echo "VERDICT: broken — the escrowHoldId release path or its UI control regressed (F-0489)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true
echo "  suite green"

echo "VERDICT: aligned (proved) — payments.releasePayout sends exactly one of milestoneId/"
echo "         escrowHoldId, never both/neither; a milestone-less FUNDED hold on brand-wallet has a"
echo "         real, reachable Release control wired to it."
echo "NOT CHECKED: live-backend behaviour; whether a real Meera-funded campaign-level hold actually"
echo "             arrives with the shape this UI assumes; deal-payments-tab.tsx's per-milestone"
echo "             path (untouched, covered by its own existing spec)."
exit 0
