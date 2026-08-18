#!/usr/bin/env bash
# gates/F-0239-no-fake-money-progress.sh
# origin failure: F-0239 (fake-money-action) — runContractAnimation stepped a non-dismissable
# modal through "Locking escrow funds" then "Generating contract from proposal" on setTimeout,
# in LIVE mode, with no server call. The brand was shown a confirmation that money had been
# locked and a contract created; neither request was ever made from this component.
#
# F-0329 (this gate's OWN defect, repaired here). Ledger record F-0239 is CLOSED against this
# file, and this file could not fail. Its whole live-mode leg was two greps:
#     grep -q  "runContractAnimation"        -> must be absent
#     grep -q  "runDemoContractAnimation"    -> must be present
# Neither says anything about WHERE the animation is called from. The demo path legitimately
# keeps the definition and one call site, so `runDemoContractAnimation` is present forever;
# adding a THIRD occurrence inside the live branch — i.e. reintroducing F-0239 verbatim —
# left this gate at exit 0, VERDICT: aligned. That is the F-0319 shape: a token that a
# still-correct part of the file satisfies permanently, standing in for a behaviour.
# Reproduced at .proof-os/tasks/T-F0329-GATES/F-0239.inject.log.
#
# THE REPAIR. A gate that asserts A TOKEN APPEARS is satisfied by any line that carries the
# token. The only assertion an accident of text cannot satisfy is an assertion about
# BEHAVIOUR. So this gate no longer greps for the animation's name. It EXTRACTS the accept
# handler and the progress animation out of the source and RUNS them, twice — once with
# isApiLive() true and once false — inside a recording sandbox that logs every request, every
# open of the progress dialog, every step advance, and whether that advance happened inside a
# setTimeout callback. The finding is then stated as the record states it: in LIVE mode the
# escrow/contract progress modal must not be driven by a clock, and must not appear at all
# unless an escrow-or-contract request was actually issued.
#
# And because a harness can rot the way those greps did, the gate first runs the very same
# assertions against a KNOWN-BAD implementation (F-0239 verbatim, frozen into this file) and
# REFUSES TO CERTIFY unless those assertions fail. A gate that cannot fail is worse than no
# gate; this one proves it can, on every run, before it believes itself.
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

F=src/components/brand/deals/deal-room-dashboard.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
F_CODE=$(code_view "$F") || { echo "$(code_why) - unavailable"; exit 2; }

command -v node >/dev/null 2>&1 || {
  echo "· node not on PATH — cannot execute the accept handler — unavailable"; exit 2; }

WORK="$(mktemp -d 2>/dev/null)" || { echo "· cannot create a scratch dir — unavailable"; exit 2; }
trap 'rm -rf "$WORK" 2>/dev/null' EXIT
fail=0

# ---------------------------------------------------------------------------
# 0 · the recording sandbox + the assertion table.
#
#     The fragment is run as a plain script inside node's vm with a Proxy global,
#     so every identifier the component closes over resolves to a recorder rather
#     than having to be enumerated here. Anything the fragment calls that is
#     DEFINED IN THE COMPONENT but was not extracted is reported as a blind spot
#     and the run reports UNAVAILABLE — never a green over code it could not see.
# ---------------------------------------------------------------------------
cat > "$WORK/harness.js" <<'JSEOF'
'use strict';
const fs = require('fs');
const vm = require('vm');

const fragPath = process.argv[2];
const localsPath = process.argv[3];
const label = process.argv[4] || 'fragment';
const frag = fs.readFileSync(fragPath, 'utf8');
const locals = new Set(
  fs.existsSync(localsPath)
    ? fs.readFileSync(localsPath, 'utf8').split('\n').map((s) => s.trim()).filter(Boolean)
    : [],
);
// identifiers the fragment itself defines are not blind spots
for (const m of frag.matchAll(/^\s*(?:const|let|var|function|async function)\s+([A-Za-z_$][\w$]*)/gm)) {
  locals.delete(m[1]);
}

async function runOnce(live) {
  const log = [];
  const blind = new Set();
  let timers = [];
  let inTimer = false;
  let seq = 0;

  const record = (name) => {
    log.push({ t: 'call', name, inTimer });
    const base = String(name).split('.')[0];
    if (locals.has(base)) blind.add(base);
  };

  const mkStub = (name) =>
    new Proxy(function () {}, {
      get(t, prop) {
        if (typeof prop === 'symbol' || prop === 'then' || prop === 'inspect') return undefined;
        return mkStub(name + '.' + String(prop));
      },
      apply() {
        record(name);
        return Promise.resolve(mkStub(name + '()'));
      },
      construct() {
        record(name);
        return mkStub(name + '()');
      },
    });

  const known = {
    React: { useCallback: (fn) => fn, useMemo: (fn) => fn(), useRef: () => ({ current: null }) },
    isApiLive: () => live,
    selectedDeal: { id: 'deal-1', status: 'PROPOSAL', campaignName: 'c', creatorName: 'k' },
    navigate: (to) => log.push({ t: 'nav', to, inTimer }),
    setTimeout: (fn, ms) => { timers.push({ fn, ms: Number(ms) || 0, seq: seq++ }); return seq; },
    clearTimeout: () => {},
    queueMicrotask: (fn) => Promise.resolve().then(fn),
    setShowContractGeneratingDialog: (v) => log.push({ t: 'modal', open: !!v, inTimer }),
    setContractGenerationStep: (n) => log.push({ t: 'step', n: Number(n), inTimer }),
    setShowProposalDialog: () => {},
    setActionLoading: () => {},
    setActionError: (m) => log.push({ t: 'error', m }),
    setActionMessage: (m) => log.push({ t: 'message', m }),
    loadDeals: () => { log.push({ t: 'call', name: 'loadDeals', inTimer }); return Promise.resolve(); },
    loadMessages: () => { log.push({ t: 'call', name: 'loadMessages', inTimer }); return Promise.resolve(); },
    Promise, console, Number, String, Boolean, Object, Array, Math, JSON, Date, Error, Symbol,
  };

  const ctx = vm.createContext(
    new Proxy(known, {
      has: () => true,
      get(t, prop) {
        if (prop in t) return t[prop];
        if (typeof prop === 'symbol') return undefined;
        return mkStub(String(prop));
      },
      set(t, p, v) { t[p] = v; return true; },
    }),
  );

  const script = frag + '\n;__entry = handleAcceptProposal;\n';
  try {
    vm.runInContext(script, ctx, { timeout: 10000 });
  } catch (e) {
    return { fatal: 'the extracted fragment could not be evaluated: ' + e.message };
  }
  const entry = ctx.__entry;
  if (typeof entry !== 'function') {
    return { fatal: 'handleAcceptProposal is not a function after evaluating the fragment' };
  }
  try {
    await entry();
  } catch (e) {
    return { fatal: 'handleAcceptProposal threw: ' + e.message };
  }
  // drain microtasks, then fire pending timers oldest-delay-first, a bounded number
  // of rounds so a self-rescheduling timer cannot hang the gate.
  for (let round = 0; round < 6 && timers.length; round++) {
    for (let i = 0; i < 10; i++) await Promise.resolve();
    const due = timers.sort((a, b) => a.ms - b.ms || a.seq - b.seq);
    timers = [];
    inTimer = true;
    for (const t of due) { try { t.fn(); } catch { /* the component's own error path */ } }
    inTimer = false;
  }
  for (let i = 0; i < 10; i++) await Promise.resolve();
  return { log, blind: [...blind] };
}

// THE ASSERTIONS. One table, applied to whichever fragment is handed to this harness.
function assess(log) {
  const calls = log.filter((e) => e.t === 'call').map((e) => e.name);
  const moneyCalls = calls.filter((n) => /escrow|contract/i.test(n));
  const opened = log.some((e) => e.t === 'modal' && e.open);
  const timerSteps = log.filter((e) => e.t === 'step' && e.inTimer && e.n > 0);
  const problems = [];
  if (calls.length === 0) {
    problems.push(
      'LIVE accept issued no request at all — either the live branch is itself fabricated, or ' +
      'this harness never actually executed it',
    );
  }
  if (opened) {
    if (timerSteps.length > 0) {
      problems.push(
        'the escrow/contract progress modal is opened in LIVE mode and advanced ' +
        timerSteps.length + ' step(s) from inside setTimeout — money movement confirmed to the ' +
        'brand by a clock, not by a server (F-0239 verbatim)',
      );
    }
    if (moneyCalls.length === 0) {
      problems.push(
        'the progress modal claiming "Locking escrow funds" / "Generating contract" is opened in ' +
        'LIVE mode, but no escrow-or-contract request was issued (requests seen: ' +
        (calls.join(', ') || 'none') + ')',
      );
    }
  }
  return { problems, calls, moneyCalls, opened, timerSteps: timerSteps.length };
}

(async () => {
  const liveRun = await runOnce(true);
  if (liveRun.fatal) { console.log('FATAL ' + liveRun.fatal); process.exit(2); }
  if (liveRun.blind.length) {
    console.log(
      'BLIND ' + label + ' calls component-local ' + liveRun.blind.join(', ') +
      ', which this run could not see inside',
    );
    process.exit(2);
  }
  const live = assess(liveRun.log);

  const mockRun = await runOnce(false);
  const mockOpened = !mockRun.fatal && mockRun.log.some((e) => e.t === 'modal' && e.open);
  const mockTimerSteps = mockRun.fatal
    ? 0
    : mockRun.log.filter((e) => e.t === 'step' && e.inTimer && e.n > 0).length;
  const mockCalls = mockRun.fatal
    ? []
    : mockRun.log.filter((e) => e.t === 'call').map((e) => e.name);

  console.log('  LIVE  requests: ' + (live.calls.join(', ') || 'none'));
  console.log('  LIVE  progress modal opened: ' + live.opened +
              '   steps advanced on a timer: ' + live.timerSteps);
  console.log('  MOCK  requests: ' + (mockCalls.join(', ') || 'none') +
              '   progress modal opened: ' + mockOpened +
              '   steps advanced on a timer: ' + mockTimerSteps);
  if (!mockOpened) {
    console.log('  note: the harness saw no progress modal in MOCK mode either — the positive ' +
                'control is silent this run (the self-check above is what proves falsifiability)');
  }
  for (const p of live.problems) console.log('x ' + p);
  process.exit(live.problems.length ? 1 : 0);
})();
JSEOF

# component-local identifiers, so the harness can tell "stubbed an import" from
# "stubbed a function of this component that I therefore could not see inside".
sed -n 's/^[[:space:]]*const \([A-Za-z_$][A-Za-z0-9_$]*\)[[:space:]]*=.*/\1/p' "$F_CODE" \
  | sort -u > "$WORK/locals.txt"

run_frag() { node "$WORK/harness.js" "$1" "$WORK/locals.txt" "$2" 2>&1; }

# ---------------------------------------------------------------------------
# 1 · SELF-FALSIFICATION. Before trusting the table above, prove it can fail:
#     run it against F-0239 verbatim — the animation fired from the live branch
#     after a real accept, exactly as the record describes it. If that passes,
#     this gate is the F-0329 shape again and must not certify anything.
# ---------------------------------------------------------------------------
echo "· self-check: these assertions reject a known-bad handler (F-0239 verbatim)"
cat > "$WORK/bad.js" <<'JSEOF'
const runDemoContractAnimation = React.useCallback(() => {
  setShowContractGeneratingDialog(true);
  setContractGenerationStep(0);
  const steps = [
    { delay: 800, step: 1 },
    { delay: 1200, step: 2 },
    { delay: 800, step: 3 },
    { delay: 600, step: 4 },
  ];
  let currentDelay = 0;
  steps.forEach(({ delay, step }) => {
    currentDelay += delay;
    setTimeout(() => setContractGenerationStep(step), currentDelay);
  });
  setTimeout(() => {
    setShowContractGeneratingDialog(false);
    navigate('/brand/contracts');
  }, currentDelay + 1000);
}, [navigate]);

const handleAcceptProposal = async () => {
  if (!selectedDeal) return;
  setShowProposalDialog(false);
  if (isApiLive()) {
    setActionLoading(true);
    try {
      await dealsApi.accept(selectedDeal.id, 'brand');
      await Promise.all([loadDeals(), loadMessages(selectedDeal.id)]);
      runDemoContractAnimation();
    } catch {
      setActionError('Could not accept the proposal. Try again.');
    } finally {
      setActionLoading(false);
    }
    return;
  }
  runDemoContractAnimation();
};
JSEOF
sc_out=$(run_frag "$WORK/bad.js" "the frozen F-0239 defect"); sc_rc=$?
printf '%s\n' "$sc_out" | sed 's/^/  /'
if [ $sc_rc -eq 2 ]; then
  echo "· the harness could not run its own frozen defect — unavailable"; exit 2
fi
if [ $sc_rc -eq 0 ]; then
  echo "· THIS GATE CANNOT FAIL: its own assertion table certified a handler that is the F-0239"
  echo "  defect verbatim. Refusing to report a verdict about the real code from a check that has"
  echo "  just proved itself blind."
  echo "VERDICT: broken — the F-0239 gate's assertions no longer detect F-0239 (F-0329)"
  echo "NOT CHECKED: the real product code — this run never got that far"
  exit 1
fi
echo "  good — the known-bad handler is rejected, so a green below means something"

# ---------------------------------------------------------------------------
# 2 · EXTRACT the real accept handler and the real animation, and RUN them.
#     This is the leg the two greps were standing in for.
# ---------------------------------------------------------------------------
echo "· handleAcceptProposal, EXECUTED in live mode and in mock mode"
awk '/^  const runDemoContractAnimation[ =]/{f=1} f{print} f&&/^  \}/{exit}' \
  "$F_CODE" > "$WORK/anim.js"
awk '/^  const handleAcceptProposal[ =]/{f=1} f{print} f&&/^  \}/{exit}' \
  "$F_CODE" > "$WORK/accept.js"

if [ ! -s "$WORK/accept.js" ]; then
  echo "· handleAcceptProposal not found as a component-level const in $F — the brand's accept"
  echo "  action has no single entry point this gate can execute"
  echo "VERDICT: broken — F-0239's entry point is gone (F-0239)"
  echo "NOT CHECKED: anything downstream — there is no handler left to run"
  exit 1
fi
if ! grep -q '^  \}' "$WORK/accept.js"; then
  echo "· could not isolate a complete handleAcceptProposal body (no closing brace at indent 2)"
  echo "  — refusing to guess at a partial handler — unavailable"; exit 2
fi
if [ ! -s "$WORK/anim.js" ]; then
  echo "  note: no runDemoContractAnimation in the source — that particular helper is gone"
  : > "$WORK/anim.js"
fi
cat "$WORK/anim.js" "$WORK/accept.js" > "$WORK/real.js"

# CLOSING THE RENAME HOLE. Extracting one hard-coded helper name is itself a
# token assumption: rename runDemoContractAnimation and the handler's call would
# resolve to a stub this gate cannot see inside. The harness reports exactly that
# as BLIND, so each round pulls the named component-local helpers into the
# fragment and runs again. Bounded, and if it still cannot see, it says so.
real_rc=0
for _round in 1 2 3 4; do
  real_out=$(run_frag "$WORK/real.js" "handleAcceptProposal"); real_rc=$?
  case "$real_out" in
    BLIND*) : ;;
    *) break ;;
  esac
  _names=$(printf '%s' "$real_out" | sed -n 's/^BLIND .*component-local \(.*\), which.*/\1/p' | tr -d ' ' | tr ',' ' ')
  [ -n "$_names" ] || break
  echo "  · pulling component-local helper(s) into the fragment: $_names"
  _added=0
  for _n in $_names; do
    awk -v n="$_n" '$0 ~ "^  (const|let|var|function|async function) " n "[ (=]" {f=1} f{print} f&&/^  \}/{exit}' \
      "$F_CODE" > "$WORK/helper.$_n.js"
    [ -s "$WORK/helper.$_n.js" ] && _added=1
  done
  [ $_added -eq 1 ] || break
  cat "$WORK"/helper.*.js "$WORK/anim.js" "$WORK/accept.js" > "$WORK/real.js"
done
printf '%s\n' "$real_out" | sed 's/^/  /'
if [ $real_rc -eq 2 ]; then
  echo "· the extracted handler could not be executed — unavailable"
  echo "NOT CHECKED: everything below — the behavioural leg is the point of this gate"
  exit 2
fi
[ $real_rc -ne 0 ] && fail=1

# ---------------------------------------------------------------------------
# 3 · the other half of F-0239: the modal was NON-DISMISSABLE. A brand shown a
#     fabricated money confirmation could not close it. Checked on the progress
#     dialog specifically, and then across the file.
# ---------------------------------------------------------------------------
echo "· the contract-progress dialog is dismissable"
if grep -q 'open={showContractGeneratingDialog}' "$F_CODE"; then
  DLG=$(grep -n 'open={showContractGeneratingDialog}' "$F_CODE" | head -1 | cut -d: -f1)
  WIN=$(sed -n "${DLG},$((DLG + 8))p" "$F_CODE")
  if ! printf '%s' "$WIN" | grep -q 'onOpenChange={'; then
    echo "· the contract-progress dialog has no onOpenChange — it cannot be closed"; fail=1
  fi
  if printf '%s' "$WIN" | grep -qE 'onOpenChange=\{\(\) => \{\}\}'; then
    echo "· the contract-progress dialog's onOpenChange is a no-op — it cannot be closed"; fail=1
  fi
  if printf '%s' "$WIN" | grep -q 'onInteractOutside={(e) => e.preventDefault()}'; then
    echo "· the contract-progress dialog blocks outside-interaction dismissal"; fail=1
  fi
  echo "  checked at line $DLG"
else
  echo "  note: no contract-progress dialog in the source — nothing to trap the brand in"
fi
if grep -qE 'onOpenChange=\{\(\) => \{\}\}' "$F_CODE"; then
  echo "· some dialog on this page is still non-dismissable (onOpenChange is a no-op)"; fail=1
fi

if [ $fail -eq 1 ]; then
  echo "VERDICT: broken — the brand is shown a money/contract confirmation this component never"
  echo "         asked the server for, or cannot dismiss it (F-0239)"
  echo "NOT CHECKED: the rendered dialog, and any other page that may fabricate the same claim"
  exit 1
fi

echo "VERDICT: aligned (proved) — handleAcceptProposal was EXECUTED with isApiLive() true and"
echo "         false inside a recording sandbox. In LIVE mode it issues a real request and does"
echo "         NOT open the escrow/contract progress modal, so no step of it can be advanced by"
echo "         a setTimeout; the modal, where it exists, is dismissable. The assertion table was"
echo "         proved falsifiable against the F-0239 defect before any of that was believed."
echo "NOT CHECKED: whether the live branch's success message matches what the server actually"
echo "             did; the rendered dialog (this gate executes the handler, it does not render);"
echo "             any OTHER handler on this page or another page that could open the same"
echo "             progress modal — only handleAcceptProposal is executed here; and whether a"
echo "             real POST /contracts elsewhere in the product actually succeeds."
exit 0
