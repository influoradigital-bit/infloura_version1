#!/usr/bin/env bash
# gates/F-0050-static-view-components.sh — origin: F-0050 (components-created-in-render),
# repaired under F-0335 (gate-is-name-locked).
#
# THE DEFECT. BoardView/ListView/TimelineView were defined INSIDE BrandPipelinePage's body
# and mounted as <BoardView/> JSX. A function literal declared in a render body is a fresh
# function IDENTITY on every render of the parent, and React compares element types by
# identity — so it does not update that subtree, it unmounts it and mounts a new one. All
# state below is discarded: focus lost, inputs cleared mid-typing, effects re-run. The fixed
# shape (verified 2026-08-15) is lowercase render HELPERS invoked as plain calls,
# `boardView()`, which have no separate fiber identity at all.
#
# ─── F-0335: WHY THIS FILE WAS REWRITTEN ──────────────────────────────────────────────
# This gate survived the F-0329 injection sweep and was correctly left alone. A third probe
# then found it NAME-LOCKED. Every one of its three checks enumerated the three identifiers
# the original record happened to name:
#     grep -qE "^[[:space:]]+(function (BoardView|ListView|TimelineView)\b|const (Board...
# Adding `const KanbanView = () => (` to the render body and mounting it as <KanbanView />
# reintroduces the SAME DEFECT CLASS under a name the list does not contain, and the gate
# exited 0, VERDICT aligned. Observed, not assumed:
#     .proof-os/tasks/T-F0335/F-0335.inject.log
# A gate can be simultaneously SOUND against its recorded instance and BLIND to the class
# that instance belongs to. Only the second probe distinguishes them.
#
# The old gate also carried a latent FALSE RED that nobody had triggered: its second check
# failed on any `<BoardView/>` mount at all, so hoisting the component to module scope —
# which is the fix record F-0050 itself prescribes — would have been reported as the defect.
# That check is gone.
#
# ─── WHAT THIS GATE NOW DECIDES ───────────────────────────────────────────────────────
# "Declared inside a render body" is a SCOPE question, and no grep can answer a scope
# question. So the detection is delegated to gates/_nested_component.js, which parses the
# file with the TypeScript compiler this project already ships (the approach gates/
# _pii_guard.js and gates/_f0240_chain.py established) and reports a finding when ALL of:
#   1. the binding is a FUNCTION OR CLASS LITERAL (arrow / function expression / function
#      declaration / class) — not an alias like `const StageIcon = stage.icon`, and not the
#      result of a call like useMemo/useCallback/memo/forwardRef;
#   2. it is declared inside an enclosing function that CONTAINS JSX — a render body.
#      Module scope is not a render body;
#   3. its name is MOUNTED AS A JSX TAG inside that same scope. The mount is what makes it
#      a component rather than a helper.
# No identifier is enumerated. The names come off the AST.
#
# ─── THE FALSE-RED DIRECTION IS GUARDED DELIBERATELY ──────────────────────────────────
# A class-detector that reds on ordinary React is WORSE than the name-lock, because someone
# will switch it off. Six KNOWN-GOOD fixtures are frozen next to the five KNOWN-BADs under
# gates/fixtures/F-0335/ and every one of them is pushed through this gate's own detector
# before the real tree is looked at: a module-scope component mounted as JSX (the prescribed
# fix), the render-helper shape the page uses today, useMemo/useCallback returning elements
# plus a memoised component binding, a named render prop and a map callback, a dynamic
# component ALIAS (`const StageIcon = stage.icon` — what brand-pipeline.tsx really does at
# line 471), and a correct file whose comments quote the forbidden shape verbatim (F-0266).
#
# ─── SELF-FALSIFICATION (F-0273/F-0319 device) ────────────────────────────────────────
# The gate runs that whole table FIRST. If a known-bad passes, this gate cannot fail and it
# exits 1 saying so rather than reporting a verdict about real code. If a known-good fails,
# the detector is untrustworthy in the other direction and it exits 1 saying that instead.
# Either way it refuses to certify from a check that has just proved itself broken.
#
# LAW: exit 1 = real finding · 2 = cannot run · 0 = proved.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-$SELF/../..}" 2>/dev/null || { echo "· cannot reach repo root — unavailable"; exit 2; }

# F-0266: gates read CODE, not file bytes. The TypeScript AST is already comment-immune (a
# comment is not a node), but the analyser is fed the gates/_code.sh stripped view as well,
# so both mechanisms have to agree before anything is believed. code_ready must run in the
# gate's OWN shell — it creates the temp dir the views live in.
. "$SELF/_code.sh" 2>/dev/null || { echo "· gates/_code.sh unreadable — unavailable"; exit 2; }
code_ready || { echo "· $(code_why) — unavailable"; exit 2; }

F=src/pages/brand-pipeline.tsx
ANALYSER="$SELF/_nested_component.js"
FIXDIR="$SELF/fixtures/F-0335"
TSDIR=node_modules/typescript

[ -f "$F" ]        || { echo "· $F missing — unavailable"; exit 2; }
[ -f "$ANALYSER" ] || { echo "· $ANALYSER missing — unavailable"; exit 2; }
[ -d "$FIXDIR" ]   || { echo "· $FIXDIR missing — this gate has no falsification table — unavailable"; exit 2; }
[ -f "$TSDIR/package.json" ] || { echo "· $TSDIR absent — cannot parse TSX — unavailable"; exit 2; }
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }

# scan <path> — run the detector over the CODE VIEW of <path>, reporting the real path.
# Echoes the analyser output; returns its exit code (2 = could not analyse).
scan() {
  _sp="$1"
  _sv=$(code_view "$_sp") || { echo "$(code_why)"; return 2; }
  node "$ANALYSER" "$_sv" "$TSDIR" --display "$_sp" 2>&1
  return $?
}

# ---------------------------------------------------------------------------
# 1 · SELF-FALSIFICATION. Prove the detector can fail, and prove it does not
#     fire on ordinary React, BEFORE looking at the product code.
# ---------------------------------------------------------------------------
echo "· self-check: the frozen known-bad / known-good table under gates/fixtures/F-0335"
bads=0; goods=0; broke=0; blind=0
for fx in "$FIXDIR"/bad-*.tsx; do
  [ -f "$fx" ] || continue
  bads=$((bads + 1))
  out=$(scan "$fx"); rc=$?
  if [ $rc -eq 2 ]; then printf '  %s\n' "$out" | sed 's/^/  /'; echo "· cannot analyse a fixture — unavailable"; exit 2; fi
  if [ $rc -ne 1 ]; then
    echo "  BLIND on $(basename "$fx") — this defect was NOT detected (exit $rc)"
    printf '%s\n' "$out" | sed 's/^/      /'
    blind=1
  fi
done
for fx in "$FIXDIR"/good-*.tsx; do
  [ -f "$fx" ] || continue
  goods=$((goods + 1))
  out=$(scan "$fx"); rc=$?
  if [ $rc -eq 2 ]; then printf '  %s\n' "$out" | sed 's/^/  /'; echo "· cannot analyse a fixture — unavailable"; exit 2; fi
  if [ $rc -ne 0 ]; then
    echo "  FALSE RED on $(basename "$fx") — ordinary, correct React was reported as the defect"
    printf '%s\n' "$out" | sed 's/^/      /'
    broke=1
  fi
done
if [ $bads -lt 5 ] || [ $goods -lt 6 ]; then
  echo "· the falsification table has shrunk (${bads} known-bad, ${goods} known-good; expected >= 5 and >= 6)."
  echo "  A gate whose own table has been thinned out is not a gate — unavailable"
  exit 2
fi
if [ $blind -eq 1 ]; then
  echo "· THIS GATE CANNOT FAIL: its own detector passed a frozen known-bad. Refusing to report"
  echo "  a verdict about the real code from a check that has just proved itself blind."
  echo "VERDICT: broken — the F-0050 detector no longer detects F-0050's class (F-0335)"
  echo "NOT CHECKED: the real product code — this run never got that far"
  exit 1
fi
if [ $broke -eq 1 ]; then
  echo "· THIS GATE IS NOT TRUSTWORTHY: it reds on ordinary React. A class-detector that false-reds"
  echo "  is worse than the name-lock it replaced, because it will be disabled. Refusing to certify."
  echo "VERDICT: broken — the F-0050 detector false-reds on correct React (F-0335)"
  echo "NOT CHECKED: the real product code — this run never got that far"
  exit 1
fi
echo "  good — ${bads}/${bads} known-bad shapes detected, ${goods}/${goods} known-good shapes clean,"
echo "  so a green below means something"

fail=0

# ---------------------------------------------------------------------------
# 2 · the real tree.
# ---------------------------------------------------------------------------
echo "· $F, parsed: no function/class literal declared in a render body is mounted as JSX"
out=$(scan "$F"); rc=$?
printf '%s\n' "$out" | sed 's/^/  /'
if [ $rc -eq 2 ]; then echo "· could not analyse $F — unavailable"; exit 2; fi
[ $rc -ne 0 ] && fail=1

# ---------------------------------------------------------------------------
# 3 · ANCHOR: the page must still HAVE a three-way view switch. Without this an
#     emptied file passes trivially — nothing declared in a render body means
#     nothing wrong with it. Deliberately shape-agnostic: it asserts the switch
#     exists, not how each branch is rendered, so hoisting the views to module
#     scope (the fix F-0050 prescribes) stays green.
# ---------------------------------------------------------------------------
echo "· anchor: the board/list/timeline view switch still exists"
F_CODE=$(code_view "$F") || { echo "· $(code_why) — unavailable"; exit 2; }
for v in board list timeline; do
  if ! grep -qE "viewMode === '$v'" "$F_CODE"; then
    echo "  ABSENT: no \`viewMode === '$v'\` branch — the page's view switch has changed shape."
    echo "  This gate's subject may have moved; re-inspect rather than trusting the green above."
    fail=1
  fi
done
[ $fail -eq 0 ] && echo "  all three branches present"

if [ $fail -ne 0 ]; then
  echo "VERDICT: broken — a component is being created inside a render body and mounted as JSX;"
  echo "         its subtree remounts on every parent render and loses its state (F-0050/F-0335)"
  exit 1
fi

echo "VERDICT: aligned (proved) — brand-pipeline.tsx was PARSED, not grepped: no function or class"
echo "         literal declared inside any render body in the file is mounted as a JSX tag, whatever"
echo "         it is called. The detector was proved falsifiable against ${bads} frozen defect shapes"
echo "         (including the original three-identifier F-0050 and the KanbanView rename that walked"
echo "         past the old gate) and proved silent on ${goods} frozen known-goods (module-scope"
echo "         components, render helpers, useMemo/useCallback elements, render props, map callbacks,"
echo "         dynamic component aliases, and a comment quoting the defect) before this file was read."
echo "NOT CHECKED: components created during render in OTHER files — this gate is one file; a nested"
echo "             component passed as a PROP rather than mounted as a tag (<Route element={Panel}/>);"
echo "             a nested component wrapped in a call the analyser cannot see through"
echo "             (React.memo(() => <div/>) inside a render body IS still unstable and is treated as"
echo "             stable here, a deliberate trade for zero false reds); a component defined in a"
echo "             custom hook that contains no JSX of its own; whether the surviving render helpers"
echo "             are heavy enough to need memoization; and runtime state-loss behaviour — nothing"
echo "             here observes a real remount, only the shape that causes one."
exit 0
