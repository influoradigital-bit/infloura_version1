#!/usr/bin/env bash
# gates/F-0678-no-import-time-timer.sh — instance gate, closes F-0678.
#
# origin: F-0678 (flaky-suite-exit-code) — `npm test` exited 1 with every assertion green, 2 of 3
# full runs. Root cause: `src/lib/scroll/smooth-scroll.ts` called gsap.registerPlugin(ScrollTrigger)
# at MODULE SCOPE. ScrollTrigger.register() ends by arming `_syncInterval = setInterval(_sync, 250)`,
# cleared only by ScrollTrigger.disable(), which nothing called. Because this module is in @/App's
# import graph, merely IMPORTING the app armed a 250ms interval that outlives jsdom's window; each
# post-teardown tick ran a bare requestAnimationFrame and threw an unhandled ReferenceError.
#
# TWO EARLIER "FIXES" WERE WRONG AND THIS GATE EXISTS BECAUSE OF THEM. A mounted-ref guard in
# collaboration-reviews-panel.tsx addressed a misreported cause. A requestAnimationFrame shim in
# src/test/setup.ts was DEAD CODE: vitest's jsdom defaults pretendToBeVisual:true, so jsdom always
# defines rAF and the shim's `typeof === 'undefined'` guard is permanently false. Both were
# reported as fixes. So this gate pins the MECHANISM — no import-time timer — not the symptom.
#
# WHY NOT "run npm test N times and count exit 0". Because that measurement is unreliable here for
# a SECOND, unrelated reason (F-0678's own investigation established it): a concurrent session
# writing to src/ mid-run makes the suite exit 1 with every assertion green, from nothing to do
# with this defect. A repeat-count gate would flap on that and teach the team to ignore it.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

SRC=src/lib/scroll/smooth-scroll.ts
TEST="src/lib/scroll/__tests__/smooth-scroll-import-side-effect.f0678.test.ts"
[ -f "$SRC" ] || { echo "· $SRC missing — unavailable"; exit 2; }

# --- CHECK A: no registerPlugin at module scope (column 0 = top level) -------------------------
if grep -nE "^gsap\.registerPlugin|^\s{0}gsap\.registerPlugin" "$SRC" | grep -qvE "^\s*[0-9]+:\s*\*"; then
  grep -nE "^gsap\.registerPlugin" "$SRC"
  echo "VERDICT: broken — registerPlugin runs at module scope again; importing the app arms"
  echo "         ScrollTrigger's 250ms _syncInterval (F-0678)"
  exit 1
fi
if ! grep -q "registerPlugin" "$SRC"; then
  echo "· registerPlugin is gone entirely — that is not this fix; ScrollTrigger would be"
  echo "  unregistered rather than lazily registered — unavailable, NOT a pass"
  exit 2
fi
echo "· registerPlugin exists and is not at module scope"

# --- CHECK B: the regression test must be git-tracked (F-0324, five waves running) -------------
if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
  git ls-files --error-unmatch "$TEST" >/dev/null 2>&1 || {
    echo "VERDICT: broken — $TEST is not git-tracked; it proves nothing on a fresh clone (F-0324)"
    exit 1; }
  echo "· regression test is git-tracked"
else
  echo "· not a git repo — TRACKING NOT CHECKED"
fi

# --- CHECK C: behaviour — the test asserts no 250ms timer and no register() at import ----------
command -v npx >/dev/null 2>&1 || { echo "· npx unavailable — structure checked, BEHAVIOUR NOT PROVED"; exit 2; }
[ -f package.json ] || { echo "· no package.json here — BEHAVIOUR NOT PROVED"; exit 2; }
[ -f "$TEST" ] || { echo "VERDICT: broken — the F-0678 regression test is gone"; exit 1; }
log=$(mktemp 2>/dev/null || echo "/tmp/f0678.$$")
npx vitest run --reporter=basic "$TEST" >"$log" 2>&1; trc=$?
if grep -qE "Cannot find module|Failed to load|ERR_MODULE_NOT_FOUND" "$log"; then
  echo "· the suite could not load — unavailable, NOT a pass"; tail -12 "$log"; exit 2
fi
if [ "$trc" -ne 0 ]; then
  echo "VERDICT: broken — the F-0678 regression test does not pass"
  sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -E "×|expected|Tests " | head -12
  exit 1
fi
sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -E "Tests |Test Files " | head -2
grep -q "250" "$TEST" || { echo "VERDICT: broken — the test no longer names the 250ms interval, so"; echo "         a green run proves nothing about the mechanism (F-0678)"; exit 1; }
echo "· the test still pins the 250ms interval specifically"

# --- CHECK D: setup.ts must not re-claim the dead rAF shim as the fix --------------------------
if [ -f src/test/setup.ts ] && grep -q "requestAnimationFrame" src/test/setup.ts; then
  if ! grep -q "does NOT fix F-0678" src/test/setup.ts; then
    echo "VERDICT: broken — src/test/setup.ts's rAF shim has lost the correction saying it does"
    echo "         NOT fix F-0678; the next engineer will re-trust dead code (F-0678)"
    exit 1
  fi
  echo "· setup.ts still carries the correction that its rAF shim is not the fix"
fi

echo "VERDICT: aligned (proved) — importing the app arms no repeating timer: registerPlugin is"
echo "         deferred out of module scope, the tracked regression test pins both the absent"
echo "         250ms interval and the un-called register(), and setup.ts keeps the correction"
echo "         that its rAF shim is dead code rather than the fix."
echo "NOT CHECKED: that \`npm test\` now exits 0 reliably — deliberately, see the header: a"
echo "             concurrent session writing to src/ mid-run produces exit 1 with every"
echo "             assertion green from an unrelated cause, so a repeat-count assertion here"
echo "             would flap. F-0682 (destroySmoothScroll never clears _syncInterval) is a"
echo "             LIVE latent path this gate does not cover: nothing calls initSmoothScroll()"
echo "             in tests today, and the first test that does re-arms the same flake."
exit 0
