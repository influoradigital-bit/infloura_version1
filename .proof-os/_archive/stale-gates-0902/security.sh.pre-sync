#!/usr/bin/env bash
# origin: F-0003 (kabir's teeth) + F-0015 (no-lockfile false red)
# LAW: tool-cannot-run => exit 2 (unavailable/believed). exit 1 is ONLY for real findings.
set -u; cd "${1:-.}"; fail=0; unavail=0
if [ -f package-lock.json ] || [ -f yarn.lock ] || [ -f pnpm-lock.yaml ]; then
  if command -v npm >/dev/null; then
    echo "· npm audit --audit-level=high"
    out=$(npm audit --audit-level=high 2>&1); rc=$?
    if [ $rc -ne 0 ]; then
      if echo "$out" | grep -qiE 'ENOLOCK|requires.*lockfile|ENOTFOUND|network|registry'; then
        echo "  audit could not run (env issue) — unavailable"; unavail=1
      else echo "$out" | tail -5; fail=1; fi
    fi
  else echo "· npm UNAVAILABLE"; unavail=1; fi
else echo "· no lockfile — npm audit cannot run here (unavailable, NOT a finding)"; unavail=1; fi
if command -v gitleaks >/dev/null; then
  echo "· gitleaks"; gitleaks detect --no-banner -s . || fail=1
else echo "· gitleaks UNAVAILABLE"; unavail=1; fi
if [ -n "${2:-}" ]; then
  echo "· headers on $2"
  h=$(curl -sI --max-time 10 "$2" 2>/dev/null) || { echo "  unreachable — unavailable"; unavail=1; h=""; }
  if [ -n "$h" ]; then for want in "strict-transport-security" "x-content-type-options"; do
    echo "$h" | grep -qi "$want" || { echo "  missing header: $want"; fail=1; }; done; fi
fi
[ $fail -eq 1 ] && { echo "VERDICT: broken (real findings above)"; exit 1; }
[ $unavail -eq 1 ] && { echo "VERDICT: partial — some oracles could not run (believed, not proved)"; exit 2; }
echo "VERDICT: aligned (proved)"; exit 0
