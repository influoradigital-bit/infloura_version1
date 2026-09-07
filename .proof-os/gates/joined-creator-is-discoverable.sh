#!/usr/bin/env bash
# Gate for F-0701 (joined-creator-not-discoverable).
#
# The failure this exists to prevent: an admin imports an Instagram handle, a brand hits Connect,
# the admin invites, the creator registers through the signed token — and finishLinking marked the
# external_creators row JOINED, emailed the brand "they joined", and wrote no platform_stats row.
# The brand's Discover filter for platforms=INSTAGRAM is an EXISTS subquery over exactly that table
# (CreatorProfileSpecifications#hasPlatforms), so the creator the brand had personally recruited was
# absent from its own search results. The handle, follower count and engagement rate were already on
# the ExternalCreator row from Business Discovery and were dropped.
#
# Why a green suite did not catch it: every discovery test seeds platform_stats directly, so the
# path that CREATES a row was never the subject. A missing write is invisible to a test that
# assumes the row exists.
#
# WHAT THIS GATE REFUSES TO LET BACK IN. Each was falsified by mutation before being trusted, and
# each turns this red on its own:
#   - the adoption call removed from finishLinking (the original defect)
#   - the adopted row claiming verified=true. An invite proves the creator controls the email an
#     admin associated with that handle; it does NOT prove they own the Instagram account. The
#     numbers are real Meta data, the identity binding is an admin's assertion, and brands read
#     PlatformStat.verified as platform-confirmed ownership before they spend money (CR-119).
#   - the absent-only guard removed, letting an admin's older copy overwrite a row a creator earned
#     by connecting Meta themselves.
#   - the totalFollowers roll-up dropped. followersBetween filters on CreatorProfile.totalFollowers,
#     NOT on the platform row, so without it the creator appears for the platform chip and vanishes
#     the moment a brand touches the follower slider — a half-fix that looks correct in any
#     chip-only test.
#
# The suite also pins two things a naive implementation gets wrong: a never-enriched ADMIN_IMPORT
# stub must become a findable row with 0 followers rather than an invented number, and a
# platform_stats failure must never stop the creator from joining.
#
# ExternalCreatorLinkServiceRollbackIsolationTest is included deliberately, not incidentally. It is
# a real @DataJpaTest whose Spring context enumerates the exact repositories and entities this
# service may depend on; adding PlatformStatRepository to the service broke it, which is the point.
# It is the only thing here that would catch a future dependency added to this service without the
# context being widened to match — a failure that unit tests with mocks cannot see and that would
# otherwise surface as a production startup failure.
#
# NOTE ON THE COMMAND: this repository has no aggregator/reactor pom — influora-api/pom.xml is the
# only pom, so `mvn -pl influora-api test` dies in the reactor and never reaches surefire. Run with
# cwd inside the module. Do not "simplify" this back to -pl.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/influora-api"
TEST='ExternalCreatorLinkServiceAdoptPlatformStatTest,ExternalCreatorLinkServiceTest,ExternalCreatorLinkServiceRollbackIsolationTest'

if [ ! -f "$MODULE/pom.xml" ]; then
  echo "UNAVAILABLE: no pom at $MODULE/pom.xml"
  exit 2
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "UNAVAILABLE: mvn not on PATH — this gate cannot report green without running"
  exit 2
fi

cd "$MODULE" || exit 2

# -DfailIfNoTests=true so a renamed or deleted class is a failure, not a silent pass: a gate that
# greens because its subject vanished is the exact failure mode this OS exists to prevent.
if ! out="$(mvn -o -q test -Dtest="$TEST" -DfailIfNoTests=true 2>&1)"; then
  echo "BROKEN: a creator who joins via a verified invite is no longer made discoverable, or is"
  echo "        made discoverable dishonestly. Check in this order: is the platform_stats row"
  echo "        written at all; does it claim verified; can it overwrite a real Meta-synced row;"
  echo "        is totalFollowers still rolled up; does the Spring context still start with this"
  echo "        service's dependencies."
  echo "$out" | tail -30
  exit 1
fi

echo "PROVED: a creator linked by verified invite gets an unverified INSTAGRAM platform_stats row"
echo "        carrying the Business Discovery handle/followers, never overwrites a real synced row,"
echo "        rolls up totalFollowers so follower-range filters find them, and still joins even if"
echo "        that write fails ($TEST)"
exit 0
