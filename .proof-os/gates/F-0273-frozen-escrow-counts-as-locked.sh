#!/usr/bin/env bash
# gates/F-0273-frozen-escrow-counts-as-locked.sh
# origin failure: F-0273 (frozen-escrow-reads-unlocked) — deriveEscrowFromMilestones counted only
# FUNDED milestones, so a deal under dispute (money FROZEN: held by the platform, NOT released)
# rendered "Escrow Status: Not Locked". The brand was told their money was not held at the exact
# moment a dispute froze it — the same false-money-statement class F-0236 was opened for.
# Found by priya (fresh-context) reviewing the F-0236 fix; the F-0236 gate could not see it,
# because that gate asserts the function EXISTS, not which statuses it counts.
#
# F-0319 (this gate's OWN defect, repaired here). Ledger record F-0273 is CLOSED against this
# file, and this file used to prove nothing. Its whole derivation leg was
#     printf '%s' "$BODY" | grep -q "FROZEN"
# — a substring search over the function body. A LATER task added, on the line after the filter,
#     const frozen = held.some((m) => (m.status ?? '').toUpperCase() === 'FROZEN');
# and from that moment that one line satisfied the grep forever, no matter what the filter above
# it did. Reintroducing F-0273 verbatim (return s === 'FUNDED';) left this gate at exit 0,
# VERDICT: aligned — a closed record standing on a gate that could not fail. Reproduced at
# .proof-os/tasks/T-BRANDOPEN-0817/F-0319.prefix.log.
#
# THE CLASS, and why the repair looks like this. A gate that asserts A TOKEN APPEARS SOMEWHERE is
# satisfied by any line that happens to carry the token — a comment (F-0266), a neighbouring
# statement (F-0319), a different attribute with a similar name (F-0310), or an unrelated line
# inside the same search window (F-0296). The only assertion that cannot be satisfied by an
# accident of text is an assertion about BEHAVIOUR. So this gate no longer greps the derivation:
# it EXTRACTS deriveEscrowFromMilestones out of the source and RUNS it over every one of the five
# backend MilestoneStatus values, and checks the numbers that come back.
#
# And because a harness can rot the way its greps did, the gate first runs the very same
# assertions against a KNOWN-BAD implementation (the F-0273 defect, frozen into this file) and
# REFUSES TO CERTIFY unless those assertions fail. A gate that cannot fail is worse than no gate;
# this one proves it can, on every run, before it believes itself.
#
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

F=src/components/brand/contracts/contracts-and-deliverables.tsx
ENUM_MS=influora-api/src/main/java/com/influora/domain/enums/MilestoneStatus.java
ENUM_ES=influora-api/src/main/java/com/influora/domain/enums/EscrowStatus.java
TESTFILE=src/components/brand/contracts/__tests__/contracts-and-deliverables.frozen-escrow.test.tsx
for f in "$F" "$ENUM_MS" "$ENUM_ES" "$TESTFILE"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done
F_CODE=$(code_view "$F") || { echo "$(code_why) - unavailable"; exit 2; }

WORK="$(mktemp -d 2>/dev/null)" || { echo "· cannot create a scratch dir — unavailable"; exit 2; }
trap 'rm -rf "$WORK" 2>/dev/null' EXIT
fail=0

# ---------------------------------------------------------------------------
# 0 · a way to actually RUN a TypeScript snippet.
# ---------------------------------------------------------------------------
command -v node >/dev/null 2>&1 || {
  echo "· node not on PATH — cannot execute the derivation — unavailable"; exit 2; }
RUN_MODE=""
printf 'const x: number = 1;\nif (x !== 1) process.exit(9);\n' > "$WORK/probe.ts"
if node --experimental-strip-types --no-warnings "$WORK/probe.ts" >/dev/null 2>&1; then
  RUN_MODE=strip
elif [ -x node_modules/.bin/esbuild ] || [ -f node_modules/.bin/esbuild ]; then
  RUN_MODE=esbuild
else
  echo "· this node cannot strip TS types (needs >= 22.6) and node_modules/.bin/esbuild is absent"
  echo "  — cannot execute the derivation — unavailable"
  exit 2
fi

# run_ts <file.ts> — prints the snippet's output, returns its exit code (90 = could not build).
run_ts() {
  if [ "$RUN_MODE" = strip ]; then
    node --experimental-strip-types --no-warnings "$1" 2>&1
  else
    node_modules/.bin/esbuild --format=cjs --platform=node --log-level=error "$1" > "$1.cjs" 2>/dev/null \
      || { echo "esbuild could not transpile the extracted derivation"; return 90; }
    node "$1.cjs" 2>&1
  fi
}

# ---------------------------------------------------------------------------
# 1 · the assertions. One table, applied to whichever implementation is
#     prepended to it. Every one of the five backend MilestoneStatus values
#     appears, with the money each case must total to.
# ---------------------------------------------------------------------------
cat > "$WORK/assert.ts" <<'TSEOF'
type Want = { locked: boolean; amount: number; frozen: boolean };
const cases: Array<{ name: string; ms: any[]; want: Want }> = [
  // THE DEFECT ITSELF: a purely-FROZEN contract. Money the platform is holding under a
  // dispute. It must read as held, for its full value, and it must say it is frozen.
  { name: 'purely FROZEN (the F-0273 defect)',
    ms: [{ id: 'a', amount: 30000, status: 'FROZEN' }],
    want: { locked: true, amount: 30000, frozen: true } },
  { name: 'two FROZEN milestones total together',
    ms: [{ id: 'a', amount: 20000, status: 'FROZEN' }, { id: 'b', amount: 10000, status: 'FROZEN' }],
    want: { locked: true, amount: 30000, frozen: true } },
  // NO-REGRESSION: ordinary funded escrow stays ordinary funded escrow, and is NOT frozen.
  { name: 'FUNDED only',
    ms: [{ id: 'a', amount: 30000, status: 'FUNDED' }],
    want: { locked: true, amount: 30000, frozen: false } },
  { name: 'mixed FUNDED + FROZEN totals both, and flags frozen',
    ms: [{ id: 'a', amount: 30000, status: 'FUNDED' }, { id: 'b', amount: 30000, status: 'FROZEN' }],
    want: { locked: true, amount: 60000, frozen: true } },
  // NEGATIVE CONTROLS: money that has LEFT escrow, and money never put in. A fix that counts
  // every status in order to satisfy a FROZEN check is caught here, not applauded.
  { name: 'RELEASED has left escrow',
    ms: [{ id: 'a', amount: 30000, status: 'RELEASED' }],
    want: { locked: false, amount: 0, frozen: false } },
  { name: 'REFUNDED has left escrow',
    ms: [{ id: 'a', amount: 30000, status: 'REFUNDED' }],
    want: { locked: false, amount: 0, frozen: false } },
  { name: 'PENDING was never funded',
    ms: [{ id: 'a', amount: 30000, status: 'PENDING' }],
    want: { locked: false, amount: 0, frozen: false } },
  { name: 'no milestones at all',
    ms: [],
    want: { locked: false, amount: 0, frozen: false } },
  // The derivation normalises case; keep that a stated, tested fact rather than an accident.
  { name: 'lower-case frozen still counts',
    ms: [{ id: 'a', amount: 30000, status: 'frozen' }],
    want: { locked: true, amount: 30000, frozen: true } },
  { name: 'absent status is not held money',
    ms: [{ id: 'a', amount: 30000 }],
    want: { locked: false, amount: 0, frozen: false } },
];
declare const deriveEscrowFromMilestones: any;
let bad = 0;
for (const c of cases) {
  let got: any;
  try {
    got = deriveEscrowFromMilestones(c.ms);
  } catch (e) {
    console.log('x ' + c.name + ' — threw: ' + (e as Error).message);
    bad++;
    continue;
  }
  const g = {
    locked: !!(got && got.locked),
    amount: Number(got && got.amount),
    frozen: !!(got && got.frozen),
  };
  const w = c.want;
  if (g.locked !== w.locked || g.amount !== w.amount || g.frozen !== w.frozen) {
    console.log('x ' + c.name);
    console.log('    want locked=' + w.locked + ' amount=' + w.amount + ' frozen=' + w.frozen);
    console.log('    got  locked=' + g.locked + ' amount=' + g.amount + ' frozen=' + g.frozen);
    bad++;
  }
}
if (bad > 0) {
  console.log(bad + '/' + cases.length + ' derivation cases wrong');
  process.exit(1);
}
console.log(cases.length + '/' + cases.length + ' derivation cases correct');
process.exit(0);
TSEOF

# ---------------------------------------------------------------------------
# 2 · SELF-FALSIFICATION. Before trusting the table above, prove it can fail:
#     run it against F-0273 verbatim. If a broken implementation passes, this
#     gate is the F-0319 shape again and must not certify anything.
# ---------------------------------------------------------------------------
echo "· self-check: these assertions reject a known-bad derivation (F-0273 verbatim)"
cat > "$WORK/bad.ts" <<'TSEOF'
function deriveEscrowFromMilestones(
  milestones: any[],
): { locked: boolean; amount: number; frozen: boolean } {
  const held = milestones.filter((m) => {
    const s = (m.status ?? '').toUpperCase();
    return s === 'FUNDED';
  });
  const frozen = held.some((m) => (m.status ?? '').toUpperCase() === 'FROZEN');
  return {
    locked: held.length > 0,
    amount: held.reduce((sum, m) => sum + m.amount, 0),
    frozen,
  };
}
TSEOF
cat "$WORK/bad.ts" "$WORK/assert.ts" > "$WORK/selfcheck.ts"
sc_out=$(run_ts "$WORK/selfcheck.ts"); sc_rc=$?
if [ $sc_rc -eq 90 ]; then
  printf '%s\n' "$sc_out" | sed 's/^/  /'
  echo "· cannot transpile the self-check — unavailable"; exit 2
fi
if [ $sc_rc -eq 0 ]; then
  printf '%s\n' "$sc_out" | sed 's/^/  /'
  echo "· THIS GATE CANNOT FAIL: its own assertion table certified a derivation that is the"
  echo "  F-0273 defect verbatim. Refusing to report a verdict about the real code from a check"
  echo "  that has just proved itself blind."
  echo "VERDICT: broken — the F-0273 gate's assertions no longer detect F-0273 (F-0319)"
  echo "NOT CHECKED: the real product code — this run never got that far"
  exit 1
fi
echo "  good — the known-bad derivation is rejected, so a green below means something"

# ---------------------------------------------------------------------------
# 3 · the backend enum this gate's five-state table rests on.
# ---------------------------------------------------------------------------
echo "· backend enum: MilestoneStatus/EscrowStatus still declare PENDING/FUNDED/RELEASED/REFUNDED/FROZEN"
ENUM_MS_CODE=$(code_view "$ENUM_MS") || { echo "$(code_why) - unavailable"; exit 2; }
ENUM_ES_CODE=$(code_view "$ENUM_ES") || { echo "$(code_why) - unavailable"; exit 2; }
for enum_file in "$ENUM_MS_CODE" "$ENUM_ES_CODE"; do
  for s in PENDING FUNDED RELEASED REFUNDED FROZEN; do
    grep -q "$s" "$enum_file" || {
      echo "· an enum no longer declares $s — this gate's five-state table is stale"
      echo "VERDICT: broken — backend enum drifted from what this gate assumes (F-0273)"
      echo "NOT CHECKED: everything below — the state set this gate totals over is no longer real"
      exit 1; }
  done
done
echo "  clean"

# ---------------------------------------------------------------------------
# 4 · RUN the real derivation. This is the leg F-0319 was missing.
# ---------------------------------------------------------------------------
echo "· deriveEscrowFromMilestones, EXECUTED over all five milestone states"
awk '/^(export )?(async )?function deriveEscrowFromMilestones/{f=1} f{print} f&&/^}/{exit}' \
  "$F_CODE" > "$WORK/fn.ts"
if [ ! -s "$WORK/fn.ts" ]; then
  echo "· deriveEscrowFromMilestones not found as a top-level function in $F — the escrow badge"
  echo "  has no single derivation point this gate can execute"
  echo "VERDICT: broken — F-0273's derivation point is gone (F-0273)"
  echo "NOT CHECKED: the rendered escrow tile — there is nothing left to derive from"
  exit 1
fi
if ! grep -q '^}' "$WORK/fn.ts"; then
  echo "· could not isolate a complete deriveEscrowFromMilestones body (no closing brace at"
  echo "  column 0) — refusing to guess at a partial function — unavailable"
  exit 2
fi
cat "$WORK/fn.ts" "$WORK/assert.ts" > "$WORK/real.ts"
real_out=$(run_ts "$WORK/real.ts"); real_rc=$?
printf '%s\n' "$real_out" | sed 's/^/  /'
if [ $real_rc -eq 90 ]; then
  echo "· cannot transpile the extracted derivation — unavailable"; exit 2
fi
[ $real_rc -ne 0 ] && fail=1

if [ $fail -eq 1 ]; then
  echo "VERDICT: broken — deriveEscrowFromMilestones does not total held money correctly; frozen"
  echo "         escrow is misreported to the brand (F-0273)"
  echo "NOT CHECKED: the rendered Escrow Status tile and Payments rows — the vitest leg below was"
  echo "             not reached, because the derivation that feeds them is already wrong"
  exit 1
fi

# ---------------------------------------------------------------------------
# 5 · what the brand actually SEES. Derivation is half of F-0273; the other half
#     is that FROZEN must not be dressed as ordinary releasable escrow. The
#     vitest suite asserts that against a real render, so this gate defers to it
#     rather than grepping the JSX for a class name.
# ---------------------------------------------------------------------------
echo "· vitest: contracts-and-deliverables.frozen-escrow.test.tsx — FROZEN renders distinctly"
if [ ! -x node_modules/.bin/vitest ] && [ ! -f node_modules/.bin/vitest ]; then
  echo "· node_modules/.bin/vitest not found — unavailable"
  echo "NOT CHECKED: the rendered tile. The derivation legs above DID pass; this run simply"
  echo "             cannot prove what is put in front of the brand."
  exit 2
fi
BUDGET="${PROOF_F0273_VITEST_TIMEOUT:-240}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
out=$($TO node_modules/.bin/vitest run "$TESTFILE" --reporter=basic 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: the rendered tile; the derivation legs above DID pass"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken — the F-0273 render suite fails: a FROZEN hold is not being shown to the"
  echo "         brand as distinct from ordinary funded, releasable escrow (F-0273)"
  echo "NOT CHECKED: live rendering against a real disputed contract"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true
echo "  suite green"

echo "VERDICT: aligned (proved) — deriveEscrowFromMilestones was EXECUTED over all five"
echo "         MilestoneStatus values: FUNDED and FROZEN total as held money (a purely-FROZEN"
echo "         contract derives locked=true with its full amount and frozen=true), while"
echo "         RELEASED / REFUNDED / PENDING / empty derive as not held; and the render suite"
echo "         proves FROZEN is shown to the brand distinctly from ordinary Locked escrow. The"
echo "         assertion table was proved falsifiable against the F-0273 defect before any of"
echo "         that was believed."
echo "NOT CHECKED: whether MilestoneStatus/EscrowStatus ever grow a sixth state; the per-milestone"
echo "             accounting of a mixed FUNDED+FROZEN contract beyond the single frozen flag;"
echo "             live rendering against a real disputed contract on a running backend; that"
echo "             deriveEscrowFromMilestones is the ONLY thing feeding the tile (the vitest leg"
echo "             covers the wired path, this gate's extraction does not)."
exit 0
