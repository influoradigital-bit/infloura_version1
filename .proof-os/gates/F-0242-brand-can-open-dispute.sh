#!/usr/bin/env bash
# gates/F-0242-brand-can-open-dispute.sh
# origin failure: F-0242 (capability-parity-gap) — brand-disputes.tsx told the brand to open a
# dispute "in the relevant deal room" while no such control existed and no brand-role client
# method for POST /deals/:dealId/disputes had ever been written.
#
# F-0329 (this gate's OWN defect, repaired here). Ledger record F-0242 is CLOSED against this
# file, and this file could not fail on the thing that matters most about the fix. Its role leg
# was
#     awk '/export const brandDisputes/…' "$A_CODE" | grep -q "role: 'brand'"
# — a substring search over the WHOLE brandDisputes object. `brandDisputes.list` already carries
# `role: 'brand'`, so that one neighbouring line satisfied the grep forever, no matter what
# `open` sent. Rewriting open as
#     open: (dealId, reason) => creatorDisputes.open(dealId, reason)
# — the exact "DRY" refactor brandDisputes' own doc-comment warns against, which sends the
# creator JWT slot and puts the brand back to being unable to escalate a money dispute — left
# this gate at exit 0, VERDICT: aligned. Reproduced at
# .proof-os/tasks/T-F0329-GATES/F-0242.inject.log.
#
# A second leg was dead outright: `grep -q "Opening a dispute is intentionally NOT wired here"`
# ran against A_CODE, the COMMENT-STRIPPED view, so the stale comment it forbids can never
# appear there. It has been rewritten to look at the file's comments, which is where a comment is.
#
# THE CLASS. Every old leg asked WHERE A TOKEN APPEARS. What F-0242 is actually about is what
# goes ON THE WIRE when the brand presses the control, and whether a control exists to press.
# So this gate no longer greps the request: it EXTRACTS creatorDisputes and brandDisputes out of
# api.ts, RUNS both `open` methods against a recording http stub, and checks the method, path,
# role and body that come back — with creatorDisputes.open as a live negative control, so the
# table has to actually discriminate the two roles rather than accept anything.
#
# And because a harness can rot the way those greps did, the gate first runs the very same
# assertion table against three KNOWN-BAD implementations frozen into this file, and one
# KNOWN-GOOD one, and REFUSES TO CERTIFY unless it rejects all three bad and accepts the good.
# A gate that cannot fail is worse than no gate; one that cannot pass is a false-red machine.
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
A=src/lib/api.ts
D=src/pages/brand-chat.tsx
TESTFILE=src/lib/brand-disputes-api.test.ts
[ -f "$A" ] && [ -f "$D" ] || { echo "· source files missing — unavailable"; exit 2; }
A_CODE=$(code_view "$A") || { echo "$(code_why) - unavailable"; exit 2; }
D_CODE=$(code_view "$D") || { echo "$(code_why) - unavailable"; exit 2; }

WIRE="$SELF/_dispute_wire.js"
[ -f "$WIRE" ] || { echo "· gates/_dispute_wire.js missing — unavailable"; exit 2; }
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
TSDIR=node_modules/typescript
[ -f "$TSDIR/package.json" ] || { echo "· $TSDIR absent — unavailable"; exit 2; }
TSABS="$(cd "$TSDIR" && pwd)"

WORK="$(mktemp -d 2>/dev/null)" || { echo "· cannot create a scratch dir — unavailable"; exit 2; }
trap 'rm -rf "$WORK" 2>/dev/null' EXIT
fail=0

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
  echo "  — cannot execute the dispute clients — unavailable"; exit 2
fi
run_ts() {
  if [ "$RUN_MODE" = strip ]; then
    node --experimental-strip-types --no-warnings "$1" 2>&1
  else
    node_modules/.bin/esbuild --format=cjs --platform=node --log-level=error "$1" > "$1.cjs" 2>/dev/null \
      || { echo "esbuild could not transpile the extracted dispute clients"; return 90; }
    node "$1.cjs" 2>&1
  fi
}

# ---------------------------------------------------------------------------
# 1 · the stubs the extracted clients close over, and the assertion table.
# ---------------------------------------------------------------------------
cat > "$WORK/prelude.ts" <<'TSEOF'
let LIVE = false;
const calls: any[] = [];
const isLive = () => LIVE;
const http = {
  request: (method: any, urlPath: any, opts?: any) => {
    calls.push({ method, path: urlPath, role: opts && opts.role, body: opts && opts.body });
    return Promise.resolve({ id: 'srv_1' });
  },
};
async function mockOr(value: any) { return value; }
const mockDisputeRows = (_r: any) => [{ id: 'd1' }];
const mockEligibleDeals: any[] = [];
TSEOF

cat > "$WORK/assert.ts" <<'TSEOF'
declare const brandDisputes: any;
declare const creatorDisputes: any;

const bad: string[] = [];
function check(name: string, cond: boolean, detail: string) {
  if (!cond) bad.push(name + ' — ' + detail);
}

async function main() {
  // ---- 1. THE DEFECT ITSELF: the brand opening a dispute must POST the shared endpoint
  //         with the BRAND role, because `role` is what selects the JWT slot.
  LIVE = true; calls.length = 0;
  try {
    await brandDisputes.open('D1', 'goods never arrived');
  } catch (e) {
    bad.push('brandDisputes.open threw: ' + (e as Error).message);
  }
  if (calls.length !== 1) {
    check('brand open issues exactly one request', false, 'issued ' + calls.length);
  } else {
    const c = calls[0];
    check('brand open method', c.method === 'POST', 'got ' + c.method);
    check('brand open path', c.path === '/deals/D1/disputes', 'got ' + c.path);
    check('brand open ROLE', c.role === 'brand',
      'got role=' + JSON.stringify(c.role) + ' — this is the F-0242 defect: the request goes ' +
      'out on the wrong session JWT, so the brand still cannot escalate');
    check('brand open forwards the reason',
      !!(c.body && c.body.reason === 'goods never arrived'),
      'got body=' + JSON.stringify(c.body));
  }

  // ---- 2. NEGATIVE CONTROL. The creator variant must still send 'creator'. Without this the
  //         table above could be satisfied by any implementation that hardcodes 'brand'
  //         everywhere, and would not be discriminating roles at all.
  LIVE = true; calls.length = 0;
  try {
    await creatorDisputes.open('D1', 'x');
  } catch (e) {
    bad.push('creatorDisputes.open threw: ' + (e as Error).message);
  }
  check('creator open still sends the creator role',
    calls.length === 1 && calls[0].role === 'creator',
    'got ' + JSON.stringify(calls.map((c) => c.role)));

  // ---- 3. brandDisputes.list must not have drifted onto the creator slot either.
  LIVE = true; calls.length = 0;
  try {
    await brandDisputes.list();
  } catch (e) {
    bad.push('brandDisputes.list threw: ' + (e as Error).message);
  }
  check('brand list sends the brand role',
    calls.length === 1 && calls[0].role === 'brand' && calls[0].method === 'GET',
    'got ' + JSON.stringify(calls[0]));

  // ---- 4. offline demo: opening a dispute in mock mode must resolve without touching the
  //         network, so the click-through walkthrough is not dead.
  LIVE = false; calls.length = 0;
  let mockRes: any = null;
  try {
    mockRes = await brandDisputes.open('D1', 'why');
  } catch (e) {
    bad.push('brandDisputes.open threw in mock mode: ' + (e as Error).message);
  }
  check('mock open makes no request', calls.length === 0, 'issued ' + calls.length);
  check('mock open resolves an id', !!(mockRes && mockRes.id), 'got ' + JSON.stringify(mockRes));

  if (bad.length) {
    for (const b of bad) console.log('  x ' + b);
    console.log('  ' + bad.length + ' dispute-client assertion(s) failed');
    process.exit(1);
  }
  console.log('  all dispute-client assertions correct (brand POST role=brand, creator role=creator, mock offline)');
  process.exit(0);
}
void main();
TSEOF

# extract_obj <name> <src> <out> — the whole `export const <name> = { … };` block.
extract_obj() {
  awk -v pat="^export const $1 = \\\\{" '$0 ~ pat {f=1} f{print} f&&/^\};/{exit}' "$2" \
    | sed 's/^export const /const /' > "$3"
  [ -s "$3" ] && grep -q '^};' "$3"
}

# ---------------------------------------------------------------------------
# 2 · SELF-FALSIFICATION.
# ---------------------------------------------------------------------------
GOOD_CREATOR='const creatorDisputes = {
  list: () => isLive() ? http.request("GET", "/creator/disputes", { role: "creator" }) : mockOr(mockDisputeRows("creator")),
  open: (dealId: string, reason: string) =>
    isLive()
      ? http.request("POST", `/deals/${dealId}/disputes`, { role: "creator", body: { reason } })
      : mockOr({ id: "dsp_new" }),
};'

mk_case() { printf '%s\n%s\n' "$GOOD_CREATOR" "$1" > "$WORK/$2.impl.ts"; }

# BAD 1 — the F-0329 injection: brandDisputes.open delegates to the creator helper.
mk_case 'const brandDisputes = {
  list: () => isLive() ? http.request("GET", "/brand/disputes/list", { role: "brand" }) : mockOr(mockDisputeRows("brand")),
  open: (dealId: string, reason: string) => creatorDisputes.open(dealId, reason),
};' bad1
# BAD 2 — the role is simply wrong on open, while list still carries role: "brand".
mk_case 'const brandDisputes = {
  list: () => isLive() ? http.request("GET", "/brand/disputes/list", { role: "brand" }) : mockOr(mockDisputeRows("brand")),
  open: (dealId: string, reason: string) =>
    isLive()
      ? http.request("POST", `/deals/${dealId}/disputes`, { role: "creator", body: { reason } })
      : mockOr({ id: "dsp_new" }),
};' bad2
# BAD 3 — F-0242 verbatim: brandDisputes has no open method at all.
mk_case 'const brandDisputes = {
  list: () => isLive() ? http.request("GET", "/brand/disputes/list", { role: "brand" }) : mockOr(mockDisputeRows("brand")),
};' bad3
# GOOD — the shipped shape.
mk_case 'const brandDisputes = {
  list: () => isLive() ? http.request("GET", "/brand/disputes/list", { role: "brand" }) : mockOr(mockDisputeRows("brand")),
  open: (dealId: string, reason: string) =>
    isLive()
      ? http.request("POST", `/deals/${dealId}/disputes`, { role: "brand", body: { reason } })
      : mockOr({ id: "dsp_new" }),
};' good

echo "· self-check: these assertions reject three known-bad dispute clients and accept a known-good one"
selfbroken=0
for c in bad1 bad2 bad3 good; do
  cat "$WORK/prelude.ts" "$WORK/$c.impl.ts" "$WORK/assert.ts" > "$WORK/$c.run.ts"
  out=$(run_ts "$WORK/$c.run.ts"); rc=$?
  if [ $rc -eq 90 ]; then
    printf '%s\n' "$out" | sed 's/^/    /'
    echo "· cannot transpile the frozen $c — unavailable"; exit 2
  fi
  case "$c" in
    bad1) want=1; label="F-0329 injection — brandDisputes.open delegates to creatorDisputes.open" ;;
    bad2) want=1; label="open sends role: 'creator' while list still says 'brand'" ;;
    bad3) want=1; label="F-0242 verbatim — brandDisputes has no open method" ;;
    good) want=0; label="the shipped shape" ;;
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
  echo "· THIS GATE CANNOT BE TRUSTED: its own assertion table did not separate the F-0242 defect"
  echo "  from the F-0242 fix. Refusing to report a verdict about the real code from a check that"
  echo "  has just proved itself blind."
  echo "VERDICT: broken — the F-0242 gate's assertions no longer detect F-0242 (F-0329)"
  echo "NOT CHECKED: the real product code — this run never got that far"
  exit 1
fi
echo "  good — all three known-bad rejected, known-good accepted, so a green below means something"

# ---------------------------------------------------------------------------
# 3 · RUN the real clients out of api.ts.
# ---------------------------------------------------------------------------
echo "· creatorDisputes + brandDisputes, EXTRACTED from $A and EXECUTED against a recording http stub"
extract_obj creatorDisputes "$A_CODE" "$WORK/real.creator.ts" || {
  echo "· could not isolate a complete \`export const creatorDisputes = { … };\` block in $A"
  echo "  — refusing to guess at a partial object — unavailable"; exit 2; }
extract_obj brandDisputes "$A_CODE" "$WORK/real.brand.ts" || {
  echo "· could not isolate a complete \`export const brandDisputes = { … };\` block in $A."
  echo "  F-0242 is precisely the absence of a brand-role dispute client, so this is a finding,"
  echo "  not an unavailable."
  echo "VERDICT: broken (F-0242 regressed) — no brandDisputes client to execute"
  echo "NOT CHECKED: the deal-room control — there is no client for it to call"
  exit 1; }
cat "$WORK/prelude.ts" "$WORK/real.creator.ts" "$WORK/real.brand.ts" "$WORK/assert.ts" > "$WORK/real.run.ts"
out=$(run_ts "$WORK/real.run.ts"); rc=$?
printf '%s\n' "$out" | sed 's/^/  /'
if [ $rc -eq 90 ]; then echo "· cannot transpile the extracted clients — unavailable"; exit 2; fi
[ $rc -ne 0 ] && fail=1
if [ $fail -eq 1 ]; then
  echo "VERDICT: broken (F-0242 regressed) — the brand's dispute request does not go out as the"
  echo "         brand, so a brand with money on hold still cannot escalate"
  echo "NOT CHECKED: the deal-room control and the vitest leg below — the client they depend on"
  echo "             is already wrong"
  exit 1
fi

# ---------------------------------------------------------------------------
# 4 · the control itself. F-0242 was a MISSING CONTROL, not only a missing method.
# ---------------------------------------------------------------------------
echo "· $D — a rendered control must reach api.brandDisputes.open, and the creator variant must not be called"
out=$(node "$WIRE" "$D_CODE" "$TSABS" 2>&1); rc=$?
printf '%s\n' "$out" | sed "s|$(printf '%s' "$D_CODE" | sed 's/[][\.*^$/]/\\&/g')|$D|g"
if [ $rc -eq 2 ]; then echo "· the wiring analyser could not analyse $D — unavailable"; exit 2; fi
if [ $rc -ne 0 ]; then
  echo "VERDICT: broken (F-0242 regressed) — the brand deal room has no control that reaches the"
  echo "         brand dispute client, which is the capability gap F-0242 was opened for"
  echo "NOT CHECKED: runtime reachability, both 409 renderings, backend authorization"
  exit 1
fi

# ---------------------------------------------------------------------------
# 5 · the stale doc-comment. This leg used to grep the COMMENT-STRIPPED view, where a
#     comment can never be — so it could not fire. It now looks at the real file.
# ---------------------------------------------------------------------------
if grep -q "Opening a dispute is intentionally NOT wired here" "$A"; then
  echo "· the stale 'not wired' comment is back in $A and contradicts the shipped method"
  echo "VERDICT: broken (F-0242 regressed) — the client's own documentation denies the capability"
  echo "         it now has; the next reader will believe the comment"
  echo "NOT CHECKED: runtime reachability, both 409 renderings, backend authorization"
  exit 1
fi

# ---------------------------------------------------------------------------
# 6 · the project's own suite for this client.
# ---------------------------------------------------------------------------
if [ -f "$TESTFILE" ]; then
  echo "· vitest: $TESTFILE"
  if [ ! -f node_modules/.bin/vitest ]; then
    echo "· node_modules/.bin/vitest not found — unavailable"
    echo "NOT CHECKED: the project suite. The execution and wiring legs above DID pass."
    exit 2
  fi
  BUDGET="${PROOF_F0242_VITEST_TIMEOUT:-240}"
  if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
  tout=$($TO node_modules/.bin/vitest run "$TESTFILE" --reporter=basic 2>&1); trc=$?
  if [ $trc -eq 124 ] || [ $trc -eq 137 ]; then
    echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
    echo "NOT CHECKED: the project suite; the execution and wiring legs above DID pass"
    exit 2
  fi
  if [ $trc -ne 0 ]; then
    printf '%s\n' "$tout" | tail -30
    echo "VERDICT: broken — the brand dispute client's own suite fails (F-0242)"
    echo "NOT CHECKED: live behaviour against a running backend"
    exit 1
  fi
  printf '%s\n' "$tout" | grep -E "Tests |Test Files " | sed 's/^/  /' || true
  echo "  suite green"
else
  echo "· no $TESTFILE in the tree — the execution leg above is this gate's only runtime evidence"
fi

echo "VERDICT: aligned (proved) — brandDisputes and creatorDisputes were EXTRACTED from api.ts and"
echo "         EXECUTED: the brand's open() issues exactly one POST /deals/:id/disputes carrying"
echo "         role='brand' and the caller's reason, the creator's still carries role='creator'"
echo "         (negative control, so the table really discriminates the two JWT slots), the brand"
echo "         list stays on the brand slot, and mock mode resolves offline without a request. The"
echo "         deal room was PARSED: a rendered onClick reaches the brand client, and the creator"
echo "         variant is not called there. The assertion table was proved able to reject the"
echo "         F-0242 defect, the F-0329 delegation injection and a wrong-role open — and to"
echo "         accept a correct fix — before any of that was believed."
echo "NOT CHECKED: whether the control is reachable at RUNTIME (the wiring leg proves a source"
echo "             wire, not that the button renders, is enabled, or is not behind a condition"
echo "             that never holds); whether either documented 409 (NO_FUNDED_ESCROW,"
echo "             DISPUTE_ALREADY_OPEN) renders understandably; backend authorization; whether"
echo "             the JWT actually stored in the brand slot is valid; dispute-open controls on"
echo "             brand surfaces other than $D."
exit 0
