#!/usr/bin/env bash
# gates/F-0236-no-mock-money-in-live.sh
# F-0323 (promoted-gate-greens-original-defect-2) is ALSO closed by this file. The prior
# version's leg 2 grepped for `deriveEscrowFromMilestones`, which the function's own
# definition satisfies forever, so deleting the CALL and hardcoding escrowLocked:false
# reproduced F-0236 verbatim and the leg still passed; leg 1 meanwhile demanded a
# `liveApi ?` ternary that a later task deliberately removed, so it emitted a false RED
# contradicting F-0251's gate over the same file. Both legs now assert behaviour.
# origin failure: F-0236 (mock-in-live-money) — the Contracts Payments tab rendered
# "50% Upon Signing / Paid" and a Jan-2024 transaction history with no isApiLive() guard, and
# `escrowLocked`/`escrowAmount` were never set from the real record, so every live contract read
# "Not Locked". A brand was shown invented money movements as if they were its own.
#
# F-0319-class repair (T-BRANDOPEN-0817). This gate used to stand on two text assertions, and
# BOTH have since rotted:
#
#   1. `awk '/TabsContent value="payments"/{f=1} f&&/liveApi \?/{print;exit}'` — "the payments tab
#      must open with a liveApi ternary". That awk window is unbounded: it accepts the first
#      `liveApi ?` ANYWHERE after the tab, which need not be inside it. And the shape it demands
#      was deliberately REMOVED by a later task: gates/F-0251-contract-payments-truthful.sh now
#      asserts the exact opposite — "Payments tab: exactly ONE render path — no liveApi-gated
#      branch left", because F-0251 lived inside that unguarded demo branch. Two closed records
#      were pointing at gates in direct contradiction, and this one has been emitting a FALSE RED
#      against the real tree ever since.
#   2. `grep -q "deriveEscrowFromMilestones" "$F_CODE"` over the whole file — satisfied forever by
#      the function's own DEFINITION. Delete the call from adaptContractRecord and hardcode
#      `escrowLocked: false` — F-0236 verbatim — and this leg still passed. That is the same
#      substring-somewhere shape that let the F-0273 gate green its own defect (F-0319).
#
# The repair is to stop asserting the fix's SHAPE and assert its BEHAVIOUR, which two real render
# suites already exercise against live-mode records:
#   - contracts-and-deliverables.payments-truthful.test.tsx (F-0251): in live mode with no
#     milestone data the tab says so plainly instead of fabricating a 50/50 split and 2024 dates.
#   - contracts-and-deliverables.frozen-escrow.test.tsx (F-0273): a live record whose milestones
#     are FUNDED renders "Locked" for the real amount, and one with none renders "Not Locked" —
#     which is precisely "escrowLocked/escrowAmount come from the real record", i.e. F-0236's own
#     second half, proved by rendering rather than by finding an identifier.
#
# LAW: exit 1 = real finding · 2 = cannot run · 0 = proved.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

F=src/components/brand/contracts/contracts-and-deliverables.tsx
T_TRUTH=src/components/brand/contracts/__tests__/contracts-and-deliverables.payments-truthful.test.tsx
T_FROZEN=src/components/brand/contracts/__tests__/contracts-and-deliverables.frozen-escrow.test.tsx
for f in "$F" "$T_TRUTH" "$T_FROZEN"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done
F_CODE=$(code_view "$F") || { echo "$(code_why) - unavailable"; exit 2; }
fail=0

# ---------------------------------------------------------------------------
# 1 · the mock fixture must not be reachable when the API is live. This one IS a
#     text fact about a single seeding expression, not a proxy for a behaviour:
#     the whole tab renders from `contracts`, and in live mode that list must
#     start empty rather than from mockContracts.
# ---------------------------------------------------------------------------
echo "· the contracts list is seeded empty in live mode, so no fixture contract can reach the tab"
SEED=$(grep -nE 'useState<Contract\[\]>\(' "$F_CODE" | head -3)
if [ -z "$SEED" ]; then
  echo "· could not find the contracts useState seed in $F — the shape this leg reads has moved;"
  echo "  re-derive it by hand rather than trusting this gate — unavailable"
  exit 2
fi
printf '%s\n' "$SEED" | sed 's/^/    /'
if ! printf '%s' "$SEED" | grep -qE 'liveApi \? \[\] :'; then
  echo "· the contracts list is NOT gated on liveApi — mockContracts can be rendered as though"
  echo "  they were the brand's own money (F-0236)"
  fail=1
else
  echo "  clean"
fi

# ---------------------------------------------------------------------------
# 2 · a LOCATOR, explicitly not the proof. If this drifts the vitest legs below
#     are still the authority; this only tells a future reader where to look.
# ---------------------------------------------------------------------------
ADAPT=$(awk '/^function adaptContractRecord/{f=1} f{print} f&&/^}/{exit}' "$F_CODE")
if [ -z "$ADAPT" ] || ! printf '%s' "$ADAPT" | grep -q 'deriveEscrowFromMilestones('; then
  echo "· note: adaptContractRecord no longer calls deriveEscrowFromMilestones. That is not this"
  echo "  gate's verdict — the render suites below decide — but the escrow figures are now coming"
  echo "  from somewhere else and this gate's comments are stale."
fi

if [ $fail -eq 1 ]; then
  echo "VERDICT: broken — fixture money is reachable in live mode (F-0236)"
  echo "NOT CHECKED: the render suites below were not run; mock leakage in files other than $F"
  exit 1
fi

# ---------------------------------------------------------------------------
# 3 · what is actually rendered. Both suites drive the component with isApiLive()
#     mocked true and real ContractApiRecords.
# ---------------------------------------------------------------------------
if [ ! -x node_modules/.bin/vitest ] && [ ! -f node_modules/.bin/vitest ]; then
  echo "· node_modules/.bin/vitest not found — unavailable"
  echo "NOT CHECKED: everything this gate now rests on — what the Payments tab renders in live mode"
  exit 2
fi
BUDGET="${PROOF_F0236_VITEST_TIMEOUT:-240}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

run_suite() {
  label="$1"; file="$2"
  echo "· vitest: $label"
  out=$($TO node_modules/.bin/vitest run "$file" --reporter=basic 2>&1); rc=$?
  if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
    echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
    echo "NOT CHECKED: what the Payments tab renders in live mode"
    exit 2
  fi
  if [ $rc -ne 0 ]; then
    printf '%s\n' "$out" | tail -40
    return 1
  fi
  printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true
  echo "  suite green"
  return 0
}

run_suite "payments-truthful — live mode with no milestone data fabricates nothing" "$T_TRUTH" || fail=1
run_suite "frozen-escrow — live milestones drive the escrow figures, not a constant" "$T_FROZEN" || fail=1

if [ $fail -eq 1 ]; then
  echo "VERDICT: broken — the Payments tab is not telling the brand the truth about its own money"
  echo "         in live mode: either invented schedule/dates are back, or the escrow figures are"
  echo "         no longer coming from the real record (F-0236)"
  echo "NOT CHECKED: live rendering against a running backend"
  exit 1
fi

echo "VERDICT: aligned (proved) — no fixture contract can reach the Payments tab in live mode, and"
echo "         two render suites drive the tab with real live records: nothing invents a 50/50"
echo "         schedule or 2024 transaction dates, and the escrow lock/amount shown are the ones"
echo "         derived from the record's own milestones."
echo "NOT CHECKED: whether the live branch shows the RIGHT amounts for a contract shape neither"
echo "             suite covers; mock-money leakage in files other than $F; live rendering against"
echo "             a running backend; and whether isApiLive() reports what the deployed build sets."
exit 0
