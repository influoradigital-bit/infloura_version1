#!/usr/bin/env bash
# Gate for F-0694 (discovery-filter-hides-real-data) and F-0695 (no-self-report-path). One gate,
# because they are two halves of one defect and either half alone re-opens it: a backend that can
# write a creator-reported platform_stats row is useless with no surface to call it, and a form
# that posts to nothing is a dead control.
#
# The failure this exists to prevent: a brand ticking the "Instagram" chip on Discover runs
# CreatorProfileSpecifications#hasPlatforms, an EXISTS subquery over platform_stats. That table's
# only two writers (PlatformStatsAggregationJob, PortfolioService#syncPlatforms) both built their
# row from a Meta CreatorMetric, and creator-onboarding.tsx's only Instagram branch was a
# full-page Meta OAuth redirect. A creator who declined, failed, or could not complete OAuth had
# NO platform_stats row at all, so the filter that reads as "creators on Instagram" actually meant
# "creators who finished Meta OAuth" — and everyone else was invisible to the single most obvious
# search a brand performs. With pages_read_engagement rejected on the Meta app, that population is
# close to everyone.
#
# Why a green suite did not catch it: nothing asserted that the platform filter can find a creator
# who has that platform. Every discovery test seeded its fixtures with platform_stats rows already
# present, so the only path that creates one was never the subject.
#
# WHAT THIS GATE REFUSES TO LET BACK IN, and why each half is load-bearing:
#   backend  — the row is written at all; it is written CREATOR_REPORTED so the brand-facing
#              verified badge stays dark (CR-119); a Meta-synced row is never overwritten by a
#              typed number; and the JOINED cross-link hook never fires on a typed handle, which
#              would otherwise let any creator claim any Instagram account. Each of these four was
#              falsified by mutation: reversing any one of them turns exactly one named test red.
#   frontend — the Add control is genuinely wired (F-0341: tsc, eslint and a screenshot all pass
#              on a form whose button does nothing — only a click test can tell the difference).
#
# NOTE ON THE COMMAND: this repository has no aggregator/reactor pom — influora-api/pom.xml is the
# only pom. `mvn -pl influora-api test` dies in the reactor and never reaches surefire, which
# would make this gate unable to go green for a reason that has nothing to do with the code. The
# command must run with cwd inside the module. Do not "simplify" this back to -pl.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/influora-api"
BACKEND_TEST='PortfolioServiceDeclarePlatformTest'
FRONTEND_TEST='src/pages/creator-portfolio-editor.f0694-declare-platform.test.tsx'

if [ ! -f "$MODULE/pom.xml" ]; then
  echo "UNAVAILABLE: no pom at $MODULE/pom.xml"
  exit 2
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "UNAVAILABLE: mvn not on PATH — this gate cannot report green without running"
  exit 2
fi
if [ ! -f "$ROOT/$FRONTEND_TEST" ]; then
  echo "UNAVAILABLE: $FRONTEND_TEST is missing — half this gate's subject does not exist"
  exit 2
fi
if [ ! -d "$ROOT/node_modules" ]; then
  echo "UNAVAILABLE: node_modules absent — the click half cannot run, so this gate must not"
  echo "             report green on the backend alone"
  exit 2
fi

# --- backend half -----------------------------------------------------------------------------
# -DfailIfNoTests=true so a renamed or deleted test class is a failure, not a silent pass: a gate
# that greens because its subject vanished is the exact failure mode this OS exists to prevent.
cd "$MODULE" || exit 2
if ! out="$(mvn -o -q test -Dtest="$BACKEND_TEST" -DfailIfNoTests=true 2>&1)"; then
  echo "BROKEN: the creator-reported platform_stats contract regressed. Either the row is no"
  echo "        longer written, or it is no longer labelled CREATOR_REPORTED (so it would render"
  echo "        to brands as platform-verified), or a typed number can now overwrite a Meta-synced"
  echo "        row, or a typed handle now fires the JOINED cross-link hook."
  echo "$out" | tail -25
  exit 1
fi

# --- frontend half ----------------------------------------------------------------------------
cd "$ROOT" || exit 2
if ! out="$(npx vitest run "$FRONTEND_TEST" 2>&1)"; then
  echo "BROKEN: the creator-facing declaration control is no longer wired. The backend can still"
  echo "        write the row, but nothing on the page reaches it — which is indistinguishable"
  echo "        from the original defect from a brand's side of Discover."
  echo "$out" | tail -25
  exit 1
fi

echo "PROVED: a creator with no Meta connection can declare a platform ($BACKEND_TEST), the row"
echo "        stays creator-reported and cannot overwrite a verified one, and the control that"
echo "        calls it is genuinely wired ($FRONTEND_TEST)"
exit 0
