#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# csp-violation-reporter.sh   (F-1789)
#
# Exit 0 = proved · 1 = broken · 2 = unavailable (never green)
#
# Real-user CSP violation reporting for the F-1787 rollout. Without it, stage 2
# (an ENFORCED policy) is switched on blind.
#
# Pins, via src/lib/csp-violation-reporter.test.ts:
#   - no query string / fragment / matrix param ever leaves the page; documentURI
#     and referrer are never read (a real browser DOES put the query string in
#     blockedURI -- verified 2026-09-22 with a real SecurityPolicyViolationEvent)
#   - report vs enforce stays distinguishable (stage 1 vs stage 2)
#   - dedupe by origin + 10-per-page cap; no recursion on our own endpoint
#   - violations buffered by public/csp-violation-buffer.js before the app
#     boots are drained, and that buffer never copies documentURI/referrer
# And statically:
#   - the buffer is the FIRST <script> in index.html (GTM's site-tags.js runs
#     before the app bundle, so a later listener misses GTM's violations)
#   - both F-1787 snippets carry 'report-sample' (without it a report cannot
#     say WHICH inline script was blocked)
#
# NOT covered: that the live nginx actually serves those snippets (F-1787 is
# applied by hand on the server -- curl it).
# ---------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/../.." || { echo "GATE 2: cannot reach project root"; exit 2; }

for f in src/lib/csp-violation-reporter.ts src/lib/csp-violation-reporter.test.ts \
         public/csp-violation-buffer.js index.html \
         deploy/utho/nginx/influora-security-headers.conf \
         deploy/utho/nginx/influora-security-headers.enforce.conf; do
  [ -f "$f" ] || { echo "GATE 2: missing $f"; exit 2; }
done
command -v node >/dev/null 2>&1 || { echo "GATE 2: node not on PATH"; exit 2; }

fail=0

first_script="$(grep -o '<script[^>]*>' index.html | head -1)"
if echo "$first_script" | grep -q 'src="/csp-violation-buffer.js"'; then
  echo "  ok: the CSP buffer is the first <script> in index.html"
else
  echo "  BROKEN: first <script> in index.html is not the CSP buffer: $first_script"
  fail=1
fi

for f in deploy/utho/nginx/influora-security-headers.conf deploy/utho/nginx/influora-security-headers.enforce.conf; do
  if grep -q "script-src 'self' 'report-sample'" "$f"; then
    echo "  ok: report-sample present in $(basename "$f")"
  else
    echo "  BROKEN: $(basename "$f") script-src lacks 'report-sample'"
    fail=1
  fi
done

LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT
NO_COLOR=1 FORCE_COLOR=0 node node_modules/vitest/vitest.mjs run src/lib/csp-violation-reporter.test.ts > "$LOG" 2>&1
rc=$?
sed -i 's/\x1b\[[0-9;]*m//g' "$LOG"
if grep -qiE "out of memory" "$LOG"; then echo "GATE 2: node ran out of memory"; exit 2; fi
if [ "$rc" -ne 0 ]; then
  echo "  BROKEN: reporter tests failed (vitest exit $rc)"; grep -E "×|FAIL" "$LOG" | head -8; fail=1
elif ! grep -qE "Tests +[0-9]+ passed" "$LOG"; then
  echo "GATE 2: vitest reported success but counted no passing tests"; exit 2
else
  echo "  ok: reporter -- $(grep -oE "Tests +[0-9]+ passed" "$LOG" | tail -1)"
fi

[ "$fail" -ne 0 ] && { echo "GATE 1: CSP violation reporting regressed"; exit 1; }
echo "GATE 0: CSP violation reporting pinned (live nginx not checked -- see header)"
exit 0
