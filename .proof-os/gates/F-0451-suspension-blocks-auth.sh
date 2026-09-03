#!/usr/bin/env bash
# gate for F-0451 · suspension-flag-not-read-by-auth
# also covers F-0457 (repair) and F-0458 (multi-workspace lockout)
#
# Origin: admin suspend wrote workspaces.is_suspended / creator_profiles.is_suspended, but no
# authentication path read either flag. The FIRST fix gated login+refresh only, which left three
# holes an independent review found: (a) WorkspaceMemberService.switchWorkspace -- the REACHABLE
# switch, as WorkspaceService.switchWorkspace has no production caller -- minted fresh tokens for a
# suspended workspace forever; (b) BrandContextService/CreatorContextService, which every endpoint
# funnels through, never read the flag, so suspension did not bite until the token expired;
# (c) a multi-workspace user was locked out of workspaces the admin never suspended.
#
# Exits 0 only when all 7 assertions hold across the 3 suites. Verified to exit 1 when any one of
# the four production guards is removed.
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2
command -v mvn >/dev/null 2>&1 || { echo "F-0451: mvn unavailable — cannot prove"; exit 2; }
cd influora-api || exit 2

mvn -o -q -Dtest='AuthServiceTest#testBrandLoginSuspendedWorkspace+testCreatorLoginSuspendedProfile+testRefreshSuspendedWorkspace+testRefreshSuspendedCreator+testBrandLoginMultiWorkspaceSkipsSuspended+testBrandLoginAllWorkspacesSuspendedStillRefused,WorkspaceMemberServiceTest#switchWorkspace_suspendedTarget_rejected,CreatorContextServiceTest#requireCreatorProfile_suspended_rejected,BrandContextServiceTest' test >/dev/null 2>&1
rc=$?

total=0
for cls in AuthServiceTest WorkspaceMemberServiceTest CreatorContextServiceTest BrandContextServiceTest; do
  rep="target/surefire-reports/com.influora.service.${cls}.txt"
  [ -f "$rep" ] || { echo "F-0451: no surefire report for $cls — its tests did not run"; exit 2; }
  grep -q "Failures: 0, Errors: 0" "$rep" || { echo "F-0451: BROKEN in $cls — $(sed -n '4p' "$rep")"; exit 1; }
  n=$(sed -n 's/.*Tests run: \([0-9]*\).*/\1/p' "$rep" | head -1)
  total=$((total + ${n:-0}))
done

# 6 AuthService + 1 switchWorkspace + 1 creator-context + 2 brand-context. Fewer means a filter matched
# nothing, which is how a deleted guard would otherwise green this gate (vacuous pass).
[ "$total" -ge 10 ] || { echo "F-0451: only $total assertion(s) ran, expected 10 — vacuous pass"; exit 1; }
[ $rc -eq 0 ] || { echo "F-0451: maven exit $rc"; exit 1; }
echo "F-0451/0457/0458: proved — $total suspension assertions green across 4 suites"
exit 0
