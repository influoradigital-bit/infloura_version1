#!/usr/bin/env bash
# Gate for F-0740 (backfill-must-not-diverge-from-the-live-path).
#
# WHAT THE BACKFILL IS FOR. F-0701 taught finishLinking to write a platform_stats row when a
# creator joins via a verified invite — the row a brand's platforms=INSTAGRAM Discover filter needs
# to find them. That fires only at the moment of joining, so every creator who joined BEFORE it
# shipped is still invisible, with their handle and follower count sitting unused on the
# external_creators row. AdminPlatformStatBackfillService walks those rows once.
#
# WHAT THIS GATE ACTUALLY PROTECTS, and why each half was falsified by mutation before being
# trusted:
#   - A DRY RUN WRITES NOTHING. This is the one property the endpoint's own response can never
#     evidence: a run that wrote rows and then reported dryRun:true looks identical to an honest
#     one. Wiring the dry-run branch to the writing call turns the suite red.
#   - ONE BAD ROW DOES NOT SINK THE RUN. A FAILED row is counted and stepped over. Making a failure
#     abort the walk turns the suite red. A backfill that dies on row 3 of 400 while reporting
#     partial success is worse than one that never ran.
#   - THE ADMIN GATE IS CHECKED BEFORE ANY ROW IS READ. Deleting the role+MFA call turns the suite
#     red — and it must fail on the ORDER, not merely on the presence of a check, because an
#     authorisation that runs after the work is not an authorisation.
#   - EVERY WRITE DELEGATES TO THE LIVE PATH. The backfill never builds a PlatformStat itself; it
#     calls the same adoptExternalPlatformStat the join path calls, so the two cannot drift. This
#     is why ExternalCreatorLinkService's own suites are in this gate's test list rather than only
#     the backfill's: the properties that make an adopted row honest (never verified, never
#     overwrites a real Meta-synced row, totalFollowers rolled up) live THERE, and the backfill
#     inherits them. If they regress, every row this backfill has ever written is wrong, and a gate
#     that only ran the backfill's own tests would still be green.
#
# A NOTE FOR WHOEVER MUTATES THIS TO CHECK IT STILL BITES. Two falsification attempts against this
# service were VACUOUS before one worked. PowerShell's `Set-Content -Encoding utf8` prepends a BOM,
# javac rejects it with "illegal character: '﻿'", the build never compiles, and surefire serves
# the PREVIOUS run's report — so the mutation reads as passing. Mutate with
# [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false))), and
# always read mvn's own exit code rather than a piped grep's.
#
# NOTE ON THE COMMAND: this repository has no aggregator/reactor pom — influora-api/pom.xml is the
# only pom, so `mvn -pl influora-api test` dies in the reactor and never reaches surefire. Run with
# cwd inside the module. Do not "simplify" this back to -pl.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/influora-api"
TEST='AdminPlatformStatBackfillServiceTest,ExternalCreatorLinkServiceAdoptPlatformStatTest,ExternalCreatorLinkServiceTest,ExternalCreatorLinkServiceRollbackIsolationTest'

if [ ! -f "$MODULE/pom.xml" ]; then
  echo "UNAVAILABLE: no pom at $MODULE/pom.xml"
  exit 2
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "UNAVAILABLE: mvn not on PATH — this gate cannot report green without running"
  exit 2
fi

# mvn on PATH is not the same as mvn able to RUN. On this machine mvn works from PowerShell and
# fails from bash, because JAVA_HOME is set in one environment and not the other. Without this
# preflight that surfaced below as "BROKEN: the backfill is no longer safe to run" — a toolchain
# problem wearing a regression's clothes, which is worse than no gate: it sends someone hunting a
# defect that does not exist. An absent toolchain is exit 2, never exit 1.
if ! java -version >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
  echo "UNAVAILABLE: no runnable java (JAVA_HOME unset and no java on PATH) — mvn cannot start,"
  echo "             so this gate has measured nothing. This is NOT a regression."
  exit 2
fi

cd "$MODULE" || exit 2

# -DfailIfNoTests=true so a renamed or deleted class is a failure, not a silent pass. Assigning the
# output rather than piping it keeps mvn's own exit code: `mvn | grep` reports grep's status, and a
# BUILD FAILURE would arrive here as success.
if ! out="$(mvn -o -q test -Dtest="$TEST" -DfailIfNoTests=true 2>&1)"; then
  # Second line of defence for the same class the JAVA_HOME preflight above covers: mvn can also
  # fail before reaching surefire for reasons that are not this code (a broken toolchain, an empty
  # offline repository). Those must read as unavailable, not as a defect in the backfill.
  if echo "$out" | grep -qiE "JAVA_HOME|Unable to locate a Java|No compiler is provided|Could not resolve dependencies|Non-resolvable"; then
    echo "UNAVAILABLE: mvn could not run this module (toolchain or dependency resolution), so"
    echo "             nothing about the backfill was measured. This is NOT a regression."
    echo "$out" | tail -8
    exit 2
  fi
  echo "BROKEN: the platform_stats backfill is no longer safe to run. Check in this order: does a"
  echo "        dry run still write nothing; does one failing row still let the rest of the walk"
  echo "        finish; is role+MFA still checked before the first row is read; and do the adopted"
  echo "        rows the backfill delegates to still refuse to claim verified or to overwrite a"
  echo "        real Meta-synced row."
  echo "$out" | tail -30
  exit 1
fi

echo "PROVED: a dry run writes nothing, a failed row is counted and stepped over, role+MFA is"
echo "        checked before any row is read, and every write delegates to the same adoption path"
echo "        the live join uses ($TEST)"
echo "        NOT proved: that the backfill has been RUN against production, or that any creator is"
echo "        actually discoverable as a result — this is a repo check, not a live one."
exit 0
