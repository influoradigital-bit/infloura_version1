#!/usr/bin/env bash
# gates/F-0648-mfa-reset-session-revocation.sh
# origin: F-0648 (no-session-revocation) — "Resetting an admin's MFA writes mfaEnabled=false but
# never revokes AdminRefreshTokenRepository rows, so a compromised admin session keeps working
# until natural token expiry. SUPPORT-tier targets have zero session-side effect at all since
# requireMfaSatisfied does not gate that role."
#
# THE FIX: AdminAuthService.resetMfaForAdmin now calls
# adminRefreshTokenRepository.revokeAllForAdmin(target.getId()) after clearing MFA state, with no
# role branch above it — SUPPORT and privileged targets take the identical path. Reuses the exact
# mechanism AdminAuthService.logout() already uses for the caller's own id.
#
# FALSIFICATION, measured 2026-09-04: reverting the one-line call failed exactly the two new tests
# ("Wanted but not invoked: revokeAllForAdmin(...)"), the other 3 unaffected; restored, 5/5 green.
# Independently re-verified by a fresh-context review: revokeAllForAdmin sits below loadActive with
# no role branch (AdminAuthService.java:352); the repository method is a real @Modifying UPDATE
# setting revoked=true, and refresh only accepts findByTokenHashAndRevokedFalse — a revoked row
# genuinely cannot be used again, not merely marked.
#
# LAW: exit 0 proved, 1 broken, 2 unavailable. main-compile-first + clean build, per the standing
# rule adopted this session after a stale-.class false green.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

API=influora-api
[ -d "$API" ] || { echo "· $API not a directory — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }

BUDGET="${PROOF_F0648_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
echo "· mvn -o clean -Dtest=AdminAuthServiceTest test"
out=$($TO mvn -o -q clean -Dtest=com.influora.service.admin.AdminAuthServiceTest -f "$API/pom.xml" test 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if echo "$out" | grep -qiE "COMPILATION ERROR|cannot find symbol|does not exist"; then
  printf '%s\n' "$out" | tail -50
  echo "VERDICT: unavailable — could be this fix's own files or an unrelated concurrent edit"
  exit 2
fi
if echo "$out" | grep -qi "No tests were executed"; then
  printf '%s\n' "$out" | tail -20
  echo "VERDICT: unavailable — target class not found"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: broken — MFA reset no longer revokes the target's sessions for at least one role (F-0648)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests run:" | sed 's/^/  /'
echo "  green on a clean build"

echo "VERDICT: aligned (proved) — resetMfaForAdmin revokes the target's active refresh tokens for"
echo "         both a privileged-role target and a SUPPORT target."
echo "NOT CHECKED: live token revocation against a real database; whether an already-issued access"
echo "             token (not just the refresh token) is invalidated before its own natural expiry."
exit 0
