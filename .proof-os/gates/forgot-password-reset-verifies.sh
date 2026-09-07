#!/usr/bin/env bash
# Gate for F-0693 (recovery-succeeds-account-still-locked) and F-0698
# (missing-status-gate-on-credential-rotation). One gate, because both are the same subject:
# what resetPassword is allowed to do to an account. They are also mutually constraining — a
# fix for either one alone can break the other — so they must be checked together or not at all.
#
# The failure this exists to prevent: AuthService.resetPassword persisted the new password hash
# but left the account at emailVerified=false / status=PENDING_VERIFICATION, so the API answered
# "Password reset successfully" and the very next login threw EMAIL_NOT_VERIFIED 403. Every
# surface reported success and the account stayed locked, with forgot-password — the one flow
# that can still reach a user who never got their verification mail — leading nowhere.
#
# Why the existing tests did not catch it: both pre-existing resetPassword cases built their user
# with creatorUser(true), an ALREADY-VERIFIED account, so the unverified branch was never
# executed. The gap was invisible to a green suite, which is exactly the shape this gate exists
# to keep closed.
#
# NOTE ON THE COMMAND: this repository has no aggregator/reactor pom — influora-api/pom.xml is
# the only pom, confirmed by an independent check. `mvn -pl influora-api test` therefore dies with
# "Could not find the selected project in the reactor" and never reaches surefire. The command
# must run with cwd inside the module. Do not "simplify" this back to -pl.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/influora-api"
# Covers the whole resetPassword contract, not just the one case this gate was opened for:
#   F-0693  testResetPasswordVerifiesPendingAccount   — a completed reset must leave the account
#                                                       loginable (verified + ACTIVE)
#   F-0698  testResetPasswordSuspendedAccount         — a locked account must NOT rotate its
#           testResetPasswordDeactivatedAccount         password, and must leave no trace
#           testResetPasswordActiveAccountUnaffected   — and the guard must refuse ONLY those two
#                                                       states, so "reject everything" cannot pass
# The last one is what keeps the two halves from being satisfiable at the same time by a wrong
# fix: without it, a guard that refused every reset would green the suspended cases while
# silently breaking the recovery path F-0693 exists to protect.
TEST='AuthServiceTest#testResetPassword*'

if [ ! -f "$MODULE/pom.xml" ]; then
  echo "UNAVAILABLE: no pom at $MODULE/pom.xml"
  exit 2
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "UNAVAILABLE: mvn not on PATH — this gate cannot report green without running"
  exit 2
fi

cd "$MODULE" || exit 2

# -o (offline) matches how this project's suite is run locally; drop it in CI if the local
# repository is not warm.
if ! out="$(mvn -o -q test -Dtest="$TEST" -DfailIfNoTests=true 2>&1)"; then
  echo "BROKEN: the resetPassword contract regressed. Either a completed reset no longer leaves"
  echo "        the account loginable (F-0693), or a SUSPENDED/DEACTIVATED account is no longer"
  echo "        refused, or the guard now refuses a reset it should allow (F-0698)."
  echo "$out" | tail -25
  exit 1
fi

# -DfailIfNoTests=true above means a renamed or deleted test is a failure, not a silent pass:
# a gate that greens because its subject vanished is the failure mode this OS is built around.
echo "PROVED: resetPassword verifies+activates a pending account, and refuses ONLY locked ones ($TEST)"
exit 0
