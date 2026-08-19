#!/usr/bin/env bash
# gates/F-0281-creator-money-buckets-honest.sh — gate for F-0281 (label-ambiguity) and
# F-0336 (creator-escrow-tile-reads-dead-column), closed together because the first could not be
# fixed with copy alone.
#
# THE DEFECT, in two parts that made each other worse.
#   F-0336: WalletService#getSummaryForUser passed wallet.getEscrowBalance() as escrowLocked —
#   a column NO service ever writes (Wallet.java only ever sets it to ZERO at construction; no
#   JPQL/SQL UPDATE touches escrow_balance anywhere in this codebase). The creator's "In Escrow"
#   tile was therefore permanently ₹0, for every creator, forever.
#   F-0281: pendingPayouts for a creator was the FUNDED-milestone sum — money held in escrow,
#   NOT YET released — displayed under the label "Pending Payouts", which reads as "a withdrawal
#   is already on its way to my bank". The opposite of what that money is.
#   Adding a tooltip explaining either figure on top of this — without fixing the numbers first —
#   would have made the surface WORSE: a confident, well-worded explanation of a lie. (This
#   codebase's git history literally contains that exact wrong fix: a WalletFigureLabel tooltip
#   was added over this same broken data before this gate existed.)
#
# WHAT THIS GATE ASSERTS, and why each leg is behavioural rather than a token search.
#   1. SELF-FALSIFICATION (F-0329 house rule). The gate does not trust its own JUnit assertions
#      until it has watched them REJECT the real F-0336/F-0281 defect. It swaps a frozen,
#      verbatim copy of the ORIGINAL (broken) getSummaryForUser method body into the actual
#      WalletService.java on disk, compiles it, and runs the two new WalletServiceTest methods
#      that pin the fixed behaviour. If those tests PASS against the known-bad body, the
#      assertions cannot tell broken from fixed and this gate refuses to report anything about
#      the real code. The original file is restored via a trap no matter how this exits.
#   2. THE REAL CODE, same tests, same JUnit run, against whatever is actually on disk. This is
#      the leg that fails on the current (pre-fix) tree and passes after the fix — a real `mvn
#      test` execution, not a grep for a method name.
#   3. THE RENDERED PAGE. `npx vitest run` against creator-wallet.money-buckets.test.tsx, which
#      renders the real page component, asserts each tile's NUMBER against its LABEL, opens each
#      tooltip and asserts its definition text describes the bucket actually beside it (not the
#      other one), and asserts a failed fetch renders "—" rather than a fabricated ₹0 distinct
#      from a genuine, server-confirmed zero (the F-0260 absent-vs-zero class).
#
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

WALLET_SVC=influora-api/src/main/java/com/influora/service/WalletService.java
PAYOUT_REPO=influora-api/src/main/java/com/influora/repository/PayoutRepository.java
WALLET_TEST=influora-api/src/test/java/com/influora/service/WalletServiceTest.java
FE_FILE=src/pages/creator-wallet.tsx
FE_TEST=src/pages/__tests__/creator-wallet.money-buckets.test.tsx
for f in "$WALLET_SVC" "$PAYOUT_REPO" "$WALLET_TEST" "$FE_FILE" "$FE_TEST"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — unavailable"; exit 2; }

PY=""
for c in "${PROOF_PYTHON:-}" python3 python py; do
  [ -n "$c" ] || continue
  command -v "$c" >/dev/null 2>&1 || continue
  "$c" -c "import sys" >/dev/null 2>&1 && { PY="$c"; break; }
done
[ -n "$PY" ] || { echo "· no working python interpreter on PATH — unavailable"; exit 2; }

WORK="$(mktemp -d 2>/dev/null)" || { echo "· cannot create a scratch dir — unavailable"; exit 2; }
cp "$WALLET_SVC" "$WORK/WalletService.java.orig" || { echo "· cannot back up $WALLET_SVC — unavailable"; rm -rf "$WORK"; exit 2; }
# Restored no matter how this script exits — the self-falsification step below deliberately
# breaks the real file on disk to prove the test suite notices.
trap 'cp "$WORK/WalletService.java.orig" "$WALLET_SVC" 2>/dev/null; rm -rf "$WORK" 2>/dev/null' EXIT

echo "· method markers: GATE-F0281-METHOD-START/END present in $WALLET_SVC"
if ! grep -q "GATE-F0281-METHOD-START" "$WALLET_SVC" || ! grep -q "GATE-F0281-METHOD-END" "$WALLET_SVC"; then
  echo "VERDICT: broken — the anchors this gate uses to swap in a known-bad implementation are"
  echo "         gone; self-falsification cannot run, so nothing below can be trusted (F-0281)"
  exit 1
fi
echo "  present"

# ---------------------------------------------------------------------------
# The frozen known-bad body — VERBATIM the pre-fix getSummaryForUser (git history, this
# gate's own preamble). escrowLocked reads the dead wallets.escrow_balance column;
# pendingPayouts is the FUNDED-milestone sum mislabeled as a withdrawal in flight.
# ---------------------------------------------------------------------------
cat > "$WORK/bad_method.txt" <<'JAVAEOF'
    // GATE-F0281-METHOD-START
    /**
     * Creator wallet summary — {@code pendingPayouts} is FUNDED milestone totals across the
     * creator's collaborations (money in escrow awaiting release to them).
     */
    @Transactional(readOnly = true)
    public WalletSummaryResponse getSummaryForUser(String userId) {
        BigDecimal pendingRaw =
                paymentMilestoneRepository.sumAmountByCreatorIdAndStatus(
                        userId, MilestoneStatus.FUNDED);
        final BigDecimal pendingPayouts =
                pendingRaw == null ? BigDecimal.ZERO : pendingRaw;
        // Creator-side escrowLocked derivation is out of scope for this fix (Track C targets only
        // the brand dashboard's escrowLocked figure) — still reads the dead `escrow_balance`
        // column here, unchanged from prior behavior.
        return walletRepository
                .findByOwnerId(userId)
                .map(wallet -> toSummaryResponse(wallet, wallet.getEscrowBalance(), pendingPayouts))
                .orElseGet(
                        () ->
                                new WalletSummaryResponse(
                                        BigDecimal.ZERO, BigDecimal.ZERO, pendingPayouts, null));
    }
    // GATE-F0281-METHOD-END
JAVAEOF

swap_in_bad() {
  "$PY" - "$WALLET_SVC" "$WORK/bad_method.txt" <<'PYEOF'
import sys
path, bad_path = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as f:
    lines = f.readlines()
with open(bad_path, encoding="utf-8") as f:
    bad_body = f.read()
start = end = None
for i, line in enumerate(lines):
    if "GATE-F0281-METHOD-START" in line:
        start = i
    if "GATE-F0281-METHOD-END" in line:
        end = i
        break
if start is None or end is None or end < start:
    sys.exit("markers not found in order")
new_lines = lines[:start] + [bad_body] + lines[end + 1:]
with open(path, "w", encoding="utf-8") as f:
    f.writelines(new_lines)
PYEOF
}

TESTS='WalletServiceTest#testGetSummaryForUserEscrowLockedIgnoresDeadColumn+testGetSummaryForUserPendingPayoutsIsInFlightPayoutSum'

# ---------------------------------------------------------------------------
# 1 · SELF-FALSIFICATION — swap the known-bad body onto disk, compile it for real, and run
#     the pinning tests. They MUST fail against the defect this gate exists to catch.
# ---------------------------------------------------------------------------
echo "· self-check: known-bad getSummaryForUser (verbatim F-0336/F-0281) is compiled and tested"
swap_in_bad || { echo "· could not write the known-bad body — unavailable"; exit 2; }
if ! grep -q "wallet.getEscrowBalance()" "$WALLET_SVC"; then
  echo "· known-bad swap did not take — unavailable"; exit 2
fi
bad_out=$(cd influora-api && mvn -q -o test -Dtest="$TESTS" -DfailIfNoSpecifiedTests=false 2>&1)
bad_rc=$?
if [ $bad_rc -eq 0 ]; then
  printf '%s\n' "$bad_out" | tail -30
  echo "· THIS GATE CANNOT FAIL: the pinning tests PASSED against the verbatim F-0336/F-0281"
  echo "  defect (escrowLocked = dead wallets.escrow_balance, pendingPayouts = FUNDED milestone"
  echo "  sum). Refusing to report a verdict about the real code from a check that has just"
  echo "  proved itself blind."
  echo "VERDICT: broken — this gate's own assertions no longer detect F-0281/F-0336"
  echo "NOT CHECKED: the real product code — this run never got that far"
  exit 1
fi
echo "  good — mvn test -Dtest=$TESTS fails against the known-bad body (rc=$bad_rc), as required"

# Restore the real file before checking it — the trap would also do this on exit, but the next
# leg needs the real file back NOW, not at process exit.
cp "$WORK/WalletService.java.orig" "$WALLET_SVC" || { echo "· could not restore $WALLET_SVC — unavailable"; exit 2; }

# ---------------------------------------------------------------------------
# 2 · THE REAL CODE — same tests, same JUnit run, against whatever is actually on disk.
# ---------------------------------------------------------------------------
echo "· mvn -o test -Dtest=$TESTS (real code on disk)"
real_out=$(cd influora-api && mvn -q -o test -Dtest="$TESTS" -DfailIfNoSpecifiedTests=false 2>&1)
real_rc=$?
if [ $real_rc -ne 0 ]; then
  printf '%s\n' "$real_out" | tail -60
  echo "VERDICT: broken — escrowLocked and/or pendingPayouts are not correctly derived for a"
  echo "         creator (F-0281/F-0336)"
  echo "NOT CHECKED: the rendered page — the vitest leg below was not reached because the"
  echo "             derivation that feeds it is already wrong"
  exit 1
fi
echo "  green — escrowLocked is the live FUNDED-milestone sum, pendingPayouts is the live"
echo "  in-flight Payout sum, neither reads the dead wallets.escrow_balance column"

# ---------------------------------------------------------------------------
# 3 · structural: the new in-flight-payout query exists and is not shadowed by a stray @Query
#     that would override name-derivation with something that forgot the confirmedAt predicate
#     (same drift class F-0234's gate guards on PayoutRepository's sibling method).
# ---------------------------------------------------------------------------
echo "· PayoutRepository declares sumAmountByCreatorUserIdAndConfirmedAtIsNull"
REPO_CODE=$(code_view "$PAYOUT_REPO") || { echo "$(code_why) - unavailable"; exit 2; }
if ! grep -q "sumAmountByCreatorUserIdAndConfirmedAtIsNull" "$REPO_CODE"; then
  echo "VERDICT: broken — the in-flight-payout sum query F-0281's pendingPayouts depends on is gone"
  exit 1
fi
echo "  present"

# ---------------------------------------------------------------------------
# 4 · THE RENDERED PAGE — real render, real tooltip content, real absent-vs-zero check.
# ---------------------------------------------------------------------------
echo "· vitest: creator-wallet.money-buckets.test.tsx — labels match buckets, absent ≠ zero"
if [ ! -x node_modules/.bin/vitest ] && [ ! -f node_modules/.bin/vitest ]; then
  echo "· node_modules/.bin/vitest not found — unavailable"
  echo "NOT CHECKED: the rendered tiles. The backend legs above DID pass."
  exit 2
fi
BUDGET="${PROOF_F0281_VITEST_TIMEOUT:-240}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
fe_out=$($TO node_modules/.bin/vitest run "$FE_TEST" --reporter=basic 2>&1)
fe_rc=$?
if [ $fe_rc -eq 124 ] || [ $fe_rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: the rendered tiles; the backend legs above DID pass"
  exit 2
fi
if [ $fe_rc -ne 0 ]; then
  printf '%s\n' "$fe_out" | tail -60
  echo "VERDICT: broken — the rendered creator wallet page does not honestly show the three money"
  echo "         buckets (wrong number under a label, a stale/mismatched definition, or a failed"
  echo "         fetch rendering as a fabricated ₹0) (F-0281)"
  exit 1
fi
printf '%s\n' "$fe_out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true
echo "  suite green"

echo "VERDICT: aligned (proved) — getSummaryForUser was EXECUTED (compiled and run under JUnit),"
echo "         not grepped: escrowLocked is the creator's live FUNDED-milestone sum (never the"
echo "         dead wallets.escrow_balance column) and pendingPayouts is the live in-flight Payout"
echo "         sum (never the FUNDED-milestone figure it used to be mislabeled as). The assertion"
echo "         table was proved falsifiable against the verbatim F-0336/F-0281 defect — compiled"
echo "         and run, not merely described — before any green was trusted. The rendered page's"
echo "         own suite confirms each tile's number sits under a definition that matches it, and"
echo "         that a failed fetch renders an honest \"—\" rather than a fabricated ₹0 indistinct"
echo "         from a creator who genuinely has none."
echo "NOT CHECKED: withdrawal-in-flight money whose EscrowHold was never bound to a collaboration"
echo "             at all (campaign-pool funding with no milestone yet) — that money is real but"
echo "             not attributable to any single creator today, and is not represented in any of"
echo "             the three tiles; live rendering against a running backend and a real MySQL"
echo "             database (H2/mocks only here); whether a future caller adds a THIRD path that"
echo "             debits Wallet.balance outside doProcessWithdrawal/doQueuePayout, which would"
echo "             leave pendingPayouts under-counting money actually in flight."
exit 0
