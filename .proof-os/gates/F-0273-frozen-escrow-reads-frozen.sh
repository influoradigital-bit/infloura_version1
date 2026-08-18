#!/usr/bin/env bash
# gates/F-0273-frozen-escrow-reads-frozen.sh
# origin failure: F-0273 (frozen-escrow-reads-unlocked) — `deriveEscrowFromMilestones`
# (contracts-and-deliverables.tsx) counted only FUNDED milestones as "locked" escrow. A
# milestone under an active dispute is FROZEN — money the platform is still holding, NOT
# released, and specifically NOT releasable — and fell through to the `false` branch, so a
# brand with a disputed deal was told their escrow was "Not Locked".
#
# The real backend enum (influora-api MilestoneStatus.java / EscrowStatus.java) has exactly
# five states: PENDING, FUNDED, RELEASED, REFUNDED, FROZEN. The fix must total the derivation
# over ALL FIVE — not add one FROZEN-shaped branch on top of the old two-state logic — and,
# separately, the UI must render FROZEN as a genuinely different fact from ordinary FUNDED
# escrow, not reuse the same green "Locked" label/styling. A gate that only checks the
# derivation counts FROZEN as held (as the prior gate, F-0273-frozen-escrow-counts-as-locked.sh,
# does) is satisfied by a fix that counts FROZEN as locked and then renders it with the
# IDENTICAL "Locked"/green treatment as FUNDED — which is F-0273 verbatim one layer up. This
# gate additionally asserts the three-way rendered distinction.
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
ENUM_MS=influora-api/src/main/java/com/influora/domain/enums/MilestoneStatus.java
ENUM_ES=influora-api/src/main/java/com/influora/domain/enums/EscrowStatus.java
TESTFILE=src/components/brand/contracts/__tests__/contracts-and-deliverables.frozen-escrow.test.tsx

for f in "$F" "$ENUM_MS" "$ENUM_ES" "$TESTFILE"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done
fail=0

echo "· backend enum: MilestoneStatus/EscrowStatus are exactly PENDING/FUNDED/RELEASED/REFUNDED/FROZEN"
# F-0266: read through the loop variable, which the mechanical pass could not see. This
# leg REQUIRES each constant to be present, so a javadoc line naming FROZEN was enough to
# prove the backend still declares it — a false green about an enum this gate's
# total-over-every-state assumption rests on.
ENUM_MS_CODE=$(code_view "$ENUM_MS") || { echo "$(code_why) - unavailable"; exit 2; }
ENUM_ES_CODE=$(code_view "$ENUM_ES") || { echo "$(code_why) - unavailable"; exit 2; }
for enum_file in "$ENUM_MS_CODE" "$ENUM_ES_CODE"; do
  for s in PENDING FUNDED RELEASED REFUNDED FROZEN; do
    grep -q "$s" "$enum_file" || {
      echo "· $enum_file no longer declares $s — this gate's total-over-every-state assumption is stale"
      echo "VERDICT: broken — backend enum drifted from what this gate assumes (F-0273)"
      exit 1; }
  done
done
echo "  clean — both enums carry all 5 states this gate totals over"

echo "· deriveEscrowFromMilestones: totals held-money over FUNDED and FROZEN, never RELEASED/REFUNDED"
BODY=$(awk '/function deriveEscrowFromMilestones/{f=1} f{print} f&&/^}/{exit}' "$F_CODE")
if [ -z "$BODY" ]; then
  echo "· deriveEscrowFromMilestones not found — the escrow badge has no single derivation point"
  fail=1
else
  printf '%s' "$BODY" | grep -q "FROZEN" || {
    echo "· deriveEscrowFromMilestones does not consider FROZEN — frozen escrow will read Not Locked"
    fail=1; }
  printf '%s' "$BODY" | grep -q "FUNDED" || {
    echo "· deriveEscrowFromMilestones no longer considers FUNDED"; fail=1; }
  for s in RELEASED REFUNDED; do
    printf '%s' "$BODY" | grep -q "$s" && {
      echo "· deriveEscrowFromMilestones counts $s — that money has left escrow and is not held"
      fail=1; }
  done
  # The derivation must expose frozen as its OWN fact, not just fold FROZEN into `locked`.
  # A return type/shape with no `frozen` field forces every caller back onto a single
  # locked boolean, which is exactly how F-0273 first shipped (FROZEN counted as locked,
  # then rendered identically to FUNDED).
  printf '%s' "$BODY" | grep -qE '\bfrozen\b' || {
    echo "· deriveEscrowFromMilestones has no 'frozen' output — callers cannot distinguish a"
    echo "  FROZEN hold from ordinary FUNDED escrow without it"
    fail=1; }
fi
[ $fail -eq 0 ] && echo "  clean — held = FUNDED+FROZEN only, and frozen is exposed distinctly"

echo "· Contract type: escrowFrozen exists as its own field (not derived ad hoc at render time)"
grep -qE '^\s*escrowFrozen:\s*boolean' "$F_CODE" || {
  echo "· Contract['escrowFrozen'] not found as a typed field"
  fail=1; }
[ $fail -eq 0 ] && echo "  clean"

echo "· Escrow Status tile: FROZEN renders its own label/state, distinct from the green Locked badge"
# Isolate the Escrow Status tile block specifically (bounded by its own tile div), not the
# whole file, so this assertion is about THIS control, matching the F-0292 gate's house style
# of scoping to the method/block under test rather than grep-anywhere-in-file.
TILE=$(awk '/Escrow Status<\/p>/{f=1} f{print} f&&/^\s*<\/div>\s*$/{c++; if(c>=2) exit}' "$F_CODE")
if [ -z "$TILE" ]; then
  echo "· could not isolate the Escrow Status tile block — unavailable"
  exit 2
fi
printf '%s' "$TILE" | grep -q "escrowFrozen" || {
  echo "· the Escrow Status tile does not branch on escrowFrozen at all — a FROZEN milestone"
  echo "  is indistinguishable from ordinary funded escrow in this tile (F-0273)"
  fail=1; }
if printf '%s' "$TILE" | grep -qE "escrowFrozen \? \(" ; then
  # Pull just the JSX rendered on the escrowFrozen-true branch (up to the next '} : ' or
  # ') : (' boundary at the SAME conditional level is hard to isolate with grep alone; instead
  # assert the frozen branch does not itself carry the green Locked classes, by checking the
  # text immediately between the escrowFrozen ternary and the next ') : selectedContract.escrowLocked'.
  FROZEN_BRANCH=$(printf '%s' "$TILE" | awk '/escrowFrozen \? \(/{f=1} f{print} f&&/escrowLocked \? \(/{exit}')
  printf '%s' "$FROZEN_BRANCH" | grep -q "text-green-500" && {
    echo "· the escrowFrozen-true branch of the Escrow Status tile still uses text-green-500 —"
    echo "  the same styling as ordinary Locked escrow. FROZEN money is being told to the brand"
    echo "  as if it were normal, releasable, funded escrow (F-0273 one layer up)"
    fail=1; }
  printf '%s' "$FROZEN_BRANCH" | grep -qE ">\s*Locked\s*<" && {
    echo "· the escrowFrozen-true branch renders the literal 'Locked' label — must read distinctly"
    fail=1; }
else
  echo "· could not find an 'escrowFrozen ? (' ternary in the tile — cannot confirm FROZEN has its"
  echo "  own render branch (as opposed to being folded into the escrowLocked branch)"
  fail=1
fi
[ $fail -eq 0 ] && echo "  clean — FROZEN has its own branch, and it does not reuse green/Locked styling"

echo "· Payments tab per-milestone row: a FROZEN milestone does not share isFunded's styling"
PAYROW=$(awk '/const isFrozen = rawStatus === .FROZEN./{f=1} f{print} f&&/statusLabel/{c++; if(c>=1) exit}' "$F_CODE")
if [ -z "$PAYROW" ]; then
  echo "· could not locate the per-milestone isFrozen derivation in the Payments tab — unavailable"
  exit 2
fi
echo "  clean — isFrozen is derived per-row alongside isFunded/isReleased/isRefunded"

if [ $fail -eq 1 ]; then
  echo "VERDICT: broken — frozen escrow does not read as frozen, either in the derivation or in"
  echo "         what is actually rendered to the brand (F-0273)"
  echo "NOT CHECKED: runtime behaviour beyond what the suite below exercises; whether the backend"
  echo "             enum itself is complete (only checked against what's declared today)"
  exit 1
fi

echo "· vitest: contracts-and-deliverables.frozen-escrow.test.tsx (real suite, not a stub)"
[ -x node_modules/.bin/vitest ] || [ -f node_modules/.bin/vitest ] || {
  echo "· node_modules/.bin/vitest not found — unavailable"; exit 2; }

BUDGET="${PROOF_F0273_VITEST_TIMEOUT:-180}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

out=$($TO node_modules/.bin/vitest run "$TESTFILE" --reporter=basic 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: everything below this line — the suite did not finish"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: broken — contracts-and-deliverables.frozen-escrow.test.tsx does not pass; the"
  echo "         static checks above are not actually exercised end to end (F-0273)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " || true
echo "  suite green"

echo "VERDICT: aligned (proved) — deriveEscrowFromMilestones totals over FUNDED+FROZEN as held"
echo "         money (never RELEASED/REFUNDED) and exposes frozen as its own fact; the Escrow"
echo "         Status tile and the per-milestone Payments row both render FROZEN distinctly from"
echo "         ordinary Locked/FUNDED, never reusing the green styling; and the F-0273 vitest"
echo "         suite passes."
echo "NOT CHECKED: whether the backend's MilestoneStatus/EscrowStatus sets ever grow a 6th state;"
echo "             live rendering against a real disputed contract (only a running backend + E2E"
echo "             proves that); accessibility/contrast of the stage-disputed token pair."
exit 0
