#!/usr/bin/env bash
# Gate for F-0728 — cross-workspace-code-shadowing.
#
# The failure: coupon_codes is UNIQUE(workspace_id, code) (V24), so two brands may legally hold the
# same code string. Redemption nonetheless looked the code up GLOBALLY and only then rejected a row
# belonging to another workspace. Two consequences, both live:
#
#   1. Shadowing. Whichever row the database returned first won, so Brand B creating SUMMER20 could
#      make Brand A's own SUMMER20 answer INVALID_CODE permanently. The post-hoc ownership check
#      that was supposed to be the cross-tenant fix (Wave D1/E4) was what produced the shadowing.
#   2. A bare 500. The finder returned Optional, and Spring Data raises
#      IncorrectResultSizeDataAccessException when such a query matches more than one row.
#      GlobalExceptionHandler has no handler for it, so every delivery carrying a collided code was
#      an unhandled 500 — including on ConversionWebhookController, where the same lookup chooses
#      WHICH WORKSPACE'S SECRET verifies the signature.
#
# WHY THESE CHECKS.
#
#   grep '\.findByCode(' — the global Optional finder must not come back. NOTE THE DOT AND PAREN,
#     and do not "simplify" this to a bare findByCode: this repo has twice shipped a grep gate that
#     went red on the comment explaining its own banned pattern. A bare grep matches 8 lines here —
#     the F-0728 javadoc in CouponCodeRepository that quotes the old signature, three @link
#     references, and PlanRepository's entirely unrelated findByCode(PlanCode). The call-shaped
#     pattern matches 0. Verified both counts before writing this.
#
#   grep findByWorkspaceIdAndCode in RedemptionWriter — the scoped finder must actually be CALLED,
#     not merely exist on the repository. A gate that only runs mocked tests cannot tell a wired
#     query from an orphaned one; that is precisely the state a crossed commit produced for
#     F-0725/F-0726 on 2026-09-08.
#
#   redeem_sameCodeInTwoWorkspaces_eachRedeemsItsOwn — the ledger's own missed_by, and it asserts
#     findAllByCode was NEVER called on the scoped path. Without that assertion a "fix" that kept
#     fetching globally and re-checked ownership would pass whenever the database happened to return
#     the right row first, and fail intermittently in production — the worst possible outcome.
#
#   redeem_unscopedCollidingCode_reportsAmbiguity — the unscoped caller (a brand's own checkout
#     webhook, which genuinely has no workspace to scope by) must report AMBIGUOUS_COUPON_CODE
#     rather than 500, and must write nothing. Guessing between two matches would credit a real sale
#     to the wrong brand and accrue a commission against the wrong campaign.
#
# FALSIFIED, not assumed: with resolveScoped reverted to global-lookup-then-filter (behaviourally
# identical to pre-fix), RedemptionServiceTest exits 1 with exactly
# redeem_sameCodeInTwoWorkspaces_eachRedeemsItsOwn erroring. Every other test stays green under that
# probe, which is why the never()-verify on findAllByCode is load-bearing rather than decorative.
#
# NOT COVERED: whether two colliding rows can actually be created through the product UI today. The
# schema permits it and nothing forbids it; whether any real pair exists in production is a data
# question this gate cannot answer.
#
# NOTE ON THE COMMAND: this repo has no aggregator pom — influora-api/pom.xml is the only one, so
# `mvn -pl influora-api test` dies in the reactor and never reaches surefire. cwd must be inside
# the module. Do not "simplify" this to -pl.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/influora-api"
MAIN="$MODULE/src/main/java/com/influora"
WRITER="$MAIN/service/tracking/RedemptionWriter.java"
TESTS='RedemptionServiceTest,ConversionWebhookControllerTest'

if [ ! -f "$MODULE/pom.xml" ]; then
  echo "UNAVAILABLE: no pom at $MODULE/pom.xml"
  exit 2
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "UNAVAILABLE: mvn not on PATH — this gate cannot report green without running"
  exit 2
fi
if [ ! -f "$WRITER" ]; then
  echo "UNAVAILABLE: RedemptionWriter not found at $WRITER"
  exit 2
fi

# Call-shaped, deliberately. See the note above before changing this pattern.
if grep -rn "\.findByCode(" --include="*.java" "$MAIN" | grep -v "PlanRepository\|PlanService" | grep -q .; then
  echo "BROKEN: the global Optional coupon finder is back in use. A code held by two workspaces will"
  echo "        shadow one of them into INVALID_CODE, and two matches will raise"
  echo "        IncorrectResultSizeDataAccessException as an unhandled 500 (F-0728). Offenders:"
  grep -rn "\.findByCode(" --include="*.java" "$MAIN" | grep -v "PlanRepository\|PlanService"
  exit 1
fi

if ! grep -q "findByWorkspaceIdAndCode" "$WRITER"; then
  echo "BROKEN: RedemptionWriter no longer calls findByWorkspaceIdAndCode, so a caller with a proven"
  echo "        workspace is resolving coupons globally again and can be shadowed by another"
  echo "        workspace's identical code (F-0728)."
  exit 1
fi

cd "$MODULE" || exit 2

if ! out="$(mvn -o -q test -Dtest="$TESTS" -DfailIfNoTests=true 2>&1)"; then
  echo "BROKEN: coupon resolution is no longer workspace-scoped, or a colliding code no longer"
  echo "        reports an explicit ambiguity (F-0728)."
  echo "$out" | tail -30
  exit 1
fi

# -DfailIfNoTests=true so a renamed or deleted test is a failure rather than a silent pass.
echo "PROVED: a code held by two workspaces redeems each workspace's own coupon, and an unscoped"
echo "        caller reports the collision instead of guessing or 500ing ($TESTS)"
exit 0
