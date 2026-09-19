#!/usr/bin/env bash
# gates/F-1776-F-1778-round7-guards.sh — origin: F-1776 (pattern-branch-with-no-test-row, kabir
# 2026-09-18 K-2c check, clause 5) and F-1778 (flag-fires-on-compliant-text, priya 2026-09-18
# round 7 / R7-A). Both fixed by vikram in K-2c.1 and K-2c.2, signed off fresh-context by kabir
# (.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/KABIR-R7-HIDE-0919.md and KABIR-R7-OFFPLAT-0919.md).
#
# THE DEFECTS.
#   F-1776  8 of 36 HIDE_TEXT alternatives and 6 sentence terminators in OffPlatformPaymentRule had
#           no row that went red when they were removed, so any of them could be deleted with the
#           whole build green.
#   F-1778  The hide-the-ad flag fired on brands that FOLLOW the ad-label rules ("Please do not post
#           without the paid partnership label."): 13 of 15 compliance lines raised a flag the
#           creator cannot dismiss.
#
# WHAT THIS GATE DECIDES. The oracles are RiskFlagCorpusTest and OffPlatformPaymentRuleTest; kabir
# showed each guard goes red alone when its piece is removed. This gate adds what a bare
# `mvn -Dtest=` cannot:
#   1. every guard row and test still exists by name (the VIK-GUARD2/3 hide rows, the pipe rows,
#      the C1-C12 compliance rows, the three boundary tests, the zero-false-positive tests and the
#      two TSV fidelity tests). A deleted row would otherwise leave the build green;
#   2. nisha's blind compliance file still holds its 14 NC rows, so the zero-flag check is not
#      measured against a shrunken file;
#   3. both classes actually RAN (tests > 0 in their own surefire report), with `clean` always
#      passed (reference_restore_by_copy_keeps_stale_bytecode).
#
# NOT CHECKED: F-1781 (bare "only" and "nahi ... toh" hide asks that escape, open); F-1777 (asks the
# text rules miss on the FALLBACK path, before go-live); F-1780 (passive payout statements); how
# real brands write beyond the rows here.
#
# Exit 0 proved · 1 broken · 2 unavailable (never green).
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
API="$ROOT/influora-api"
RULES="$API/src/test/java/com/influora/service/risk/rules"
CORPUS="$RULES/RiskFlagCorpusTest.java"
OFFPLAT="$RULES/OffPlatformPaymentRuleTest.java"
NC_TSV="$API/src/test/resources/risk-corpus/nisha-compliance-r7.tsv"
REPORTS="$API/target/surefire-reports"

command -v mvn >/dev/null 2>&1 || { echo "UNAVAILABLE: mvn not on PATH"; exit 2; }
for f in "$CORPUS" "$OFFPLAT" "$NC_TSV"; do [ -f "$f" ] || { echo "BROKEN: $f is missing"; exit 1; }; done

missing=0
need() { grep -aqF -- "$2" "$1" || { echo "BROKEN: '$2' is gone from $(basename "$1")"; missing=1; }; }
for row in adtag collab it put sponsoredtag thisis; do need "$CORPUS" "\"VIK-GUARD2-HD-F-$row\""; done
need "$CORPUS" '"VIK-GUARD3-HD-F-an-ad"'
for row in PIPE-N-deva PIPE-N-hinglish PIPE-N-en PIPE-F-rupee PIPE-F-upi-pay; do need "$CORPUS" "\"$row\""; done
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do need "$CORPUS" "\"C$i\""; done
need "$CORPUS" "void hideDisclosureHasNoFalsePositives"
need "$CORPUS" "void offPlatformPaymentHasNoFalsePositives"
need "$CORPUS" "void nishaComplianceResourceMatchesMarkdownVerbatim"
need "$CORPUS" "void nishaBlindResourceMatchesMarkdownVerbatim"
need "$OFFPLAT" "void eachExtraTerminatorCharacterStopsPairing"
need "$OFFPLAT" "void paragraphSeparatorAloneStopsPairing"
need "$OFFPLAT" "void dotBeforeCurrencySymbolAloneIsProtected"
nc=$(grep -ac '^NC-[0-9][0-9]' "$NC_TSV")
[ "$nc" -eq 14 ] || { echo "BROKEN: nisha-compliance-r7.tsv holds $nc NC rows, expected 14"; missing=1; }
[ "$missing" -eq 0 ] || exit 1

rm -rf "$REPORTS"
mvn -o -q -f "$API/pom.xml" clean -Dtest=RiskFlagCorpusTest,OffPlatformPaymentRuleTest \
  -Dsurefire.failIfNoSpecifiedTests=true test
rc=$?

summary=$(python - "$REPORTS" <<'PY'
import re, sys, pathlib
d = pathlib.Path(sys.argv[1])
out = []
for cls in ("com.influora.service.risk.rules.RiskFlagCorpusTest",
            "com.influora.service.risk.rules.OffPlatformPaymentRuleTest"):
    files = list(d.glob(f"TEST-{cls}*.xml")) if d.is_dir() else []
    t = f = e = 0
    for p in files:
        m = re.search(r"<testsuite[^>]*", p.read_text(encoding="utf-8", errors="ignore")[:4000])
        g = lambda k: int(re.search(k + r'="(\d+)"', m.group(0)).group(1))
        t += g("tests"); f += g("failures"); e += g("errors")
    out.append(f"{cls.rsplit('.', 1)[1]}:{t}:{f}:{e}")
print(" ".join(out))
PY
)
bad=0
for item in $summary; do
  IFS=: read -r name t f e <<<"$item"
  if [ "${t:-0}" -lt 1 ]; then echo "BROKEN: $name reported $t tests; it did not run"; bad=1; fi
  if [ "${f:-1}" -ne 0 ] || [ "${e:-1}" -ne 0 ]; then echo "BROKEN: $name failures=$f errors=$e"; bad=1; fi
done
if [ "$rc" -ne 0 ] || [ "$bad" -ne 0 ]; then echo "BROKEN: mvn exit $rc; $summary"; exit 1; fi
echo "PROVED: $summary (class:tests:failures:errors); round-7 guard rows, 14 NC rows and boundary tests present"
exit 0
