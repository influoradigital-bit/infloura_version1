#!/usr/bin/env bash
# gates/F-1765-F-1766-risk-corpus.sh — origin: F-1765 (flag-fires-on-unrelated-words) and
# F-1766 (pattern-branch-with-no-test-row), both found by kabir on 2026-09-17 in the K-2b round-5
# last call, fixed by vikram (KB5-1, KB5-2), checked fresh-context by kabir 2026-09-18
# (.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/KABIR-KB5-CHECK-0918.md).
#
# THE DEFECTS.
#   F-1765  OffPlatformPaymentRule accepted a wallet name ANYWHERE plus a send/pay word ANYWHERE,
#           so "Please send the draft ... Your fee is released to the UPI ID saved in your
#           Influora payout settings." raised a flag the creator cannot dismiss. Fixed by a
#           PAIRING_WINDOW (6 tokens) between the two.
#   F-1766  Three HideDisclosureRule HIDE_TEXT branches (Hinglish short form, Devanagari short
#           form, "don't mention it's sponsored") had no corpus row, so deleting any of them left
#           the corpus test green.
#
# WHAT THIS GATE DECIDES. The oracle is RiskFlagCorpusTest itself. kabir showed each of its new
# rows goes red when its fix is removed. This gate adds the three things a bare `mvn -Dtest=`
# cannot:
#   1. the rows that guard these two defects still exist in the test source. A deleted row
#      would otherwise leave the test green;
#   2. the test class actually RAN (tests > 0 in its surefire report). A renamed class or a typo
#      in -Dtest makes Maven report success on zero tests;
#   3. `clean` is always passed. A restore-by-copy keeps the old mtime, and an incremental build
#      then serves the mutant's bytecode (reference_restore_by_copy_keeps_stale_bytecode).
#
# NOT CHECKED: whether 6 is the right window (the corpus only pins it between 3 and 12); the
# sentence-boundary false positive the window still has (F-1769, open); and whether the rows
# reflect how real brands write. That is the offline model-recall run owed before go-live.
#
# Exit 0 proved · 1 broken · 2 unavailable (never green).
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TEST_SRC="$ROOT/influora-api/src/test/java/com/influora/service/risk/rules/RiskFlagCorpusTest.java"
RULE_SRC="$ROOT/influora-api/src/main/java/com/influora/service/risk/rules/OffPlatformPaymentRule.java"
REPORT="$ROOT/influora-api/target/surefire-reports/TEST-com.influora.service.risk.rules.RiskFlagCorpusTest.xml"

command -v mvn >/dev/null 2>&1 || { echo "UNAVAILABLE: mvn not on PATH"; exit 2; }
[ -f "$TEST_SRC" ] || { echo "BROKEN: $TEST_SRC is missing"; exit 1; }
[ -f "$RULE_SRC" ] || { echo "BROKEN: $RULE_SRC is missing"; exit 1; }

# 1. The guarding rows and the window are still present.
missing=0
for row in KAB5-N-send-draft KAB5-N-send-files KAB5-HD-F-hinglish-01 KAB5-HD-F-devanagari-01 KAB5-HD-F-dont-mention; do
  if ! grep -q "$row" "$TEST_SRC"; then echo "BROKEN: corpus row $row is gone from RiskFlagCorpusTest"; missing=1; fi
done
grep -q "PAIRING_WINDOW" "$RULE_SRC" || { echo "BROKEN: PAIRING_WINDOW is gone from OffPlatformPaymentRule"; missing=1; }
[ "$missing" -eq 0 ] || exit 1

# 2. Run the oracle, clean, unpiped so its exit code is its own.
rm -f "$REPORT"
mvn -o -q -f "$ROOT/influora-api/pom.xml" clean -Dtest=RiskFlagCorpusTest -Dsurefire.failIfNoSpecifiedTests=true test
rc=$?

# 3. The class really ran.
[ -f "$REPORT" ] || { echo "BROKEN: no surefire report for RiskFlagCorpusTest (mvn exit $rc); the class did not run"; exit 1; }
counts=$(python - "$REPORT" <<'PY'
import re, sys
head = open(sys.argv[1], encoding="utf-8", errors="ignore").read(4000)
m = re.search(r"<testsuite[^>]*", head)
get = lambda k: int(re.search(k + r'="(\d+)"', m.group(0)).group(1))
print(get("tests"), get("failures"), get("errors"), get("skipped"))
PY
)
read -r tests failures errors skipped <<<"$counts"
if [ "$rc" -ne 0 ] || [ "${failures:-1}" -ne 0 ] || [ "${errors:-1}" -ne 0 ]; then
  echo "BROKEN: RiskFlagCorpusTest mvn exit $rc, tests=$tests failures=$failures errors=$errors"
  exit 1
fi
if [ "${tests:-0}" -lt 1 ]; then echo "BROKEN: RiskFlagCorpusTest reported $tests tests; vacuous"; exit 1; fi
echo "PROVED: RiskFlagCorpusTest tests=$tests failures=0 errors=0 skipped=$skipped; guarding rows present; PAIRING_WINDOW present"
exit 0
