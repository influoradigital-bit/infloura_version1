#!/usr/bin/env bash
# origin: kavya's 9 promoted items + F-0013/F-0017 (eslint-9 / no-config false reds)
# LAW: tool-cannot-run => exit 2 (unavailable). exit 1 ONLY for real findings.
set -u; cd "${1:-.}"; fail=0; unavail=0
run(){ echo "· $1"; }
if command -v npx >/dev/null && [ -f package.json ]; then
  if [ -f tsconfig.json ]; then
  run "tsc --noEmit"
  out=$(npx tsc --noEmit 2>&1); rc=$?
  if [ $rc -ne 0 ]; then
    if echo "$out" | grep -qiE 'not found|could not determine|Cannot find module .typescript|TS18003'; then
      echo "  tsc unavailable"; unavail=1
    else echo "$out" | head -10; fail=1; fi
  fi
  else run "tsc skipped (no tsconfig.json) — unavailable"; unavail=1; fi
  run "eslint (sage rules, flat config)"
  out=$(npx eslint . --config "$(dirname "$0")/eslint.sage.mjs" --no-config-lookup 2>&1); rc=$?
  if [ $rc -ge 2 ]; then
    out=$(ESLINT_USE_FLAT_CONFIG=false npx eslint . -c "$(dirname "$0")/eslint.sage.json" --no-eslintrc 2>&1); rc=$?
  fi
  if [ $rc -ge 2 ]; then echo "  eslint tool failure (exit $rc) — unavailable"; unavail=1
  elif [ $rc -ne 0 ]; then
    if echo "$out" | grep -qiE 'invalid option|unrecognized|could not find (a )?config|no eslint configuration'; then
      out2=$(npx eslint . 2>&1); rc2=$?
      if [ $rc2 -eq 0 ]; then echo "  (project config used — believed, not proved)"; unavail=1
      elif echo "$out2" | grep -qiE 'could not find|no.*config'; then echo "  eslint has no usable config here — unavailable"; unavail=1
      else echo "$out2" | head -10; fail=1; fi
    else echo "$out" | head -10; fail=1; fi
  fi
else run "tsc/eslint UNAVAILABLE (no node project here)"; unavail=1; fi
if command -v gitleaks >/dev/null; then
  run "gitleaks"; gitleaks detect --no-banner -s . || fail=1
else run "gitleaks UNAVAILABLE"; unavail=1; fi
run "grep: raw hex colors outside tokens"
! grep -rEn --include='*.tsx' '#[0-9a-fA-F]{6}' src app components 2>/dev/null | grep -v tokens || fail=1
[ $fail -eq 1 ] && { echo "VERDICT: broken (real findings above)"; exit 1; }
[ $unavail -eq 1 ] && { echo "VERDICT: partial — some oracles could not run (believed, not proved)"; exit 2; }
echo "VERDICT: aligned (proved)"; exit 0
