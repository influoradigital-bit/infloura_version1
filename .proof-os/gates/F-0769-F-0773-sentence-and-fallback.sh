#!/usr/bin/env bash
# gates/F-0769-F-0773-sentence-and-fallback.sh — origin: F-0769 (flag-fires-on-unrelated-words,
# kabir 2026-09-18) and F-0773 (flag-fires-on-unrelated-words, priya 2026-09-18). Both fixed by
# vikram in K-2c; checked fresh-context by kabir with 73 clean-build mutations
# (.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/KABIR-K2C-CHECK-0918.md, clauses 1-4 and 6 MET).
#
# THE DEFECTS.
#   F-0769  OffPlatformPaymentRule's 6-token pairing window counted straight through a sentence
#           end, so "Draft bhej do kal tak. UPI se payout Influora pe aayega." raised a flag the
#           creator cannot dismiss. Fixed by pairing only within one sentence (RULINGS-U-0917.md
#           round 6); "Rs. 5,000"-style dots do not end a sentence.
#   F-0773  On the FALLBACK path (monthly AI allowance spent, or AI down) BriefFallbackExtractor set
#           both risk hints from its own older patterns, which brought back every false flag that
#           rounds 4-6 fixed. Fixed by never setting either hint on that path.
#
# WHAT THIS GATE DECIDES. The oracles are three test classes. kabir showed each one goes red
# when its fix is removed. This gate adds what a bare `mvn -Dtest=` cannot:
#   1. the guarding tests and rows still exist: XS-1..XS-6, the pairing-window pin, the sentence
#      test, and the fallback tests. A deleted test would otherwise leave the build green;
#   2. every one of the three classes actually RAN (tests > 0 in its own surefire report);
#   3. `clean` is always passed (reference_restore_by_copy_keeps_stale_bytecode).
#
# NOT CHECKED: F-0776 (8 HIDE_TEXT alternatives and 6 sentence terminators with no guard row, open);
# F-0777 (real asks the text rules miss on the FALLBACK path, open, a product ruling); whether the
# rows reflect how real brands write.
#
# Exit 0 proved · 1 broken · 2 unavailable (never green).
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
API="$ROOT/influora-api"
CORPUS="$API/src/test/java/com/influora/service/risk/rules/RiskFlagCorpusTest.java"
FB_REAL="$API/src/test/java/com/influora/service/brief/BriefFallbackExtractorRealRiskRulesTest.java"
FB_UNIT="$API/src/test/java/com/influora/service/brief/BriefFallbackExtractorTest.java"
REPORTS="$API/target/surefire-reports"

command -v mvn >/dev/null 2>&1 || { echo "UNAVAILABLE: mvn not on PATH"; exit 2; }
for f in "$CORPUS" "$FB_REAL" "$FB_UNIT"; do [ -f "$f" ] || { echo "BROKEN: $f is missing"; exit 1; }; done

missing=0
need() { grep -aq -- "$2" "$1" || { echo "BROKEN: '$2' is gone from $(basename "$1")"; missing=1; }; }
for row in '"XS-1"' '"XS-2"' '"XS-3"' '"XS-4"' '"XS-5"' '"XS-6"'; do need "$CORPUS" "$row"; done
need "$CORPUS" "void pairingWindowIsExactlySix"
need "$CORPUS" "void sentenceBoundaryStopsPairingAcrossSentences"
need "$FB_REAL" "void onPlatformPayoutInstructionDoesNotFlag"
need "$FB_REAL" "void ordinaryAdCaptionDoesNotFlag"
need "$FB_UNIT" "void offPlatformPaymentHintIsAlwaysFalse"
need "$FB_UNIT" "void disclosureHiddenHintIsAlwaysFalse"
[ "$missing" -eq 0 ] || exit 1

rm -rf "$REPORTS"
mvn -o -q -f "$API/pom.xml" clean \
  -Dtest=RiskFlagCorpusTest,BriefFallbackExtractorRealRiskRulesTest,BriefFallbackExtractorTest \
  -Dsurefire.failIfNoSpecifiedTests=true test
rc=$?

summary=$(python - "$REPORTS" <<'PY'
import re, sys, pathlib
d = pathlib.Path(sys.argv[1])
out = []
for cls in ("com.influora.service.risk.rules.RiskFlagCorpusTest",
            "com.influora.service.brief.BriefFallbackExtractorRealRiskRulesTest",
            "com.influora.service.brief.BriefFallbackExtractorTest"):
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
echo "PROVED: $summary (class:tests:failures:errors); guarding rows and tests present"
exit 0
