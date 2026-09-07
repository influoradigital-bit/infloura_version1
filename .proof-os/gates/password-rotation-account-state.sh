#!/usr/bin/env bash
# Gate for F-0702 (reset-token-outlives-account) and F-0703
# (missing-status-gate-on-credential-rotation-repair).
#
# Sibling to forgot-password-reset-verifies.sh, which already covers AuthServiceTest#testResetPassword*
# (and therefore the F-0702 service-side guard, since that test matches the pattern). This one covers
# the two things that gate does NOT reach:
#
#   F-0703  AuthServiceTest#testChangePassword*   — changePassword rotated a password hash with no
#                                                   SUSPENDED/DEACTIVATED check. Includes the
#                                                   ordering test: a caller who cannot supply the
#                                                   current password must get INVALID_CURRENT_PASSWORD
#                                                   and never learn the account's status, so moving
#                                                   the guard above the matches() check fails here.
#   F-0702  AccountControllerTest                 — the CALL SITE of purgePasswordResetTokens. The
#                                                   service method being correct proves nothing if
#                                                   the delete path never calls it, and before this
#                                                   AccountController had no test class at all.
#   F-0702  PasswordResetTokenRepositoryDeleteByUserIdTest
#                                                 — the QUERY itself, on real Hibernate + H2.
#                                                   deleteByUserId is a Spring Data DERIVED query:
#                                                   a name that does not parse fails at context
#                                                   startup, not at compile time, and every mocked
#                                                   test stays green. Nothing else here executes it
#                                                   (the Testcontainers classes are the standing
#                                                   Skipped: 15 and need Docker).
#
# All three mutations were run against these tests and each was caught:
#   remove the changePassword guard          -> 2 failures ("nothing was thrown")
#   remove the resetPassword deletedAt check -> 1 failure  (covered by the sibling gate's pattern)
#   remove the purge call from the controller-> 2 failures ("Wanted but not invoked")
#
# NOTE ON THE COMMAND: this repository has no aggregator pom — influora-api/pom.xml is the only one,
# so `mvn -pl influora-api test` dies in the reactor and never reaches surefire. cwd must be inside
# the module. Do not "simplify" this to -pl.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/influora-api"
TESTS='AuthServiceTest#testChangePassword*,AccountControllerTest,PasswordResetTokenRepositoryDeleteByUserIdTest'

if [ ! -f "$MODULE/pom.xml" ]; then
  echo "UNAVAILABLE: no pom at $MODULE/pom.xml"
  exit 2
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "UNAVAILABLE: mvn not on PATH — this gate cannot report green without running"
  exit 2
fi

cd "$MODULE" || exit 2

if ! out="$(mvn -o -q test -Dtest="$TESTS" -DfailIfNoTests=true 2>&1)"; then
  echo "BROKEN: an account-state guard on password rotation regressed. Either a locked account can"
  echo "        change its password again, or the status leaked to a caller who did not supply the"
  echo "        current password (F-0703), or the delete path stopped purging reset tokens so a"
  echo "        mailed link outlives the account again (F-0702), or deleteByUserId stopped resolving."
  echo "$out" | tail -25
  exit 1
fi

# -DfailIfNoTests=true means a renamed or deleted test is a failure, not a silent pass. That matters
# more than usual here: AccountControllerTest is the ONLY coverage of the delete endpoint, so if it
# vanishes there is nothing else to notice.
echo "PROVED: locked accounts cannot rotate passwords, status does not leak, and account deletion purges reset tokens ($TESTS)"
exit 0
