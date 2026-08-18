#!/usr/bin/env bash
# F-0279-role-recovery-consumed.sh — gate for F-0279 (dead-recovery-api).
#
# F-0244 added `roleError` / `retryRole` to useWorkspaceVerification so a caller whose role
# fetch is unresolved (pending, rejected, or no `brand_user_id` to match) has a way to tell
# "unknown" apart from "confirmed non-admin" and a way to recover. The owner-lockout bug itself
# was cured by the separate `canVerify` fail-open logic, not by wiring up the recovery API — so
# `roleError` and `retryRole` were exposed on the hook's return type and never consumed by any
# of the three surfaces that call useWorkspaceVerification (campaign-form.tsx,
# WorkspaceVerificationBanner.tsx, brand-verification.tsx). Dead code: the symbols exist, no UI
# ever reads or calls them. Secondary defect: `retryRole: () => void role.refetch()` was a fresh
# closure literal in the return object every render.
#
# This gate asserts BOTH:
#   1. the hook itself stabilises `retryRole` (useCallback, not an inline closure literal), and
#   2. at least one real consumer (brand-verification.tsx — the actual KYC submission screen,
#      the highest-stakes surface for an unresolved role) actually BRANCHES on `roleError` and
#      actually CALLS `retryRole` from an event handler, not merely destructures both names.
#
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

HOOK=src/hooks/brand/useWorkspaceVerification.ts
HOOK_CODE=$(code_view "$HOOK") || { echo "$(code_why) - unavailable"; exit 2; }
CONSUMER=src/pages/brand-verification.tsx
CONSUMER_CODE=$(code_view "$CONSUMER") || { echo "$(code_why) - unavailable"; exit 2; }
TESTFILE=src/pages/__tests__/brand-verification.retry.test.tsx

for f in "$HOOK" "$CONSUMER"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

echo "· hook: WorkspaceVerification still exposes roleError + retryRole (F-0244 surface intact)"
if ! grep -qE '^\s*roleError:\s*boolean;' "$HOOK_CODE"; then
  echo "VERDICT: broken — useWorkspaceVerification no longer declares roleError on its return type"
  echo "         (F-0279 / regresses F-0244)"
  exit 1
fi
if ! grep -qE "^\s*retryRole:\s*\(\)\s*=>\s*void;" "$HOOK_CODE"; then
  echo "VERDICT: broken — useWorkspaceVerification no longer declares retryRole on its return type"
  echo "         (F-0279 / regresses F-0244)"
  exit 1
fi
echo "  clean — roleError / retryRole both still on WorkspaceVerification"

echo "· hook: retryRole is a stable reference (useCallback), not a fresh closure literal"
if ! grep -qE "^\s*import\s*\{[^}]*\buseCallback\b[^}]*\}\s*from\s*'react';" "$HOOK_CODE"; then
  echo "VERDICT: broken — $HOOK does not import useCallback; retryRole cannot be memoised (F-0279"
  echo "         secondary defect: fresh closure every render)"
  exit 1
fi
# The exact pre-fix shape was the closure written inline in the return object literal:
#   retryRole: () => void role.refetch(),
# A fix must move it to its own useCallback-bound const above the return.
if grep -qE "retryRole:\s*\(\)\s*=>\s*void\s+role\.refetch\(\)" "$HOOK_CODE"; then
  echo "VERDICT: broken — retryRole is still the inline '() => void role.refetch()' closure"
  echo "         literal in the return object; a new one is allocated every render (F-0279)"
  exit 1
fi
if ! grep -qE "const\s+retryRole\s*=\s*useCallback\(" "$HOOK_CODE"; then
  echo "VERDICT: broken — no 'const retryRole = useCallback(...)' found; retryRole is not"
  echo "         memoised (F-0279 secondary defect)"
  exit 1
fi
if ! grep -qE "^\s*retryRole,\s*$" "$HOOK_CODE"; then
  echo "VERDICT: broken — the return object does not reference the memoised retryRole binding"
  echo "         (expected a bare 'retryRole,' line, not an inline closure) (F-0279)"
  exit 1
fi
echo "  clean — retryRole is bound via useCallback and returned by reference"

echo "· consumer ($CONSUMER): destructures roleError AND retryRole"
if ! grep -qE "useWorkspaceVerification\(\)" "$CONSUMER_CODE"; then
  echo "VERDICT: broken — $CONSUMER does not call useWorkspaceVerification() at all (F-0279)"
  exit 1
fi
# The destructure may wrap across lines (Prettier line-length), so pull a small window ending
# at the `useWorkspaceVerification()` call line rather than requiring it all on one line.
call_lineno=$(grep -nE 'useWorkspaceVerification\(\)' "$CONSUMER_CODE" | head -1 | cut -d: -f1)
window_start=$((call_lineno > 4 ? call_lineno - 4 : 1))
destructure_window=$(sed -n "${window_start},${call_lineno}p" "$CONSUMER_CODE")
if ! printf '%s\n' "$destructure_window" | grep -qE '\broleError\b'; then
  echo "VERDICT: broken — $CONSUMER never destructures roleError from useWorkspaceVerification()"
  echo "         (F-0279: recovery API not consumed)"
  exit 1
fi
if ! printf '%s\n' "$destructure_window" | grep -qE '\bretryRole\b'; then
  echo "VERDICT: broken — $CONSUMER never destructures retryRole from useWorkspaceVerification()"
  echo "         (F-0279: recovery API not consumed)"
  exit 1
fi
echo "  clean —"
printf '%s\n' "$destructure_window" | sed 's/^/    /'

echo "· consumer: roleError actually gates a rendered branch (not just destructured and unused)"
if ! grep -qE '\broleError\s*&&|\broleError\s*\?|!roleError\b' "$CONSUMER_CODE"; then
  echo "VERDICT: broken — roleError is destructured in $CONSUMER but never appears in a"
  echo "         conditional — a consumer that destructures a symbol and never reads it again is"
  echo "         exactly the wrong fix this gate rejects (F-0279)"
  exit 1
fi
echo "  clean — roleError drives a conditional branch"

echo "· consumer: retryRole is actually invoked from an event handler, not merely named"
# Reject the specific wrong fix: a destructure with no call site anywhere else in the file.
# Look at every FULL line mentioning retryRole outside the destructure window itself, and
# require at least one to actually call it — 'retryRole()' or 'onClick={retryRole}' (passed
# by reference). Keeping the full line (not a trimmed -o extraction) matters: an extraction
# starting at the word boundary would drop a leading 'onClick={' and never match.
retry_lines=$(grep -nE '\bretryRole\b' "$CONSUMER_CODE")
other_lines=$(printf '%s\n' "$retry_lines" \
  | awk -F: -v s="$window_start" -v e="$call_lineno" '{ln=$1+0; if (ln < s || ln > e) print}')
call_sites=$(printf '%s\n' "$other_lines" | grep -cE 'retryRole\(\)|onClick=\{retryRole\}')
if [ "${call_sites:-0}" -lt 1 ]; then
  echo "  destructured but no call site found (checked for retryRole() or onClick={retryRole})"
  echo "VERDICT: broken — retryRole is destructured in $CONSUMER but never called from anywhere;"
  echo "         the symbol appearing is not the same as the recovery actually working (F-0279)"
  exit 1
fi
echo "  clean — retryRole is wired to a real call site ($call_sites match(es))"

# T-BRANDOPEN-0817 self-check: a wrong fix that renders a retry control with an EMPTY handler
# (onClick={() => {}}) would pass every grep leg above (roleError branches something, retryRole
# is "called" nowhere near an empty arrow) only if the empty-arrow variant doesn't literally
# contain the substrings checked above — confirm no such empty-handler decoy sits next to a
# roleError branch pretending to be the real thing.
if grep -qE 'roleError' "$CONSUMER_CODE" && grep -qE 'onClick=\{\(\)\s*=>\s*\{\s*\}\}' "$CONSUMER_CODE"; then
  echo "VERDICT: broken — an empty-bodied onClick handler exists alongside a roleError branch;"
  echo "         confirm the retry control's handler is not this decoy (F-0279)"
  exit 1
fi

command -v npx >/dev/null 2>&1 || { echo "· npx not on PATH — unavailable"; exit 2; }
[ -f package.json ] || { echo "· package.json missing — unavailable"; exit 2; }
[ -f "$TESTFILE" ] || { echo "· $TESTFILE missing — unavailable"; exit 2; }

BUDGET="${PROOF_F0279_VITEST_TIMEOUT:-180}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 15 $BUDGET"; else TO=""; fi

echo "· vitest run $TESTFILE (budget ${BUDGET}s)"
out=$(cd "$ROOT" && $TO npx vitest run "$TESTFILE" 2>&1); rc=$?

if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: everything below this line — the suite did not finish"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: broken — $TESTFILE does not pass; the retry affordance this gate asserts"
  echo "         statically is not actually exercised end to end (F-0279)"
  exit 1
fi
echo "  suite green — $TESTFILE"

echo "VERDICT: aligned (proved) — retryRole is memoised with useCallback in the hook, and"
echo "         brand-verification.tsx destructures roleError/retryRole, branches real UI on"
echo "         roleError, and actually calls retryRole from a click handler; $TESTFILE passes"
echo "NOT CHECKED: whether campaign-form.tsx / VerificationRequiredBox.tsx or"
echo "             WorkspaceVerificationBanner.tsx also consume roleError/retryRole (they were"
echo "             left on canVerify-only fail-open behaviour — canVerify is derived to fail"
echo "             open whenever roleError is true, so their existing behaviour is still"
echo "             correct, just without the extra retry affordance); a live role-fetch failure"
echo "             actually recovering against a real backend (only a running API proves that,"
echo "             this gate mocks the query client)."
exit 0
