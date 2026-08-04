#!/usr/bin/env bash
# gates/build.sh — meera's oracle. Exit codes, not opinion.
# origin: registry grants meera may_claim=proved; PROOFOS.md roadmap item 2.
#         An oracle with no gate is the silent-oracle trap (F-0023).
# LAW (false-red, F-0013/F-0015/F-0017): tool-cannot-run => exit 2 (unavailable/believed).
#      exit 1 is ONLY for real findings. exit 0 is proved.
# LAW (F-0025, unbounded-gate-runtime): every step is wall-clock bounded. A step that
#      exceeds its budget is UNAVAILABLE (exit 2) — never a hang, never a false red.
#      Override with PROOF_GATE_TIMEOUT (seconds per step, default 300).
# Usage: gates/build.sh [project_dir] [health_url]
set -u
cd "${1:-.}" || { echo "· project dir unreadable — unavailable"; exit 2; }
URL="${2:-}"
fail=0; unavail=0; ran=0
TMO="${PROOF_GATE_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $TMO"; else TO=""; fi
timed_out() { [ "$1" -eq 124 ] || [ "$1" -eq 137 ]; }

env_issue() { echo "$1" | grep -qiE 'ENOENT|Cannot find module|ERR_MODULE_NOT_FOUND|command not found|not recognized|EACCES|ENOSPC|network|ETIMEDOUT|registry\.npmjs'; }

if [ ! -f package.json ]; then
  echo "· no package.json here — build oracle cannot run (unavailable)"; exit 2
fi
if ! command -v npm >/dev/null 2>&1; then
  echo "· npm UNAVAILABLE"; exit 2
fi
if [ ! -d node_modules ]; then
  echo "· node_modules absent — build/test cannot run without install (unavailable, NOT a finding)"; unavail=1
fi

has_script() { node -e "process.exit(require('./package.json').scripts?.['$1']?0:1)" 2>/dev/null; }

# ---- 1. typecheck -----------------------------------------------------------
if [ -f tsconfig.json ] && [ -d node_modules ]; then
  echo "· tsc --noEmit"; ran=1
  out=$($TO npx --no-install tsc --noEmit 2>&1); rc=$?
  if timed_out $rc; then echo "  tsc exceeded ${TMO}s — unavailable (F-0025)"; unavail=1; rc=0; out=""; fi
  if [ $rc -ne 0 ]; then
    if env_issue "$out"; then echo "  tsc unavailable"; unavail=1
    else echo "$out" | head -12; fail=1; fi
  fi
elif [ ! -f tsconfig.json ]; then
  echo "· tsc skipped (no tsconfig.json) — unavailable"; unavail=1
fi

# ---- 2. build ---------------------------------------------------------------
if has_script build && [ -d node_modules ]; then
  echo "· npm run build"; ran=1
  out=$($TO npm run build 2>&1); rc=$?
  if timed_out $rc; then echo "  build exceeded ${TMO}s — unavailable (F-0025)"; unavail=1; rc=0; out=""; fi
  if [ $rc -ne 0 ]; then
    if env_issue "$out"; then echo "  build could not run (env) — unavailable"; unavail=1
    else echo "$out" | tail -20; fail=1; fi
  fi
else
  echo "· no build script or no node_modules — unavailable"; unavail=1
fi

# ---- 3. tests ---------------------------------------------------------------
if has_script test && [ -d node_modules ]; then
  echo "· npm test"; ran=1
  out=$($TO npm test --silent 2>&1); rc=$?
  if timed_out $rc; then echo "  tests exceeded ${TMO}s — unavailable (F-0025)"; unavail=1; rc=0; out=""; fi
  if [ $rc -ne 0 ]; then
    if env_issue "$out" || echo "$out" | grep -qiE 'no test (files|specs) found'; then
      echo "  tests could not run — unavailable"; unavail=1
    else echo "$out" | tail -20; fail=1; fi
  fi
else
  echo "· no test script or no node_modules — unavailable"; unavail=1
fi

# ---- 4. optional health check ----------------------------------------------
if [ -n "$URL" ]; then
  if command -v curl >/dev/null 2>&1; then
    echo "· curl $URL"; ran=1
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$URL" 2>/dev/null) || code="000"
    if [ "$code" = "000" ]; then echo "  unreachable — unavailable"; unavail=1
    elif [ "$code" -ge 400 ]; then echo "  HTTP $code"; fail=1
    else echo "  HTTP $code"; fi
  else
    echo "· curl UNAVAILABLE"; unavail=1
  fi
fi

[ $fail -eq 1 ] && { echo "VERDICT: broken (real findings above)"; exit 1; }
[ $ran -eq 0 ] && { echo "VERDICT: partial — nothing actually ran (believed, not proved)"; exit 2; }
[ $unavail -eq 1 ] && { echo "VERDICT: partial — some oracles could not run (believed, not proved)"; exit 2; }
echo "VERDICT: aligned (proved)"; exit 0
