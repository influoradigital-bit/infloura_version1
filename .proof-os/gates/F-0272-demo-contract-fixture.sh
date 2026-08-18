#!/usr/bin/env bash
# gates/F-0272-demo-contract-fixture.sh
# origin failure: F-0272 (demo-sign-flow-dead) — the F-0237 fix gated DealContractTab's Sign
# control on a real fetched contract, but api.contracts.get resolved `null` in mock mode. So every
# demo/offline walkthrough rendered "Contract terms are not available yet" with Sign permanently
# disabled: a fix for a live-mode lie that silently broke the sales demo.
# Found by priya (fresh-context) reviewing the F-0237 fix.
#
# F-0329 (this gate's OWN defect, repaired here). Ledger record F-0272 is CLOSED against this
# file, and this file could not fail. It isolated contracts.get with
#     awk '/^  get: \(role: Role, id: string\) =>/{f=1} f{print} f&&/^    : mockOr|^    \}\),/{c++; …}'
# whose terminator patterns assume a 4-space indent. The real lines are indented 6 and 8, so the
# range NEVER TERMINATED: the "window" was 3781 of api.ts's 5524 lines, running from contracts.get
# to `export default api;`. The `grep -A 22` fallback never fired either — a 3781-line string is
# not empty. Two of the three assertions were therefore answered by unrelated code thousands of
# lines away: `milestones` by contracts.generate's fixture, and the live-request check by other
# endpoints entirely. Restoring the defect as `: mockOr(null)` (no explicit generic, so the one
# literal regex missed too) left this gate at exit 0, VERDICT: aligned. Reproduced at
# .proof-os/tasks/T-F0329-GATES/F-0272.inject.log.
#
# THE CLASS, and why the repair looks like this. A line window is the wrong instrument — the
# F-0296 shape, here in its widest form. This gate no longer uses one. It locates contracts.get
# with the TypeScript parser (gates/_member_src.js), which cannot drift with indentation,
# reformatting, comment length, or a neighbour appearing; then it RUNS the extracted function in
# both modes and checks what it actually resolves. Assert behaviour, not the token.
#
# And because a harness can rot the way that awk did, the gate first runs the very same assertion
# table against four KNOWN-BAD implementations frozen into this file, and one KNOWN-GOOD one, and
# REFUSES TO CERTIFY unless it rejects all four bad and accepts the good. A gate that cannot fail
# is worse than no gate; one that cannot pass is a false-red machine.
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
A=src/lib/api.ts
[ -f "$A" ] || { echo "· $A missing — unavailable"; exit 2; }
A_CODE=$(code_view "$A") || { echo "$(code_why) - unavailable"; exit 2; }

EXTRACT="$SELF/_member_src.js"
[ -f "$EXTRACT" ] || { echo "· gates/_member_src.js missing — unavailable"; exit 2; }
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
TSDIR=node_modules/typescript
[ -f "$TSDIR/package.json" ] || {
  echo "· $TSDIR absent — this gate parses api.ts, it does not window it — unavailable"; exit 2; }
TSABS="$(cd "$TSDIR" && pwd)"

WORK="$(mktemp -d 2>/dev/null)" || { echo "· cannot create a scratch dir — unavailable"; exit 2; }
trap 'rm -rf "$WORK" 2>/dev/null' EXIT

# ---------------------------------------------------------------------------
# 0 · a way to actually RUN a TypeScript snippet.
# ---------------------------------------------------------------------------
RUN_MODE=""
printf 'const x: number = 1;\nif (x !== 1) process.exit(9);\n' > "$WORK/probe.ts"
if node --experimental-strip-types --no-warnings "$WORK/probe.ts" >/dev/null 2>&1; then
  RUN_MODE=strip
elif [ -f node_modules/.bin/esbuild ]; then
  RUN_MODE=esbuild
else
  echo "· this node cannot strip TS types (needs >= 22.6) and node_modules/.bin/esbuild is absent"
  echo "  — cannot execute contracts.get — unavailable"; exit 2
fi
run_ts() {
  if [ "$RUN_MODE" = strip ]; then
    node --experimental-strip-types --no-warnings "$1" 2>&1
  else
    node_modules/.bin/esbuild --format=cjs --platform=node --log-level=error "$1" > "$1.cjs" 2>/dev/null \
      || { echo "esbuild could not transpile the extracted contracts.get"; return 90; }
    node "$1.cjs" 2>&1
  fi
}

# ---------------------------------------------------------------------------
# 1 · stubs + the assertion table, applied to whichever contracts.get is prepended.
# ---------------------------------------------------------------------------
cat > "$WORK/prelude.ts" <<'TSEOF'
let LIVE = false;
const calls: any[] = [];
const isLive = () => LIVE;
const http = {
  request: (method: any, urlPath: any, opts?: any) => {
    calls.push({ method, path: urlPath, role: opts && opts.role });
    return Promise.resolve({ id: 'from-server', milestones: [], totalAmount: 0 });
  },
};
async function mockOr(value: any) { return value; }
TSEOF

cat > "$WORK/assert.ts" <<'TSEOF'
declare const contractsGet: any;
const bad: string[] = [];
function check(name: string, cond: boolean, detail: string) {
  if (!cond) bad.push(name + ' — ' + detail);
}

async function main() {
  // ---- 1. THE DEFECT ITSELF. DealContractTab does `if (!record) setContractError(...)` and
  //         gates Sign on `!contractRecord` (deal-contract-tab.tsx:83, :405). A null here is
  //         "Contract terms are not available yet" and a permanently disabled Sign, in every
  //         offline walkthrough.
  LIVE = false; calls.length = 0;
  let rec: any = null;
  try {
    rec = await contractsGet('brand', 'CTR_9');
  } catch (e) {
    bad.push('contracts.get threw in mock mode: ' + (e as Error).message);
  }
  check('mock mode resolves a contract (the F-0272 defect)', rec !== null && rec !== undefined,
    'resolved ' + JSON.stringify(rec) + ' — DealContractTab renders "Contract terms are not ' +
    'available yet" and Sign is permanently disabled for the whole demo');
  check('mock mode makes no network call', calls.length === 0, 'issued ' + calls.length);

  if (rec) {
    // The panel reads `contractRecord.milestones.length` unguarded — a missing or non-array
    // milestones field is a TypeError in the render, not a graceful empty state.
    check('the fixture echoes the requested id', rec.id === 'CTR_9', 'got ' + JSON.stringify(rec.id));
    check('milestones is an array', Array.isArray(rec.milestones),
      'got ' + JSON.stringify(rec.milestones) + ' — the panel does milestones.length unguarded');
    if (Array.isArray(rec.milestones)) {
      check('the demo contract has milestones to show', rec.milestones.length > 0,
        'empty — the demo renders "No milestones on this contract."');
      const sum = rec.milestones.reduce((s: number, m: any) => s + Number(m && m.amount), 0);
      check('totalAmount is the sum of the milestone amounts', Number(rec.totalAmount) === sum,
        'totalAmount=' + rec.totalAmount + ' but the milestones sum to ' + sum +
        ' — the demo would state a total the schedule does not support');
      check('every milestone has a numeric amount',
        rec.milestones.every((m: any) => Number.isFinite(Number(m && m.amount))),
        'got ' + JSON.stringify(rec.milestones.map((m: any) => m && m.amount)));
    }
    check('the fixture is not pre-signed', !rec.brandSignedAt && !rec.creatorSignedAt,
      'brandSignedAt=' + rec.brandSignedAt + ' creatorSignedAt=' + rec.creatorSignedAt +
      ' — a pre-signed fixture skips the very step the demo exists to show');
    check('the fixture carries a currency', !!rec.currency, 'got ' + JSON.stringify(rec.currency));
  }

  // ---- 2. NO LEAK. The fixture must never stand in for a real contract in live mode: that
  //         is the F-0237 guarantee this fixture was not allowed to undo.
  LIVE = true; calls.length = 0;
  let live: any = null;
  try {
    live = await contractsGet('brand', 'CTR_9');
  } catch (e) {
    bad.push('contracts.get threw in live mode: ' + (e as Error).message);
  }
  check('live mode issues exactly one request', calls.length === 1, 'issued ' + calls.length);
  if (calls.length === 1) {
    check('live mode GETs /contracts/:id',
      calls[0].method === 'GET' && calls[0].path === '/contracts/CTR_9',
      'got ' + calls[0].method + ' ' + calls[0].path);
    check('live mode passes the caller role', calls[0].role === 'brand',
      'got ' + JSON.stringify(calls[0].role));
  }
  check('live mode returns the SERVER record, not the fixture',
    !!(live && live.id === 'from-server'),
    'got ' + JSON.stringify(live && live.id) + ' — the demo fixture has leaked into live mode');

  if (bad.length) {
    for (const b of bad) console.log('  x ' + b);
    console.log('  ' + bad.length + ' contracts.get assertion(s) failed');
    process.exit(1);
  }
  console.log('  contracts.get correct in both modes (mock resolves a signable contract, live GETs the server record)');
  process.exit(0);
}
void main();
TSEOF

# ---------------------------------------------------------------------------
# 2 · SELF-FALSIFICATION.
# ---------------------------------------------------------------------------
GOODFIX='const contractsGet = (role: any, id: string) =>
  isLive()
    ? http.request("GET", `/contracts/${id}`, { role })
    : mockOr({
        id,
        collaborationId: "deal_1",
        status: "PENDING_SIGNATURES",
        totalAmount: 50000,
        currency: "INR",
        brandSignedAt: null,
        creatorSignedAt: null,
        terms: null,
        milestones: [
          { sequenceNo: 1, description: "On signing", amount: 25000, status: "PENDING" },
          { sequenceNo: 2, description: "On deliverable approval", amount: 25000, status: "PENDING" },
        ],
      });'

# BAD 1 — the F-0329 injection: null again, written without the explicit generic the old
# gate's one literal regex looked for.
cat > "$WORK/bad1.impl.ts" <<'TSEOF'
const contractsGet = (role: any, id: string) =>
  isLive() ? http.request("GET", `/contracts/${id}`, { role }) : mockOr(null);
TSEOF
# BAD 2 — F-0272 verbatim, the byte-identical revert.
cat > "$WORK/bad2.impl.ts" <<'TSEOF'
const contractsGet = (role: any, id: string) =>
  isLive() ? http.request("GET", `/contracts/${id}`, { role })
           : mockOr<any | null>(null);
TSEOF
# BAD 3 — a fixture that exists but has no milestones. The old gate's `grep -q milestones`
# could not tell this from a real schedule; the panel shows an empty contract.
cat > "$WORK/bad3.impl.ts" <<'TSEOF'
const contractsGet = (role: any, id: string) =>
  isLive()
    ? http.request("GET", `/contracts/${id}`, { role })
    : mockOr({ id, status: "PENDING_SIGNATURES", totalAmount: 50000, currency: "INR",
               brandSignedAt: null, creatorSignedAt: null, terms: null, milestones: [] });
TSEOF
# BAD 4 — the fixture leaks into LIVE mode. This is the failure the fixture was forbidden to
# cause: it would re-create the F-0237 lie the whole chain started from.
cat > "$WORK/bad4.impl.ts" <<'TSEOF'
const contractsGet = (role: any, id: string) =>
  mockOr({ id, collaborationId: "deal_1", status: "PENDING_SIGNATURES", totalAmount: 50000,
           currency: "INR", brandSignedAt: null, creatorSignedAt: null, terms: null,
           milestones: [{ sequenceNo: 1, description: "On signing", amount: 50000, status: "PENDING" }] });
TSEOF
printf '%s\n' "$GOODFIX" > "$WORK/good.impl.ts"

echo "· self-check: these assertions reject four known-bad contracts.get implementations and accept a known-good one"
selfbroken=0
for c in bad1 bad2 bad3 bad4 good; do
  cat "$WORK/prelude.ts" "$WORK/$c.impl.ts" "$WORK/assert.ts" > "$WORK/$c.run.ts"
  out=$(run_ts "$WORK/$c.run.ts"); rc=$?
  if [ $rc -eq 90 ]; then
    printf '%s\n' "$out" | sed 's/^/    /'
    echo "· cannot transpile the frozen $c — unavailable"; exit 2
  fi
  case "$c" in
    bad1) want=1; label="F-0329 injection — mockOr(null), no explicit generic" ;;
    bad2) want=1; label="F-0272 verbatim — mock branch resolves null" ;;
    bad3) want=1; label="fixture with no milestones — the demo contract is empty" ;;
    bad4) want=1; label="the demo fixture leaks into LIVE mode" ;;
    good) want=0; label="the shipped fixture" ;;
  esac
  if [ $want -eq 1 ] && [ $rc -eq 0 ]; then
    echo "  ACCEPTED a known-bad: $label"; printf '%s\n' "$out" | sed 's/^/    /'; selfbroken=1
  fi
  if [ $want -eq 0 ] && [ $rc -ne 0 ]; then
    echo "  REJECTED the known-good: $label — this table would fail a correct fix"
    printf '%s\n' "$out" | sed 's/^/    /'; selfbroken=1
  fi
done
if [ $selfbroken -eq 1 ]; then
  echo "· THIS GATE CANNOT BE TRUSTED: its own assertion table did not separate the F-0272 defect"
  echo "  from the F-0272 fix. Refusing to report a verdict about the real code from a check that"
  echo "  has just proved itself blind."
  echo "VERDICT: broken — the F-0272 gate's assertions no longer detect F-0272 (F-0329)"
  echo "NOT CHECKED: the real product code — this run never got that far"
  exit 1
fi
echo "  good — all four known-bad rejected, known-good accepted, so a green below means something"

# ---------------------------------------------------------------------------
# 3 · locate the real contracts.get by PARSE, not by line window, and RUN it.
# ---------------------------------------------------------------------------
echo "· contracts.get, located in $A by the TypeScript parser (no line window) and EXECUTED in both modes"
if ! node "$EXTRACT" "$A_CODE" contracts get "$TSABS" --as contractsGet > "$WORK/real.impl.ts" 2> "$WORK/extract.err"; then
  erc=$?
  sed 's/^/  /' "$WORK/extract.err"
  if [ $erc -eq 3 ]; then
    echo "· api.ts no longer exports a `contracts` object with a `get` member. F-0272 is about what"
    echo "  that member resolves; if it is gone, the demo contract flow has no fetch at all."
    echo "VERDICT: broken (F-0272 regressed) — contracts.get is gone"
    echo "NOT CHECKED: the rest of the demo contract flow — its entry point does not exist"
    exit 1
  fi
  echo "· could not parse $A to locate contracts.get — unavailable"; exit 2
fi
cat "$WORK/prelude.ts" "$WORK/real.impl.ts" "$WORK/assert.ts" > "$WORK/real.run.ts"
out=$(run_ts "$WORK/real.run.ts"); rc=$?
printf '%s\n' "$out" | sed 's/^/  /'
if [ $rc -eq 90 ]; then echo "· cannot transpile the extracted contracts.get — unavailable"; exit 2; fi
if [ $rc -ne 0 ]; then
  echo "VERDICT: broken (F-0272 regressed) — the offline demo's contract fetch does not resolve a"
  echo "         signable contract, so DealContractTab renders its unavailable state and Sign is"
  echo "         disabled for the whole walkthrough"
  echo "NOT CHECKED: the rendered panel — the record that feeds it is already wrong"
  exit 1
fi

echo "VERDICT: aligned (proved) — contracts.get was LOCATED BY PARSE (the old 3781-line awk window"
echo "         is gone) and EXECUTED: in mock mode it resolves a non-null contract that echoes the"
echo "         requested id, carries a non-empty milestone schedule whose amounts sum to the stated"
echo "         totalAmount, is unsigned by both parties and makes no network call; in live mode it"
echo "         issues exactly one GET /contracts/:id with the caller's role and returns the SERVER"
echo "         record, so the fixture cannot leak past the F-0237 guarantee. The assertion table was"
echo "         proved able to reject the F-0272 defect, the F-0329 no-generic injection, an empty"
echo "         fixture and a fixture that leaks into live — and to accept a correct fix — before"
echo "         any of that was believed."
echo "NOT CHECKED: whether the fixture's VALUES are plausible to a viewer (only that they are"
echo "             internally consistent); whether Sign actually enables at RUNTIME — this gate"
echo "             executes the client, not DealContractTab, and there is no vitest suite in the"
echo "             tree covering that panel's demo path; that isApiLive()/API_MODE reports in a"
echo "             deployed build what isLive() reports here; the other contracts.* members"
echo "             (list/generate/sign/pdf), which have their own mock branches this gate does"
echo "             not execute."
exit 0
