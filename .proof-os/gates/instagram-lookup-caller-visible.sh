#!/usr/bin/env bash
# Gate for F-0697 (unset-credential-degrades-silently).
#
# WHAT THIS GATE DOES AND DOES NOT CLOSE — read this before trusting a green.
#
# The class is "degrades SILENTLY". This gate proves the silence is gone: with the Instagram system
# caller unset, half-set, or the Meta app unconfigured, the application says so at boot and names
# the consequence. That is the detection half, and it is genuinely closed.
#
# It does NOT prove the credential is provisioned. META_SYSTEM_IG_USER_ID and
# META_SYSTEM_IG_ACCESS_TOKEN are still empty on every deploy, brand handle lookups still either
# borrow a connected creator's Meta token or return 503, and no test in this repository can see the
# contents of a VPS .env file. The live instance is tracked separately and the steps are in
# wiki/processes/OPS-F0697-instagram-system-caller.md. A green here means "an operator will now be
# told", not "an operator has acted" (RETENTION §4: closure closes the class, not the instances).
#
# Why the warning's CONTENT is asserted and not just its existence: the whole defect was that
# nothing said anything. A warning that fires but reads as "optional config absent" would reproduce
# the defect while greening a laxer gate — an operator skims it and moves on. The consequence line
# (creator-token borrowing, rate-limit starvation) is what makes it actionable, so the test pins it.
#
# Why the half-configured case is pinned separately: setting one variable of a pair is the failure
# an operator is most likely to CREATE and least likely to notice, because a config dump shows the
# feature as switched on while resolveBusinessDiscoveryCaller treats it as entirely absent.
#
# NOTE ON THE COMMAND: this repository has no aggregator/reactor pom — influora-api/pom.xml is the
# only pom, so `mvn -pl influora-api test` dies in the reactor and never reaches surefire. Run with
# cwd inside the module. Do not "simplify" this back to -pl.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/influora-api"
TEST='InstagramLookupCallerStartupValidatorTest'

if [ ! -f "$MODULE/pom.xml" ]; then
  echo "UNAVAILABLE: no pom at $MODULE/pom.xml"
  exit 2
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "UNAVAILABLE: mvn not on PATH — this gate cannot report green without running"
  exit 2
fi

cd "$MODULE" || exit 2

# -DfailIfNoTests=true so a deleted or renamed validator test is a failure, not a silent pass. A
# gate that greens because its subject vanished is the exact shape this OS exists to prevent — and
# it is doubly important here, where the subject is itself a warning about something being absent.
if ! out="$(mvn -o -q test -Dtest="$TEST" -DfailIfNoTests=true 2>&1)"; then
  echo "BROKEN: the Instagram lookup caller no longer reports its own state at boot. Either the"
  echo "        warning stopped firing for an unset/half-set system caller, or it stopped naming"
  echo "        the creator-token borrowing it causes — which returns this to a silent degrade,"
  echo "        the original F-0697 defect."
  echo "$out" | tail -25
  exit 1
fi

echo "PROVED: an unset, half-set or unconfigured Instagram lookup caller warns at boot and names"
echo "        its consequence ($TEST). NOT proved, and not in scope: whether the credential is"
echo "        actually provisioned — see wiki/processes/OPS-F0697-instagram-system-caller.md"
exit 0
