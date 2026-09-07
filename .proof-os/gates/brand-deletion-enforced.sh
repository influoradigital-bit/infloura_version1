#!/usr/bin/env bash
# Gate for F-0708 — deletion-not-enforced-on-brand-gate.
#
# The failure: only CreatorContextService.requireCreator re-checked deletedAt. BrandContextService
# had ZERO occurrences of it, so a soft-deleted BRAND user kept full brand-scoped access — wallet
# and payout endpoints included — for the remaining access-token lifetime (900s default), because
# JwtAuthenticationFilter is a pure token parse that never touches the database and
# AccountController.deleteAccount revokes refresh tokens only. AccountController's own javadoc
# asserted for months that BOTH gates checked it, which is how it stayed invisible: the comment
# that would have prompted anyone to look said the work was already done.
#
# WHY THESE TESTS. requireBrandWorkspace and requireMember INHERIT the check by delegating to
# requireBrand rather than repeating it, so the suite pins all three entry points: a refactor that
# stopped delegating would reopen the hole at the inheriting sites while a requireBrand-only test
# stayed green. It also pins orElse(true) — a principal with no user row must be refused, not
# admitted — because flipping that default is invisible to every other test here.
#
# CampaignAuthzTest is included as the blast-radius canary: it is the other suite that builds a
# REAL BrandContextService, so it proves the added per-request findById did not break the
# role-authorization paths that funnel through the same gate.
#
# NOTE ON THE COMMAND: this repo has no aggregator pom — influora-api/pom.xml is the only one, so
# `mvn -pl influora-api test` dies in the reactor and never reaches surefire. cwd must be inside
# the module. Do not "simplify" this to -pl.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/influora-api"
TESTS='BrandContextServiceTest,CampaignAuthzTest'

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
  echo "BROKEN: account deletion is no longer enforced at the brand gate, or the gate now refuses"
  echo "        someone it should admit. A soft-deleted brand user would keep wallet and payout"
  echo "        access until their access token expires (F-0708)."
  echo "$out" | tail -25
  exit 1
fi

# -DfailIfNoTests=true so a renamed or deleted test is a failure rather than a silent pass.
echo "PROVED: a soft-deleted brand user is refused at requireBrand, requireBrandWorkspace and requireMember ($TESTS)"
exit 0
