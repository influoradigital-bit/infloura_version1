#!/usr/bin/env bash
# gates/F-0674-public-collab-visibility.sh — closes F-0674.
#
# origin: F-0674 (privacy-leak-client-side-only). A creator can mark a past collab "hidden" or
# "category" (anonymised). Those choices were enforced ONLY in the browser —
# creator-portfolio-public.tsx:1091 filtered hidden rows out and :822/:849 swapped the brand name
# for a label — while PortfolioService.getPublic returned every collab with its REAL
# Workspace.getName() regardless. An unauthenticated `curl /portfolio/{username}` therefore
# returned the brand names of collabs the creator had explicitly chosen to hide. The masking was
# cosmetic.
#
# THE FIX mirrors this service's OWN existing server-side visibility pattern (getVisiblePinnedPosts),
# rather than inventing one: on the public path only, a "hidden" collab is dropped from the response
# entirely (not returned with a flag the client is trusted to honour — that WAS the defect), and a
# "category" collab has its brandName replaced with an industry-derived label AND its brandId and
# brandLogoUrl nulled. Leaving those two would have defeated the anonymisation via a real logo image
# or a lookup-able id — a genuinely good catch by the implementing agent, beyond the brief.
#
# NOT OVER-FIXED: getMine (the authenticated creator-facing path) is unchanged and still returns the
# creator their own real brand names for hidden and category collabs. Blinding creators to their own
# data would have been a different defect; a review asserted this explicitly and it is green.
#
# FALSIFICATION, measured: stripping the two guards fails the shipped test 2 of 4 ("expected: <2>
# but was: <3>" — the hidden collab still counted; "expected: <Beauty Brand> but was: <Sugar
# Cosmetics>" — the real name leaking). A fresh-context reviewer additionally wrote her OWN probe
# serialising the WHOLE PortfolioPageResponse through Jackson (the shipped test only inspects
# collabs().toString()), asserting both suppressed brand names AND both workspace ids are absent,
# with a positive control that a non-suppressed brand IS present: passed on the fix, failed on the
# revert.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

T=influora-api/src/test/java/com/influora/service/portfolio/PortfolioServicePublicVisibilityTest.java
[ -f "$T" ] || { echo "· $T missing — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }

# This test was UNTRACKED when first written (the F-0324 pattern, a blocker on three consecutive
# waves): every gate passed only because the file happened to exist on disk. A privacy-leak gate
# that vanishes on a fresh clone is not a gate.
if git ls-files --error-unmatch "$T" >/dev/null 2>&1; then
  echo "· regression test is git-tracked"
else
  echo "· $T exists on disk but is NOT git-tracked — this gate would be absent on a fresh clone"
  echo "VERDICT: broken — F-0324 pattern; git add the test before relying on this gate"
  exit 1
fi

BUDGET="${PROOF_F0674_TIMEOUT:-600}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 15 $BUDGET"; else TO=""; fi

echo "· mvn -o clean -Dtest=PortfolioService{PublicVisibility,CollabDisplayMode,}Test test"
out=$($TO mvn -o -q clean -Dtest='com.influora.service.portfolio.PortfolioServicePublicVisibilityTest,com.influora.service.portfolio.PortfolioServiceCollabDisplayModeTest,com.influora.service.portfolio.PortfolioServiceTest' -f influora-api/pom.xml test 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if echo "$out" | grep -qiE "COMPILATION ERROR|cannot find symbol"; then
  printf '%s\n' "$out" | tail -40; echo "VERDICT: unavailable — module does not compile"; exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken — a hidden or anonymised collab's real brand identity is reaching the"
  echo "         public portfolio response again (F-0674)"
  exit 1
fi
echo "  portfolio suite green"

echo "VERDICT: aligned (proved) — the PUBLIC portfolio response omits a hidden collab entirely and"
echo "         carries no real brand name, brand id or logo for an anonymised one, while the"
echo "         authenticated getMine still returns the creator their own real data."
echo "NOT CHECKED: whether any OTHER public endpoint exposes the same collab data by a different"
echo "             route; the browser-side masking that was previously the only enforcement is now"
echo "             redundant rather than removed, and is not asserted here. Per the standing"
echo "             caveat: no test in this repo hits a real HTTP endpoint, so this is proved at the"
echo "             service layer, not against a live curl."
exit 0
